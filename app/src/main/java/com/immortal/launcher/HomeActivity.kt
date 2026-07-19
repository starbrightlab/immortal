/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageInstaller
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs
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
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val folder: String? = null,
)

private data class WidgetProviderEntry(
    val label: String,
    val packageLabel: String,
    val icon: ImageBitmap?,
    val info: AppWidgetProviderInfo?,
    val spanX: Int,
    val spanY: Int,
    val customKind: String? = null,
)

private data class PendingWidgetAdd(
    val appWidgetId: Int,
    val provider: WidgetProviderEntry,
)

/** Portal-TV (ripleyhome) fallback for [launchStockHome]: how many system Back presses to reach
 *  Meta's launcher, and the gap between them. Best-effort — on some Portal TVs the burst lands on
 *  the previously-used app instead (issue #91), and it's untestable from here. */
private const val STOCK_HOME_BACK_PRESSES = 5
private const val STOCK_HOME_BACK_INTERVAL_MS = 300L
private const val HOME_APP_WIDGET_HOST_ID = 0x4611

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
    DreamPolicy.clearBridge(this)
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
   * transition. (Launching Contacts directly is rejected as an untrusted caller, so the
   * hand-off must go through the launcher.)
   *
   * Path order: the deep link is primary on touchscreen Portals. The accessibility
   * Back-press burst is only a Portal-TV (ripleyhome) fallback for when the deep link
   * doesn't resolve — on a touchscreen Portal a system BACK from Immortal (itself the
   * home activity) never surfaces the stock launcher, so the burst can't be primary there.
   */
  private fun launchStockHome() {
    // Suppress the screensaver-relaunch race while the stock home comes forward, and
    // keep suppressing the holding-frame relaunch until the user returns to Immortal
    // (cleared in onResume) so the frame can't slam over an in-progress call.
    // Persisted to SharedPreferences so new Immortal processes spawned after Android
    // kills our process also see the bridge and return SUPPRESSED (not REDREAM).
    DreamPolicy.markBridge(this)

    fun fire(intent: Intent): Boolean =
        runCatching {
              startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              true
            }
            .getOrDefault(false)

    // 1) Touchscreen Portals (Portal / Portal+ / Go / Mini): deep-link into the stock launcher's
    //    Home tab. Needs the stock launcher enabled — provisioning leaves it so. Gate on
    //    resolveActivity: launching an explicit-but-DISABLED component does NOT throw, so an
    //    unguarded fire() would return true and swallow the tap (Calls silently no-ops) instead of
    //    falling through. Resolving first means a disabled launcher drops to the fallbacks below.
    val deepLink =
        Intent(Intent.ACTION_VIEW, Uri.parse("portal://launcher/home"))
            .setPackage("com.facebook.alohaapps.launcher")
    if (packageManager.resolveActivity(deepLink, 0) != null && fire(deepLink)) return

    // 2) Portal TV (ripleyhome): no portal:// deep link, so the VIEW above didn't resolve. Surface
    //    its stock launcher with a Back-press burst through the accessibility service.
    if (RemoteInput.available()) {
      RemoteInput.backRepeat(STOCK_HOME_BACK_PRESSES, STOCK_HOME_BACK_INTERVAL_MS)
      return
    }

    // 3) Last resort: this device's real stock HOME, excluding ourselves and the fallback homes.
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
  val loadedApps by
      produceState<List<AppEntry>?>(initialValue = null, reload) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
      }
  val appsLoaded = loadedApps != null
  val apps = loadedApps.orEmpty()
  var editMode by remember { mutableStateOf(false) }
  var openFolder by remember { mutableStateOf<String?>(null) }
  var showWidgetPicker by remember { mutableStateOf(false) }
  var widgetStatus by remember { mutableStateOf<String?>(null) }
  var widgets by remember { mutableStateOf(HomeWidgetStore.load(context)) }
  // Whether the shared timer is currently ringing — drives the swipe-to-stop alarm overlay.
  var timerRinging by remember { mutableStateOf(TimerStore.load(context).ringing) }
  DisposableEffect(Unit) {
    val l: () -> Unit = { timerRinging = TimerStore.load(context).ringing }
    TimerStore.addListener(l)
    onDispose { TimerStore.removeListener(l) }
  }

  // Immortal Settings the home screen reflects (tile size, and the optional weather
  // widget's mode/unit), re-read on resume so a change applies the moment the user
  // comes back to the home screen.
  var tileSize by remember { mutableStateOf(ImmortalSettings.load(context).tileSize) }
  var constrainPageWidth by
      remember { mutableStateOf(ImmortalSettings.load(context).constrainPageWidth) }
  var weatherWidget by remember { mutableStateOf(ImmortalSettings.load(context).weatherWidget) }
  var weatherFahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
  val appWidgetHost = remember(context) { AppWidgetHost(context, HOME_APP_WIDGET_HOST_ID) }
  fun loadLiveWidgets(): List<HomeWidgetStore.HomeWidget> {
    val loaded = HomeWidgetStore.load(context)
    val live = loaded.filterLiveWidgets(appWidgetManager, appWidgetHost)
    if (live.size != loaded.size) HomeWidgetStore.save(context, live)
    return live
  }
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        val s = ImmortalSettings.load(context)
        tileSize = s.tileSize
        constrainPageWidth = s.constrainPageWidth
        weatherWidget = s.weatherWidget
        weatherFahrenheit = ImmortalSettings.useFahrenheit(context)
        widgets = loadLiveWidgets()
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  DisposableEffect(lifecycleOwner, appWidgetHost) {
    val obs = LifecycleEventObserver { _, e ->
      when (e) {
        Lifecycle.Event.ON_START -> runCatching { appWidgetHost.startListening() }
        Lifecycle.Event.ON_STOP -> runCatching { appWidgetHost.stopListening() }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    runCatching { appWidgetHost.startListening() }
    val live = widgets.filterLiveWidgets(appWidgetManager, appWidgetHost)
    if (live.size != widgets.size) HomeWidgetStore.save(context, live)
    widgets = live
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(obs)
      runCatching { appWidgetHost.stopListening() }
    }
  }

  fun saveWidgets(next: List<HomeWidgetStore.HomeWidget>) {
    widgets = next
    HomeWidgetStore.save(context, next)
  }

  fun removeWidget(widget: HomeWidgetStore.HomeWidget) {
    if (widget.isAppWidget) runCatching { appWidgetHost.deleteAppWidgetId(widget.appWidgetId) }
    saveWidgets(HomeWidgetStore.without(widgets, widget.key))
    widgetStatus = "Widget removed"
  }

  fun addCustomWidget(kind: String) {
    val widget =
        when (kind) {
          HomeWidgetStore.KIND_TIMERS -> HomeWidgetStore.custom(kind, spanX = 2, spanY = 2)
          else -> HomeWidgetStore.custom(kind, spanX = 2, spanY = 2)
        }
    saveWidgets(HomeWidgetStore.withAdded(widgets, widget))
    showWidgetPicker = false
    widgetStatus = "Added ${customWidgetLabel(kind)}"
  }

  fun commitWidget(pending: PendingWidgetAdd) {
    val provider = pending.provider
    val info = provider.info ?: return
    val record =
        HomeWidgetStore.HomeWidget(
            appWidgetId = pending.appWidgetId,
            providerPackage = info.provider.packageName,
            providerClass = info.provider.className,
            spanX = provider.spanX,
            spanY = provider.spanY,
        )
    updateWidgetSizeOptions(appWidgetManager, record, tileDpFor(tileSize))
    saveWidgets(HomeWidgetStore.withAdded(widgets, record))
    showWidgetPicker = false
    widgetStatus = "Added ${provider.label}"
  }

  var pendingBind by remember { mutableStateOf<PendingWidgetAdd?>(null) }
  var pendingConfig by remember { mutableStateOf<PendingWidgetAdd?>(null) }
  val configLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingConfig ?: return@rememberLauncherForActivityResult
        pendingConfig = null
        if (result.resultCode == Activity.RESULT_OK) {
          commitWidget(pending)
        } else {
          runCatching { appWidgetHost.deleteAppWidgetId(pending.appWidgetId) }
          widgetStatus = "Widget setup was cancelled"
        }
      }

  fun configureOrCommit(pending: PendingWidgetAdd) {
    val info = pending.provider.info ?: return
    val configure = info.configure
    if (configure != null) {
      pendingConfig = pending
      val launched =
          runCatching {
                configLauncher.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .setComponent(configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending.appWidgetId))
                true
              }
              .getOrDefault(false)
      if (!launched) {
        pendingConfig = null
        runCatching { appWidgetHost.deleteAppWidgetId(pending.appWidgetId) }
        widgetStatus = "Widget setup could not open"
      }
    } else {
      commitWidget(pending)
    }
  }

  val bindLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingBind ?: return@rememberLauncherForActivityResult
        pendingBind = null
        if (result.resultCode == Activity.RESULT_OK) {
          configureOrCommit(pending)
        } else {
          runCatching { appWidgetHost.deleteAppWidgetId(pending.appWidgetId) }
          widgetStatus = "Widget permission was cancelled"
        }
      }

  fun addWidget(provider: WidgetProviderEntry) {
    provider.customKind?.let {
      addCustomWidget(it)
      return
    }
    val info = provider.info ?: return
    val appWidgetId =
        runCatching { appWidgetHost.allocateAppWidgetId() }
            .getOrElse {
              widgetStatus = "Widget host could not allocate an ID"
              return
            }
    val pending = PendingWidgetAdd(appWidgetId, provider)
    val bound =
        runCatching {
              appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)
            }
            .getOrDefault(false)
    if (bound) {
      configureOrCommit(pending)
      return
    }

    pendingBind = pending
    widgetStatus = "Allow Immortal to add widgets in the system dialog."
    val launched =
        runCatching {
              bindLauncher.launch(
                  Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                      .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                      .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider))
              true
            }
            .getOrDefault(false)
    if (!launched) {
      pendingBind = null
      runCatching { appWidgetHost.deleteAppWidgetId(appWidgetId) }
      widgetStatus = "Android's widget permission dialog could not open"
    }
  }

  // Remote support: land focus on the grid at startup so the D-pad works on the TV.
  val homeGridFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { homeGridFocus.requestFocus() } }

  // User-created folder assignments (via drag-drop), persisted, overlaying the
  // curated defaults: a user override wins over Curation.folderFor(). An empty
  // string is an explicit "ungrouped" override (used when dragging out of a
  // folder), so it beats a curated default too.
  val assignments =
      remember { mutableStateMapOf<String, String>().apply { putAll(UserLayout.load(context)) } }
  val appsEff =
      remember(apps, assignments.toMap()) {
        val effective = HomeLayoutModel.effectiveApps(apps.toLayoutRefs(), assignments)
        val foldersByPackage = effective.associate { it.packageName to it.folder }
        apps.map { it.copy(folder = foldersByPackage[it.component.packageName]) }
      }
  val ungrouped = remember(appsEff) { appsEff.filter { it.folder == null } }
  val folderNames = remember(appsEff) { HomeLayoutModel.folderNames(appsEff.toLayoutRefs()) }

  // --- unified, fully-reorderable home grid -----------------------------------
  // Every top-level tile — built-ins, widgets, folders, and ungrouped apps — has a stable key and
  // lives in one ordered list, so ALL of them can be dragged (not just apps).
  val allTileKeys =
      remember(widgets, folderNames, ungrouped) {
        buildList {
          add(BUILTIN_CALLS)
          add(BUILTIN_STORE)
          add(BUILTIN_TOOLS)
          widgets.forEach { add(WIDGET_KEY + it.key) }
          folderNames.forEach { add(FOLDER_KEY + it) }
          ungrouped.forEach { add(APP_KEY + it.component.packageName) }
          add(BUILTIN_UPDATES)
        }
      }
  var gridSlots by remember { mutableStateOf(UserLayout.loadGridSlots(context)) }
  val gridColumns = gridColumnsFor(tileSize)
  // Keep the saved slot list in step with what's actually present, preserving the user's blanks.
  LaunchedEffect(allTileKeys, gridColumns, appsLoaded) {
    val normalized =
        HomeGrid.normalizeSlotsWhenReady(
            gridSlots, allTileKeys, gridColumns, dynamicTilesLoaded = appsLoaded)
    if (normalized != gridSlots) {
      gridSlots = normalized
      UserLayout.saveGridSlots(context, normalized)
    }
  }
  val widgetByKey = remember(widgets) { widgets.associateBy { WIDGET_KEY + it.key } }
  val appByKey = remember(ungrouped) { ungrouped.associateBy { APP_KEY + it.component.packageName } }
  // A tile's column footprint: widgets span their width, everything else is 1.
  fun widthOf(key: String?): Int =
      if (key != null && key.startsWith(WIDGET_KEY))
          (widgetByKey[key]?.spanX ?: 1).coerceIn(1, gridColumns)
      else 1

  // --- drag-and-drop (Manage mode): free placement over a fixed slot grid ------
  val slotBounds = remember { mutableStateMapOf<Int, Rect>() }
  var dragKey by remember { mutableStateOf<String?>(null) }
  var dragPos by remember { mutableStateOf(Offset.Zero) }
  var dragOriginalSlots by remember { mutableStateOf<List<String?>?>(null) }
  // The drag gesture lives on a stable container that wraps the pager (not on the tile), so it
  // survives page flips — that's what lets a tile be dragged onto a different page.
  var containerOrigin by remember { mutableStateOf(Offset.Zero) }
  var containerDragging by remember { mutableStateOf(false) }
  // Pending folder creation awaiting a name (source+target packages).
  var pendingPair by remember { mutableStateOf<Pair<String, String>?>(null) }
  // Folder currently being renamed.
  var renaming by remember { mutableStateOf<String?>(null) }
  // Dwell-to-fold: while an app is dragged over another app, foldCandidate tracks that app;
  // once the finger holds still (see the dwell LaunchedEffect) foldTarget is armed and its tile
  // highlights, so releasing there makes a folder instead of a swap. moveTick restarts the dwell
  // on every drag movement, so a quick pass-through just reorders.
  var foldCandidate by remember { mutableStateOf<String?>(null) }
  var foldTarget by remember { mutableStateOf<String?>(null) }
  var moveTick by remember { mutableStateOf(0) }

  fun persist() = UserLayout.save(context, assignments.toMap())
  fun replaceAssignments(next: Map<String, String>) {
    assignments.clear()
    assignments.putAll(next)
    persist()
  }
  fun assign(pkg: String, folder: String) {
    assignments[pkg] = folder
    persist()
  }
  fun createFolder(a: String, b: String, name: String) {
    val result = HomeLayoutModel.createFolder(assignments, a, b, name)
    replaceAssignments(result.assignments)
    openFolder = result.folderName
  }
  fun renameFolder(old: String, raw: String) {
    val result = HomeLayoutModel.renameFolder(appsEff.toLayoutRefs(), assignments, old, raw) ?: return
    replaceAssignments(result.assignments)
    openFolder = result.folderName
  }
  fun moveOut(pkg: String) {
    val result = HomeLayoutModel.moveOut(appsEff.toLayoutRefs(), assignments, pkg) ?: return
    replaceAssignments(result.assignments)
    if (result.closeFolder) openFolder = null
  }
  fun targetSlotAt(pos: Offset): Int? =
      slotBounds.entries.firstOrNull { it.value.contains(pos) }?.key

  // --- horizontal paging (iOS-style swipeable app pages) ----------------------
  var pageCapacity by remember { mutableStateOf(1) }
  val pageCount = ((gridSlots.size + pageCapacity - 1) / pageCapacity).coerceAtLeast(1)
  val pagerState = rememberPagerState { pageCount }
  // While dragging, drift toward the screen edge auto-advances to the next/previous page.
  var edgeDir by remember { mutableStateOf(0) }

  fun startTileDrag(key: String, win: Offset) {
    // Long-pressing a tile enters Manage mode AND begins dragging it in the same gesture, so the
    // user doesn't have to lift and press again.
    if (!editMode) editMode = true
    dragKey = key
    dragPos = win
    dragOriginalSlots = gridSlots
  }
  fun moveTileDrag(amount: Offset) {
    val dm = context.resources.displayMetrics
    val screenW = dm.widthPixels.toFloat()
    val screenH = dm.heightPixels.toFloat()
    // Clamp to the screen so a finger riding the very edge (or briefly off it) still maps to the
    // edge column instead of drifting into nowhere — keeps target detection and edge-flip working.
    dragPos =
        Offset(
            (dragPos.x + amount.x).coerceIn(0f, screenW),
            (dragPos.y + amount.y).coerceIn(0f, screenH),
        )
    edgeDir =
        when {
          dragPos.x > screenW * 0.86f -> 1
          dragPos.x < screenW * 0.14f -> -1
          else -> 0
        }
    // Any movement restarts the dwell timer and drops the fold highlight; only a pause re-arms it.
    moveTick++
    foldTarget = null
    val src = dragKey ?: return
    val srcIdx = gridSlots.indexOf(src)
    if (srcIdx < 0) return
    val tgtIdx = targetSlotAt(dragPos) ?: return
    if (tgtIdx == srcIdx) return
    // Hovering an app over a folder files it in (handled on drop) — don't live-swap into it.
    val tgt = gridSlots.getOrNull(tgtIdx)
    // Dwell-to-fold: remember the app we're passing directly over so a pause here can arm a new
    // folder. Anything else under the finger (folder, widget, built-in, blank) clears the candidate.
    foldCandidate = if (src.startsWith(APP_KEY) && tgt != null && tgt.startsWith(APP_KEY)) tgt else null
    if (src.startsWith(APP_KEY) && tgt != null && tgt.startsWith(FOLDER_KEY)) return
    val next = HomeGrid.swap(gridSlots, srcIdx, tgtIdx)
    if (next != gridSlots) gridSlots = next
  }
  fun endTileDrag() {
    edgeDir = 0
    val src = dragKey
    if (src != null) {
      val armed = foldTarget
      if (src.startsWith(APP_KEY) && armed != null && armed.startsWith(APP_KEY) && armed != src) {
        // Dwell-to-fold: the app rested on another app — make a folder from the pair. Undo any live
        // swaps first so the grid is back to its pre-drag order; the reconcile effect then removes
        // both apps and drops the new folder tile in for them once it's named.
        dragOriginalSlots?.let { gridSlots = it }
        UserLayout.saveGridSlots(context, gridSlots)
        pendingPair = src.removePrefix(APP_KEY) to armed.removePrefix(APP_KEY)
      } else {
        val tgtIdx = targetSlotAt(dragPos)
        val tgt = tgtIdx?.let { gridSlots.getOrNull(it) }
        if (src.startsWith(APP_KEY) && tgt != null && tgt.startsWith(FOLDER_KEY)) {
          // File the app into the folder; undo any live swaps so the folder stays put.
          dragOriginalSlots?.let { gridSlots = it }
          assignments[src.removePrefix(APP_KEY)] = tgt.removePrefix(FOLDER_KEY)
          persist()
        }
        UserLayout.saveGridSlots(context, gridSlots)
      }
    }
    dragKey = null
    dragOriginalSlots = null
    foldTarget = null
    foldCandidate = null
  }
  fun cancelTileDrag() {
    // Don't revert — commit the tiles where they were last dragged, so a brief finger-off-screen
    // (which the OS reports as a cancel) never snaps everything back to where it started.
    edgeDir = 0
    if (dragKey != null) UserLayout.saveGridSlots(context, gridSlots)
    dragKey = null
    dragOriginalSlots = null
    foldTarget = null
    foldCandidate = null
  }
  // Debounced page flip while a drag rests against a screen edge.
  LaunchedEffect(edgeDir) {
    if (edgeDir != 0 && dragKey != null) {
      delay(450)
      if (dragKey != null && edgeDir != 0) {
        val target = (pagerState.currentPage + edgeDir).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (target != pagerState.currentPage) pagerState.animateScrollToPage(target)
      }
      edgeDir = 0
    }
  }
  // Dwell-to-fold: if a dragged app rests over another app for a beat, arm folder creation so the
  // release makes a folder (a quick pass-through just reorders). moveTick restarts this on every
  // movement, so the delay only completes once the finger holds still over the candidate.
  LaunchedEffect(moveTick, dragKey) {
    val src = dragKey ?: return@LaunchedEffect
    if (!src.startsWith(APP_KEY) || foldCandidate == null) return@LaunchedEffect
    delay(FOLD_DWELL_MS)
    val cand = foldCandidate
    if (dragKey == src && cand != null && cand.startsWith(APP_KEY) && gridSlots.contains(cand)) {
      foldTarget = cand
    }
  }
  // Auto-arrange: pack every tile into the clean default order (built-ins, widgets, folders, then
  // apps alphabetically), removing blanks and empty pages.
  fun tidyGrid() {
    val packed = HomeGrid.normalizeToPages(allTileKeys, allTileKeys, pageCapacity, keepSpare = false)
    gridSlots = packed
    UserLayout.saveGridSlots(context, packed)
  }
  // If pages were removed (e.g. auto-deleted empties), don't leave the pager stranded past the end.
  LaunchedEffect(pageCount) {
    if (pagerState.currentPage >= pageCount) {
      pagerState.scrollToPage((pageCount - 1).coerceAtLeast(0))
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
  Box(modifier = Modifier.fillMaxSize()) {
    HomeBackground(Modifier.fillMaxSize())
    Column(
        modifier =
            Modifier.fillMaxHeight()
                // Optionally cap the content width and center it so the grid stays
                // comfortably sized on large displays (e.g. Portal+ 1920px) instead
                // of stretching 6 columns across the whole panel. Off by default —
                // most users want the full screen — and gated on the user's
                // "Constrain page width" preference; uncapped, fillMaxWidth takes the
                // whole panel. (widthIn must precede fillMaxWidth so the cap wins,
                // then align centers the capped content.)
                .align(Alignment.TopCenter)
                .widthIn(max = if (constrainPageWidth) 1264.dp else Dp.Infinity)
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
                  // One stable, page-flip-proof drag detector for EVERY tile (apps, folders,
                  // built-ins AND widgets). It watches for a long-press in the pointer Initial pass,
                  // so it grabs the tile under the finger before a hosted widget's AndroidView can
                  // swallow the touch — without consuming anything until the long-press actually
                  // fires, so normal taps / widget interaction / page swipes still work.
                  .pointerInput(Unit) {
                    awaitEachGesture {
                      val down =
                          awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                      val slop = viewConfiguration.touchSlop
                      // Wait for the long-press without consuming. Returns non-null if the gesture
                      // was a tap/move/swipe instead (let children handle it); null on timeout = a
                      // genuine long-press.
                      val aborted =
                          withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var done = false
                            while (!done) {
                              val e = awaitPointerEvent(PointerEventPass.Initial)
                              val c = e.changes.firstOrNull { it.id == down.id }
                              if (c == null ||
                                  !c.pressed ||
                                  (c.position - down.position).getDistance() > slop) {
                                done = true
                              }
                            }
                            true
                          }
                      if (aborted != null) return@awaitEachGesture
                      val win = down.position + containerOrigin
                      val hitIdx =
                          slotBounds.entries.firstOrNull { it.value.contains(win) }?.key
                      val hitKey = hitIdx?.let { gridSlots.getOrNull(it) }
                      if (hitKey == null) return@awaitEachGesture
                      containerDragging = true
                      startTileDrag(hitKey, win)
                      val endedNormally =
                          drag(down.id) { change ->
                            change.consume()
                            moveTileDrag(change.position - change.previousPosition)
                          }
                      if (endedNormally) endTileDrag() else cancelTileDrag()
                      containerDragging = false
                    }
                  },
      ) {
        val tileDp = LocalTileDp.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
          // Fit whole rows per page from the available height, then page horizontally (iOS-style).
          // Use a safe row height (icon + spacer + label + padding) and reserve the page-dots strip
          // below the pager, so the bottom row is never clipped in half.
          val rowH = tileDp + 42.dp
          val dotsReserve = 30.dp
          val avail = (maxHeight - dotsReserve).coerceAtLeast(rowH)
          val rows = ((avail.value + 20f) / (rowH.value + 20f)).toInt().coerceAtLeast(1)
          val cap = (gridColumns * rows).coerceAtLeast(1)
          LaunchedEffect(cap) { pageCapacity = cap }
          LaunchedEffect(allTileKeys, cap, dragKey != null, appsLoaded) {
            // Keep a spare empty page only while arranging; otherwise empty pages are removed.
            val normalized =
                HomeGrid.normalizeToPagesWhenReady(
                    gridSlots,
                    allTileKeys,
                    cap,
                    keepSpare = dragKey != null,
                    dynamicTilesLoaded = appsLoaded,
                )
            if (normalized != gridSlots) {
              gridSlots = normalized
              UserLayout.saveGridSlots(context, normalized)
            }
          }
          Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                // Disable horizontal page-swipe while a tile is being dragged (edge auto-advance
                // takes over then); otherwise swiping flips between app pages.
                userScrollEnabled = dragKey == null,
                // Keep the neighbouring page composed so a drag survives a single edge page-flip
                // (the dragged tile's gesture node isn't disposed when the page scrolls).
                beyondViewportPageCount = 1,
                modifier = Modifier.weight(1f).focusRequester(homeGridFocus).focusGroup(),
            ) { page ->
              LazyVerticalGrid(
                  columns = GridCells.Fixed(gridColumns),
                  horizontalArrangement = Arrangement.spacedBy(16.dp),
                  verticalArrangement = Arrangement.spacedBy(20.dp),
                  userScrollEnabled = false,
                  modifier = Modifier.fillMaxSize(),
              ) {
                val pageStart = page * pageCapacity
                val pageEnd = minOf(pageStart + pageCapacity, gridSlots.size)
                val pageSize = (pageEnd - pageStart).coerceAtLeast(0)
                // Free-placement grid: every cell is a slot — a tile or an intentional blank.
                // Dragging a tile swaps slots, so blanks stay where the user left them. A long-press
                // both enters Manage mode AND starts the drag in one gesture (see startTileDrag).
                items(
                    count = pageSize,
                    key = { li -> gridSlots[pageStart + li] ?: "blank:${pageStart + li}" },
                    span = { li -> GridItemSpan(widthOf(gridSlots[pageStart + li])) },
                ) { li ->
                  val i = pageStart + li
                  val key = gridSlots[i]
            val boundsMod = Modifier.onGloballyPositioned { slotBounds[i] = it.boundsInWindow() }
            if (key == null) {
              // Blank cell — an invisible drop target with the same footprint as a tile so the
              // grid lines up perfectly whether a cell is filled or empty.
              Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  modifier = boundsMod.fillMaxWidth().padding(4.dp),
              ) {
                Box(Modifier.size(tileDp))
                Spacer(Modifier.size(8.dp))
                Text(" ", color = Color.Transparent, fontSize = 15.sp, maxLines = 1)
              }
            } else {
              val isDragged = dragKey == key
              when {
                key == BUILTIN_CALLS ->
                    HomeTileFrame(boundsMod, isDragged) { PortalHomeTile(onExitHome) }
                key == BUILTIN_STORE ->
                    HomeTileFrame(boundsMod, isDragged) { StoreTile(onOpenStore) }
                key == BUILTIN_TOOLS ->
                    HomeTileFrame(boundsMod, isDragged) {
                      ToolsTile {
                        context.startActivity(Intent(context, ToolsActivity::class.java))
                      }
                    }
                key == BUILTIN_UPDATES ->
                    HomeTileFrame(boundsMod, isDragged) {
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
                key.startsWith(WIDGET_KEY) ->
                    widgetByKey[key]?.let { w ->
                      WidgetCell(
                          widget = w,
                          host = appWidgetHost,
                          manager = appWidgetManager,
                          editMode = editMode,
                          tileDp = tileDp,
                          dimmed = isDragged,
                          modifier = boundsMod.alpha(if (isDragged) 0f else 1f),
                          onRemove = { removeWidget(w) },
                      )
                    }
                key.startsWith(FOLDER_KEY) -> {
                  val name = key.removePrefix(FOLDER_KEY)
                  HomeTileFrame(boundsMod, isDragged) {
                    FolderTile(
                        name = name,
                        apps = appsEff.filter { it.folder == name },
                        editMode = editMode,
                        onClick = { openFolder = name },
                    )
                  }
                }
                key.startsWith(APP_KEY) ->
                    appByKey[key]?.let { app ->
                      val isFoldTarget = foldTarget == key
                      AppTile(
                          app = app,
                          editMode = editMode,
                          dimmed = isDragged,
                          modifier =
                              boundsMod
                                  .alpha(if (isDragged) 0f else 1f)
                                  .then(
                                      if (isFoldTarget)
                                          Modifier.border(2.dp, Color.White, RoundedCornerShape(18.dp))
                                      else Modifier),
                          onClick = { onLaunch(app.component) },
                          onDelete = { onUninstall(app.component.packageName) },
                      )
                    }
                  }
                }
              }
            }
          }
          if (pageCount > 1) {
            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
              PageDots(selected = pagerState.currentPage, count = pageCount)
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

    // Manage / Done toggle lives in the bottom-right corner. While managing, a
    // sibling + button opens the widget picker so widgets are an edit action, not
    // a permanent launcher tile.
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 32.dp),
    ) {
      if (editMode) TidyButton { tidyGrid() }
      if (editMode) AddWidgetButton { showWidgetPicker = true }
      EditButton(editMode = editMode, onClick = { editMode = !editMode })
    }

    // Floating ghost of the tile being dragged — it follows the finger while the grid reflows
    // beneath it (the source cell is hidden via alpha while dragging).
    dragKey?.let { key ->
      val ghostDp = LocalTileDp.current
      val half = with(androidx.compose.ui.platform.LocalDensity.current) { (ghostDp / 2).toPx() }
      val ghostMod =
          Modifier.offset {
                IntOffset((dragPos.x - half).roundToInt(), (dragPos.y - half).roundToInt())
              }
              .size(ghostDp)
      val app = appByKey[key]
      if (app != null) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = ghostMod.clip(RoundedCornerShape(20.dp)),
        )
      } else {
        // Folders / built-ins / widgets: a simple lifted tile stand-in.
        Box(
            modifier =
                ghostMod
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF23A3A3C)),
        )
      }
    }

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

    if (showWidgetPicker) {
      val providers by
          produceState(initialValue = emptyList<WidgetProviderEntry>(), reload, tileSize) {
            value = withContext(Dispatchers.IO) { loadWidgetProviders(context, tileDpFor(tileSize)) }
          }
      WidgetPickerOverlay(
          providers = providers,
          status = widgetStatus,
          onPick = { addWidget(it) },
          onDismiss = {
            showWidgetPicker = false
            widgetStatus = null
          },
      )
    }

    // Timer "time's up" alarm: a full-screen swipe-to-stop overlay while ringing.
    if (timerRinging) {
      TimerAlarmOverlay(onStop = { TimerAlarm.stop(context) })
    }
  }
  } // CompositionLocalProvider(LocalTileDp)
}

