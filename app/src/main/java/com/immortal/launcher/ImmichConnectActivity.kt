/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlin.concurrent.thread

/**
 * Connect an Immich server as the screensaver source: enter the server URL + an API key, test
 * the connection, then pick an album (or the whole library). Modelled on [AlbumUrlEntryActivity].
 */
class ImmichConnectActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ImmichConnectScreen(onDone = { finish() }) } }
  }
}

@Composable
private fun ImmichConnectScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val ui = remember { Handler(Looper.getMainLooper()) }
  val existing = remember { ScreensaverConfig.load(context) }

  var url by remember { mutableStateOf(existing.immichUrl ?: "") }
  var key by remember { mutableStateOf(existing.immichKey ?: "") }
  var testing by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }
  // null = not connected yet (show the form); non-null = connected, show the album picker.
  var albums by remember { mutableStateOf<List<ImmichSource.Album>?>(null) }
  var selectedAlbumId by remember { mutableStateOf(existing.immichAlbumId) }

  BackHandler { onDone() }

  fun connect() {
    val u = url.trim()
    val k = key.trim()
    if (u.isEmpty() || k.isEmpty()) {
      status = "Enter both the server address and an API key."
      return
    }
    testing = true
    status = null
    thread {
      val ok = ImmichSource.testConnection(u, k)
      val list = if (ok) ImmichSource.listAlbums(u, k) else null
      ui.post {
        testing = false
        if (ok) {
          ScreensaverConfig.setImmich(context, u, k)
          albums = list ?: emptyList()
        } else {
          status = "Couldn't reach Immich or the key was rejected. Check the address and key."
        }
      }
    }
  }

  fun choose(albumId: String?, albumName: String?) {
    ScreensaverConfig.setImmichAlbum(context, albumId, albumName)
    onDone()
  }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      Text("Immich server", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)

      val loaded = albums
      if (loaded == null) {
        // --- Step 1: connection details ---
        Text(
            "Pull photos straight from your self-hosted Immich server. Enter its address and an " +
                "API key (Immich → Account Settings → API Keys).",
            color = Color(0xFF9A9A9A),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.heightIn(min = 22.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = { Text("http://192.168.1.50:2283", color = Color(0xFF777777)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.heightIn(min = 12.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            placeholder = { Text("API key", color = Color(0xFF777777)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
        )

        status?.let {
          Text(
              it,
              color = Color(0xFFE89090),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 10.dp, start = 4.dp),
          )
        }

        Spacer(Modifier.heightIn(min = 26.dp))
        Surface(
            color = if (testing) Color(0xFF2A2A2C) else Color(0xFF2E6BE6),
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                  if (!testing) connect()
                },
        ) {
          Text(
              if (testing) "Connecting…" else "Test & connect",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
          )
        }
        Spacer(Modifier.heightIn(min = 12.dp))
        CancelRow(onDone)
      } else {
        // --- Step 2: pick an album (or the whole library) ---
        Text(
            "Connected. Choose what to show:",
            color = Color(0xFF9A9A9A),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.heightIn(min = 22.dp))

        Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
          Column {
            AlbumRow("Whole library", "All your photos", selectedAlbumId == null) {
              selectedAlbumId = null
              choose(null, null)
            }
            loaded.forEach { a ->
              Divider()
              AlbumRow(a.name, "${a.count} photo${if (a.count == 1) "" else "s"}", selectedAlbumId == a.id) {
                selectedAlbumId = a.id
                choose(a.id, a.name)
              }
            }
          }
        }
        Spacer(Modifier.heightIn(min = 16.dp))
        CancelRow(onDone)
      }
    }
  }
}

@Composable
private fun AlbumRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onClick() }
              .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
      Text(title, color = Color.White, fontSize = 17.sp)
      Text(subtitle, color = Color(0xFF9A9A9A), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
    RadioButton(selected = selected, onClick = null)
  }
}

@Composable
private fun CancelRow(onDone: () -> Unit) {
  Surface(
      color = Color(0xFF1C1C1E),
      shape = RoundedCornerShape(16.dp),
      modifier =
          Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) { onDone() },
  ) {
    Text(
        "Done",
        color = Color(0xFFDDDDDD),
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
    )
  }
}
