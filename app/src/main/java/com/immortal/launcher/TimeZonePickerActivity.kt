/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Search every timezone the device knows about and add one to the world clock. The curated city
 * list in the world-clock screen covers the common cases; this is the escape hatch for everywhere
 * else, so no one is stuck because their city isn't on a hand-written list.
 */
class TimeZonePickerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { TimeZonePickerScreen(onBack = { finish() }) } }
  }
}

/** Every zone id, minus the legacy aliases that would just clutter the list. */
private fun allZoneIds(): List<String> =
    TimeZone.getAvailableIDs()
        .filter { it.contains('/') && !it.startsWith("SystemV/") && !it.startsWith("Etc/") }
        .sorted()

@Composable
private fun TimeZonePickerScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val all = remember { allZoneIds() }
  var query by remember { mutableStateOf("") }
  var selected by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }
  val (_, initialFocus) = rememberInitialFocus()
  val now = remember { Date() }

  BackHandler { onBack() }

  // Match on the city and the region, so "auck", "pacific" and "Pacific/Auckland" all land.
  val matches =
      remember(query, all) {
        val q = query.trim().lowercase(Locale.getDefault())
        val hits = if (q.isEmpty()) all else all.filter { it.lowercase(Locale.getDefault()).contains(q) }
        hits.take(60)
      }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusGroup()) {
      Text("Add a timezone", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Type a city or region, then pick one to add it to the world clock.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )

      Spacer(Modifier.size(20.dp))

      OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("auckland", color = Color(0xFF777777)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).then(initialFocus),
          shape = RoundedCornerShape(14.dp),
      )

      Text(
          if (query.isBlank()) "${all.size} timezones — start typing to narrow it down"
          else "${matches.size} shown",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp),
      )

      Spacer(Modifier.size(18.dp))

      Card {
        matches.forEachIndexed { i, zone ->
          if (i > 0) Divider()
          val on = zone in selected
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .tvFocusableRow {
                        if (!on) {
                          selected = selected + zone
                          ImmortalSettings.setWorldClockZones(context, selected)
                        }
                        onBack()
                      }
                      .padding(horizontal = 18.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(ImmortalSettings.cityFromZoneId(zone), color = Color.White, fontSize = 16.sp)
              Text(
                  zone,
                  color = Color(0xFF9A9A9A),
                  fontSize = 13.sp,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            Text(
                if (on) "added" else offsetLabel(zone, now),
                color = if (on) Color(0xFF8AB4F8) else Color(0xFF7C7C7C),
                fontSize = 13.sp,
            )
          }
        }
      }

      if (matches.isEmpty()) {
        Text(
            "Nothing matches \"${query.trim()}\". Try a city (\"auckland\") or a region (\"pacific\").",
            color = Color(0xFF9A9A9A),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
      }

      Spacer(Modifier.size(18.dp))
      Text(
          "Press Back to return without adding anything.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(start = 4.dp),
      )
    }
  }
}

/** Offset of [zone] against this Portal's own clock, e.g. "+12h" / "-5h" / "same". */
private fun offsetLabel(zone: String, now: Date): String {
  val diffH =
      (TimeZone.getTimeZone(zone).getOffset(now.time) - TimeZone.getDefault().getOffset(now.time)) /
          3_600_000
  return when {
    diffH == 0 -> "same"
    diffH > 0 -> "+${diffH}h"
    else -> "${diffH}h"
  }
}
