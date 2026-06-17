/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * The Immortal App Store. Two screens in one activity: a browse view (search,
 * updates, categories of icon-rich app cards) and a per-app detail view
 * (description, author, source, credit, contextual Install/Open/Update action).
 * Fully driveable by touch or the Portal TV remote.
 */
class StoreActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { StoreRoot() } }
  }
}

// --- root: state + navigation -------------------------------------------------

@Composable
private fun StoreRoot() {
  val context = LocalContext.current
  val activity = context as? Activity
  var apps by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
  val status = remember { mutableStateMapOf<String, String>() }
  var updates by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
  var query by remember { mutableStateOf("") }
  var detail by remember { mutableStateOf<CatalogApp?>(null) }
  var pendingConfirm by remember { mutableStateOf<Intent?>(null) }

  val confirmLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

  LaunchedEffect(Unit) { StoreCatalog.loadCatalog(context) { apps = it } }
  LaunchedEffect(apps) {
    if (apps.isNotEmpty()) StoreCatalog.findUpdates(context, apps) { updates = it }
  }
  LaunchedEffect(pendingConfirm) {
    pendingConfirm?.let {
      confirmLauncher.launch(it)
      pendingConfirm = null
    }
  }

  DisposableEffect(Unit) {
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            val pkg = intent.getStringExtra(STORE_EXTRA_PKG) ?: return
            when (val s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
              PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingConfirm =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                status[pkg] = "Confirm to install…"
              }
              PackageInstaller.STATUS_SUCCESS -> {
                status[pkg] = "Installed ✓"
                updates = updates - pkg
              }
              else -> status[pkg] = "Failed (status=$s)"
            }
          }
        }
    val filter = IntentFilter(STORE_INSTALL_ACTION)
    if (Build.VERSION.SDK_INT >= 33)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }

  val install: (CatalogApp) -> Unit = { app ->
    StoreCatalog.install(context, app) { p, m ->
      status[p] = m
      if (m == "Installed ✓") updates = updates - p
    }
  }

  // One Back interceptor for both screens: detail closes, browse exits. Catches
  // the TV remote's Back before the focus system can swallow it.
  Surface(
      modifier =
          Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.key == Key.Back) {
              if (e.type == KeyEventType.KeyUp) {
                if (detail != null) detail = null else activity?.finish()
              }
              true
            } else false
          },
      color = MaterialTheme.colorScheme.background,
  ) {
    val current = detail
    if (current == null) {
      BrowseScreen(
          apps = apps,
          status = status,
          updates = updates,
          query = query,
          onQuery = { query = it },
          onOpenDetail = { detail = it },
          onInstall = install,
      )
    } else {
      BackHandler { detail = null }
      AppDetailScreen(
          app = current,
          status = status[current.packageName],
          hasUpdate = updates.containsKey(current.packageName),
          onBack = { detail = null },
          onInstall = { install(current) },
      )
    }
  }
}

// --- browse -------------------------------------------------------------------

