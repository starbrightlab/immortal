/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * "Set up from your phone" — shows the address of the on-device [LanSetupServer] so the user can
 * enter a photo source's details from a phone/laptop on the same Wi-Fi (real keyboard), and a live
 * status that flips when the form is submitted. The server runs only while this screen is foreground.
 */
class LanSetupActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { LanSetupScreen() } }
  }
}

@Composable
private fun LanSetupScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  var savedLabel by remember { mutableStateOf<String?>(null) }
  var url by remember { mutableStateOf<String?>(null) }
  val server = remember { LanSetupServer(context) { label -> savedLabel = label } }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs =
        LifecycleEventObserver { _, e ->
          when (e) {
            Lifecycle.Event.ON_RESUME -> {
              server.start()
              val ip = LanSetupServer.lanIp()
              url = if (ip != null && server.port > 0) "http://$ip:${server.port}" else null
            }
            Lifecycle.Event.ON_PAUSE -> server.stop()
            else -> {}
          }
        }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(obs)
      server.stop()
    }
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
              .background(Color(0xFF101012))
              .padding(horizontal = 28.dp, vertical = 40.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 900.dp)) {
      Text(
          "Set up from your phone",
          color = Color.White,
          fontSize = 34.sp,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          "Open this address in a browser on a phone or computer on the same Wi-Fi, then enter " +
              "your source's details there — no typing on the Portal.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(28.dp))

      if (url == null) {
        Card {
          Text(
              "Connect your Portal to Wi-Fi to use this. You can still enter the details on the " +
                  "Portal directly.",
              color = Color(0xFFE0A0A0),
              fontSize = 17.sp,
              modifier = Modifier.padding(20.dp),
          )
        }
      } else {
        val qr = remember(url) { url?.let { lanSetupQr(it, 600) } }
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
                    contentDescription = "Setup QR code",
                    modifier = Modifier.padding(12.dp).size(240.dp),
                )
              }
            }
            Text(
                "or open this address",
                color = Color(0xFF9A9A9A),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                url!!,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
          }
        }
      }

      Spacer(Modifier.size(20.dp))
      Card {
        Text(
            if (savedLabel != null) "✓  Received — your Portal is now using ${savedLabel}."
            else "Waiting for your phone…",
            color = if (savedLabel != null) Color(0xFF8FE08F) else Color(0xFFBBBBBB),
            fontSize = 18.sp,
            modifier = Modifier.padding(20.dp),
        )
      }

      Spacer(Modifier.size(28.dp))
      Surface(
          color = Color(0xFF2E6BE6),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
            if (savedLabel != null) "Done" else "Close",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().tvFocusableRow { activity?.finish() }.padding(vertical = 16.dp),
        )
      }
    }
  }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
  Surface(
      color = Color(0xFF1C1C1E),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    content()
  }
}
