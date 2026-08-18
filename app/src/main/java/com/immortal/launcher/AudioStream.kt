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
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The camera stream's audio track: microphone → AAC-LC, emitted frame by frame for [AacRtp] to
 * packetise. Phase 3 of `docs/design/camera-streaming.md`.
 *
 * Two rules matter more than the encoding:
 *
 *  - **Mic mute means silence on the wire.** Immortal publishes a `mic_mute` switch to Home
 *    Assistant. A stream that keeps sending audio while Home Assistant says the microphone is
 *    muted is both a contradiction and a genuine privacy surprise, so muting stops audio leaving
 *    the device entirely — see [shouldEmit].
 *  - **A person talking outranks a background stream.** The microphone is arbitrated through
 *    [MicOwner]; if the intercom takes it mid-stream, the audio track stops and the video carries
 *    on rather than both fighting over a device only one can have.
 */
class AudioStream(
    private val appContext: Context,
    private val onFrame: (frame: ByteArray, presentationTimeUs: Long) -> Unit,
) {
  @Volatile private var running = false
  private var record: AudioRecord? = null
  private var codec: MediaCodec? = null
  private var worker: Thread? = null

  private val audio by lazy {
    appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  }

  fun isRunning(): Boolean = running

  /** Start capturing. False when the permission is missing, or something louder holds the mic. */
  fun start(): Boolean {
    if (running) return true
    if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.i(TAG, "no record-audio permission — streaming without sound")
      return false
    }
    if (!MicOwner.acquire(OWNER, MicOwner.PRIORITY_STREAM)) {
      Log.i(TAG, "microphone held by ${MicOwner.holder} — streaming without sound")
      return false
    }
    return runCatching {
          val minBuf =
              AudioRecord.getMinBufferSize(
                  SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
          val rec =
              AudioRecord(
                  MediaRecorder.AudioSource.MIC,
                  SAMPLE_RATE,
                  AudioFormat.CHANNEL_IN_MONO,
                  AudioFormat.ENCODING_PCM_16BIT,
                  maxOf(minBuf, BUFFER_BYTES))
          record = rec

          val enc = MediaCodec.createEncoderByType(MIME)
          codec = enc
          val format =
              MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_BYTES)
              }
          enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
          enc.start()
          rec.startRecording()

          running = true
          worker = Thread(::pump, "immortal-audio").apply { isDaemon = true; start() }
          Log.i(TAG, "audio started at ${SAMPLE_RATE}Hz")
          true
        }
        .onFailure {
          Log.w(TAG, "audio start failed", it)
          stop()
        }
        .getOrDefault(false)
  }

  fun stop() {
    running = false
    worker?.interrupt()
    runCatching { record?.stop() }
    runCatching { record?.release() }
    runCatching { codec?.stop() }
    runCatching { codec?.release() }
    record = null
    codec = null
    worker = null
    MicOwner.release(OWNER)
    Log.i(TAG, "audio stopped")
  }

  /**
   * Read PCM, encode, emit. Muting doesn't tear the pipeline down — it just stops frames leaving,
   * so unmuting resumes immediately with the timeline intact rather than renegotiating the track.
   */
  private fun pump() {
    val buf = ByteArray(BUFFER_BYTES)
    val info = MediaCodec.BufferInfo()
    while (running) {
      if (!MicOwner.holds(OWNER)) {
        Log.i(TAG, "microphone taken by ${MicOwner.holder} — stopping audio")
        break
      }
      val rec = record ?: break
      val enc = codec ?: break
      val read = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
      if (read <= 0) continue
      runCatching {
            val inIndex = enc.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
              enc.getInputBuffer(inIndex)?.apply {
                clear()
                put(buf, 0, read)
              }
              enc.queueInputBuffer(inIndex, 0, read, System.nanoTime() / 1000, 0)
            }
            var outIndex = enc.dequeueOutputBuffer(info, TIMEOUT_US)
            while (outIndex >= 0) {
              if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val out = enc.getOutputBuffer(outIndex)
                if (out != null) {
                  out.position(info.offset)
                  out.limit(info.offset + info.size)
                  val frame = ByteArray(info.size).also { out.get(it) }
                  if (shouldEmit(micMuted = audio.isMicrophoneMute, holdsMic = true)) {
                    onFrame(frame, info.presentationTimeUs)
                  }
                }
              }
              enc.releaseOutputBuffer(outIndex, false)
              outIndex = enc.dequeueOutputBuffer(info, 0)
            }
          }
          .onFailure {
            if (running) Log.w(TAG, "audio encode failed", it)
          }
    }
    if (running) stop()
  }

  companion object {
    private const val TAG = "ImmortalAudio"
    private const val MIME = "audio/mp4a-latm"
    private const val OWNER = "camera-stream"

    /** 16 kHz mono, matching [LanAudio]: speech-grade, and cheap on a fanless device. */
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    private const val BIT_RATE = 32_000
    private const val BUFFER_BYTES = 4096
    private const val TIMEOUT_US = 10_000L

    /**
     * Whether an encoded frame may go on the wire. Muting the microphone must mean **no audio
     * leaves the device** — anything else contradicts the switch Home Assistant is showing.
     */
    fun shouldEmit(micMuted: Boolean, holdsMic: Boolean): Boolean = !micMuted && holdsMic
  }
}
