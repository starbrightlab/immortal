/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Reads Meta's OWN camera presence detection, by tailing the system log.
 *
 * The permission that would let us ask the platform directly is signature-gated and stays shut
 * (see [PresenceHub] and `docs/limitations.md`) — but the detector is chatty. Portal's
 * `PresenceManager` (Aloha) logs a heartbeat roughly every 30s **only while it sees a person**,
 * and goes quiet when the room empties. So the signal isn't the text of the line, it's the
 * *liveness* of the beat: recent beats mean someone is there, a gap means the room emptied.
 *
 * This gives the presence sensor a real reading instead of [PresenceHub]'s dream/sleep proxy,
 * and it keeps working while the frame is pinned on — exactly the case the proxy calls
 * `confident = false`, because a held screen never times out and hides the empty-room transition.
 *
 * Needs `READ_LOGS`, which is a *development* permission the provisioning kit already grants for
 * the fleet agent's `/logcat` endpoint ([FleetDiag]) — so on a provisioned Portal this costs no
 * new permission. Without it we simply never start, and the proxy stays in charge.
 *
 * **Silence is not absence.** Until a first beat is seen the verdict is `null`, not "absent" —
 * on hardware or a firmware build where these tags never appear (or where the grant is missing),
 * reporting an empty room forever would be worse than saying nothing and letting the proxy
 * answer. Only once the detector has proven it talks do gaps start meaning "nobody here".
 */
object PortalPresenceLog {
  /** A beat whose own timestamp is older than this is backlog, not news — logcat replays. */
  const val FRESH_MS = 45_000L

  /** Declare the room empty once the newest beat is older than this (~30s beat + margin). */
  const val ABSENT_MS = 50_000L

  /** How often the verdict is re-evaluated against the clock. */
  const val CHECK_MS = 10_000L

  /** Portal's presence service. Everything it logs is about presence — the tag IS the signal. */
  const val TAG_PRESENCE = "PresenceManager"

  /** The camera service. It logs presence among other things, so its lines need the text test. */
  const val TAG_CAMERA = "aloha.CameraServiceController"

  /** The log tags to follow, in `logcat -s` form. */
  val TAG_FILTERS = arrayOf("$TAG_PRESENCE:I", "$TAG_CAMERA:I")

  /**
   * Epoch millis of a presence heartbeat line (`logcat -v epoch`), or null when the line isn't
   * one.
   *
   * The message is tested separately from the tag, which matters more than it looks: every line
   * from `PresenceManager` contains the string "presence" in its *tag*, so testing the whole line
   * would accept anything that tag ever logs. Lines from the presence service count on the tag
   * alone; lines from the camera service, which logs plenty that isn't about presence, have to
   * say so in the message.
   */
  fun beatEpochMs(line: String): Long? {
    val epoch = line.trimStart().substringBefore(' ').toDoubleOrNull() ?: return null
    if (epoch <= 0.0) return null
    // "<epoch>  <pid>  <tid> <level> <Tag>: <message>"
    val head = line.substringBefore(": ", missingDelimiterValue = "")
    if (head.isEmpty()) return null
    val message = line.substringAfter(": ", missingDelimiterValue = "")
    val fromPresenceService = head.trimEnd().endsWith(TAG_PRESENCE)
    if (!fromPresenceService && !message.contains("presence", ignoreCase = true)) return null
    return (epoch * 1000).toLong()
  }

  /**
   * True when a beat stamped [beatEpochMs] is recent enough to count as live rather than the
   * backlog logcat dumps on attach. A beat slightly in the future (clock jitter between the log
   * writer and us) counts as fresh.
   */
  fun isFreshBeat(beatEpochMs: Long, nowMs: Long): Boolean = (nowMs - beatEpochMs) < FRESH_MS

  /**
   * The presence verdict: true = someone's here, false = room empty, **null = don't know yet**
   * (no beat has ever been seen, so the detector may simply not be talking on this device).
   */
  fun verdict(lastBeatMs: Long, nowMs: Long): Boolean? =
      when {
        lastBeatMs <= 0L -> null
        nowMs - lastBeatMs < ABSENT_MS -> true
        else -> false
      }
}

/**
 * Owns the one [PortalPresenceMonitor] for the process, so presence improves for everything that
 * reads [PresenceHub] — the Home Assistant entity, the fleet agent's `/info`, and the multi-room
 * companion — rather than only while a broker happens to be connected.
 *
 * Started from [ImmortalApp] and re-synced when the setting changes. [sync] is the whole API:
 * call it, and the monitor runs iff the user wants it (and `READ_LOGS` allows it).
 */
object PortalPresenceDetector {
  private var monitor: PortalPresenceMonitor? = null

