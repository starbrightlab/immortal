/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * Portal-to-Portal LAN intercom / baby monitor (one-way audio). One Portal
 * broadcasts its mic; another listens. No servers, no GMS — raw PCM over TCP on the
 * local network (see [LanAudio]). Needs the mic permission, requested on first use.
 */
class IntercomActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { IntercomScreen() } }
  }
}

@Composable
private fun IntercomScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  val lan = remember { LanAudio() }
  DisposableEffect(Unit) { onDispose { lan.stop() } }

  var mode by remember { mutableStateOf("idle") } // idle | broadcasting | listening
  var host by remember { mutableStateOf("") }
  var status by remember { mutableStateOf("") }
  val myIp = remember { localIp(context) }

  // Kick off the broadcast and reflect whether the port actually bound, so the UI
  // never claims it's broadcasting when the socket couldn't open.
  fun beginBroadcast() {
    status = "Starting…"
    lan.startBroadcast { ok ->
      if (ok) { mode = "broadcasting"; status = "Broadcasting this room's audio" }
      else { mode = "idle"; status = "Couldn't start — the broadcast port is in use" }
    }
  }

  val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) beginBroadcast()
    else status = "Microphone permission is needed to broadcast"
  }

  fun startBroadcast() {
    val granted = android.content.pm.PackageManager.PERMISSION_GRANTED ==
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
    if (granted) beginBroadcast()
    else permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
  }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF111111))
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Intercom", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
      Text("One-way audio between two Portals on the same Wi-Fi. No internet, no servers.",
          color = Color(0xFF9A9A9A), fontSize = 15.sp, textAlign = TextAlign.Center)

      if (myIp.isNotBlank()) {
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
          Text("This Portal's address: $myIp", color = Color.White, fontSize = 15.sp,
              modifier = Modifier.padding(16.dp))
        }
      }

      if (status.isNotBlank()) {
        Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, textAlign = TextAlign.Center)
      }

      // Broadcast (baby monitor in the nursery): this device's mic → the network.
      ActionButton(
          label = if (mode == "broadcasting") "■ Stop broadcasting" else "🔊 Broadcast this room",
          primary = mode != "broadcasting",
      ) {
        if (mode == "broadcasting") { lan.stop(); mode = "idle"; status = "" } else startBroadcast()
      }

      // Listen (kitchen Portal): connect to the broadcasting Portal's address.
      Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
          shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(value = host, onValueChange = { host = it }, singleLine = true,
              label = { Text("Other Portal's address (e.g. 192.168.1.42)") },
              modifier = Modifier.fillMaxWidth())
          ActionButton(
              label = if (mode == "listening") "■ Stop listening" else "👂 Listen",
              primary = mode != "listening",
          ) {
            if (mode == "listening") { lan.stop(); mode = "idle"; status = "" }
            else if (host.isNotBlank()) {
              status = "Connecting…"
              lan.startListening(host.trim()) { ok ->
                status = if (ok) "Listening to $host" else "Couldn't connect to $host"
                mode = if (ok) "listening" else "idle"
              }
            }
          }
        }
      }
      Spacer(Modifier.size(8.dp))
    }
  }
  FolderBackButton(onClick = { lan.stop(); activity?.finish() })
}

@Composable
private fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
  Surface(
      color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { onClick() },
  ) {
    Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth())
  }
}

@Suppress("DEPRECATION")
private fun localIp(context: Context): String = runCatching {
  val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  val ip = wm.connectionInfo.ipAddress
  if (ip == 0) "" else String.format("%d.%d.%d.%d",
      ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff)
}.getOrDefault("")
