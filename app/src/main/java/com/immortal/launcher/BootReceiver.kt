/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Re-asserts our screensaver settings after a reboot. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      SettingsGuard.reaffirmAdb(context)
      SettingsGuard.reaffirmScreensaver(context)
      // Alarms don't survive a reboot: re-arm the overnight window and apply it now.
      SleepScheduler.applyOvernightNow(context)
      // Likewise re-arm the ambient chime alarms.
      ChimeScheduler.reschedule(context)
      // Re-arm the sunrise wake-light alarm.
      SunriseScheduler.reschedule(context)
      // Re-open the WiFi fleet channel after the reboot (the whole point of an
      // in-app agent: it comes back without USB, unlike adb-over-WiFi here).
      FleetAgentService.ensureRunning(context)
      // Reconnect the Home Assistant MQTT publisher (no-op unless configured).
      MqttService.sync(context)
      // (Re)launch apps that can't restart themselves (e.g. the MA/Sendspin player,
      // which has no boot receiver). Wait a few seconds first so WiFi is up for their
      // first connect, then hand the screen back to our home so the Portal doesn't sit
      // on a third-party app (the launched app keeps connecting in the background).
      // Keep the receiver alive across the delays with goAsync().
      if (BootLaunch.packages(context).isNotEmpty()) {
        val pending = goAsync()
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ runCatching { BootLaunch.launchAll(context) } }, BOOT_LAUNCH_DELAY_MS)
        h.postDelayed(
            {
              runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
              pending.finish()
            },
            BOOT_HOME_DELAY_MS)
      }
    }
  }

  private companion object {
    const val BOOT_LAUNCH_DELAY_MS = 5000L
    const val BOOT_HOME_DELAY_MS = 9000L
  }
}
