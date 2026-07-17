/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native Android TTS out of the Portal's own speakers, exposed over the Fleet
 * HTTP API at `POST /speak`. This is Star's mouth: the NUC sends reply text
 * here instead of depending on a browser SPA being foreground to run
 * speechSynthesis.
 *
 * The engine is initialized lazily on first use (on the main looper, as TTS
 * requires) and kept warm. [isSpeaking] is exposed via `GET /speak` so the NUC
 * can hold the face in the "speaking" state until the utterance actually ends.
 */
object StarVoice {
  private const val TAG = "ImmortalStarVoice"
  private const val INIT_TIMEOUT_MS = 5000L

  @Volatile private var tts: TextToSpeech? = null
  @Volatile private var ready = false
  @Volatile var speaking = false
    private set

  private val utteranceSeq = AtomicInteger(0)

  /** Blocking lazy init; safe to call from an HTTP handler thread. */
  private fun engine(context: Context): TextToSpeech? {
    tts?.let { if (ready) return it }
    synchronized(this) {
      tts?.let { if (ready) return it }
      val latch = CountDownLatch(1)
      var status = TextToSpeech.ERROR
      Handler(Looper.getMainLooper()).post {
        tts =
            TextToSpeech(context.applicationContext) { s ->
              status = s
              latch.countDown()
            }
      }
      if (!latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) || status != TextToSpeech.SUCCESS) {
        Log.w(TAG, "TTS init failed (status=$status)")
        runCatching { tts?.shutdown() }
        tts = null
        return null
      }
      val t = tts!!
      runCatching { t.language = Locale.US }
      t.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
              speaking = true
            }
            override fun onDone(utteranceId: String?) {
              speaking = false
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
              speaking = false
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
              speaking = false
            }
          })
      ready = true
      Log.i(TAG, "TTS engine ready: ${t.defaultEngine}")
      return t
    }
  }

  /**
   * Queue [text] for speech. Returns an error code for the HTTP layer, or null
   * on success. QUEUE_FLUSH: a new line from Star supersedes a stale one.
   */
  fun speak(context: Context, text: String, rate: Float, pitch: Float): String? {
    val t = engine(context) ?: return "tts_unavailable"
    runCatching { t.setSpeechRate(rate.coerceIn(0.1f, 4f)) }
    runCatching { t.setPitch(pitch.coerceIn(0.1f, 2f)) }
    val id = "star-${utteranceSeq.incrementAndGet()}"
    val result = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    if (result != TextToSpeech.SUCCESS) {
      Log.w(TAG, "speak() returned $result")
      return "speak_failed"
    }
    speaking = true // listener will confirm/clear; set eagerly so an immediate GET sees it
    return null
  }

  fun stop() {
    runCatching { tts?.stop() }
    speaking = false
    stopPlayback()
  }

  // --- WAV playback (`POST /play`) ------------------------------------------
  // The Portal's on-device TTS (Facebook's assistant service) accepts speak()
  // and synthesizes nothing — its backend is gone. So the NUC synthesizes with
  // Piper and ships a WAV here; the Portal is just the speaker.

  @Volatile var playing = false
    private set

  private var player: android.media.MediaPlayer? = null

  /** Play a WAV/audio file through the Portal speakers. Returns error code or null. */
  fun play(file: java.io.File): String? {
    synchronized(this) {
      stopPlayback()
      val mp = android.media.MediaPlayer()
      return try {
        mp.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
        mp.setDataSource(file.path)
        mp.prepare()
        mp.setOnCompletionListener {
          playing = false
          runCatching { it.release() }
          runCatching { file.delete() }
          synchronized(this) { if (player === mp) player = null }
        }
        mp.setOnErrorListener { p, what, extra ->
          Log.w(TAG, "playback error what=$what extra=$extra")
          playing = false
          runCatching { p.release() }
          synchronized(this) { if (player === mp) player = null }
          true
        }
        player = mp
        playing = true
        mp.start()
        null
      } catch (t: Throwable) {
        Log.w(TAG, "play failed", t)
        playing = false
        runCatching { mp.release() }
        "play_failed"
      }
    }
  }

  private fun stopPlayback() {
    synchronized(this) {
      player?.let {
        runCatching { it.stop() }
        runCatching { it.release() }
      }
      player = null
      playing = false
    }
  }
}
