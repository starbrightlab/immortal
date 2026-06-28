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
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Fires at the sunrise-alarm time: launches the full-screen wake light, then arms the
 *  next occurrence. */
class SunriseReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != SunriseScheduler.ACTION_FIRE) return
    val cfg = SunriseConfig.load(context)
    if (cfg.enabled) showWakeLight(context, cfg)
    // Arm the next day (or next matching weekday).
    SunriseScheduler.reschedule(context)
  }

  /**
   * Bring up the wake light. A direct `startActivity` from a receiver is blocked by the
   * background-activity-launch rules on API 29+, so we use a **full-screen-intent
   * notification** — the sanctioned alarm-clock path — which the system promotes to a
   * full-screen activity even from the background / lock screen. We also try a direct
   * start (harmless on API 28 where it's allowed and is a no-op duplicate otherwise).
   */
  private fun showWakeLight(context: Context, cfg: SunriseConfig.Config) {
    val activity =
        Intent(context, WakeLightActivity::class.java)
            .putExtra(WakeLightActivity.EXTRA_RAMP_MIN, cfg.rampMinutes)
            .putExtra(WakeLightActivity.EXTRA_CHIME, cfg.chime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    val pi =
        PendingIntent.getActivity(
            context, 0, activity,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val nm = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(CHANNEL, "Sunrise alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Wakes the screen for the sunrise alarm"
          }
      nm.createNotificationChannel(channel)
    }
    val notif =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Sunrise alarm")
            .setContentText("Good morning")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .build()
    runCatching { nm.notify(NOTIF_ID, notif) }
    // Best-effort direct launch too (allowed on API 28 / when the launcher is foreground).
    runCatching { context.startActivity(activity) }
  }

  private companion object {
    const val CHANNEL = "immortal_sunrise"
    const val NOTIF_ID = 42101
  }
}
