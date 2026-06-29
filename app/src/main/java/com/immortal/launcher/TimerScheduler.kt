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

/** Arms an exact AlarmManager alarm for each kitchen timer; [TimerReceiver] rings
 *  when one fires. Mirrors [ChimeScheduler]'s exact-and-allow-while-idle pattern. */
object TimerScheduler {
  const val ACTION_FIRE = "com.immortal.launcher.TIMER_FIRE"
  const val EXTRA_ID = "timer_id"
  const val EXTRA_LABEL = "timer_label"

  private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)

  // A stable, positive request code per timer id so cancel() targets the same alarm.
  private fun rc(id: String): Int = (id.hashCode() and 0x7FFFFFFF) or 1

  private fun pi(c: Context, t: TimerConfig.Timer, create: Boolean): PendingIntent? {
    val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
        PendingIntent.FLAG_IMMUTABLE
    val intent = Intent(c, TimerReceiver::class.java)
        .setAction(ACTION_FIRE)
        .putExtra(EXTRA_ID, t.id)
        .putExtra(EXTRA_LABEL, t.label)
    return PendingIntent.getBroadcast(c, rc(t.id), intent, flags)
  }

  fun schedule(context: Context, t: TimerConfig.Timer) {
    val p = pi(context, t, create = true) ?: return
    runCatching { alarms(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAtMillis, p) }
        .onFailure {
          runCatching { alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAtMillis, p) }
        }
  }

  fun cancel(context: Context, t: TimerConfig.Timer) {
    pi(context, t, create = false)?.let { alarms(context).cancel(it); it.cancel() }
  }
}
