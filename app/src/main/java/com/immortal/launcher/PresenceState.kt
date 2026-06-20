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
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One source of truth for "is someone here, and what's the screen doing" — shared by the
 * photo-frame screensaver (in-process) and the Snapcast music companion (cross-process).
 *
 * Why this exists: the Portal won't let an unprivileged app read Meta's presence signal
 * (it's front-camera CV behind a platform-signature permission — see docs/design/multi-room-audio.md),
 * but the system's own dream/sleep lifecycle is DERIVED from that signal, and we DO receive
 * it. So the dream coming up means "someone's around"; the device sleeping at a screen
 * timeout means "the room emptied." [PresenceHub] turns those events into a single
 * [PresenceState] that both the screensaver and the music react to, instead of each guessing
 * from raw screen on/off and drifting apart.
 *
 * The honest caveat lives in one field: [PresenceState.confident]. It's false exactly when the
 * frame is pinned on (Always-on mode), because a held screen never times out, so the
 * empty-room transition is hidden and presence is genuinely UNKNOWN. Consumers read that as
 * "fall back to Home Assistant or the manual override."
 */

enum class Presence {
  /** The room is occupied (the dream is up, or the user just interacted). */
  PRESENT,
  /** The room emptied — the device slept at a screen timeout. */
  ABSENT,
  /** Can't tell: the frame is pinned on (proxy masked) or we haven't observed a transition. */
  UNKNOWN,
}

enum class ScreenState {
  /** Screen on and being touched / the home UI is up. */
  INTERACTIVE,
  /** The photo-frame dream is showing. */
  DREAMING,
  /** Screen blanked. */
  OFF,
}

/**
 * Immutable snapshot of the shared signal.
 *
 * @param confident false when the Always-on frame masks the proxy → treat [presence] as
 *   advisory only and defer to an authoritative source (HA occupancy / manual override).
 * @param sinceMs wall-clock millis when this state began (for grace-period timing downstream).
 */
data class PresenceState(
    val presence: Presence,
    val screen: ScreenState,
    val confident: Boolean,
    val sinceMs: Long,
)

/**
 * The verdict for a single ACTION_DREAMING_STOPPED. Pure and unit-tested ([classifyDreamStop]);
 * this is the one decision that used to be smeared across [DreamPolicy]'s `shouldRelaunch` /
 * `bridging` / volatile flags.
 */
enum class DreamStopVerdict {
  /** A periodic transient force-wake while someone's still here → re-assert the frame. */
  REDREAM,
  /** A real sleep at a screen timeout → the room is empty; let it sleep. */
  SLEEP,
  /** The user tapped out of the dream — they're here and interacting; don't relaunch. */
  USER_EXIT,
  /** A deliberate stock-launcher (Calls) handoff is in flight → ignore entirely. */
  SUPPRESSED,
}

/** Grace after a user tap during which a dream-stop is read as a deliberate exit, not presence. */
const val USER_EXIT_GRACE_MS = 4000L

/** How long after a Calls bridge we keep suppressing dream-stop handling. */
const val BRIDGE_GRACE_MS = 8000L

/**
 * Pure classifier for a dream-stop. No Android, no side effects → JVM-unit-tested.
 *
 * Order matters: a stock-launcher handoff trumps everything (we must not act over a call
 * flow); a fresh user tap trumps the interactive/sleep split; otherwise the screen state at
 * the moment the dream stopped tells present-vs-empty.
 */
fun classifyDreamStop(
    userExitAgoMs: Long,
    bridgeAgoMs: Long,
    inStockHandoff: Boolean,
    interactive: Boolean,
): DreamStopVerdict =
    when {
      inStockHandoff || bridgeAgoMs in 0..BRIDGE_GRACE_MS -> DreamStopVerdict.SUPPRESSED
      userExitAgoMs in 0..USER_EXIT_GRACE_MS -> DreamStopVerdict.USER_EXIT
      interactive -> DreamStopVerdict.REDREAM // force-woken but still awake → someone's here
      else -> DreamStopVerdict.SLEEP // slept at the timeout → room empty
    }

/**
 * Runtime owner of [PresenceState]. Initialised once from [ImmortalApp]; holds the current
 * snapshot, notifies in-process listeners (the screensaver), and publishes a
 * [com.immortal.launcher.PRESENCE][ACTION_PRESENCE] broadcast the GPL companion subscribes to
 * — keeping that integration intent-only (no code linking; see *Licensing* in the design doc).
 */
object PresenceHub {
  private const val TAG = "ImmortalPresence"

  /** Action the music companion listens for. Extras: see [publish]. */
  const val ACTION_PRESENCE = "com.immortal.launcher.PRESENCE"
  const val EXTRA_PRESENCE = "presence" // Presence.name
  const val EXTRA_SCREEN = "screen" // ScreenState.name
  const val EXTRA_CONFIDENT = "confident" // Boolean
  const val EXTRA_SINCE_MS = "sinceMs" // Long

  fun interface Listener {
    fun onPresenceChanged(state: PresenceState)
  }

  private val listeners = CopyOnWriteArrayList<Listener>()
  private var app: Context? = null

  @Volatile
  var current: PresenceState = PresenceState(Presence.UNKNOWN, ScreenState.OFF, confident = false, sinceMs = 0L)
    private set

  /** Subscribe (in-process). Immediately replays the current state to the new listener. */
  fun addListener(l: Listener) {
    listeners.add(l)
    l.onPresenceChanged(current)
  }

  fun removeListener(l: Listener) = listeners.remove(l)

  /**
   * Register the receivers that feed presence. Called from [ImmortalApp.onCreate]; safe to call
   * once. We listen for the dream lifecycle (the proxy), plus screen/user/power events that
   * either confirm presence (a real interaction) or change whether the proxy is even observable.
   */
  fun init(context: Context) {
    if (app != null) return
    app = context.applicationContext
    val filter =
        IntentFilter().apply {
          addAction(Intent.ACTION_DREAMING_STARTED)
          addAction(Intent.ACTION_USER_PRESENT)
          addAction(Intent.ACTION_SCREEN_OFF)
          addAction(Intent.ACTION_POWER_CONNECTED)
          addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
    // DREAMING_STOPPED is intentionally NOT handled here — it's routed through
    // [DreamPolicy.onDreamingStopped], which owns the frame action and then calls
    // [onDreamStopped] so the hub stays the single source of truth.
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
              Intent.ACTION_DREAMING_STARTED -> onDreamStarted(c)
              Intent.ACTION_USER_PRESENT -> onInteraction(c)
              Intent.ACTION_SCREEN_OFF -> onScreenOff(c)
              Intent.ACTION_POWER_CONNECTED,
              Intent.ACTION_POWER_DISCONNECTED -> refreshConfidence(c)
            }
          }
        }
    context.applicationContext.registerReceiver(receiver, filter)
    refreshConfidence(context)
  }

  /** The dream came up: the Portal's presence service saw someone. */
  fun onDreamStarted(c: Context) =
      set(Presence.PRESENT, ScreenState.DREAMING, confident = isConfident(c), context = c)

  /** A real interaction (unlock / touch). Unambiguously present. */
  fun onInteraction(c: Context) =
      set(Presence.PRESENT, ScreenState.INTERACTIVE, confident = isConfident(c), context = c)

  /**
   * Result of a dream-stop, after [DreamPolicy] has classified and acted on it. The hub records
   * the presence consequence and republishes for the companion.
   */
  fun onDreamStopped(c: Context, verdict: DreamStopVerdict) =
      when (verdict) {
        DreamStopVerdict.SLEEP -> set(Presence.ABSENT, ScreenState.OFF, confident = isConfident(c), context = c)
        DreamStopVerdict.REDREAM -> set(Presence.PRESENT, ScreenState.DREAMING, confident = isConfident(c), context = c)
        DreamStopVerdict.USER_EXIT -> set(Presence.PRESENT, ScreenState.INTERACTIVE, confident = isConfident(c), context = c)
        DreamStopVerdict.SUPPRESSED -> Unit // a call handoff — presence is unknowable here
      }

  /** Screen blanked outside the dream path (our own lockNow, power button). */
  private fun onScreenOff(c: Context) {
    // If we were confidently following the proxy, a screen-off means the room emptied or the
    // user left; report ABSENT. If we weren't confident (pinned frame), stay UNKNOWN.
    val confident = isConfident(c)
    set(if (confident) Presence.ABSENT else Presence.UNKNOWN, ScreenState.OFF, confident, c)
  }

  /** Power changed → whether the frame is pinned (and thus whether the proxy is observable). */
  private fun refreshConfidence(c: Context) {
    val confident = isConfident(c)
    if (confident != current.confident) {
      // Confidence flipped without a presence event (e.g. unplugged a mains frame): keep the
      // presence value but correct its trustworthiness, so consumers re-evaluate.
      set(if (confident) current.presence else Presence.UNKNOWN, current.screen, confident, c)
    }
  }

  /** True when the frame is NOT pinned, i.e. the dream/sleep proxy is actually observable. */
  private fun isConfident(c: Context): Boolean {
    val cfg = ScreensaverConfig.load(c)
    return !DreamPolicy.holdScreenOn(
        mode = cfg.presenceMode,
        hasBattery = DreamPolicy.hasBattery(c),
        batterySaver = cfg.batterySaver,
        powered = DreamPolicy.isPowered(c),
    )
  }

  private fun set(presence: Presence, screen: ScreenState, confident: Boolean, context: Context) {
    val next = PresenceState(presence, screen, confident, System.currentTimeMillis())
    val prev = current
    if (prev.presence == next.presence && prev.screen == next.screen && prev.confident == next.confident) {
      return // no meaningful change; don't churn listeners/broadcasts
    }
    current = next
    Log.i(TAG, "presence ${prev.presence}/${prev.screen} -> ${next.presence}/${next.screen} confident=${next.confident}")
    listeners.forEach { runCatching { it.onPresenceChanged(next) } }
    publish(context, next)
  }

  /** Fan the state out to the companion app over the intent-only boundary. */
  private fun publish(context: Context, state: PresenceState) {
    runCatching {
      context.applicationContext.sendBroadcast(
          Intent(ACTION_PRESENCE)
              .putExtra(EXTRA_PRESENCE, state.presence.name)
              .putExtra(EXTRA_SCREEN, state.screen.name)
              .putExtra(EXTRA_CONFIDENT, state.confident)
              .putExtra(EXTRA_SINCE_MS, state.sinceMs))
    }
        .onFailure { Log.w(TAG, "presence broadcast failed", it) }
  }
}
