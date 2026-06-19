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

/**
 * Live status of the special permissions Immortal relies on — all granted by the
 * provisioning kit (`provision.sh` / `provision.ps1`) over adb, none of them toggleable
 * from the Portal's own UI on Android 9/10. Surfaced as a "Device health" view so a
 * struggling user (or we, debugging) can see at a glance which capability is missing and
 * what it costs them.
 *
 * Each [Check] always reports what it enables; the [degraded] cost and [fix] are only
 * meant to be shown when it's NOT [granted].
 */
object DevicePermissions {

  data class Check(
      val title: String,
      val enables: String,
      val granted: Boolean,
      val degraded: String,
      val fix: String,
  )

  fun all(context: Context): List<Check> =
      listOf(
          Check(
              title = "Screen off",
              enables = "Turning the screen off — idle timeout, overnight sleep, and the Home Assistant control.",
              granted = ScreenControl.isAdminActive(context),
              degraded = "The screen can't be turned off automatically or from Home Assistant; it stays on.",
              fix = "Re-run Immortal setup — it activates the screen-off device admin (dpm set-active-admin).",
          ),
          Check(
              title = "Now playing",
              enables = "Showing the current track and album art on the screensaver and home header.",
              granted = SettingsGuard.isMediaListenerEnabled(context),
              degraded = "What's playing won't appear on the screensaver or the home header.",
              fix = "Re-run Immortal setup — it grants notification access (cmd notification allow_listener).",
          ),
          Check(
              title = "Install apps",
              enables = "Installing and updating apps from the Immortal App Store.",
              granted = canInstallApps(context),
              degraded = "The App Store can't install or update apps.",
              fix = "Re-run Immortal setup — it allows installs (appops REQUEST_INSTALL_PACKAGES).",
          ),
          Check(
              title = "Frame relaunch",
              enables = "Bringing the photo frame straight back when the system force-wakes the screensaver.",
              granted = Settings.canDrawOverlays(context),
              degraded = "The photo frame may not reappear promptly after the system force-wakes it.",
              fix = "Re-run Immortal setup — it grants draw-over-other-apps (appops SYSTEM_ALERT_WINDOW).",
          ),
          Check(
              title = "System settings",
              enables = "Keeping the screensaver, status bar, and WiFi-debug settings applied (and self-healing them).",
              granted = SettingsGuard.canWriteSecureSettings(context),
              degraded = "Immortal can't keep the screensaver/status-bar settings applied or repair them after changes.",
              fix = "Re-run Immortal setup — it grants write-secure-settings (pm grant WRITE_SECURE_SETTINGS).",
          ),
      )

  /** Count of capabilities that aren't granted — 0 means a healthy device. */
  fun issueCount(context: Context): Int = all(context).count { !it.granted }

  private fun canInstallApps(context: Context): Boolean =
      runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls()
            else true
          }
          .getOrDefault(false)
}
