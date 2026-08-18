/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.immortal.airplay.AIRPLAY_HANDOVER_GRACE_MS
import com.immortal.airplay.AirPlayEngine
import com.immortal.airplay.AirPlayOptions
import com.immortal.airplay.AirPlaySession
import com.immortal.airplay.sessionFlow
import io.github.jqssun.airplay.service.AirPlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Immortal's runtime wiring for the `:airplay` receiver: bring it up to match [AirPlayConfig], and
 * react to what a session turns out to be.
 *
 * The module needs no service of its own here — `AirPlayService` merges in from the library — so
 * this is only the two things a *launcher* has to decide that a receiver app doesn't:
 *
 *  - **When to put a surface on screen.** The module offers to launch the host's surface Activity
 *    at TCP connect, but the session's kind isn't known yet: an audio-only stream would tear the
 *    screensaver down for nothing. So [AirPlayConfig.options] pins `launchOnConnect` off and the
 *    watcher below raises [AirPlayActivity] only once mirroring or AirPlay video is genuinely live.
 *  - **Who owns the speakers.** Multi-room (Snapcast) and an AirPlay audio session both want them.
 *    The live AirPlay session wins for its duration and hands back when it ends.
 *
 * Both need someone watching the running service, which is what [SessionWatcher] is. As the home
 * app our process is effectively persistent, so a binding held for as long as the receiver runs
 * costs nothing — the same reasoning as the runtime receivers in [ImmortalApp].
 */
object AirPlayControl {
  private const val TAG = "ImmortalAirPlay"

  /**
   * The main thread, which everything here runs on.
   *
   * Callers arrive on whatever thread applied a setting — the phone remote's HTTP worker as much as
   * the UI — while the service's own state changes and the binder callbacks are already on main.
   * One thread for the lot is what keeps [started] and [lastStarted] describing the same server:
   * `startServer` reads the prefs and reaches RUNNING inside a single main-thread message, so an
   * apply can no longer slip between a start's settings and the state that confirms them. Always
   * posted, never run inline, so requests take effect in the order they were issued.
   */
  private val main = Handler(Looper.getMainLooper())

  private fun onMain(block: () -> Unit) {
    main.post(block)
  }

  /**
   * The options the *running* server was started with, so [applyConfig] can skip a no-op restart.
   * Not an optimisation: a restart re-inits the native stack on the main thread and drops any live
   * session, and both pairing surfaces re-apply the config every time they open.
   *
   * Written from the server's own state by [SessionWatcher] — [started] the moment it reports
   * RUNNING, null on anything else — and cleared by [stop]. Latching it optimistically at the call
   * site is what made a failed start unrecoverable: every later apply matched a config that was
   * never actually running, skipped, and left the receiver down behind a UI saying it was on.
   */
  private var lastStarted: AirPlayOptions? = null

  /** The options the last start/restart was issued with — [lastStarted] once the server confirms. */
  private var started: AirPlayOptions? = null

  /** The config a restart has already been retried for, so a miss costs one extra attempt, not a loop. */
  private var retried: AirPlayOptions? = null

  /** Long enough for whatever made the bind time out to have passed. */
  private const val RECONFIGURE_RETRY_MS = 5_000L

  /**
   * Whether this device can run the receiver at all (arm64 + the native library present). Loads the
   * native stack to find out, so only call it where the receiver is about to start anyway.
   */
  fun isSupported(): Boolean = AirPlayEngine.isSupported()

  /** The same question for a UI gate, cheap enough for a composable. */
  fun isProbablySupported(): Boolean = AirPlayEngine.isProbablySupported()

  /**
   * Bring the receiver up if the user turned it on. No-op otherwise, so [ImmortalApp] and
   * [BootReceiver] can call it unconditionally — mirroring [FleetAgentService.ensureRunning].
   */
  fun ensureRunning(context: Context) = onMain { ensureRunningNow(context.applicationContext) }

  private fun ensureRunningNow(c: Context) {
    if (!AirPlayConfig.isEnabled(c) || !isSupported()) return
    runCatching {
          // The service reads its settings on start, so persist them first.
          val options = AirPlayConfig.options(c)
          options.applyTo(c)
          started = options
          // A refused start leaves nothing to watch.
          if (AirPlayEngine.start(c)) SessionWatcher.attach(c)
        }
        .onFailure { Log.w(TAG, "ensureRunning failed", it) }
  }

