/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * Activity wrappers for the Immortal settings sub-screens, so each is reached by a `NavSpec`
 * (launching an Activity) like every other settings sub-screen — collapsing the old in-file
 * `mutableStateOf` navigation onto the one nav model the Screensaver screen already uses. The screen
 * composables themselves live in [ImmortalSettingsActivity]; these are thin hosts (`onBack` = finish).
 */
class MultiRoomActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { MultiRoomScreen(onBack = { finish() }) } }
  }
}

class MqttActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { MqttScreen(onBack = { finish() }) } }
  }
}

class DeviceHealthActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { DeviceHealthScreen(onBack = { finish() }) } }
  }
}

class BootAppsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SampleAppTheme(darkTheme = true) {
        // The selection state lived in the parent screen before; the Activity owns it now.
        val context = LocalContext.current
        var selected by remember { mutableStateOf(BootLaunch.packages(context).toSet()) }
        BootAppsScreen(
            selected = selected,
            onToggle = { pkg ->
              selected = if (pkg in selected) selected - pkg else selected + pkg
              BootLaunch.setPackages(context, selected.toList())
            },
            onBack = { finish() },
        )
      }
    }
  }
}
