/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as MediaPlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Reads the device's *native* media session — whatever app is playing — and feeds
 * [NowPlayingHub]. This is the source of truth for the screensaver now-playing card
 * and the home-screen mini-player. It also
 * exposes transport so the header buttons can play/pause/skip the active app.
 *
 * Access to active sessions is gated on being an enabled notification listener (see
 * [MediaNotificationListenerService], enabled at provisioning); we (re)attach when
 * that service connects. All framework callbacks land on a dedicated [HandlerThread]
 * so nothing touches the session APIs from the main thread; the hub then marshals to
 * its consumers as before.
 */
object MediaSessionReader {
  private const val TAG = "ImmortalNowPlaying"
  private const val ART_MAX_EDGE = 320 // px — covers the 72dp card art and the header thumb

  private var started = false
  private lateinit var appContext: Context
  private var manager: MediaSessionManager? = null
  private var component: ComponentName? = null
  private var handler: Handler? = null

  // Mutated only on the handler thread; read from the UI thread for transport.
  @Volatile private var active: MediaController? = null

  // The session we last saw actually PLAYING. We keep showing it once it's paused (so
  // "you paused your music, the card stays" works), but we do NOT resurrect a stale
  // paused session we never saw playing — e.g. an old Spotify pause surfacing when the
  // multi-room group stops. Cleared implicitly: a paused session not matching this is
  // ignored, and the next thing to play overwrites it.
  private var lastPlayingToken: MediaSession.Token? = null

  // Every active controller we've registered [genericCb] on, so we catch any
  // session's state/metadata transitions (not just the one we're currently showing).
  private val watched = HashMap<MediaSession.Token, MediaController>()

