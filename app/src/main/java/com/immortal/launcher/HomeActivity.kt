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
import android.os.BatteryManager
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageInstaller
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.roundToInt
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val folder: String? = null,
)

/** Calls→stock-home bridge: how many system Back presses reach Meta's launcher, and the gap
 *  between them (verified on the Portal TV — 5 presses lands on the stock launcher's Apps tab). */
private const val STOCK_HOME_BACK_PRESSES = 5
private const val STOCK_HOME_BACK_INTERVAL_MS = 300L

/**
 * The custom Portal home launcher. Replaces the stock Aloha home (selected via
 * `cmd package set-home-activity`). Shows a clock/date/weather header, an App
 * Store tile, and a grid of every installed launchable app. Built for Portal's
 * form factor: dark theme, top 64dp reserved for the system overlay, large
 * touch targets, landscape.
 */
class HomeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enterImmersive()
    // First launch: show the friendly tour once so new users aren't dropped in cold.
    if (!HelpActivity.hasSeen(this)) {
      window.decorView.post {
        runCatching { startActivity(Intent(this, HelpActivity::class.java)) }
      }
    }
    setContent {
      SampleAppTheme(darkTheme = true) {
        LauncherScreen(
            onLaunch = { cn ->
              runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
            onOpenStore = {
              runCatching { startActivity(Intent(this, StoreActivity::class.java)) }
            },
            onOpenHelp = {
              runCatching { startActivity(Intent(this, HelpActivity::class.java)) }
            },
            onStartScreensaver = {
              runCatching {
                startActivity(Intent(this, PhotoFramePreviewActivity::class.java))
              }
            },
            onExitHome = { launchStockHome() },
            onUninstall = { pkg ->
              // System uninstall dialog; no special permission needed.
              runCatching {
                startActivity(
                    Intent(Intent.ACTION_DELETE)
                        .setData(android.net.Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
        )
      }
    }
  }

  // Re-assert immersive fullscreen whenever the launcher regains focus — e.g.
  // after the screensaver or another app closes — since the system restores the
  // bars when another window takes over.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) enterImmersive()
  }

  // Self-heal: if anything (e.g. the stock launcher) reset our screensaver
  // settings, put them back every time Immortal comes to the foreground.
  override fun onResume() {
    super.onResume()
    // The user is back on Immortal, so any stock-launcher call handoff is over:
    // allow the photo frame to resume its normal screensaver behaviour.
    DreamPolicy.inStockHandoff = false
    SettingsGuard.reaffirmScreensaver(this)
    // The user is back on the launcher: end the idle session and, inside the overnight window,
    // arm the touch-renewed "you have the device" session. SleepScheduler owns that policy.
    SleepScheduler.onReturnedToLauncher(this)
  }

  override fun onPause() {
    super.onPause()
    // Don't carry a pending overnight re-sleep into another activity or a real sleep.
    SleepScheduler.onLeftLauncher()
  }

  // dispatchTouchEvent is the top of the input chain, so it sees every touch before Compose
  // consumes it. Renews the overnight session; a no-op outside the window.
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    SleepScheduler.onInteraction(this)
    return super.dispatchTouchEvent(ev)
  }

  private fun enterImmersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }

  /**
   * The "Calls" tile bridges to the STOCK Portal home — the only caller Meta trusts
   * to launch Contacts/calling/camera.
   *
   * Why a deep link and not a plain HOME launch: the Contacts app
   * (com.facebook.alohaapps.contacts) enforces a signature-based trusted-caller
   * check (Meta's com.facebook.secure framework) that Immortal can never satisfy,
   * so we must route through the trusted stock launcher. A plain MAIN/HOME launch
   * cold-starts the stock launcher into its idle "dream" face, whose
   * DREAMING_STOPPED then makes our own screensaver relaunch over the top and trap
   * the user (the reported "Calls kicks me back into Immortal"). The stock
   * launcher's `portal://launcher/home` VIEW deep link instead resumes its
   * interactive Home tab directly — the Contacts/Favorites calling surface — and we
   * mark a bridge in flight so [DreamPolicy] doesn't claw the frame back during the
   * transition.
   */
  private fun launchStockHome() {
    // Suppress the screensaver-relaunch race while the stock home comes forward, and
    // keep suppressing the holding-frame relaunch until the user returns to Immortal
    // (cleared in onResume) so the frame can't slam over an in-progress call.
    DreamPolicy.bridgeAt = System.currentTimeMillis()
    DreamPolicy.inStockHandoff = true

    // Preferred path: a short burst of system Back presses reliably surfaces Meta's stock
    // launcher (verified on the Portal TV) — unlike the portal:// deep link or a HOME launch,
    // which cold-start ripleyhome's idle dream and bounce the user. Needs our accessibility
    // service (baseline-enabled on provisioned devices); falls back to the deep link otherwise.
    if (RemoteInput.available()) {
      RemoteInput.backRepeat(STOCK_HOME_BACK_PRESSES, STOCK_HOME_BACK_INTERVAL_MS)
      return
    }

    fun fire(intent: Intent): Boolean =
        runCatching {
              startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              true
            }
            .getOrDefault(false)

    // 1) Deep-link straight to the touchscreen stock launcher's Home tab.
    val deepLink =
        Intent(Intent.ACTION_VIEW, Uri.parse("portal://launcher/home"))
            .setPackage("com.facebook.alohaapps.launcher")
    if (fire(deepLink)) return

    // 2) Fallback for models without the portal:// deep link (e.g. the Portal TV's
    //    ripleyhome): this device's real stock HOME, excluding ourselves and the
    //    system fallback homes.
    val stock =
        packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo }
            .firstOrNull {
              it.packageName != packageName &&
                  it.packageName != "com.android.settings" &&
                  it.packageName != "com.android.tv.settings" &&
                  !it.name.contains("FallbackHome", ignoreCase = true)
            }
    if (stock != null) {
      fire(
          Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_LAUNCHER)
              .setComponent(ComponentName(stock.packageName, stock.name)))
    }
  }
}