/**
 * Full-screen "time's up" alarm overlay with an iOS-style slide-to-stop. Blocks touches from
 * reaching the grid beneath, so the only way out is the slider (or the 60s auto-silence).
 */
@Composable
private fun TimerAlarmOverlay(onStop: () -> Unit) {
  val noRipple = remember { MutableInteractionSource() }
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xE6000000))
              // Swallow taps so nothing behind the alarm reacts.
              .clickable(interactionSource = noRipple, indication = null) {},
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text("⏰", fontSize = 68.sp)
      Spacer(Modifier.size(18.dp))
      Text("Timer", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
      Text(
          "Time's up",
          color = Color(0xFFBFBFBF),
          fontSize = 18.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(44.dp))
      SlideToStop(onStop = onStop)
    }
  }
}

/** A draggable thumb on a track; sliding it to the far end fires [onStop] (else it snaps back). */
@Composable
private fun SlideToStop(onStop: () -> Unit) {
  val density = androidx.compose.ui.platform.LocalDensity.current
  val trackWidth = 320.dp
  val thumbSize = 56.dp
  val inset = 4.dp
  val maxOffset =
      with(density) { (trackWidth - thumbSize - inset * 2).toPx() }.coerceAtLeast(0f)
  val insetPx = with(density) { inset.roundToPx() }
  var offsetX by remember { mutableStateOf(0f) }
  Box(
      modifier =
          Modifier.width(trackWidth)
              .height(64.dp)
              .clip(RoundedCornerShape(32.dp))
              .background(Color(0x33FFFFFF)),
      contentAlignment = Alignment.CenterStart,
  ) {
    Text(
        "Slide to stop",
        color = Color(0xFFE8E8E8),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.align(Alignment.Center).alpha(1f - (offsetX / maxOffset).coerceIn(0f, 1f)),
    )
    Box(
        modifier =
            Modifier.offset { IntOffset(offsetX.roundToInt() + insetPx, 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color(0xFFE53935))
                .pointerInput(Unit) {
                  detectDragGestures(
                      onDragEnd = { if (offsetX >= maxOffset * 0.9f) onStop() else offsetX = 0f },
                      onDragCancel = { offsetX = 0f },
                      onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxOffset)
                      },
                  )
                },
        contentAlignment = Alignment.Center,
    ) {
      Text("›", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
  }
}

private const val APP_KEY = "app:"
private const val FOLDER_KEY = "folder:"
private const val WIDGET_KEY = "widget-tile:"
// How long a dragged app must rest over another app before the drop makes a folder (dwell-to-fold).
private const val FOLD_DWELL_MS = 1000L
private const val BUILTIN_CALLS = "builtin:calls"
private const val BUILTIN_STORE = "builtin:store"
private const val BUILTIN_TOOLS = "builtin:tools"
private const val BUILTIN_UPDATES = "builtin:updates"

private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

private fun List<AppEntry>.toLayoutRefs(): List<HomeLayoutModel.AppRef> =
    map { HomeLayoutModel.AppRef(it.component.packageName, it.folder) }

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

private fun estimateWidgetSpan(minDp: Int, tileDp: Dp): Int {
  if (minDp <= 0) return HomeWidgetStore.DEFAULT_SPAN_X
  val tile = tileDp.value.coerceAtLeast(1f)
  return HomeWidgetStore.normalizeSpan(((minDp + tile - 1f) / tile).toInt().coerceAtLeast(1))
}

private fun customWidgetLabel(kind: String): String =
    when (kind) {
      HomeWidgetStore.KIND_WEATHER -> "Weather"
      HomeWidgetStore.KIND_WORLD_CLOCK -> "World Clock"
      HomeWidgetStore.KIND_TIMERS -> "Timers"
      else -> "Widget"
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
  val context = androidx.compose.ui.platform.LocalContext.current
  // Some media apps hand cover art as a URI rather than an embedded bitmap. Resolve it off the
  // main thread (content:// or http) so the header shows the cover too — the screensaver and the
  // phone remote already do this via [MediaArt]; the mini-player was the one place that didn't.
  var urlArt by remember(state.artUrl, state.artBitmap) { mutableStateOf<android.graphics.Bitmap?>(null) }
  androidx.compose.runtime.LaunchedEffect(state.artUrl, state.artBitmap) {
    urlArt =
        if (state.artBitmap == null && state.artUrl.isNotBlank())
            withContext(Dispatchers.IO) { MediaArt.resolveUri(context, state.artUrl) }
        else null
  }
  val cover = state.artBitmap ?: urlArt
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    // The album art IS the play/pause button — same 56dp circle as the other header
    // buttons, just filled with the cover instead of a flat tint.
    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape).tvFocusable(CircleShape) { NowPlayingHub.playPause() },
        contentAlignment = Alignment.Center,
    ) {
      val bmp = cover
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

/** Edit-mode "clean up" button — re-packs every tile into a tidy ordered grid. */
@Composable
private fun TidyButton(onClick: () -> Unit) {
  val path = remember { PathParser().parsePathString(ICON_WIDGETS).toPath() }
  Surface(
      color = Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier =
          Modifier.size(60.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
            onClick()
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
      }
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
private const val ICON_WIDGETS =
    "M3 3h8v8H3V3zm10 0h8v5h-8V3zM3 13h5v8H3v-8zm7 0h11v8H10v-8z"
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
      modifier = Modifier.fillMaxWidth().padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
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
    editMode: Boolean = false,
    onClick: () -> Unit,
) {
  val tileDp = LocalTileDp.current
  val scale = tileDp / 88.dp // mini-icon grid scales with the tile
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.fillMaxWidth().padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    Surface(
        color = Color(0xFF3A3A3A),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.size(tileDp).jiggle(editMode, name.hashCode()),
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
      modifier = Modifier.fillMaxWidth().padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
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
private fun ToolsTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "Tools",
      background = Color(0xFF5A5F6A),
      glyph = ICON_WIDGETS,
      onClick = onClick,
  )
}

@Composable
private fun AddWidgetButton(onClick: () -> Unit) {
  Surface(
      color = Color(0xFF5B6BC0),
      shape = RoundedCornerShape(30.dp),
      modifier = Modifier.width(124.dp).height(60.dp).tvFocusable(RoundedCornerShape(30.dp)) {
        onClick()
      },
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
      Text("+", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
      Text("Widget", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun WidgetTile(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editMode: Boolean,
    tileDp: Dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
  val info = remember(widget.appWidgetId) {
    if (widget.isAppWidget) manager.getAppWidgetInfo(widget.appWidgetId) else null
  }
  val height =
      tileDp * widget.spanY.toFloat() +
          20.dp * (widget.spanY - 1).coerceAtLeast(0).toFloat()

  LaunchedEffect(widget, tileDp) {
    if (widget.isAppWidget) updateWidgetSizeOptions(manager, widget, tileDp)
  }

  Box(modifier = modifier.fillMaxWidth().height(height).padding(4.dp)) {
    Surface(
        color = Color(0xFF252527),
        shape = RoundedCornerShape(20.dp),
        modifier =
            Modifier.fillMaxSize()
                .alpha(if (dimmed) 0.3f else 1f)
                .jiggle(editMode && !dimmed, widget.key.hashCode()),
    ) {
      if (!widget.isAppWidget) {
        ImmortalWidgetContent(widget = widget, modifier = Modifier.fillMaxSize())
      } else if (info != null) {
        AndroidView(
            factory = {
              host.createView(it, widget.appWidgetId, info).apply {
                setAppWidget(widget.appWidgetId, info)
                setPadding(0, 0, 0, 0)
                isFocusable = true
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
              }
            },
            update = { view ->
              view.setAppWidget(widget.appWidgetId, info)
              view.isEnabled = !editMode
            },
            modifier = Modifier.fillMaxSize(),
        )
      } else {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF303033)),
            contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WidgetGlyph()
            Text(
                "Widget unavailable",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                widget.providerPackage,
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 18.dp, end = 18.dp),
            )
          }
        }
      }
    }
    if (editMode) {
      // Dim/scrim only — the drag scrim and ✕ / resize controls are added by the enclosing
      // WidgetCell so they layer correctly above the hosted widget's own touch handling.
      Box(
          Modifier.fillMaxSize()
              .jiggle(!dimmed, widget.key.hashCode())
              .clip(RoundedCornerShape(20.dp))
              .background(Color(0x66000000)))
    }
  }
}

@Composable
private fun ImmortalWidgetContent(widget: HomeWidgetStore.HomeWidget, modifier: Modifier = Modifier) {
  when (widget.kind) {
    HomeWidgetStore.KIND_WEATHER -> ImmortalWeatherWidget(modifier)
    HomeWidgetStore.KIND_WORLD_CLOCK -> ImmortalWorldClockWidget(modifier)
    HomeWidgetStore.KIND_TIMERS -> ImmortalTimersWidget(modifier)
    else -> UnknownImmortalWidget(widget.kind, modifier)
  }
}

@Composable
private fun ImmortalWidgetShell(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF7AA7FF),
    content: @Composable ColumnScope.() -> Unit,
) {
  Column(
      modifier =
          modifier
              .background(Color(0xFF161618), RoundedCornerShape(24.dp))
              .padding(18.dp),
  ) {
    Text(
        title.uppercase(Locale.getDefault()),
        color = accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
    Spacer(Modifier.size(8.dp))
    content()
  }
}

@Composable
private fun ImmortalWeatherWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var fahrenheit by remember { mutableStateOf(ImmortalSettings.useFahrenheit(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) fahrenheit = ImmortalSettings.useFahrenheit(context)
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  val current by
      produceState<Weather.Current?>(initialValue = null, fahrenheit) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchCurrent(context) }
          delay(if (value == null) 60_000L else 30L * 60 * 1000)
        }
      }
  val forecast by
      produceState<Weather.Forecast?>(initialValue = null, fahrenheit) {
        value = withContext(Dispatchers.IO) { Weather.fetchForecast(context) }
      }
  val code = current?.code ?: 0
  val isDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 6..18
  val (gTop, gBottom) = weatherGradient(code, isDay)
  // Dynamic gradient card that matches the weather (iOS-style), city + big temp top-left,
  // condition glyph top-right, and an hourly strip along the bottom.
  Box(
      modifier =
          modifier
              .clip(RoundedCornerShape(24.dp))
              .background(Brush.linearGradient(listOf(gTop, gBottom)))
              .padding(18.dp),
  ) {
    Column(Modifier.fillMaxSize()) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text(
              current?.city?.ifBlank { "Weather" } ?: "Weather",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(
              current?.let { "${it.temp}°" } ?: "—",
              color = Color.White,
              fontSize = 46.sp,
              fontWeight = FontWeight.Light,
          )
        }
        Text(Weather.emoji(code), fontSize = 34.sp)
      }
      Spacer(Modifier.weight(1f))
      val hours = forecast?.hours?.take(5).orEmpty()
      if (hours.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          hours.forEach { h ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
              Text(h.label, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1)
              Text(Weather.emoji(h.code), fontSize = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
              Text("${h.temp}°", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
      }
    }
  }
}

