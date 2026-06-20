/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log

/**
 * Cooperates with the Portal build's presence-driven power policy.
 *
 * How this build behaves (measured on device):
 *  - At the screen timeout, Meta's PowerManager decides AMBIENT vs SLEEP from the
 *    presence service: someone around → start the dream; empty room → real sleep.
 *    (`screensaver_activate_on_sleep` is ignored.)
 *  - A running dream is force-woken at `last-activity + min(screen_off + 120s,
 *    sleep_timeout)` — dreams are transient by design; stock SuperFrame caught
 *    that wake, so we must too.
 *
 * Immortal's policy:
 *  - When the system bounces the dream (not a user tap, not a power-button
 *    sleep), instantly relaunch the same frame as [PhotoFramePreviewActivity].
 *  - The frame HOLDS the screen on mains-powered Portals (and on the Go while
 *    charging, or with the battery saver off) → permanent photo frame.
 *  - On the Go on battery with the saver on, the frame does NOT hold the screen:
 *    each screen timeout becomes a fresh presence decision — someone around →
 *    the dream takes over again (visually identical, so it reads as one
 *    continuous frame); empty room → the device truly sleeps.
 */
object DreamPolicy {
  private const val TAG = "ImmortalDream"

  /** Set by [PhotoDreamService] just before finish() on a user tap. */
  @Volatile var userExitAt: Long = 0L

  /**
   * Set by [HomeActivity] when it deliberately bridges to the stock launcher (the
   * Calls tile). The stock launcher cold-starts into its own idle "dream" face and
   * immediately stops it, firing ACTION_DREAMING_STOPPED — which would otherwise
   * make us relaunch our photo frame over the top of the stock home and trap the
   * user there (the reported "Calls kicks me back into Immortal"). While a bridge
   * is in flight we suppress that relaunch.
   */
  @Volatile var bridgeAt: Long = 0L

  /**
   * True from the moment the Calls tile bridges to the stock launcher until the
   * user comes back to Immortal (cleared in [HomeActivity.onResume]). While set, we
   * never relaunch the holding photo-frame Activity, so it can't slam over the stock
   * home while the user is in a call flow. The system Dream still shows photos on
   * idle as usual — we only skip the aggressive "permanent frame" relaunch.
   */
  @Volatile var inStockHandoff: Boolean = false

  fun hasBattery(context: Context): Boolean =
      runCatching {
            context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
          }
          .getOrDefault(false)

  fun isPowered(context: Context): Boolean =
      runCatching {
            (context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
          }
          .getOrDefault(true)

  /**
   * Pure decision (unit-tested): should the frame hold the screen on?
   *
   *  - [FrameMode.PRESENCE] never holds: we hand control back to the Portal's presence policy
   *    at every screen timeout, so it sleeps the screen when the room empties and re-dreams
   *    when someone returns. This is the shared screensaver/music baseline.
   *  - [FrameMode.ALWAYS_ON] holds on mains / while charging / with the saver off → a permanent
   *    frame, at the cost of masking the presence proxy (presence then reads UNKNOWN; the music
   *    defers to Home Assistant — see [PresenceHub] and docs/design/multi-room-audio.md → *Presence*).
   *
   * Note on what we CAN'T do: we can't read Meta's presence signal directly — it's front-camera
   * CV behind a platform-signature permission (proven by the SuperFrame APK teardown). But the
   * dream/sleep lifecycle is *derived* from it and is observable, which is exactly what
   * PRESENCE mode rides. [SleepScheduler] still offers a presence-free idle timeout and an
   * overnight window via device-admin lockNow() on top of either mode.
   */
  internal fun holdScreenOn(
      mode: FrameMode,
      hasBattery: Boolean,
      batterySaver: Boolean,
      powered: Boolean,
  ): Boolean =
      when (mode) {
        FrameMode.PRESENCE -> false
        FrameMode.ALWAYS_ON -> !hasBattery || !batterySaver || powered
      }

  /**
   * Called on ACTION_DREAMING_STOPPED. Classifies the stop once ([classifyDreamStop]), records
   * the presence consequence in the shared [PresenceHub] (which the music companion reads), and
   * — as the screensaver's own reaction — re-asserts the holding frame only on a force-wake.
   */
  fun onDreamingStopped(context: Context) {
    if (!ScreensaverConfig.load(context).enabled) {
      Log.i(TAG, "screensaver disabled; not relaunching frame")
      return
    }
    val now = System.currentTimeMillis()
    val pm = context.getSystemService(PowerManager::class.java)
    val verdict =
        classifyDreamStop(
            userExitAgoMs = now - userExitAt,
            bridgeAgoMs = now - bridgeAt,
            inStockHandoff = inStockHandoff,
            interactive = pm?.isInteractive == true,
        )
    Log.i(TAG, "dream stopped; verdict = $verdict")
    // Single source of truth: tell the hub the presence consequence (it broadcasts to the
    // companion). SUPPRESSED (a Calls handoff) leaves presence untouched.
    PresenceHub.onDreamStopped(context, verdict)
    if (verdict != DreamStopVerdict.REDREAM) return
    runCatching {
      context.startActivity(
          Intent(context, PhotoFramePreviewActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
        .onFailure { Log.w(TAG, "frame relaunch failed", it) }
  }
}