@Composable
private fun LauncherScreen(
    onLaunch: (ComponentName) -> Unit,
    onOpenStore: () -> Unit,
    onOpenHelp: () -> Unit,
    onStartScreensaver: () -> Unit,
    onExitHome: () -> Unit,
    onUninstall: (String) -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Bumped whenever an app is installed/removed so the grid refreshes live.
  var reload by remember { mutableStateOf(0) }
  DisposableEffect(Unit) {
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: android.content.Context, i: Intent) {
            reload++
          }
        }
    val filter =
        android.content.IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addAction(Intent.ACTION_PACKAGE_REPLACED)
          addDataScheme("package")
        }
    if (android.os.Build.VERSION.SDK_INT >= 33)
        context.registerReceiver(
            receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  val apps by
      produceState(initialValue = emptyList<AppEntry>(), reload) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
      }
  var editMode by remember { mutableStateOf(false) }
  var openFolder by remember { mutableStateOf<String?>(null) }

  // Immortal Settings the home screen reflects (tile size, and the optional weather
  // widget's mode/unit), re-read on resume so a change applies the moment the user
  // comes back to the home screen.
  var tileSize by remember { mutableStateOf(ImmortalSettings.load(context).tileSize) }
  var weatherWidget by remember { mutableStateOf(ImmortalSettings.load(context).weatherWidget) }
  var weatherFahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        val s = ImmortalSettings.load(context)
        tileSize = s.tileSize
        weatherWidget = s.weatherWidget
        weatherFahrenheit = ImmortalSettings.useFahrenheit(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  // Remote support: land focus on the grid at startup so the D-pad works on the TV.
  val homeGridFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { homeGridFocus.requestFocus() } }

  // User-created folder assignments (via drag-drop), persisted, overlaying the
  // curated defaults: a user override wins over Curation.folderFor(). An empty
  // string is an explicit "ungrouped" override (used when dragging out of a
  // folder), so it beats a curated default too.
  val assignments = remember { mutableStateMapOf<String, String>() }
  LaunchedEffect(Unit) { assignments.putAll(UserLayout.load(context)) }
  val appsEff =
      remember(apps, assignments.toMap()) {
        apps.map { a ->
          val pkg = a.component.packageName
          val eff = if (assignments.containsKey(pkg)) assignments[pkg]!!.ifEmpty { null } else a.folder
          a.copy(folder = eff)
        }
      }
  val ungrouped = remember(appsEff) { appsEff.filter { it.folder == null } }
  val folderNames = remember(appsEff) { appsEff.mapNotNull { it.folder }.distinct().sorted() }

  // --- drag-and-drop folder management (Manage mode) --------------------------
  val tileBounds = remember { mutableStateMapOf<String, Rect>() }
  var containerOrigin by remember { mutableStateOf(Offset.Zero) }
  var dragPkg by remember { mutableStateOf<String?>(null) }
  var dragPos by remember { mutableStateOf(Offset.Zero) }
  // Pending folder creation awaiting a name (source+target packages).
  var pendingPair by remember { mutableStateOf<Pair<String, String>?>(null) }
  // Folder currently being renamed.
  var renaming by remember { mutableStateOf<String?>(null) }

  fun persist() = UserLayout.save(context, assignments.toMap())
  fun assign(pkg: String, folder: String) {
    assignments[pkg] = folder
    persist()
  }
  fun createFolder(a: String, b: String, name: String) {
    val n = name.trim().ifEmpty { "Folder" }
    assignments[a] = n
    assignments[b] = n
    persist()
    openFolder = n
  }
  fun renameFolder(old: String, raw: String) {
    val new = raw.trim()
    if (new.isEmpty() || new == old) return
    appsEff.filter { it.folder == old }.forEach { assignments[it.component.packageName] = new }
    persist()
    openFolder = new
  }
  fun moveOut(pkg: String) {
    val folder = appsEff.firstOrNull { it.component.packageName == pkg }?.folder ?: return
    assignments[pkg] = "" // explicit ungroup
    // No single-app folders: if only one remains, pop it out too.
    val remaining = appsEff.filter { it.folder == folder && it.component.packageName != pkg }
    if (remaining.size == 1) assignments[remaining[0].component.packageName] = ""
    persist()
    if (remaining.size <= 1) openFolder = null
  }
  fun onDrop(sourcePkg: String, targetKey: String?) {
    if (targetKey == null) return
    when {
      targetKey.startsWith(FOLDER_KEY) -> assign(sourcePkg, targetKey.removePrefix(FOLDER_KEY))
      targetKey.startsWith(APP_KEY) -> {
        val targetPkg = targetKey.removePrefix(APP_KEY)
        if (targetPkg == sourcePkg) return
        pendingPair = sourcePkg to targetPkg // ask for a name first
      }
    }
  }

  // --- over-the-air self-update -----------------------------------------------
  var update by remember { mutableStateOf<UpdateInfo?>(null) }
  var updateStatus by remember { mutableStateOf<String?>(null) }
  var pendingConfirm by remember { mutableStateOf<Intent?>(null) }
  val confirmLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
  // Check on launch, then periodically while the launcher runs (it's the
  // long-lived home, so a one-shot check would go stale). The Updates tile also
  // lets the user force a check at any time.
  LaunchedEffect(Unit) {
    while (true) {
      UpdateManager.checkForUpdate(context) { update = it }
      delay(UPDATE_CHECK_INTERVAL_MS)
    }
  }
  LaunchedEffect(pendingConfirm) {
    pendingConfirm?.let {
      confirmLauncher.launch(it)
      pendingConfirm = null
    }
  }
  DisposableEffect(Unit) {
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: android.content.Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
              PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingConfirm =
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                updateStatus = "Confirm to update…"
              }
              PackageInstaller.STATUS_SUCCESS -> updateStatus = "Updated"
              else -> updateStatus = "Update failed"
            }
          }
        }
    val filter = android.content.IntentFilter(UPDATE_INSTALL_ACTION)
    if (android.os.Build.VERSION.SDK_INT >= 33)
        context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }

  CompositionLocalProvider(LocalTileDp provides tileDpFor(tileSize)) {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
    Column(
        modifier =
            Modifier.fillMaxHeight()
                // Cap the content width and center it so the grid stays
                // comfortably sized on large displays (e.g. Portal+ 1920px)
                // instead of stretching 6 columns across the whole panel. On the
                // smaller models this is effectively full-width (unchanged).
                // (widthIn must precede fillMaxWidth so the cap wins, then align
                // centers the capped content.)
                .align(Alignment.TopCenter)
                .widthIn(max = 1264.dp)
                .fillMaxWidth()
                // Top is padded clear of the 60dp systemui status-bar window,
                // which silently eats touches even while hidden in immersive —
                // so header action buttons stay tappable.
                .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 24.dp)
    ) {
      HeaderBar(onScreensaver = onStartScreensaver)
      Spacer(Modifier.size(20.dp))
      Box(
          modifier =
              Modifier.fillMaxWidth()
                  .weight(1f)
                  .onGloballyPositioned { containerOrigin = it.boundsInWindow().topLeft }
                  .pointerInput(editMode) {
                    if (!editMode) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                          val win = local + containerOrigin
                          dragPkg =
                              tileBounds.entries
                                  .firstOrNull {
                                    it.key.startsWith(APP_KEY) && it.value.contains(win)
                                  }
                                  ?.key
                                  ?.removePrefix(APP_KEY)
                          dragPos = win
                        },
                        onDrag = { change, delta ->
                          change.consume()
                          dragPos += delta
                        },
                        onDragEnd = {
                          dragPkg?.let { src ->
                            val target =
                                tileBounds.entries
                                    .firstOrNull {
                                      it.key != APP_KEY + src && it.value.contains(dragPos)
                                    }
                                    ?.key
                            onDrop(src, target)
                          }
                          dragPkg = null
                        },
                        onDragCancel = { dragPkg = null },
                    )
                  },
      ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumnsFor(tileSize)),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.focusRequester(homeGridFocus).focusGroup(),
        ) {
          // Special + folder tiles persist in Manage mode (non-uninstallable);
          // only regular apps get a delete badge and become draggable.
          item { PortalHomeTile(onExitHome) }
          item { StoreTile(onOpenStore) }
          items(folderNames, key = { it }) { name ->
            FolderTile(
                name = name,
                apps = appsEff.filter { it.folder == name },
                modifier =
                    Modifier.onGloballyPositioned {
                      tileBounds[FOLDER_KEY + name] = it.boundsInWindow()
                    },
                onClick = { openFolder = name },
            )
          }
          items(ungrouped, key = { it.component.packageName }) { app ->
            val pkg = app.component.packageName
            AppTile(
                app = app,
                editMode = editMode,
                dimmed = dragPkg == pkg,
                modifier =
                    Modifier.onGloballyPositioned {
                      tileBounds[APP_KEY + pkg] = it.boundsInWindow()
                    },
                onDelete = { onUninstall(pkg) },
                onClick = { onLaunch(app.component) },
            )
          }
          // Always-present Updates tile, parked at the end of the grid. Tapping
          // installs a ready update, or forces a fresh check when up to date.
          item {
            UpdatesTile(update = update, status = updateStatus) {
              val info = update
              if (info != null) {
                UpdateManager.installUpdate(context, info) { updateStatus = it }
              } else {
                updateStatus = "Checking…"
                UpdateManager.checkForUpdate(context) {
                  update = it
                  updateStatus = if (it == null) "Up to date" else null
                }
              }
            }
          }
        }
      }
      // Optional weather forecast, pinned full-width at the bottom of the screen
      // below the (scrolling) app grid. Off by default.
      if (weatherWidget != ImmortalSettings.WIDGET_OFF) {
        Spacer(Modifier.size(16.dp))
        WeatherWidget(mode = weatherWidget, fahrenheit = weatherFahrenheit)
      }
    }

    // Floating ghost of the app being dragged.
    dragPkg?.let { pkg ->
      appsEff.firstOrNull { it.component.packageName == pkg }?.let { dragged ->
        val ghostDp = LocalTileDp.current
        val half = with(androidx.compose.ui.platform.LocalDensity.current) { (ghostDp / 2).toPx() }
        Image(
            bitmap = dragged.icon,
            contentDescription = null,
            modifier =
                Modifier.offset {
                      IntOffset(
                          (dragPos.x - half).roundToInt(),
                          (dragPos.y - half).roundToInt(),
                      )
                    }
                    .size(ghostDp)
                    .clip(RoundedCornerShape(20.dp)),
        )
      }
    }

    // Manage / Done toggle lives in the bottom-right corner.
    EditButton(
        editMode = editMode,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 32.dp),
        onClick = { editMode = !editMode },
    )

    openFolder?.let { name ->
      val folderApps = appsEff.filter { it.folder == name }
      if (folderApps.isEmpty()) {
        LaunchedEffect(name) { openFolder = null }
      } else {
        FolderOverlay(
            name = name,
            apps = folderApps,
            onLaunch = {
              onLaunch(it)
              openFolder = null
            },
            onRename = { renaming = name },
            onMoveOut = { moveOut(it) },
            onDismiss = { openFolder = null },
            extras =
                if (name == "Settings")
                    listOf(
                        FolderExtra("Immortal", ICON_GEAR) {
                          openFolder = null
                          runCatching {
                            context.startActivity(
                                Intent(context, ImmortalSettingsActivity::class.java))
                          }
                        },
                        FolderExtra("Screensaver", ICON_IMAGE) {
                          openFolder = null
                          runCatching {
                            context.startActivity(
                                Intent(context, ScreensaverSettingsActivity::class.java))
                          }
                        },
                        FolderExtra("Help", ICON_HELP) {
                          openFolder = null
                          onOpenHelp()
                        })
                else emptyList(),
        )
      }
    }

    // Name a new folder (created by dropping one app on another).
    pendingPair?.let { (src, tgt) ->
      NameOverlay(
          title = "Name folder",
          initial = "Folder",
          confirmLabel = "Create",
          onConfirm = {
            createFolder(src, tgt, it)
            pendingPair = null
          },
          onCancel = { pendingPair = null },
      )
    }

    // Rename an existing folder.
    renaming?.let { old ->
      NameOverlay(
          title = "Rename folder",
          initial = old,
          confirmLabel = "Rename",
          onConfirm = {
            renameFolder(old, it)
            renaming = null
          },
          onCancel = { renaming = null },
      )
    }
  }
  } // CompositionLocalProvider(LocalTileDp)
}

