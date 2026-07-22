/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-page overlays for the sky/space tools whose logic already landed but whose UI formerly lived
 * only in ForkHome ([IssPasses], [Aurora]). Hosted full-page by [ToolsActivity] — each is a centered
 * card over a scrim, with an `onDismiss` back to the Tools list. This file holds only rendering; the
 * computation lives in the matching object. More tools (converter, timers, notes, day/year, speed
 * test) hook into the same scaffolding in follow-up changes.
 */

/* ------------------------------------------------------------------ ISS passes */

@Composable
internal fun IssOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val userLang = ImmortalSettings.load(context).language
  var passes by remember { mutableStateOf<List<IssPasses.Pass>?>(null) }
  LaunchedEffect(Unit) { passes = withContext(Dispatchers.IO) { IssPasses.predict(context) } }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard {
      Text(com.immortal.launcher.i18n.I18n.translate("🛰️ Space station overhead", userLang), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      val p = passes
      when {
        p == null -> Text(com.immortal.launcher.i18n.I18n.translate("Finding passes…", userLang), color = Color(0xFFB0B0B0), fontSize = 16.sp)
        p.isEmpty() ->
            Text(
                com.immortal.launcher.i18n.I18n.translate("No passes found. Check the device is online so it can fetch the latest orbit, and that your location is set (it follows the weather tile).", userLang),
                color = Color(0xFFB0B0B0),
                fontSize = 15.sp,
            )
        else -> {
          val first = p.first()
          Text(
              buildString {
                append(if (first.visible) com.immortal.launcher.i18n.I18n.tr("Visible pass ", "Pase visible ", userLang) else com.immortal.launcher.i18n.I18n.tr("Passes over ", "Pasa sobre ", userLang))
                append(IssPasses.timeLabel(first.startMillis))
                append(if (first.visible) " ✨" else "")
              },
              color = if (first.visible) Color(0xFFFFD54F) else Color.White,
              fontSize = 17.sp,
              fontWeight = FontWeight.SemiBold,
          )
          Column(
              modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            p.forEach { pass ->
              Surface(color = Color(0x18FFFFFF), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(IssPasses.timeLabel(pass.startMillis), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (pass.visible) {
                      Surface(color = Color(0x33FFD54F), shape = RoundedCornerShape(8.dp)) {
                        Text("✨ " + com.immortal.launcher.i18n.I18n.translate("visible", userLang), color = Color(0xFFFFD54F), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                      }
                    }
                  }
                  Text(
                      com.immortal.launcher.i18n.I18n.tr(
                          "Rises ${pass.startDir} · peak ${pass.maxElevationDeg}° to the ${pass.peakDir} · sets ${pass.endDir}",
                          "Sale ${pass.startDir} · pico ${pass.maxElevationDeg}° hacia el ${pass.peakDir} · se pone ${pass.endDir}",
                          userLang
                      ),
                      color = Color(0xFFB8B8B8),
                      fontSize = 14.sp,
                  )
                }
              }
            }
          }
          Text(
              com.immortal.launcher.i18n.I18n.translate("Times are local. ✨ means it should be bright enough to see — go outside and look up to the listed direction.", userLang),
              color = Color(0xFF8A8A8A),
              fontSize = 12.sp,
          )
        }
      }
      CloseButton(com.immortal.launcher.i18n.I18n.translate("Close", userLang), onDismiss)
    }
  }
}

/* ------------------------------------------------------------------ Aurora */

