/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Self-healing for our screensaver settings. The stock Aloha launcher rewrites
 * `screensaver_components` / `screensaver_default_component` back to its own
 * SuperFrame whenever it runs, so Immortal re-asserts them on boot and on every
 * resume. Requires `WRITE_SECURE_SETTINGS`, which provisioning grants via
 * `pm grant` — without it this is a silent no-op (settings stay as provisioned).
 *
 * Note: the *home* role can't be reasserted this way (it isn't a secure setting
 * and needs system privilege); provisioning sets it separately via
 * `cmd package set-home-activity`. The stock launcher stays enabled and remains
 * explicitly launchable — that's what the Calls tile bridges to.
 */
object SettingsGuard {

  /**
   * Directly turn the system Dream on/off. Used to keep the screen dark after an
   * idle/overnight sleep — otherwise a docked Portal immediately re-dreams the
   * screensaver and re-lights the screen. [reaffirmScreensaver] (run on the next
   * return to Immortal's home) puts it back per the user's setting.
   */
  fun setSystemScreensaverEnabled(context: Context, on: Boolean) {
    runCatching {
      Settings.Secure.putInt(context.contentResolver, "screensaver_enabled", if (on) 1 else 0)
    }
  }

  /**
   * Hide or show the system status bar per [ImmortalSettings.hideStatusBar]. Hiding uses
   * the immersive `policy_control` (swipe from the top still reveals it briefly); showing
   * clears it. `Settings.Global`, so needs `WRITE_SECURE_SETTINGS` (provisioning grants
   * it) — a silent no-op without it. Applied on startup and whenever the toggle changes.
   */
  fun applyStatusBar(context: Context) {
    runCatching {
      Settings.Global.putString(
          context.contentResolver,
          "policy_control",
          if (ImmortalSettings.hideStatusBar(context)) "immersive.status=*" else null)
    }
  }

  fun reaffirmScreensaver(context: Context) {
    runCatching {
      val resolver = context.contentResolver
      // User turned Immortal's screensaver OFF (Screensaver settings): stop hijacking
      // the system Dream and turn it off, so the Portal can sleep on its screen-off
      // timer and the user can run their own setup. We mirror our normal "force on"
      // behaviour as a "force off" so a stray re-enable can't bring it back — which
      // is exactly the "I disable it and it comes back" complaint.
      if (!ScreensaverConfig.load(context).enabled || SleepScheduler.isOvernightNow(context)) {
        Settings.Secure.putInt(resolver, "screensaver_enabled", 0)
        return@runCatching
      }
      val ours = ComponentName(context, PhotoDreamService::class.java).flattenToShortString()
      if (Settings.Secure.getString(resolver, "screensaver_components") != ours) {
        Settings.Secure.putString(resolver, "screensaver_components", ours)
      }
      if (Settings.Secure.getString(resolver, "screensaver_default_component") != ours) {
        Settings.Secure.putString(resolver, "screensaver_default_component", ours)
      }
      Settings.Secure.putInt(resolver, "screensaver_enabled", 1)
    }
  }

  /**
   * Re-enables ADB after boot. The vendor init script
   * (`init.common.usb.rc`) kills adbd on every boot for omni_prod-user
   * builds when `ro.boot.force_enable_usb_adb=0`. Writing `adb_enabled=1`
   * to Settings.Global triggers UsbDeviceManager to reconfigure the USB
   * gadget and restart adbd, restoring connectivity.
   *
   * Also re-enables developer settings so the toggle stays visible in
   * Android Settings as a fallback.
   */
  fun reaffirmAdb(context: Context) {
    runCatching {
      val resolver = context.contentResolver
      Settings.Global.putInt(resolver, "adb_enabled", 1)
      Settings.Global.putInt(resolver, "development_settings_enabled", 1)
    }
  }

  /**
   * Enable [InstallConfirmService] so the fleet agent's installs auto-confirm.
   * APPENDS our component to `enabled_accessibility_services` — the Portal ships
   * Meta accessibility services (presence, key events) in that same list, so we
   * must never overwrite it. Requires `WRITE_SECURE_SETTINGS`; a silent no-op
   * without it. Idempotent.
   */
  fun enableInstallConfirm(context: Context) {
    runCatching {
      val resolver = context.contentResolver
      val comp = ComponentName(context, InstallConfirmService::class.java).flattenToString()
      val current =
          Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
      val parts = current.split(':').filter { it.isNotBlank() }
      if (comp !in parts) {
        Settings.Secure.putString(
            resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (parts + comp).joinToString(":"))
      }
      Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }
  }

  /** Whether [InstallConfirmService] is currently enabled (so dialog-mode installs
   *  complete unattended). Lets the fleet dashboard show that a "dialog"-mode device
   *  can still be deployed to without a tap. */
  fun isInstallConfirmEnabled(context: Context): Boolean =
      runCatching {
            val comp = ComponentName(context, InstallConfirmService::class.java).flattenToString()
            (Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':')
                .any { it.equals(comp, ignoreCase = true) }
          }
          .getOrDefault(false)

  // The secure-setting key for notification listeners (the public constant is hidden).
  private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

