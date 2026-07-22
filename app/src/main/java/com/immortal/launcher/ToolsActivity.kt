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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * The full-page "Tools" screen, opened from the built-in Tools tile on the home grid. A plain
 * launcher for the in-process tool features. See docs/design/feature-integration.md (step 3).
 *
 * Tools that already have their own Activity (cameras, countdowns, lamp, bedtime, intercom) launch
 * it directly. Tools whose UI formerly lived only in ForkHome open as an in-page overlay hosted here
 * (see [HomeToolOverlays]); this change adds the sky/space pair (ISS passes, aurora outlook).
 */
class ToolsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ToolsRoot(::finish) } }
  }
}

/** Which in-page tool overlay is showing over the list (or [ToolPage.LIST] for the list itself). */
private enum class ToolPage {
  LIST,
  ISS,
  AURORA,
  SPEEDTEST,
  TIMERS,
  NOTES,
  CONVERTER,
}

@Composable
private fun ToolsRoot(onExit: () -> Unit) {
  var page by remember { mutableStateOf(ToolPage.LIST) }
  when (page) {
    ToolPage.LIST -> ToolsScreen(onExit = onExit, onOpen = { page = it })
    ToolPage.ISS -> IssOverlay { page = ToolPage.LIST }
    ToolPage.AURORA -> AuroraOverlay { page = ToolPage.LIST }
    ToolPage.SPEEDTEST -> SpeedTestOverlay { page = ToolPage.LIST }
    ToolPage.TIMERS -> TimersOverlay { page = ToolPage.LIST }
    ToolPage.NOTES -> NotesOverlay { page = ToolPage.LIST }
    ToolPage.CONVERTER -> ConverterOverlay { page = ToolPage.LIST }
  }
}

@Composable
private fun ToolsScreen(onExit: () -> Unit, onOpen: (ToolPage) -> Unit) {
  val context = LocalContext.current
  val userLang = ImmortalSettings.load(context).language
  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) onExit()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusRequester(firstFocus).focusGroup()) {
      Text(
          com.immortal.launcher.i18n.I18n.translate("Tools", userLang),
          color = Color.White,
          fontSize = 34.sp,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          com.immortal.launcher.i18n.I18n.translate("Extra utilities for your Portal.", userLang),
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))
      Card {
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Cameras", userLang),
            com.immortal.launcher.i18n.I18n.translate("View a saved RTSP camera feed", userLang)
        ) {
          context.startActivity(Intent(context, CameraViewerActivity::class.java))
        }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Countdowns", userLang),
            com.immortal.launcher.i18n.I18n.translate("Days until birthdays and events", userLang)
        ) {
          context.startActivity(Intent(context, CountdownSettingsActivity::class.java))
        }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Lamp", userLang),
            com.immortal.launcher.i18n.I18n.translate("A full-screen warm-white light", userLang)
        ) {
          context.startActivity(Intent(context, LampActivity::class.java))
        }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Bedtime story", userLang),
            com.immortal.launcher.i18n.I18n.translate("Public-domain tales read aloud", userLang)
        ) {
          context.startActivity(Intent(context, BedtimeStoryActivity::class.java))
        }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Intercom", userLang),
            com.immortal.launcher.i18n.I18n.translate("Talk to another Portal on your Wi-Fi", userLang)
        ) {
          context.startActivity(Intent(context, IntercomActivity::class.java))
        }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Timers", userLang),
            com.immortal.launcher.i18n.I18n.translate("Kitchen timers with a live countdown", userLang)
        ) { onOpen(ToolPage.TIMERS) }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Leave a note", userLang),
            com.immortal.launcher.i18n.I18n.translate("A sticky note or a quick voice memo", userLang)
        ) { onOpen(ToolPage.NOTES) }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Converter", userLang),
            com.immortal.launcher.i18n.I18n.translate("Units and currency", userLang)
        ) { onOpen(ToolPage.CONVERTER) }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("ISS passes", userLang),
            com.immortal.launcher.i18n.I18n.translate("When the space station flies over", userLang)
        ) { onOpen(ToolPage.ISS) }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Aurora outlook", userLang),
            com.immortal.launcher.i18n.I18n.translate("Northern-lights chance for your location", userLang)
        ) { onOpen(ToolPage.AURORA) }
        ToolRow(
            com.immortal.launcher.i18n.I18n.translate("Speed test", userLang),
            com.immortal.launcher.i18n.I18n.translate("Check your internet speed (Cloudflare)", userLang)
        ) { onOpen(ToolPage.SPEEDTEST) }
      }
    }
  }
}

@Composable
private fun ToolRow(title: String, subtitle: String, onOpen: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().tvFocusableRow { onOpen() }.padding(18.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, color = Color.White, fontSize = 17.sp)
      Text(
          subtitle,
          color = Color(0xFF9A9A9A),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp),
      )
    }
    Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
  }
}
