/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * Settings for the Home Assistant voice satellite ([VoiceSatelliteService]): the Portal offers
 * itself to Home Assistant's Assist as a Wyoming satellite, like a Voice PE. Off by default — it
 * keeps the microphone open while on.
 */
object VoiceConfig {
  private const val PREFS = "immortal_voice"

  const val DEFAULT_PORT = 10700

  data class Settings(
      val enabled: Boolean = false,
      // Play a short tone when the wake word is heard, so you know it's listening.
      val wakeSound: Boolean = true,
      // Show what you said and Home Assistant's answer on screen.
      val showTranscript: Boolean = true,
      // Volume of answers, announcements and the wake tone, 0..100 % of the alarm stream.
      val voiceVolume: Int = 100,
      // Where the wake word is detected: [WAKE_SERVER] streams the microphone to Home Assistant
      // (openWakeWord there); a model name detects it on this Portal and only sends audio after.
      val wakeWord: String = WAKE_SERVER,
  )

  const val WAKE_SERVER = "server"

  /** [v] if it names a wake word mode, else null (a typo leaves the setting alone). */
  fun coerceWakeWord(v: String?): String? =
      v?.takeIf { it == WAKE_SERVER || it in WakeWordDetector.MODELS }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(c: Context): Settings {
    val p = prefs(c)
    return Settings(
        enabled = p.getBoolean("enabled", false),
        wakeSound = p.getBoolean("wake_sound", true),
        showTranscript = p.getBoolean("show_transcript", true),
        voiceVolume = p.getInt("voice_volume", 100).coerceIn(0, 100),
        wakeWord = coerceWakeWord(p.getString("wake_word", null)) ?: WAKE_SERVER,
    )
  }

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

  fun setWakeSound(c: Context, on: Boolean) = prefs(c).edit().putBoolean("wake_sound", on).apply()

  fun setShowTranscript(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("show_transcript", on).apply()

  fun setVoiceVolume(c: Context, v: Int) =
      prefs(c).edit().putInt("voice_volume", v.coerceIn(0, 100)).apply()

  fun setWakeWord(c: Context, v: String) {
    val w = coerceWakeWord(v) ?: return
    prefs(c).edit().putString("wake_word", w).apply()
  }
}
