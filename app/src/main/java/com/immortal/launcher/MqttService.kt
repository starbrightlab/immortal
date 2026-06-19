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
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that runs the [MqttPublisher], exposing this Portal to Home Assistant
 * over MQTT Discovery. Mirrors [FleetAgentService]: a long-running, reboot-proof on-device
 * service, off until the user configures a broker.
 *
 * [sync] starts it when enabled+configured and stops it otherwise, so toggling the setting
 * (and boot) just calls one method — like [MultiRoomService.sync].
 */
class MqttService : Service() {

  private var publisher: MqttPublisher? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIF_ID, notification())
    publisher = MqttPublisher(applicationContext).also { it.start() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    // A deliberate disable (the user turned it off) → clear the HA entities. A transient
    // system kill while still enabled → keep them; START_STICKY will reconnect.
    val deliberate = !MqttConfig.isEnabled(this)
    runCatching { publisher?.stop(removeFromHa = deliberate) }
    publisher = null
    super.onDestroy()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val ch =
          NotificationChannel(CHANNEL, "Home Assistant", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Publishes this Portal to Home Assistant over MQTT"
          }
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }
  }

  private fun notification(): Notification {
    val b =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
    return b.setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Immortal · Home Assistant")
        .setContentText("Publishing this Portal to Home Assistant")
        .setOngoing(true)
        .build()
  }

  companion object {
    private const val CHANNEL = "mqtt_publisher"
    private const val NOTIF_ID = 4712

    /** Start the publisher when enabled+configured, stop it otherwise. Safe to call repeatedly. */
    fun sync(context: Context) {
      val intent = Intent(context, MqttService::class.java)
      if (MqttConfig.isEnabled(context) && MqttConfig.isConfigured(context)) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
        else context.startService(intent)
      } else {
        context.stopService(intent)
      }
    }
  }
}
