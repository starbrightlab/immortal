/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.SampleAppTheme
import org.json.JSONObject

/**
 * Dedicated settings screen for the digital clock. Opened from a "Clock" tile
 * in the launcher's Settings folder. The clock-specific preferences (enable, style,
 * color, font, size, layout, background, glow, show date, show seconds) render from
 * the `digitalclock` registry domain — the same specs the phone remote uses. The
 * screensaver-activation toggles route through the `immortal` domain so its `onApplied`
 * fires. The back-gesture section (accessibility service, overlay permission, test) is
 * genuinely bespoke system-action UI the registry can't model, so it stays hand-built.
 */
class ClockSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ClockSettingsScreen() } }
  }
}

@Composable
private fun ClockSettingsScreen() {
  val context = LocalContext.current
  val userLang = ImmortalSettings.load(context).language
  var clockConfig by remember { mutableStateOf(DigitalClockConfig.load(context)) }
  var settings by remember { mutableStateOf(ImmortalSettings.load(context)) }

  // Re-read on resume so a change in another screen is reflected here.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        clockConfig = DigitalClockConfig.load(context)
        settings = ImmortalSettings.load(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { e ->
                  if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyUp) activity?.finish()
                    true
                  } else false
                }
                .background(Color(0xFF101012))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
    ) {
      Column(modifier = Modifier.widthIn(max = 1100.dp).focusRequester(firstFocus).focusGroup()) {
        Text(com.immortal.launcher.i18n.I18n.translate("Clock", userLang), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Text(
            com.immortal.launcher.i18n.I18n.translate("Choose the digital clock and how it looks on the screensaver.", userLang),
            color = Color(0xFF9A9A9A),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.size(26.dp))

        // Clock preferences render from the `digitalclock` registry domain. Apply routes through
        // the domain so its onApplied (reaffirm the dream when `enabled` toggles) fires here too.
        SettingsList(SettingsDomains.digitalclock, clockConfig) { k, v ->
          SettingsDomains.digitalclock.apply(context, JSONObject().put(k, v))
          clockConfig = DigitalClockConfig.load(context)
        }

        // Preview button — opens fullscreen preview (a bespoke action, not a setting).
        SectionLabel(com.immortal.launcher.i18n.I18n.translate("Preview", userLang))
        Card {
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp).clickable {
                context.startActivity(
                    Intent(context, DigitalClockPreviewActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              },
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(com.immortal.launcher.i18n.I18n.translate("Preview", userLang), color = Color.White, fontSize = 17.sp)
              Text(
                  com.immortal.launcher.i18n.I18n.translate("See the clock full-screen with your current settings.", userLang),
                  color = Color(0xFF9A9A9A),
                  fontSize = 13.sp,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.tvFocusable(RoundedCornerShape(10.dp)) {
                  context.startActivity(
                      Intent(context, DigitalClockPreviewActivity::class.java)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            ) {
              Text(
                  com.immortal.launcher.i18n.I18n.translate("Preview", userLang),
                  color = Color.White,
                  fontSize = 15.sp,
                  modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
              )
            }
          }
        }

        Text(
            com.immortal.launcher.i18n.I18n.translate("Changes apply immediately. Tap the clock icon on the home screen to start the screensaver.", userLang),
            color = Color(0xFF7C7C7C),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
        )
      }
    }
    FolderBackButton(onClick = { activity?.finish() })
  }
}