  /**
   * Make the running receiver match [AirPlayConfig] — start it, restart it with new settings, or
   * stop it. The settings domain's `onApplied` hook, so a change from the on-device screen and one
   * pushed from the phone remote take effect the same way.
   */
  fun applyConfig(context: Context) = onMain { applyConfigNow(context.applicationContext) }

  private fun applyConfigNow(c: Context) {
    // The cheap gate, not [isSupported]: every settings apply lands here — a rename pushed from the
    // phone remote included — and this now runs on the main thread, which is not where a launcher
    // should be dlopening the native stack. The paths below that actually start something check the
    // real thing.
    if (!isProbablySupported()) return
    if (!AirPlayConfig.isEnabled(c)) {
      stopNow(c)
      return
    }
    val options = AirPlayConfig.options(c)
    // `lastStarted == null` means no server is confirmed running with a config — either this
    // process has not brought one up yet or the last attempt failed — and a plain start covers
    // both. Keying that on the service's own state is safe because [SessionWatcher] holds an
    // AUTO_CREATE binding for the receiver's lifetime: the stopSelf inside a settings restart
    // cannot destroy a bound service, so the instance that reports RUNNING again is the same one.
    if (lastStarted == null) {
      retried = null
      ensureRunningNow(c)
      return
    }
    // Config unchanged — leave the running server alone (the common path from both pair surfaces).
    if (options == lastStarted) {
      retried = null
      return
    }
    // Restart to pick up the change. Nothing may follow: reconfigure is asynchronous and re-issues
    // the foreground start itself, so a second start here would race its stopSelf and get the
    // process killed for not calling startForeground in time.
    started = options
    AirPlayEngine.reconfigure(c, options, running = true) { applied ->
      if (applied) {
        retried = null
        return@reconfigure
      }
      // The restart never reached the instance: the old settings are still running, and the server
      // never left RUNNING, so there is no state change to notice that by. Put [started] back to
      // what the server actually has — otherwise the next RUNNING edge, from a reconnect or an
      // unrelated restart, would latch a config it never took and skip every apply after it. Unless
      // a later apply has moved on already, in which case that one owns the field.
      if (started == options) started = lastStarted
      // Nothing else will re-drive this: the prefs already hold the new value, so the disagreement
      // is invisible to every later comparison, and a rename is a one-shot action. One retry, once
      // per config, so a receiver that is simply gone doesn't get poked forever.
      if (retried == options) {
        Log.w(TAG, "reconfigure did not reach the receiver; giving up until the next change")
        return@reconfigure
      }
      Log.w(TAG, "reconfigure did not reach the receiver; retrying once")
      retried = options
      main.postDelayed({ applyConfigNow(c) }, RECONFIGURE_RETRY_MS)
    }
  }

  /** Stop the receiver and drop its mDNS advertisement. Safe when it isn't running. */
  fun stop(context: Context) = onMain { stopNow(context.applicationContext) }

  private fun stopNow(c: Context) {
    // Asynchronous: it stops the service outright, then reaches the live instance through a bind of
    // its own to stop the server inside it. Either half is enough — if dropping our binding below
    // destroys the service before that bind is answered, its onDestroy stops the server anyway.
    runCatching { AirPlayEngine.stop(c) }.onFailure { Log.w(TAG, "stop failed", it) }
    // Both, or the watcher's last emission on the way down could re-latch what we just cleared.
    started = null
    lastStarted = null
    retried = null
    SessionWatcher.detach(c)
  }

  /**
   * Bound to the running [AirPlayService] for as long as the receiver is up, translating its
   * session state into the two host decisions described on [AirPlayControl], and reporting back
   * what the server is actually doing.
   *
   * Main thread throughout: its collectors and binder callbacks land there, and [attach]/[detach]
   * are only ever reached from the hop above.
   */
  private object SessionWatcher : ServiceConnection {
    private var service: AirPlayService? = null
    private var scope: CoroutineScope? = null
    /** Registered with the system, not connected to an instance — only [detach] may clear it. */
    private var bound = false
    /** Kept so a disconnect can release what we were holding without a live service to ask. */
    private var appContext: Context? = null
    /** Rising-edge latches — a session state is a level, and both reactions are edge-triggered. */
    private var videoRaised = false
    private var audioOwned = false
    /** Pending hand-back of the speakers, cancelled if the session comes back within the grace. */
    private var releaseJob: Job? = null

    /** Ignored focus changes: we don't pause the sender, we just claim the output. */
    private val focusListener = AudioManager.OnAudioFocusChangeListener {}

