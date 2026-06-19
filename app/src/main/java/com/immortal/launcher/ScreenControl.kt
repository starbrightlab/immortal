/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Turns the Portal's screen off. A normal app can't call PowerManager.goToSleep
 * (signature permission), so we use device-admin lockNow(), which the Portal has no
 * keyguard for — it simply blanks the screen and a tap wakes it.
 *
 * If the admin isn't active (provisioning didn't run `dpm set-active-admin`), every
 * call here is a logged no-op, so the app still behaves correctly — it just can't
 * force the screen off.
 */
object ScreenControl {
  private const val TAG = "ImmortalSleep"

  fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

  fun dpm(context: Context): DevicePolicyManager =
      context.getSystemService(DevicePolicyManager::class.java)

  fun isAdminActive(context: Context): Boolean =
      runCatching { dpm(context).isAdminActive(admin(context)) }.getOrDefault(false)

  /**
   * Deactivate our own device admin. An app can always remove its OWN active admin
   * (no privileged force-remove needed — unlike `dpm remove-active-admin` from the
   * shell, which Android blocks for a non-test admin). This is what lets Immortal be
   * cleanly uninstalled (`adb uninstall` is otherwise refused while the admin is
   * active). Turns off the idle / overnight screen-off features until re-activated.
   */
  fun deactivateAdmin(context: Context) {
    runCatching { dpm(context).removeActiveAdmin(admin(context)) }
        .onSuccess { Log.i(TAG, "device admin deactivated") }
        .onFailure { Log.w(TAG, "couldn't deactivate admin", it) }
  }

  /**
   * Turn the screen on. A normal app can't call PowerManager.wakeUp (signature
   * permission), but a wake lock acquired with ACQUIRE_CAUSES_WAKEUP powers the display
   * on — this is what lets Home Assistant wake the panel (e.g. on a doorbell). Held
   * briefly then auto-released; the screensaver / idle policy keeps it on afterwards.
   * Needs only the normal WAKE_LOCK permission (no provisioning), unlike screen-off.
   */
  fun wake(context: Context) {
    runCatching {
          val pm = context.getSystemService(PowerManager::class.java)
          @Suppress("DEPRECATION") // FULL_WAKE_LOCK is the working wake path on API 28/29 Portals
          val wl =
              pm.newWakeLock(
                  PowerManager.FULL_WAKE_LOCK or
                      PowerManager.ACQUIRE_CAUSES_WAKEUP or
                      PowerManager.ON_AFTER_RELEASE,
                  "immortal:wake")
          wl.acquire(3_000L) // turns the display on; auto-releases after 3s
        }
        .onSuccess { Log.i(TAG, "wake(): screen on") }
        .onFailure { Log.w(TAG, "wake() failed", it) }
  }

  /** Blank the screen now, if we hold the device-admin force-lock policy. */
  fun sleep(context: Context) {
    if (!isAdminActive(context)) {
      Log.w(TAG, "sleep requested but device admin not active — no-op")
      return
    }
    runCatching { dpm(context).lockNow() }
        .onSuccess { Log.i(TAG, "lockNow(): screen off") }
        .onFailure { Log.w(TAG, "lockNow() failed", it) }
  }
}
