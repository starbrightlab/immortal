/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Immortal's screen-off model, in one place. The Portal has several ways the screen can go
 * dark; this is the map so they stop fighting each other. Two distinct concerns:
 *
 *  1. **Whether the photo frame *holds* the screen on** — [DreamPolicy.holdScreenOn], keyed on
 *     [FrameMode] (presence vs always-on) and, on the Portal Go, the battery saver. This decides
 *     if the frame pins the display or hands control back to the Portal's own presence policy.
 *     It does not actively turn the screen off; it just stops keeping it on.
 *
 *  2. **Actively turning the screen off** — the two presence-free features below, both via
 *     AlarmManager + [ScreenControl.sleep] (device-admin `lockNow`). A manual Home-Assistant
 *     command ([MqttPublisher]) is the third caller of [ScreenControl.sleep]; it shares the same
 *     primitive but isn't scheduled here.
 *
 * The two scheduled features (both off by default — see [ScreensaverConfig]):
 *  - **Idle timeout**: turn the screen off after the screensaver has run N minutes with no
 *    interaction. Armed when the screensaver starts, cancelled when the user returns to Immortal,
 *    so it measures one continuous idle session.
 *  - **Overnight window**: prefer the screen dark between two times each night. The window-start
 *    alarm turns the screen off and suppresses the auto screensaver for the window. Crucially it
 *    is **not** a re-lock loop: a deliberate wake hands the user the device, and it only returns
 *    to dark via an idle timeout (see [HomeActivity]'s overnight session). It must never trap the
 *    user — it's their device.
 */
object SleepScheduler {
  private const val TAG = "ImmortalSleep"

  const val ACTION_IDLE = "com.immortal.launcher.SLEEP_IDLE"
  const val ACTION_OVERNIGHT_START = "com.immortal.launcher.OVERNIGHT_START"
  const val ACTION_OVERNIGHT_END = "com.immortal.launcher.OVERNIGHT_END"

  private const val RC_IDLE = 1001
  private const val RC_OVERNIGHT_START = 1002
  private const val RC_OVERNIGHT_END = 1003

  private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)

  private fun pi(c: Context, action: String, rc: Int, create: Boolean): PendingIntent? {
    val flags =
        (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(
        c, rc, Intent(c, SleepReceiver::class.java).setAction(action), flags)
  }

  private fun setAlarm(c: Context, atMs: Long, action: String, rc: Int) {
    val p = pi(c, action, rc, create = true) ?: return
    runCatching { alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p) }
        .onFailure {
          // No exact-alarm permission: an inexact alarm is fine for these features.
          runCatching { alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p) }
        }
  }

  private fun cancel(c: Context, action: String, rc: Int) {
    pi(c, action, rc, create = false)?.let { alarms(c).cancel(it); it.cancel() }
  }

  // ----- idle timeout --------------------------------------------------------

  /** Arm the idle alarm when a screensaver session begins (no-op if one is pending). */
  fun armIdle(context: Context) {
    val cfg = ScreensaverConfig.load(context)
    if (!cfg.enabled || !cfg.idleSleepOn) return
    if (pi(context, ACTION_IDLE, RC_IDLE, create = false) != null) return // already counting
    val at = System.currentTimeMillis() + cfg.idleSleepMin * 60_000L
    setAlarm(context, at, ACTION_IDLE, RC_IDLE)
    Log.i(TAG, "idle sleep armed for ${cfg.idleSleepMin} min")
  }

  /** Cancel the idle alarm — the user is interacting again. */
  fun cancelIdle(context: Context) = cancel(context, ACTION_IDLE, RC_IDLE)

  // ----- overnight window ----------------------------------------------------

  fun isOvernightNow(context: Context): Boolean {
    val cfg = ScreensaverConfig.load(context)
    if (!cfg.overnightEnabled) return false
    return inWindow(nowMinuteOfDay(), cfg.overnightStartMin, cfg.overnightEndMin)
  }

  /** Pure (unit-tested): is [now] inside the [start,end) window, handling wrap past midnight? */
  fun inWindow(now: Int, start: Int, end: Int): Boolean =
      if (start == end) false
      else if (start < end) now in start until end
      else now >= start || now < end // wraps midnight

  /** (Re)schedule the daily start/end alarms, or clear them if disabled. */
  fun scheduleOvernight(context: Context) {
    val cfg = ScreensaverConfig.load(context)
    if (!cfg.overnightEnabled) {
      cancel(context, ACTION_OVERNIGHT_START, RC_OVERNIGHT_START)
      cancel(context, ACTION_OVERNIGHT_END, RC_OVERNIGHT_END)
      return
    }
    setAlarm(context, nextOccurrence(cfg.overnightStartMin), ACTION_OVERNIGHT_START, RC_OVERNIGHT_START)
    setAlarm(context, nextOccurrence(cfg.overnightEndMin), ACTION_OVERNIGHT_END, RC_OVERNIGHT_END)
    Log.i(TAG, "overnight scheduled ${cfg.overnightStartMin}→${cfg.overnightEndMin}")
  }

  /** Apply the right state immediately (on boot, app start, or a settings change). */
  fun applyOvernightNow(context: Context) {
    scheduleOvernight(context)
    if (isOvernightNow(context)) {
      SettingsGuard.reaffirmScreensaver(context) // forces screensaver off inside the window
      ScreenControl.sleep(context)
    }
  }

  private fun nowMinuteOfDay(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
  }

  private fun nextOccurrence(minuteOfDay: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
    c.set(Calendar.MINUTE, minuteOfDay % 60)
    if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
  }
}