private const val APP_KEY = "app:"
private const val FOLDER_KEY = "folder:"

private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

// --- tile sizing ----------------------------------------------------------------
// The grid's tile edge, provided once at the top of the tree so every tile
// (apps, folders, built-ins, the drag ghost) follows the user's size setting.
// Standard is the original 6-column/88dp look; Large is 5 columns of 110dp tiles,
// closer to the stock Portal launcher.
private val LocalTileDp = compositionLocalOf { 88.dp }

private fun tileDpFor(size: String): Dp =
    when (size) {
      ImmortalSettings.SIZE_XL -> 140.dp
      ImmortalSettings.SIZE_LARGE -> 110.dp
      else -> 88.dp
    }

private fun gridColumnsFor(size: String): Int =
    when (size) {
      ImmortalSettings.SIZE_XL -> 4
      ImmortalSettings.SIZE_LARGE -> 5
      else -> 6
    }

@Composable
private fun HeaderBar(onScreensaver: () -> Unit) {
  var now by remember { mutableStateOf(Date()) }
  androidx.compose.runtime.LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(1000)
    }
  }
  val context = androidx.compose.ui.platform.LocalContext.current
  // The unit preference is re-read on resume and keys the fetch loop, so flipping
  // °F/°C in Immortal Settings updates the header the moment the user returns.
  var weatherUnit by remember { mutableStateOf(ImmortalSettings.load(context).weatherUnit) }
  // The clock format is likewise re-read on resume so flipping Auto/12h/24h in
  // Immortal Settings updates the header the moment the user returns home.
  var use24Hour by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  // The "hey" button only appears when Millennium is installed; re-checked on
  // resume so it shows up the moment the user finishes provisioning without
  // needing to relaunch the launcher.
  var heyPkg by remember { mutableStateOf(heyPackage(context)) }
  // The header mini-player toggle is re-read on resume so flipping it in Immortal
  // Settings shows/hides the player the moment the user returns home.
  var showMiniPlayer by remember { mutableStateOf(ImmortalSettings.load(context).showMiniPlayer) }
  // Live now-playing from the device's media session. The hub notifies off-main, so
  // hop back to the main thread before touching Compose state.
  var nowPlaying by remember { mutableStateOf(NowPlayingHub.current) }
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { s -> mainHandler.post { nowPlaying = s } }
    NowPlayingHub.addListener(l) // replays current immediately
    onDispose { NowPlayingHub.removeListener(l) }
  }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        weatherUnit = ImmortalSettings.load(context).weatherUnit
        use24Hour = ImmortalSettings.use24HourClock(context)
        heyPkg = heyPackage(context)
        showMiniPlayer = ImmortalSettings.load(context).showMiniPlayer
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val weather by produceState(initialValue = "", weatherUnit) {
    // Retry soon on failure (e.g. a transient geolocation rate-limit), then
    // refresh periodically. Location is cached after the first success.
    while (true) {
      val w = withContext(Dispatchers.IO) { Weather.fetch(context) }
      if (w.isNotBlank()) {
        value = w
        delay(30L * 60 * 1000) // refresh every 30 min
      } else {
        delay(60L * 1000) // retry in 1 min
      }
    }
  }
  val battery = batteryState()

  // Quick-connect: the remote header button enables the remote and pops a QR/PIN modal.
  var showPair by remember { mutableStateOf(false) }
  var pairUrl by remember { mutableStateOf<String?>(null) }
  var pairPin by remember { mutableStateOf<String?>(null) }

  // Layout: the big clock anchors the left; the weather and date stack on the
  // right, right-aligned, so the header reads as a balanced pair of blocks. The
  // clock and the right-hand stack are centred against each other.
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    // Clock anchors the top-left corner; the action buttons sit just to its right
    // (now that there's a group of them, leading with the buttons looked off).
    Text(
        SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm", Locale.getDefault()).format(now),
        color = Color.White,
        fontSize = 56.sp,
        fontWeight = FontWeight.Light,
        lineHeight = 56.sp,
    )
    Spacer(Modifier.size(28.dp))
    // Screensaver entry — the stock launcher's stacked-photo icon so the affordance
    // reads the same as the Portal users already know.
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier =
            Modifier.size(56.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
              onScreensaver()
            },
    ) {
      Box(contentAlignment = Alignment.Center) { StackedPhotoIcon() }
    }
    // Remote — one-tap "control from your phone": turn the remote on (if off) and show a
    // QR/PIN modal, so a phone pairs without digging into Settings.
    Spacer(Modifier.size(14.dp))
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier =
            Modifier.size(56.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
              val (u, p) = enableRemoteAndMintPin(context)
              pairUrl = u
              pairPin = p
              showPair = true
            },
    ) {
      Box(contentAlignment = Alignment.Center) { RemoteGlyph() }
    }
    // "Hey" button — push-to-talk for the active assistant. The launcher stays
    // dumb: it just broadcasts the trigger; Millennium owns assistant selection,
    // the premium gate and the falcon mic handoff. Only shown when Millennium is
    // installed (so a bare launcher has no dead button).
    heyPkg?.let { pkg ->
      Spacer(Modifier.size(14.dp))
      Surface(
          color = Color(0x33FFFFFF),
          shape = androidx.compose.foundation.shape.CircleShape,
          modifier =
              Modifier.size(56.dp).tvFocusable(
                  shape = androidx.compose.foundation.shape.CircleShape,
                  onLongClick = { openHeyPicker(context, pkg) },
              ) {
                fireHey(context, pkg)
              },
      ) {
        Box(contentAlignment = Alignment.Center) { MicGlyph() }
      }
    }
    // Mini-player — sits in the left action cluster, only while something's playing.
    // When shown it takes the flexible space (so its text uses the header width before
    // it scrolls); otherwise a weight spacer pushes the weather/date to the right.
    val np = nowPlaying
    if (showMiniPlayer && np != null && np.active) {
      Spacer(Modifier.size(16.dp))
      MiniPlayer(np, modifier = Modifier.weight(1f).padding(end = 16.dp))
    } else {
      Spacer(Modifier.weight(1f))
    }
    Column(horizontalAlignment = Alignment.End) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        // Charge level is OPTIONAL — only Portal Go has a battery; mains-powered
        // Portals report no battery present, so we render nothing for them.
        if (battery.present) {
          BatteryIndicator(percent = battery.percent, charging = battery.charging)
        }
        if (weather.isNotBlank()) {
          Text(weather, color = Color.White, fontSize = 30.sp)
        }
      }
      Text(
          SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now),
          color = Color(0xFFDADADA),
          fontSize = 18.sp,
          modifier = Modifier.padding(top = 4.dp),
      )
    }
  }

  if (showPair) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { showPair = false }) {
      Surface(color = Color(0xFF101012), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
          Text("Control from your phone", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
          Text(
              "Scan with a phone on the same Wi-Fi, or open the address and enter the code.",
              color = Color(0xFF9A9A9A),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
          )
          RemotePairCard(pairUrl, pairPin)
          Spacer(Modifier.size(16.dp))
          PairDoneButton { showPair = false }
        }
      }
    }
  }
}

