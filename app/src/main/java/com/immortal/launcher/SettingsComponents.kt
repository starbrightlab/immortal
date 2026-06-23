/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The shared settings-screen UI vocabulary. Every settings Activity was carrying its own private
 * copies of these rows (a `ToggleRow` here, a `Card` there, the same palette hardcoded six times);
 * they're consolidated here so there's one definition to evolve and the screens can't drift. All
 * are built on the D-pad focus primitives in [TvFocus] ([tvFocusable] / [tvFocusableRow]).
 *
 * The bespoke steppers (interval, time-of-day, clock size, album refresh) stay with their screens
 * for now — they share [ArrowButton] but encode per-control step/format/bounds.
 */

/** Uppercase grey section header. */
@Composable
internal fun SectionLabel(text: String) {
  Text(
      text.uppercase(),
      color = Color(0xFF7C7C7C),
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
  )
}

/** A rounded card surface that groups a column of rows. */
@Composable
internal fun Card(content: @Composable () -> Unit) {
  Surface(
      color = Color(0xFF1C1C1E),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    Column { content() }
  }
}

/** A hairline divider between rows in a [Card]. */
@Composable
internal fun Divider() {
  Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
}

/**
 * A tappable ◀ / ▶ arrow for the steppers. Excluded from D-pad focus traversal
 * (`canFocus = false`) so on the remote the parent row keeps handling LEFT/RIGHT — the value
 * adjusts the same way with either input.
 */
@Composable
internal fun ArrowButton(glyph: String, rowFocused: Boolean, onClick: () -> Unit) {
  Box(
      modifier =
          Modifier.size(48.dp)
              .clip(RoundedCornerShape(12.dp))
              .focusProperties { canFocus = false }
              .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        glyph,
        color = if (rowFocused) Color.White else Color(0xFFBBBBBB),
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
  }
}

/** A row that opens a sub-page; shows the current [value] and a chevron. */
@Composable
internal fun NavRow(title: String, value: String, onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onClick() }
              .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    Text(value, color = Color(0xFF9A9A9A), fontSize = 15.sp)
    Text("  ›", color = Color(0xFF7C7C7C), fontSize = 20.sp)
  }
}

/** A title + on/off switch. The whole row is the click target (so the remote's centre works). */
@Composable
internal fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onChange(!checked) }
              .padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    // Visual only — the row toggles it (so the remote's center button works).
    Switch(checked = checked, onCheckedChange = null)
  }
}

/** A single-select row with a title + subtitle and a radio dot. The whole row is the click target. */
@Composable
internal fun SelectableRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
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

/** A compact segmented control: [options] are `label to value`, the selected one is highlighted. */
@Composable
internal fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
  Row(
      modifier = Modifier.background(Color(0xFF2A2A2C), RoundedCornerShape(12.dp)).padding(3.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    options.forEach { (label, value) ->
      val on = value == selected
      Surface(
          color = if (on) Color(0xFF2E6BE6) else Color.Transparent,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.tvFocusable(RoundedCornerShape(10.dp)) { onSelect(value) },
      ) {
        Text(
            label,
            color = if (on) Color.White else Color(0xFFBBBBBB),
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
      }
    }
  }
}

/**
 * Remote-friendly stepper (replaces the per-screen IntervalStepper/MinuteStepper/TimeStepper):
 * focusable, LEFT/RIGHT adjust the value and UP/DOWN pass through so the remote can move off it (a
 * focused Slider traps all four directions). On touch it's still adjustable via the on-screen arrows.
 */
@Composable
internal fun Stepper(label: String, valueText: String, widthMin: Dp = 64.dp, onMinus: () -> Unit, onPlus: () -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown)
                    when (e.key) {
                      Key.DirectionLeft -> {
                        onMinus()
                        true
                      }
                      Key.DirectionRight -> {
                        onPlus()
                        true
                      }
                      else -> false
                    }
                else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0x402E6BE6) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onMinus() } // left-pointing triangle
    Text(
        valueText,
        color = if (focused) Color.White else Color(0xFFDDDDDD),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = widthMin),
    )
    ArrowButton("▶", focused) { onPlus() } // right-pointing triangle
  }
}