/** A weather-matching two-stop gradient (top → bottom), varying with the condition and day/night. */
private fun weatherGradient(code: Int, isDay: Boolean): Pair<Color, Color> {
  if (!isDay) {
    return when {
      code in 51..99 -> Color(0xFF2A3340) to Color(0xFF49586B)
      code == 3 || code in 45..48 -> Color(0xFF263041) to Color(0xFF3C4A5E)
      else -> Color(0xFF18233F) to Color(0xFF36527E)
    }
  }
  return when {
    code in 71..77 -> Color(0xFF7E93A8) to Color(0xFFBFCEDC) // snow
    code in 51..67 || code in 80..82 -> Color(0xFF45607A) to Color(0xFF7791A6) // rain
    code in 95..99 -> Color(0xFF3A4A5C) to Color(0xFF5E7286) // thunder
    code == 3 -> Color(0xFF5E7E98) to Color(0xFF93ABC0) // overcast
    code in 45..48 -> Color(0xFF7E8E9C) to Color(0xFFAEBBC6) // fog
    else -> Color(0xFF3E8BD0) to Color(0xFF77B8E8) // clear / partly (the blue from the mock)
  }
}

@Composable
private fun ImmortalWorldClockWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var zones by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) zones = ImmortalSettings.worldClockZones(context)
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  var now by remember { mutableStateOf(Date()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(1000)
    }
  }
  ImmortalWidgetShell(title = "World Clock", accent = Color(0xFFFFC857), modifier = modifier) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
      zones.take(4).forEach { zone ->
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          AnalogClock(zone, now, Modifier.fillMaxWidth().aspectRatio(1f))
          Spacer(Modifier.size(6.dp))
          Text(
              worldClockLabel(zone),
              color = Color.White,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
          Text(worldClockOffset(zone, now), color = Color(0xFF9A9A9A), fontSize = 10.sp, maxLines = 1)
        }
      }
    }
  }
}

