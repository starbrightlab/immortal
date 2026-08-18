/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * Records and plays the single "leave a note" voice memo to [NotesConfig.audioFile].
 * Uses the standard handset mic (RECORD_AUDIO) — the far-field array needs a Meta-
 * signed permission Immortal can't hold, which is fine for a short up-close memo.
 * One instance owns one recorder/player at a time; call [release] when done.
 */
class AudioNote(private val context: Context) {
  private val TAG = "ImmortalNote"
  private val MIC_OWNER = "audio-note"
  private var recorder: MediaRecorder? = null
  private var player: MediaPlayer? = null

  val isRecording: Boolean get() = recorder != null
  val isPlaying: Boolean get() = player?.isPlaying == true

  /** Begin recording to the note file (overwrites any previous memo). */
  fun startRecording(): Boolean = runCatching {
    stopPlaying()
    // Deliberate user action, so it outranks the camera stream's audio track and takes the
    // microphone from it. See [MicOwner] — nothing arbitrated this before, and a note recorded
    // while something else held the mic came out silent.
    if (!MicOwner.acquire(MIC_OWNER, MicOwner.PRIORITY_NOTE)) {
      Log.w(TAG, "microphone held by ${MicOwner.holder} — not recording")
      return@runCatching false
    }
    val file = NotesConfig.audioFile(context)
    @Suppress("DEPRECATION")
    val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
    file.delete() // start fresh; a leftover/partial file can't be appended to
    rec.setAudioSource(MediaRecorder.AudioSource.MIC)
    rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    // Explicit, widely-supported params. Without these some devices write a file
    // whose header the player later refuses ("records but won't play").
    rec.setAudioChannels(1)
    rec.setAudioSamplingRate(44100)
    rec.setAudioEncodingBitRate(128000)
    rec.setOutputFile(file.absolutePath)
    rec.prepare()
    rec.start()
    recordStartMs = System.currentTimeMillis()
    recorder = rec
    true
  }.getOrElse {
    Log.w(TAG, "startRecording failed", it)
    MicOwner.release(MIC_OWNER)
    false
  }

  private var recordStartMs = 0L

  /** Peak input level since the last poll, 0..32767. ~0 means the mic is hearing
   *  near-silence (speaker too far from the device). Drives the UI meter. */
  fun peakLevel(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

  /** Stop and finalize the recording. Returns true if a playable file resulted. */
  fun stopRecording(): Boolean {
    val r = recorder ?: return NotesConfig.hasAudioNote(context)
    recorder = null
    MicOwner.release(MIC_OWNER)
    // MediaRecorder.stop() throws if stopped almost immediately (no encoded frames),
    // leaving a corrupt file. Guard against a too-short tap.
    val tooShort = System.currentTimeMillis() - recordStartMs < 500
    val stopped = runCatching { r.stop() }.isSuccess
    runCatching { r.release() }
    if (!stopped || tooShort) {
      Log.w(TAG, "recording too short / stop failed — discarding")
      runCatching { NotesConfig.audioFile(context).delete() }
      return false
    }
    val f = NotesConfig.audioFile(context)
    Log.i(TAG, "recorded ${f.length()} bytes to ${f.absolutePath}")
    return f.exists() && f.length() > 0
  }

  /** Play the saved memo, invoking [onDone] when it finishes (or fails). */
  fun play(onDone: () -> Unit = {}) {
    val file = NotesConfig.audioFile(context)
    if (!file.exists() || file.length() == 0L) {
      Log.w(TAG, "play: no file (${file.length()} bytes)"); onDone(); return
    }
    runCatching {
      stopPlaying()
      val mp = MediaPlayer()
      mp.setAudioAttributes(
          AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      // Use a file descriptor — more reliable than a path string across devices.
      java.io.FileInputStream(file).use { fis -> mp.setDataSource(fis.fd) }
      mp.setOnCompletionListener { stopPlaying(); onDone() }
      mp.setOnErrorListener { _, what, extra ->
        Log.w(TAG, "MediaPlayer error what=$what extra=$extra"); stopPlaying(); onDone(); true
      }
      mp.prepare()
      mp.start()
      player = mp
    }.onFailure { Log.w(TAG, "play failed", it); onDone() }
  }

  fun stopPlaying() {
    player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
    player = null
  }

  fun release() {
    stopRecording()
    stopPlaying()
  }
}
