/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.viewinterop.AndroidView
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * RTSP/ONVIF camera viewer: full-screen live view of a saved camera stream, played by
 * Android's built-in [VideoView] (native RTSP support — no extra library, no GMS).
 * A kitchen Portal showing the driveway or door cam.
 */
class CameraViewerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { CameraViewerScreen() } }
  }
}

@Composable
private fun CameraViewerScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  var cameras by remember { mutableStateOf(CameraConfig.load(context)) }
  var playing by remember { mutableStateOf<CameraConfig.Camera?>(null) }
  var adding by remember { mutableStateOf(cameras.isEmpty()) }
  var name by remember { mutableStateOf("") }
  var url by remember { mutableStateOf("rtsp://") }

  val current = playing
  if (current != null) {
    BackHandler { playing = null }
    // Full-screen live player.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      AndroidView(
          factory = { ctx ->
            VideoView(ctx).apply {
              setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
              setVideoPath(current.url)
              setOnPreparedListener { it.isLooping = true; start() }
              setOnErrorListener { _, _, _ -> true }
            }
          },
          modifier = Modifier.fillMaxSize(),
      )
    }
    return
  }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF111111))
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Cameras", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
      Text("Live view of an RTSP camera (e.g. rtsp://user:pass@192.168.1.50:554/stream).",
          color = Color(0xFF9A9A9A), fontSize = 15.sp, textAlign = TextAlign.Center)

      cameras.forEach { cam ->
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
          Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)
                .tvFocusableRow { playing = cam }) {
              Text(cam.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
              Text(cam.url, color = Color(0xFF9A9A9A), fontSize = 12.sp, maxLines = 1)
            }
            Box(modifier = Modifier.size(40.dp).clickable {
              CameraConfig.remove(context, cam.id); cameras = CameraConfig.load(context)
            }, contentAlignment = Alignment.Center) {
              Text("✕", color = MaterialTheme.colorScheme.error, fontSize = 20.sp)
            }
          }
        }
      }

      if (adding) {
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Name (e.g. Driveway)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text("rtsp:// URL") }, modifier = Modifier.fillMaxWidth())
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                  if (url.startsWith("rtsp://") && url.length > 8) {
                    CameraConfig.add(context, name, url)
                    cameras = CameraConfig.load(context); name = ""; url = "rtsp://"; adding = false
                  }
                }) {
              Text("Add camera", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                  textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth())
            }
          }
        }
      } else {
        Surface(color = Color(0x22FFFFFF), shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { adding = true }) {
          Text("+ Add a camera", color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth())
        }
      }
      Spacer(Modifier.size(8.dp))
    }
  }
  FolderBackButton(onClick = { activity?.finish() })
}
