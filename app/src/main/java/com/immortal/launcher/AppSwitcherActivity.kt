/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.util.Locale
import kotlin.concurrent.thread

/**
 * App switcher: recently-used apps (most-recent first) with per-app notification badges, usage
 * info, and actions; plus a search box that turns it into an all-apps drawer. Opened from the
 * quick-button cluster ([QuickBar]).
 *
 * Recents/usage come from [UsageStatsManager] (foreground history — not running processes, which
 * a non-system app can't enumerate; and no live thumbnails on this API level). Badges come from
 * [MediaNotificationListenerService]'s active notifications.
 */
class AppSwitcherActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { AppSwitcherScreen { finish() } } }
  }
}

private data class AppItem(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val lastUsedMs: Long = 0L,
    val fgMs: Long = 0L,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppSwitcherScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  var recents by remember { mutableStateOf<List<AppItem>?>(null) }
  var allApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
  var badges by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
  var query by remember { mutableStateOf("") }
  var menuFor by remember { mutableStateOf<AppItem?>(null) }

  LaunchedEffect(Unit) {
    badges = MediaNotificationListenerService.activeCountsByPackage()
    thread {
      recents = loadRecents(context)
      allApps = loadAllApps(context)
    }
  }

  BackHandler { if (menuFor != null) menuFor = null else onDone() }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xFF101012))
              .padding(horizontal = 28.dp, vertical = 24.dp),
  ) {
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text("Search apps", color = Color(0xFF777777)) },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )

    val searching = query.isNotBlank()
    val list =
        if (searching)
            allApps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        else recents

    Text(
        if (searching) "All apps" else "Recent apps",
        color = Color(0xFF9A9A9A),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp),
    )

    when {
      list == null -> Hint("Loading…")
      list.isEmpty() && searching -> Hint("No apps match “${query.trim()}”.")
      list.isEmpty() -> Hint("No recent apps yet. Open a few and they'll show up here.")
      else ->
          LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 132.dp),
              modifier = Modifier.fillMaxSize(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(list) { app ->
              AppTile(
                  app = app,
                  badge = badges[app.component.packageName] ?: 0,
                  subtitle = if (searching) null else recencyLine(app),
                  onClick = {
                    switchTo(context, app.component)
                    onDone()
                  },
                  onLongClick = { menuFor = app },
              )
            }
          }
    }
  }

  menuFor?.let { app ->
    AppActionMenu(
        app = app,
        onOpen = {
          switchTo(context, app.component)
          onDone()
        },
        onAppInfo = {
          open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(app)))
          onDone()
        },
        onUninstall = {
          open(context, Intent(Intent.ACTION_DELETE, pkgUri(app)))
          menuFor = null
        },
        onClose = {
          runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .killBackgroundProcesses(app.component.packageName)
          }
          menuFor = null
        },
        onDismiss = { menuFor = null },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppItem,
    badge: Int,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
  Column(
      modifier =
          Modifier.clip(RoundedCornerShape(16.dp))
              .combinedClickable(onClick = onClick, onLongClick = onLongClick)
              .padding(vertical = 14.dp, horizontal = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    BadgedBox(
        badge = { if (badge > 0) Badge { Text(if (badge > 9) "9+" else "$badge") } },
        modifier = Modifier.size(56.dp),
    ) {
      Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(56.dp))
    }
    Text(
        app.label,
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
    )
    if (subtitle != null) {
      Text(
          subtitle,
          color = Color(0xFF7C7C7C),
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun AppActionMenu(
    app: AppItem,
    onOpen: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
    Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(18.dp)) {
      Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text(
            app.label,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
        ActionRow("Open") { onOpen() }
        ActionRow("App info") { onAppInfo() }
        ActionRow("Close (free up memory)") { onClose() }
        ActionRow("Uninstall", danger = true) { onUninstall() }
      }
    }
  }
}

@Composable
private fun ActionRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
  Text(
      label,
      color = if (danger) Color(0xFFE0908A) else Color.White,
      fontSize = 16.sp,
      modifier = Modifier.fillMaxWidth().tvFocusableRow { onClick() }.padding(horizontal = 22.dp, vertical = 14.dp),
  )
}

