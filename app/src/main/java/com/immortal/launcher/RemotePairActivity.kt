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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * "Control from your phone" — turns on the phone remote and shows a one-time PIN plus a
 * scan-to-pair QR for the [RemoteRoutes] page served by the fleet agent. The remote is
 * opt-in: opening this screen enables it ([RemotePairing.setEnabled]) and ensures the
 * agent (its transport) is running. A fresh PIN is minted each time the screen resumes.
 */
class RemotePairActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { RemotePairScreen() } }
  }
}

@Composable
private fun RemotePairScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  var pin by remember { mutableStateOf<String?>(null) }
  var url by remember { mutableStateOf<String?>(null) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs =
        LifecycleEventObserver { _, e ->
          if (e == Lifecycle.Event.ON_RESUME) {
            RemotePairing.setEnabled(context, true)
            // Nav buttons drive global actions through BarWatchService, which is NOT enabled
            // by provisioning — turn it on now (reconcile keeps it shared with quick buttons).
            SettingsGuard.reconcileBarWatch(context)
            FleetAgentService.ensureRunning(context) // the remote rides on the agent
            val fresh = RemotePairing.newPin()
            pin = fresh
            val ip = lanIp()
            url =
                if (ip != null) "http://$ip:${FleetConfig.port(context)}/remote/ui" else null
          }
        }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) activity?.finish()
                  true
                } else false
              }
              .padding(horizontal = 28.dp, vertical = 40.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 900.dp)) {
      Text("Control from your phone", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Use a phone or tablet on the same Wi-Fi as a remote for this Portal — nav buttons and " +
              "an app launcher, no extra app to install.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(28.dp))

      if (url == null) {
        Card {
          Text(
              "Connect your Portal to Wi-Fi to use the remote.",
              color = Color(0xFFE0A0A0),
              fontSize = 17.sp,
              modifier = Modifier.padding(20.dp),
          )
        }
      } else {
        // The QR carries the PIN in the URL fragment so scanning auto-pairs; the page
        // still shows manual PIN entry for anyone who opens the address by hand.
        val pairUrl = remember(url, pin) { url + (pin?.let { "#pin=$it" } ?: "") }
        val qr = remember(pairUrl) { lanSetupQr(pairUrl, 600) }
        Card {
          Column(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text("Scan with your phone camera", color = Color(0xFF9A9A9A), fontSize = 15.sp)
            if (qr != null) {
              Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 14.dp)) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Remote pairing QR code",
                    modifier = Modifier.padding(12.dp).size(240.dp),
                )
              }
            }
            Text("or open $url and enter the code", color = Color(0xFF9A9A9A), fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
            Text(
                pin ?: "------",
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text("Code expires in a few minutes", color = Color(0xFF7C7C7C), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
          }
        }
      }

      Spacer(Modifier.size(28.dp))
      Surface(color = Color(0xFF2E6BE6), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Done",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().tvFocusableRow { activity?.finish() }.padding(vertical = 16.dp),
        )
      }
    }
  }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
  Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
    content()
  }
}
