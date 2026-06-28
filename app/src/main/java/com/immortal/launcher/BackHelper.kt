package com.immortal.launcher

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Shared helper for system-wide "go back" actions.
 *
 * Behaves like the system back button: one step at a time through the
 * activity stack. The only Immortal-specific behavior is: if the back
 * action would exit the current app to the home screen, we re-route to
 * Immortal's HomeActivity so the user doesn't end up on the Portal's
 * stock Aloha launcher.
 *
 * Used by both the ImmortalBackGestureService (accessibility) and the
 * SystemBackGestureService (overlay).
 */
object BackHelper {

  private const val TAG = "ImmortalBack"
  private val handler = Handler(Looper.getMainLooper())

  /**
   * Perform a global BACK action, then ensure the user lands on the Immortal
   * launcher (not the Portal's stock Aloha home) only if the back action
   * would otherwise exit the current app to the home screen.
   *
   * The check is delayed by ~200ms so the activity stack has time to settle
   * after the back action before we check what the top activity is.
   */
  fun performBack(context: Context) {
    val topBefore = topActivityPackage(context)
    if (requestGlobalBack(context)) {
      handler.postDelayed({ maybeRerouteToImmortal(context, topBefore) }, 200)
    }
  }

  fun performBack(context: Context, dispatchBack: () -> Boolean) {
    val topBefore = topActivityPackage(context)
    if (runCatching { dispatchBack() }.getOrDefault(false)) {
      handler.postDelayed({ maybeRerouteToImmortal(context, topBefore) }, 200)
    }
  }

  /**
   * Force the Immortal launcher to the front. Safe to call multiple times.
   */
  fun ensureImmortalHome(context: Context) {
    val intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setComponent(
                ComponentName(context, "com.immortal.launcher.HomeActivity")
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
    runCatching { context.startActivity(intent) }
  }

  // --- internals ---

  /**
   * After a back action, check whether we landed on the home screen. If so,
   * re-route to Immortal. Otherwise, the back action already did the right
   * thing (e.g. went from one app to another within the task stack) and we
   * leave it alone.
   */
  private fun maybeRerouteToImmortal(context: Context, topBefore: String?) {
    if (topBefore == null) return
    val topAfter = topActivityPackage(context) ?: return
    // If the top didn't change, the back action didn't pop anything — it would
    // have gone to the home screen. Re-route to Immortal.
    if (topAfter != topBefore) return
    // Top didn't change but it's Immortal already — nothing to do.
    if (topAfter == context.packageName) return
    // The back action went to the home screen. Bring Immortal home to front.
    if (isLikelyHomeScreen(context, topAfter)) {
      ensureImmortalHome(context)
    }
  }

  fun isBackServiceEnabled(context: Context): Boolean {
    val am =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
    return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        .any {
          it.resolveInfo.serviceInfo.packageName == context.packageName &&
              it.resolveInfo.serviceInfo.name ==
                  "com.immortal.launcher.ImmortalBackGestureService"
        }
  }

  private fun requestGlobalBack(context: Context): Boolean {
    if (isBackServiceEnabled(context)) {
      val intent = Intent(ImmortalBackGestureService.ACTION_BACK).setPackage(context.packageName)
      context.sendBroadcast(intent)
      Log.i(TAG, "global back sent through Immortal accessibility service")
      return true
    }

    Log.w(TAG, "global back requested but Immortal accessibility service is not enabled")
    return false
  }

  private fun topActivityPackage(context: Context): String? {
    return runCatching {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val tasks = am.appTasks
      if (tasks.isNullOrEmpty()) return@runCatching null
      val top = tasks[0].taskInfo.topActivity
      top?.packageName
    }.getOrNull()
  }

  /**
   * Heuristic: is the given package the home screen? Checks for the
   * "android.intent.category.HOME" intent filter.
   */
  private fun isLikelyHomeScreen(context: Context, pkg: String): Boolean {
    return runCatching {
      val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
      val pm = context.packageManager
      val resolves = pm.queryIntentActivities(homeIntent, 0)
      resolves.any { it.activityInfo.packageName == pkg }
    }.getOrDefault(false)
  }
}
