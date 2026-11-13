/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Settings for the Home Assistant voice satellite, with its live connection state. */
class VoiceSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { VoiceSettingsScreen() } }
  }
}

@Composable
private fun VoiceSettingsScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  var settings by remember { mutableStateOf(VoiceConfig.load(context)) }
  var status by remember { mutableStateOf(VoiceStatus.text) }
  LaunchedEffect(Unit) {
    while (true) {
      status = VoiceStatus.text
      delay(1000)
    }
  }

  fun apply(key: String, value: Any) {
    SettingsDomains.voice.apply(context, JSONObject().put(key, value))
    settings = VoiceConfig.load(context)
  }
  // Turning the satellite on needs the microphone; ask first, enable only if granted.
  val micPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) apply("enabled", true)
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
      Text("Voice assistant", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
      Text(
          "Use this Portal as a Home Assistant voice satellite. In Home Assistant it appears " +
              "under Settings → Devices as a discovered Wyoming device; pick its assistant " +
              "there. Okay Nabu and Hey Jarvis are heard on this Portal; \"In Home Assistant\" " +
              "needs the openWakeWord add-on.",
          color = Color(0xFF9A9A9A),
          fontSize = 15.sp,
          textAlign = TextAlign.Center,
      )
      if (settings.enabled) {
        Text("Status: $status", color = Color(0xFF8AB4F8), fontSize = 15.sp)
      }
      SettingsList(SettingsDomains.voice, settings) { k, v ->
        val needsMic =
            k == "enabled" &&
                v == true &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
        if (needsMic) micPermission.launch(Manifest.permission.RECORD_AUDIO) else apply(k, v)
      }
    }
    FolderBackButton(onClick = { activity?.finish() })
  }
}
