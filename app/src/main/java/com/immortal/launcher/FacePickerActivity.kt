/*
 * Copyright (c) 2026 Starbright Lab.
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.key.onKeyEvent
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
  var facesOn by remember { mutableStateOf(ScreensaverConfig.load(context).facesEnabled) }
  var faceId by remember { mutableStateOf(ScreensaverConfig.load(context).faceId) }
  var sizeIndex by remember { mutableStateOf(ScreensaverConfig.load(context).faceSizeIndex) }
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

      // Master switch — off shows photos only (no clock). On by default.
      Card { ToggleRow("Show a clock face", facesOn) { v ->
        ScreensaverConfig.setFacesEnabled(context, v)
        facesOn = v
      } }

      if (facesOn) {
        Spacer(Modifier.size(22.dp))
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

        // Size control — only for faces that offer variants (flip / big / bold).
        val selected = FaceCatalog.entryFor(faceId)
        if (selected.sizes.isNotEmpty()) {
          Spacer(Modifier.size(18.dp))
          Card {
            SizeStepper(sizeIndex.coerceIn(0, selected.sizes.lastIndex)) { v ->
              ScreensaverConfig.setFaceSizeIndex(context, v)
              sizeIndex = v
            }
          }
        }
      } else {
        Text(
            "Photos only — no clock or widgets on the screensaver. The now-playing card still " +
                "follows its own switch in screensaver settings.",
            color = Color(0xFF9A9A9A),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp),
        )
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

/** Small ◀ Small/Medium/Large ▶ stepper for the clock size (D-pad and touch). */
@Composable
private fun SizeStepper(index: Int, onChange: (Int) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  val last = FaceCatalog.SIZE_LABELS.lastIndex
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown)
                    when (e.key) {
                      Key.DirectionLeft -> {
                        if (index > 0) onChange(index - 1)
                        true
                      }
                      Key.DirectionRight -> {
                        if (index < last) onChange(index + 1)
                        true
                      }
                      else -> false
                    }
                else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0x402E6BE6) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Size", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { if (index > 0) onChange(index - 1) }
    Text(
        FaceCatalog.SIZE_LABELS[index.coerceIn(0, last)],
        color = if (focused) Color.White else Color(0xFFDDDDDD),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 110.dp),
    )
    ArrowButton("▶", focused) { if (index < last) onChange(index + 1) }
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
