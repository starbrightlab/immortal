/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * A friendly, non-technical guided tour of Immortal. Opened from the Help tile,
 * and shown once automatically on first launch. Plain language only — written for
 * someone who just wants to use their Portal, not a developer.
 *
 * Works with a finger (swipe or tap Next) and with the Portal TV remote (D-pad
 * on the Back/Next buttons, BACK to leave).
 */
class HelpActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    markSeen(this)
    setContent { SampleAppTheme(darkTheme = true) { HelpTour() } }
  }

  companion object {
    private const val PREFS = "immortal_help"
    private const val KEY_SEEN = "seen"

    fun hasSeen(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
  }
}

private data class HelpPage(
    val glyph: String,
    val accent: Color,
    val title: String,
    val body: String,
    val linkLabel: String? = null,
    val linkUrl: String? = null,
)
private fun buildHelpPages(userLang: String?): List<HelpPage> {
  val isEs = com.immortal.launcher.i18n.I18n.isSpanish(userLang)
  return if (isEs) {
    listOf(
        HelpPage(
            glyph = ICON_HEART,
            accent = Color(0xFFE0567A),
            title = "Bienvenido a Immortal",
            body =
                "Tu Portal tiene una segunda vida. Immortal es una pantalla de inicio, marco de fotos " +
                    "y tienda de apps gratuita creada por la comunidad para mantener tu dispositivo útil. " +
                    "Aquí tienes una guía rápida: solo te llevará un minuto.",
        ),
        HelpPage(
            glyph = ICON_GRID,
            accent = Color(0xFF4F8DF0),
            title = "Cómo navegar por la app",
            body =
                "Toca cualquier icono para abrirlo. Los iconos que parecen un pequeño grupo (como " +
                    "Ajustes) son carpetas, toca para ver su contenido. Desde cualquier app, " +
                    "toca el botón de inicio redondo o el icono de Immortal para volver aquí.",
        ),
        HelpPage(
            glyph = ICON_CALL,
            accent = Color(0xFF1FA463),
            title = "Hacer videollamadas",
            body =
                "¡Las llamadas siguen funcionando! Toca el icono Llamadas y elige una persona en la " +
                    "pantalla original del Portal. Al finalizar la llamada, toca el botón de inicio " +
                    "o el icono de Immortal para regresar.",
        ),
        HelpPage(
            glyph = ICON_IMAGE,
            accent = Color(0xFF8E5BD0),
            title = "Tu marco de fotos personal",
            body =
                "Cuando no lo estás usando, tu Portal se convierte en un marco de fotos. Para " +
                    "mostrar tus fotos, abre Ajustes, elige Salvapantallas y selecciona tu carpeta. " +
                    "En el Portal Go, puede suspenderse cuando no haya nadie cerca para ahorrar batería.",
        ),
        HelpPage(
            glyph = ICON_DOWNLOAD,
            accent = Color(0xFF2D6CDF),
            title = "Añadir más aplicaciones",
            body =
                "Toca App Store para añadir aplicaciones seleccionadas: reproductores, navegador, " +
                    "paneles de domótica y más. ¿Buscas algo específico? Instala Aurora Store desde allí " +
                    "para acceder a la mayoría de apps de Android sin cuenta de Google.",
        ),
        HelpPage(
            glyph = ICON_REFRESH,
            accent = Color(0xFFD98C12),
            title = "Instalar apps tras reiniciar",
            body =
                "Instalar apps usa un asistente que se ejecuta hasta que el Portal se apaga o reinicia. " +
                    "Todo lo instalado sigue funcionando, pero para instalar algo NUEVO tras reiniciar, " +
                    "vuelve a conectar tu Portal al ordenador y ejecuta el instalador de Immortal. Es rápido y seguro.",
        ),
        HelpPage(
            glyph = ICON_INFO,
            accent = Color(0xFF5A6470),
            title = "Algunas limitaciones",
            body =
                "Immortal hace mucho, pero no todo. Algunas apps que dependen de los servicios de Google " +
                    "pueden no funcionar completamente y no podemos modificar el sistema integrado del Portal. " +
                    "Nos enfocamos en lo que funciona de manera estable.",
        ),
        HelpPage(
            glyph = ICON_PEOPLE,
            accent = Color(0xFF2E9E8F),
            title = "Creado por y para fans",
            body =
                "Immortal está hecho por voluntarios que no querían que estos estupendos dispositivos terminaran " +
                    "en un cajón. Añadimos nuevas apps y funciones conforme la comunidad las crea. " +
                    "¿Quieres ayudar o sugerir una app? Visítanos en GitHub.",
            linkLabel = "Abrir nuestra página de GitHub",
            linkUrl = "https://github.com/starbrightlab/immortal",
        ),
    )
  } else {
    listOf(
        HelpPage(
            glyph = ICON_HEART,
            accent = Color(0xFFE0567A),
            title = "Welcome to Immortal",
            body =
                "Your Portal has a second life. Immortal is a free, community-made home " +
                    "screen, photo frame, and app store that keeps it useful now that it's no " +
                    "longer supported. Here's a quick tour — it only takes a minute.",
        ),
        HelpPage(
            glyph = ICON_GRID,
            accent = Color(0xFF4F8DF0),
            title = "Finding your way around",
            body =
                "Tap any tile to open it. Tiles that look like a little stack — like " +
                    "Settings — are folders, so tap to see what's inside. From inside any app, " +
                    "tap the round home button or the Immortal icon to come straight back here.",
        ),
        HelpPage(
            glyph = ICON_CALL,
            accent = Color(0xFF1FA463),
            title = "Making video calls",
            body =
                "Calling still works! Tap the Calls tile, then pick a person from the " +
                    "Portal's original screen that appears. When you've finished your call, tap " +
                    "the home button or the Immortal icon to return to Immortal.",
        ),
        HelpPage(
            glyph = ICON_IMAGE,
            accent = Color(0xFF8E5BD0),
            title = "Your personal photo frame",
            body =
                "When you're not using it, your Portal becomes a gentle photo frame. To " +
                    "show your own pictures, open Settings, choose Screensaver, and pick a " +
                    "folder of your photos. On the Portal Go, it can quietly sleep when no one's " +
                    "nearby to save battery — that's a setting there too.",
        ),
        HelpPage(
            glyph = ICON_DOWNLOAD,
            accent = Color(0xFF2D6CDF),
            title = "Adding more apps",
            body =
                "Tap App Store to add hand-picked apps — media players, a browser, " +
                    "smart-home dashboards and more. Want something specific? Install Aurora " +
                    "Store from there. It opens the door to most of the apps you'd expect on an " +
                    "Android device, with no Google account required.",
        ),
        HelpPage(
            glyph = ICON_REFRESH,
            accent = Color(0xFFD98C12),
            title = "Installing apps after a restart",
            body =
                "Adding new apps uses a clever helper that runs until your Portal is " +
                    "switched off or restarted. Everything you've already installed keeps " +
                    "working — but to install something NEW after a restart, reconnect your " +
                    "Portal to your computer and run the Immortal setup again. It's quick, and " +
                    "completely safe to repeat as often as you like.",
        ),
        HelpPage(
            glyph = ICON_INFO,
            accent = Color(0xFF5A6470),
            title = "A few honest limits",
            body =
                "Immortal does a lot, but it can't do everything. Some apps that depend on " +
                    "Google's services may not fully work, and we can't change the Portal's " +
                    "built-in software. We focus on the things that work well and reliably — " +
                    "and we're finding more all the time.",
        ),
        HelpPage(
            glyph = ICON_PEOPLE,
            accent = Color(0xFF2E9E8F),
            title = "Built by fans, for fans",
            body =
                "Immortal is made by volunteers who didn't want these lovely devices to end " +
                    "up in a drawer. We add new apps and features as the community creates " +
                    "them. Want to help, suggest an app, or just say hello? Come find us on " +
                    "GitHub.",
            linkLabel = "Open our GitHub page",
            linkUrl = "https://github.com/starbrightlab/immortal",
        ),
    )
  }
}

