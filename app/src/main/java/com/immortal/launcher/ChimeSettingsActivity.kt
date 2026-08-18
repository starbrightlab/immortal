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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.SampleAppTheme
import org.json.JSONObject

class ChimeSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ChimeSettingsScreen() } }
  }
}

@Composable
private fun ChimeSettingsScreen() {
  val context = LocalContext.current
  val userLang = ImmortalSettings.load(context).language
  var settings by remember { mutableStateOf(ChimeConfig.load(context)) }
  val activity = context as? Activity

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
      Text(com.immortal.launcher.i18n.I18n.translate("Sounds", userLang), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
      Text(
          com.immortal.launcher.i18n.I18n.translate("Gentle ambient cues. All off by default; nothing plays during quiet hours.", userLang),
          color = Color(0xFF9A9A9A),
          fontSize = 15.sp,
          textAlign = TextAlign.Center,
      )

      // Scalar controls (toggles, volumes, quiet times, sunrise sound) render from the `chime`
      // registry domain - the same specs the phone remote uses. Apply routes through the domain so
      // its onApplied (ChimeScheduler.reschedule) fires here too, not just from the remote.
      SettingsList(SettingsDomains.chime, settings) { k, v ->
        SettingsDomains.chime.apply(context, JSONObject().put(k, v))
        settings = ChimeConfig.load(context)
      }

      // Bespoke: voice picker + test buttons the registry can't model (TTS voice enumeration is
      // on-device only; test buttons are actions, not settings).
      if (settings.spokenTimeOn || settings.hourlyChimeOn || settings.goldenHourOn) {
        Card {
          if (settings.spokenTimeOn) {
            VoicePicker(settings.spokenVoice) { name ->
              ChimeConfig.setSpokenVoice(context, name)
              settings = settings.copy(spokenVoice = name)
            }
            TestButton(com.immortal.launcher.i18n.I18n.translate("Test voice", userLang)) { ChimePlayer.testVoice(context, settings.spokenVoice) }
          }
          if (settings.hourlyChimeOn) {
            TestButton(com.immortal.launcher.i18n.I18n.translate("Play chime", userLang)) { ChimePlayer.playChime(context) }
          }
          if (settings.goldenHourOn) {
            TestButton(com.immortal.launcher.i18n.I18n.translate("Test golden hour", userLang)) {
              ChimePlayer.playGoldenHourTone(context)
            }
          }
          TestButton(com.immortal.launcher.i18n.I18n.translate("Play ping", userLang)) { ChimePlayer.playPing(context, repeats = 1) }
        }
      }
    }
    FolderBackButton(onClick = { activity?.finish() })
  }
}

@Composable
private fun TestButton(label: String, onClick: () -> Unit) {
  Surface(
      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
      shape = RoundedCornerShape(12.dp),
      modifier =
          Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
              .tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f) { onClick() },
  ) {
    Text(
        "▶  $label",
        color = MaterialTheme.colorScheme.primary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
}

/** Dropdown of the TTS engine's installed voices for the chosen locale. The empty
 *  name means "engine default". Voices are enumerated from a throwaway TextToSpeech. */
@Composable
private fun VoicePicker(current: String, onPick: (String) -> Unit) {
  val context = LocalContext.current
  // name -> friendly label; first entry is always the engine default.
  var voices by remember { mutableStateOf<List<Pair<String, String>>>(listOf("" to "Default voice")) }
  var expanded by remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    var tts: android.speech.tts.TextToSpeech? = null
    tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
      if (status == android.speech.tts.TextToSpeech.SUCCESS) {
        val found = runCatching {
          tts?.voices
              ?.filter { !it.isNetworkConnectionRequired }
              ?.sortedWith(chimeTtsVoiceSort())
              ?.map { it.name to prettyVoice(it) }
              ?.distinctBy { it.first }
              ?: emptyList()
        }.getOrDefault(emptyList())
        if (found.isNotEmpty()) voices = listOf("" to "Default voice") + found
      }
    }
    onDispose { runCatching { tts?.shutdown() } }
  }

  val label = voices.firstOrNull { it.first == current }?.second ?: "Default voice"
  Box {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .tvFocusableRow { expanded = true }
                .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Voice", color = Color(0xFFDDDDDD), fontSize = 15.sp, modifier = Modifier.weight(1f))
      Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
      Text("  ▾", color = Color(0xFFDDDDDD), fontSize = 15.sp)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      voices.forEach { (name, friendly) ->
        DropdownMenuItem(
            text = { Text(friendly) },
            onClick = { onPick(name); expanded = false })
      }
    }
  }
}

private fun chimeTtsVoiceSort(): Comparator<android.speech.tts.Voice> =
    compareBy<android.speech.tts.Voice> { chimeTtsVoiceGroup(it) }
        .thenByDescending { it.quality }
        .thenBy { it.locale.displayLanguage }
        .thenBy { it.name }

private fun chimeTtsVoiceGroup(v: android.speech.tts.Voice): Int {
  val n = v.name.lowercase()
  return when {
    n.startsWith("sherpa-kokoro-") -> 0
    n.startsWith("sherpa-piper-ro_ro-") -> 1
    n.startsWith("sherpa-piper-") -> 2
    else -> 3
  }
}

private fun chimeTtsLocaleLabel(v: android.speech.tts.Voice): String {
  val language = v.locale.getDisplayLanguage(java.util.Locale.US).ifBlank { v.locale.language }
  val country = v.locale.country
  return if (country.isBlank()) language else "$language (${country.uppercase()})"
}

private fun chimeTtsQualityLabel(v: android.speech.tts.Voice): String? =
    when {
      v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "HQ+"
      v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "HQ"
      else -> null
    }

/** Turn an engine voice name like "en-us-x-sfg#female_1-local" into something readable. */
private fun prettyVoice(v: android.speech.tts.Voice): String {
  val n = v.name.lowercase()
  if (n.startsWith("sherpa-kokoro-")) {
    val voice = v.name.removePrefix("sherpa-kokoro-")
    return listOfNotNull("Kokoro $voice", chimeTtsLocaleLabel(v), chimeTtsQualityLabel(v)).joinToString(" - ")
  }
  if (n == "sherpa-piper-ro_ro-mihai-medium") {
    return listOfNotNull("Romanian Mihai", chimeTtsLocaleLabel(v), chimeTtsQualityLabel(v)).joinToString(" - ")
  }
  if (n.startsWith("sherpa-piper-")) {
    val voice = v.name.removePrefix("sherpa-piper-").replace('-', ' ')
    return listOfNotNull("Piper $voice", chimeTtsLocaleLabel(v), chimeTtsQualityLabel(v)).joinToString(" - ")
  }
  val gender = when {
    n.contains("female") -> "Female"
    n.contains("male") -> "Male"
    else -> null
  }
  val num = Regex("(\\d+)").find(n)?.value
  val base = listOfNotNull(gender, num?.let { "voice $it" }).joinToString(" ").ifBlank { v.name }
  val region = v.locale.country.ifBlank { v.locale.language }.uppercase()
  return if (region.isNotBlank()) "$base · $region" else base
}
