/*
 * Copyright (c) 2026 Starbright Lab.
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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Process-owned room link. Remote and on-device settings writes both land here through the
 * immortal domain's apply hook, so changing a setting changes the long-lived audio state rather
 * than creating a separate Activity-owned session that can race with the configured desired state.
 */
class IntercomService : Service() {
  private var lan = LanAudio()
  private val retries = Handler(Looper.getMainLooper())

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startInForeground(STATUS_STARTING)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    applyDesiredState()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun applyDesiredState() {
    retries.removeCallbacks(retry)
    val command =
        IntercomPolicy.commandFor(
            ImmortalSettings.intercomMode(applicationContext),
            ImmortalSettings.intercomPeerHost(applicationContext))

    when (command) {
      IntercomCommand.STOP -> {
        lan.stop()
        state = STATE_OFF
        updateForeground(STATUS_OFF)
        stopSelf()
      }
      IntercomCommand.BROADCAST -> startBroadcast()
      IntercomCommand.RECEIVE -> startReceive()
      IntercomCommand.INVALID -> failStably("Set a Portal address before receiving")
    }
  }

  private fun startBroadcast() {
    if (!hasRecordAudio()) {
      state = STATE_ERROR
      failStably("Microphone permission is needed to broadcast")
      return
    }
    // A retry after the user grants RECORD_AUDIO must also promote this foreground service to
    // its microphone type; starting with the playback type was the crash-free fallback.
    startInForeground("Starting broadcast...")
    state = STATE_STARTING
    lan.startBroadcast { started ->
      if (started) {
        retries.removeCallbacks(retry)
        state = STATE_BROADCASTING
        updateForeground(STATUS_BROADCASTING)
      } else {
        state = STATE_ERROR
        failStably("Couldn't start broadcast on port ${IntercomPolicy.PORT}")
      }
    }
  }

  private fun startReceive() {
    val host = ImmortalSettings.intercomPeerHost(applicationContext)
    updateForeground("Connecting to $host...")
    state = STATE_STARTING
    lan.startListening(host) { connected ->
      if (connected) {
        retries.removeCallbacks(retry)
        state = STATE_RECEIVING
        updateForeground("Receiving from $host")
      } else {
        state = STATE_ERROR
        failStably("Couldn't connect to $host")
      }
    }
  }

  /** Keep the configured state observable while a transient condition is repaired. */
  private fun failStably(message: String) {
    lan.stop()
    updateForeground(message)
    retries.removeCallbacks(retry)
    retries.postDelayed(retry, RETRY_MS)
  }

  private val retry = Runnable { applyDesiredState() }

  override fun onDestroy() {
    retries.removeCallbacks(retry)
    lan.stop()
    statusText = STATUS_OFF
    state = STATE_OFF
    super.onDestroy()
  }

  private fun hasRecordAudio(): Boolean =
      androidx.core.content.ContextCompat.checkSelfPermission(
          this, android.Manifest.permission.RECORD_AUDIO) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val channel =
          NotificationChannel(CHANNEL_ID, "Room link", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while this Portal broadcasts or receives room audio"
          }
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
  }

  private fun notification(text: String): Notification {
    val builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
    return builder
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Immortal room link")
        .setContentText(text)
        .setOngoing(true)
        .build()
  }

  private fun startInForeground(text: String) {
    statusText = text
    if (Build.VERSION.SDK_INT >= 30) {
      val type =
          if (ImmortalSettings.intercomMode(this) ==
              ImmortalSettings.INTERCOM_MODE_BROADCAST &&
              hasRecordAudio()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
          } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
          }
      startForeground(NOTIFICATION_ID, notification(text), type)
    } else {
      startForeground(NOTIFICATION_ID, notification(text))
    }
  }

  private fun updateForeground(text: String) {
    statusText = text
    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
  }

  companion object {
    @Volatile var statusText: String = STATUS_OFF
      private set

    @Volatile var state: String = STATE_OFF
      private set

    private const val CHANNEL_ID = "immortal_intercom"
    private const val NOTIFICATION_ID = 5023
    private const val RETRY_MS = 5_000L
    private const val STATUS_OFF = "Off"
    private const val STATUS_STARTING = "Starting..."
    private const val STATUS_BROADCASTING = "Broadcasting this room's audio"

    const val STATE_OFF = "off"
    const val STATE_STARTING = "starting"
    const val STATE_BROADCASTING = "broadcasting"
    const val STATE_RECEIVING = "receiving"
    const val STATE_ERROR = "error"

    fun sync(context: Context) {
      val intent = Intent(context, IntercomService::class.java)
      if (ImmortalSettings.intercomMode(context) == ImmortalSettings.INTERCOM_MODE_OFF) {
        context.stopService(intent)
      } else if (Build.VERSION.SDK_INT >= 26) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
