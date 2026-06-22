/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * The phone remote's media surface, bridged onto the launcher's existing now-playing stack
 * ([NowPlayingHub] / [MediaSessionReader]). The remote reads [stateJson] to render a now-playing
 * card, fetches [artPng] for the cover, and posts transport via [command] — all reusing the same
 * media-session controller that drives the on-TV header mini-player. No new permissions: it rides
 * the notification-listener access already held for now-playing.
 */
object RemoteMedia {
  // Lazy so merely loading this object (e.g. the pure stateJson serializer in tests) doesn't
  // touch the Android main looper; only command() — on-device — ever needs it.
  private val main by lazy { Handler(Looper.getMainLooper()) }

  /** Current now-playing as JSON for `/remote/nowplaying`. `{active:false}` when nothing plays. */
  fun stateJson(): JSONObject = stateJson(NowPlayingHub.current)

  /** Pure serializer (testable): the live [stateJson] delegates here with [NowPlayingHub.current]. */
  internal fun stateJson(s: NowPlayingState?): JSONObject {
    if (s == null) return JSONObject().put("active", false)
    return JSONObject()
        .put("active", true)
        .put("title", s.title)
        .put("artist", s.artist)
        .put("album", s.album)
        .put("durationMs", s.durationMs)
        .put("positionMs", s.positionMs)
        .put("playing", s.state == PlaybackState.PLAYING)
        .put("source", s.source)
        .put("hasArt", s.artBitmap != null || s.artUrl.isNotBlank())
        // Changes only when the track changes, so the phone caches the cover and refetches
        // /remote/art (a fresh ?v=) just once per track instead of every poll.
        .put("artVersion", artVersion(s))
  }

  /**
   * Dispatch a transport command. Posts to the main thread because [MediaSessionReader]'s
   * transport is documented as UI-thread-only. Returns whether a session is currently active
   * (so the route can report 409 when there's nothing to control). [positionMs] is used by "seek".
   */
  fun command(action: String, positionMs: Long): Boolean {
    val active = NowPlayingHub.current != null
    main.post {
      when (action) {
        "playpause" -> NowPlayingHub.playPause()
        "next" -> NowPlayingHub.next()
        "prev", "previous" -> NowPlayingHub.previous()
        "seek" -> NowPlayingHub.seek(positionMs)
      }
    }
    return active
  }

  /**
   * The current cover as PNG bytes, or null if there's no art. Prefers the in-memory bitmap the
   * session already gave us; otherwise resolves the metadata's art **URI** — which is often a
   * device-local `content://`/`file://` the phone itself can't fetch, so the Portal reads it here
   * and relays the bytes. `http(s)://` URIs are fetched directly. Bounded + downscaled.
   */
  fun artPng(context: Context): ByteArray? {
    val s = NowPlayingHub.current ?: return null
    val bmp = s.artBitmap ?: MediaArt.resolveUri(context, s.artUrl) ?: return null
    return runCatching {
          ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
          }
        }
        .getOrNull()
  }

  private fun artVersion(s: NowPlayingState): Int =
      listOf(s.title, s.artist, s.album, s.artUrl).joinToString("").hashCode()
}