@Composable
internal fun AuroraOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  var status by remember { mutableStateOf<Aurora.Status?>(null) }
  var loaded by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    status = withContext(Dispatchers.IO) { Aurora.status(context) }
    loaded = true
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard {
      Text("🌌 Aurora outlook", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      val st = status
      when {
        !loaded -> Text("Checking the K-index…", color = Color(0xFFB0B0B0), fontSize = 16.sp)
        st == null ->
            Text(
                "Couldn't fetch the K-index. Check the device is online and that your location is " +
                    "set (it follows the weather tile).",
                color = Color(0xFFB0B0B0),
                fontSize = 15.sp,
            )
        else -> {
          val accent =
              when (st.chance) {
                Aurora.Chance.LIKELY -> Color(0xFF69F0AE)
                Aurora.Chance.POSSIBLE -> Color(0xFFB9F6CA)
                Aurora.Chance.SLIM -> Color(0xFFB0BEC5)
                Aurora.Chance.NONE -> Color(0xFF90A4AE)
              }
          Text(st.headline, color = accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
          Text(st.detail, color = Color(0xFFCFCFCF), fontSize = 15.sp, lineHeight = 21.sp)
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            AuroraStat("Kp now", Aurora.fmtKp(st.kpNow), Modifier.weight(1f))
            AuroraStat("Next 24 h peak", Aurora.fmtKp(st.kpForecast), Modifier.weight(1f))
            AuroraStat("Look", st.lookToward, Modifier.weight(1f))
          }
          Text(
              "Source: NOAA SWPC planetary K-index. Best after dark, away from city lights, with a " +
                  "clear ${if (st.lookToward == "N") "northern" else "southern"} horizon.",
              color = Color(0xFF8A8A8A),
              fontSize = 12.sp,
          )
        }
      }
      CloseButton("Close", onDismiss)
    }
  }
}

@Composable
private fun AuroraStat(label: String, value: String, modifier: Modifier = Modifier) {
  Surface(color = Color(0x18FFFFFF), shape = RoundedCornerShape(12.dp), modifier = modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth()) {
      Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
      Text(label, color = Color(0xFF9A9A9A), fontSize = 12.sp, textAlign = TextAlign.Center)
    }
  }
}

/* ------------------------------------------------------------------ Speed test */

@Composable
internal fun SpeedTestOverlay(onDismiss: () -> Unit) {
  var phase by remember { mutableStateOf(SpeedTest.Phase.PING) }
  val r = remember { SpeedTest.Result() }
  var runId by remember { mutableStateOf(0) }

  LaunchedEffect(runId) {
    r.reset()
    phase = SpeedTest.Phase.PING
    SpeedTest.run(result = r, onPhase = { phase = it })
    phase = SpeedTest.Phase.DONE
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF1C1C1E)) {
      Column(modifier = Modifier.width(380.dp).padding(24.dp)) {
        Text("Speed test", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            if (r.server.isNotEmpty()) r.server
            else if (phase == SpeedTest.Phase.DONE) "Cloudflare" else "Finding server…",
            color = Color(0xFF9A9A9A),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.size(22.dp))
        Row(Modifier.fillMaxWidth()) {
          SpeedMetric("Ping", r.pingMs, "ms", 0, active = phase == SpeedTest.Phase.PING, accent = Color(0xFFFFCA28), modifier = Modifier.weight(1f))
          SpeedMetric("Jitter", r.jitterMs, "ms", 1, active = phase == SpeedTest.Phase.PING, accent = Color(0xFFFFCA28), modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.size(20.dp))
        SpeedMetric("↓  Download", r.downMbps, "Mbps", 1, active = phase == SpeedTest.Phase.DOWNLOAD, accent = Color(0xFF42A5F5), big = true)
        Spacer(Modifier.size(16.dp))
        SpeedMetric("↑  Upload", r.upMbps, "Mbps", 1, active = phase == SpeedTest.Phase.UPLOAD, accent = Color(0xFF66BB6A), big = true)
        Spacer(Modifier.size(26.dp))
        val busy = phase != SpeedTest.Phase.DONE
        val label =
            when (phase) {
              SpeedTest.Phase.PING -> "Pinging…"
              SpeedTest.Phase.DOWNLOAD -> "Testing download…"
              SpeedTest.Phase.UPLOAD -> "Testing upload…"
              SpeedTest.Phase.DONE -> "Run again"
            }
        Surface(
            color = if (busy) Color(0x22FFFFFF) else MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { if (!busy) runId++ },
        ) {
          Text(label, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp))
        }
        Spacer(Modifier.size(10.dp))
        CloseButton("Close", onDismiss)
      }
    }
  }
}

