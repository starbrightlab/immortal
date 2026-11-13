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
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Calendar
import java.util.Locale

/**
 * Plays the ambient audio cues. Everything routes through the alarm/notification
 * stream so it's audible even when media is paused, and uses one-shot players that
 * release themselves on completion — these fire from a [android.content.BroadcastReceiver]
 * with no UI to own a player lifecycle.
 */
object ChimePlayer {
private const val TAG = "ImmortalChime"
private const val SHERPA_VOICE_PARAM = "sherpa_voice_name"

  private val ambientAttrs =
      AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()

  /** Play the soft hourly chime (res/raw/chime.mp3), releasing on completion. */
  fun playChime(context: Context) {
    runCatching {
      val vol = ChimeConfig.load(context).chimeVolume / 100f
      if (vol <= 0f) return
      val mp = MediaPlayer()
      mp.setAudioAttributes(ambientAttrs)
      val afd = context.resources.openRawResourceFd(R.raw.chime) ?: return
      afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
      mp.setVolume(vol, vol)
      mp.setOnCompletionListener { it.release() }
      mp.setOnErrorListener { p, _, _ -> p.release(); true }
      mp.prepare()
      mp.start()
    }.onFailure { Log.w(TAG, "chime playback failed", it) }
  }

  /** Ring for an elapsed kitchen timer: the chime sound a few times. Independent
   *  of the hourly-chime volume (you set a timer on purpose, so it always rings). */
  fun playTimerRing(context: Context, repeats: Int = 3) {
    val h = Handler(Looper.getMainLooper())
    for (i in 0 until repeats) {
      h.postDelayed({
        runCatching {
          val mp = MediaPlayer()
          mp.setAudioAttributes(ambientAttrs)
          val afd = context.resources.openRawResourceFd(R.raw.chime) ?: return@postDelayed
          afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
          mp.setOnCompletionListener { it.release() }
          mp.setOnErrorListener { p, _, _ -> p.release(); true }
          mp.prepare()
          mp.start()
        }.onFailure { Log.w(TAG, "timer ring failed", it) }
      }, i * 1400L)
    }
  }

  /** Ring for an incoming "ping the other room" (res/raw/ping.mp3), a few times at the
   *  ping volume from Sounds settings. Separate sound + volume from the kitchen timer. */
  fun playPing(context: Context, repeats: Int = 2) {
    val vol = (ChimeConfig.load(context).pingVolume / 100f).coerceIn(0f, 1f)
    if (vol <= 0f) return
    val h = Handler(Looper.getMainLooper())
    for (i in 0 until repeats) {
      h.postDelayed({
        runCatching {
          val mp = MediaPlayer()
          mp.setAudioAttributes(ambientAttrs)
          val afd = context.resources.openRawResourceFd(R.raw.ping) ?: return@postDelayed
          afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
          mp.setVolume(vol, vol)
          mp.setOnCompletionListener { it.release() }
          mp.setOnErrorListener { p, _, _ -> p.release(); true }
          mp.prepare()
          mp.start()
        }.onFailure { Log.w(TAG, "ping ring failed", it) }
      }, i * 1400L)
    }
  }

  /** Speak an arbitrary phrase (e.g. "Pasta timer done"). [volume] is 0.0-1.0 of the spoken
   *  stream and defaults to full — an announcement you asked for shouldn't be capped by the
   *  hourly-chime slider. A notify payload's `speak` passes its own volume through. */
  fun announce(context: Context, text: String, volume: Float = 1f) =
      speak(context, text, volumeOverride = volume.coerceIn(0f, 1f))

  /** Audition the currently selected spoken-time voice without using a clock phrase. */
  fun testVoice(context: Context, voiceName: String = ChimeConfig.load(context).spokenVoice) {
    val sample =
        if (voiceName.contains("ro_RO", ignoreCase = true) || voiceName.contains("ro-ro", ignoreCase = true)) {
          "Aceasta este vocea selectata pentru Immortal."
        } else {
          "This is the selected voice for Immortal."
        }
    speak(context, sample, voiceNameOverride = voiceName)
  }

