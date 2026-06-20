/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Client for an **optional** shell-privileged install daemon — a watched queue
 * that installs/updates APKs silently, with no system installer dialog.
 *
 * History: the provisioning kit used to start this daemon over ADB to work
 * around the Gen-1 Portal+'s broken installer dialog. That daemon was retired
 * in 1.40 — the kit no longer sets one up. The normal install path is now the
 * system [android.content.pm.PackageInstaller]; on the Gen-1 Portal+, whose
 * built-in dialog renders white-on-white, the kit makes that dialog usable
 * again with a reboot-persistent overlay fix (see [installerDialogFixed]).
 *
 * This client is retained as a still-supported fast path: callers check
 * [isAvailable] first and use the silent queue if a daemon happens to be
 * running (e.g. one started by hand), otherwise they fall through to
 * PackageInstaller. With stock provisioning [isAvailable] is simply false. It
 * also backs the store's "installs paused" state via [installPaused] — true
 * only on a Gen-1 with neither a daemon nor the overlay fix.
 *
 * The daemon renames `<name>.apk` → `<name>.apk.done` / `.failed` to report
 * results; we write APKs atomically (`.part` → rename) so it never sees a
 * partial file.
 */
object InstallDaemon {

  private fun queueDir(context: Context) = File(context.getExternalFilesDir(null), "installq")

  /** Heartbeat freshness window (extracted as a pure function for testing). */
  internal fun heartbeatFresh(tsSeconds: Long, nowSeconds: Long): Boolean =
      (nowSeconds - tsSeconds) in 0..20

  /** True if the daemon is alive (it writes a unix-time heartbeat every ~2s). */
  fun isAvailable(context: Context): Boolean {
    val ts =
        runCatching { File(queueDir(context), ".heartbeat").readText().trim().toLong() }
            .getOrDefault(0L)
    return heartbeatFresh(ts, System.currentTimeMillis() / 1000)
  }

  /**
   * Whether this device's built-in installer dialog is broken out of the box, so
   * it can't use the stock PackageInstaller dialog unaided. True on the Gen-1
   * Portal/Portal+ (Android 9 / API 28); the Android-10 models (Go, Mini, gen-2)
   * have a working system installer. On a Gen-1 an install needs either the
   * overlay fix (makes the stock dialog usable) or a running daemon — used to tell
   * a genuinely-paused Gen-1 ("re-run setup") apart from a healthy newer model.
   */
  /** Pure SDK check (extracted for testing): API < 29 == broken-installer Gen-1. */
  internal fun isLegacy(sdkInt: Int): Boolean = sdkInt < 29

  fun legacyInstaller(): Boolean = isLegacy(Build.VERSION.SDK_INT)

  /**
   * Whether the provisioning kit has fixed the Gen-1 installer dialog by disabling
   * Meta's white-on-white RRO (it sets the global flag `immortal_overlay_fix=1`).
   * When true, the *stock* PackageInstaller dialog is usable again, so a Gen-1 with
   * the daemon down can still install via the system dialog instead of being paused.
   * Unlike the daemon, the overlay fix persists across reboots, so this can be true
   * even when the daemon isn't running. Reading the marker avoids needing the hidden
   * IOverlayManager API to query overlay state directly.
   */
  fun installerDialogFixed(context: Context): Boolean =
      runCatching { Settings.Global.getInt(context.contentResolver, OVERLAY_FIX_FLAG, 0) }
          .getOrDefault(0) == 1

  internal const val OVERLAY_FIX_FLAG = "immortal_overlay_fix"

  /** Pure paused decision (extracted for testing): paused only when there is no
   *  silent path (daemon) AND no working dialog (overlay fix) on a Gen-1. */
  internal fun isPaused(legacy: Boolean, daemonAvailable: Boolean, dialogFixed: Boolean): Boolean =
      legacy && !daemonAvailable && !dialogFixed

  /**
   * Gen-1 with the daemon down AND no working dialog: on-device installs are truly
   * paused until the kit is re-run. If the overlay fix is in place the stock dialog
   * works, so callers fall through to PackageInstaller rather than refusing.
   */
  fun installPaused(context: Context): Boolean =
      isPaused(legacyInstaller(), isAvailable(context), installerDialogFixed(context))

  /**
   * Queue [apk] for the daemon and block (on a background thread) until it
   * reports a result or [timeoutMs] elapses. Returns true on success.
   */
  fun install(context: Context, apk: File, name: String, timeoutMs: Long = 180_000): Boolean =
      install(queueDir(context), apk, name, timeoutMs)

  /**
   * The queue protocol against an explicit directory — extracted so the
   * atomic-rename + poll-for-result behaviour is testable without a Context.
   */
  internal fun install(queueDir: File, apk: File, name: String, timeoutMs: Long = 180_000): Boolean {
    val d = queueDir.apply { mkdirs() }
    val target = File(d, "$name.apk")
    val done = File(d, "$name.apk.done")
    val failed = File(d, "$name.apk.failed")
    val log = File(d, "$name.apk.log")
    listOf(target, done, failed, log).forEach { runCatching { it.delete() } }

    // Write to a temp name, then atomically rename in — the daemon only ever
    // sees a complete APK.
    val part = File(d, "$name.part")
    runCatching { apk.copyTo(part, overwrite = true) }.getOrElse {
      part.delete()
      return false
    }
    if (!part.renameTo(target)) {
      part.delete()
      return false
    }

    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (done.exists()) {
        listOf(done, log).forEach { runCatching { it.delete() } }
        return true
      }
      if (failed.exists()) {
        listOf(failed, log).forEach { runCatching { it.delete() } }
        return false
      }
      Thread.sleep(800)
    }
    return false
  }
}