/**
 * Home-header mini-player: the play/pause control is a round button the same size as
 * the other header buttons, styled with the cover art (tap to toggle), with the
 * title/artist alongside. The text takes the available header width and only scrolls
 * (ticker) when a track genuinely overruns it. Driven by [NowPlayingHub].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniPlayer(state: NowPlayingState, modifier: Modifier = Modifier) {
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    // The album art IS the play/pause button — same 56dp circle as the other header
    // buttons, just filled with the cover instead of a flat tint.
    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape).tvFocusable(CircleShape) { NowPlayingHub.playPause() },
        contentAlignment = Alignment.Center,
    ) {
      val bmp = state.artBitmap
      if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp),
        )
      } else {
        Box(Modifier.size(56.dp).background(Color(0x33FFFFFF)))
      }
      Box(Modifier.size(56.dp).background(Color(0x3D000000)), contentAlignment = Alignment.Center) {
        PlayPauseGlyph(playing = state.state == PlaybackState.PLAYING)
      }
    }
    Spacer(Modifier.size(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
          state.title,
          color = Color.White,
          fontSize = 18.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          modifier = Modifier.basicMarquee(),
      )
      if (state.artist.isNotBlank()) {
        Text(
            state.artist,
            color = Color(0xFFB6B6B6),
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 1.dp).basicMarquee(),
        )
      }
    }
  }
}

@Composable
private fun PlayPauseGlyph(playing: Boolean) {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    if (playing) {
      // Two slim, fully-rounded bars with a clear gap between them — close-set square
      // bars blur into a single block from across the room.
      val barW = w * 0.13f
      val top = w * 0.24f
      val barH = w * 0.52f
      val r = CornerRadius(barW * 0.5f, barW * 0.5f)
      drawRoundRect(Color.White, topLeft = Offset(w * 0.30f, top), size = Size(barW, barH), cornerRadius = r)
      drawRoundRect(Color.White, topLeft = Offset(w * 0.57f, top), size = Size(barW, barH), cornerRadius = r)
    } else {
      drawPath(
          Path().apply {
            moveTo(w * 0.32f, w * 0.22f)
            lineTo(w * 0.32f, w * 0.78f)
            lineTo(w * 0.80f, w * 0.50f)
            close()
          },
          Color.White,
      )
    }
  }
}

/** What the forecast widget currently has to show. */
private sealed interface ForecastState {
  /** First fetch still in flight — render nothing so the bar doesn't flash. */
  object Loading : ForecastState
  /** Tried and failed with nothing cached — show a friendly note instead. */
  object Unavailable : ForecastState
  data class Ready(val forecast: Weather.Forecast) : ForecastState
}