    fun attach(context: Context) {
      if (bound) return
      val c = context.applicationContext
      appContext = c
      // AUTO_CREATE (not a passive bind), always reached just after start() so creation is wanted:
      // a passive bind fails while the service is still starting, and would keep holding the old
      // instance across a settings restart (stopServer calls stopSelf). [detach] releases it.
      bound =
          runCatching {
                c.bindService(Intent(c, AirPlayService::class.java), this, Context.BIND_AUTO_CREATE)
              }
              .getOrDefault(false)
    }

    fun detach(context: Context) {
      val c = context.applicationContext
      if (bound) runCatching { c.unbindService(this) }
      bound = false
      release(c)
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
      service = svc
      val ctx = appContext ?: return
      val s = CoroutineScope(Dispatchers.Main.immediate)
      scope = s
      s.launch { svc.sessionFlow().collect { onSession(ctx, it) } }
      s.launch {
        // The only writer of [lastStarted]: a start that failed (port taken, native init) or a
        // server that stopped leaves it null, so the next apply retries instead of matching a config
        // nothing is running. Reading the state rather than trusting the start call also covers the
        // failure repeating — a second ERROR is not an event, but never reaching RUNNING is.
        svc.serverState.collect {
          lastStarted = if (it == AirPlayService.ServerState.RUNNING) started else null
        }
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      // The service process died. Drop what we held on its behalf, but leave `bound` alone: the
      // binding survives a disconnect and the system reconnects us through it. Clearing it would
      // leave [detach] nothing to unbind, so the AUTO_CREATE binding would keep recreating the
      // service — still advertising over mDNS — after the user turned AirPlay off.
      appContext?.let { release(it) }
    }

    private fun onSession(c: Context, s: AirPlaySession) {
      // Video: raise the surface once, on the way in. The Activity owns its own dismissal — it has
      // to survive the mirroring -> AirPlay-video handover, during which both flags are briefly
      // false and a level-triggered teardown would read as a disconnect.
      val video = s.connected && s.video
      if (video && !videoRaised && AirPlayConfig.showOnConnect(c)) {
        runCatching {
              ScreenControl.wake(c)
              c.startActivity(
                  Intent(c, AirPlayActivity::class.java)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            .onFailure { Log.w(TAG, "failed to raise the AirPlay surface", it) }
      }
      videoRaised = video

      // Audio: any live session owns the speakers, mirroring and AirPlay video included — those
      // carry sound too, and leaving multi-room running under them plays two streams at once.
      if (s.connected && (s.audioOnly || s.video)) {
        disarmRelease()
        setAudioOwned(c, true)
      } else {
        armRelease(c)
      }
    }

    /**
     * Hand the speakers back, but not during a handover. Mirroring → AirPlay video passes through a
     * state with neither flag set, and releasing there would stop and immediately respawn the
     * Snapcast relay — an audible gap in a session that never actually ended. Same reasoning, and
     * the same delay, as [AirPlayActivity]'s close.
     */
    private fun armRelease(c: Context) {
      if (!audioOwned || releaseJob != null) return
      releaseJob =
          scope?.launch {
            delay(AIRPLAY_HANDOVER_GRACE_MS)
            releaseJob = null
            setAudioOwned(c, false)
          }
    }

    private fun disarmRelease() {
      releaseJob?.cancel()
      releaseJob = null
    }

    /**
     * Take or hand back the speakers. Multi-room's Snapcast player and an AirPlay session would
     * otherwise play over each other: the relay is stopped so the now-playing card follows the live
     * stream, and audio focus asks any other local player to get out of the way.
     */
    private fun setAudioOwned(c: Context, owned: Boolean) {
      if (owned == audioOwned) return
      audioOwned = owned
      val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      runCatching {
            if (owned) {
              // The pre-26 form on purpose: it is still honoured on API 26+, and every Portal is
              // API 28/29 — one call beats a version fork for a single transient claim.
              @Suppress("DEPRECATION")
              am?.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
              c.stopService(Intent(c, MultiRoomService::class.java))
            } else {
              @Suppress("DEPRECATION") am?.abandonAudioFocus(focusListener)
              // Restores the relay only if multi-room is still configured; a no-op otherwise.
              MultiRoomService.sync(c)
            }
          }
          .onFailure { Log.w(TAG, "audio ownership change failed", it) }
    }

    private fun release(c: Context) {
      // Cancelling the scope takes any armed release with it, so hand the speakers back here.
      disarmRelease()
      scope?.cancel()
      scope = null
      service = null
      videoRaised = false
      setAudioOwned(c, false)
    }
  }
}