/** Short city label from a tz id, e.g. "America/New_York" → "New York". */
private fun worldClockLabel(zoneId: String): String =
    zoneId.substringAfterLast('/').replace('_', ' ')

/** Offset of [zoneId] vs the device's zone, e.g. "+5h" / "-3h" / "same". */
private fun worldClockOffset(zoneId: String, now: Date): String {
  val local = TimeZone.getDefault().getOffset(now.time)
  val there = TimeZone.getTimeZone(zoneId).getOffset(now.time)
  val diffH = (there - local) / 3_600_000
  return when {
    diffH == 0 -> "same"
    diffH > 0 -> "+${diffH}h"
    else -> "${diffH}h"
  }
}

/** A small analog clock face for [zoneId], white by day / dark by night, with an orange seconds hand. */
@Composable
private fun AnalogClock(zoneId: String, now: Date, modifier: Modifier = Modifier) {
  val cal = remember(zoneId) { java.util.Calendar.getInstance(TimeZone.getTimeZone(zoneId)) }
  cal.time = now
  val hour = cal.get(java.util.Calendar.HOUR)
  val minute = cal.get(java.util.Calendar.MINUTE)
  val second = cal.get(java.util.Calendar.SECOND)
  val night = cal.get(java.util.Calendar.HOUR_OF_DAY) !in 6..18
  val face = if (night) Color(0xFF1F1F22) else Color.White
  val ink = if (night) Color.White else Color(0xFF1A1A1A)
  val tick = if (night) Color(0x66FFFFFF) else Color(0x55000000)
  Canvas(modifier) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(face, radius = r, center = c)
    // Hour ticks.
    for (i in 0 until 12) {
      val a = Math.toRadians(i * 30.0)
      val sin = kotlin.math.sin(a).toFloat()
      val cos = kotlin.math.cos(a).toFloat()
      drawLine(
          tick,
          start = Offset(c.x + sin * r * 0.82f, c.y - cos * r * 0.82f),
          end = Offset(c.x + sin * r * 0.94f, c.y - cos * r * 0.94f),
          strokeWidth = r * 0.05f,
      )
    }
    fun hand(angleDeg: Double, length: Float, width: Float, color: Color) {
      val a = Math.toRadians(angleDeg)
      drawLine(
          color,
          start = c,
          end = Offset(c.x + (kotlin.math.sin(a) * length).toFloat(), c.y - (kotlin.math.cos(a) * length).toFloat()),
          strokeWidth = width,
          cap = StrokeCap.Round,
      )
    }
    hand((hour % 12 + minute / 60.0) * 30.0, r * 0.5f, r * 0.10f, ink)
    hand((minute + second / 60.0) * 6.0, r * 0.78f, r * 0.07f, ink)
    hand(second * 6.0, r * 0.85f, r * 0.03f, Color(0xFFFF9500))
    drawCircle(Color(0xFFFF9500), radius = r * 0.06f, center = c)
  }
}

