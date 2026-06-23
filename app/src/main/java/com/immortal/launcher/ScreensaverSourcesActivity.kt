/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlin.concurrent.thread

/**
 * The screensaver's photo-source picker, on its own subpage (reached from a "Photo source" row
 * in the main screensaver settings). Split into **Standard** sources (built-in feed, your own
 * folder, a shared album link) and **Advanced** self-hosted sources (Immich, a NAS share,
 * WebDAV, or any web page) so the everyday choices aren't buried under the technical ones.
 */
class ScreensaverSourcesActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ScreensaverSourcesScreen() } }
  }
}

@Composable
private fun ScreensaverSourcesScreen() {
  val context = LocalContext.current
  var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
  var mediaCount by remember { mutableStateOf<Int?>(null) }
  var folderName by remember { mutableStateOf<String?>(null) }

  // Re-read config when returning from a picker / connect screen.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) settings = ScreensaverConfig.load(context)
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  LaunchedEffect(settings.folderPath, settings.includeVideo, settings.source) {
    val path = if (settings.usesFolder) settings.folderPath else null
    if (path == null) {
      mediaCount = null
      folderName = null
    } else {
      mediaCount = null
      folderName = LocalMedia.displayName(path)
      thread {
        mediaCount =
            if (LocalMedia.isAccessible(path)) LocalMedia.enumerate(path, settings.includeVideo).size
            else -1
      }
    }
  }

  fun open(cls: Class<*>) = context.startActivity(Intent(context, cls))
  fun openPicker() = open(FolderPickerActivity::class.java)

  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  val isDefault =
      !(settings.usesFolder ||
          settings.usesUrl ||
          settings.usesImmich ||
          settings.usesSmb ||
          settings.usesDav ||
          settings.usesWebUrl)

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) activity?.finish()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusRequester(firstFocus).focusGroup()) {
      Text("Photo source", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Choose where your screensaver photos come from.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))

      // ── Set up from another device (LAN) ──────────────────────────────────
      // Easier than typing an Immich API key / NAS path / long URL on the Portal: the phone remote
      // now hosts source + calendar setup, so pair it and enter the details from another device.
      Card {
        Row(
            modifier =
                Modifier.fillMaxWidth().tvFocusableRow { open(RemotePairActivity::class.java) }
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Set up from your phone", color = Color.White, fontSize = 17.sp)
            Text(
                "Pair the phone remote, then enter Immich keys, NAS details, a link, or the " +
                    "calendar from another device on your Wi-Fi.",
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
          }
          Text("  ›", color = Color(0xFF7C7C7C), fontSize = 20.sp)
        }
      }

      Spacer(Modifier.size(26.dp))

      // ── Standard ──────────────────────────────────────────────────────────
      SectionLabel("Standard")
      Card {
        SelectableRow(
            title = "Immortal photos",
            subtitle = "A calming built-in photo feed (no setup).",
            selected = isDefault,
            onClick = {
              ScreensaverConfig.useDefault(context)
              settings = ScreensaverConfig.load(context)
            },
        )
        Divider()
        SelectableRow(
            title = "My photos & videos",
            subtitle = folderSubtitle(settings.usesFolder, folderName, mediaCount),
            selected = settings.usesFolder,
            onClick = { openPicker() },
        )
        if (settings.usesFolder) {
          Divider()
          TextButtonRow("Choose a different folder…") { openPicker() }
        }
        Divider()
        SelectableRow(
            title = "Shared album link",
            subtitle = albumUrlSubtitle(settings.usesUrl, settings.albumUrl),
            selected = settings.usesUrl,
            onClick = { open(AlbumUrlEntryActivity::class.java) },
        )
        if (settings.usesUrl) {
          Divider()
          AlbumRefreshStepper(settings.albumRefreshMin) { v ->
            val c = ScreensaverConfig.clampAlbumRefresh(v)
            ScreensaverConfig.setAlbumRefreshMin(context, c)
            settings = settings.copy(albumRefreshMin = c)
          }
          Divider()
          TextButtonRow("Paste a different link…") { open(AlbumUrlEntryActivity::class.java) }
        }
      }

      Spacer(Modifier.size(26.dp))

      // ── Advanced (self-hosted) ────────────────────────────────────────────
      SectionLabel("Advanced")
      Text(
          "Self-hosted sources — pull from your own server or NAS, or point the screensaver at a " +
              "web frame like Immich Kiosk. These are on your local network.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 0.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
      )
      Card {
        SourceConnectRow(
            "Immich server",
            immichSubtitle(settings),
            settings.usesImmich,
            "Change Immich server or album…",
        ) {
          open(ImmichConnectActivity::class.java)
        }
        Divider()
        SourceConnectRow(
            "Network share (NAS)",
            smbSubtitle(settings),
            settings.usesSmb,
            "Change network share…",
        ) {
          open(SmbConnectActivity::class.java)
        }
        Divider()
        SourceConnectRow(
            "WebDAV folder",
            davSubtitle(settings),
            settings.usesDav,
            "Change WebDAV folder…",
        ) {
          open(DavConnectActivity::class.java)
        }
        Divider()
        SourceConnectRow(
            "Web page",
            webUrlSubtitle(settings),
            settings.usesWebUrl,
            "Change web page…",
        ) {
          open(WebUrlEntryActivity::class.java)
        }
      }
      Text(
          "If a source can't be read (server offline, drive unplugged), Immortal falls back to " +
              "its built-in photos so the frame is never blank.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
  }
}

