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
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Live video for Home Assistant: owns the encoder ([CameraStream]) and the [RtspServer] that puts
 * its frames on the wire. Phase 2 of `docs/design/camera-streaming.md`.
 *
 * A foreground service because the camera is held for as long as it runs — this is exactly the
 * case Android's foreground requirement exists for, and the notification is a second, system-level
 * signal that the camera is live, alongside the on-screen indicator.
 *
 * **Streaming does not survive a reboot.** [ImmortalSettings.cameraEnabled] is the consent and
 * persists; whether the stream is actually running does not, so a Portal that loses power comes
 * back with the camera idle rather than quietly broadcasting a room again.
 */
class CameraStreamService : Service() {

  private var stream: CameraStream? = null
  private var server: RtspServer? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIF_ID, notification())
    val s = CameraStream(applicationContext) { nals, ptsUs, keyframe ->
      server?.broadcast(nals, ptsUs, keyframe)
    }
    stream = s
    val srv =
        RtspServer(
            sdp = {
              val sps = s.sps
              val pps = s.pps
              if (sps == null || pps == null) null
              else RtspSdp.videoSdp(s.width, s.height, sps, pps)
            },
            onActiveChanged = { active -> Log.i(TAG, "viewers: $active") },
        )
    server = srv
    val ok = runCatching { srv.start() }.isSuccess && s.start()
    if (!ok) {
      Log.w(TAG, "couldn't start streaming — stopping")
      running = false
      stopSelf()
    } else {
      running = true
      Log.i(TAG, "streaming service up on :${RtspServer.DEFAULT_PORT}")
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    running = false
    runCatching { stream?.stop() }
    runCatching { server?.stop() }
    stream = null
    server = null
    super.onDestroy()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val ch =
          NotificationChannel(CHANNEL, "Camera streaming", NotificationManager.IMPORTANCE_LOW)
              .apply { description = "Shown whenever this Portal's camera is streaming" }
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }
  }

  private fun notification(): Notification {
    val b =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
    return b.setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Immortal · camera streaming")
        .setContentText("This Portal's camera is live")
        .setOngoing(true)
        .build()
  }

  companion object {
    private const val TAG = "ImmortalStream"
    private const val CHANNEL = "camera_stream"
    private const val NOTIF_ID = 4713

    /** True while the service is up — what the Home Assistant switch reports. */
    @Volatile
    var running = false
      private set

    /**
     * Start or stop streaming. Starting requires the device-side camera consent: a Home Assistant
     * command can turn the stream on only within permission already granted on the Portal, never
     * grant it.
     */
    fun sync(context: Context, on: Boolean) {
      val intent = Intent(context, CameraStreamService::class.java)
      if (on && ImmortalSettings.cameraEnabled(context)) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
        else context.startService(intent)
      } else {
        context.stopService(intent)
        running = false
      }
    }
  }
}
