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
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.util.Calendar

/**
 * Immortal's screen-off model, in one place. The Portal has several ways the screen can go
 * dark; this is the map so they stop fighting each other. Two distinct concerns:
 *
 *  1. **Whether the photo frame *holds* the screen on** — [DreamPolicy.holdScreenOn], keyed on
 *     [FrameMode] (presence vs always-on) and, on the Portal Go, the battery saver. This decides
 *     if the frame pins the display or hands control back to the Portal's own presence policy.
 *     It does not actively turn the screen off; it just stops keeping it on.
 *
 *  2. **Actively turning the screen off** — the two presence-free features below, both via
 *     AlarmManager + [ScreenControl.sleep] (device-admin `lockNow`). A manual Home-Assistant
 *     command ([MqttPublisher]) is the third caller of [ScreenControl.sleep]; it shares the same
 *     primitive but isn't scheduled here.
 *
 * The two scheduled features (both off by default — see [ScreensaverConfig]):
 *  - **Idle timeout**: turn the screen off after the screensaver has run N minutes with no
 *    interaction. Armed when the screensaver starts, cancelled when the user returns to Immortal,
 *    so it measures one continuous idle session.
 *  - **Overnight window**: between two times each night the window either goes dark (screen off)
 *    or shows a dimmed flip **night clock** ([ScreensaverConfig.overnightNightClock]). The
 *    window-start alarm enters that rest state and suppresses the auto screensaver for the window.
 *    Crucially it is **not** a re-lock loop: a deliberate wake hands the user the device for a
 *    full, touch-renewed session, and it only returns to the rest state once they're genuinely
 *    idle. It must never trap the user — it's their device.
 *
 * This object is the single owner of all of the above. Activities and the alarm receiver don't
 * implement policy; they just report events ([onScreensaverStarted], [onReturnedToLauncher],
 * [onInteraction], [onLeftLauncher], [onWindowStart], [onWindowEnd], [onIdleElapsed]) and this
 * decides what the screen does. The night-session timer that used to live in [HomeActivity] is
 * owned here too.
 */
object SleepScheduler {
  private const val TAG = "ImmortalSleep"

  // How long a deliberate overnight wake keeps the screen on (renewed on each touch). Matches the
  // user's daytime idle timeout when set, else a generous default — so a 3am pickup behaves like
  // any idle device instead of snapping back to the rest state.
  private const val OVERNIGHT_SESSION_DEFAULT_MS = 5L * 60_000

  // How recently a real user wake (USER_PRESENT / a touch) must have happened for a redream landing
  // inside the dark window to be read as "the user woke the device" rather than a stray dream cycle
  // (issue #138). Covers the brief race where the dream-stop broadcast beats USER_PRESENT.
  private const val USER_WAKE_GRACE_MS = 4000L

  // The renewable overnight "you have the device" session, owned here (was HomeActivity's Handler).
  // Lazy so loading this object (e.g. for the pure inWindow tests) doesn't touch the main Looper.
  private val main by lazy { Handler(Looper.getMainLooper()) }
  @Volatile private var nightSessionActive = false
  @Volatile private var nightSessionCtx: Context? = null
  // When the user last unambiguously woke/touched the device (USER_PRESENT or a launcher touch).
  @Volatile private var lastUserWakeAt = 0L
  private val nightSessionElapsed = Runnable {
    nightSessionActive = false
    nightSessionCtx?.let { if (isOvernightNow(it)) enterOvernightRest(it) }
  }

  const val ACTION_IDLE = "com.immortal.launcher.SLEEP_IDLE"
  const val ACTION_SLEEP_TIMER = "com.immortal.launcher.SLEEP_TIMER"
  const val ACTION_OVERNIGHT_START = "com.immortal.launcher.OVERNIGHT_START"
  const val ACTION_OVERNIGHT_END = "com.immortal.launcher.OVERNIGHT_END"

  private const val RC_IDLE = 1001
  private const val RC_SLEEP_TIMER = 1004
  private const val RC_OVERNIGHT_START = 1002
  private const val RC_OVERNIGHT_END = 1003