@Composable
private fun ImmortalTimersWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var state by remember { mutableStateOf(TimerStore.load(context)) }
  var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
  var showCustom by remember { mutableStateOf(false) }
  // Live updates if the timer is changed elsewhere (e.g. another Timers widget instance).
  DisposableEffect(Unit) {
    val l: () -> Unit = {
      state = TimerStore.load(context)
      nowMs = System.currentTimeMillis()
    }
    TimerStore.addListener(l)
    onDispose { TimerStore.removeListener(l) }
  }
  // Tick the countdown while it's running. When it hits zero we stop ticking but DON'T clear —
  // the exact alarm ([TimerAlarmReceiver]) flips the timer to ringing, which the listener picks up.
  LaunchedEffect(state.running, state.endsAt) {
    while (state.running) {
      nowMs = System.currentTimeMillis()
      if (state.remaining(nowMs) <= 0L) break
      delay(250)
    }
  }
  val remainingMs = state.remaining(nowMs)
  val mins = (remainingMs / 60_000).toInt()
  val secs = ((remainingMs / 1000) % 60).toInt()
  ImmortalWidgetShell(title = "Timers", accent = Color(0xFFFF9F0A), modifier = modifier) {
    Text(
        when {
          state.ringing -> "Time's up"
          remainingMs > 0 -> "%d:%02d".format(mins, secs)
          else -> "Ready"
        },
        color = if (state.ringing) Color(0xFFFF9F0A) else Color.White,
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.size(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.ringing) {
        TimerChip("Stop") { TimerAlarm.stop(context) }
      } else if (remainingMs > 0) {
        TimerChip(if (state.running) "Pause" else "Start") {
          if (state.running) TimerStore.pause(context) else TimerStore.resume(context)
        }
        TimerChip("Reset") {
          TimerStore.clear(context)
        }
      } else {
        listOf(5, 10, 30).forEach { minutes ->
          TimerChip("${minutes}m") { TimerStore.start(context, minutes * 60_000L) }
        }
        TimerChip("Custom") { showCustom = true }
      }
    }
  }
  if (showCustom) {
    CustomTimerDialog(
        onStart = { ms ->
          if (ms > 0) TimerStore.start(context, ms)
          showCustom = false
        },
        onDismiss = { showCustom = false },
    )
  }
}

