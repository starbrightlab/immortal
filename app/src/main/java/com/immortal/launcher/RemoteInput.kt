/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

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

  /**
   * Fire the system Back action [times] times, [delayMs] apart, off the main thread. Backs the
   * Calls→stock-home bridge: a short burst of system Back presses is the reliable way back to
   * Meta's stock launcher on the Portal (where deep-link / HOME launches are flaky). No-op if no
   * accessibility service is connected.
   */
  fun backRepeat(times: Int, delayMs: Long) {
    if (service == null) return
    thread(name = "immortal-back") {
      repeat(times) { i ->
        globalAction("back")
        if (i < times - 1) runCatching { Thread.sleep(delayMs) }
      }
    }
  }

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

  // --- touchpad (gesture dispatch + on-TV cursor) -----------------------------
  // The reboot-proof, no-INJECT_EVENTS way to navigate any app: synthesize touches via
  // the accessibility service (needs canPerformGestures) and draw the pointer with
  // [RemoteCursor]. The cursor is tracked in screen pixels; the phone sends relative deltas.

  @Volatile private var curX = -1f
  @Volatile private var curY = -1f

  /** True if the connected service was granted gesture dispatch (canPerformGestures). */
  fun gesturesAvailable(): Boolean {
    val svc = service ?: return false
    val caps = svc.serviceInfo?.capabilities ?: return false
    return caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
  }

  /** Move the on-TV pointer by a relative delta (px), clamped to the screen. */
  fun cursorMove(dx: Float, dy: Float): Boolean {
    val svc = service ?: return false
    val dm = svc.resources.displayMetrics
    initCursor(dm.widthPixels, dm.heightPixels)
    curX = (curX + dx).coerceIn(0f, dm.widthPixels - 1f)
    curY = (curY + dy).coerceIn(0f, dm.heightPixels - 1f)
    RemoteCursor.showAt(curX.toInt(), curY.toInt())
    return true
  }

  /** Tap at the current pointer position (a synthesized touch). */
  fun tap(): Boolean {
    val svc = service ?: return false
    val dm = svc.resources.displayMetrics
    initCursor(dm.widthPixels, dm.heightPixels)
    RemoteCursor.showAt(curX.toInt(), curY.toInt())
    val path = Path().apply { moveTo(curX, curY) }
    return dispatch(svc, path, durationMs = 50)
  }

  /** Swipe/scroll from the current pointer by a relative delta (px) — content scrolls. */
  fun swipe(dx: Float, dy: Float): Boolean {
    val svc = service ?: return false
    val dm = svc.resources.displayMetrics
    initCursor(dm.widthPixels, dm.heightPixels)
    val x2 = (curX + dx).coerceIn(0f, dm.widthPixels - 1f)
    val y2 = (curY + dy).coerceIn(0f, dm.heightPixels - 1f)
    val path = Path().apply { moveTo(curX, curY); lineTo(x2, y2) }
    return dispatch(svc, path, durationMs = 120)
  }

  /**
   * Page-scroll the foreground content with ONE long vertical swipe down the screen centre —
   * the reliable scroll on the Portal. [down] = true reveals content below (finger swipes up);
   * false reveals above. (A stream of tiny per-frame swipes gets cancelled and ignored, which is
   * why the remote uses discrete scroll buttons instead of a two-finger drag.)
   */
  fun scrollPage(down: Boolean): Boolean {
    val svc = service ?: return false
    val dm = svc.resources.displayMetrics
    val cx = dm.widthPixels / 2f
    val y1 = dm.heightPixels * (if (down) 0.72f else 0.28f)
    val y2 = dm.heightPixels * (if (down) 0.28f else 0.72f)
    val path = Path().apply { moveTo(cx, y1); lineTo(cx, y2) }
    return dispatch(svc, path, durationMs = 250)
  }

  /** Centre the pointer the first time we're used after a (re)connect. */
  private fun initCursor(w: Int, h: Int) {
    if (curX < 0f) {
      curX = w / 2f
      curY = h / 2f
    }
  }

  private fun dispatch(svc: AccessibilityService, path: Path, durationMs: Long): Boolean {
    val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    return runCatching { svc.dispatchGesture(gesture, null, null) }
        .onFailure { Log.w(TAG, "dispatchGesture failed", it) }
        .getOrDefault(false)
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
