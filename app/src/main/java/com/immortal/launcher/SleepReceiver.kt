/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Routes the idle and overnight alarms to [SleepScheduler], which owns the policy. This class only
 * maps an alarm action to the corresponding event — it makes no screen-state decisions itself.
 */
class SleepReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      SleepScheduler.ACTION_IDLE -> SleepScheduler.onIdleElapsed(context)
      SleepScheduler.ACTION_OVERNIGHT_START -> SleepScheduler.onWindowStart(context)
      SleepScheduler.ACTION_OVERNIGHT_END -> SleepScheduler.onWindowEnd(context)
    }
  }
}
