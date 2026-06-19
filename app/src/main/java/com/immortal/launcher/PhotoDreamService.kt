/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.service.dreams.DreamService
import android.util.Log
import android.view.MotionEvent

/**
 * Native photo-frame screensaver. Reproduces the stock Portal idle screen
 * (clock / battery / date / weather over a full-screen photo feed) without
 * touching Meta's APK. Set as the screensaver via
 * `settings put secure screensaver_components <pkg>/.PhotoDreamService`.
 *
 * All UI/logic lives in [PhotoFrameController], shared with
 * [PhotoFramePreviewActivity].
 */
class PhotoDreamService : DreamService() {
  private lateinit var frame: PhotoFrameController

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    Log.i(TAG, "attached: dream starting")
    // Interactive so we receive touch and can exit on tap (verified on device).
    // Fullscreen for an immersive frame — tap-to-exit is the way out.
    isInteractive = true
    isFullscreen = true
    isScreenBright = true
    frame = PhotoFrameController(this)
    frame.onExit = {
      Log.i(TAG, "onExit (tap) -> finish()")
      // Mark this as a USER exit so DreamPolicy doesn't relaunch the frame.
      DreamPolicy.userExitAt = System.currentTimeMillis()
      // If the user chose an app to open on dismiss (e.g. their Home Assistant
      // dashboard), launch it; otherwise this is a no-op and we fall back to the
      // launcher — same as before, and also the path when that app is uninstalled.
      ScreensaverDismiss.launchChosenApp(this)
      finish()
    }
    val root = frame.view
    // Tap dismisses, horizontal swipe changes photo (handled by the controller).
    // Touch events are logged so spurious/phantom touches that end the dream are
    // visible in logcat (they masquerade as a user tap otherwise).
    root.setOnTouchListener { _, ev ->
      Log.i(TAG, "touch action=${ev.actionMasked} x=${ev.x} y=${ev.y}")
      frame.onTouch(ev)
      true
    }
    setContentView(root)
    frame.start()
  }

  override fun onDreamingStarted() {
    super.onDreamingStarted()
    Log.i(TAG, "onDreamingStarted")
    // A screensaver session is running: start (or keep) the idle screen-off countdown.
    SleepScheduler.armIdle(this)
  }

  override fun onDreamingStopped() {
    Log.i(TAG, "onDreamingStopped")
    super.onDreamingStopped()
  }

  override fun onWakeUp() {
    Log.i(TAG, "onWakeUp (system or finish())")
    super.onWakeUp()
  }

  override fun onDetachedFromWindow() {
    Log.i(TAG, "detached: dream ending")
    if (this::frame.isInitialized) frame.stop()
    super.onDetachedFromWindow()
  }

  private companion object {
    const val TAG = "ImmortalDream"
  }
}