@Composable
private fun BrowseScreen(
    apps: List<CatalogApp>,
    status: Map<String, String>,
    updates: Map<String, Long>,
    query: String,
    onQuery: (String) -> Unit,
    onOpenDetail: (CatalogApp) -> Unit,
    onInstall: (CatalogApp) -> Unit,
) {
  val context = LocalContext.current
  // Initial focus goes to the FIRST APP CARD, not the search field — focusing the
  // field would pop the keyboard over the whole store on open.
  val firstCardFocus = remember { FocusRequester() }
  LaunchedEffect(apps.isNotEmpty()) {
    if (apps.isNotEmpty()) runCatching { firstCardFocus.requestFocus() }
  }

  val filtered =
      remember(apps, query) {
        if (query.isBlank()) apps
        else
            apps.filter {
              it.name.contains(query, true) ||
                  it.description.contains(query, true) ||
                  (it.author ?: "").contains(query, true)
            }
      }
  val updateApps = remember(apps, updates) { apps.filter { it.packageName in updates.keys } }
  val firstPkg =
      remember(updateApps, filtered) {
        (updateApps.firstOrNull() ?: filtered.firstOrNull())?.packageName
      }

  LazyColumn(
      modifier = Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp).focusGroup(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item {
      Spacer(Modifier.height(64.dp))
      Text("App Store", fontSize = 34.sp, fontWeight = FontWeight.Bold)
      Text(
          "${apps.size} apps picked for Portal · community submissions welcome",
          style = MaterialTheme.typography.bodyMedium,
          color = Color(0xFFAAAAAA),
      )
      if (InstallDaemon.installPaused(context)) PausedBanner()
      else if (InstallDaemon.silentInstallOffline(context)) SilentOffBanner()
      Spacer(Modifier.height(10.dp))
      OutlinedTextField(
          value = query,
          onValueChange = onQuery,
          placeholder = { Text("Search apps…", color = Color(0xFF777777)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
          shape = RoundedCornerShape(14.dp),
      )
      Text(
          "📁  Have an APK file? Install it →",
          color = Color(0xFF8AB4F8),
          fontSize = 15.sp,
          modifier =
              Modifier.padding(top = 10.dp).tvFocusable(RoundedCornerShape(8.dp)) {
                context.startActivity(Intent(context, ApkBrowserActivity::class.java))
              },
      )
    }

    // The FocusRequester may only be attached once: it goes on the first card of the
    // FIRST section shown (updates if present, else the first category / results).
    val showUpdates = query.isBlank() && updateApps.isNotEmpty()
    fun cardModifier(app: CatalogApp, inFirstSection: Boolean): Modifier =
        if (inFirstSection && app.packageName == firstPkg)
            Modifier.focusRequester(firstCardFocus)
        else Modifier

    if (showUpdates) {
      item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          SectionHeader(
              if (updateApps.size == 1) "1 update available"
              else "${updateApps.size} updates available")
          Spacer(Modifier.weight(1f))
          if (updateApps.size > 1) ActionButton("Update all") { updateApps.forEach(onInstall) }
        }
      }
      items(updateApps, key = { "u:" + it.packageName }) { app ->
        AppCard(
            app, status[app.packageName], updates, onOpenDetail, onInstall,
            cardModifier(app, inFirstSection = true))
      }
    }

    if (query.isBlank()) {
      val categories = filtered.groupBy { it.category }
      categories.forEach { (cat, list) ->
        item { SectionHeader(cat) }
        items(list, key = { it.packageName }) { app ->
          AppCard(
              app, status[app.packageName], updates, onOpenDetail, onInstall,
              cardModifier(app, inFirstSection = !showUpdates))
        }
      }
    } else {
      item { SectionHeader(if (filtered.isEmpty()) "No matches" else "Results") }
      items(filtered, key = { it.packageName }) { app ->
        AppCard(
            app, status[app.packageName], updates, onOpenDetail, onInstall,
            cardModifier(app, inFirstSection = true))
      }
    }
    item { Spacer(Modifier.height(40.dp)) }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
      text,
      fontSize = 20.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(top = 14.dp),
  )
}

@Composable
private fun AppCard(
    app: CatalogApp,
    status: String?,
    updates: Map<String, Long>,
    onOpenDetail: (CatalogApp) -> Unit,
    onInstall: (CatalogApp) -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val installed = StoreCatalog.isInstalled(context, app.packageName)
  val compatible = StoreCatalog.isCompatible(app)
  val hasUpdate = app.packageName in updates.keys

  Card(
      modifier =
          modifier
              .fillMaxWidth()
              .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { onOpenDetail(app) },
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      AppIcon(app, 52.dp)
      Column(modifier = Modifier.weight(1f).padding(start = 14.dp, end = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(app.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
          app.author?.let {
            Text(
                "  ·  $it",
                fontSize = 12.sp,
                color = Color(0xFF8A8A8A),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
          }
        }
        Text(
            app.description,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB9B9B9),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        status?.let {
          Text(
              it,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 3.dp),
          )
        }
      }
      when {
        !compatible -> Chip(StoreCatalog.incompatibleLabel(app.minSdk ?: 0), Color(0xFF6B6B6B))
        hasUpdate -> ActionButton("Update") { onInstall(app) }
        installed -> OpenButton(app)
        else -> ActionButton("Install") { onInstall(app) }
      }
    }
  }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
  val src = remember { MutableInteractionSource() }
  Button(
      onClick = onClick,
      interactionSource = src,
      modifier =
          Modifier.heightIn(min = 48.dp).widthIn(min = 108.dp).focusRing(src, RoundedCornerShape(20.dp)),
  ) {
    Text(label)
  }
}

@Composable
private fun OpenButton(app: CatalogApp) {
  val context = LocalContext.current
  val src = remember { MutableInteractionSource() }
  OutlinedButton(
      onClick = {
        runCatching {
          context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            context.startActivity(it)
          }
        }
      },
      interactionSource = src,
      modifier =
          Modifier.heightIn(min = 48.dp).widthIn(min = 108.dp).focusRing(src, RoundedCornerShape(20.dp)),
  ) {
    Text("Open")
  }
}

@Composable
private fun Chip(text: String, color: Color) {
  Surface(color = color.copy(alpha = 0.22f), shape = RoundedCornerShape(8.dp)) {
    Text(
        text,
        fontSize = 12.sp,
        color = Color(0xFFDDDDDD),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
    )
  }
}

// --- app icon (catalog icon or monogram fallback) -------------------------------

private val monogramPalette =
    listOf(
        Color(0xFF3B6BC9), Color(0xFF8E4FC9), Color(0xFF2E8B6B), Color(0xFFB9552E),
        Color(0xFF4F7DA0), Color(0xFFA04F70), Color(0xFF6B8E2E), Color(0xFF73598C))