/** A keyboard-free duration picker (±steppers, remote/touch friendly) for a custom timer. */
@Composable
private fun CustomTimerDialog(onStart: (Long) -> Unit, onDismiss: () -> Unit) {
  var minutes by remember { mutableStateOf(5) }
  var seconds by remember { mutableStateOf(0) }
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
    Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(24.dp)) {
      Column(
          modifier = Modifier.padding(28.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("Custom timer", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(24.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
          DurationStepper("min", minutes, step = 1) { minutes = (minutes + it).coerceIn(0, 180) }
          Text(":", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
          DurationStepper("sec", seconds, step = 5) { seconds = (seconds + it + 60) % 60 }
        }
        Spacer(Modifier.size(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          TimerChip("Cancel") { onDismiss() }
          TimerChip("Start ${minutes}:%02d".format(seconds)) {
            onStart((minutes * 60 + seconds) * 1000L)
          }
        }
      }
    }
  }
}

@Composable
private fun DurationStepper(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label.uppercase(Locale.getDefault()), color = Color(0xFF9A9A9A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.size(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StepButton("−") { onChange(-step) }
      Text("%02d".format(value), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
      StepButton("+") { onChange(step) }
    }
  }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
  Surface(
      color = Color(0x22FFFFFF),
      shape = CircleShape,
      modifier = Modifier.size(44.dp).tvFocusable(CircleShape) { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
    }
  }
}

@Composable
private fun TimerChip(label: String, onClick: () -> Unit) {
  Surface(
      color = Color(0x22FFFFFF),
      shape = RoundedCornerShape(999.dp),
      modifier = Modifier.tvFocusable(RoundedCornerShape(999.dp)) { onClick() },
  ) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
    )
  }
}

@Composable
private fun UnknownImmortalWidget(kind: String, modifier: Modifier = Modifier) {
  Box(modifier.background(Color(0xFF303033)), contentAlignment = Alignment.Center) {
    Text(kind, color = Color.White, fontSize = 16.sp)
  }
}

@Composable
private fun WidgetPickerOverlay(
    providers: List<WidgetProviderEntry>,
    status: String?,
    onPick: (WidgetProviderEntry) -> Unit,
    onDismiss: () -> Unit,
) {
  val noRipple = remember { MutableInteractionSource() }
  BackHandler { onDismiss() }
  val gridFocus = remember { FocusRequester() }
  LaunchedEffect(providers) { runCatching { gridFocus.requestFocus() } }

  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) {
                  if (e.type == KeyEventType.KeyUp) onDismiss()
                  true
                } else false
              }
              .background(Color(0xDD000000))
              .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
  ) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(28.dp),
        modifier =
            Modifier.widthIn(max = 920.dp)
                .fillMaxWidth(0.78f)
                .heightIn(max = 620.dp)
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(modifier = Modifier.padding(28.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Add widget", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose a widget provider installed on this Portal.",
                color = Color(0xFF9A9A9A),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
          }
          Surface(
              color = Color(0x33FFFFFF),
              shape = CircleShape,
              modifier = Modifier.size(44.dp).tvFocusable(CircleShape) { onDismiss() },
          ) {
            Box(contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 20.sp) }
          }
        }
        if (status != null) {
          Text(
              status,
              color = Color(0xFF8AB4F8),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 14.dp),
          )
        }
        Spacer(Modifier.size(20.dp))
        if (providers.isEmpty()) {
          Box(
              modifier = Modifier.fillMaxWidth().height(220.dp),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                "No widget providers are installed.",
                color = Color(0xFFB8B8B8),
                fontSize = 16.sp,
            )
          }
        } else {
          LazyVerticalGrid(
              columns = GridCells.Fixed(3),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
              modifier = Modifier.focusRequester(gridFocus).focusGroup(),
          ) {
            items(providers, key = { it.info?.provider?.flattenToString() ?: "custom:${it.customKind}" }) { provider ->
              WidgetProviderTile(provider = provider, onClick = { onPick(provider) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WidgetProviderTile(provider: WidgetProviderEntry, onClick: () -> Unit) {
  Surface(
      color = Color(0xFF29292C),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.tvFocusable(RoundedCornerShape(18.dp)) { onClick() },
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
          color = Color(0xFF38383C),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.size(58.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (provider.icon != null) {
            Image(
                bitmap = provider.icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
            )
          } else if (provider.customKind != null) {
            CustomWidgetGlyph(provider.customKind)
          } else {
            WidgetGlyph()
          }
        }
      }
      Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
        Text(
            provider.label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            provider.packageLabel,
            color = Color(0xFF9A9A9A),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            "${provider.spanX} x ${provider.spanY}",
            color = Color(0xFF7C7C7C),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
      }
    }
  }
}

@Composable
private fun CustomWidgetGlyph(kind: String) {
  val emoji =
      when (kind) {
        HomeWidgetStore.KIND_WEATHER -> "☁"
        HomeWidgetStore.KIND_WORLD_CLOCK -> "◷"
        HomeWidgetStore.KIND_TIMERS -> "⏱"
        else -> "+"
      }
  Text(emoji, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun WidgetGlyph() {
  val path = remember { PathParser().parsePathString(ICON_WIDGETS).toPath() }
  Canvas(Modifier.size(28.dp)) {
    val s = size.minDimension / 24f
    scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
  }
}

/**
 * iOS-style "jiggle": a gentle continuous wobble applied to editable tiles while Manage mode is on.
 * Each tile is given a [seed] so they fall slightly out of phase (rather than wobbling in lockstep).
 * The animation only exists while [enabled]; it leaves composition when Manage mode ends so the
 * long-lived launcher isn't animating in the background.
 */
@Composable
private fun Modifier.jiggle(enabled: Boolean, seed: Int): Modifier {
  if (!enabled) return this
  val transition = rememberInfiniteTransition(label = "jiggle")
  val phase = (abs(seed) % 6) * 28
  val angle by
      transition.animateFloat(
          initialValue = -2.0f,
          targetValue = 2.0f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 170, easing = LinearEasing),
                  repeatMode = RepeatMode.Reverse,
                  initialStartOffset = StartOffset(phase),
              ),
          label = "jiggle-angle",
      )
  val bob by
      transition.animateFloat(
          initialValue = -1.4f,
          targetValue = 1.4f,
          animationSpec =
              infiniteRepeatable(
                  animation = tween(durationMillis = 230, easing = LinearEasing),
                  repeatMode = RepeatMode.Reverse,
                  initialStartOffset = StartOffset(phase + 60),
              ),
          label = "jiggle-bob",
      )
  return this.graphicsLayer {
    rotationZ = angle
    translationY = bob
  }
}

/**
 * Round red delete affordance with a crisply-drawn, perfectly-centered ✕ (the unicode glyph
 * isn't vertically centered inside its line box, so it's drawn as two crossing strokes instead).
 */
@Composable
private fun RemoveBadge(size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = Color(0xFFE53935),
      shape = CircleShape,
      modifier = modifier.size(size).tvFocusable(CircleShape) { onClick() },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val arm = this.size.minDimension * 0.21f
      val stroke = this.size.minDimension * 0.11f
      drawLine(
          Color.White,
          Offset(cx - arm, cy - arm),
          Offset(cx + arm, cy + arm),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
      drawLine(
          Color.White,
          Offset(cx - arm, cy + arm),
          Offset(cx + arm, cy - arm),
          strokeWidth = stroke,
          cap = StrokeCap.Round,
      )
    }
  }
}

/**
 * Wraps a tile whose own content does NOT swallow touches (built-ins, folders, apps) with a
 * dragged-dim. The drag itself is handled by the stable container detector that wraps the pager, so
 * the gesture survives a page flip (letting a tile be dragged onto another page). Hosted widgets
 * still use their own in-page scrim handle (see [WidgetCell]), since their AndroidView swallows it.
 */
@Composable
private fun HomeTileFrame(
    modifier: Modifier,
    dragged: Boolean,
    content: @Composable () -> Unit,
) {
  Box(modifier = modifier.fillMaxWidth().alpha(if (dragged) 0f else 1f)) { content() }
}

/** Widget tile + the ✕ remove badge in edit mode. Drag is handled by the stable container detector
 * (which grabs widgets via the pointer Initial pass), so widgets drag and move across pages like
 * apps. Widgets are not user-resizable — they keep the size their provider declares. */
@Composable
private fun WidgetCell(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editMode: Boolean,
    tileDp: Dp,
    dimmed: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
) {
  Box(modifier = modifier) {
    WidgetTile(
        widget = widget,
        host = host,
        manager = manager,
        editMode = editMode,
        tileDp = tileDp,
        dimmed = dimmed,
    )
    if (editMode) {
      RemoveBadge(
          size = 30.dp,
          modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp),
          onClick = onRemove,
      )
    }
  }
}

@Composable
private fun AppTile(
    app: AppEntry,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onDelete: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      // In Manage mode the body tap is inert (drag to reorder, ✕ to remove); the
      // icon launches normally otherwise. A long-press enters Manage mode (iOS-style).
      modifier =
          modifier.fillMaxWidth().padding(4.dp).tvFocusable(
              RoundedCornerShape(22.dp),
              enabled = !editMode,
              onLongClick = onLongPress,
          ) {
            onClick()
          },
  ) {
    // The icon (and its delete badge) jiggle as a unit in Manage mode; the label stays still.
    Box(modifier = Modifier.jiggle(editMode && !dimmed, app.component.packageName.hashCode())) {
      Image(
          bitmap = app.icon,
          contentDescription = app.label,
          modifier =
              Modifier.size(LocalTileDp.current)
                  .clip(RoundedCornerShape(20.dp))
                  .alpha(if (dimmed) 0.3f else 1f),
      )
      if (editMode) {
        // Sits at the icon's outer top-right corner (nudged off the icon, iOS-style).
        RemoveBadge(
            size = 26.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-9).dp),
            onClick = onDelete,
        )
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

private fun loadWidgetProviders(context: Context, tileDp: Dp): List<WidgetProviderEntry> {
  val pm = context.packageManager
  val density = context.resources.displayMetrics.densityDpi
  val custom =
      listOf(
          WidgetProviderEntry(
              label = "Weather",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_WEATHER,
          ),
          WidgetProviderEntry(
              label = "World Clock",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_WORLD_CLOCK,
          ),
          WidgetProviderEntry(
              label = "Timers",
              packageLabel = "Immortal",
              icon = null,
              info = null,
              spanX = 2,
              spanY = 2,
              customKind = HomeWidgetStore.KIND_TIMERS,
          ),
      )
  val android =
      AppWidgetManager.getInstance(context)
      .installedProviders
      .mapNotNull { info ->
        runCatching {
              val packageLabel =
                  pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0))
                      .toString()
              val icon =
                  runCatching { info.loadIcon(context, density) }
                      .getOrNull()
                      ?: runCatching { pm.getApplicationIcon(info.provider.packageName) }.getOrNull()
              val iconBitmap = icon?.toBitmap(96, 96)?.asImageBitmap()
              WidgetProviderEntry(
                  label = info.loadLabel(pm).toString().ifBlank { packageLabel },
                  packageLabel = packageLabel,
                  icon = iconBitmap,
                  info = info,
                  spanX =
                      estimateWidgetSpan(appWidgetMinDp(context, info.minWidth), tileDp)
                          .coerceAtLeast(HomeWidgetStore.DEFAULT_SPAN_X),
                  spanY =
                      estimateWidgetSpan(appWidgetMinDp(context, info.minHeight), tileDp)
                          .coerceAtLeast(HomeWidgetStore.DEFAULT_SPAN_Y),
              )
            }
            .getOrNull()
      }
      .sortedWith(
          compareBy(
              { it.packageLabel.lowercase(Locale.getDefault()) },
              { it.label.lowercase(Locale.getDefault()) }))
  return custom + android
}

