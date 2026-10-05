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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lamp mode — fills the always-on panel with warm white at a chosen brightness so a
 * Portal becomes an instant nightlight / reading light / video-call fill light. The big
 * panel was a wasted light source; this puts it to work.
 *
 * Tap anywhere to show/hide the controls; the screen is forced bright and kept awake
 * while the lamp is on. The window brightness override is released when you leave.
 */
class LampActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent { com.immortal.launcher.ui.theme.SampleAppTheme(darkTheme = true) { LampScreen(::applyBrightness) } }
  }

  /** Drive the panel directly via the window so the lamp ignores the system auto-dim. */
  private fun applyBrightness(level: Float) {
    window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.05f, 1f) }
  }
}

@Composable
private fun LampScreen(onBrightness: (Float) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val userLang = ImmortalSettings.load(context).language
  var brightness by remember { mutableFloatStateOf(0.9f) }
  var warmth by remember { mutableFloatStateOf(0.6f) } // 0 = white, 1 = candle
  var showControls by remember { mutableStateOf(true) }
  // Red night-light: a deep red panel preserves dark-adapted night vision.
  var red by remember { mutableStateOf(false) }

  // Warm-white: pull the blue (and a touch of green) down as warmth rises.
  val g = (255 - warmth * 60).toInt().coerceIn(0, 255)
  val b = (255 - warmth * 170).toInt().coerceIn(0, 255)
  val lampColor = if (red) Color(0xFFFF0000) else Color(255, g, b)

  androidx.compose.runtime.LaunchedEffect(brightness) {
    onBrightness(brightness)
  }

  Box(
      modifier = Modifier.fillMaxSize().background(lampColor)
          .clickable { showControls = !showControls },
      contentAlignment = Alignment.Center,
  ) {
    if (showControls) {
      Surface(color = Color(0x66000000), shape = RoundedCornerShape(20.dp),
          modifier = Modifier.widthIn(max = 520.dp).padding(24.dp)) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(com.immortal.launcher.i18n.I18n.translate("💡 Lamp", userLang), color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth())
          Text(com.immortal.launcher.i18n.I18n.translate("Brightness", userLang), color = Color.White, fontSize = 15.sp)
          Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.05f..1f)
          if (!red) {
            Text(com.immortal.launcher.i18n.I18n.translate("Warmth", userLang), color = Color.White, fontSize = 15.sp)
            Slider(value = warmth, onValueChange = { warmth = it }, valueRange = 0f..1f)
          }
          // Night-light toggle: switch the whole panel to deep red for night use,
          // which preserves dark-adapted vision. Brightness still applies.
          Surface(
              color = if (red) Color(0x55FF3B30) else Color(0x33FFFFFF),
              shape = RoundedCornerShape(16.dp),
              modifier = Modifier.fillMaxWidth().clickable { red = !red },
          ) {
            Text(
                if (red) com.immortal.launcher.i18n.I18n.translate("🔴 Red night-light", userLang) else com.immortal.launcher.i18n.I18n.translate("White light", userLang),
                color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
            )
          }
          // Sleep button: genuinely turns the screen off via device-admin lockNow(),
          // exactly like the home screen's moon sleep button (ScreenControl.sleep).
          // The Portal has no keyguard, so a tap wakes it straight back to the lamp.
          Surface(
              color = Color(0x33FFFFFF), shape = RoundedCornerShape(16.dp),
              modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                  .clickable { ScreenControl.sleep(context) },
          ) {
            Text(com.immortal.launcher.i18n.I18n.translate("🌙 Sleep", userLang), color = Color.White, fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
          }
          Text("Tap anywhere to hide these controls. Back to exit.",
              color = Color(0xCCFFFFFF), fontSize = 13.sp, textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}