  /** Speak the current time, e.g. "It's three o'clock" / "It's half past four".
   * Uses the platform TTS (fast, always present); Piper is overkill for a chime. */
  fun speakTime(context: Context, now: Calendar = Calendar.getInstance()) {
    val phrase = spokenTimePhrase(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
    speak(context, phrase)
  }

  /** Build the spoken phrase for [hour24]:[minute]. Pure + unit-testable. */
  fun spokenTimePhrase(hour24: Int, minute: Int): String {
    val names = arrayOf(
        "twelve", "one", "two", "three", "four", "five", "six",
        "seven", "eight", "nine", "ten", "eleven")
    val h12 = hour24 % 12
    val hourWord = names[h12]
    return when (minute) {
      0 -> "It's $hourWord o'clock"
      15 -> "It's quarter past $hourWord"
      30 -> "It's half past $hourWord"
      45 -> {
        val next = names[(h12 + 1) % 12]
        "It's quarter to $next"
      }
      else -> {
        val mm = if (minute < 10) "oh $minute" else "$minute"
        "It's $hourWord $mm"
      }
    }
  }

  /** Gentle sound marking sunrise. Two variants, chosen in Sounds settings:
   *  0 = "Morning" (golden_sunrise), 1 = "Rooster" (golden_sunrise_2). */
  fun playSunriseTone(context: Context) {
    val res = if (ChimeConfig.load(context).sunriseVariant == 1) R.raw.golden_sunrise_2 else R.raw.golden_sunrise
    playRaw(context, res)
  }

  /** Gentle sound marking sunset (res/raw/golden_sunset.mp3 — "Goodnight"). */
  fun playSunsetTone(context: Context) = playRaw(context, R.raw.golden_sunset)

  /** Generic golden-hour preview (used by the Sounds "Play tone" test button). */
  fun playGoldenHourTone(context: Context) = playSunriseTone(context)

  /** Play a bundled raw audio resource through the alarm stream at the golden volume. */
  private fun playRaw(context: Context, resId: Int) {
    runCatching {
      val vol = (ChimeConfig.load(context).goldenVolume / 100f).coerceIn(0f, 1f)
      if (vol <= 0f) return
      val mp = MediaPlayer()
      mp.setAudioAttributes(
          AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ALARM)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build())
      val afd = context.resources.openRawResourceFd(resId) ?: return
      afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
      mp.setVolume(vol, vol)
      mp.setOnCompletionListener { it.release() }
      mp.setOnErrorListener { p, _, _ -> p.release(); true }
      mp.prepare()
      mp.start()
    }.onFailure { Log.w(TAG, "golden-hour tone failed", it) }
  }

  private fun speak(
      context: Context,
      text: String,
      volumeOverride: Float? = null,
      voiceNameOverride: String? = null,
  ) {
    runCatching {
      val cfg = ChimeConfig.load(context)
      val vol = (volumeOverride ?: (cfg.spokenVolume / 100f)).coerceIn(0f, 1f)
      if (vol <= 0f) return
      var tts: TextToSpeech? = null
      tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          val t = tts ?: return@TextToSpeech
          val voiceName = voiceNameOverride ?: cfg.spokenVoice
          // Apply the chosen voice; if none chosen, pick the highest-quality voice the
          // device has so spoken time sounds as good as possible by default.
          runCatching {
            val lang = Locale.getDefault().language
            val v =
                if (voiceName.isNotBlank()) t.voices?.firstOrNull { it.name == voiceName }
                else t.voices
                    ?.filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }
            if (v != null) {
              t.language = v.locale
              t.voice = v
            } else {
              t.language = Locale.getDefault()
            }
          }
          t.setOnUtteranceProgressListener(
              object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) {
                  Handler(Looper.getMainLooper()).postDelayed({ runCatching { t.shutdown() } }, 100)
                }
                override fun onDone(id: String?) {
                  Handler(Looper.getMainLooper()).postDelayed({ runCatching { t.shutdown() } }, 100)
                }
              })
          val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol)
            if (voiceName.isNotBlank()) putString(SHERPA_VOICE_PARAM, voiceName)
          }
          t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "chime_time")
        } else {
          runCatching { tts?.shutdown() }
        }
      }
    }.onFailure { Log.w(TAG, "spoken time failed", it) }
  }
}