  // Any session changing → re-query everything and re-pick. One instance shared
  // across all controllers; reselect figures out which one wins.
  private val genericCb =
      object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: MediaPlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = refresh()
      }

  private val sessionsChanged =
      MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list ?: emptyList()) }

  /** Start reading sessions. Called once from [ImmortalApp.onCreate]; idempotent. */
  fun init(context: Context) {
    if (started) return
    started = true
    appContext = context.applicationContext
    component = ComponentName(appContext, MediaNotificationListenerService::class.java)
    manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    HandlerThread("media-session").also { it.start(); handler = Handler(it.looper) }
    // Best-effort self-enable (typically a no-op on the Portal — see SettingsGuard;
    // the listener is really enabled at provisioning). Then try to attach: succeeds
    // immediately if the listener is already enabled+bound, else onListenerConnected
    // retries when it binds.
    SettingsGuard.enableMediaListener(appContext)
    handler?.post { attach() }
  }

  /** The listener bound — the authoritative "session access is now permitted" signal. */
  fun onListenerConnected(service: MediaNotificationListenerService) {
    handler?.post { attach() }
  }

  fun onListenerDisconnected() {
    handler?.post {
      watched.values.forEach { runCatching { it.unregisterCallback(genericCb) } }
      watched.clear()
      active = null
      NowPlayingHub.publish(null)
    }
  }

  // --- transport (called from the UI thread) ----------------------------------

  fun playPause() {
    val c = active ?: return
    if (c.playbackState?.state == MediaPlaybackState.STATE_PLAYING) c.transportControls.pause()
    else c.transportControls.play()
  }

  fun next() = active?.transportControls?.skipToNext() ?: Unit

  fun previous() = active?.transportControls?.skipToPrevious() ?: Unit

  fun seek(positionMs: Long) = active?.transportControls?.seekTo(positionMs) ?: Unit

  // --- session plumbing (all on the handler thread) ---------------------------

  private fun attach() {
    val mgr = manager ?: return
    val comp = component ?: return
    // getActiveSessions throws SecurityException until the listener is enabled+bound;
    // swallow it and stay dormant — onListenerConnected will retry.
    runCatching {
      mgr.removeOnActiveSessionsChangedListener(sessionsChanged)
      mgr.addOnActiveSessionsChangedListener(sessionsChanged, comp) // delivers on this thread
      onSessions(mgr.getActiveSessions(comp))
    }
        .onFailure { Log.i(TAG, "media sessions not available yet: ${it.message}") }
  }

  /** Re-query the active sessions and re-pick (from a per-session callback). */
  private fun refresh() {
    val mgr = manager ?: return
    val comp = component ?: return
    runCatching { onSessions(mgr.getActiveSessions(comp)) }
  }

  /** Sync our per-controller watchers to [controllers], then pick what to show. */
  private fun onSessions(controllers: List<MediaController>) {
    val byToken = controllers.associateBy { it.sessionToken }
    // Drop watchers for sessions that have gone away.
    watched.keys.filter { it !in byToken }.forEach { token ->
      watched.remove(token)?.let { c -> runCatching { c.unregisterCallback(genericCb) } }
    }
    // Watch any new session so we catch it transitioning into playback.
    byToken.forEach { (token, c) ->
      if (token !in watched) {
        watched[token] = c
        runCatching { c.registerCallback(genericCb, handler!!) }
      }
    }
    reselect(controllers)
  }

  /**
   * Pick the active session (playing, else paused) and publish it. When several sessions
   * are in the same state, prefer one that ISN'T our own [MultiRoomService] relay: on the
   * Portal driving playback, the Music Assistant app and our relay both publish the same
   * track, but the MA app's session has working transport — our relay is the fallback
   * that fills in the *other* rooms, where it's the only source.
   */
  private fun reselect(controllers: List<MediaController>) {
    fun isOwnRelay(c: MediaController) = c.packageName == appContext.packageName
    // Prefer a PLAYING session (a real app over our own relay), and remember it.
    val playing =
        controllers
            .filter { it.playbackState?.state == MediaPlaybackState.STATE_PLAYING }
            .let { inState -> inState.firstOrNull { !isOwnRelay(it) } ?: inState.firstOrNull() }
    val chosen =
        if (playing != null) {
          lastPlayingToken = playing.sessionToken
          playing
        } else {
          // No one's playing: keep showing ONLY the session we last saw playing, now
          // paused — never a stale paused session we never saw play.
          controllers.firstOrNull {
            it.playbackState?.state == MediaPlaybackState.STATE_PAUSED &&
                it.sessionToken == lastPlayingToken
          }
        }
    active = chosen
    Log.i(
        TAG,
        "sessions=${controllers.size} chosen=${chosen?.packageName} state=${chosen?.playbackState?.state}")
    if (chosen != null) publishFrom(chosen) else NowPlayingHub.publish(null)
  }

  /** Map a controller's metadata + playback state into a [NowPlayingState] and publish. */
  private fun publishFrom(c: MediaController) {
    val md = c.metadata
    val ps = c.playbackState
    val state =
        when (ps?.state) {
          MediaPlaybackState.STATE_PLAYING,
          MediaPlaybackState.STATE_BUFFERING -> PlaybackState.PLAYING
          MediaPlaybackState.STATE_PAUSED -> PlaybackState.PAUSED
          MediaPlaybackState.STATE_STOPPED,
          MediaPlaybackState.STATE_NONE,
          null -> PlaybackState.STOPPED
          else -> PlaybackState.IDLE
        }
    fun str(key: String) = md?.getString(key)?.takeIf { it.isNotBlank() }
    val title = str(MediaMetadata.METADATA_KEY_TITLE) ?: str(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    val artist =
        str(MediaMetadata.METADATA_KEY_ARTIST)
            ?: str(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: str(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    val album = str(MediaMetadata.METADATA_KEY_ALBUM)
    val rawArt =
        md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    val artBitmap = rawArt?.let { downscale(it) }
    val artUri =
        if (artBitmap != null) null
        else
            str(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: str(MediaMetadata.METADATA_KEY_ART_URI)
                ?: str(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

    NowPlayingHub.publish(
        NowPlayingState(
            state = state,
            title = title.orEmpty(),
            artist = artist.orEmpty(),
            album = album.orEmpty(),
            artUrl = artUri.orEmpty(),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = ps?.position ?: 0L,
            source = c.packageName,
            artBitmap = artBitmap))
  }

  /** Scale album art down to [ART_MAX_EDGE] into a new bitmap; never recycle the
   *  framework's source (it belongs to the session's metadata). */
  private fun downscale(src: Bitmap): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= ART_MAX_EDGE) return src
    val ratio = ART_MAX_EDGE.toFloat() / longest
    return Bitmap.createScaledBitmap(
        src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
  }
}