@Composable
private fun HelpTour() {
  val context = LocalContext.current
  val userLang = ImmortalSettings.load(context).language
  val helpPages = remember(userLang) { buildHelpPages(userLang) }
  val activity = context as? Activity
  var page by remember { mutableIntStateOf(0) }
  val last = helpPages.lastIndex
  val nextFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { nextFocus.requestFocus() } }

  fun go(delta: Int) {
    page = (page + delta).coerceIn(0, last)
  }

  Surface(
      modifier =
          Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.key == Key.Back || e.key == Key.Escape) {
              if (e.type == KeyEventType.KeyUp) activity?.finish()
              true
            } else false
          },
      color = Color(0xFF0E1116),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Card area — swipeable on touch.
      Box(
          contentAlignment = Alignment.Center,
          modifier =
              Modifier.weight(1f).fillMaxWidth().padding(horizontal = 48.dp).pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onHorizontalDrag = { _, d -> dx += d },
                    onDragEnd = { if (dx < -80) go(1) else if (dx > 80) go(-1) },
                )
              },
      ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
              (fadeIn(tween(220)) togetherWith fadeOut(tween(160)))
            },
            label = "helpPage",
        ) { idx ->
          PageContent(helpPages[idx])
        }
      }

      // Dots
      Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Spacer(Modifier.weight(1f))
        helpPages.indices.forEach { i ->
          Box(
              modifier =
                  Modifier.size(if (i == page) 10.dp else 7.dp)
                      .background(
                          if (i == page) Color.White else Color(0x55FFFFFF), CircleShape))
        }
        Spacer(Modifier.weight(1f))
      }

      // Controls
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 28.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        if (page > 0) {
          PillButton(label = com.immortal.launcher.i18n.I18n.translate("Back", userLang), filled = false) { go(-1) }
        } else {
          PillButton(label = com.immortal.launcher.i18n.I18n.translate("Skip", userLang), filled = false) { activity?.finish() }
        }
        Spacer(Modifier.weight(1f))
        if (page < last) {
          PillButton(label = com.immortal.launcher.i18n.I18n.translate("Next", userLang), filled = true, focusRequester = nextFocus) { go(1) }
        } else {
          PillButton(label = com.immortal.launcher.i18n.I18n.translate("Done", userLang), filled = true, focusRequester = nextFocus) {
            activity?.finish()
          }
        }
      }
    }
  }
}

