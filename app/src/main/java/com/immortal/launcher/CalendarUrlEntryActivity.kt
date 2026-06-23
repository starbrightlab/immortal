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

/** Paste-a-link screen for the calendar widget's ICS feed. Validates the link up-front. */
class CalendarUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SampleAppTheme(darkTheme = true) {
        CalendarUrlEntryScreen(
            onSave = { url ->
              ScreensaverConfig.setCalendarUrl(this, url)
              finish()
            },
            onRemove = {
              ScreensaverConfig.clearCalendarUrl(this)
              finish()
            },
            onCancel = { finish() },
        )
      }
    }
  }
}

@Composable
private fun CalendarUrlEntryScreen(
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
  val context = LocalContext.current
  val existing = remember { ScreensaverConfig.load(context).calendarUrl.orEmpty() }
  var url by remember { mutableStateOf(existing) }
  val (_, initialFocus) = rememberInitialFocus()

  val trimmed = url.trim()
  val supported = trimmed.isNotEmpty() && CalendarFeed.isSupported(trimmed)
  val provider = if (supported) CalendarFeed.providerName(trimmed) else null

  BackHandler { onCancel() }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      Text(
          "Calendar link",
          color = Color.White,
          fontSize = 34.sp,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          "Paste a private iCalendar (.ics) link from Google Calendar or Apple iCloud. " +
              "Immortal reads it directly — it can't sign in to your account.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )

      Spacer(Modifier.heightIn(min = 22.dp))

      OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          placeholder = {
            Text("https://calendar.google.com/calendar/ical/…/basic.ics", color = Color(0xFF777777))
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
          shape = RoundedCornerShape(14.dp),
      )

      Text(
          when {
            trimmed.isEmpty() -> "Examples: Google \"secret address in iCal format\", Apple iCloud public calendar."
            supported -> "Looks like a $provider link."
            else -> "That doesn't look like a calendar (.ics) link yet."
          },
          color = if (supported || trimmed.isEmpty()) Color(0xFF9A9A9A) else Color(0xFFE89090),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp),
      )

      Spacer(Modifier.heightIn(min = 26.dp))

      Surface(
          color = if (supported) Color(0xFF2E6BE6) else Color(0xFF2A2A2C),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().then(initialFocus).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                if (supported) onSave(trimmed)
              },
      ) {
        Text(
            "Use this calendar",
            color = if (supported) Color.White else Color(0xFF777777),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
      }

      Spacer(Modifier.heightIn(min = 12.dp))

      if (existing.isNotBlank()) {
        Surface(
            color = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                  onRemove()
                },
        ) {
          Text(
              "Remove calendar",
              color = Color(0xFFE89090),
              fontSize = 16.sp,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
          )
        }
        Spacer(Modifier.heightIn(min = 12.dp))
      }

      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                onCancel()
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

      Spacer(Modifier.heightIn(min = 26.dp))

      Text(
          "How to get a link:",
          color = Color(0xFFBBBBBB),
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
      )
      Text(
          "• Google Calendar (on a computer) → Settings → your calendar → " +
              "\"Integrate calendar\" → copy the \"Secret address in iCal format\". " +
              "Keep it private — anyone with it can read the calendar.\n" +
              "• Apple iCloud → Calendar app → tap a calendar → turn on \"Public Calendar\" " +
              "→ Copy Link (a webcal:// link works too).",
          color = Color(0xFF8A8A8A),
          fontSize = 13.sp,
          modifier = Modifier.padding(start = 4.dp),
      )
    }
  }
}
