/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * The MQTT-notify toast. A bottom- or top-anchored card with optional image + title +
 * message, hosted by [BarWatchService] as a third `wm.addView` peer alongside [QuickBar]
 * and [RemoteCursor].
 *
 * Why an accessibility-service overlay instead of a regular window: `TYPE_ACCESSIBILITY_OVERLAY`
 * renders above the system bar and doesn't require `SYSTEM_ALERT_WINDOW`, matching how
 * [QuickBar] already pins its row to the bar.
 *
 * Replace-don't-stack: a new [show] takes over the slot, cancels the previous auto-dismiss
 * timer, and re-arms a new one. Tap dismisses early and fires the payload's `on_tap` action
 * (the host passes the dispatcher in — the overlay doesn't know about HA routing).
 *
 * See `docs/design/mqtt-notifications.md` for the full schema and behavior contract.
 */
object NotificationOverlay {
  private const val TAG = "ImmortalNotifyOverlay"
  private const val IMAGE_MAX_EDGE_PX = 512
  private const val IMAGE_TIMEOUT_MS = 3000
  private val main = Handler(Looper.getMainLooper())

  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  private var dismissAt: Long = 0
  private var onTapCallback: (() -> Unit)? = null

  // --- lifecycle (called by BarWatchService) ----------------------------------

  fun attach(service: AccessibilityService) {
    main.post {
      host = service
      wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }
  }

  fun detach() {
    main.post { hideInternal() }
    main.post {
      host = null
      wm = null
    }
  }

  // --- public API (called by MqttPublisher.handleNotify) ----------------------

  /**
   * Display [spec]. Replaces any current toast. Image fetch and decode happen on a worker
   * thread; the toast renders text immediately and the image fills in when it lands (or
   * never, if the fetch fails — the toast still shows). [onTap] is invoked when the toast
   * is tapped before auto-dismiss; the overlay itself doesn't know about HA routing.
   */
  fun show(spec: NotifyPayload, onTap: (() -> Unit)? = null) {
    main.post { showInternal(spec, onTap) }
  }

  private fun showInternal(spec: NotifyPayload, onTap: (() -> Unit)?) {
    val ctx = host ?: run {
      // Boot race: MqttPublisher may receive a notify before BarWatchService.onServiceConnected
      // has fired. Drop with a log so it's visible in logcat — producers can retry.
      Log.w(TAG, "show() before attach(); dropping payload (title='${spec.title}')")
      return
    }
    val wmgr = wm ?: return

    // Cancel any in-flight auto-dismiss before we replace.
    main.removeCallbacks(dismissRunnable)
    onTapCallback = onTap
    removeView(wmgr)

    val card = buildCard(ctx, spec)
    // Explicit pixel width (display width minus side insets) — MATCH_PARENT plus
    // CENTER_HORIZONTAL collapses to content-width on TYPE_ACCESSIBILITY_OVERLAY in some
    // configurations, leaving the toast pinned narrow and left-aligned. Pixel width is
    // unambiguous. Standoff from the gravity edge goes on the window via `y`, not on the
    // root view (addView overwrites the root view's layoutParams with the WindowManager
    // params we pass).
    val dm = ctx.resources.displayMetrics
    val sideInsetPx = (24 * dm.density).toInt()
    val standoffPx = (48 * dm.density).toInt()
    val lp =
        WindowManager.LayoutParams(
                dm.widthPixels - 2 * sideInsetPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
              gravity =
                  Gravity.CENTER_HORIZONTAL or
                      when (spec.position) {
                        NotifyPayload.Position.TOP -> Gravity.TOP
                        NotifyPayload.Position.BOTTOM -> Gravity.BOTTOM
                      }
              y = standoffPx
            }

    runCatching { wmgr.addView(card, lp); view = card }
        .onFailure { Log.w(TAG, "addView failed", it) }

    // Wire taps (whole-card click).
    card.setOnClickListener {
      val cb = onTapCallback
      hideInternal()
      runCatching { cb?.invoke() }
    }

    // Schedule auto-dismiss unless duration is 0 (acknowledgement-required).
    if (spec.durationSec > 0) {
      dismissAt = System.currentTimeMillis() + spec.durationSec * 1000L
      main.postDelayed(dismissRunnable, spec.durationSec * 1000L)
    } else {
      dismissAt = 0
    }

    // Image (if any) fetches in the background and sets when ready.
    spec.image?.let { src ->
      val imageView = card.findViewWithTag<ImageView>(IMAGE_TAG) ?: return@let
      loadImage(src, imageView)
    }
  }

  /** Hide and free the current toast without firing on-tap. Safe to call repeatedly. */
  fun dismiss() {
    main.post { hideInternal() }
  }

  // --- internals --------------------------------------------------------------

  private val dismissRunnable = Runnable { hideInternal() }

  private fun hideInternal() {
    main.removeCallbacks(dismissRunnable)
    onTapCallback = null
    val wmgr = wm
    if (wmgr != null) removeView(wmgr)
  }

  private fun removeView(wmgr: WindowManager) {
    view?.let { v -> runCatching { wmgr.removeView(v) } }
    view = null
  }

  // --- view building ----------------------------------------------------------

  private const val IMAGE_TAG = "notify_image"

  private fun buildCard(ctx: Context, spec: NotifyPayload): View {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    // Root view's layoutParams are overwritten by the WindowManager.LayoutParams passed to
    // addView, so don't bother setting margins or width here — the window's pixel width and
    // y-standoff (set in showInternal) are the source of truth for size and position.
    val card =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setPadding(dp(16), dp(12), dp(16), dp(12))
          background =
              GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(0xE6101010.toInt()) // ~90% opaque near-black, matches launcher overlays
              }
        }

    if (spec.image != null) {
      val img =
          ImageView(ctx).apply {
            tag = IMAGE_TAG
            scaleType = ImageView.ScaleType.CENTER_CROP
            background =
                GradientDrawable().apply {
                  shape = GradientDrawable.RECTANGLE
                  cornerRadius = dp(8).toFloat()
                  setColor(0x33FFFFFF) // placeholder until the bitmap loads
                }
            clipToOutline = true
          }
      val imgLp = LinearLayout.LayoutParams(dp(80), dp(80))
      imgLp.setMargins(0, 0, dp(12), 0)
      card.addView(img, imgLp)
    }

    val textColumn =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.VERTICAL
          val lp =
              LinearLayout.LayoutParams(
                  0,
                  LinearLayout.LayoutParams.WRAP_CONTENT,
                  1f,
              )
          layoutParams = lp
        }

    if (spec.title.isNotEmpty()) {
      textColumn.addView(
          TextView(ctx).apply {
            text = spec.title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
          })
    }
    if (spec.message.isNotEmpty()) {
      textColumn.addView(
          TextView(ctx).apply {
            text = spec.message
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (spec.title.isNotEmpty()) {
              val lp = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                  LinearLayout.LayoutParams.MATCH_PARENT,
                  LinearLayout.LayoutParams.WRAP_CONTENT,
              )
              lp.topMargin = dp(2)
              layoutParams = lp
            }
          })
    }
    card.addView(textColumn)
    return card
  }

  // --- image loading ----------------------------------------------------------

  private fun loadImage(source: String, target: ImageView) {
    Thread {
          val bitmap = if (source.startsWith("data:")) decodeInline(source) else fetchAndDecode(source)
          if (bitmap != null) main.post { runCatching { target.setImageBitmap(bitmap) } }
        }
        .apply {
          isDaemon = true
          name = "notify-image-fetch"
        }
        .start()
  }

  /** Decode a `data:image/...;base64,...` URI. Falls back to null on any malformed input. */
  private fun decodeInline(uri: String): Bitmap? =
      runCatching {
            val comma = uri.indexOf(',')
            if (comma <= 0) return@runCatching null
            val bytes = Base64.decode(uri.substring(comma + 1), Base64.DEFAULT)
            decodeSampled(bytes)
          }
          .onFailure { Log.w(TAG, "inline image decode failed", it) }
          .getOrNull()

  /** Fetch [url] with a short timeout and decode with `inSampleSize` to cap memory. */
  private fun fetchAndDecode(url: String): Bitmap? =
      runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = IMAGE_TIMEOUT_MS
            conn.readTimeout = IMAGE_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
          }
          .onFailure { Log.w(TAG, "image fetch failed: $url", it) }
          .getOrNull()
          ?.let { decodeSampled(it) }

  /**
   * Decode [bytes] with `inSampleSize` chosen so the long edge lands at ≤ [IMAGE_MAX_EDGE_PX].
   * Two-pass: header-only first to read dimensions, then full decode at the sampled size.
   * Non-optional on Gen-1 Portal+ — full-res decode of a 4K snapshot will OOM.
   */
  private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    val long = maxOf(opts.outWidth, opts.outHeight)
    var sample = 1
    while (long / sample > IMAGE_MAX_EDGE_PX) sample *= 2
    val full = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, full)
  }
}