@Composable
private fun SpeedMetric(
    label: String,
    value: Double,
    unit: String,
    decimals: Int,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
  val shown = if (value > 0.0) String.format("%.${decimals}f", value) else "—"
  if (big) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
      Text(label, color = Color(0xFFDADADA), fontSize = 17.sp, modifier = Modifier.weight(1f))
      Text(shown, color = if (active) accent else Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp)
      Spacer(Modifier.size(6.dp))
      Text(unit, color = Color(0xFF9A9A9A), fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
  } else {
    Column(modifier) {
      Text(label.uppercase(), color = Color(0xFF8A8A8A), fontSize = 12.sp, letterSpacing = 0.5.sp)
      Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
        Text(shown, color = if (active) accent else Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
        Spacer(Modifier.size(4.dp))
        Text(unit, color = Color(0xFF9A9A9A), fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
      }
    }
  }
}

/* ------------------------------------------------------------------ Converter */

@Composable
internal fun ConverterOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val categories = remember { Converter.UNIT_CATEGORIES.map { it.first } + "Currency" }
  var category by remember { mutableStateOf(categories.first()) }
  val units =
      remember(category) {
        if (category == "Currency") Converter.CURRENCIES
        else Converter.UNIT_CATEGORIES.first { it.first == category }.second.keys.toList()
      }
  var from by remember(category) { mutableStateOf(units.first()) }
  var to by remember(category) { mutableStateOf(units.getOrElse(1) { units.first() }) }
  var input by remember { mutableStateOf("1") }
  var result by remember { mutableStateOf("") }

  LaunchedEffect(category, from, to, input) {
    val v = input.toDoubleOrNull()
    result =
        when {
          v == null -> ""
          category == "Currency" ->
              withContext(Dispatchers.IO) { Converter.convertCurrency(context, from, to, v) }
                  ?.let { trimNum(it) } ?: "no rate (offline?)"
          else -> trimNum(Converter.convert(category, from, to, v))
        }
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard {
      Text("🔢 Converter", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      ChipRow(categories, category) { category = it }
      TextField(
          value = input,
          onValueChange = { v -> input = v.filter { it.isDigit() || it == '.' || it == '-' } },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
      )
      Text("From", color = Color(0xFF8A8A8A), fontSize = 12.sp)
      ChipRow(units, from) { from = it }
      Text("To", color = Color(0xFF8A8A8A), fontSize = 12.sp)
      ChipRow(units, to) { to = it }
      Text(
          if (result.isBlank()) "—" else "$result $to",
          color = Color(0xFF69F0AE),
          fontSize = 26.sp,
          fontWeight = FontWeight.Bold,
      )
      CloseButton("Close", onDismiss)
    }
  }
}

/* ------------------------------------------------------------------ Timers */

@Composable
internal fun TimersOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  var timers by remember { mutableStateOf(TimerConfig.load(context)) }
  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1000)
    }
  }
  fun reload() {
    timers = TimerConfig.load(context)
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard {
      Text("⏲️ Timers", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      Text("Start a timer", color = Color(0xFF8A8A8A), fontSize = 12.sp)
      val presets = listOf(1, 3, 5, 10, 15, 30)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        presets.take(3).forEach { m ->
          PillButton("${m}m", { TimerConfig.add(context, "$m min", m * 60_000L); reload() }, Modifier.weight(1f))
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        presets.drop(3).forEach { m ->
          PillButton("${m}m", { TimerConfig.add(context, "$m min", m * 60_000L); reload() }, Modifier.weight(1f))
        }
      }
      val active = timers.filter { it.endAtMillis > now }.sortedBy { it.endAtMillis }
      if (active.isEmpty()) Text("No timers running.", color = Color(0xFFB0B0B0), fontSize = 15.sp)
      active.forEach { t ->
        Surface(color = Color(0x18FFFFFF), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
          Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Text(t.label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(fmtClock(t.endAtMillis - now), color = Color(0xFFFFCA28), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(12.dp))
            Surface(
                color = Color(0x33FF5252),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.tvFocusable(RoundedCornerShape(10.dp), focusScale = 1f) { TimerConfig.remove(context, t.id); reload() },
            ) {
              Text("Cancel", color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
          }
        }
      }
      CloseButton("Close", onDismiss)
    }
  }
}

/* ------------------------------------------------------------------ Leave a note */

@Composable
internal fun NotesOverlay(onDismiss: () -> Unit) {
  val context = LocalContext.current
  var text by remember { mutableStateOf(NotesConfig.loadText(context)) }
  var saved by remember { mutableStateOf(false) }
  val audio = remember { AudioNote(context) }
  var recording by remember { mutableStateOf(false) }
  var hasAudio by remember { mutableStateOf(NotesConfig.hasAudioNote(context)) }
  DisposableEffect(Unit) { onDispose { audio.release() } }

  fun beginRecord() {
    if (audio.startRecording()) recording = true
  }
  val micPerm =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecord()
      }
  fun record() {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED)
        beginRecord()
    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
  }

  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard {
      Text("📝 Leave a note", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      TextField(
          value = text,
          onValueChange = { text = it; saved = false },
          modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
      )
      PillButton(
          if (saved) "Saved ✓" else "Save note",
          { NotesConfig.saveText(context, text); saved = true },
          Modifier.fillMaxWidth(),
      )
      Text("Voice memo", color = Color(0xFF8A8A8A), fontSize = 12.sp)
      if (recording) {
        PillButton(
            "■ Stop recording",
            { audio.stopRecording(); recording = false; hasAudio = NotesConfig.hasAudioNote(context) },
            Modifier.fillMaxWidth(),
        )
      } else {
        PillButton(if (hasAudio) "Re-record" else "● Record", { record() }, Modifier.fillMaxWidth())
      }
      if (hasAudio && !recording) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          PillButton("▶ Play", { audio.play() }, Modifier.weight(1f))
          PillButton("Delete", { NotesConfig.clearAudio(context); hasAudio = false }, Modifier.weight(1f))
        }
      }
      CloseButton("Close", onDismiss)
    }
  }
}

