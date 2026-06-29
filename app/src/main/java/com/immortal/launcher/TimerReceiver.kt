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
import android.util.Log

/** Fires when a kitchen timer elapses: rings the chime a few times, announces the
 *  timer's label, and forgets it. A deliberately-set timer rings even during the
 *  chime's quiet hours. */
class TimerReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != TimerScheduler.ACTION_FIRE) return
    val id = intent.getStringExtra(TimerScheduler.EXTRA_ID)
    val label = intent.getStringExtra(TimerScheduler.EXTRA_LABEL)?.ifBlank { "Timer" } ?: "Timer"
    Log.i(TAG, "timer fired: $label ($id)")
    ChimePlayer.playTimerRing(context)
    Handler(Looper.getMainLooper())
        .postDelayed({ ChimePlayer.announce(context, "$label timer done") }, 3 * 1400L)
    id?.let { TimerConfig.forget(context, it) }
  }

  private companion object {
    const val TAG = "ImmortalTimer"
  }
}
