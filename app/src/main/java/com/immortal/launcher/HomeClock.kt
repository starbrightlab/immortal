/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Wall-clock time that changes once a minute, on the minute. The home header only shows H:mm,
 * so a per-second ticker just recomposes the whole header sixty times for one visible change.
 */
@Composable
internal fun rememberMinuteTicker(): State<Long> =
    produceState(System.currentTimeMillis()) {
      while (true) {
        val now = System.currentTimeMillis()
        delay(60_000L - now % 60_000L + 50L)
        value = System.currentTimeMillis()
      }
    }

/**
 * The header clock as its own leaf composable, so the minute tick recomposes just this text
 * rather than every button, the weather and the mini-player next to it.
 */
@Composable
internal fun MinuteClockText(
    use24Hour: Boolean,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontWeight: FontWeight = FontWeight.Light,
) {
  val now by rememberMinuteTicker()
  val fmt = remember(use24Hour) { SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm", Locale.getDefault()) }
  Text(
      fmt.format(Date(now)),
      color = color,
      fontSize = fontSize,
      fontWeight = fontWeight,
      lineHeight = fontSize,
      modifier = modifier,
  )
}

/** The long date ("Thursday, October 9"), ticking with the minute clock. */
@Composable
internal fun MinuteDateText(fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
  val now by rememberMinuteTicker()
  Text(DateFormatter.format(Date(now), "EEEEMMMMd"), color = color, fontSize = fontSize, modifier = modifier)
}