// The two swipeable forecast pages, in left-to-right order.
private const val PAGE_HOURLY = 0
private const val PAGE_DAILY = 1

/**
 * Optional home-screen forecast, shown full-width below the app grid when enabled in
 * Immortal Settings ▸ Weather. One network call fetches both views; the user swipes
 * left/right between the hourly and 7-day pages. [mode] picks which page shows first
 * (and jumps to it if the setting changes). [fahrenheit] only keys a re-fetch when the
 * unit changes — the unit itself is resolved inside [Weather.fetchForecast].
 *
 * Failure handling: the fetch retries every minute until it succeeds. While the very
 * first attempt is in flight nothing is drawn (no flash). If it can't be reached and
 * we have no forecast yet, a quiet "unavailable" note replaces the data; once a
 * forecast has loaded, a later failed refresh keeps the last good one on screen rather
 * than blanking it.
 */
@Composable
private fun WeatherWidget(mode: String, fahrenheit: Boolean) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Keyed on the unit only: switching pages is a local swipe, not a re-fetch.
  val state by
      produceState<ForecastState>(initialValue = ForecastState.Loading, fahrenheit) {
        while (true) {
          val f = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
          if (f != null) {
            value = ForecastState.Ready(f)
            delay(30L * 60 * 1000) // refresh every 30 min
          } else {
            // Keep showing the last good forecast if we have one; only surface the
            // note when there's nothing to display.
            if (value !is ForecastState.Ready) value = ForecastState.Unavailable
            delay(60L * 1000) // retry in 1 min
          }
        }
      }
  if (state is ForecastState.Loading) return

  val startPage = if (mode == ImmortalSettings.WIDGET_DAILY) PAGE_DAILY else PAGE_HOURLY
  val pagerState = rememberPagerState(initialPage = startPage) { 2 }
  // Follow the setting: if the user changes the default in Immortal Settings, jump to
  // that page when they come back (no-op on first composition, where it already matches).
  LaunchedEffect(mode) {
    if (pagerState.currentPage != startPage) pagerState.scrollToPage(startPage)
  }

  val ready = state as? ForecastState.Ready
  Surface(
      color = Color(0x14FFFFFF),
      shape = RoundedCornerShape(20.dp),
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      // Header: the current page's title, plus a two-dot indicator hinting the swipe.
      Row(
          modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            when {
              ready == null -> "Forecast"
              pagerState.currentPage == PAGE_DAILY -> "7-day forecast"
              else -> "Hourly forecast"
            },
            color = Color(0xFFBFBFBF),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (ready != null) PageDots(selected = pagerState.currentPage, count = 2)
      }
      if (ready != null) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
          // Each page's cells share the width evenly, spanning the whole card.
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (page == PAGE_HOURLY) {
              ready.forecast.hours.forEach { HourCell(it, Modifier.weight(1f)) }
            } else {
              ready.forecast.days.forEach { DayCell(it, Modifier.weight(1f)) }
            }
          }
        }
      } else {
        // Unavailable: no connection / location yet. Retries quietly in the
        // background, so no action is needed from the user.
        Text(
            "Forecast unavailable. It'll appear once your Portal is back online.",
            color = Color(0xFF9A9A9A),
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
      }
    }
  }
}

