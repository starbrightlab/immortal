/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The sunrise wake light: the screen brightens gradually from a deep ember through warm
 * amber to bright daylight over the chosen ramp, optionally finishing with a soft chime
 * crescendo. People pay real money for a wake light; here the always-on panel is one.
 *
 * Launched by [SunriseReceiver] at the alarm time. Tap anywhere to dismiss.
 */
class WakeLightActivity : ComponentActivity() {
  companion object {
    const val EXTRA_RAMP_MIN = "ramp_min"
    const val EXTRA_CHIME = "chime"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Show over the lock screen and wake the device.
    window.addFlags(
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

    val rampMin = intent.getIntExtra(EXTRA_RAMP_MIN, 20).coerceIn(1, 60)
    val chime = intent.getBooleanExtra(EXTRA_CHIME, true)
    setContent { WakeLightScreen(rampMin, chime, ::applyBrightness) { finish() } }
  }

  private fun applyBrightness(level: Float) {
    window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.02f, 1f) }
  }
}

@Composable
private fun WakeLightScreen(rampMin: Int, chime: Boolean, onBrightness: (Float) -> Unit, onDismiss: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // 0..1 progress through the ramp, animated over the full duration.
  var target by remember { mutableFloatStateOf(0f) }
  val progress by animateFloatAsState(
      targetValue = target,
      animationSpec = tween(durationMillis = rampMin * 60_000, easing = LinearEasing),
      label = "sunriseRamp",
  )
  var chimed by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { target = 1f }

  // Drive the hardware backlight up alongside the on-screen colour.
  LaunchedEffect(progress) {
    onBrightness(0.05f + progress * 0.95f)
    // Soft chime as we approach full light.
    if (chime && !chimed && progress >= 0.85f) {
      chimed = true
      runCatching { ChimePlayer.playSunriseTone(context) }
    }
  }

  // Ember -> amber -> warm daylight.
  val ember = Color(0xFF2A0E00)
  val amber = Color(0xFFFF8A3D)
  val day = Color(0xFFFFF4E0)
  val color = if (progress < 0.5f) lerp(ember, amber, progress * 2f) else lerp(amber, day, (progress - 0.5f) * 2f)

  Box(
      modifier = Modifier.fillMaxSize().background(color).clickable { onDismiss() },
      contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(color = Color(0x33000000), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(28.dp)) {
      Text("☀ Good morning — tap to dismiss",
          color = Color(0xCCFFFFFF), fontSize = 16.sp,
          modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
  }
}
