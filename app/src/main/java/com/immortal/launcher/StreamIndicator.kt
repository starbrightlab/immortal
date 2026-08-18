/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A small "camera live" badge, on top of whatever is on screen, for as long as the camera is
 * streaming. It does two jobs, and both of them matter.
 *
 * **It tells the room.** Continuous capture needs a signal that survives the launcher being in
 * the background — a toast can't do that, and the notification is behind whatever app is in
 * front. Someone walking past a Portal should be able to see that its camera is live.
 *
 * **It keeps the camera open.** Android 9 restricts camera access to foreground apps, and a
 * foreground *service* is not enough on its own: opening any other app killed the stream with
 * `ERROR_CAMERA_DISABLED` (verified on a Portal Go — the stream ran happily until VLC came to
 * the front). Holding a visible overlay window keeps this app's camera access alive while
 * something else is in front. It's the same lever `portal-ha-bridge` documents needing
 * `SYSTEM_ALERT_WINDOW` for, and provisioning already grants it.
 *
 * That the privacy signal and the technical requirement are the same object is a happy accident,
 * but it's worth stating: removing the badge to "clean up the UI" would silently break streaming.
 */
object StreamIndicator {
  private const val TAG = "ImmortalStream"
  private var view: View? = null

  /** Show the badge. Safe to call twice; must be called from the main thread. */
  fun show(context: Context) {
    if (view != null) return
    if (!canOverlay(context)) {
      Log.w(TAG, "no overlay permission — the stream will stop when another app comes forward")
      return
    }
    runCatching {
          val density = context.resources.displayMetrics.density
          val pad = (8 * density).toInt()
          val badge =
              TextView(context).apply {
                text = "● CAMERA LIVE"
                setTextColor(Color.WHITE)
                textSize = 11f
                setPadding(pad, pad / 2, pad, pad / 2)
                background =
                    GradientDrawable().apply {
                      cornerRadius = 12 * density
                      setColor(Color.parseColor("#CC0B84")) // magenta-red: not a UI colour we use
                    }
              }
          val lp =
              WindowManager.LayoutParams(
                  WindowManager.LayoutParams.WRAP_CONTENT,
                  WindowManager.LayoutParams.WRAP_CONTENT,
                  WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                  // Not focusable and not touchable: it must never take input from whatever the
                  // user is actually doing.
                  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                      WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                  PixelFormat.TRANSLUCENT,
              )
          lp.gravity = Gravity.TOP or Gravity.END
          lp.x = (12 * density).toInt()
          lp.y = (12 * density).toInt()
          wm(context).addView(badge, lp)
          view = badge
          Log.i(TAG, "camera-live badge shown")
        }
        .onFailure { Log.w(TAG, "couldn't show the camera-live badge", it) }
  }

  /** Remove the badge. Safe to call when it isn't showing. */
  fun hide(context: Context) {
    val v = view ?: return
    view = null
    runCatching { wm(context).removeView(v) }
        .onFailure { Log.w(TAG, "couldn't remove the camera-live badge", it) }
  }

  private fun wm(context: Context) =
      context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

  private fun canOverlay(context: Context): Boolean =
      runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
}
