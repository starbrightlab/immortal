/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat

/**
 * System-wide "back swipe" gesture.
 *
 * Runs as a foreground service and draws a thin transparent overlay on the
 * right edge of the screen. When the user swipes leftward within that strip,
 * the service asks the Immortal accessibility service to dispatch a global
 * BACK action.
 *
 * Requirements:
 *  - The user must grant Immortal the "Draw over other apps" permission
 *    (Settings → Apps → Immortal → Display over other apps).
 *  - For the back action to actually work, the Immortal accessibility service
 *    must be enabled (see Clock settings → Back shortcut).
 *
 * The service is started by the launcher when the user enables the toggle
 * in settings, and stopped when the user disables it or uninstalls the app.
 */
class SystemBackGestureService : Service() {

  private var windowManager: WindowManager? = null
  private var overlayView: View? = null

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    startInForeground()
    drawOverlay()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    removeOverlay()
  }

  private fun startInForeground() {
    val channelId = "immortal_back_gesture"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java)
      if (nm.getNotificationChannel(channelId) == null) {
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Back gesture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Listening for right-edge back swipes" }
        )
      }
    }
    val notification: Notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle("Immortal Back Gesture")
        .setContentText("Swipe from the right edge to go back")
        .setSmallIcon(android.R.drawable.ic_menu_revert)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
    startForeground(NOTIFICATION_ID, notification)
  }

  private fun drawOverlay() {
    if (overlayView != null) return
    val wm = windowManager ?: return
    if (!android.provider.Settings.canDrawOverlays(this)) {
      Log.w(TAG, "cannot draw overlays — permission not granted")
      stopSelf()
      return
    }

    val stripWidth = (40 * resources.displayMetrics.density).toInt()
    val params = WindowManager.LayoutParams(
        stripWidth,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )
    params.gravity = Gravity.TOP or Gravity.END

    val view = FrameLayout(this)
    view.setBackgroundColor(Color.TRANSPARENT)

    var startX = 0f
    var startY = 0f
    view.setOnTouchListener { _, ev ->
      when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          startX = ev.x
          startY = ev.y
          true
        }
        MotionEvent.ACTION_UP -> {
          val dx = ev.x - startX
          val dy = kotlin.math.abs(ev.y - startY)
          if (dx < -120f && dy < 80f) {
            // Swipe leftward from the right edge — perform a global BACK.
            performGlobalBack()
          }
          true
        }
        else -> true
      }
    }
    try {
      wm.addView(view, params)
      overlayView = view
      Log.i(TAG, "back-gesture overlay added")
    } catch (e: Exception) {
      Log.e(TAG, "failed to add overlay", e)
      stopSelf()
    }
  }

  private fun removeOverlay() {
    val wm = windowManager ?: return
    val view = overlayView ?: return
    runCatching { wm.removeView(view) }
    overlayView = null
  }

  private fun performGlobalBack() {
    // Use the shared helper so the back action lands on Immortal
    // (not the Portal's stock Aloha home screen).
    BackHelper.performBack(this)
  }

  companion object {
    private const val TAG = "ImmortalBackGestureOverlay"
    private const val NOTIFICATION_ID = 4242
  }
}
