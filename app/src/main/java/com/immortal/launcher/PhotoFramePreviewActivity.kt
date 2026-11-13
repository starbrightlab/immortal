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
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

  // Read from the *latest* intent (onCreate or onNewIntent), never captured in a closure:
  // this activity is singleTask, so a relaunch while the frame is already up reuses the live
  // instance and skips onCreate entirely (issue #146 regression).
  private var launchDismissOnExit = false

  // Overnight "night clock" mode: this activity is the dimmed bedside flip clock for the window.
  private var nightClock = false
  private val nightWatch = Handler(Looper.getMainLooper())
  // While showing the night clock, bow out the moment the window ends (within a minute), restoring
  // normal brightness and handing back to the launcher.
  private val nightWatchTick =
      object : Runnable {
        override fun run() {
          if (!SleepScheduler.isOvernightNow(this@PhotoFramePreviewActivity)) {
            finish()
            return
          }
          nightWatch.postDelayed(this, 60_000L)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Immersive fullscreen.
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    // Overnight behaviour: dark mode → don't show photos, just blank. Night-clock mode → fall
    // through and render the dimmed flip clock instead.
    nightClock = SleepScheduler.isOvernightNow(this) && ScreensaverConfig.load(this).overnightNightClock
    if (SleepScheduler.isOvernightNow(this) && !nightClock) {
      ScreenControl.sleep(this)
      finish()
      return
    }

    // Render the frame *over* a secure keyguard so a presence/motion wake lands on the slideshow
    // (or the night clock), the stock pre-Immortal behaviour, instead of the PIN pad (issue #158).
    // Placed after the overnight-dark early-return above so it never fights the night blanking.
    showOverKeyguard()

    frame = PhotoFrameController(this, showWelcome = intent.getBooleanExtra(EXTRA_SHOW_WELCOME, false))
    // Only the screensaver-continuation launch (DreamPolicy's force-wake handoff) honours the
    // "open when dismissed" choice — to the user that frame IS the screensaver, so a tap must
    // behave like PhotoDreamService's tap (issue #146). Deliberate on-demand starts (settings
    // preview, face picker, header button, MQTT command) keep returning to where they came from.
    // The night clock never launches anything — a 3am tap should just hand back, dark.
    launchDismissOnExit = intent.getBooleanExtra(EXTRA_LAUNCH_DISMISS_APP, false) && !nightClock
    frame.onExit = {
      Log.i(TAG, "onExit (tap) -> finish(), launchDismiss=$launchDismissOnExit")
      // The user tapped the frame: they're unambiguously here. Without this the continuation
      // frame leaves PresenceHub stuck on DREAMING (finishing an Activity fires no dream-stop),
      // so MQTT screen/state reported "dreaming" forever after the ~2-min handoff (issue #103).
      PresenceHub.onInteraction(this)
      if (launchDismissOnExit) ScreensaverDismiss.launchChosenApp(this)
      finish()
    }

    if (nightClock) {
      // Bedside clock: force the screen on for the window and dim it to a soft glow. Brightness is
      // window-scoped, so it restores automatically when this activity finishes (tap, or window end).
      frame.faceOverride = Face.flipNight(this)
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      window.attributes = window.attributes.apply { screenBrightness = NIGHT_BRIGHTNESS }
    } else {
      applyKeepScreenOn()
      // Debug preview harness: render an arbitrary face (mantelframe WidgetConfig shape) instead
      // of the classic one, for fast on-device iteration. The face JSON is passed base64-encoded
      // ("face_b64") to survive adb's shell quoting; plain "face_json" is also accepted. Only the
      // debug build exports this activity (src/debug/AndroidManifest.xml), so it's a no-op in
      // release where the extra can't be supplied.
      val faceJson =
          intent?.getStringExtra("face_b64")?.let {
            runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }
                .getOrNull()
          } ?: intent?.getStringExtra("face_json")
      faceJson?.let { json ->
        runCatching { frame.faceOverride = Face.fromJson("preview", org.json.JSONObject(json)) }
            .onFailure { android.util.Log.w("FacePreview", "face parse failed", it) }
      }
    }

    setContentView(frame.view)
    frame.start()

    if (nightClock) {
      // The clock stays up all window (no idle screen-off); just watch for the window to end.
      nightWatch.post(nightWatchTick)
    } else {
      // A screensaver session is running: start (or keep) the idle screen-off countdown.
      SleepScheduler.onScreensaverStarted(this)
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
  }

  // singleTask relaunch onto a live frame (most commonly DreamPolicy's force-wake continuation
  // when this instance survived under the dream) lands here, not onCreate. Re-derive the
  // dismiss behaviour from the fresh intent — last launch wins, matching what onCreate would
  // have decided. Rebuild the frame if the overnight clock mode has changed.
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val nextNightClock =
        SleepScheduler.isOvernightNow(this) && ScreensaverConfig.load(this).overnightNightClock
    launchDismissOnExit = intent.getBooleanExtra(EXTRA_LAUNCH_DISMISS_APP, false) && !nextNightClock
    if (nextNightClock != nightClock) {
      // The overnight alarm can reuse a daytime frame. Let onCreate rebuild the face,
      // window brightness and timer callbacks for the new mode.
      recreate()
      return
    }
    Log.i(TAG, "onNewIntent (reused instance): launchDismiss=$launchDismissOnExit")
  }

  /**
   * Show the frame over a secure keyguard and turn the panel on for it, so a presence/motion wake
   * surfaces the slideshow rather than the lock screen (issue #158). Mirrors [WakeLightActivity]'s
   * show-over-lock flags but deliberately OMITS FLAG_DISMISS_KEYGUARD: the keyguard stays armed
   * underneath, so a Portal in a public space still requires the PIN to actually *use* the device —
   * finishing this frame on a tap falls back to the keyguard-protected launcher, which prompts the
   * PIN on interaction. No-op on devices without a secure lock set.
   */
  private fun showOverKeyguard() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      window.addFlags(
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
              WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
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
    nightWatch.removeCallbacks(nightWatchTick)
    if (this::frame.isInitialized) frame.stop()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "ImmortalFrame"

    /** When true, the launched frame shows the welcome-back overlay (presence-triggered starts). */
    const val EXTRA_SHOW_WELCOME = "show_welcome"

    /**
     * When true, a tap-to-dismiss honours the "open when dismissed" target ([ScreensaverDismiss]).
     * Set only by [DreamPolicy]'s screensaver-continuation relaunch — on-demand previews keep the
     * plain finish-back-to-caller behaviour.
     */
    const val EXTRA_LAUNCH_DISMISS_APP = "launch_dismiss_app"

    // A soft glow for the overnight bedside clock — dim but still legible in a dark room.
    const val NIGHT_BRIGHTNESS = 0.08f
  }
}
