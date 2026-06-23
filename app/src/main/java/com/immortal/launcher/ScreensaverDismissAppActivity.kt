/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Resolves and launches whatever the user picked to open when they tap the photo-frame
 * screensaver to dismiss it (see [ScreensaverDismissAppActivity]). Kept separate from
 * [ScreensaverConfig] (pure preference storage) because this touches the PackageManager
 * and launches activities.
 *
 * Three mutually-exclusive targets, in precedence order:
 *  1. **Home Assistant** ([ScreensaverConfig.Settings.dismissHaDashboard] non-null) —
 *     deep-link to a dashboard via `homeassistant://navigate/<path>`, or open HA's
 *     default dashboard when the path is blank.
 *  2. **An app** ([ScreensaverConfig.Settings.dismissAppComponent]) — a plain launch.
 *  3. **The Immortal launcher** (both null) — the default; [launchChosenApp] is a no-op
 *     and the dream just finishes home.
 *
 * Everything degrades to the launcher: an uninstalled app or a removed HA companion
 * makes [launchChosenApp] return false, so a stale choice can never strand the dream.
 */
object ScreensaverDismiss {
  private const val TAG = "ImmortalDismiss"

  // Both flavours of the official HA Android app; the minimal (F-Droid) build is what
  // no-GMS devices like the Portal run, the other is the Play build.
  private val HA_PACKAGES =
      listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")

  /** The installed HA companion package (either flavour), or null if neither is present. */
  fun installedHaPackage(context: Context): String? {
    val pm = context.packageManager
    return HA_PACKAGES.firstOrNull { pkg ->
      runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
    }
  }

  /** The chosen app launch target, or null if none is set or it's no longer installed. */
  fun chosenComponent(context: Context): ComponentName? {
    val flat = ScreensaverConfig.load(context).dismissAppComponent ?: return null
    val cn = ComponentName.unflattenFromString(flat) ?: return null
    return runCatching { context.packageManager.getActivityInfo(cn, 0); cn }.getOrNull()
  }

  /** Friendly label of the current dismiss target, or null when it resolves to the launcher. */
  fun chosenLabel(context: Context): String? {
    val cfg = ScreensaverConfig.load(context)
    cfg.dismissHaDashboard?.let { path ->
      if (installedHaPackage(context) == null) return null // HA gone → effectively launcher
      val p = path.trim()
      return if (p.isBlank()) "Home Assistant" else "Home Assistant · $p"
    }
    val cn = chosenComponent(context) ?: return null
    val pm = context.packageManager
    val raw = runCatching { pm.getActivityInfo(cn, 0).loadLabel(pm).toString() }.getOrNull()
    return raw?.let { Curation.displayLabel(cn.packageName, it) }
  }

  /**
   * Launch the chosen target, if any. Called from the dream's tap-to-dismiss handler.
   * Returns true if something was launched. Best-effort: any failure is swallowed so the
   * dream still finishes to the launcher.
   */
  fun launchChosenApp(context: Context): Boolean {
    val cfg = ScreensaverConfig.load(context)

    // 1. Home Assistant dashboard / app.
    cfg.dismissHaDashboard?.let { path ->
      val pkg = installedHaPackage(context) ?: return false
      return runCatching {
            val intent =
                if (path.isBlank()) {
                  context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(
                      Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
                } else {
                  Intent(Intent.ACTION_VIEW, Uri.parse(haDeepLink(path)))
                      .setPackage(pkg)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            true
          }
          .onFailure { Log.w(TAG, "couldn't open HA dashboard '$path': $it") }
          .getOrDefault(false)
    }

    // 2. A plain app.
    val cn = chosenComponent(context) ?: return false
    return runCatching {
          context.startActivity(
              Intent(Intent.ACTION_MAIN)
                  .addCategory(Intent.CATEGORY_LAUNCHER)
                  .setComponent(cn)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          true
        }
        .onFailure { Log.w(TAG, "couldn't open dismiss target $cn: $it") }
        .getOrDefault(false)
  }

  /** An app's launcher icon as an [ImageBitmap], or null if it can't be loaded. */
  fun appIcon(context: Context, pkg: String): ImageBitmap? =
      runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap() }
          .getOrNull()

  /**
   * Turn user-entered dashboard text into a `homeassistant://navigate/<path>` deep link.
   * Accepts a bare path ("today-home/security"), a leading slash ("/lovelace/0"), a full
   * `homeassistant://` URI (used verbatim), or a pasted `http(s)://host/<path>` URL (the
   * host is stripped). Only called with non-blank input.
   */
  fun haDeepLink(input: String): String {
    val p = input.trim()
    if (p.startsWith("homeassistant://")) return p
    val rel =
        when {
          p.startsWith("http://") || p.startsWith("https://") ->
              p.substringAfter("://").substringAfter('/', "")
          else -> p
        }
            .trim()
            .trimStart('/')
    return "homeassistant://navigate/$rel"
  }
}

/** One installed, launchable app shown in the picker. */
private data class PickEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
)

/**
 * Picker for the "open this when the screensaver is dismissed" setting. Offers a Home
 * Assistant section (only when an HA app is installed) with an optional dashboard deep
 * link, then the Immortal launcher (default) and the installed apps. Reached from the
 * screensaver settings.
 */
class ScreensaverDismissAppActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { DismissAppScreen { finish() } } }
  }
}

