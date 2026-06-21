/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Builds and drives the screensaver **overlay** from a [Face] — clock, date, weather,
 * battery, and the now-playing card — laid out on the 9-point grid. The photo/video layer
 * and all source/power logic stay in [PhotoFrameController]; this owns only what's drawn on
 * top, plus the per-second tick, the weather fetch, and the now-playing subscription.
 *
 * This is the new render path that replaces the original hardcoded overlay. [Face.immortalClassic]
 * reproduces that original face through it; richer modes (flip / analog / word / gradient /
 * neon) extend the widget builders here.
 */
class FaceRenderer(
    private val context: Context,
    private val weatherRefreshMs: Long = 30 * 60_000L,
) {
  private val io = Executors.newSingleThreadExecutor()
  private val ui = Handler(Looper.getMainLooper())
  private val assets = AssetResolver(context)

  private var face: Face = Face("immortal-classic")

  /** The overlay container, added above the photo layer by the host. */
  val view: FrameLayout by lazy {
    FrameLayout(context).apply {
      layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
      // Let text shadows overflow their tight boxes instead of being clipped into hard rectangles.
      clipChildren = false
      clipToPadding = false
    }
  }

  // Position buckets (a vertical stack per used grid cell), created on demand.
  private val buckets = HashMap<GridPosition, LinearLayout>()

  // Time-bearing views the tick refreshes; null when the face omits them.
  private var clockFace: ClockFaceView? = null
  private var dateView: TextView? = null
  private var batteryView: TextView? = null
  private var batteryDivider: View? = null
  private var weatherView: TextView? = null
  private var weatherDivider: View? = null
  private var weatherText: String = ""
  private var blinkOn = true

  // Now-playing card.
  private var nowPlayingCard: LinearLayout? = null
  private var npArt: ImageView? = null
  private var npTitle: TextView? = null
  private var npArtist: TextView? = null
  private var lastArtUrl: String = ""
  private var lastArtBitmap: Bitmap? = null
  private var npListener: NowPlayingHub.Listener? = null

  fun start(face: Face) {
    this.face = face
    buildOverlay()
    tick.run()
    refreshWeather.run()
    // Now-playing is independent of the face (see buildOverlay): subscribe whenever the user has
    // the setting on, so music shows on every face — even the full-bleed flip clock.
    if (ScreensaverConfig.load(context).showNowPlaying) {
      npListener = NowPlayingHub.Listener { state -> ui.post { updateNowPlaying(state) } }
      NowPlayingHub.addListener(npListener!!)
    }
  }

  fun stop() {
    ui.removeCallbacksAndMessages(null)
    npListener?.let { NowPlayingHub.removeListener(it) }
    npListener = null
    clockFace?.dispose()
    io.shutdownNow()
  }

  // --- overlay assembly -------------------------------------------------------
  private fun buildOverlay() {
    view.removeAllViews()
    buckets.clear()

    buildClockCluster(face.clock)
    val fullBleed = clockFace?.fullBleed == true

    // A full-bleed clock (e.g. the Fliqlo flip clock) owns the whole frame on its own near-black
    // background, so skip the scrim and the date/battery/weather widgets — none should overlay it.
    if (!fullBleed) {
      // Legibility scrim under the bottom cluster, matching the original frame.
      val scrim = View(context)
      scrim.background =
          GradientDrawable(
              GradientDrawable.Orientation.BOTTOM_TOP,
              intArrayOf(0xCC000000.toInt(), 0x00000000),
          )
      view.addView(scrim, 0, FrameLayout.LayoutParams(MATCH, dp(320), Gravity.BOTTOM))
      buildStandaloneWidgets(face)
    }

    // Now-playing is independent of the face: it shows on EVERY face — including the full-bleed
    // flip clock — whenever the user has the now-playing setting on (it's high-value enough to be
    // its own switch, not tied to face selection). The card self-hides until music is playing.
    if (ScreensaverConfig.load(context).showNowPlaying) buildNowPlaying(face.nowPlaying)
  }

  /**
   * The clock and any small meta fields (date / battery / weather) that share its grid
   * position: the time on top, then a single horizontal "date • battery • weather" row,
   * reproducing the original overlay. Each optional field owns the divider that precedes it,
   * so a dot never orphans when its field is hidden (e.g. a battery-less Portal).
   */
  private fun buildClockCluster(clock: ClockSpec) {
    val made = makeClockFace(context, clock, assets)
    clockFace = made
    made.update(Date(), blinkOn)

    // A full-bleed clock (WebView) is its own full-screen layer that positions itself; it sits
    // below the native widgets and owns no grid bucket. The meta row is skipped for it (the
    // HTML clock owns the clock area; date/battery/weather handling moves into the face later).
    if (made.fullBleed) {
      view.addView(made.view, FrameLayout.LayoutParams(MATCH, MATCH))
      return
    }

    val col = bucket(clock.position)
    // Explicit WRAP: a vertical LinearLayout otherwise defaults children to MATCH_PARENT width.
    col.addView(
        made.view,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    val row = LinearLayout(context)
    row.gravity = Gravity.CENTER_VERTICAL
    row.clipChildren = false // let each field's shadow overflow rather than clip to a box

    if (clock.showDate) {
      dateView =
          textView(baseSp = 22f, spec = clock, light = false).also { row.addView(it) }
    }
    if (face.battery.enabled && face.battery.position == clock.position) {
      batteryDivider = divider().also { row.addView(it) }
      batteryView =
          textView(baseSp = 22f, spec = clock, light = false).also { row.addView(it) }
    }
    if (face.weather.enabled && face.weather.position == clock.position) {
      weatherDivider = divider().also { row.addView(it) }
      weatherView =
          textView(baseSp = 22f, spec = clock, light = false).also { row.addView(it) }
    }
    // Explicit WRAP width: a horizontal row added to a vertical LinearLayout otherwise defaults to
    // MATCH_PARENT and gets constrained to the (often narrower) clock's width above it, clipping
    // the date/battery/weather on the right. WRAP lets the row size to its own content.
    if (row.childCount > 0)
        col.addView(row, LinearLayout.LayoutParams(WRAP, LinearLayout.LayoutParams.WRAP_CONTENT))
  }

  /** Weather / battery that aren't co-located with the clock get their own line in their bucket. */
  private fun buildStandaloneWidgets(face: Face) {
    if (face.battery.enabled && face.battery.position != face.clock.position) {
      batteryView =
          textView(baseSp = 22f, spec = face.clock, light = false).also {
            bucket(face.battery.position).addView(it)
          }
    }
    if (face.weather.enabled && face.weather.position != face.clock.position) {
      weatherView =
          textView(baseSp = 22f, spec = face.clock, light = false).also {
            bucket(face.weather.position).addView(it)
          }
    }
  }

  /** Now-playing card (album art + track / artist), hidden until something is playing. */
  private fun buildNowPlaying(spec: NowPlayingSpec) {
    val scale = spec.sizeScale / 100f
    val card = LinearLayout(context)
    card.orientation = LinearLayout.HORIZONTAL
    card.gravity = Gravity.CENTER_VERTICAL
    card.visibility = View.GONE

    // Text first, art at the right edge: title/artist hug the cover (right-aligned).
    val npCol = LinearLayout(context)
    npCol.orientation = LinearLayout.VERTICAL
    npCol.gravity = Gravity.END
    card.addView(npCol, LinearLayout.LayoutParams(WRAP, WRAP))

    val title = text(26f * scale, Color.WHITE, false)
    title.typeface = Typeface.DEFAULT_BOLD
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    title.gravity = Gravity.END
    title.maxWidth = dp(560)
    npCol.addView(title, LinearLayout.LayoutParams(WRAP, WRAP))

    val artist = text(18f * scale, 0xCCFFFFFF.toInt(), false)
    artist.maxLines = 1
    artist.ellipsize = TextUtils.TruncateAt.END
    artist.gravity = Gravity.END
    artist.maxWidth = dp(560)
    npCol.addView(artist, LinearLayout.LayoutParams(WRAP, WRAP))

    val art = ImageView(context)
    art.scaleType = ImageView.ScaleType.CENTER_CROP
    art.clipToOutline = true
    art.outlineProvider =
        object : ViewOutlineProvider() {
          override fun getOutline(v: View, o: Outline) {
            o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
          }
        }
    val artLp = LinearLayout.LayoutParams((dp(72) * scale).toInt(), (dp(72) * scale).toInt())
    artLp.setMarginStart(dp(16))
    if (spec.showArt) card.addView(art, artLp)

    bucket(spec.position).addView(card)
    nowPlayingCard = card
    npTitle = title
    npArtist = artist
    npArt = art
  }

  /** Reflect the latest now-playing state (main thread). Mirrors the original behaviour. */
  private fun updateNowPlaying(s: NowPlayingState?) {
    val card = nowPlayingCard ?: return
    if (s == null || !s.active) {
      card.visibility = View.GONE
      lastArtUrl = ""
      lastArtBitmap = null
      return
    }
    card.visibility = View.VISIBLE
    npTitle?.text = s.title
    npArtist?.text = s.artist
    npArtist?.visibility = if (s.artist.isBlank()) View.GONE else View.VISIBLE
    val art = npArt ?: return
    val bitmap = s.artBitmap
    if (bitmap != null) {
      if (bitmap !== lastArtBitmap) {
        lastArtBitmap = bitmap
        lastArtUrl = ""
        art.visibility = View.VISIBLE
        art.setImageBitmap(bitmap)
      }
    } else if (s.artUrl != lastArtUrl) {
      lastArtBitmap = null
      lastArtUrl = s.artUrl
      art.setImageBitmap(null)
      val want = s.artUrl
      art.visibility = if (want.isNotBlank()) View.VISIBLE else View.GONE
      if (want.isNotBlank())
          io.execute {
            val bmp = runCatching { downloadBitmap(want) }.getOrNull()
            ui.post { if (lastArtUrl == want) art.setImageBitmap(bmp) }
          }
    }
  }

  // --- periodic loops ---------------------------------------------------------
  private val tick =
      object : Runnable {
        override fun run() {
          val now = Date()
          blinkOn = !blinkOn
          clockFace?.update(now, blinkOn)
          if (face.clock.showDate)
              dateView?.text =
                  SimpleDateFormat(face.clock.dateFormat.pattern(), Locale.getDefault()).format(now)
          val (pct, charging) = batteryInfo()
          val hasBattery = pct >= 0
          batteryView?.text = if (hasBattery) (if (charging) "$pct% ⚡" else "$pct%") else ""
          batteryView?.visibility = if (hasBattery) View.VISIBLE else View.GONE
          batteryDivider?.visibility = if (hasBattery) View.VISIBLE else View.GONE
          val hasWeather = weatherText.isNotBlank()
          weatherView?.text = weatherText
          weatherView?.visibility = if (hasWeather) View.VISIBLE else View.GONE
          weatherDivider?.visibility = if (hasWeather) View.VISIBLE else View.GONE
          ui.postDelayed(this, 1_000L)
        }
      }

  private val refreshWeather =
      object : Runnable {
        override fun run() {
          io.execute {
            val w = Weather.fetch(context)
            if (w.isNotBlank()) weatherText = w
          }
          ui.postDelayed(this, weatherRefreshMs)
        }
      }

  // --- styling ----------------------------------------------------------------
  /** A styled text view (meta row) honouring the clock spec's font / colour / shadow / size. */
  private fun textView(baseSp: Float, spec: ClockSpec, light: Boolean = true): TextView {
    val t = TextView(context)
    t.textSize = baseSp * spec.sizeScale / 100f
    t.setTextColor(FaceStyle.colorWithOpacity(spec.color, spec.opacity))
    t.typeface = FaceStyle.typeface(assets, spec, light)
    FaceStyle.applyShadow(t, spec.shadow, spec.color)
    return t
  }

  /** Plain styled text (now-playing), matching the original helper. */
  private fun text(sizeSp: Float, color: Int, lightFont: Boolean): TextView {
    val t = TextView(context)
    t.textSize = sizeSp
    t.setTextColor(color)
    if (lightFont) t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    t.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    return t
  }

  private fun divider(): View {
    val v = TextView(context)
    v.text = "   •   "
    v.textSize = 22f
    v.setTextColor(0x88FFFFFF.toInt())
    return v
  }

  // --- grid -------------------------------------------------------------------
  /** Get (or create) the vertical stack for a grid position, placed with the right gravity. */
  private fun bucket(pos: GridPosition): LinearLayout =
      buckets.getOrPut(pos) {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = horizontalGravity(pos)
        col.clipChildren = false // shadows on the clock / meta text may overflow this column
        col.clipToPadding = false
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, gravityFor(pos))
        lp.setMargins(dp(40), dp(40), dp(40), dp(40))
        view.addView(col, lp)
        col
      }

  private fun gravityFor(pos: GridPosition): Int =
      when (pos) {
        GridPosition.TOP_LEFT -> Gravity.TOP or Gravity.START
        GridPosition.TOP_CENTER -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        GridPosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
        GridPosition.MIDDLE_LEFT -> Gravity.CENTER_VERTICAL or Gravity.START
        GridPosition.MIDDLE_CENTER -> Gravity.CENTER
        GridPosition.MIDDLE_RIGHT -> Gravity.CENTER_VERTICAL or Gravity.END
        GridPosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
        GridPosition.BOTTOM_CENTER -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        GridPosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
      }

  private fun horizontalGravity(pos: GridPosition): Int =
      when (pos) {
        GridPosition.TOP_RIGHT,
        GridPosition.MIDDLE_RIGHT,
        GridPosition.BOTTOM_RIGHT -> Gravity.END
        GridPosition.TOP_CENTER,
        GridPosition.MIDDLE_CENTER,
        GridPosition.BOTTOM_CENTER -> Gravity.CENTER_HORIZONTAL
        else -> Gravity.START
      }

  // --- data -------------------------------------------------------------------
  /** Battery percent (-1 = no battery / mains-only Portal) and whether it's charging. */
  private fun batteryInfo(): Pair<Int, Boolean> {
    val i =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1 to false
    if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return -1 to false
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging =
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    return pct to charging
  }

  private fun downloadBitmap(spec: String): Bitmap? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 12000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "PortalPhotoFrame/1.0")
    return c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
  }

  private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
  private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
  private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
