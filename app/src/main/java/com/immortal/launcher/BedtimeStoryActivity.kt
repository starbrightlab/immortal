/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.util.Locale

/**
 * Bedtime story tile — public-domain children's stories ([Stories]) shown in big, calm
 * text and read aloud through Android's built-in TextToSpeech, so the Portal's screen +
 * speaker work together in a child's room. No downloads, no network.
 */
class BedtimeStoryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContent { SampleAppTheme(darkTheme = true) { BedtimeScreen() } }
  }
}

@Composable
private fun BedtimeScreen() {
  val context = LocalContext.current
  val userLang = ImmortalSettings.load(context).language
  val stories = remember(userLang) { Stories.forLanguage(userLang) }
  var selected by remember { mutableStateOf<Stories.Story?>(null) }
  var speaking by remember { mutableStateOf(false) }

  // One TTS instance for the screen's lifetime; stop + shut it down on exit.
  val tts = remember {
    var engine: TextToSpeech? = null
    engine = TextToSpeech(context.applicationContext) { status ->
      if (status == TextToSpeech.SUCCESS) {
        runCatching { engine?.language = Locale.getDefault() }
      }
    }
    engine
  }
  DisposableEffect(Unit) {
    onDispose { runCatching { tts?.stop(); tts?.shutdown() } }
  }

  fun readAloud(story: Stories.Story) {
    runCatching {
      tts?.stop()
      val text = story.title + ". " + story.paragraphs.joinToString(" ")
      // Slow, gentle pace for bedtime.
      tts?.setSpeechRate(0.85f)
      tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "story")
      speaking = true
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0E0E1A)), contentAlignment = Alignment.Center) {
    val story = selected
    if (story == null) {
      // Story picker.
      Column(
          modifier = Modifier.widthIn(max = 620.dp).padding(28.dp)
              .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Text(com.immortal.launcher.i18n.I18n.translate("🌙 Bedtime stories", userLang), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        stories.forEach { s ->
          Surface(color = Color(0x18FFFFFF), shape = RoundedCornerShape(16.dp),
              modifier = Modifier.fillMaxWidth()
                  .tvFocusableRow { selected = s; readAloud(s) }) {
            Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(s.emoji, fontSize = 30.sp, modifier = Modifier.padding(end = 14.dp))
              Text(s.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    } else {
      BackHandler {
        runCatching { tts?.stop() }
        speaking = false
        selected = null
      }
      // Reader.
      Column(
          modifier = Modifier.widthIn(max = 720.dp).padding(32.dp)
              .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Text("${story.emoji}  ${story.title}", color = Color.White, fontSize = 30.sp,
            fontWeight = FontWeight.Bold)
        story.paragraphs.forEach { p ->
          Text(p, color = Color(0xFFEDEDED), fontSize = 23.sp, lineHeight = 34.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
          StoryButton(if (speaking) com.immortal.launcher.i18n.I18n.translate("Stop", userLang) else com.immortal.launcher.i18n.I18n.translate("Read aloud", userLang), Color(0xFF512DA8), Modifier.fillMaxWidth()) {
            if (speaking) { runCatching { tts?.stop() }; speaking = false } else readAloud(story)
          }
        }
      }
    }
  }
}

@Composable
private fun StoryButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(color = color, shape = RoundedCornerShape(14.dp),
      modifier = modifier.tvFocusableRow { onClick() }) {
    Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth())
  }
}