@Composable
private fun Hint(text: String) {
  Text(text, color = Color(0xFF9A9A9A), fontSize = 15.sp, modifier = Modifier.padding(top = 24.dp, start = 4.dp))
}

// --- helpers ------------------------------------------------------------------

private fun switchTo(context: Context, component: ComponentName) {
  runCatching {
    context.startActivity(
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}

private fun open(context: Context, intent: Intent) {
  runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun pkgUri(app: AppItem): Uri = Uri.parse("package:${app.component.packageName}")

private fun recencyLine(app: AppItem): String {
  val ago = ago(System.currentTimeMillis() - app.lastUsedMs)
  val today = duration(app.fgMs)
  return if (today.isNotEmpty()) "$ago · $today today" else ago
}

private fun ago(ms: Long): String {
  val s = ms / 1000
  return when {
    s < 45 -> "just now"
    s < 3600 -> "${s / 60}m ago"
    s < 86400 -> "${s / 3600}h ago"
    else -> "${s / 86400}d ago"
  }
}

private fun duration(ms: Long): String {
  val m = ms / 60000
  return when {
    m <= 0 -> ""
    m < 60 -> "${m}m"
    else -> "${m / 60}h ${m % 60}m"
  }
}

/** Recently-used apps, most-recent first, with usage time; launchable + curated, minus Immortal. */
private fun loadRecents(context: Context): List<AppItem> {
  val usm =
      context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
          ?: return emptyList()
  val now = System.currentTimeMillis()
  val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 24L * 3600_000, now)
  if (stats.isNullOrEmpty()) return emptyList()

  val lastUsed = HashMap<String, Long>()
  val fg = HashMap<String, Long>()
  for (s in stats) {
    if (s.lastTimeUsed > (lastUsed[s.packageName] ?: 0)) lastUsed[s.packageName] = s.lastTimeUsed
    fg[s.packageName] = (fg[s.packageName] ?: 0) + s.totalTimeInForeground
  }

  val pm = context.packageManager
  return lastUsed.entries
      .sortedByDescending { it.value }
      .mapNotNull { (pkg, last) ->
        if (pkg == context.packageName || Curation.isHidden(pkg, "")) return@mapNotNull null
        // Drop epoch/stale entries UsageStats sometimes reports (e.g. a never-foregrounded
        // system service with lastTimeUsed≈0 showing as "20000d ago").
        if (last <= 0L || now - last > 7L * 86400_000) return@mapNotNull null
        val component = pm.getLaunchIntentForPackage(pkg)?.component ?: return@mapNotNull null
        runCatching {
              val ai = pm.getApplicationInfo(pkg, 0)
              val raw = pm.getApplicationLabel(ai).toString()
              val bmp: Bitmap = pm.getApplicationIcon(ai).toBitmap(144, 144)
              AppItem(Curation.displayLabel(pkg, raw), component, bmp.asImageBitmap(), last, fg[pkg] ?: 0)
            }
            .getOrNull()
      }
      .filterNot { it.label in Curation.hiddenLabels }
      .take(16)
}

/** All installed launchable apps (for search / drawer), alphabetised, curated, minus Immortal. */
private fun loadAllApps(context: Context): List<AppItem> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return pm.queryIntentActivities(intent, 0)
      .filter { it.activityInfo.packageName != context.packageName && !Curation.isHidden(it.activityInfo.packageName, "") }
      .mapNotNull { ri ->
        runCatching {
              val ai = ri.activityInfo
              val raw = ri.loadLabel(pm).toString()
              val bmp: Bitmap = ri.loadIcon(pm).toBitmap(144, 144)
              AppItem(
                  Curation.displayLabel(ai.packageName, raw),
                  ComponentName(ai.packageName, ai.name),
                  bmp.asImageBitmap()) to raw
            }
            .getOrNull()
      }
      .filterNot { (_, raw) -> raw in Curation.hiddenLabels }
      .map { it.first }
      .distinctBy { it.component.packageName }
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
}