/** Small page-position dots, hinting the forecast can be swiped between its pages. */
@Composable
private fun PageDots(selected: Int, count: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    repeat(count) { i ->
      Box(
          modifier =
              Modifier.size(7.dp)
                  .clip(androidx.compose.foundation.shape.CircleShape)
                  .background(if (i == selected) Color.White else Color(0x55FFFFFF)))
    }
  }
}

@Composable
private fun HourCell(h: Weather.HourForecast, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(vertical = 4.dp),
  ) {
    Text(h.label, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 1)
    Text(Weather.emoji(h.code), fontSize = 26.sp, modifier = Modifier.padding(vertical = 6.dp))
    Text("${h.temp}°", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun DayCell(d: Weather.DayForecast, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(vertical = 4.dp),
  ) {
    Text(d.label, color = Color(0xFFCFCFCF), fontSize = 13.sp, maxLines = 1)
    Text(Weather.emoji(d.code), fontSize = 26.sp, modifier = Modifier.padding(vertical = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("${d.hi}°", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
      Text("${d.lo}°", color = Color(0xFF9A9A9A), fontSize = 16.sp)
    }
  }
}

// --- "Hey" assistant trigger -------------------------------------------------
// Millennium publishes an exported receiver for this action; the launcher only
// fires it. Release build preferred, debug fallback for sideloaded test devices.
private const val HEY_TRIGGER_ACTION = "com.millennium.TRIGGER_ASSISTANT"
private val HEY_PACKAGES = listOf("com.millennium", "com.millennium.debug")

/** The installed Millennium ("hey") package, release preferred, or null if absent. */
private fun heyPackage(context: android.content.Context): String? =
    HEY_PACKAGES.firstOrNull { pkg ->
      try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
      } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
      }
    }

/** Ask Millennium to activate the user's active assistant (same path as a wake word). */
private fun fireHey(context: android.content.Context, pkg: String) {
  context.sendBroadcast(Intent(HEY_TRIGGER_ACTION).setPackage(pkg))
}

/** Millennium's assistant picker (premium: choose which assistant to talk to). */
private const val HEY_PICKER_ACTIVITY = "com.millennium.ui.HeyPickerActivity"

/** Long-press: open Millennium's picker. Falls back to a normal trigger if the
 *  installed Millennium predates the picker (so the gesture is never a dead end). */
private fun openHeyPicker(context: android.content.Context, pkg: String) {
  val ok =
      runCatching {
            context.startActivity(
                Intent()
                    .setClassName(pkg, HEY_PICKER_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
          .isSuccess
  if (!ok) fireHey(context, pkg)
}

/** White line-art microphone glyph for the header "hey" button. */
@Composable
private fun MicGlyph() {
  Canvas(modifier = Modifier.size(28.dp)) {
    val w = size.minDimension
    val s = w * 0.08f
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Capsule mic body.
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.38f, w * 0.16f),
        size = Size(w * 0.24f, w * 0.40f),
        cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
        style = stroke,
    )
    // Cradle arc hugging the bottom of the body.
    drawArc(
        color = Color.White,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(w * 0.26f, w * 0.24f),
        size = Size(w * 0.48f, w * 0.48f),
        style = stroke,
    )
    // Stem + base.
    drawLine(Color.White, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.84f), strokeWidth = s)
    drawLine(Color.White, Offset(w * 0.38f, w * 0.84f), Offset(w * 0.62f, w * 0.84f), strokeWidth = s)
  }
}

/** White line-art phone glyph for the header "remote" (control from your phone) button. */
@Composable
private fun RemoteGlyph() {
  Canvas(modifier = Modifier.size(28.dp)) {
    val w = size.minDimension
    val s = w * 0.08f
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Phone body (portrait rounded rect).
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.34f, w * 0.12f),
        size = Size(w * 0.32f, w * 0.66f),
        cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
        style = stroke,
    )
    // Speaker slit near the top, home dot near the bottom.
    drawLine(Color.White, Offset(w * 0.44f, w * 0.22f), Offset(w * 0.56f, w * 0.22f), strokeWidth = s)
    drawCircle(color = Color.White, radius = w * 0.045f, center = Offset(w * 0.5f, w * 0.67f))
  }
}

/** White line-art photo glyph (single frame), matching the stock screensaver. */
@Composable
private fun StackedPhotoIcon() {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    val s = w * 0.075f // stroke width
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Photo frame outline.
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.16f, w * 0.20f),
        size = Size(w * 0.68f, w * 0.60f),
        cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        style = stroke,
    )
    // Sun.
    drawCircle(
        color = Color.White,
        radius = w * 0.075f,
        center = Offset(w * 0.36f, w * 0.40f),
        style = Stroke(width = s),
    )
    // Mountains.
    val path =
        androidx.compose.ui.graphics.Path().apply {
          moveTo(w * 0.20f, w * 0.72f)
          lineTo(w * 0.40f, w * 0.48f)
          lineTo(w * 0.52f, w * 0.60f)
          lineTo(w * 0.62f, w * 0.50f)
          lineTo(w * 0.80f, w * 0.72f)
        }
    drawPath(path, color = Color.White, style = stroke)
  }
}

/** Bottom-right Manage / Done toggle. */
@Composable
private fun EditButton(editMode: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = if (editMode) Color(0xFFE53935) else Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier =
          modifier.size(60.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
            onClick()
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(if (editMode) "✓" else "✎", color = Color.White, fontSize = 28.sp)
    }
  }
}

private data class BatteryReading(val present: Boolean, val percent: Int, val charging: Boolean)

/** Reads the device battery, updating live. Returns present=false on devices
 * without a battery (Portal+, Portal TV, Portal Mini), so callers can hide it. */
