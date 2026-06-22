/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared "control from your phone" pairing UI + enable step, used by both the full
 * [RemotePairActivity] (Settings entry) and the home-header quick-connect modal. Keeping the
 * QR/PIN card in one place stops the two surfaces from drifting.
 */

/**
 * Turn the phone remote on and mint a fresh pairing PIN, returning `(url, pin)` for the QR/PIN
 * card. Enables the feature ([RemotePairing]), the accessibility service it needs
 * ([SettingsGuard.reconcileBarWatch]) and the agent that serves it; `url` is null off Wi-Fi.
 */
fun enableRemoteAndMintPin(context: Context): Pair<String?, String?> {
  RemotePairing.setEnabled(context, true)
  SettingsGuard.reconcileBarWatch(context)
  // The nav buttons + touchpad route through the accessibility service; if an app update left it
  // enabled-but-unbound, force a rebind now so the remote isn't half-dead on connect.
  SettingsGuard.ensureBarWatchConnected(context)
  FleetAgentService.ensureRunning(context)
  val pin = RemotePairing.newPin()
  val ip = lanIp()
  val url = if (ip != null) "http://$ip:${FleetConfig.port(context)}/remote/ui" else null
  return url to pin
}

/**
 * The scan-to-pair card: a QR (URL + `#pin=` so scanning auto-pairs) plus the manual code and
 * address. Shows a Wi-Fi hint when [url] is null.
 */
@Composable
fun RemotePairCard(url: String?, pin: String?, modifier: Modifier = Modifier) {
  Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) {
    if (url == null) {
      Text(
          "Connect your Portal to Wi-Fi to use the remote.",
          color = Color(0xFFE0A0A0),
          fontSize = 17.sp,
          modifier = Modifier.padding(20.dp),
      )
    } else {
      val pairUrl = remember(url, pin) { url + (pin?.let { "#pin=$it" } ?: "") }
      val qr = remember(pairUrl) { lanSetupQr(pairUrl, 600) }
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
}

/**
 * Compact, centred "Done" for the pairing surfaces (modal + [RemotePairActivity]). A wrap-content
 * pill rather than a full-width bar, which looked heavy in the dialog. Ring focus for the remote.
 */
@Composable
fun PairDoneButton(modifier: Modifier = Modifier, onDone: () -> Unit) {
  Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Surface(
        color = Color(0xFF2E6BE6),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.tvFocusable(RoundedCornerShape(12.dp)) { onDone() },
    ) {
      Text(
          "Done",
          color = Color.White,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(horizontal = 44.dp, vertical = 12.dp),
      )
    }
  }
}
