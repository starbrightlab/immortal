/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.URL
import org.json.JSONObject

/**
 * The phone remote's media surface, bridged onto the launcher's existing now-playing stack
 * ([NowPlayingHub] / [MediaSessionReader]). The remote reads [stateJson] to render a now-playing
 * card, fetches [artPng] for the cover, and posts transport via [command] — all reusing the same
 * media-session controller that drives the on-TV header mini-player. No new permissions: it rides
 * the notification-listener access already held for now-playing.
 */
object RemoteMedia {
  /** Cap on re-encoded URI art; matches the on-TV art size closely enough for a phone card. */
  private const val MAX_EDGE = 384

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
    val bmp = s.artBitmap ?: resolveUriArt(context, s.artUrl) ?: return null
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

  /** Load art bytes from a metadata URI and decode them downscaled. Any failure → null (placeholder). */
  private fun resolveUriArt(context: Context, url: String): Bitmap? {
    if (url.isBlank()) return null
    return runCatching {
          val bytes =
              if (url.startsWith("http://") || url.startsWith("https://")) {
                val conn = URL(url).openConnection()
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.getInputStream().use { it.readBytes() }
              } else {
                // content://, file://, android.resource:// — device-local, only we can read it.
                context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
              }
          bytes?.let { decodeDownscaled(it) }
        }
        .getOrNull()
  }

  /** Decode [bytes] with an inSampleSize that keeps the longest edge near [MAX_EDGE]. */
  private fun decodeDownscaled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    val opts =
        BitmapFactory.Options().apply {
          inSampleSize = if (longest > MAX_EDGE) Integer.highestOneBit(longest / MAX_EDGE) else 1
        }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
  }
}