  /** Start or stop the detector to match the setting. Safe to call repeatedly. */
  @Synchronized
  fun sync(context: Context) {
    val app = context.applicationContext
    if (!ImmortalSettings.portalPresence(app)) {
      monitor?.stop()
      monitor = null
      return
    }
    if (monitor != null) return
    val m = PortalPresenceMonitor(app) { PresenceHub.onPortalPresence(app, it) }
    // start() returns false without READ_LOGS; drop the instance so a later sync retries (the
    // grant can arrive between runs, when the provisioning kit is re-run).
    if (m.start()) monitor = m
  }
}

/**
 * Runs [PortalPresenceLog] against a live `logcat` process and reports transitions to
 * [PresenceHub]. Owns two daemon threads — one blocked on the log stream, one on a timer that
 * ages the last beat out — mirroring the shape of [MqttPublisher]'s worker/pinger pair.
 */
class PortalPresenceMonitor(
    private val appContext: Context,
    private val onPresence: (Boolean?) -> Unit,
) {
  @Volatile private var running = false
  @Volatile private var lastBeatMs = 0L
  @Volatile private var reported: Boolean? = null
  private var process: Process? = null
  private var reader: Thread? = null
  private var checkThread: HandlerThread? = null
  private var checkHandler: Handler? = null

  /** True when `READ_LOGS` is granted — i.e. when this monitor can do anything at all. */
  fun canRead(): Boolean =
      runCatching {
            appContext.checkSelfPermission(android.Manifest.permission.READ_LOGS) ==
                PackageManager.PERMISSION_GRANTED
          }
          .getOrDefault(false)

  /** Start tailing. Returns false when the permission is missing (the proxy stays in charge). */
  fun start(): Boolean {
    if (running) return true
    if (!canRead()) {
      Log.i(TAG, "READ_LOGS not granted; leaving presence to the dream proxy")
      return false
    }
    running = true
    lastBeatMs = 0L
    reported = null
    val t = HandlerThread("portal-presence-check").apply { start() }
    checkThread = t
    checkHandler = Handler(t.looper).also { it.post(checkTick) }
    reader = Thread(::readLoop, "portal-presence-log").apply { isDaemon = true; start() }
    Log.i(TAG, "portal presence monitor started")
    return true
  }

  /** Stop tailing and hand presence back to the proxy (reports null, not "absent"). */
  fun stop() {
    if (!running) return
    running = false
    checkHandler?.removeCallbacks(checkTick)
    checkHandler = null
    runCatching { checkThread?.quitSafely() }
    checkThread = null
    runCatching { process?.destroy() }
    process = null
    reader?.interrupt()
    reader = null
    if (reported != null) {
      reported = null
      runCatching { onPresence(null) }
    }
    Log.i(TAG, "portal presence monitor stopped")
  }

  /**
   * Block on `logcat`, stamping [lastBeatMs] for every fresh heartbeat. If the process dies
   * (the log daemon restarts, or someone runs `logcat -c`) this re-spawns it while we're still
   * running, so a transient failure doesn't silently end presence for the rest of the session.
   */
  private fun readLoop() {
    while (running) {
      runCatching {
            // -v epoch prefixes each line with a Unix timestamp we can age-check.
            val p =
                ProcessBuilder(listOf("logcat", "-v", "epoch", "-s") + PortalPresenceLog.TAG_FILTERS)
                    .redirectErrorStream(true)
                    .start()
            process = p
            p.inputStream.bufferedReader().use { r ->
              while (running) {
                val line = r.readLine() ?: break
                val beat = PortalPresenceLog.beatEpochMs(line) ?: continue
                if (PortalPresenceLog.isFreshBeat(beat, System.currentTimeMillis())) {
                  lastBeatMs = System.currentTimeMillis()
                }
              }
            }
          }
          .onFailure { if (running) Log.w(TAG, "presence log reader stopped: ${it.message}") }
      runCatching { process?.destroy() }
      process = null
      // runCatching: stop() interrupts this thread, and an uncaught InterruptedException
      // here would reach the crash handler on an ordinary teardown.
      if (running) runCatching { Thread.sleep(RESPAWN_MS) }
    }
  }

  private val checkTick =
      object : Runnable {
        override fun run() {
          if (!running) return
          val next = PortalPresenceLog.verdict(lastBeatMs, System.currentTimeMillis())
          if (next != reported) {
            reported = next
            Log.i(TAG, "portal presence -> ${next ?: "unknown"}")
            runCatching { onPresence(next) }
          }
          checkHandler?.postDelayed(this, PortalPresenceLog.CHECK_MS)
        }
      }

  private companion object {
    const val TAG = "ImmortalPresence"
    const val RESPAWN_MS = 5_000L
  }
}