/** An advanced-source row: selectable, with a "change…" link beneath when it's the active one. */
@Composable
private fun SourceConnectRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    changeLabel: String,
    onOpen: () -> Unit,
) {
  SelectableRow(title = title, subtitle = subtitle, selected = selected, onClick = onOpen)
  if (selected) {
    Divider()
    TextButtonRow(changeLabel) { onOpen() }
  }
}

// --- subtitles --------------------------------------------------------------
private fun folderSubtitle(usesFolder: Boolean, name: String?, count: Int?): String =
    when {
      !usesFolder -> "Pick a folder of your photos and videos on your Portal."
      count == null -> "${name ?: "Selected folder"} — scanning…"
      count < 0 -> "${name ?: "Selected folder"} — can't read it; showing built-in photos"
      count == 0 -> "${name ?: "Selected folder"} — no photos or videos found"
      else -> "${name ?: "Selected folder"} — $count item${if (count == 1) "" else "s"}"
    }

private fun albumUrlSubtitle(usesUrl: Boolean, url: String?): String =
    when {
      !usesUrl -> "Paste a public iCloud or Google Photos share link."
      url.isNullOrBlank() -> "No link yet — tap to paste one."
      else -> "${RemoteAlbum.providerName(url)} — ${shortUrl(url)}"
    }

private fun immichSubtitle(s: ScreensaverConfig.Settings): String =
    when {
      !s.usesImmich -> "Pull photos from your self-hosted Immich server."
      !s.immichAlbumName.isNullOrBlank() -> "Connected — album “${s.immichAlbumName}”"
      else -> "Connected — whole library"
    }

private fun smbSubtitle(s: ScreensaverConfig.Settings): String =
    when {
      !s.usesSmb -> "Show photos from a folder on your NAS over the network."
      else -> "Connected — \\\\${s.smbHost}\\${s.smbShare}"
    }

private fun davSubtitle(s: ScreensaverConfig.Settings): String =
    when {
      !s.usesDav -> "Show photos from a WebDAV share or Nextcloud."
      else -> "Connected — ${shortUrl(s.davUrl.orEmpty())}"
    }

private fun webUrlSubtitle(s: ScreensaverConfig.Settings): String =
    when {
      !s.usesWebUrl -> "Render any web page (Immich Kiosk, a dashboard) as the screensaver."
      else -> shortUrl(s.webUrl.orEmpty())
    }

private fun shortUrl(url: String): String {
  val trimmed = url.trim()
  return if (trimmed.length <= 56) trimmed else trimmed.take(53) + "…"
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
  Text(
      label,
      color = Color(0xFF8AB4F8),
      fontSize = 16.sp,
      modifier = Modifier.fillMaxWidth().tvFocusableRow { onClick() }.padding(18.dp),
  )
}

// Non-uniform steps so every LEFT/RIGHT tap lands on a value users think in (15m … 24h).
@Composable
private fun AlbumRefreshStepper(minutes: Int, onChange: (Int) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> {
                      onChange(prevAlbumRefreshStep(minutes))
                      true
                    }
                    Key.DirectionRight -> {
                      onChange(nextAlbumRefreshStep(minutes))
                      true
                    }
                    else -> false
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0x402E6BE6) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Refresh album", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onChange(prevAlbumRefreshStep(minutes)) }
    Text(
        formatRefreshMinutes(minutes),
        color = if (focused) Color.White else Color(0xFFDDDDDD),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 64.dp),
    )
    ArrowButton("▶", focused) { onChange(nextAlbumRefreshStep(minutes)) }
  }
}

private val ALBUM_REFRESH_STEPS = listOf(15, 30, 45, 60, 120, 180, 240, 360, 720, 1440)

private fun nextAlbumRefreshStep(minutes: Int): Int =
    ALBUM_REFRESH_STEPS.firstOrNull { it > minutes } ?: ALBUM_REFRESH_STEPS.last()

private fun prevAlbumRefreshStep(minutes: Int): Int =
    ALBUM_REFRESH_STEPS.lastOrNull { it < minutes } ?: ALBUM_REFRESH_STEPS.first()

private fun formatRefreshMinutes(minutes: Int): String =
    when {
      minutes < 60 -> "${minutes}m"
      minutes % 60 == 0 -> "${minutes / 60}h"
      else -> "${minutes / 60}h ${minutes % 60}m"
    }