@Composable
private fun AppIcon(app: CatalogApp, size: androidx.compose.ui.unit.Dp) {
  val context = LocalContext.current
  val bmp by
      produceState<Bitmap?>(initialValue = StoreIcons.cached(app.packageName), app.packageName) {
        if (value == null) StoreIcons.load(context, app) { value = it }
      }
  val b = bmp
  if (b != null) {
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)),
    )
  } else {
    val color = monogramPalette[Math.abs(app.name.hashCode()) % monogramPalette.size]
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(color),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          app.name.first().uppercase(),
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = (size.value * 0.42f).sp,
      )
    }
  }
}

// --- detail ---------------------------------------------------------------------

@Composable
private fun AppDetailScreen(
    app: CatalogApp,
    status: String?,
    hasUpdate: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
) {
  val context = LocalContext.current
  val installed = StoreCatalog.isInstalled(context, app.packageName)
  val compatible = StoreCatalog.isCompatible(app)
  val backFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 40.dp)
              .focusGroup(),
  ) {
    Spacer(Modifier.height(56.dp))
    Text(
        "←  App Store",
        color = Color(0xFF8AB4F8),
        fontSize = 16.sp,
        modifier =
            Modifier.focusRequester(backFocus).tvFocusable(RoundedCornerShape(8.dp)) { onBack() },
    )
    Spacer(Modifier.height(22.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
      AppIcon(app, 96.dp)
      Column(modifier = Modifier.padding(start = 22.dp)) {
        Text(app.name, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        app.author?.let { Text(it, fontSize = 15.sp, color = Color(0xFF9A9A9A)) }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Chip(if (app.source == "fdroid") "F-Droid" else "Direct download", Color(0xFF2E6BE6))
          if (!compatible) Chip(StoreCatalog.incompatibleLabel(app.minSdk ?: 0), Color(0xFFB9552E))
          if (app.devices.size == 1)
              Chip(
                  if (app.devices.first() == "tv") "Portal TV" else "Touch Portals",
                  Color(0xFF2E8B6B))
        }
      }
    }

    Spacer(Modifier.height(20.dp))
    status?.let {
      Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
      Spacer(Modifier.height(10.dp))
    }
    when {
      !compatible ->
          Text(
              "This app needs a newer Android version than this Portal has.",
              color = Color(0xFFCC8866),
              fontSize = 15.sp,
          )
      hasUpdate -> ActionButton("Update") { onInstall() }
      installed ->
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OpenButton(app)
            ActionButton("Reinstall") { onInstall() }
          }
      else -> ActionButton("Install") { onInstall() }
    }

    Spacer(Modifier.height(24.dp))
    Text(
        app.longDescription ?: app.description,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = Color(0xFFDDDDDD),
    )

    Spacer(Modifier.height(28.dp))
    Text("Details", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    DetailRow("Version", app.versionCode?.toString() ?: if (app.source == "fdroid") "Latest from F-Droid" else "Latest release")
    DetailRow("Package", app.packageName)
    app.homepage?.let { url ->
      Text(
          "Website  ·  $url",
          color = Color(0xFF8AB4F8),
          fontSize = 14.sp,
          modifier =
              Modifier.padding(vertical = 6.dp).tvFocusable(RoundedCornerShape(8.dp)) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
              },
      )
    }
    app.submittedBy?.let { DetailRow("Submitted by", it) }
    Spacer(Modifier.height(48.dp))
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(modifier = Modifier.padding(vertical = 6.dp)) {
    Text(label, color = Color(0xFF8A8A8A), fontSize = 14.sp, modifier = Modifier.width(130.dp))
    Text(value, color = Color(0xFFDDDDDD), fontSize = 14.sp)
  }
}

// --- banners ----------------------------------------------------------------------

@Composable
private fun PausedBanner() {
  Card(
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0x33FFB300)),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
          "Installing new apps is paused",
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = Color(0xFFFFD180),
      )
      Text(
          "On first-gen Portals this happens after a reboot. Connect to your computer and " +
              "run the Immortal installer again to add apps. Everything else keeps working.",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFFEEEEEE),
          modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}

@Composable
private fun SilentOffBanner() {
  Card(
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0x332E6BE6)),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
          "Silent install is off",
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold,
          color = Color(0xFF9EC1FF),
      )
      Text(
          "The install helper stops after a reboot, so apps now install through the " +
              "system dialog. Most still install fine; a few (some Play-store apps) show " +
              "“There was a problem parsing the package.” To restore silent, one-tap " +
              "installs that work for every app, reconnect to your computer and run the " +
              "Immortal installer again.",
          style = MaterialTheme.typography.bodySmall,
          color = Color(0xFFEEEEEE),
          modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}
