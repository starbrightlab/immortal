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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * The photo-caption layout editor, on its own subpage (reached from the "Caption layout" row in
 * screensaver settings).
 *
 * It holds **only what the settings registry can't express**: the top-to-bottom order of the
 * lines, and each line's size and weight. Everything that *is* a plain scalar — which lines show
 * at all, and whether icons draw — stays a `SettingSpec` on the screensaver settings screen, so
 * it renders there once and reaches the phone remote for free. Duplicating those switches here
 * would give the same setting two homes and no single source of truth.
 *
 * Which is why this lists only the lines that are currently switched on: arranging a line that
 * isn't drawn is meaningless, and a disabled row here would just be the switch again in disguise.
 * A line's place in the order survives being switched off and back on.
 *
 * A sub-screen rather than a pile of generic rows because reordering isn't a scalar: "move up /
 * move down" is the only way to express a permutation on a D-pad. Same reasoning (and the same
 * shape) as the clock-face picker.
 */
class CaptionStyleActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { CaptionStyleScreen() } }
  }
}

@Composable
private fun CaptionStyleScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  val loaded = remember { ScreensaverConfig.load(context) }
  var order by remember { mutableStateOf(PhotoCaption.parseOrder(loaded.captionOrder)) }
  var styles by remember { mutableStateOf(PhotoCaption.parseStyles(loaded.captionStyles)) }
  // Read-only here: the switches live on the previous screen. Re-read on every entry, because
  // that's where the user just came from.
  val enabled = remember { enabledLines(loaded) }

  fun persistOrder(next: List<PhotoCaption.Line>) {
    order = next
    ScreensaverConfig.setCaptionOrder(context, next)
  }

  fun persistStyle(line: PhotoCaption.Line, style: PhotoCaption.LineStyle) {
    val next = styles + (line to style)
    styles = next
    ScreensaverConfig.setCaptionStyles(context, next)
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
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      Text("Caption layout", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Arrange the details showing under your photos, and set how big and bold each one is. " +
              "Text follows your clock face's font and colour.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))

      val shown = order.filter { enabled[it] == true }
      if (shown.isEmpty()) {
        Card {
          Text(
              "No caption lines are switched on. Turn some on under Photo details in screensaver " +
                  "settings, then come back to arrange them.",
              color = Color(0xFF9A9A9A),
              fontSize = 15.sp,
              modifier = Modifier.padding(18.dp),
          )
        }
        Spacer(Modifier.size(22.dp))
      }

      shown.forEachIndexed { index, line ->
        val style = styles[line] ?: line.defaultStyle
        SectionLabel(line.label)
        Card {
          Row(
              modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Image(
                painter = painterResource(line.iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color(0xFFDDDDDD)),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "Line ${index + 1} of ${shown.size}",
                color = Color(0xFF9A9A9A),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            // Swapping with the neighbouring *shown* line leaves any switched-off line where it
            // sits, so its position is still there when it comes back.
            MoveButton("▲", enabled = index > 0) {
              persistOrder(order.swapped(line, shown[index - 1]))
            }
            MoveButton("▼", enabled = index < shown.lastIndex) {
              persistOrder(order.swapped(line, shown[index + 1]))
            }
          }
          Divider()
          Stepper(
              label = "Size",
              valueText = "${style.size}%",
              widthMin = 78.dp,
              onMinus = {
                persistStyle(line, style.copy(size = PhotoCaption.clampSize(style.size - PhotoCaption.LineStyle.SIZE_STEP)))
              },
              onPlus = {
                persistStyle(line, style.copy(size = PhotoCaption.clampSize(style.size + PhotoCaption.LineStyle.SIZE_STEP)))
              },
          )
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Weight", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Segmented(
                PhotoCaption.Weight.entries.map { it.label to it.key },
                style.weight.key,
            ) { key ->
              PhotoCaption.Weight.fromKey(key)?.let { persistStyle(line, style.copy(weight = it)) }
            }
          }
        }
        Spacer(Modifier.size(18.dp))
      }

      Spacer(Modifier.size(4.dp))
      Card {
        ActionRow("Reset layout to defaults") {
          ScreensaverConfig.resetCaptionLayout(context)
          order = PhotoCaption.DEFAULT_ORDER
          styles = PhotoCaption.parseStyles(null)
        }
      }
      Spacer(Modifier.size(22.dp))
      PreviewButton {
        runCatching { context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java)) }
      }
      Text(
          "Only the lines you've switched on under Photo details are listed here. A line still " +
              "shows just when the photo has that detail, and the whole caption is hidden on the " +
              "full-screen flip clock, which owns the frame on its own.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp),
      )
    }
  }
}

/** The order with [a] and [b] trading places; everything else keeps its slot. */
private fun List<PhotoCaption.Line>.swapped(
    a: PhotoCaption.Line,
    b: PhotoCaption.Line,
): List<PhotoCaption.Line> {
  val i = indexOf(a)
  val j = indexOf(b)
  if (i < 0 || j < 0 || i == j) return this
  val out = toMutableList()
  out[i] = b
  out[j] = a
  return out
}

private fun enabledLines(s: ScreensaverConfig.Settings): Map<PhotoCaption.Line, Boolean> =
    mapOf(
        PhotoCaption.Line.LOCATION to s.captionLocation,
        PhotoCaption.Line.DATE to s.captionDate,
        PhotoCaption.Line.DESCRIPTION to s.captionDescription,
        PhotoCaption.Line.PEOPLE to s.captionPeople,
        PhotoCaption.Line.TAGS to s.captionTags,
    )

/** A ▲/▼ reorder button, dimmed and inert at the ends of the list. */
@Composable
private fun MoveButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
  Surface(
      color = if (enabled) Color(0xFF2A2A2C) else Color.Transparent,
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier.padding(start = 8.dp),
  ) {
    Text(
        glyph,
        color = if (enabled) Color.White else Color(0xFF4A4A4A),
        fontSize = 17.sp,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.size(44.dp)
                .tvFocusable(RoundedCornerShape(10.dp), enabled = enabled) { onClick() }
                .padding(top = 10.dp),
    )
  }
}

@Composable
private fun ActionRow(title: String, onClick: () -> Unit) {
  Text(
      title,
      color = Color.White,
      fontSize = 17.sp,
      modifier = Modifier.fillMaxWidth().tvFocusableRow { onClick() }.padding(18.dp),
  )
}

@Composable
private fun PreviewButton(onClick: () -> Unit) {
  Surface(color = Color(0xFF2E6BE6), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
    Text(
        "Preview screensaver",
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().tvFocusableRow { onClick() }.padding(vertical = 16.dp),
    )
  }
}
