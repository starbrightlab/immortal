/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Arms the next sunrise-alarm fire via AlarmManager; [SunriseReceiver] launches the
 *  wake light. Re-armed on boot and app start, and rescheduled after each fire. */
object SunriseScheduler {
  const val ACTION_FIRE = "com.immortal.launcher.SUNRISE_FIRE"
  private const val RC = 0x5A1B

  private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)

  private fun pi(c: Context, create: Boolean): PendingIntent? {
    val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
        PendingIntent.FLAG_IMMUTABLE
    val intent = Intent(c, SunriseReceiver::class.java).setAction(ACTION_FIRE)
    return PendingIntent.getBroadcast(c, RC, intent, flags)
  }

  /** Cancel any pending alarm and arm the next one per the saved config. */
  fun reschedule(context: Context) {
    pi(context, create = false)?.let { alarms(context).cancel(it); it.cancel() }
    val cfg = SunriseConfig.load(context)
    val at = SunriseConfig.nextTrigger(cfg) ?: return
    val p = pi(context, create = true) ?: return
    runCatching { alarms(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
        .onFailure { runCatching { alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) } }
  }
}