  private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)

  private fun pi(c: Context, action: String, rc: Int, create: Boolean): PendingIntent? {
    val flags =
        (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getBroadcast(
        c, rc, Intent(c, SleepReceiver::class.java).setAction(action), flags)
  }

  private fun setAlarm(c: Context, atMs: Long, action: String, rc: Int) {
    val p = pi(c, action, rc, create = true) ?: return
    runCatching { alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p) }
        .onFailure {
          // No exact-alarm permission: an inexact alarm is fine for these features.
          runCatching { alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, p) }
        }
  }

  private fun cancel(c: Context, action: String, rc: Int) {
    pi(c, action, rc, create = false)?.let { alarms(c).cancel(it); it.cancel() }
  }

  // ----- events from the app -------------------------------------------------

  /** A screensaver session began: start the daytime idle screen-off countdown if enabled. */
  fun onScreensaverStarted(context: Context) {
    val cfg = ScreensaverConfig.load(context)
    if (!cfg.enabled || !cfg.idleSleepOn) return
    if (pi(context, ACTION_IDLE, RC_IDLE, create = false) != null) return // already counting
    val at = System.currentTimeMillis() + cfg.idleSleepMin * 60_000L
    setAlarm(context, at, ACTION_IDLE, RC_IDLE)
    Log.i(TAG, "idle sleep armed for ${cfg.idleSleepMin} min")
  }

  /**
   * The user is back on Immortal's launcher. The idle screen-off session is over; and inside the
   * overnight window, give them the device — arm a full, touch-renewed session after which the
   * screen returns to its overnight rest state (dark or the night clock). Never an instant re-lock.
   */
  fun onReturnedToLauncher(context: Context) {
    cancelIdle(context)
    if (isOvernightNow(context)) {
      startNightSession(context)
    } else {
      nightSessionActive = false
      main.removeCallbacks(nightSessionElapsed)
    }
  }

  /**
   * A real user wake or touch (USER_PRESENT, or a launcher touch). Renews the overnight session so
   * interaction keeps the screen up — and, crucially, *starts* one if we're inside the dark
   * overnight window with none active yet. Without that start, a deliberate 3am tap wakes the
   * screen and a racing redream immediately reblanks it (issue #138), because nothing had handed
   * the user the device. The timestamp also lets [handleRedreamDuringOvernight] recognise a wake
   * whose USER_PRESENT lands just after the dream-stop.
   */
  fun onInteraction(context: Context) {
    lastUserWakeAt = System.currentTimeMillis()
    if (nightSessionActive) {
      main.removeCallbacks(nightSessionElapsed)
      main.postDelayed(nightSessionElapsed, overnightSessionMs(context))
    } else if (isOvernightNow(context)) {
      startNightSession(context)
    }
  }

  /** The launcher is no longer foreground: drop any pending overnight re-sleep. */
  fun onLeftLauncher() {
    nightSessionActive = false
    main.removeCallbacks(nightSessionElapsed)
  }

  /** The daytime idle alarm fired: turn the system Dream off so it can't re-light, then blank. */
  fun onIdleElapsed(context: Context) {
    SettingsGuard.setSystemScreensaverEnabled(context, false)
    ScreenControl.sleep(context)
  }

  private fun cancelIdle(context: Context) = cancel(context, ACTION_IDLE, RC_IDLE)

  private fun overnightSessionMs(context: Context): Long {
    val cfg = ScreensaverConfig.load(context)
    return if (cfg.idleSleepOn) cfg.idleSleepMin * 60_000L else OVERNIGHT_SESSION_DEFAULT_MS
  }

  private fun startNightSession(context: Context) {
    main.removeCallbacks(nightSessionElapsed)
    nightSessionCtx = context.applicationContext
    nightSessionActive = true
    main.postDelayed(nightSessionElapsed, overnightSessionMs(context))
  }

  // ----- one-shot sleep timer ------------------------------------------------

  /** Arm a one-shot sleep timer. */
  fun armSleepTimer(context: Context) {
    val cfg = ScreensaverConfig.load(context)
    if (!cfg.sleepTimerEnabled) {
      cancelSleepTimer(context)
      return
    }
    val minutes = ScreensaverConfig.clampSleepTimer(cfg.sleepTimerMin)
    val at = System.currentTimeMillis() + minutes * 60_000L
    setAlarm(context, at, ACTION_SLEEP_TIMER, RC_SLEEP_TIMER)
    Log.i(TAG, "sleep timer armed for $minutes min")
  }

  /** Cancel the one-shot sleep timer. */
  fun cancelSleepTimer(context: Context) = cancel(context, ACTION_SLEEP_TIMER, RC_SLEEP_TIMER)

  // ----- overnight window ----------------------------------------------------

  fun isOvernightNow(context: Context): Boolean {
    val cfg = ScreensaverConfig.load(context)
    if (!cfg.overnightEnabled) return false
    return inWindow(nowMinuteOfDay(), cfg.overnightStartMin, cfg.overnightEndMin)
  }

  /** Pure (unit-tested): is [now] inside the [start,end) window, handling wrap past midnight? */
  fun inWindow(now: Int, start: Int, end: Int): Boolean =
      if (start == end) false
      else if (start < end) now in start until end
      else now >= start || now < end // wraps midnight

  /** What a would-be photo-frame redream should do when it happens inside the overnight window. */
  internal enum class OvernightRedream {
    /** Not in the window (or night-clock mode): let the normal frame relaunch proceed. */
    RELAUNCH,
    /** The user deliberately woke the device (a session is live, or one just started): leave it. */
    LEAVE,
    /** Dark window, no user around: re-blank directly, without launching an Activity. */
    REBLANK,
  }

  /**
   * Pure (unit-tested) decision for [handleRedreamDuringOvernight]. Two things must both hold:
   *
   *  - A *stray* dream stop inside the dark window must not relaunch [PhotoFramePreviewActivity]:
   *    launching an Activity wakes the screen, and the activity then immediately blanks it again —
   *    a brief flash every time a sibling/system dream cycles overnight (issue #73). So with no
   *    user around we [REBLANK] in place. Night-clock mode still relaunches, because there the
   *    frame *is* the dimmed clock the window is meant to show.
   *  - A *deliberate* dark-window wake must NOT be reblanked (issue #138). Every redream that
   *    reaches here is already `interactive` (that is what makes the verdict REDREAM), so
   *    interactivity alone can't tell a real wake from a stray cycle. [userWokeRecently] — a real
   *    USER_PRESENT/touch within [USER_WAKE_GRACE_MS] — is the signal that distinguishes them, and
   *    it means [LEAVE] (the caller then starts the renewable session).
   */
  internal fun classifyOvernightRedream(
      inWindow: Boolean,
      nightSessionActive: Boolean,
      nightClock: Boolean,
      userWokeRecently: Boolean = false,
  ): OvernightRedream =
      when {
        !inWindow -> OvernightRedream.RELAUNCH
        nightSessionActive -> OvernightRedream.LEAVE
        nightClock -> OvernightRedream.RELAUNCH
        userWokeRecently -> OvernightRedream.LEAVE
        else -> OvernightRedream.REBLANK
      }

  internal enum class OvernightApply {
    /** Outside the overnight window: scheduling is enough. */
    LEAVE,
    /** Inside the window and already interactive: give the user a renewable session. */
    START_SESSION,
    /** Inside the window with no active user: enter the dark/clock rest state now. */
    REST,
  }

  internal fun classifyOvernightApply(inWindow: Boolean, interactive: Boolean): OvernightApply =
      when {
        !inWindow -> OvernightApply.LEAVE
        interactive -> OvernightApply.START_SESSION
        else -> OvernightApply.REST
      }

  /**
   * Called from [DreamPolicy.onDreamingStopped] before it would relaunch the photo frame. Returns
   * true if the overnight window owns the screen and the caller must NOT relaunch (we either left a
   * live session alone, or re-blanked the dark window in place). Returns false to allow the normal
   * relaunch (outside the window, or night-clock mode where the relaunch renders the clock).
   */
  fun handleRedreamDuringOvernight(context: Context): Boolean {
    val userWokeRecently =
        lastUserWakeAt > 0L && System.currentTimeMillis() - lastUserWakeAt in 0..USER_WAKE_GRACE_MS
    val decision =
        classifyOvernightRedream(
            inWindow = isOvernightNow(context),
            nightSessionActive = nightSessionActive,
            nightClock = ScreensaverConfig.load(context).overnightNightClock,
            userWokeRecently = userWokeRecently,
        )
    return when (decision) {
      OvernightRedream.RELAUNCH -> false
      OvernightRedream.LEAVE -> {
        // A deliberate dark-window wake whose USER_PRESENT raced behind this dream-stop: start the
        // renewable session now so the screen returns to rest after idle instead of staying lit.
        // A no-op when a session is already active (the other LEAVE case).
        if (!nightSessionActive) startNightSession(context)
        true
      }
      OvernightRedream.REBLANK -> {
        // Keep the system Dream suppressed for the window, then blank without an Activity launch
        // (no flash). Mirrors enterOvernightRest's dark path minus the screen-on round-trip.
        SettingsGuard.setSystemScreensaverEnabled(context, false)
        ScreenControl.sleep(context)
        true
      }
    }
  }

  /** (Re)schedule the daily start/end alarms, or clear them if disabled. */
  fun scheduleOvernight(context: Context) {
    val cfg = ScreensaverConfig.load(context)
    if (!cfg.overnightEnabled) {
      cancel(context, ACTION_OVERNIGHT_START, RC_OVERNIGHT_START)
      cancel(context, ACTION_OVERNIGHT_END, RC_OVERNIGHT_END)
      return
    }
    setAlarm(context, nextOccurrence(cfg.overnightStartMin), ACTION_OVERNIGHT_START, RC_OVERNIGHT_START)
    setAlarm(context, nextOccurrence(cfg.overnightEndMin), ACTION_OVERNIGHT_END, RC_OVERNIGHT_END)
    Log.i(TAG, "overnight scheduled ${cfg.overnightStartMin}→${cfg.overnightEndMin}")
  }

  fun sleepNow(context: Context, pauseAudio: Boolean = true, closeApp: Boolean = true) {
    if (pauseAudio) pauseAudio(context)
    if (closeApp) closeCurrentApp(context)
    SettingsGuard.setSystemScreensaverEnabled(context, false)
    ScreenControl.sleep(context)
  }

  /** Apply the right state immediately (on boot, app start, or a settings change). */
  fun applyOvernightNow(context: Context) {
    scheduleOvernight(context)
    when (classifyOvernightApply(inWindow = isOvernightNow(context), interactive = isInteractive(context))) {
      OvernightApply.LEAVE -> Unit
      OvernightApply.START_SESSION -> {
        SettingsGuard.reaffirmScreensaver(context)
        startNightSession(context)
      }
      OvernightApply.REST -> enterOvernightRest(context)
    }
  }

  /** The window-start alarm fired: enter the rest state and arm tomorrow's alarms. */
  fun onWindowStart(context: Context) {
    Log.i(TAG, "overnight start")
    enterOvernightRest(context)
    scheduleOvernight(context)
  }

  internal enum class OvernightWake {
    /** Screensaver enabled: wake panel and launch screensaver over keyguard. */
    WAKE_AND_SCREENSAVER,
    /** Screensaver disabled: wake panel back to launcher. */
    WAKE_ONLY,
  }

  internal fun classifyOvernightWake(screensaverEnabled: Boolean): OvernightWake =
      if (screensaverEnabled) OvernightWake.WAKE_AND_SCREENSAVER else OvernightWake.WAKE_ONLY

  /** The window-end alarm fired: restore the screensaver per the user's setting, re-arm tomorrow. */
  fun onWindowEnd(context: Context) {
    Log.i(TAG, "overnight end → restore screensaver")
    SettingsGuard.reaffirmScreensaver(context)
    scheduleOvernight(context)

    // Wake the panel from overnight sleep. The dark window locked the display via ScreenControl.sleep
    // (lockNow), so reaffirming Settings.Secure alone leaves the panel powered off. An explicit
    // wake turns the display on; and launching PhotoFramePreviewActivity surfaces the frame
    // over the keyguard (issue #158) with setShowWhenLocked / setTurnScreenOn.
    ScreenControl.wake(context)
    val cfg = ScreensaverConfig.load(context)
    if (classifyOvernightWake(cfg.enabled) == OvernightWake.WAKE_AND_SCREENSAVER) {
      runCatching {
        context.startActivity(
            Intent(context, PhotoFramePreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
      }
          .onFailure { Log.w(TAG, "screensaver launch on window end failed", it) }
    }
  }

  /**
   * Put the screen into the overnight rest state: a dimmed flip night clock, or dark. Either way
   * we force the system Dream off for the window first (so the stock dream can't re-light over us).
   */
  private fun enterOvernightRest(context: Context) {
    SettingsGuard.reaffirmScreensaver(context) // inside the window this forces the system Dream off
    if (ScreensaverConfig.load(context).overnightNightClock) {
      cancelIdle(context) // the bedside clock stays up; no stray daytime idle alarm may blank it
      ScreenControl.wake(context) // the window may start while the screen is already off
      launchNightClock(context)
    } else {
      ScreenControl.sleep(context)
    }
  }

  private fun pauseAudio(context: Context) {
    runCatching {
      val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      am.dispatchMediaKeyEvent(
          android.view.KeyEvent(
              android.view.KeyEvent.ACTION_DOWN,
              android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
          )
      )
      am.dispatchMediaKeyEvent(
          android.view.KeyEvent(
              android.view.KeyEvent.ACTION_UP,
              android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
          )
      )
      Log.i(TAG, "media pause dispatched")
    }.onFailure { Log.w(TAG, "failed to dispatch media pause", it) }
  }

  private fun closeCurrentApp(context: Context) {
    val intent =
        Intent(context, HomeActivity::class.java)
            .setAction("com.immortal.launcher.CLOSE_CURRENT_APP")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onSuccess { Log.i(TAG, "close-app intent sent") }
        .onFailure { Log.w(TAG, "failed to send close-app intent", it) }
  }

  /** Show [PhotoFramePreviewActivity], which renders the dimmed night clock while in the window. */
  private fun launchNightClock(context: Context) {
    runCatching {
          context.startActivity(
              Intent(context, PhotoFramePreviewActivity::class.java)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        .onFailure { Log.w(TAG, "night clock launch failed", it) }
  }

  private fun nowMinuteOfDay(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
  }

  private fun isInteractive(context: Context): Boolean =
      context.getSystemService(PowerManager::class.java)?.isInteractive == true

  private fun nextOccurrence(minuteOfDay: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
    c.set(Calendar.MINUTE, minuteOfDay % 60)
    if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
  }
}
