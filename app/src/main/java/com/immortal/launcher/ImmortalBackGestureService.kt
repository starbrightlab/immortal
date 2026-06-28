package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * System-wide "Go back" accessibility action.
 *
 * When the user enables this service in Android Settings → Accessibility, an
 * Immortal tile appears in the accessibility navigation bar. Tapping it
 * performs GLOBAL_ACTION_BACK — same effect as the system back button —
 * working in any app.
 *
 * Also listens for the IMMORTAL_BACK_ACTION broadcast so the launcher (or
 * any other component) can trigger a back action programmatically:
 *
 *   adb shell am broadcast -a com.immortal.launcher.BACK
 *
 * The broadcast is useful when the accessibility service is enabled but
 * the user wants to trigger back from a script, a Tile service, or another
 * app.
 */
class ImmortalBackGestureService : AccessibilityService() {

  private var receiver: BroadcastReceiver? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    Log.i(TAG, "back-gesture accessibility service connected")
    // Register a broadcast receiver so external triggers (e.g. adb, a
    // Quick Settings tile) can fire a back action through this service.
    receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == ACTION_BACK) {
              // Use the shared helper so the back action lands on Immortal
              // (not the Portal's stock Aloha home screen).
              BackHelper.performBack(this@ImmortalBackGestureService) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
              }
            }
          }
        }
    val filter = IntentFilter(ACTION_BACK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("UnspecifiedRegisterReceiverFlag")
      registerReceiver(receiver, filter)
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // No-op: this service doesn't consume events, it just exposes the
    // global BACK action.
  }

  override fun onInterrupt() {
    Log.w(TAG, "back-gesture service interrupted")
  }

  override fun onDestroy() {
    receiver?.let { runCatching { unregisterReceiver(it) } }
    receiver = null
    super.onDestroy()
  }

  /**
   * Public entry point for triggering a back action. Returns true if the
   * action was dispatched successfully.
   */
  fun performBack(): Boolean {
    var dispatched = false
    BackHelper.performBack(this) {
      dispatched = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      dispatched
    }
    return dispatched
  }

  companion object {
    const val TAG = "ImmortalBackGesture"
    const val ACTION_BACK = "com.immortal.launcher.BACK"
  }
}