/* ------------------------------------------------------------------ small shared bits */

/** A horizontally-scrolling row of selectable chips (category / unit pickers). */
@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    options.forEach { opt ->
      val on = opt == selected
      Surface(
          color = if (on) MaterialTheme.colorScheme.primary else Color(0x22FFFFFF),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.tvFocusable(RoundedCornerShape(10.dp), focusScale = 1f) { onSelect(opt) },
      ) {
        Text(opt, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
      }
    }
  }
}

/** A full-width-ish pill action button used by the timer / notes overlays. */
@Composable
private fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Surface(
      color = Color(0x22FFFFFF),
      shape = RoundedCornerShape(12.dp),
      modifier = modifier.tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f) { onClick() },
  ) {
    Text(
        label,
        color = Color.White,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp).fillMaxWidth(),
    )
  }
}

/** Trim a converted number to at most 4 decimals, dropping trailing zeros. */
private fun trimNum(v: Double): String = String.format("%.4f", v).trimEnd('0').trimEnd('.')

/** Remaining-time as `m:ss` (or `h:mm:ss`). */
private fun fmtClock(ms: Long): String {
  val s = ms.coerceAtLeast(0L) / 1000
  val h = s / 3600
  val m = (s % 3600) / 60
  val sec = s % 60
  return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

/* ------------------------------------------------------------------ shared scaffolding */

/** Full-screen scrim over the home page; tapping outside the card dismisses. */
@Composable
internal fun Scrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xCC000000))
              .tvFocusable(RoundedCornerShape(0.dp), focusScale = 1f) { onDismiss() },
  ) {
    content()
  }
}

/** The standard tool card: a rounded surface with a vertically-scrolling padded column. */
@Composable
internal fun ToolCard(maxWidth: Int = 560, content: @Composable () -> Unit) {
  Surface(
      color = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(24.dp),
      modifier = Modifier.widthIn(max = maxWidth.dp).heightIn(max = 760.dp).padding(24.dp),
  ) {
    Column(
        modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      content()
    }
  }
}

@Composable
internal fun CloseButton(label: String, onClick: () -> Unit) {
  Surface(
      color = Color(0x22FFFFFF),
      shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { onClick() },
  ) {
    Text(label, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth())
  }
}
