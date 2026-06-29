/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.util.Calendar

class CountdownSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { CountdownSettingsScreen() } }
  }
}

private val EMOJI_PALETTE =
    listOf("📅", "🎂", "🎉", "✈️", "❤️", "🎄", "🏖️", "🎓", "💍", "🍼", "⭐", "🏠")

@Composable
private fun CountdownSettingsScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  var events by remember { mutableStateOf(CountdownConfig.loadSorted(context)) }

  // New-event form state.
  var label by remember { mutableStateOf("") }
  var emoji by remember { mutableStateOf("🎂") }
  val cal = remember { Calendar.getInstance() }
  var month by remember { mutableIntStateOf(cal.get(Calendar.MONTH) + 1) }
  var day by remember { mutableIntStateOf(cal.get(Calendar.DAY_OF_MONTH)) }
  var repeatsYearly by remember { mutableStateOf(true) }
  var year by remember { mutableIntStateOf(cal.get(Calendar.YEAR)) }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .background(Color(0xFF111111))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("Countdowns", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
      Text(
          "Pin a chip on the home screen counting down to a birthday, trip, or holiday.",
          color = Color(0xFF9A9A9A),
          fontSize = 15.sp,
          textAlign = TextAlign.Center,
      )

      // Existing events.
      if (events.isNotEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(vertical = 6.dp)) {
            events.forEach { e ->
              Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                Text("${e.emoji}  ", fontSize = 20.sp)
                Column(modifier = Modifier.weight(1f)) {
                  Text(e.label, color = Color.White, fontSize = 17.sp)
                  Text(
                      "${String.format("%02d.%02d", e.day, e.month)}" +
                          (if (e.year > 0) ".${e.year}" else " · yearly") + " — ${e.phrase()}",
                      color = Color(0xFF9A9A9A),
                      fontSize = 13.sp,
                  )
                }
                Box(
                    modifier =
                        Modifier.size(40.dp)
                            .clickable {
                              CountdownConfig.remove(context, e.id)
                              events = CountdownConfig.loadSorted(context)
                            },
                    contentAlignment = Alignment.Center,
                ) {
                  Text("✕", color = MaterialTheme.colorScheme.error, fontSize = 20.sp)
                }
              }
            }
          }
        }
      }

      // Add form.
      Surface(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
          shape = RoundedCornerShape(22.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("New countdown", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
          OutlinedTextField(
              value = label,
              onValueChange = { label = it },
              label = { Text("Name") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )

          // Emoji palette.
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EMOJI_PALETTE.take(6).forEach { e -> EmojiChip(e, e == emoji) { emoji = e } }
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EMOJI_PALETTE.drop(6).forEach { e -> EmojiChip(e, e == emoji) { emoji = e } }
          }

          Stepper("Month", month) { month = ((it - 1 + 12) % 12) + 1 }
          Stepper("Day", day) { day = ((it - 1 + 31) % 31) + 1 }

          Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Repeats every year", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Switch(checked = repeatsYearly, onCheckedChange = { repeatsYearly = it })
          }
          if (!repeatsYearly) {
            Stepper("Year", year) { year = it.coerceIn(2000, 2100) }
          }

          Surface(
              color = MaterialTheme.colorScheme.primary,
              shape = RoundedCornerShape(14.dp),
              modifier =
                  Modifier.fillMaxWidth()
                      .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) {
                        CountdownConfig.add(context, label, emoji, month, day,
                            if (repeatsYearly) 0 else year)
                        label = ""
                        events = CountdownConfig.loadSorted(context)
                      },
          ) {
            Text(
                "Add countdown",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
            )
          }
        }
      }
      Spacer(Modifier.size(8.dp))
    }
  }
  FolderBackButton(onClick = { activity?.finish() })
}

@Composable
private fun EmojiChip(emoji: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
      color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color(0x22FFFFFF),
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier.size(44.dp).clickable { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
  }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    Box(modifier = Modifier.size(48.dp).clickable { onChange(value - 1) }, contentAlignment = Alignment.Center) {
      Text("◀", color = Color(0xFFDDDDDD), fontSize = 20.sp)
    }
    Text(
        "$value",
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 56.dp),
    )
    Box(modifier = Modifier.size(48.dp).clickable { onChange(value + 1) }, contentAlignment = Alignment.Center) {
      Text("▶", color = Color(0xFFDDDDDD), fontSize = 20.sp)
    }
  }
}