@Composable
private fun batteryState(): BatteryReading {
  val context = androidx.compose.ui.platform.LocalContext.current
  var reading by remember { mutableStateOf(BatteryReading(false, 0, false)) }
  DisposableEffect(Unit) {
    fun parse(i: Intent?): BatteryReading {
      if (i == null) return BatteryReading(false, 0, false)
      val present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
      val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
      val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
      val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
      val charging =
          status == BatteryManager.BATTERY_STATUS_CHARGING ||
              status == BatteryManager.BATTERY_STATUS_FULL
      return BatteryReading(present && pct >= 0, pct.coerceIn(0, 100), charging)
    }
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) {
            reading = parse(i)
          }
        }
    // Registering for the sticky ACTION_BATTERY_CHANGED returns the current value.
    val sticky =
        context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    reading = parse(sticky)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  return reading
}

/** Minimal drawn battery glyph + percent, green while charging, red when low. */
@Composable
private fun BatteryIndicator(percent: Int, charging: Boolean) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Canvas(modifier = Modifier.size(width = 36.dp, height = 18.dp)) {
      val cap = 3.dp.toPx()
      val bodyW = size.width - cap
      val stroke = 2.dp.toPx()
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(bodyW - stroke, size.height - stroke),
          cornerRadius = CornerRadius(4f, 4f),
          style = Stroke(width = stroke),
      )
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(bodyW, size.height * 0.3f),
          size = Size(cap, size.height * 0.4f),
          cornerRadius = CornerRadius(2f, 2f),
      )
      val inset = stroke + 2.dp.toPx()
      val fillColor =
          when {
            charging -> Color(0xFF4CAF50)
            percent <= 15 -> Color(0xFFE53935)
            else -> Color.White
          }
      drawRoundRect(
          color = fillColor,
          topLeft = Offset(inset, inset),
          size =
              Size(
                  ((bodyW - inset * 2) * (percent / 100f)).coerceAtLeast(0f),
                  size.height - inset * 2,
              ),
          cornerRadius = CornerRadius(2f, 2f),
      )
    }
    Text("$percent%", color = Color.White, fontSize = 22.sp)
    if (charging) Text("⚡", color = Color(0xFF4CAF50), fontSize = 16.sp)
  }
}

@Composable
private fun PortalHomeTile(onClick: () -> Unit) {
  // Bridge to Meta's stock launcher — the only context allowed to open the
  // trusted-caller apps (Contacts, Camera, Photos), so this is how the user
  // reaches calling. Tapping Portal's home button returns to Immortal.
  BuiltInTile(
      label = "Calls",
      background = Color(0xFF1FA463),
      glyph = ICON_CALL,
      onClick = onClick,
  )
}

// Material-style glyph paths (24x24 viewport), rendered crisply as vectors.
private const val ICON_CALL =
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
private const val ICON_DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
private const val ICON_REFRESH =
    "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
private const val ICON_IMAGE =
    "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
private const val ICON_HELP =
    "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z"
private const val ICON_GEAR =
    "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"

/** A non-app tile injected into a folder (e.g. the Screensaver settings entry). */
private data class FolderExtra(val label: String, val glyph: String, val onClick: () -> Unit)

/** A built-in launcher tile: a rounded colour tile with a centered white vector
 * glyph, styled to sit naturally beside real app icons. */
@Composable
private fun BuiltInTile(
    label: String,
    background: Color,
    glyph: String,
    onClick: () -> Unit,
) {
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  val tileDp = LocalTileDp.current
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    Surface(
        color = background,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.size(tileDp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(46.dp * (tileDp / 88.dp))) {
          val s = size.minDimension / 24f
          scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(label, color = Color.White, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center)
  }
}

@Composable
private fun FolderTile(
    name: String,
    apps: List<AppEntry>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
  val tileDp = LocalTileDp.current
  val scale = tileDp / 88.dp // mini-icon grid scales with the tile
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    Surface(
        color = Color(0xFF3A3A3A),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.size(tileDp),
    ) {
      Column(
          modifier = Modifier.padding(13.dp * scale),
          verticalArrangement = Arrangement.spacedBy(6.dp * scale),
      ) {
        apps.chunked(2).take(2).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp * scale)) {
            row.take(2).forEach { app ->
              Image(
                  bitmap = app.icon,
                  contentDescription = null,
                  modifier = Modifier.size(25.dp * scale).clip(RoundedCornerShape(7.dp)),
              )
            }
          }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        name,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun FolderOverlay(
    name: String,
    apps: List<AppEntry>,
    onLaunch: (ComponentName) -> Unit,
    onRename: () -> Unit,
    onMoveOut: (String) -> Unit,
    onDismiss: () -> Unit,
    extras: List<FolderExtra> = emptyList(),
) {
  // Rendered inside the launcher's own (immersive) window — NOT a Dialog, which
  // would spawn a separate window and momentarily reveal the system bars.
  val noRipple = remember { MutableInteractionSource() }
  val tileBounds = remember { mutableStateMapOf<String, Rect>() }
  var panel by remember { mutableStateOf(Rect.Zero) }
  var dragPkg by remember { mutableStateOf<String?>(null) }
  var dragPos by remember { mutableStateOf(Offset.Zero) }

  // Remote support: Back closes the folder, and focus moves into the grid on open
  // so the D-pad works immediately (the folder was previously unusable by remote).
  BackHandler { onDismiss() }
  val gridFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { gridFocus.requestFocus() } }

  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              // Remote BACK closes the folder. Intercept in the preview (tunnelling)
              // phase so the focus system doesn't swallow it first.
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) {
                  if (e.type == KeyEventType.KeyUp) onDismiss()
                  true // consume down+up so the focus system doesn't eat it first
                } else false
              }
              .background(Color(0xCC000000))
              .clickable(interactionSource = noRipple, indication = null) { onDismiss() }
              // Drag an app out of the panel to remove it from the folder.
              .pointerInput(apps) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                      dragPkg =
                          tileBounds.entries.firstOrNull { it.value.contains(pos) }?.key
                      dragPos = pos
                    },
                    onDrag = { change, delta ->
                      change.consume()
                      dragPos += delta
                    },
                    onDragEnd = {
                      val pkg = dragPkg
                      if (pkg != null && !panel.contains(dragPos)) onMoveOut(pkg)
                      dragPkg = null
                    },
                    onDragCancel = { dragPkg = null },
                )
              },
  ) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(28.dp),
        modifier =
            // The panel grows with the tile size (3 columns of LocalTileDp tiles
            // must fit) — at 88dp this is the original 420dp.
            Modifier.width(420.dp * (LocalTileDp.current / 88.dp))
                .onGloballyPositioned { panel = it.boundsInWindow() }
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(modifier = Modifier.padding(28.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(name, color = Color.White, fontSize = 22.sp, modifier = Modifier.weight(1f))
          // Rename.
          Surface(
              color = Color(0x33FFFFFF),
              shape = androidx.compose.foundation.shape.CircleShape,
              modifier =
                  Modifier.size(40.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
                    onRename()
                  },
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text("✎", color = Color.White, fontSize = 18.sp)
            }
          }
        }
        Spacer(Modifier.size(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.focusRequester(gridFocus).focusGroup(),
        ) {
          extras.forEach { extra ->
            item(key = "extra:${extra.label}") {
              BuiltInTile(
                  label = extra.label,
                  background = Color(0xFF5B6BC0),
                  glyph = extra.glyph,
                  onClick = extra.onClick,
              )
            }
          }
          items(apps, key = { it.component.packageName }) { app ->
            val pkg = app.component.packageName
            AppTile(
                app = app,
                editMode = false,
                dimmed = dragPkg == pkg,
                modifier =
                    Modifier.onGloballyPositioned { tileBounds[pkg] = it.boundsInWindow() },
                onClick = { onLaunch(app.component) },
            )
          }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "Drag an app out to remove it",
            color = Color(0xFF8A8A8A),
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
      }
    }
  }

  // Floating ghost of the app being dragged out.
  dragPkg?.let { pkg ->
    apps.firstOrNull { it.component.packageName == pkg }?.let { dragged ->
      val ghostDp = LocalTileDp.current
      val half = with(androidx.compose.ui.platform.LocalDensity.current) { (ghostDp / 2).toPx() }
      Image(
          bitmap = dragged.icon,
          contentDescription = null,
          modifier =
              Modifier.offset {
                    IntOffset((dragPos.x - half).roundToInt(), (dragPos.y - half).roundToInt())
                  }
                  .size(ghostDp)
                  .clip(RoundedCornerShape(20.dp)),
      )
    }
  }
}

