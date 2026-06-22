/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The bridge from the fleet agent's HTTP thread ([RemoteRoutes]) to the accessibility
 * layer, so a phone "remote" can drive the Portal. The Portal is non-root Android 9/10
 * and we hold no `INJECT_EVENTS` (signature) permission, so raw D-pad/key events can't
 * be injected into other apps. Instead an enabled [AccessibilityService] gives us three
 * working surfaces with no extra permission:
 *  - **global actions** ([globalAction]) — Back / Home / Power.
 *  - **text entry** ([typeText]) — set the focused editable field's text directly.
 *
 * [BarWatchService] — a connected, general-purpose accessibility service (the remote
 * self-enables it, see [SettingsGuard.reconcileBarWatch]) — registers itself here on
 * connect (the same way it hosts [QuickBar]); the routes call into us. A no-op until a
 * service is registered, so a device with no enabled service reports "unavailable".
 *
 * Directional/pointer navigation is deliberately NOT done here: D-pad focus movement via
 * accessibility (`focusSearch` + `ACTION_FOCUS`) was tried and proved non-functional on the
 * Portal's Compose/custom UIs (no input-focus node to search from). It's delivered instead
 * by the gesture touchpad in a later phase — the right fit for a touchscreen. This object is
 * the single seam those gestures will extend.
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

  // --- text entry -------------------------------------------------------------

  /**
   * Edit the currently focused editable field on the device. [mode] is "set" (replace
   * with [text]), "append", "backspace" (drop last char), or "clear". Returns true only
   * if a service is connected, an editable field has input focus, and the platform
   * accepted the edit. No IME swap needed — we set the node's text directly.
   */
  fun typeText(text: String, mode: String): Boolean {
    val svc = service ?: return false
    val node = focusedEditable(svc) ?: return false
    val next = nextText(node.text?.toString() ?: "", text, mode)
    val args =
        Bundle().apply {
          putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
    return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
        .onFailure { Log.w(TAG, "typeText failed", it) }
        .getOrDefault(false)
  }

  /** The input-focused editable node, or null if nothing editable has focus. */
  private fun focusedEditable(svc: AccessibilityService): AccessibilityNodeInfo? {
    val root = svc.rootInActiveWindow ?: return null
    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    return if (focused != null && focused.isEditable) focused else null
  }

  // --- pure helpers (unit-tested) ---------------------------------------------

  /** Compute the new field text for an edit [mode]. Pure. */
  internal fun nextText(current: String, text: String, mode: String): String =
      when (mode.lowercase().trim()) {
        "append" -> current + text
        "backspace" -> if (current.isNotEmpty()) current.dropLast(1) else ""
        "clear" -> ""
        else -> text // "set"
      }
}
