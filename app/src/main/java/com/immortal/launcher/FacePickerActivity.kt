/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * The clock-face picker, on its own subpage (reached from a "Clock face" row in the main
 * screensaver settings). Lists the built-in [FaceCatalog] faces; selecting one persists it and
 * the screensaver renders it. A "Preview" button opens the frame so the choice is visible
 * straight away.
 */
class FacePickerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { FacePickerScreen() } }
  }
}

@Composable
private fun FacePickerScreen() {
  val context = LocalContext.current
  var faceId by remember { mutableStateOf(ScreensaverConfig.load(context).faceId) }
  val activity = context as? Activity

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
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      Text("Clock face", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Choose how the time looks on your screensaver.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))

      Card {
        FaceCatalog.entries.forEachIndexed { i, entry ->
          if (i > 0) Divider()
          SelectableRow(
              title = entry.name,
              subtitle = entry.tagline,
              selected = faceId == entry.id,
              onClick = {
                ScreensaverConfig.setFaceId(context, entry.id)
                faceId = entry.id
              },
          )
        }
      }

      Spacer(Modifier.size(22.dp))
      PreviewButton {
        runCatching {
          context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
        }
      }
      Text(
          "More faces — and premium layouts — are coming. Your photos keep showing behind the " +
              "clock; the flip clock takes over the whole screen on its own.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp),
      )
    }
  }
}

// --- self-contained row/card composables (same style as the other settings screens) ----------
@Composable
private fun Card(content: @Composable () -> Unit) {
  Surface(
      color = Color(0xFF1C1C1E),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    Column { content() }
  }
}

@Composable
private fun Divider() {
  Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
}

@Composable
private fun SelectableRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onClick() }
              .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, color = Color.White, fontSize = 17.sp)
      Text(subtitle, color = Color(0xFF9A9A9A), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
    RadioButton(selected = selected, onClick = null)
  }
}

@Composable
private fun PreviewButton(onClick: () -> Unit) {
  Surface(color = Color(0xFF2E6BE6), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
    Text(
        "Preview",
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().tvFocusableRow { onClick() }.padding(vertical = 16.dp),
    )
  }
}