/** Centered overlay with a text field for naming/renaming a folder. */
@Composable
private fun NameOverlay(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
  val noRipple = remember { MutableInteractionSource() }
  // Pre-select the whole name so the first keystroke replaces it (iOS-style).
  var field by remember {
    mutableStateOf(
        androidx.compose.ui.text.input.TextFieldValue(
            initial,
            selection = androidx.compose.ui.text.TextRange(0, initial.length),
        ))
  }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  Box(
      contentAlignment = Alignment.TopCenter,
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xCC000000))
              .clickable(interactionSource = noRipple, indication = null) { onCancel() },
  ) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(24.dp),
        modifier =
            Modifier.padding(top = 70.dp)
                .width(440.dp)
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.size(16.dp))
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            modifier =
                Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .background(Color(0xFF2B2B2B), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
        )
        Spacer(Modifier.size(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          Text(
              "Cancel",
              color = Color(0xFF8AB4F8),
              fontSize = 18.sp,
              modifier = Modifier.clickable { onCancel() }.padding(12.dp),
          )
          Spacer(Modifier.size(8.dp))
          Text(
              confirmLabel,
              color = Color(0xFF8AB4F8),
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.clickable { onConfirm(field.text) }.padding(12.dp),
          )
        }
      }
    }
  }
}

/** Always-present Updates tile. Neutral + refresh glyph when up to date; blue +
 * download glyph (with a badge) when an update is ready. Shows transient status
 * text during a check or install. */
@Composable
private fun UpdatesTile(update: UpdateInfo?, status: String?, onClick: () -> Unit) {
  val available = update != null
  val label = status ?: if (available) "Update ready" else "Up to date"
  val background = if (available) Color(0xFF2D6CDF) else Color(0xFF2B2B2B)
  val glyph = if (available) ICON_DOWNLOAD else ICON_REFRESH
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  val tileDp = LocalTileDp.current
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    Box {
      Surface(
          color = background,
          shape = RoundedCornerShape(20.dp),
          modifier = Modifier.size(tileDp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Canvas(Modifier.size(44.dp * (tileDp / 88.dp))) {
            val s = size.minDimension / 24f
            scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
          }
        }
      }
      if (available) {
        Surface(
            color = Color(0xFFE53935),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
        ) {}
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        label,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun StoreTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "App Store",
      background = Color(0xFF2D6CDF),
      glyph = ICON_DOWNLOAD,
      onClick = onClick,
  )
}

@Composable
private fun AppTile(
    app: AppEntry,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onDelete: () -> Unit = {},
    onClick: () -> Unit,
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      // In Manage mode the body tap is inert (drag to fold, ✕ to remove); the
      // icon launches normally otherwise.
      modifier =
          modifier.padding(4.dp).tvFocusable(RoundedCornerShape(22.dp), enabled = !editMode) {
            onClick()
          },
  ) {
    Box {
      Image(
          bitmap = app.icon,
          contentDescription = app.label,
          modifier =
              Modifier.size(LocalTileDp.current)
                  .clip(RoundedCornerShape(20.dp))
                  .alpha(if (dimmed) 0.3f else 1f),
      )
      if (editMode) {
        Surface(
            color = Color(0xFFE53935),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(30.dp).align(Alignment.TopEnd).clickable { onDelete() },
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text("✕", color = Color.White, fontSize = 18.sp)
          }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        app.label,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

// --- data ---------------------------------------------------------------------

private fun loadApps(context: Context): List<AppEntry> {
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
              AppEntry(
                  label = Curation.displayLabel(ai.packageName, rawLabel),
                  component = ComponentName(ai.packageName, ai.name),
                  icon = bmp.asImageBitmap(),
                  folder = Curation.folderFor(ai.packageName),
              ) to rawLabel
            }
            .getOrNull()
      }
      // Label-based hide (catches gated apps that share a package).
      .filterNot { (_, raw) -> raw in Curation.hiddenLabels }
      .map { it.first }
      // One tile per package — some apps (e.g. Portal Settings) expose a second
      // debug/launcher activity that would otherwise show as a duplicate.
      .distinctBy { it.component.packageName }
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

