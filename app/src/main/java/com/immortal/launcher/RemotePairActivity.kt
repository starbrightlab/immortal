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
import androidx.compose.ui.Modifier
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
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * "Control from your phone" — turns on the phone remote and shows a one-time PIN plus a
 * scan-to-pair QR for the [RemoteRoutes] page served by the fleet agent. The remote is
 * opt-in: opening this screen enables it ([RemotePairing.setEnabled]) and ensures the
 * agent (its transport) is running. A fresh PIN is minted each time the screen resumes.
 *
 * The pairing card itself ([RemotePairCard]) is shared with the home-header quick-connect modal.
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
            val (u, p) = enableRemoteAndMintPin(context)
            url = u
            pin = p
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

      RemotePairCard(url, pin)

      Spacer(Modifier.size(28.dp))
      PairDoneButton { activity?.finish() }
    }
  }
}
