/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** Position ticks keep [NowPlayingHub.current] fresh without waking every listener. */
class NowPlayingHubTest {
  private val song =
      NowPlayingState(PlaybackState.PLAYING, title = "Burn Me Blue", artist = "Remi Wolf", positionMs = 1_000L)

  @Test
  fun positionTick_isTheSameTrack() {
    assertTrue(NowPlayingHub.sameTrack(song, song.copy(positionMs = 4_000L)))
  }

  @Test
  fun pauseNewTrackOrStop_areChanges() {
    assertFalse(NowPlayingHub.sameTrack(song, song.copy(state = PlaybackState.PAUSED)))
    assertFalse(NowPlayingHub.sameTrack(song, song.copy(title = "Liquor Store")))
    assertFalse(NowPlayingHub.sameTrack(song, null))
    assertTrue(NowPlayingHub.sameTrack(null, null))
  }

  @Test
  fun freshArtInstance_isAChange() {
    // Bitmaps compare by identity, which is why MediaSessionReader reuses the downscaled art for
    // the same track: a new copy on every metadata read would defeat the dedupe.
    val art = mock(Bitmap::class.java)
    val withArt = song.copy(artBitmap = art)
    assertTrue(NowPlayingHub.sameTrack(withArt, withArt.copy(positionMs = 9_000L)))
    assertFalse(NowPlayingHub.sameTrack(withArt, withArt.copy(artBitmap = mock(Bitmap::class.java))))
  }

  @Test
  fun publish_notifiesListenersOnlyWhenTheTrackOrStateChanges() {
    NowPlayingHub.publish(null)
    val seen = mutableListOf<NowPlayingState?>()
    val l = NowPlayingHub.Listener { seen.add(it) }
    NowPlayingHub.addListener(l) // replays the current (null) state
    try {
      NowPlayingHub.publish(song)
      NowPlayingHub.publish(song.copy(positionMs = 4_000L))
      NowPlayingHub.publish(song.copy(positionMs = 7_000L))
      // The holder still tracks the live position for on-demand readers (the phone remote).
      assertEquals(7_000L, NowPlayingHub.current?.positionMs)
      val paused = song.copy(state = PlaybackState.PAUSED, positionMs = 7_000L)
      NowPlayingHub.publish(paused)
      assertEquals(listOf(null, song, paused), seen)
    } finally {
      NowPlayingHub.removeListener(l)
      NowPlayingHub.publish(null)
    }
  }
}
