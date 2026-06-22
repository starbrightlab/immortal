/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.util.Log

/**
 * The bridge from the fleet agent's HTTP thread ([RemoteRoutes]) to the accessibility
 * layer, so a phone "remote" can drive the Portal. The Portal is non-root Android 9/10
 * and we hold no `INJECT_EVENTS` (signature) permission, so raw D-pad/key events can't
 * be injected into other apps — but an enabled [AccessibilityService] can fire the
 * system **global actions** (Back / Home / Recents / Notifications / Quick settings),
 * which is the whole Tier-A soft-remote surface and needs no extra permission.
 *
 * [BarWatchService] — already a connected, general-purpose accessibility service on
 * provisioned devices — registers itself here on connect (the same way it hosts
 * [QuickBar]); the routes call [globalAction]. A no-op until a service is registered,
 * so an un-provisioned device with no enabled service simply reports "unavailable".
 *
 * Directional focus nav, gestures (touchpad) and text entry arrive in later phases;
 * this object is the single seam they'll extend.
 */
object RemoteInput {
  private const val TAG = "ImmortalRemote"

  @Volatile private var service: AccessibilityService? = null

  /** Called by [BarWatchService.onServiceConnected]. */
  fun register(svc: AccessibilityService) {
    service = svc
  }

  /** Called by [BarWatchService.onUnbind] when the service goes away. */
  fun unregister() {
    service = null
  }

  /** True when an accessibility service is connected and can perform actions. */
  fun available(): Boolean = service != null

  /**
   * Perform a named global action. Returns true only if a service was connected AND
   * the platform accepted the action. Unknown names return false without touching the
   * service. Safe to call from any thread — [AccessibilityService.performGlobalAction]
   * is documented thread-safe.
   */
  fun globalAction(name: String): Boolean {
    val code = globalActionCode(name) ?: return false
    val svc = service ?: return false
    return runCatching { svc.performGlobalAction(code) }
        .onFailure { Log.w(TAG, "globalAction($name) failed", it) }
        .getOrDefault(false)
  }

  /**
   * Map a remote button name to an [AccessibilityService] `GLOBAL_ACTION_*` constant,
   * or null if unknown. Pure (extracted for unit testing).
   *
   * Only the actions that hit a real surface on the Portal are mapped. Verified on a
   * PortalGo: BACK / HOME / POWER_DIALOG work; RECENTS, NOTIFICATIONS and QUICK_SETTINGS
   * are accepted by the framework (performGlobalAction returns true) but no-op — Meta's
   * Portal SystemUI ships no overview, notification shade, or quick-settings panel. So we
   * don't expose them; "recents" is served instead by launching the in-app app switcher
   * ([AppSwitcherActivity], handled in [RemoteRoutes]).
   */
  internal fun globalActionCode(name: String): Int? =
      when (name.lowercase().trim()) {
        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
        "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        else -> null
      }

  /** The global-action button names [globalActionCode] accepts (for the UI / docs). The
   *  "apps" button is handled separately (in-app switcher), not as a global action. */
  internal val ACTIONS = listOf("back", "home", "power")
}
