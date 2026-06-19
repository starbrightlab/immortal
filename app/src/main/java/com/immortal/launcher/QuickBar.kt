/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The quick-button cluster: a centered row of buttons pinned to the top of the screen, over any
 * app. Centered (not left/right) so it never collides with the system bar's back/home (left) or
 * status icons (right), and works on any Portal size without per-model layout.
 *
 * Hosted by [BarWatchService] as a **TYPE_ACCESSIBILITY_OVERLAY** — that renders ABOVE the system
 * bar (a plain app overlay sits below it, so it would draw under the bar and its taps would be
 * eaten). The same service detects the bar reveal and drives [setBarVisible]. Visibility =
 * always-show OR the top bar is showing. The accessibility service only runs while the feature is
 * enabled (immortal self-enables it), so no separate foreground service is needed.
 *
 * v1 holds one button (app switcher). The row is the extension point — add child views later.
 */
object QuickBar {
  private const val TAG = "ImmortalQuickBar"
  private val main = Handler(Looper.getMainLooper())

  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  @Volatile private var barVisible = false

  /** Add the (initially evaluated) cluster overlay, hosted by the accessibility service. */
  fun attach(service: AccessibilityService) {
    main.post {
      if (view != null) {
        refresh()
        return@post
      }
      host = service
      val wmgr = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      wm = wmgr
      val cluster = buildCluster(service)
      val lp =
          WindowManager.LayoutParams(
                  WindowManager.LayoutParams.WRAP_CONTENT,
                  // Span the full bar height so the button can sit vertically centered within it,
                  // matching the back/home buttons (rather than flush to the top edge).
                  statusBarHeight(service),
                  WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                  PixelFormat.TRANSLUCENT,
              )
              .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
      runCatching { wmgr.addView(cluster, lp); view = cluster }
          .onFailure { Log.w(TAG, "addView failed", it) }
      refresh()
    }
  }

  fun detach() {
    main.post {
      view?.let { v -> runCatching { wm?.removeView(v) } }
      view = null
      host = null
      wm = null
    }
  }

  /** Called by [BarWatchService] when the system top bar is revealed / hidden. */
  fun setBarVisible(visible: Boolean) {
    barVisible = visible
    main.post { refresh() }
  }

  /** Re-evaluate visibility after a config change (e.g. the always-show toggle). */
  fun applyConfig() = main.post { refresh() }

  private fun refresh() {
    val v = view ?: return
    val ctx = host ?: return
    v.visibility = if (QuickBarConfig.alwaysShow(ctx) || barVisible) View.VISIBLE else View.GONE
  }

  private fun statusBarHeight(ctx: Context): Int {
    val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) ctx.resources.getDimensionPixelSize(id)
    else (28 * ctx.resources.displayMetrics.density).toInt()
  }

  // --- UI ---------------------------------------------------------------------

  private fun buildCluster(ctx: Context): View =
      // Fills the bar-height window; CENTER keeps the button vertically centered in the bar.
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(appSwitcherButton(ctx))
      }

  /** A pill sized/shaped to match the system bar's back/home buttons, with the standard recents glyph. */
  private fun appSwitcherButton(ctx: Context): View {
    val d = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    return ImageView(ctx).apply {
      setImageResource(R.drawable.ic_app_switcher)
      scaleType = ImageView.ScaleType.CENTER_INSIDE
      // Match the back/home pill footprint: wider than tall, fully rounded, light fill.
      minimumWidth = dp(76)
      minimumHeight = dp(44)
      setPadding(dp(20), dp(10), dp(20), dp(10))
      background =
          GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(0x33FFFFFF)
          }
      setOnClickListener {
        runCatching {
          ctx.startActivity(
              Intent(ctx, AppSwitcherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
    }
  }
}
