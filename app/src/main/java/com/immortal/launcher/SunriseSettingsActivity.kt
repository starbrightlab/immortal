/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.SampleAppTheme
import org.json.JSONObject
import java.util.Calendar

/** Set the sunrise alarm: time, which days, ramp length, and the optional chime. */
class SunriseSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { SunriseScreen() } }
  }
}

private val DAY_LABELS =
    listOf(
        Calendar.MONDAY to "M", Calendar.TUESDAY to "T", Calendar.WEDNESDAY to "W",
        Calendar.THURSDAY to "T", Calendar.FRIDAY to "F", Calendar.SATURDAY to "S",
        Calendar.SUNDAY to "S")

@Composable
private fun SunriseScreen() {
  val context = LocalContext.current
  val initial = remember { SunriseConfig.load(context) }
  var enabled by remember { mutableStateOf(initial.enabled) }
  var hour by remember { mutableIntStateOf(initial.hour) }
  var minute by remember { mutableIntStateOf(initial.minute) }
  var ramp by remember { mutableIntStateOf(initial.rampMinutes) }
  var chime by remember { mutableStateOf(initial.chime) }
  var days by remember { mutableStateOf(initial.days) }

  fun persist() {
    // Set days first (bespoke — the registry models scalars, not sets), then route the scalar
    // fields through the domain so its onApplied (SunriseScheduler.reschedule) fires once with
    // the new days already in prefs.
    SunriseConfig.setDays(context, days)
    SettingsDomains.sunrise.apply(
        context,
        JSONObject()
            .put("enabled", enabled)
            .put("hour", hour)
            .put("minute", minute)
            .put("rampMinutes", ramp)
            .put("chime", chime))
  }

  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)), contentAlignment = Alignment.TopCenter) {
    Column(
        modifier = Modifier.widthIn(max = 620.dp).padding(28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Text("🌅 Sunrise alarm", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
      Text("The screen brightens gradually to wake you, with an optional gentle chime.",
          color = Color(0xFFB8B8B8), fontSize = 15.sp)

      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Enabled", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { enabled = it; persist() })
      }

      // Time pickers (stepper rows).
      Stepper("Hour", hour, 0, 23) { hour = it; persist() }
      Stepper("Minute", minute, 0, 59, step = 5) { minute = it; persist() }

      Text("Wake on", color = Color(0xFF9A9A9A), fontSize = 14.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DAY_LABELS.forEach { (d, label) ->
          val on = d in days
          Surface(color = if (on) MaterialTheme.colorScheme.primary else Color(0x22FFFFFF), shape = CircleShape,
              modifier = Modifier.tvFocusable(CircleShape, focusScale = 1.08f) {
                days = if (on) days - d else days + d; persist()
              }) {
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(14.dp))
          }
        }
      }

      Text("Ramp: $ramp min", color = Color.White, fontSize = 16.sp)
      Slider(value = ramp.toFloat(), onValueChange = { ramp = it.toInt() },
          valueRange = 1f..45f, onValueChangeFinished = { persist() })

      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Chime at the end", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Switch(checked = chime, onCheckedChange = { chime = it; persist() })
      }

      val next = remember(enabled, hour, minute, days) {
        SunriseConfig.nextTrigger(SunriseConfig.Config(enabled, hour, minute, ramp, chime, days))
      }
      Text(
          if (next != null)
              "Next: " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
                  .format(java.util.Date(next))
          else "Alarm is off.",
          color = Color(0xFF8AB4F8), fontSize = 15.sp)

      Surface(color = Color(0xFFEF6C00), shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp)) {
            context.startActivity(
                Intent(context, WakeLightActivity::class.java)
                    .putExtra(WakeLightActivity.EXTRA_RAMP_MIN, 1)
                    .putExtra(WakeLightActivity.EXTRA_CHIME, chime))
          }) {
        Text("Preview (1-min ramp)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth())
      }
    }
  }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(label, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
    StepBtn("–") { onChange(((value - step).coerceAtLeast(min))) }
    Text("%02d".format(value), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
    StepBtn("+") { onChange(((value + step).coerceAtMost(max))) }
  }
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
  Surface(color = Color(0x33FFFFFF), shape = CircleShape,
      modifier = Modifier.tvFocusable(CircleShape, focusScale = 1.1f) { onClick() }) {
    Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
  }
}
