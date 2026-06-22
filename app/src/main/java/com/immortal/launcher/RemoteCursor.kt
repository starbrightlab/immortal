/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The on-TV pointer for the phone-remote touchpad. The remote can't move a real cursor
 * (the Portal has no pointer), so we draw our own dot via a **TYPE_ACCESSIBILITY_OVERLAY**
 * — the same overlay class [QuickBar] uses — and dispatch taps under it through the
 * accessibility service ([RemoteInput]). It's the visual feedback that makes a "blind"
 * trackpad usable: you watch this dot on the TV, not the phone.
 *
 * The overlay is **not touchable** (FLAG_NOT_TOUCHABLE) so the synthesized gesture passes
 * straight through to the app beneath it. Hosted by [BarWatchService] (attached on connect,
 * like [QuickBar]); it auto-hides after a few idle seconds so it isn't always on screen.
 */
object RemoteCursor {
  private const val TAG = "ImmortalRemote"
  private const val SIZE_DP = 26
  private const val IDLE_HIDE_MS = 4000L

  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var view: View? = null
  private var lp: WindowManager.LayoutParams? = null
  private var sizePx = 0
  private val hideRunnable = Runnable { setVisible(false) }

  fun attach(service: AccessibilityService) {
    main.post {
      host = service
      wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      val d = service.resources.displayMetrics.density
      sizePx = (SIZE_DP * d).toInt()
    }
  }

  fun detach() {
    main.post {
      main.removeCallbacks(hideRunnable)
      view?.let { v -> runCatching { wm?.removeView(v) } }
      view = null
      lp = null
      host = null
      wm = null
    }
  }

  /** Show (or move) the pointer to the screen-pixel centre [xPx], [yPx] and arm the idle hide. */
  fun showAt(xPx: Int, yPx: Int) {
    main.post {
      val ctx = host ?: return@post
      val wmgr = wm ?: return@post
      if (view == null) {
        val params =
            WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                .apply { gravity = Gravity.TOP or Gravity.START }
        val dot =
            View(ctx).apply {
              background =
                  GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCCFFFFFF.toInt()) // mostly-opaque white fill
                    setStroke((2 * ctx.resources.displayMetrics.density).toInt(), 0xFF2E6BE6.toInt())
                  }
            }
        runCatching { wmgr.addView(dot, params); view = dot; lp = params }
            .onFailure { Log.w(TAG, "cursor addView failed", it) }
      }
      val params = lp ?: return@post
      params.x = xPx - sizePx / 2
      params.y = yPx - sizePx / 2
      view?.let { v ->
        v.visibility = View.VISIBLE
        runCatching { wmgr.updateViewLayout(v, params) }
      }
      main.removeCallbacks(hideRunnable)
      main.postDelayed(hideRunnable, IDLE_HIDE_MS)
    }
  }

  private fun setVisible(visible: Boolean) {
    main.post { view?.visibility = if (visible) View.VISIBLE else View.GONE }
  }
}