@Composable
private fun PageContent(p: HelpPage) {
  val context = LocalContext.current
  val path = remember(p.glyph) { PathParser().parsePathString(p.glyph).toPath() }
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.widthIn(max = 760.dp),
  ) {
    Surface(color = p.accent, shape = RoundedCornerShape(28.dp), modifier = Modifier.size(112.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(60.dp)) {
          val s = size.minDimension / 24f
          scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
        }
      }
    }
    Spacer(Modifier.height(28.dp))
    Text(
        p.title,
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        p.body,
        color = Color(0xFFC9CDD3),
        fontSize = 18.sp,
        lineHeight = 27.sp,
        textAlign = TextAlign.Center,
    )
    if (p.linkLabel != null && p.linkUrl != null) {
      Spacer(Modifier.height(24.dp))
      PillButton(label = p.linkLabel, filled = false, accent = Color(0xFF8AB4F8)) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.linkUrl))) }
      }
    }
  }
}

@Composable
private fun PillButton(
    label: String,
    filled: Boolean,
    accent: Color = Color(0xFF2E6BE6),
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
  val base = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
  Surface(
      color = if (filled) accent else Color(0x22FFFFFF),
      shape = RoundedCornerShape(26.dp),
      modifier = base.tvFocusable(RoundedCornerShape(26.dp), focusScale = 1.04f) { onClick() },
  ) {
    Text(
        label,
        color = if (filled) Color.White else accent,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 34.dp, vertical = 15.dp),
    )
  }
}

// --- Material glyph paths (24x24) ---
private const val ICON_HEART =
    "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
private const val ICON_GRID = "M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z"
private const val ICON_CALL =
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
private const val ICON_IMAGE =
    "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
private const val ICON_DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
private const val ICON_REFRESH =
    "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
private const val ICON_INFO =
    "M11 7h2v2h-2V7zm0 4h2v6h-2v-6zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z"
private const val ICON_PEOPLE =
    "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"
