/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.SampleAppTheme
import org.json.JSONObject

/**
 * "Stream from your iPhone" — turns the AirPlay receiver on and shows what to look for in Control
 * Center, rendering the `airplay` settings domain below the how-to card.
 *
 * Opening it opts in (like [RemotePairActivity] for the phone remote), but only once on entry so
 * the toggle below still turns it back off. No QR code: a sender finds the Portal over mDNS, so what
 * the user needs is just the name to pick — [FleetConfig.name].
 */
class AirPlayPairActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (AirPlayControl.isSupported()) enableAirPlay(this)
    setContent { SampleAppTheme(darkTheme = true) { AirPlayPairScreen() } }
  }
}

/**
 * Turn the receiver on and start it. Mirrors [enableRemoteAndMintPin] — reaching a pairing surface
 * is itself the "yes, I want this". Shared with the home-header quick-connect modal.
 */
fun enableAirPlay(context: Context) {
  AirPlayConfig.setEnabled(context, true)
  AirPlayControl.applyConfig(context)
}

@Composable
private fun AirPlayPairScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  val supported = remember { AirPlayControl.isSupported() }
  var settings by remember { mutableStateOf(AirPlayConfig.load(context)) }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF111111))
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) activity?.finish()
                  true
                } else false
              }
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 40.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Stream from your iPhone", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "Play music through this Portal's speakers, or put your iPhone, iPad or Mac screen on it — " +
            "over the same Wi-Fi, with nothing to install.",
        color = Color(0xFF9A9A9A),
        fontSize = 16.sp,
    )
    Spacer(Modifier.size(8.dp))

    if (!supported) {
      AirPlayUnsupportedCard()
    } else {
      if (settings.enabled) AirPlayPairCard(FleetConfig.name(context), settings.requirePin)

      // Everything else renders from the `airplay` registry domain — the same specs the phone
      // remote uses, applied through the domain so its onApplied (restart the receiver with the new
      // settings) fires here too. "pairing" is the NavSpec back into this very screen.
      SettingsList(SettingsDomains.airplay, settings, exclude = setOf("pairing")) { k, v ->
        SettingsDomains.airplay.apply(context, JSONObject().put(k, v))
        settings = AirPlayConfig.load(context)
      }
    }

    Spacer(Modifier.size(8.dp))
    PairDoneButton { activity?.finish() }
  }
}

/**
 * The "here's what to tap" card: the two Control Center routes and the name to pick. Shared with the
 * home-header quick-connect modal so the two surfaces can't drift.
 */
@Composable
fun AirPlayPairCard(deviceName: String, requirePin: Boolean, modifier: Modifier = Modifier) {
  Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
      Step("1", "Open Control Center on your iPhone, iPad or Mac.")
      Step("2", "Tap AirPlay to send music, or Screen Mirroring to send the screen.")
      Step("3", "Pick this Portal from the list:")
      Surface(
          color = Color(0xFF2A2A2C),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.padding(start = 34.dp, top = 10.dp),
      ) {
        Text(
            deviceName,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
      }
      Text(
          "This is the Portal's device name — rename it from the phone remote and AirPlay follows.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(start = 34.dp, top = 8.dp),
      )
      if (requirePin) {
        Text(
            "A code appears on this Portal when your phone connects — type it into the phone to " +
                "allow the connection.",
            color = Color(0xFFD8C08A),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
      }
    }
  }
}

@Composable
private fun Step(n: String, text: String) {
  Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
    Text(n, color = Color(0xFF7C7C7C), fontSize = 17.sp, modifier = Modifier.width(34.dp))
    Text(text, color = Color(0xFFDADADA), fontSize = 17.sp)
  }
}

/**
 * Shown instead of the card on a device with no native library. The build filters it to arm64 (every
 * Portal is), so in practice this is only ever seen on an emulator.
 */
@Composable
private fun AirPlayUnsupportedCard(modifier: Modifier = Modifier) {
  Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
    Text(
        "AirPlay isn't available on this device.",
        color = Color(0xFFE0A0A0),
        fontSize = 17.sp,
        modifier = Modifier.padding(20.dp),
    )
  }
}
