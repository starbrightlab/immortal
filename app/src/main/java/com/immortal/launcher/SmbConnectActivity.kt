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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlin.concurrent.thread

/**
 * Connect an SMB / network share (a NAS) as the screensaver source: server, share, an optional
 * folder within it, and credentials. "Test & connect" verifies the share and counts the images
 * before saving. Modelled on [ImmichConnectActivity].
 */
class SmbConnectActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { SmbConnectScreen(onDone = { finish() }) } }
  }
}

@Composable
private fun SmbConnectScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val ui = remember { Handler(Looper.getMainLooper()) }
  val existing = remember { ScreensaverConfig.load(context) }

  var host by remember { mutableStateOf(existing.smbHost ?: "") }
  var share by remember { mutableStateOf(existing.smbShare ?: "") }
  var path by remember { mutableStateOf(existing.smbPath ?: "") }
  var user by remember { mutableStateOf(existing.smbUser ?: "") }
  var pass by remember { mutableStateOf(existing.smbPass ?: "") }
  var testing by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }
  var connectedCount by remember { mutableStateOf<Int?>(null) }
  val (_, initialFocus) = rememberInitialFocus()

  BackHandler { onDone() }

  fun connect() {
    if (host.isBlank() || share.isBlank()) {
      status = "Enter at least the server and share name."
      return
    }
    testing = true
    status = null
    thread {
      val src = SmbSource(host.trim(), share.trim(), path.trim(), user.trim(), pass)
      val images = if (src.connect()) src.listImages() else emptyList()
      runCatching { src.close() }
      ui.post {
        testing = false
        if (images.isNotEmpty()) {
          ScreensaverConfig.setSmb(context, host, share, path, user, pass)
          connectedCount = images.size
        } else {
          status =
              "Couldn't read images from that share. Check the server, share name, folder and " +
                  "credentials."
        }
      }
    }
  }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      Text("Network share", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)

      val count = connectedCount
      if (count != null) {
        Text(
            "Connected — found $count photo${if (count == 1) "" else "s"}. Showing them on the " +
                "screensaver now.",
            color = Color(0xFF9A9A9A),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.heightIn(min = 26.dp))
        PrimaryButton("Done") { onDone() }
        return@Column
      }

      Text(
          "Show photos from a folder on your NAS over your local network (SMB). Enter the server, " +
              "the share, an optional folder inside it, and your username and password.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.heightIn(min = 22.dp))

      Field(host, { host = it }, "Server (e.g. 192.168.1.50)", modifier = initialFocus)
      Spacer(Modifier.heightIn(min = 12.dp))
      Field(share, { share = it }, "Share name (e.g. media)")
      Spacer(Modifier.heightIn(min = 12.dp))
      Field(path, { path = it }, "Folder in the share (optional, e.g. photos)")
      Spacer(Modifier.heightIn(min = 12.dp))
      Field(user, { user = it }, "Username")
      Spacer(Modifier.heightIn(min = 12.dp))
      Field(pass, { pass = it }, "Password", password = true)

      status?.let {
        Text(
            it,
            color = Color(0xFFE89090),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
      }

      Spacer(Modifier.heightIn(min = 26.dp))
      PrimaryButton(if (testing) "Connecting…" else "Test & connect", enabled = !testing) {
        if (!testing) connect()
      }
      Spacer(Modifier.heightIn(min = 12.dp))
      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                onDone()
              },
      ) {
        Text(
            "Cancel",
            color = Color(0xFFDDDDDD),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    modifier: Modifier = Modifier,
) {
  OutlinedTextField(
      value = value,
      onValueChange = onChange,
      placeholder = { Text(placeholder, color = Color(0xFF777777)) },
      singleLine = true,
      visualTransformation =
          if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
      modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
      shape = RoundedCornerShape(14.dp),
  )
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
  Surface(
      color = if (enabled) Color(0xFF2E6BE6) else Color(0xFF2A2A2C),
      shape = RoundedCornerShape(16.dp),
      modifier =
          Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
            onClick()
          },
  ) {
    Text(
        label,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
    )
  }
}
