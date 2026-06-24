/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.exifinterface.media.ExifInterface
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import org.json.JSONObject

/**
 * The photo-frame UI + update logic, decoupled from any host. Used by both
 * [PhotoDreamService] (the real screensaver) and [PhotoFramePreviewActivity]
 * (an in-app preview you can launch on demand). Reproduces the stock Portal
 * idle screen: full-screen rotating media with a clock / battery / date /
 * weather cluster bottom-left.
 *
 * Source is configurable via [ScreensaverConfig]:
 *  - **default** — the built-in keyless photo feed. This is also the fallback whenever
 *    any other source is unset, empty, or unreachable (e.g. a USB drive was unplugged,
 *    a shared album was unshared). It walks an ordered chain — Unsplash (if a key is
 *    supplied) → Lorem Picsum → Wikimedia Commons featured landscapes → CC0 photos
 *    bundled in the APK — showing the first image it can fetch, so the frame is never
 *    blank even with no network or a third-party outage. See [fetchWebPhoto].
 *  - **folder** — photos and videos from a folder the user picked (internal, SD, or
 *    a USB-C drive), read through the Storage Access Framework.
 *  - **url** — a public share link to an iCloud Shared Album or a Google Photos
 *    shared album. Fetched once on start; the screensaver rotates through the
 *    returned direct image URLs and silently falls back to the default feed if the
 *    album can't be resolved.
 *
 * Weather is Open-Meteo + IP geolocation (both keyless). All network is best-effort.
 */
