/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous raw-PCM microphone stream from the Portal's mic array, exposed over
 * the Fleet HTTP API at `GET /ear/stream`. Powers the "Star can hear you"
 * pipeline: the NUC consumes this stream and runs wake-word + whisper on it, so
 * the Portal needs no on-device speech models.
 *
 * Format: 16 kHz, mono, 16-bit little-endian PCM, no container — exactly what
 * openWakeWord/whisper want, and trivial to parse on the far end. The stream
 * runs until the client disconnects (`Connection: close` framing); the mic is
 * released the moment the socket drops.
 *
 * Privacy: the hardware privacy switch electrically cuts the mics — with it
 * engaged this stream produces silence, same honest affordance as StarEye.
 */
object StarEar {
  private const val TAG = "ImmortalStarEar"
  const val SAMPLE_RATE = 16000

  /** Only one streamer at a time — the mic is exclusive anyway. */
  private val busy = AtomicBoolean(false)

  fun hasPermission(context: Context): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED

  fun isBusy(): Boolean = busy.get()

  /**
   * Blocking: pumps PCM to [out] until the client disconnects or the mic dies.
   * Returns false if the stream could not start (busy / mic init failed) —
   * caller maps that to an HTTP error. Once audio flows, errors just end the
   * stream (the client reconnects).
   */
  fun stream(out: OutputStream): Boolean {
    if (!busy.compareAndSet(false, true)) {
      Log.i(TAG, "stream already in flight")
      return false
    }
    var record: AudioRecord? = null
    try {
      record = openRecord() ?: return false
      record.startRecording()
      if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        Log.w(TAG, "startRecording did not enter RECORDING state")
        return false
      }
      Log.i(TAG, "mic stream started")
      // 100ms chunks — small enough for snappy wake detection, big enough to
      // keep the socket write rate trivial.
      val buf = ByteArray(SAMPLE_RATE / 10 * 2)
      while (true) {
        val n = record.read(buf, 0, buf.size)
        if (n <= 0) {
          Log.w(TAG, "mic read returned $n; ending stream")
          break
        }
        out.write(buf, 0, n)
        out.flush()
      }
      return true
    } catch (t: Throwable) {
      // Client disconnect lands here as an IOException on write — normal end.
      Log.i(TAG, "mic stream ended: ${t.message}")
      return true
    } finally {
      runCatching { record?.stop() }
      runCatching { record?.release() }
      busy.set(false)
      Log.i(TAG, "mic released")
    }
  }

  /** VOICE_RECOGNITION source first (no aggressive AGC), plain MIC fallback. */
  private fun openRecord(): AudioRecord? {
    val minBuf =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) {
      Log.w(TAG, "getMinBufferSize failed: $minBuf")
      return null
    }
    for (source in
        intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
      val r =
          runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, SAMPLE_RATE)) // >= 0.5s of headroom
              }
              .getOrNull()
      if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
      runCatching { r?.release() }
    }
    Log.w(TAG, "no AudioRecord source initialized")
    return null
  }
}
