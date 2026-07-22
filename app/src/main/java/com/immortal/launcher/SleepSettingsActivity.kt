/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlinx.coroutines.delay
import kotlin.math.max
import org.json.JSONObject

class SleepSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { SleepSettingsScreen() } }
  }
}

@Composable
private fun SleepSettingsScreen() {
  val context = LocalContext.current
  val userLang = ImmortalSettings.load(context).language
  var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
  var remainingMs by remember { mutableLongStateOf(0L) }
  var running by remember { mutableStateOf(false) }
  var timerEndsAtMs by remember { mutableLongStateOf(0L) }

  LaunchedEffect(settings.sleepTimerEnabled) {
    if (!settings.sleepTimerEnabled) {
      remainingMs = 0L
      running = false
      timerEndsAtMs = 0L
    }
  }

  LaunchedEffect(running, timerEndsAtMs) {
    while (running && timerEndsAtMs > 0L) {
      val left = max(0L, timerEndsAtMs - System.currentTimeMillis())
      remainingMs = left
      if (left == 0L) {
        running = false
        timerEndsAtMs = 0L
        break
      }
      delay(1000L)
    }
  }

  val timerText = remember(remainingMs) {
    val total = remainingMs / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
  }

  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  fun startCountdown() {
    if (!settings.sleepTimerEnabled) return
    SleepScheduler.armSleepTimer(context)
    val durationMs = ScreensaverConfig.clampSleepTimer(settings.sleepTimerMin) * 60_000L
    timerEndsAtMs = System.currentTimeMillis() + durationMs
    remainingMs = durationMs
    running = true
  }

  fun stopCountdown() {
    SleepScheduler.cancelSleepTimer(context)
    remainingMs = 0L
    timerEndsAtMs = 0L
    running = false
  }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(Color(0xFF111111))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(com.immortal.launcher.i18n.I18n.translate("Sleep Timer", userLang), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
      Text(
          if (settings.sleepTimerEnabled) com.immortal.launcher.i18n.I18n.translate("Set the timer, then start the countdown.", userLang)
          else com.immortal.launcher.i18n.I18n.translate("Enable Sleep Timer below before starting.", userLang),
          color = Color(0xFF9A9A9A),
          fontSize = 15.sp,
          textAlign = TextAlign.Center,
      )

      Surface(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
          shape = RoundedCornerShape(22.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(com.immortal.launcher.i18n.I18n.translate("Countdown", userLang), color = Color.White, fontSize = 18.sp)
            Text(
                if (remainingMs > 0) timerText else "00:00",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
          }

          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(14.dp),
                modifier =
                    Modifier.weight(1f)
                        .focusRequester(firstFocus)
                        .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                          startCountdown()
                        },
            ) {
              Text(
                  com.immortal.launcher.i18n.I18n.translate("Start countdown", userLang),
                  color = Color.White,
                  fontSize = 18.sp,
                  fontWeight = FontWeight.SemiBold,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
              )
            }
            Surface(
                color = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                shape = RoundedCornerShape(14.dp),
                modifier =
                    Modifier.weight(1f)
                        .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                          stopCountdown()
                        },
            ) {
              Text(
                  com.immortal.launcher.i18n.I18n.translate("Stop timer", userLang),
                  color = Color.White,
                  fontSize = 18.sp,
                  fontWeight = FontWeight.SemiBold,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
              )
            }
          }
        }
      }

      SleepToggleRow("Sleep Timer", settings.sleepTimerEnabled) {
        SettingsDomains.screensaver.apply(context, JSONObject().put("sleepTimerEnabled", it))
        settings = ScreensaverConfig.load(context)
        if (!it) stopCountdown()
      }

      if (settings.sleepTimerEnabled) {
        SleepMinuteStepper(settings.sleepTimerMin) { v ->
          val c = ScreensaverConfig.clampSleepTimer(v)
          SettingsDomains.screensaver.apply(context, JSONObject().put("sleepTimerMin", c))
          settings = ScreensaverConfig.load(context)
          if (running) {
            SleepScheduler.armSleepTimer(context)
            val durationMs = c * 60_000L
            timerEndsAtMs = System.currentTimeMillis() + durationMs
            remainingMs = durationMs
          }
        }
      }

      SleepToggleRow("Pause audio before sleeping", settings.pauseAudioOnSleep) {
        SettingsDomains.screensaver.apply(context, JSONObject().put("pauseAudioOnSleep", it))
        settings = ScreensaverConfig.load(context)
      }

      SleepToggleRow("Close Immortal before sleeping", settings.closeAppOnSleep) {
        SettingsDomains.screensaver.apply(context, JSONObject().put("closeAppOnSleep", it))
        settings = ScreensaverConfig.load(context)
      }

      Spacer(Modifier.size(8.dp))
      Text(
          "When the timer reaches zero, Immortal pauses audio, closes itself, disables the screensaver, and locks the screen.",
          color = Color(0xFF9A9A9A),
          fontSize = 13.sp,
          textAlign = TextAlign.Center,
      )
    }
  }
  FolderBackButton(onClick = { activity?.finish() })
}

@Composable
private fun SleepToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onChange(!checked) }
              .padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = null)
  }
}

@Composable
private fun SleepMinuteStepper(minutes: Int, onChange: (Int) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> { onChange(max(1, minutes - 5)); true }
                    Key.DirectionRight -> { onChange(minutes + 5); true }
                    else -> false
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0x402E6BE6) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Turn off after", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onChange(max(1, minutes - 5)) }
    Text(
        "${minutes}m",
        color = if (focused) Color.White else Color(0xFFDDDDDD),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 64.dp),
    )
    ArrowButton("▶", focused) { onChange(minutes + 5) }
  }
}