  /**
   * Best-effort enable of [MediaNotificationListenerService] so the launcher may read
   * the device's active media sessions (native now-playing). Append-don't-overwrite —
   * the Portal ships Meta notification listeners in this list, so never clobber it.
   *
   * NOTE: on Android 10 `enabled_notification_listeners` is OS-protected and usually
   * NOT writable even with `WRITE_SECURE_SETTINGS` (unlike accessibility services), so
   * this is typically a silent no-op on the Portal. The reliable enabler is
   * provisioning's `cmd notification allow_listener` (see provision.sh). Kept as a
   * harmless best-effort for platforms where it does work. Idempotent.
   */
  fun enableMediaListener(context: Context) {
    runCatching {
      val resolver = context.contentResolver
      val comp = ComponentName(context, MediaNotificationListenerService::class.java).flattenToString()
      val current = Settings.Secure.getString(resolver, ENABLED_NOTIFICATION_LISTENERS) ?: ""
      val parts = current.split(':').filter { it.isNotBlank() }
      if (comp !in parts) {
        Settings.Secure.putString(
            resolver, ENABLED_NOTIFICATION_LISTENERS, (parts + comp).joinToString(":"))
      }
    }
  }

  /** Whether [MediaNotificationListenerService] is currently an enabled listener. */
  fun isMediaListenerEnabled(context: Context): Boolean =
      runCatching {
            val comp =
                ComponentName(context, MediaNotificationListenerService::class.java).flattenToString()
            (Settings.Secure.getString(context.contentResolver, ENABLED_NOTIFICATION_LISTENERS) ?: "")
                .split(':')
                .any { it.equals(comp, ignoreCase = true) }
          }
          .getOrDefault(false)

  /** True if we hold WRITE_SECURE_SETTINGS (so self-healing is active). */
  fun canWriteSecureSettings(context: Context): Boolean =
      context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED

  /**
   * Enable or disable our [BarWatchService] accessibility service by editing the secure setting
   * ourselves. Unlike notification listeners (OS-protected on A10), `enabled_accessibility_services`
   * IS writable with WRITE_SECURE_SETTINGS — so the quick-button feature can turn its watcher on
   * only when the user enables it, instead of provisioning leaving an always-on a11y service.
   */
  fun setBarWatchEnabled(context: Context, on: Boolean) {
    runCatching {
      val comp = ComponentName(context, BarWatchService::class.java).flattenToString()
      val cr = context.contentResolver
      val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
      val parts = cur.split(':').filter { it.isNotBlank() && !it.equals(comp, ignoreCase = true) }
      val next = (if (on) parts + comp else parts).joinToString(":")
      // Assert the master accessibility flag even when the service list is unchanged — the list
      // can survive a reboot with the master flag off, which would leave the service inactive.
      // (This doesn't cause a rebind; only rewriting the service list does.)
      if (on) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
      if (next == cur) return // service list already correct — don't rewrite it (avoids a rebind)
      Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
      android.util.Log.i("ImmortalQuickBar", "BarWatch a11y ${if (on) "enabled" else "disabled"}")
    }
        .onFailure { android.util.Log.w("ImmortalQuickBar", "couldn't toggle BarWatch a11y", it) }
  }

  /**
   * Keep [BarWatchService] enabled. It is baseline launcher infrastructure now — it backs the
   * Calls→stock-home bridge (5 system Back presses via accessibility, the reliable way back to
   * Meta's launcher), the phone remote, and the quick-button cluster. Enabled at app start
   * ([ImmortalApp]) and reboot; requires `WRITE_SECURE_SETTINGS`, so a silent no-op without it
   * (i.e. it effectively turns on only on provisioned devices). Idempotent.
   *
   * Note: the cluster overlay is gated separately on [QuickBarConfig.isEnabled] (in [QuickBar]),
   * so the service being on doesn't, by itself, show the quick buttons.
   */
  fun reconcileBarWatch(context: Context) {
    setBarWatchEnabled(context, true)
  }

  /**
   * Force [BarWatchService] to (re)bind if it's enabled in settings but not actually connected.
   * An app update (a real self-update, or `install -r` in dev) unbinds the service while LEAVING
   * it in `enabled_accessibility_services`, so [setBarWatchEnabled]'s "already listed" early-return
   * won't rebind it — the service stays inert and the phone remote's nav buttons and touchpad
   * (which all route through it) go dead, while volume / screensaver / app-launch (which don't)
   * keep working. Toggling our component out of the list and back in makes the system rebind.
   *
   * Call from a USER action (enabling the remote) — NOT from the frequent app-start reconcile,
   * where the service is simply mid-bind and [RemoteInput.available] is briefly false anyway.
   */
  fun ensureBarWatchConnected(context: Context) {
    if (RemoteInput.available()) return // already connected — don't churn the binding
    runCatching {
      val comp = ComponentName(context, BarWatchService::class.java).flattenToString()
      val cr = context.contentResolver
      val others =
          (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
              .split(':')
              .filter { it.isNotBlank() && !it.equals(comp, ignoreCase = true) }
      Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
      // Drop our component, then re-add it after a real beat. The system's settings observer
      // coalesces back-to-back writes into the unchanged final value (no rebind) — it has to
      // observe the service LEAVE the list before it'll bind it on rejoin, so the gap must clear
      // the observer's debounce window (a few hundred ms proved too short; ~2s is reliable).
      Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
      Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
          Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
          Settings.Secure.putString(
              cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + comp).joinToString(":"))
          android.util.Log.i("ImmortalQuickBar", "BarWatch a11y re-bound (was enabled but not connected)")
        }
      }, 2000)
    }
        .onFailure { android.util.Log.w("ImmortalQuickBar", "couldn't re-bind BarWatch a11y", it) }
  }
}