private fun List<HomeWidgetStore.HomeWidget>.filterLiveWidgets(
    manager: AppWidgetManager,
    host: AppWidgetHost,
): List<HomeWidgetStore.HomeWidget> {
  val live = filter { !it.isAppWidget || manager.getAppWidgetInfo(it.appWidgetId) != null }
  if (live.size != size) {
    val liveIds = live.map { it.appWidgetId }.toSet()
    filter { it.isAppWidget && it.appWidgetId !in liveIds }.forEach {
      runCatching { host.deleteAppWidgetId(it.appWidgetId) }
    }
  }
  return live
}

private fun updateWidgetSizeOptions(
    manager: AppWidgetManager,
    widget: HomeWidgetStore.HomeWidget,
    tileDp: Dp,
) {
  val width =
      (tileDp.value * widget.spanX + 16f * (widget.spanX - 1).coerceAtLeast(0)).roundToInt()
  val height =
      (tileDp.value * widget.spanY + 20f * (widget.spanY - 1).coerceAtLeast(0)).roundToInt()
  runCatching {
    manager.updateAppWidgetOptions(
        widget.appWidgetId,
        Bundle().apply {
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        })
  }
}

private fun appWidgetMinDp(context: Context, raw: Int): Int {
  if (raw <= 0) return 0
  // Android 9/10 on Portal reports AppWidgetProviderInfo sizes as raw complex
  // dimension values (for example, 10241 == 40dp). Newer framework builds may
  // already hand back dp integers, so only decode values that are clearly not
  // plain dp sizes.
  if (raw < 1000) return raw
  val metrics = context.resources.displayMetrics
  return (TypedValue.complexToDimensionPixelSize(raw, metrics) / metrics.density).roundToInt()
}
