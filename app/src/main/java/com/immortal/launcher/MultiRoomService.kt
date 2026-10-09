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
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as MediaPlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Multi-room now-playing: when the Portal is part of a Snapcast group, this foreground
 * service reads what the group is playing off the snapserver ([SnapcastControlClient])
 * and publishes it as a device MEDIA SESSION. The launcher's own [MediaSessionReader]
 * then surfaces it on the now-playing card — on EVERY Portal, not just the one running
 * the Music Assistant app — and the Portal's system media controls show it too.
 *
 * The audio itself is played by the separate Snapcast app; this only speaks Snapcast's
 * control protocol (for metadata) and Android's media APIs. Transport (play/pause/next)
 * is forwarded to Music Assistant via [MaControl] — MA's Snapcast stream is canPlay=false,
 * so the snapserver control channel can't drive playback, only MA's own API can.
 */
class MultiRoomService : Service() {
  private var client: SnapcastControlClient? = null
  private val art = java.util.concurrent.Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())
  private var session: MediaSession? = null

  // The last track we saw playing, kept so we can hold the card as "paused" after the
  // user pauses (the snapserver drops the group to an idle stream on pause, so it can't
  // tell us paused-vs-stopped on its own).
  @Volatile private var lastPlaying: NowPlayingState? = null
  @Volatile private var lastCover: Bitmap? = null
  @Volatile private var userPaused = false

  // Two now-playing sources, merged by [MultiRoomNowPlaying]: Snapcast carries metadata for MA's
  // own library/radio streams (via control.py); MA's API fills in for streams that don't — an
  // AirPlay receiver bridged to the group surfaces a bare Snapcast stream, so its metadata only
  // exists in MA's player state. We dedupe so the MA poll doesn't re-fetch art each tick.
  @Volatile private var snapState: NowPlayingState? = null
  @Volatile private var maState: NowPlayingState? = null
  @Volatile private var lastEffectiveKey: String? = null
  @Volatile private var polling = false
  private var maPoller: Thread? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    session = MediaSession(this, "ImmortalMultiRoom").apply { setCallback(transportCallback) }
    startForeground(NOTIF_ID, notification(null, null, "Multi-room audio"))
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    connect()
    return START_STICKY
  }

  private fun connect() {
    val host = ImmortalSettings.snapcastHost(this)
    if (host.isBlank()) {
      MultiRoomStatus.text = "No server set"
      updateStatus("No multi-room server set")
      return
    }
    MultiRoomStatus.text = "Connecting to $host…"
    client?.stop()
    client =
        SnapcastControlClient(
                host,
                PORT,
                { localIp() },
                { connected ->
                  main.post {
                    MultiRoomStatus.text =
                        if (connected) "Connected to $host" else "Connecting to $host…"
                  }
                }) { s -> onSnapState(s) }
            .also { it.start() }
    startMaPoller()
    updateStatus("Linked to $host")
  }

  /** This device's LAN IPv4 — used to find our own snapclient in the server topology so
   *  we only surface the group THIS Portal is actually playing. */
  private fun localIp(): String? =
      runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()
            .toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull {
              !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress
            }
            ?.hostAddress
      }
          .getOrNull()

  private fun onSnapState(s: NowPlayingState) = main.post { snapState = s; applyEffective() }

  private fun onMaState(s: NowPlayingState) = main.post { maState = s; applyEffective() }

  /**
   * Merge the two sources and push the result to the session, skipping no-op repeats (the MA
   * poll re-reports the same track every few seconds). Runs on main so the dedupe is race-free.
   */
  private fun applyEffective() {
    val eff = MultiRoomNowPlaying.effective(snapState, maState)
    val key = "${eff.state}|${eff.title}|${eff.artist}|${eff.artUrl}"
    if (key == lastEffectiveKey) return
    lastEffectiveKey = key
    onState(eff)
  }

  /** Each MA poll is a fresh WebSocket + auth + full `players/all` read, so only poll fast while
   *  something is actually playing; idle, or with the screen off, a slower check is plenty. */
  private fun maPollDelay(s: NowPlayingState): Long {
    val pm = getSystemService(android.os.PowerManager::class.java)
    return when {
      pm?.isInteractive == false -> POLL_MA_SCREEN_OFF_MS
      s.active -> POLL_MA_MS
      else -> POLL_MA_IDLE_MS
    }
  }

  /** Poll MA for our player's current track (the AirPlay fill-in). Always runs while the relay
   *  is connected: [MaControl.nowPlaying] returns IDLE with no network when MA creds aren't set,
   *  so Snapcast-only Portals are unaffected — and creds entered later are picked up on the next
   *  poll without needing the service (or app) to restart. */
  private fun startMaPoller() {
    stopMaPoller()
    polling = true
    maPoller =
        Thread(
                {
                  while (polling) {
                    val s =
                        runCatching {
                              MaControl.nowPlaying(
                                  applicationContext, client?.ourClientId, client?.ourClientName)
                            }
                            .getOrDefault(NowPlayingState(PlaybackState.IDLE))
                    if (polling) onMaState(s)
                    runCatching { Thread.sleep(maPollDelay(s)) }
                  }
                },
                "ma-nowplaying")
            .apply {
              isDaemon = true
              start()
            }
  }

  private fun stopMaPoller() {
    polling = false
    maPoller = null
  }

  /** Fetch art off the control thread, then mirror the track into the session on main. */
  private fun onState(s: NowPlayingState) {
    art.execute {
      val cover = if (s.artUrl.isNotBlank()) MultiRoomArt.fetch(s.artUrl) else null
      main.post { updateSession(s, cover) }
    }
  }

  // --- media session: the surface the launcher (and the OS) read ---------------

  private val transportCallback =
      object : MediaSession.Callback() {
        override fun onPlay() = onTransport("play")

        override fun onPause() = onTransport("pause")

        override fun onSkipToNext() = onTransport("next")

        override fun onSkipToPrevious() = onTransport("previous")
      }

  /**
   * Device media controls land here and are forwarded to Music Assistant's API for THIS
   * Portal's player (resolved from our snapclient id/name). MA's Snapcast stream reports
   * canPlay=false, so the snapserver control channel can't start/stop playback — only
   * MA's own API can.
   */
  private fun onTransport(cmd: String) {
    Log.i(TAG, "transport '$cmd' → Music Assistant")
    when (cmd) {
      // Pausing from our own card: hold it as paused so the control flips to "resume"
      // (the snapserver drops the group to its idle stream on pause). Flip the session
      // immediately for a snappy UI; the MA command follows.
      "pause" -> {
        userPaused = true
        lastPlaying?.let { p -> main.post { session?.let { present(it, p, lastCover, playing = false) } } }
      }
      "play" -> userPaused = false
    }
    MaControl.command(applicationContext, cmd, client?.ourClientId, client?.ourClientName)
  }

  private fun updateSession(s: NowPlayingState, cover: Bitmap?) {
    val sess = session ?: return
    when (s.state) {
      PlaybackState.PLAYING -> {
        lastPlaying = s
        lastCover = cover
        userPaused = false
        present(sess, s, cover, playing = true)
      }
      PlaybackState.PAUSED -> present(sess, s, cover, playing = false)
      else -> {
        // Snapserver idle. If WE paused from here, keep the card up as paused so the user
        // can resume; otherwise the group genuinely stopped → drop the card.
        val held = lastPlaying
        if (userPaused && held != null) {
          present(sess, held, lastCover, playing = false)
        } else {
          sess.setPlaybackState(
              MediaPlaybackState.Builder().setState(MediaPlaybackState.STATE_STOPPED, 0L, 0f).build())
          sess.isActive = false
          updateStatus("Linked")
        }
      }
    }
  }

  /** Publish [s] into the media session (metadata + state + active), playing or paused. */
  private fun present(sess: MediaSession, s: NowPlayingState, cover: Bitmap?, playing: Boolean) {
    val md =
        MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
    if (cover != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
    sess.setMetadata(md.build())
    val actions =
        MediaPlaybackState.ACTION_PLAY or
            MediaPlaybackState.ACTION_PAUSE or
            MediaPlaybackState.ACTION_PLAY_PAUSE or
            MediaPlaybackState.ACTION_SKIP_TO_NEXT or
            MediaPlaybackState.ACTION_SKIP_TO_PREVIOUS
    sess.setPlaybackState(
        MediaPlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (playing) MediaPlaybackState.STATE_PLAYING else MediaPlaybackState.STATE_PAUSED,
                s.positionMs,
                if (playing) 1f else 0f)
            .build())
    sess.isActive = true
    updateNowPlaying(s, cover)
  }

  override fun onDestroy() {
    MultiRoomStatus.text = "Off"
    stopMaPoller()
    client?.stop()
    art.shutdownNow()
    runCatching {
      session?.isActive = false
      session?.release()
    }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  // --- notification -----------------------------------------------------------

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      getSystemService(NotificationManager::class.java)
          ?.createNotificationChannel(
              NotificationChannel(CHANNEL, "Multi-room audio", NotificationManager.IMPORTANCE_MIN))
    }
  }

  private fun notification(s: NowPlayingState?, cover: Bitmap?, status: String?): Notification {
    val b =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
    b.setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(s?.title?.takeIf { it.isNotBlank() } ?: "Multi-room audio")
        .setContentText(s?.artist?.takeIf { it.isNotBlank() } ?: status ?: "")
        .setOngoing(true)
    if (cover != null) b.setLargeIcon(cover)
    session?.sessionToken?.let { token -> b.style = Notification.MediaStyle().setMediaSession(token) }
    return b.build()
  }

  private fun updateStatus(text: String) {
    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification(null, null, text))
  }

  private fun updateNowPlaying(s: NowPlayingState, cover: Bitmap?) {
    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification(s, cover, null))
  }

  companion object {
    private const val TAG = "ImmortalNowPlaying"
    private const val CHANNEL = "immortal_multiroom"
    private const val NOTIF_ID = 5022
    private const val PORT = 1705 // snapserver JSON-RPC control port
    private const val POLL_MA_MS = 4000L // how often to poll MA for the AirPlay-source track
    private const val POLL_MA_IDLE_MS = 12_000L // …while nothing is playing
    private const val POLL_MA_SCREEN_OFF_MS = 30_000L // …while the screen is off

    /** The standalone Snapcast player that actually renders the synced audio. The
     *  multi-room now-playing UI is gated on this being installed. */
    const val SNAPCAST_PACKAGE = "de.badaix.snapcast"

    /** Start the relay if multi-room is enabled and a server is configured; else stop it. */
    fun sync(context: Context) {
      val on =
          ImmortalSettings.multiRoomEnabled(context) &&
              ImmortalSettings.snapcastHost(context).isNotBlank()
      val i = Intent(context, MultiRoomService::class.java)
      if (on) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
        else context.startService(i)
      } else {
        context.stopService(i)
      }
    }
  }
}
