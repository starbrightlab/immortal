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
import android.content.IntentFilter
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen photo frame as a normal activity. Two jobs:
 *  - on-demand preview (Screensaver tile), and
 *  - the continuation frame: when the system force-wakes the real screensaver
 *    (see [DreamPolicy]), this activity takes over seamlessly.
 *
 * Keep-screen-on policy ([DreamPolicy.holdScreenOn], keyed on [FrameMode]): in PRESENCE mode
 * the flag is never held — at each screen timeout the Portal's presence policy decides (someone
 * around → the dream, same visuals, takes over again; empty room → the device truly sleeps),
 * which is the baseline the music companion follows too. In ALWAYS_ON mode the flag is held on
 * mains / while charging / with the saver off → a permanent frame. Plug/unplug re-evaluates it.
 */
class PhotoFramePreviewActivity : ComponentActivity() {
  private lateinit var frame: PhotoFrameController
  private var powerReceiver: BroadcastReceiver? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Immersive fullscreen.
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    // Inside the overnight window the screen should stay off — don't show photos.
    if (SleepScheduler.isOvernightNow(this)) {
      ScreenControl.sleep(this)
      finish()
      return
    }
    applyKeepScreenOn()
    frame = PhotoFrameController(this)
    frame.onExit = { finish() }
    setContentView(frame.view)
    frame.start()
    // A screensaver session is running: start (or keep) the idle screen-off countdown.
    SleepScheduler.armIdle(this)

    if (DreamPolicy.hasBattery(this)) {
      powerReceiver =
          object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
              applyKeepScreenOn()
            }
          }
      registerReceiver(
          powerReceiver,
          IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
          })
    }
  }

  private fun applyKeepScreenOn() {
    val cfg = ScreensaverConfig.load(this)
    val keep =
        DreamPolicy.holdScreenOn(
            mode = cfg.presenceMode,
            hasBattery = DreamPolicy.hasBattery(this),
            batterySaver = cfg.batterySaver,
            powered = DreamPolicy.isPowered(this),
        )
    if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  // Feed all touches to the controller's gesture detector (tap = exit,
  // horizontal swipe = prev/next) at the window level for reliability.
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    frame.onTouch(ev)
    return true
  }

  override fun onDestroy() {
    powerReceiver?.let { runCatching { unregisterReceiver(it) } }
    if (this::frame.isInitialized) frame.stop()
    super.onDestroy()
  }
}