class PhotoFrameController(
    private val context: Context,
    private val unsplashKey: String = "",
    private val unsplashQuery: String = "nature,landscape,scenic",
    private val weatherRefreshMs: Long = 30 * 60_000L,
    private val calendarRefreshMs: Long = 30 * 60_000L,
) {
  private val io = Executors.newSingleThreadExecutor()
  // A separate single-thread executor for caption work (EXIF read + the reverse-geocode
  // network call), so an 8s geocode lookup can never stall the image-decode pipeline on [io].
  private val metaIo = Executors.newSingleThreadExecutor()
  private val ui = Handler(Looper.getMainLooper())

  private lateinit var photo: ImageView
  private lateinit var videoView: VideoView

  // tvOS-style slow zoom/pan ("Ken Burns") on the photo layer. Runs over the dwell time and is
  // cancelled/restarted on each advance; only in fill mode (fit/letterbox is "show the whole
  // image", which zooming would crop into). [kenBurnsStyle] cycles the motion so consecutive
  // photos don't all move the same way.
  private var kenBurns: AnimatorSet? = null
  private var kenBurnsStyle = 0

  // The "place · date" caption is now a FaceRenderer grid element (so it stacks with the
  // now-playing card instead of overlapping it). This controller still reads the EXIF here
  // (own photos only — local folder / SMB) and pushes it via [FaceRenderer.setCaption].

  // The overlay (clock / date / weather / battery / now-playing) is built and driven by the
  // FaceRenderer from a Face descriptor; this controller owns only the photo/video layer.
  private val faceRenderer = FaceRenderer(context, weatherRefreshMs)

  // Calendar widget (top-right): a clean upcoming-events panel fed by a public ICS
  // feed (Google secret-iCal / Apple iCloud public-calendar). Hidden when no link.
  private lateinit var calendarPanel: LinearLayout
  private lateinit var calendarHeader: TextView
  private lateinit var calendarRows: LinearLayout
  private var calendarEvents: List<CalendarFeed.Event> = emptyList()
  // Text/padding scale for the current calendar size (0/1/2 → small/medium/large),
  // applied as the panel is (re)rendered so a size change takes effect live.
  private var calScale: Float = 1f
  // The minute we last re-windowed the calendar, so the 1s tick only rebuilds it
  // when the clock minute changes (drops past events, refreshes Today/Tomorrow).
  private var lastCalMinute: Long = -1L

  private var settings = ScreensaverConfig.Settings()

  // Local-folder playback state.
  private var localMode = false
  private var playlist: List<MediaItem> = emptyList()
  private var localIndex = -1
  // Bumped on every advance so a slow image-decode or an old video's completion
  // callback can tell it's been superseded and bow out.
  private var gen = 0

  // Remote playback state (iCloud / Google Photos shared albums, or an Immich server).
  private var remoteMode = false
  private var remoteUrls: List<String> = emptyList()
  private var remoteIndex = -1
  private var remoteFailStreak = 0
  // Shared-album signed image URLs (iCloud/Google) expire over time. When a whole loop of
  // downloads fails we re-mint fresh URLs and keep playing instead of dropping to the built-in
  // feed; [remoteReresolveStreak] caps consecutive re-resolves so a genuine outage still settles
  // on the fallback rather than re-fetching forever. [remoteReresolving] guards against
  // overlapping re-fetches.
  private var remoteReresolveStreak = 0
  private var remoteReresolving = false
  // Auth headers sent with each remote image download — empty for public shares, the
  // x-api-key for Immich. Applied in [advanceRemote]/[downloadBitmap].
  private var remoteHeaders: Map<String, String> = emptyMap()
  // Optional custom fetcher for the remote path: when set (SMB), each "url" is fetched through
  // this instead of an HTTP download, so SMB reuses all the remote advance/tick/fallback logic.
  private var remoteFetch: ((String) -> Bitmap?)? = null
  private var smbSource: SmbSource? = null
  // Web-page source: a fullscreen WebView that owns the whole frame (the page brings its own
  // clock/widgets, so the photo layer and Immortal overlay are skipped).
  private var webView: android.webkit.WebView? = null

  // Web-feed history so swipes can go back as well as forward.
  private val history = ArrayList<Bitmap>()
  private var index = -1

  // Default-feed source-chain state (see [fetchWebPhoto]).
  // Wikimedia Commons featured-landscape image list: fetched once per session, then cycled.
  private var wikimediaUrls: List<String> = emptyList()
  private var wikimediaIdx = 0
  // Bundled offline photos (assets/[FALLBACK_DIR]): shuffled once, then cycled.
  private var bundledNames: List<String> = emptyList()
  private var bundledIdx = 0
  // Per-source failure backoff so a dead remote (e.g. a Picsum outage) isn't retried
  // every tick; the entry expires after [SOURCE_COOLDOWN_MS] so recovery self-heals.
  private val sourceCooldownUntil = HashMap<String, Long>()

  /** Host (dream / preview activity) sets this to dismiss the frame on tap. */
  var onExit: (() -> Unit)? = null

  /** Debug/preview override: render this face instead of the built-in classic one. */
  var faceOverride: Face? = null

  // Deterministic gesture from raw down/up deltas (robust to synthetic input
  // that omits MOVE events): clear horizontal swipe = prev/next, clear tap = exit.
  private var downX = 0f
  private var downY = 0f

  /** Hosts forward their touch events here. */
  fun onTouch(ev: MotionEvent) {
    // While the timer alarm is showing, the slide-to-stop owns the touch stream.
    if (faceRenderer.handleAlarmTouch(ev)) return
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = ev.x
        downY = ev.y
      }
      MotionEvent.ACTION_UP -> {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) > 120 && abs(dx) > abs(dy) * 1.5f) {
          if (dx < 0) next() else prev()
        } else if (abs(dx) < 48 && abs(dy) < 48) {
          onExit?.invoke()
        }
      }
    }
  }

  val view: View by lazy { buildUi() }

  fun start() {
    settings = ScreensaverConfig.load(context)
    val source = PhotoFrameSource.from(settings, allowWebPage = faceOverride == null)
    applyFit()
    refreshCalendar.run()
    calendarTick.run()
    // Build + drive the overlay from the user's selected face ([FaceCatalog]); faceOverride lets
    // the debug preview harness (and the overnight night clock) render a specific face instead.
    faceRenderer.start(faceOverride ?: FaceCatalog.active(context))
    when (source) {
      is PhotoFrameSource.WebPage -> {
        // Web-page source takes over the whole frame — no photo feed, no Immortal overlay.
        startWebPage(source.url)
      }
      is PhotoFrameSource.Folder -> {
        io.execute {
          val list =
              if (LocalMedia.isAccessible(source.path)) LocalMedia.enumerate(source.path, source.includeVideo)
              else emptyList()
          ui.post {
            if (list.isNotEmpty()) {
              playlist = if (source.shuffle) list.shuffled() else list
              localMode = true
              localIndex = -1
              advanceLocal(+1)
            } else {
              // Folder empty / unreachable → never leave the frame blank.
              startWeb()
            }
          }
        }
      }
      is PhotoFrameSource.SharedAlbum -> {
        val m = context.resources.displayMetrics
        io.execute {
          val album = RemoteAlbum.fetch(source.url, m.widthPixels, m.heightPixels)
          val urls = album?.photoUrls.orEmpty()
          ui.post {
            if (urls.isNotEmpty()) {
              remoteUrls = if (source.shuffle) urls.shuffled() else urls
              remoteHeaders = emptyMap()
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
              scheduleRemoteRefresh()
            } else {
              // Album unshared / unreachable → never leave the frame blank.
              startWeb()
            }
          }
        }
      }
      is PhotoFrameSource.Immich -> {
        io.execute {
          val urls = ImmichSource.listImageUrls(source.url, source.key, source.albumId).orEmpty()
          ui.post {
            if (urls.isNotEmpty()) {
              remoteUrls = if (source.shuffle) urls.shuffled() else urls
              remoteHeaders = ImmichSource.authHeaders(source.key)
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
            } else {
              // Server unreachable / album empty → never leave the frame blank.
              startWeb()
            }
          }
        }
      }
      is PhotoFrameSource.WebDav -> {
        io.execute {
          val urls = DavSource.listImageUrls(source.url, source.user, source.pass).orEmpty()
          ui.post {
            if (urls.isNotEmpty()) {
              remoteUrls = if (source.shuffle) urls.shuffled() else urls
              remoteHeaders = DavSource.authHeaders(source.user, source.pass)
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
            } else {
              startWeb()
            }
          }
        }
      }
      is PhotoFrameSource.Smb -> {
        val src =
            SmbSource(
                host = source.host,
                shareName = source.share,
                basePath = source.path,
                user = source.user,
                password = source.pass,
            )
        io.execute {
          val paths = if (src.connect()) src.listImages() else emptyList()
          ui.post {
            if (paths.isNotEmpty()) {
              smbSource = src
              remoteUrls = if (source.shuffle) paths.shuffled() else paths
              remoteFetch = { p -> src.openStream(p)?.use { decodeBoundedStream(it) } }
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
            } else {
              // Share unreachable / no images → never leave the frame blank.
              src.close()
              startWeb()
            }
          }
        }
      }
      PhotoFrameSource.DefaultFeed -> startWeb()
    }
  }

  /** Render an arbitrary web page fullscreen (Immich Kiosk, a dashboard, …). The page owns the
   *  whole frame; the host's touch handling still gives tap-to-exit. */
  @android.annotation.SuppressLint("SetJavaScriptEnabled")
  private fun startWebPage(url: String) {
    // Override onTouchEvent/performClick (not just setOnTouchListener, which WebView ignores in its
    // own onTouchEvent) so the page never eats touch — the host needs it for tap-to-exit / swipe.
    val wv =
        object : android.webkit.WebView(context) {
          override fun onTouchEvent(event: MotionEvent): Boolean = false
          override fun performClick(): Boolean = false
        }
    wv.setBackgroundColor(Color.BLACK)
    wv.isVerticalScrollBarEnabled = false
    wv.isHorizontalScrollBarEnabled = false
    wv.overScrollMode = View.OVER_SCROLL_NEVER
    wv.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      mediaPlaybackRequiresUserGesture = false
      loadWithOverviewMode = true
      useWideViewPort = true
    }
    wv.webViewClient = android.webkit.WebViewClient() // keep navigation inside the WebView
    (view as FrameLayout).addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
    wv.loadUrl(url)
    webView = wv
  }

  fun stop() {
    ui.removeCallbacksAndMessages(null)
    cancelKenBurns()
    faceRenderer.stop()
    if (this::videoView.isInitialized) runCatching { videoView.stopPlayback() }
    webView?.let { runCatching { it.stopLoading(); it.destroy() } }
    webView = null
    // Close the SMB connection off-thread (network I/O) before the io executor is killed.
    smbSource?.let { s -> Thread { runCatching { s.close() } }.start() }
    smbSource = null
    io.shutdownNow()
    metaIo.shutdownNow()
  }

  // --- UI ---------------------------------------------------------------------
  // The photo/video layer lives here; the overlay (clock / widgets / now-playing) is the
  // FaceRenderer's [view], stacked on top from a Face descriptor.
  private fun buildUi(): View {
    val root = FrameLayout(context)
    root.setBackgroundColor(Color.BLACK)

    photo = ImageView(context)
    photo.scaleType = ImageView.ScaleType.CENTER_CROP
    root.addView(photo, FrameLayout.LayoutParams(MATCH, MATCH))

    videoView = VideoView(context)
    videoView.visibility = View.GONE
    root.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

    root.addView(faceRenderer.view)
    buildCalendar(root)
    return root
  }

  /** Read EXIF date/GPS for a local file off [metaIo], reverse-geocode the place, then publish
   *  the caption — guarded by [gen] so a slow lookup for a superseded photo is dropped. */
  private fun loadCaptionForLocal(path: String, g: Int) {
    metaIo.execute {
      val meta = runCatching { PhotoCaption.read(ExifInterface(path)) }.getOrNull()
      publishCaption(meta, g)
    }
  }

  /** Same as [loadCaptionForLocal] but for an SMB file: a fresh read stream feeds EXIF. */
  private fun loadCaptionForSmb(path: String, g: Int) {
    val src = smbSource ?: return
    metaIo.execute {
      val meta =
          runCatching { src.openStream(path)?.use { PhotoCaption.read(ExifInterface(it)) } }
              .getOrNull()
      publishCaption(meta, g)
    }
  }

  private fun publishCaption(meta: PhotoCaption.Meta?, g: Int) {
    if (meta == null || meta.isEmpty) {
      ui.post { if (g == gen) faceRenderer.setCaption(null, null) }
      return
    }
    // Resolve the place name on this background thread (network) before touching the UI.
    val place = if (meta.hasLocation) PhotoCaption.placeName(meta.lat!!, meta.lng!!) else null
    val date = PhotoCaption.formatDate(meta.dateMillis)
    ui.post { if (g == gen) faceRenderer.setCaption(place, date) }
  }

  /** A clean upcoming-events panel top-right, over the photo. Its own translucent
   *  rounded backing keeps it legible without a full-screen scrim. Hidden until a
   *  calendar link is set and there's something to show. */
  private fun buildCalendar(root: FrameLayout) {
    calendarPanel = LinearLayout(context)
    calendarPanel.orientation = LinearLayout.VERTICAL
    calendarPanel.visibility = View.GONE
    val bg = GradientDrawable()
    bg.setColor(0x66000000)
    bg.cornerRadius = dp(18).toFloat()
    calendarPanel.background = bg
    calendarPanel.setPadding(dp(22), dp(18), dp(22), dp(18))
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
    lp.setMargins(0, dp(40), dp(40), 0)
    root.addView(calendarPanel, lp)

    calendarHeader = text(15f, 0xCCFFFFFF.toInt(), false)
    calendarHeader.typeface = Typeface.DEFAULT_BOLD
    calendarHeader.letterSpacing = 0.08f
    calendarPanel.addView(calendarHeader)

    calendarRows = LinearLayout(context)
    calendarRows.orientation = LinearLayout.VERTICAL
    val rowsLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowsLp.topMargin = dp(10)
    calendarPanel.addView(calendarRows, rowsLp)
  }

  /** Rebuild the calendar panel from the cached events for the chosen range. Cheap
   *  (no network) — called when a fetch lands and once per minute from [tick]. */
  private fun renderCalendar() {
    if (!this::calendarPanel.isInitialized) return
    if (!settings.usesCalendar) {
      calendarPanel.visibility = View.GONE
      return
    }
    val now = System.currentTimeMillis()
    val shown = CalendarFeed.window(calendarEvents, settings.calendarRange, now)
    // Size + position can change at runtime (settings screen or a fleet push), so
    // (re)apply the chrome each render. Scale drives text/padding; side drives the edge.
    calScale =
        when (settings.calendarSize) {
          0 -> 0.82f
          2 -> 1.22f
          else -> 1f
        }
    applyCalendarChrome()
    calendarHeader.textSize = 15f * calScale
    calendarHeader.text = rangeLabel(settings.calendarRange).uppercase(Locale.getDefault())
    calendarRows.removeAllViews()
    calendarPanel.visibility = View.VISIBLE

    if (shown.isEmpty()) {
      val empty = text(17f * calScale, 0xCCFFFFFF.toInt(), false)
      empty.text = "Nothing scheduled"
      calendarRows.addView(empty)
      return
    }

    val agenda = settings.calendarRange == CalendarFeed.RANGE_AGENDA
    var lastDayKey = ""
    for (ev in shown) {
      // Day header whenever the day changes (and always in agenda mode, which spans
      // arbitrary dates). A single-day range needs no per-day header.
      val key = dayKey(ev.startMillis)
      if (key != lastDayKey && (agenda || settings.calendarRange != CalendarFeed.RANGE_DAY)) {
        val header = text(13f * calScale, 0x99FFFFFF.toInt(), false)
        header.typeface = Typeface.DEFAULT_BOLD
        header.text = dayHeader(ev.startMillis).uppercase(Locale.getDefault())
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = if (calendarRows.childCount == 0) 0 else dp(12)
        calendarRows.addView(header, hlp)
      }
      lastDayKey = key
      calendarRows.addView(buildEventRow(ev))
    }
  }

  /** Position the panel against the chosen edge and scale its padding to the size. */
  private fun applyCalendarChrome() {
    val pad = (22 * calScale).toInt()
    val padV = (18 * calScale).toInt()
    calendarPanel.setPadding(dp(pad), dp(padV), dp(pad), dp(padV))
    val gravity =
        Gravity.TOP or
            (if (settings.calendarSide == ScreensaverConfig.CAL_SIDE_LEFT) Gravity.START
            else Gravity.END)
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, gravity)
    val side = dp(40)
    if (settings.calendarSide == ScreensaverConfig.CAL_SIDE_LEFT) lp.setMargins(side, dp(40), 0, 0)
    else lp.setMargins(0, dp(40), side, 0)
    calendarPanel.layoutParams = lp
  }

  private fun buildEventRow(ev: CalendarFeed.Event): View {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    val rowLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowLp.topMargin = dp(6)

    val time = text(17f * calScale, Color.WHITE, false)
    time.text = if (ev.allDay) "All day" else timeLabel(ev.startMillis)
    time.width = dp((96 * calScale).toInt())

    val title = text(17f * calScale, Color.WHITE, false)
    title.typeface = Typeface.DEFAULT_BOLD
    title.text = ev.title
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    title.maxWidth = dp((360 * calScale).toInt())

    row.addView(time)
    row.addView(title)
    row.layoutParams = rowLp
    return row
  }

  private fun rangeLabel(range: String): String =
      when (range) {
        CalendarFeed.RANGE_3DAY -> "Next 3 days"
        CalendarFeed.RANGE_WEEK -> "This week"
        CalendarFeed.RANGE_AGENDA -> "Upcoming"
        else -> "Today"
      }

  private fun timeLabel(millis: Long): String {
    val pattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
  }

  /** A stable per-day key (yyyyDDD) so the renderer can tell when the day changes. */
  private fun dayKey(millis: Long): String =
      SimpleDateFormat("yyyyDDD", Locale.US).format(Date(millis))

  /** "Today" / "Tomorrow" / "Sat, Jun 21" relative to now. */
  private fun dayHeader(millis: Long): String {
    val today = dayKey(System.currentTimeMillis())
    val tomorrow = dayKey(System.currentTimeMillis() + 24L * 60 * 60 * 1000)
    return when (dayKey(millis)) {
      today -> "Today"
      tomorrow -> "Tomorrow"
      else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(millis))
    }
  }

  private fun applyFit() {
    // Video letterboxes either way (VideoView limitation); images honour the choice.
    photo.scaleType =
        if (settings.fit == ScreensaverConfig.FIT_FIT) ImageView.ScaleType.FIT_CENTER
        else ImageView.ScaleType.CENTER_CROP
  }

  private fun text(sizeSp: Float, color: Int, light: Boolean): TextView {
    val t = TextView(context)
    t.textSize = sizeSp
    t.setTextColor(color)
    if (light) t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    t.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    return t
  }

  private fun intervalMs(): Long = settings.intervalSec * 1000L

  // --- periodic loops ---------------------------------------------------------
  private val calendarTick =
      object : Runnable {
        override fun run() {
          // Re-window the calendar once per minute (cheap, no network): drops events
          // as they pass and rolls the Today/Tomorrow labels over at midnight.
          val now = Date()
          val minute = now.time / 60_000L
          if (minute != lastCalMinute) {
            lastCalMinute = minute
            renderCalendar()
          }
          ui.postDelayed(this, 1_000L)
        }
      }

  private val rotate =
      object : Runnable {
        override fun run() {
          webNext()
          ui.postDelayed(this, intervalMs())
        }
      }

  private val refreshCalendar =
      object : Runnable {
        override fun run() {
          // Pick up calendar changes pushed over the fleet API (or via in-app
          // settings) without restarting the dream: reload just the calendar fields,
          // leaving the live slideshow state untouched.
          val fresh = ScreensaverConfig.load(context)
          if (fresh.calendarUrl != settings.calendarUrl ||
              fresh.calendarRange != settings.calendarRange) {
            settings = settings.copy(calendarUrl = fresh.calendarUrl, calendarRange = fresh.calendarRange)
            renderCalendar()
          }
          val url = settings.calendarUrl
          if (settings.usesCalendar && !url.isNullOrBlank()) {
            io.execute {
              val events = CalendarFeed.fetch(url)
              ui.post {
                calendarEvents = events
                renderCalendar()
              }
            }
          } else {
            calendarEvents = emptyList()
            renderCalendar()
          }
          ui.postDelayed(this, calendarRefreshMs)
        }
      }
  // --- navigation (branches on the active source) -----------------------------
  fun next() {
    when {
      localMode -> advanceLocal(+1)
      remoteMode -> advanceRemote(+1)
      else -> webNext()
    }
  }

  fun prev() {
    when {
      localMode -> advanceLocal(-1)
      remoteMode -> advanceRemote(-1)
      else -> webPrev()
    }
  }

  // --- local folder playback --------------------------------------------------
  private val localTick =
      object : Runnable {
        override fun run() {
          advanceLocal(+1)
        }
      }

  private fun advanceLocal(dir: Int) {
    ui.removeCallbacks(localTick)
    if (playlist.isEmpty()) {
      startWeb()
      return
    }
    gen++
    localIndex = ((localIndex + dir) % playlist.size + playlist.size) % playlist.size
    val item = playlist[localIndex]
    if (item.isVideo) showVideo(item.path, gen) else showLocalImage(item.path, gen)
  }

  private fun showLocalImage(path: String, g: Int) {
    stopVideo()
    io.execute {
      val bmp = runCatching { decodeCorrected(path) }.getOrNull()
      ui.post {
        if (g != gen) return@post // superseded by a newer advance
        if (bmp == null) {
          advanceLocal(+1) // skip an unreadable file
          return@post
        }
        photo.visibility = View.VISIBLE
        show(bmp)
        loadCaptionForLocal(path, g)
        ui.postDelayed(localTick, intervalMs())
      }
    }
  }

  /**
   * Downsample any photo to a safe size before it ever reaches the [ImageView].
   *
   * A full-resolution camera original is enormous once decoded: a ~39MP shot becomes a ~157MB
   * ARGB_8888 bitmap, which is larger than the hardware Canvas can draw (~100MB). The draw then
   * throws "trying to draw too large bitmap" on the main thread and crashes the whole launcher —
   * and because Android responds to a *home app* crashing by clearing its preferred-activity (HOME)
   * association, the very next Home press pops the "Select Home app" chooser. Bounding the longest
   * edge to [MAX_EDGE] here (the same two-pass trick used by [Thumbnails]/[MediaArt]) keeps every
   * source — folder, SMB, Immich, WebDAV, bundled — well under the cap, so it can't happen.
   *
   * The bound never upscales (small images decode untouched) and uses a power-of-two
   * [BitmapFactory.Options.inSampleSize], the only value the decoder honours efficiently.
   */
  private fun sampleSizeFor(w: Int, h: Int): Int {
    val longest = maxOf(w, h)
    return if (longest > MAX_EDGE) Integer.highestOneBit(longest / MAX_EDGE) else 1
  }

  private fun decodeBoundedFile(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val opts =
        BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
    return BitmapFactory.decodeFile(path, opts)
  }

  /** Stream variant: buffers to bytes so the bounds pass can run before the real decode. */
  private fun decodeBoundedStream(input: java.io.InputStream): Bitmap? {
    val bytes = input.readBytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts =
        BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
  }

  /**
   * Decode a local image and apply its EXIF orientation. Phone cameras save the
   * raw sensor buffer and record the intended rotation in an EXIF tag rather than
   * baking it into the pixels, so [BitmapFactory.decodeFile] alone shows portrait
   * shots sideways and some landscapes upside-down. The web feed is unaffected (its
   * images carry no rotation flag), so this only matters for the folder source.
   *
   * Reading the tag is best-effort: a missing or unreadable EXIF block falls back to
   * the upright orientation so a quirky file still shows (just unrotated) instead of
   * being skipped.
   */
  private fun decodeCorrected(path: String): Bitmap? {
    val bmp = decodeBoundedFile(path) ?: return null
    val orientation =
        runCatching {
              ExifInterface(path)
                  .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.preScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(-90f)
        matrix.preScale(-1f, 1f)
      }
      else -> return bmp // NORMAL / UNDEFINED — already upright, no copy needed
    }
    // createBitmap allocates a second full-size bitmap; free the source once the
    // rotated copy exists so peak memory stays at one image (Portal heaps are small
    // and phone photos are large).
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    if (rotated != bmp) bmp.recycle()
    return rotated
  }

  private fun showVideo(path: String, g: Int) {
    cancelKenBurns()
    faceRenderer.setCaption(null, null)
    photo.setImageDrawable(null)
    photo.visibility = View.GONE
    videoView.visibility = View.VISIBLE
    runCatching {
          videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            runCatching { mp.setVolume(0f, 0f) } // a screensaver shouldn't blare audio
            if (g == gen) videoView.start()
          }
          videoView.setOnCompletionListener {
            if (g == gen) advanceLocal(+1)
          }
          videoView.setOnErrorListener { _, _, _ ->
            if (g == gen) advanceLocal(+1)
            true
          }
          videoView.setVideoPath(path)
          // Safety net: advance even if a clip is very long or never reports done.
          ui.postDelayed(localTick, maxOf(intervalMs(), 5 * 60_000L))
        }
        .onFailure { if (g == gen) advanceLocal(+1) }
  }

  private fun stopVideo() {
    if (this::videoView.isInitialized && videoView.visibility != View.GONE) {
      runCatching { videoView.stopPlayback() }
      videoView.visibility = View.GONE
    }
  }

  // --- remote album playback (iCloud / Google Photos public shares) -----------
  private val remoteTick =
      object : Runnable {
        override fun run() {
          advanceRemote(+1)
        }
      }

  // Shuffle is applied once at start, not on refresh — re-shuffling every tick
  // would scramble the user's current position.
  private val remoteRefresh =
      object : Runnable {
        override fun run() {
          if (!remoteMode) return
          val shareUrl = settings.albumUrl
          if (shareUrl.isNullOrBlank()) return
          val m = context.resources.displayMetrics
          io.execute {
            val fresh = RemoteAlbum.fetch(shareUrl, m.widthPixels, m.heightPixels)
            val urls = fresh?.photoUrls.orEmpty()
            ui.post {
              if (remoteMode && urls.isNotEmpty()) {
                remoteUrls = urls
                if (remoteIndex >= remoteUrls.size) remoteIndex = -1
                remoteFailStreak = 0
              }
              scheduleRemoteRefresh()
            }
          }
        }
      }

  private fun scheduleRemoteRefresh() {
    ui.removeCallbacks(remoteRefresh)
    ui.postDelayed(remoteRefresh, settings.albumRefreshMin * 60_000L)
  }

  private fun advanceRemote(dir: Int) {
    ui.removeCallbacks(remoteTick)
    if (remoteUrls.isEmpty()) {
      startWeb()
      return
    }
    // One failure per URL = the whole album is unreachable. For a public-album share this
    // usually means its signed image URLs have simply expired, so try to re-mint fresh ones
    // before giving up to the built-in feed (see [reresolveOrFallback]).
    if (remoteFailStreak >= remoteUrls.size) {
      reresolveOrFallback()
      return
    }
    gen++
    remoteIndex = ((remoteIndex + dir) % remoteUrls.size + remoteUrls.size) % remoteUrls.size
    val url = remoteUrls[remoteIndex]
    val g = gen
    stopVideo()
    io.execute {
      val fetch = remoteFetch
      val bmp =
          runCatching { fetch?.invoke(url) ?: downloadBitmap(url, remoteHeaders) }.getOrNull()
      ui.post {
        if (g != gen) return@post // superseded by a newer advance
        if (!remoteMode) return@post // raced with startWeb() flipping us off
        if (bmp == null) {
          remoteFailStreak++
          advanceRemote(+1)
          return@post
        }
        remoteFailStreak = 0
        remoteReresolveStreak = 0
        photo.visibility = View.VISIBLE
        show(bmp)
        // EXIF caption only for SMB here — it reads the user's own files. The HTTP remote sources
        // (iCloud/Google/Immich/DAV) serve EXIF-stripped images, so they carry no caption.
        if (smbSource != null) loadCaptionForSmb(url, g) else faceRenderer.setCaption(null, null)
        ui.postDelayed(remoteTick, intervalMs())
      }
    }
  }

  /**
   * A full loop of failed downloads on a public-album share almost always means its signed image
   * URLs have expired (iCloud/Google hand out short-lived links), not that the album is gone. So
   * re-fetch fresh URLs and keep playing rather than reverting to the built-in feed — the bug
   * where a "refresh" silently dropped a working shared album back to the stock screensaver.
   *
   * Only the public-album URL source mints expiring links; the others (Immich/DAV/SMB) use stable
   * endpoints, so a full failure loop there is a genuine outage and falls back as before.
   * [remoteReresolveStreak] caps consecutive re-resolves (reset on any successful photo) so a real,
   * sustained outage still settles on the fallback instead of re-fetching forever.
   */
  private fun reresolveOrFallback() {
    if (remoteReresolving) return
    val shareUrl = settings.albumUrl
    if (!settings.usesUrl || shareUrl.isNullOrBlank() || remoteReresolveStreak >= MAX_RERESOLVE) {
      startWeb()
      return
    }
    remoteReresolving = true
    remoteReresolveStreak++
    val m = context.resources.displayMetrics
    io.execute {
      val fresh = RemoteAlbum.fetch(shareUrl, m.widthPixels, m.heightPixels)
      val urls = fresh?.photoUrls.orEmpty()
      ui.post {
        remoteReresolving = false
        if (!remoteMode) return@post // raced with a source change / startWeb()
        if (urls.isNotEmpty()) {
          remoteUrls = if (settings.shuffle) urls.shuffled() else urls
          remoteIndex = -1
          remoteFailStreak = 0
          advanceRemote(+1)
        } else {
          // Album genuinely unreachable / unshared → don't leave the frame spinning.
          startWeb()
        }
      }
    }
  }

  // --- web feed (default + fallback) ------------------------------------------
  private fun startWeb() {
    localMode = false
    remoteMode = false
    remoteHeaders = emptyMap()
    remoteFetch = null
    smbSource?.let { s -> io.execute { runCatching { s.close() } } }
    smbSource = null
    ui.removeCallbacks(remoteTick)
    ui.removeCallbacks(remoteRefresh)
    stopVideo()
    rotate.run()
  }

  /** Forward through history, loading a fresh photo when at the end. */
  private fun webNext() {
    if (index in 0 until history.size - 1) {
      index++
      show(history[index])
    } else {
      loadFresh()
    }
  }

  /** Back through history (no-op at the start). */
  private fun webPrev() {
    if (index > 0) {
      index--
      show(history[index])
    }
  }

  private fun loadFresh() {
    io.execute {
      val bmp = fetchWebPhoto() ?: return@execute
      ui.post {
        photo.visibility = View.VISIBLE
        if (history.size >= 6) history.removeAt(0) // cap memory; GC reclaims
        history.add(bmp)
        index = history.size - 1
        show(bmp)
      }
    }
  }

  // The default feed (and the universal fallback for any other source that's unset,
  // empty, or unreachable) walks an ordered chain of keyless sources and returns the
  // first image it can fetch:
  //   1. Unsplash  — only when an API key was supplied (richest, but rate-limited)
  //   2. Picsum    — keyless; the historical default
  //   3. Wikimedia — Commons "Featured pictures of landscapes"; keyless, and on
  //                  infrastructure independent of Picsum (the point of having it)
  //   4. Bundled   — CC0/public-domain photos shipped in the APK; can't fail, so a
  //                  fresh device with no network yet — or a day every web source is
  //                  down (e.g. the Picsum outage that prompted this) — is never blank.
  private val webSources: List<Pair<String, () -> Bitmap?>> by lazy {
    buildList {
      if (unsplashKey.isNotBlank()) add("unsplash" to ::unsplashBitmap)
      add("picsum" to ::picsumBitmap)
      add("wikimedia" to ::wikimediaBitmap)
      add("bundled" to ::bundledBitmap)
    }
  }

  private fun fetchWebPhoto(): Bitmap? {
    val now = System.currentTimeMillis()
    for ((name, fetch) in webSources) {
      if ((sourceCooldownUntil[name] ?: 0L) > now) continue // dead recently; skip until cooldown
      val bmp = runCatching { fetch() }.getOrNull()
      if (bmp != null) return bmp
      Log.w(TAG, "screensaver photo source '$name' unavailable; backing off")
      sourceCooldownUntil[name] = now + SOURCE_COOLDOWN_MS
    }
    // Everything failed or is cooling down (e.g. all remotes down *and* the bundled
    // assets are unreadable). Retry the whole chain ignoring cooldowns rather than
    // leave the frame blank.
    for ((_, fetch) in webSources) {
      runCatching { fetch() }.getOrNull()?.let { return it }
    }
    Log.w(TAG, "screensaver: no photo source available")
    return null
  }

  private fun show(bmp: Bitmap) {
    // The caption belongs to whichever photo is incoming; hide it now and let the per-source
    // metadata load re-show it (web/CDN photos never re-show it — they carry no EXIF).
    faceRenderer.setCaption(null, null)
    photo
        .animate()
        .alpha(0.15f)
        .setDuration(220)
        .withEndAction {
          photo.setImageBitmap(bmp)
          startKenBurns()
          photo.animate().alpha(1f).setDuration(420).start()
        }
        .start()
  }

  /**
   * Apply a slow tvOS-style zoom/pan to the current photo over the dwell time. Cancelled and
   * restarted on each advance. Only in fill mode: in fit/letterbox mode the user asked to see the
   * whole frame, so cropping into it with a zoom would defeat that. A little overscan (scale
   * [BIG]) gives pan styles room to move without ever exposing the black background.
   */
  private fun startKenBurns() {
    kenBurns?.cancel()
    // Reset to identity first so a cancelled mid-animation transform never lingers on the new shot.
    photo.scaleX = 1f
    photo.scaleY = 1f
    photo.translationX = 0f
    photo.translationY = 0f
    if (settings.fit != ScreensaverConfig.FIT_FILL) return
    val w = (if (photo.width > 0) photo.width else context.resources.displayMetrics.widthPixels)
    val h = (if (photo.height > 0) photo.height else context.resources.displayMetrics.heightPixels)
    photo.pivotX = w / 2f
    photo.pivotY = h / 2f
    val pan = (BIG - 1f) / 2f // max translation fraction that stays within the overscan at scale BIG
    val set = AnimatorSet()
    when ((kenBurnsStyle++ % 4 + 4) % 4) {
      0 -> // slow zoom in
      set.playTogether(
          ObjectAnimator.ofFloat(photo, View.SCALE_X, 1f, BIG),
          ObjectAnimator.ofFloat(photo, View.SCALE_Y, 1f, BIG))
      1 -> // slow zoom out
      set.playTogether(
          ObjectAnimator.ofFloat(photo, View.SCALE_X, BIG, 1f),
          ObjectAnimator.ofFloat(photo, View.SCALE_Y, BIG, 1f))
      2 -> { // pan down (hold the zoom so the edges stay covered)
        photo.scaleX = BIG
        photo.scaleY = BIG
        set.playTogether(ObjectAnimator.ofFloat(photo, View.TRANSLATION_Y, pan * h, -pan * h))
      }
      else -> { // pan up
        photo.scaleX = BIG
        photo.scaleY = BIG
        set.playTogether(ObjectAnimator.ofFloat(photo, View.TRANSLATION_Y, -pan * h, pan * h))
      }
    }
    set.duration = intervalMs() + 1200L // outlast the dwell so the motion never visibly stalls
    set.interpolator = AccelerateDecelerateInterpolator()
    set.start()
    kenBurns = set
  }

  private fun cancelKenBurns() {
    kenBurns?.cancel()
    kenBurns = null
    if (this::photo.isInitialized) {
      photo.scaleX = 1f
      photo.scaleY = 1f
      photo.translationX = 0f
      photo.translationY = 0f
    }
  }

  // --- default-feed sources (tried in turn by [fetchWebPhoto]) ----------------
  private fun picsumBitmap(): Bitmap? {
    val m = context.resources.displayMetrics
    return downloadBitmap(
        "https://picsum.photos/${m.widthPixels}/${m.heightPixels}?random=${System.currentTimeMillis()}")
  }

  private fun unsplashBitmap(): Bitmap? {
    val m = context.resources.displayMetrics
    // Match the Unsplash crop to the screen aspect so portrait panels (e.g. Portal
    // Mini at 800x1280) don't get a letterboxed/upscaled landscape shot.
    val orientation = if (m.heightPixels > m.widthPixels) "portrait" else "landscape"
    val json =
        httpGet(
            "https://api.unsplash.com/photos/random?orientation=$orientation" +
                "&query=$unsplashQuery&client_id=$unsplashKey")
    return downloadBitmap(JSONObject(json).getJSONObject("urls").getString("regular"))
  }

  /**
   * Keyless Wikimedia Commons feed: the curated "Featured pictures of landscapes"
   * category. The file list is fetched once per session (then shuffled and cycled),
   * so steady state is just a thumbnail download from Wikimedia's upload CDN.
   */
  private fun wikimediaBitmap(): Bitmap? {
    if (wikimediaUrls.isEmpty()) wikimediaUrls = fetchWikimediaUrls()
    if (wikimediaUrls.isEmpty()) return null
    val url = wikimediaUrls[wikimediaIdx % wikimediaUrls.size]
    wikimediaIdx++
    return downloadBitmap(url)
  }

  private fun fetchWikimediaUrls(): List<String> {
    val width = context.resources.displayMetrics.widthPixels.coerceIn(640, 1920)
    val json =
        httpGet(
            "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
                "&generator=categorymembers&gcmtype=file&gcmlimit=200" +
                "&gcmtitle=Category:Featured_pictures_of_landscapes" +
                "&prop=imageinfo&iiprop=url&iiurlwidth=$width")
    val pages =
        JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
    val urls = ArrayList<String>()
    for (key in pages.keys()) {
      val info = pages.getJSONObject(key).optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
      val url = info.optString("thumburl").ifBlank { info.optString("url") }
      if (url.isNotBlank()) urls.add(url)
    }
    urls.shuffle()
    return urls
  }

  /** Terminal fallback: CC0/public-domain photos bundled in the APK — never fails. */
  private fun bundledBitmap(): Bitmap? {
    if (bundledNames.isEmpty()) {
      bundledNames =
          runCatching {
                context.assets.list(FALLBACK_DIR)?.filter { it.endsWith(".jpg", ignoreCase = true) }
              }
              .getOrNull()
              .orEmpty()
              .shuffled()
    }
    if (bundledNames.isEmpty()) return null
    val name = bundledNames[bundledIdx % bundledNames.size]
    bundledIdx++
    return runCatching {
          context.assets.open("$FALLBACK_DIR/$name").use { decodeBoundedStream(it) }
        }
        .getOrNull()
  }

  // --- data -------------------------------------------------------------------
  private fun httpGet(spec: String): String {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.setRequestProperty("User-Agent", USER_AGENT)
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  private fun downloadBitmap(spec: String, headers: Map<String, String> = emptyMap()): Bitmap? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 12000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", USER_AGENT)
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    return c.inputStream.use { decodeBoundedStream(it) }
  }

  private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
  private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
  // Ken Burns overscan: the photo is scaled to this at the zoomed end / throughout a pan, giving
  // pan styles room to travel without exposing the black background behind the image.
  private val BIG = 1.12f
  // Cap on consecutive shared-album re-resolves before yielding to the built-in feed (a real
  // sustained outage), reset on any successful photo. See [reresolveOrFallback].
  private val MAX_RERESOLVE = 3
  private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

  private companion object {
    const val TAG = "ImmortalPhotoFrame"
    // Cap on the longest edge of any decoded photo. Full-res camera originals (tens of MP) decode
    // to bitmaps larger than the hardware Canvas can draw (~100MB), which crashes the launcher and
    // makes Android drop its default-home role. The largest Portal panel is 1920px; 2560 leaves
    // Ken-Burns overscan headroom (2560²·4 ≈ 26MB) while staying far under the cap. See
    // [decodeBoundedFile]/[decodeBoundedStream].
    const val MAX_EDGE = 2560
    // Bundled fallback photos live under app/src/main/assets/<FALLBACK_DIR>/.
    const val FALLBACK_DIR = "photoframe_fallback"
    // How long to skip a web source after it fails, so a dead host (e.g. a Picsum
    // outage) isn't re-hammered every tick. Expires on its own so recovery self-heals.
    const val SOURCE_COOLDOWN_MS = 5 * 60_000L
    // Descriptive UA — Wikimedia's API policy asks for an identifiable agent + contact.
    const val USER_AGENT = "Immortal/1.0 (+https://github.com/starbrightlab/immortal)"
  }
}
