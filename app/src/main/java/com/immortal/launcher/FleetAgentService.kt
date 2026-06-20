/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * The Fleet Agent: a foreground service that runs [FleetHttpServer]/[FleetRoutes]
 * so a laptop tool can deploy/update/configure this Portal over WiFi.
 *
 * Why this is the reboot-proof channel: adb-over-WiFi can't auto-survive a reboot
 * on these non-root Android 9/10 Portals (the TCP port is a root-only system
 * property), but an app foreground service
 * comes straight back. [ensureRunning] is called from [ImmortalApp.onCreate] and
 * [BootReceiver], the same hooks that re-assert ADB and the screensaver, so the
 * agent is reachable again after a power-cycle with no USB and no root.
 *
 * Off by default: [ensureRunning] no-ops until provisioning enables it, so an
 * un-provisioned device never opens a port.
 */
class FleetAgentService : Service() {

  private var server: FleetHttpServer? = null
  private var wifiLock: WifiManager.WifiLock? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIF_ID, notification())
    acquireWifiLock()
    // So the agent can install apps headlessly (auto-confirm the installer dialog).
    SettingsGuard.enableInstallConfirm(applicationContext)
    val routes = FleetRoutes(applicationContext).also { it.refreshCatalog() }
    val port = FleetConfig.port(this)
    server =
        runCatching { FleetHttpServer(port) { req -> routes.handle(req) }.also { it.start() } }
            .onFailure { Log.w(TAG, "fleet server failed to start on :$port", it) }
            .getOrNull()
  }

  /**
   * Keep WiFi at full power while the agent runs. Without this an idle, screen-off
   * Portal power-saves its radio and the laptop's first request can be dropped or
   * delayed — the device reads as briefly "offline." No permission required.
   */
  private fun acquireWifiLock() {
    runCatching {
      val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      @Suppress("DEPRECATION") // FULL_HIGH_PERF is right for an always-reachable agent on API 28/29
      wifiLock =
          wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "immortal:fleet-agent")?.apply {
            setReferenceCounted(false)
            acquire()
          }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    runCatching { server?.stop() }
    server = null
    runCatching { wifiLock?.release() }
    wifiLock = null
    super.onDestroy()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val ch =
          NotificationChannel(CHANNEL, "Fleet agent", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps WiFi device management reachable"
          }
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }
  }

  private fun notification(): Notification {
    val b =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
    return b.setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Immortal fleet agent")
        .setContentText("Manage this Portal over WiFi")
        .setOngoing(true)
        .build()
  }

  companion object {
    private const val TAG = "ImmortalFleet"
    private const val CHANNEL = "fleet_agent"
    private const val NOTIF_ID = 4711

    /**
     * Pick up any kit-written provisioning, then start the agent if it's enabled.
     * A no-op on an un-provisioned device. Safe to call repeatedly.
     */
    fun ensureRunning(context: Context) {
      runCatching {
            FleetConfig.applyPendingProvisioning(context)
            if (!FleetConfig.isEnabled(context)) return
            val intent = Intent(context, FleetAgentService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
          }
          .onFailure { Log.w(TAG, "ensureRunning failed", it) }
    }
  }
}
