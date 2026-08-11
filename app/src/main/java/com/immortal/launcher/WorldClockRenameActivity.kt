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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * Renames one world-clock entry, so a clock can read "Mum's" instead of "Auckland". The city from
 * the IANA id stays visible on the widget's second line, so a renamed clock is still identifiable.
 *
 * Its own Activity because entering text on the Portal TV means summoning the on-screen keyboard,
 * which wants a screen of its own — the same shape as [CalendarUrlEntryActivity].
 */
class WorldClockRenameActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val zone = intent.getStringExtra(EXTRA_ZONE).orEmpty()
    if (zone.isBlank()) {
      finish()
      return
    }
    setContent {
      SampleAppTheme(darkTheme = true) { WorldClockRenameScreen(zone, onDone = { finish() }) }
    }
  }

  companion object {
    const val EXTRA_ZONE = "zone"
  }
}

@Composable
private fun WorldClockRenameScreen(zone: String, onDone: () -> Unit) {
  val context = LocalContext.current
  val city = remember(zone) { ImmortalSettings.cityFromZoneId(zone) }
  var name by remember { mutableStateOf(ImmortalSettings.worldClockLabels(context)[zone].orEmpty()) }
  val (_, initialFocus) = rememberInitialFocus()

  BackHandler { onDone() }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      Text("Rename clock", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Give this clock a name that means something to you. Leave it empty to go back to the " +
              "city name.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )

      Spacer(Modifier.heightIn(min = 22.dp))

      OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          placeholder = { Text(city, color = Color(0xFF777777)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
          shape = RoundedCornerShape(14.dp),
      )

      Text(
          "$city · $zone",
          color = Color(0xFF9A9A9A),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp),
      )

      Spacer(Modifier.heightIn(min = 26.dp))

      Surface(
          color = Color(0xFF2E6BE6),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().then(initialFocus).tvFocusable(
                  RoundedCornerShape(16.dp), focusScale = 1f) {
                    ImmortalSettings.setWorldClockLabel(context, zone, name)
                    onDone()
                  },
      ) {
        Text(
            if (name.isBlank()) "Use \"$city\"" else "Show \"${name.trim()}\"",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
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
            color = Color(0xFFBFBFBF),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
      }
    }
  }
}