@Composable
private fun DismissAppScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val haPkg = remember { ScreensaverDismiss.installedHaPackage(context) }
  val initial = remember { ScreensaverConfig.load(context) }

  // Three-way selection: HA mode, a specific app component, or neither (launcher).
  var haMode by remember { mutableStateOf(initial.dismissHaDashboard != null) }
  var haPath by remember { mutableStateOf(initial.dismissHaDashboard ?: "") }
  var selectedComponent by remember { mutableStateOf(initial.dismissAppComponent) }

  // null = still loading; emptyList would mean "no apps" (shouldn't happen on a Portal).
  var apps by remember { mutableStateOf<List<PickEntry>?>(null) }
  var haIcon by remember { mutableStateOf<ImageBitmap?>(null) }
  // Enumerating + decoding icons is slow; do it off the main thread.
  LaunchedEffect(Unit) {
    thread {
      haPkg?.let { haIcon = ScreensaverDismiss.appIcon(context, it) }
      apps = loadLaunchableApps(context)
    }
  }

  BackHandler { onDone() }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 24.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      // Header with a back affordance, matching the glyph idiom of our other sub-pages.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier.size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f) { onDone() },
            contentAlignment = Alignment.Center,
        ) {
          Text("←", color = Color.White, fontSize = 26.sp)
        }
        Spacer(Modifier.size(14.dp))
        Text(
            "Open when dismissed",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
      }
      Text(
          "Choose what opens when you tap the screensaver to wake the Portal.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp, start = 58.dp),
      )
      Spacer(Modifier.size(26.dp))

      // --- Home Assistant (only when the companion app is installed) -----------------
      if (haPkg != null) {
        SectionLabel("Home Assistant")
        Card {
          DismissRow(
              icon = haIcon,
              title = "Home Assistant",
              subtitle = "Open your Home Assistant dashboard on tap.",
              selected = haMode,
          ) {
            haMode = true
            selectedComponent = null
            ScreensaverConfig.setDismissHaDashboard(context, haPath)
          }
          if (haMode) {
            Divider()
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
              Text("Dashboard", color = Color.White, fontSize = 15.sp)
              OutlinedTextField(
                  value = haPath,
                  onValueChange = {
                    // Single-line: strip any newline the IME might inject.
                    val v = it.replace("\n", "")
                    haPath = v
                    ScreensaverConfig.setDismissHaDashboard(context, v)
                  },
                  placeholder = { Text("today-home/security", color = Color(0xFF6E6E6E)) },
                  singleLine = true,
                  shape = RoundedCornerShape(12.dp),
                  modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(top = 8.dp),
              )
              Text(
                  "The path after your Home Assistant address (e.g. lovelace/0). " +
                      "Leave blank to open your default dashboard.",
                  color = Color(0xFF8A8A8A),
                  fontSize = 13.sp,
                  modifier = Modifier.padding(top = 8.dp),
              )
            }
          }
        }
        Spacer(Modifier.size(26.dp))
      }

      // --- Launcher + installed apps -------------------------------------------------
      SectionLabel(if (haPkg != null) "Or an app" else "Open")
      Card {
        DismissRow(
            icon = null,
            title = "Immortal launcher",
            subtitle = "Return to your home screen (default).",
            selected = !haMode && selectedComponent == null,
        ) {
          ScreensaverConfig.setDismissLauncher(context)
          onDone()
        }

        val list = apps
        if (list == null) {
          Divider()
          Text(
              "Finding your apps…",
              color = Color(0xFF9A9A9A),
              fontSize = 15.sp,
              modifier = Modifier.padding(18.dp),
          )
        } else {
          list.forEach { app ->
            Divider()
            val flat = app.component.flattenToString()
            DismissRow(
                icon = app.icon,
                title = app.label,
                subtitle = null,
                selected = !haMode && selectedComponent == flat,
            ) {
              ScreensaverConfig.setDismissApp(context, flat)
              onDone()
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DismissRow(
    icon: ImageBitmap?,
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onClick() }
              .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (icon != null) {
      Image(
          bitmap = icon,
          contentDescription = null,
          modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
      )
    } else {
      Spacer(Modifier.size(40.dp))
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(title, color = Color.White, fontSize = 17.sp)
      if (subtitle != null) {
        Text(
            subtitle,
            color = Color(0xFF9A9A9A),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
    // Visual only — the whole row is the focus/click target.
    RadioButton(selected = selected, onClick = null)
  }
}

/**
 * Installed launchable apps, mirroring the launcher's own curation (hidden packages,
 * label overrides, one tile per package) so the picker shows the same friendly set the
 * user sees on the home screen — minus Immortal itself.
 */
private fun loadLaunchableApps(context: Context): List<PickEntry> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return pm.queryIntentActivities(intent, 0)
      .filter {
        val pkg = it.activityInfo.packageName
        pkg != context.packageName && !Curation.isHidden(pkg, "")
      }
      .mapNotNull { ri ->
        runCatching {
              val ai = ri.activityInfo
              val rawLabel = ri.loadLabel(pm).toString()
              val bmp: Bitmap = ri.loadIcon(pm).toBitmap(144, 144)
              PickEntry(
                  label = Curation.displayLabel(ai.packageName, rawLabel),
                  component = ComponentName(ai.packageName, ai.name),
                  icon = bmp.asImageBitmap(),
              ) to rawLabel
            }
            .getOrNull()
      }
      .filterNot { (_, raw) -> raw in Curation.hiddenLabels }
      .map { it.first }
      .distinctBy { it.component.packageName }
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
}
