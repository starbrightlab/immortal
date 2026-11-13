/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Debug-only: run [WakeWordDetector] over a 16 kHz mono PCM16 WAV in the app's files dir, so the
 * on-device pipeline can be checked without acoustics.
 *   adb shell am broadcast -n com.immortal.launcher.debug/com.immortal.launcher.WakeWordProbeReceiver \
 *       --es wav okay_nabu.wav --es model okay_nabu
 */
class WakeWordProbeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val file = File(context.getExternalFilesDir(null), intent.getStringExtra("wav") ?: return)
    val model = intent.getStringExtra("model") ?: "okay_nabu"
    val bytes = file.readBytes()
    val pcm = ShortArray((bytes.size - 44) / 2) { i ->
      ((bytes[44 + 2 * i + 1].toInt() shl 8) or (bytes[44 + 2 * i].toInt() and 0xFF)).toShort()
    }
    WakeWordDetector(context, model).use { d ->
      // Past the detector's one-second start-up cool-off first, as a live mic would be.
      val silence = ShortArray(24000)
      d.process(silence, 0, silence.size)
      var detections = 0
      var i = 0
      while (i < pcm.size) {
        val n = minOf(1024, pcm.size - i)
        if (d.process(pcm, i, n)) detections++
        i += n
      }
      Log.i("ImmortalWakeProbe", "${file.name} model=$model samples=${pcm.size} peak=${d.peakProbability} detections=$detections")
    }
  }
}
