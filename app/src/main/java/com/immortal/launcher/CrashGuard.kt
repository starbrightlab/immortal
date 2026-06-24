/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

/**
 * Last line of defence for the device's home experience: if anything crashes our process with an
 * uncaught exception, bounce straight back into [HomeActivity] instead of leaving the Portal
 * stranded.
 *
 * Why a launcher needs this. When the home app's process dies on a crash, Android **clears its
 * preferred-activity (HOME) association** (`ActivityTaskManager: Clearing package preferred
 * activities`), so the next Home press pops the "Select Home app" chooser. We can't silently
 * re-assert the home role from inside the app (it isn't a secure setting — see [SettingsGuard]),
 * so instead we relaunch our Home component *explicitly by class*, which bypasses the chooser
 * entirely: the user lands back in Immortal and never sees it. The crash is still recorded
 * (we chain to the platform handler) — we recover, we don't swallow.
 *
 * The known trigger for this was an oversized photo-frame bitmap (fixed at the source in
 * [PhotoFrameController]); this guard is the belt-and-suspenders for any *future* uncaught crash.
 *
 * Loop safety: a relaunch that immediately re-crashes would hot-loop (battery drain, alarm spam).
 * [decide] gives up after [MAX_RAPID] crashes inside [WINDOW_MS] of each other; crashes spaced
 * further apart always relaunch (the window resets), so an occasional fault still self-heals while
 * a genuine boot-loop is allowed to settle.
 */
object CrashGuard {
  private const val TAG = "ImmortalCrashGuard"
  private const val PREFS = "crash_guard"
  private const val KEY_LAST = "last_crash_ms"
  private const val KEY_COUNT = "rapid_count"

  // Two crashes closer than this count as "rapid" (same fault re-firing on relaunch).
  internal const val WINDOW_MS = 10_000L
  // Relaunch up to this many rapid crashes, then stop until the next well-spaced crash / reboot.
  internal const val MAX_RAPID = 3
  // Fire the relaunch after the dying process is gone, so the fresh task starts clean.
  private const val RELAUNCH_DELAY_MS = 1_000L
  private const val RELAUNCH_REQ = 0x1A0A

  fun install(app: Application) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching {
        Log.e(TAG, "uncaught exception on '${thread.name}' — recovering launcher", error)
        if (registerCrashAndDecide(app, System.currentTimeMillis())) scheduleRelaunch(app)
        else Log.e(TAG, "crash loop detected (>$MAX_RAPID in ${WINDOW_MS}ms) — not relaunching")
      }
      // Chain to the platform handler so the crash still reaches logcat / DropBox and the process
      // dies the normal way; our relaunch is scheduled out-of-process via AlarmManager.
      if (previous != null) previous.uncaughtException(thread, error)
      else {
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(10)
      }
    }
  }

  /**
   * Record this crash against the recent history and decide whether to relaunch. Persisted with
   * `commit()` (not `apply()`) because the process is about to die and an async write would be lost.
   */
  private fun registerCrashAndDecide(ctx: Context, nowMs: Long): Boolean {
    val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val (relaunch, count) = decide(nowMs, p.getLong(KEY_LAST, 0L), p.getInt(KEY_COUNT, 0))
    p.edit().putLong(KEY_LAST, nowMs).putInt(KEY_COUNT, count).commit()
    return relaunch
  }

  /**
   * Pure loop-breaker decision (no Android deps, so it's unit-tested directly). Crashes within
   * [WINDOW_MS] of the previous one accumulate; spacing them out resets the streak to 1.
   */
  internal fun decide(nowMs: Long, lastCrashMs: Long, prevCount: Int): Pair<Boolean, Int> {
    val count = if (nowMs - lastCrashMs < WINDOW_MS) prevCount + 1 else 1
    return (count <= MAX_RAPID) to count
  }

  private fun scheduleRelaunch(ctx: Context) {
    val intent =
        Intent(ctx, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pi =
        PendingIntent.getActivity(
            ctx,
            RELAUNCH_REQ,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    am.set(AlarmManager.RTC, System.currentTimeMillis() + RELAUNCH_DELAY_MS, pi)
  }
}
