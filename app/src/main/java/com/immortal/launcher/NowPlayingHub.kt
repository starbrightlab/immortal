/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-level holder for the latest now-playing track. The source is the device's own
 * media session ([MediaSessionReader]) — whatever app is playing — so the screensaver
 * card and the home-screen mini-player both read from here and work with any media app.
 *
 * Stays a passive holder: the reader pushes state in via [publish]; consumers read
 * [current] / subscribe via [addListener]. Transport is proxied to the active session.
 */
object NowPlayingHub {
  private const val TAG = "ImmortalNowPlaying"

  fun interface Listener {
    fun onNowPlaying(state: NowPlayingState?)
  }

  private val listeners = CopyOnWriteArrayList<Listener>()

  @Volatile
  var current: NowPlayingState? = null
    private set

  /**
   * Push the latest state from [MediaSessionReader]. Holds the active track (or null
   * when nothing is playing) and notifies listeners. May be called from the reader's
   * worker thread — consumers must marshal to the UI thread themselves (the screensaver
   * `ui.post`s; the header hops via a main Handler).
   */
  fun publish(state: NowPlayingState?) {
    val next = state?.takeIf { it.active }
    current = next
    Log.i(TAG, "now-playing: ${next?.state ?: "—"} ${next?.artist.orEmpty()} — ${next?.title.orEmpty()}")
    listeners.forEach { runCatching { it.onNowPlaying(next) } }
  }

  /** Subscribe (in-process). Immediately replays [current] to the new listener. */
  fun addListener(l: Listener) {
    listeners.add(l)
    l.onNowPlaying(current)
  }

  fun removeListener(l: Listener) = listeners.remove(l)

  // --- transport: forwarded to whatever media session is currently active ------
  fun playPause() = MediaSessionReader.playPause()

  fun next() = MediaSessionReader.next()

  fun previous() = MediaSessionReader.previous()

  fun seek(positionMs: Long) = MediaSessionReader.seek(positionMs)
}
