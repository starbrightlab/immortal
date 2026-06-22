package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaTest {

  @Test
  fun stateJson_nullIsInactive() {
    val j = RemoteMedia.stateJson(null)
    assertFalse(j.getBoolean("active"))
  }

  @Test
  fun stateJson_playingTrackSerialisesFields() {
    val s =
        NowPlayingState(
            state = PlaybackState.PLAYING,
            title = "Song",
            artist = "Band",
            album = "Record",
            durationMs = 240_000,
            positionMs = 30_000,
            source = "com.spotify.music")
    val j = RemoteMedia.stateJson(s)
    assertTrue(j.getBoolean("active"))
    assertTrue(j.getBoolean("playing"))
    assertEquals("Song", j.getString("title"))
    assertEquals("Band", j.getString("artist"))
    assertEquals("Record", j.getString("album"))
    assertEquals(240_000, j.getLong("durationMs"))
    assertEquals(30_000, j.getLong("positionMs"))
  }

  @Test
  fun stateJson_pausedIsNotPlaying() {
    val j = RemoteMedia.stateJson(NowPlayingState(state = PlaybackState.PAUSED, title = "x"))
    assertTrue(j.getBoolean("active"))
    assertFalse(j.getBoolean("playing"))
  }

  @Test
  fun stateJson_hasArtFromUriEvenWithoutBitmap() {
    // A URI-only cover (no embedded bitmap) still counts as art — the Portal resolves it.
    val withUri = RemoteMedia.stateJson(NowPlayingState(PlaybackState.PLAYING, title = "t", artUrl = "content://media/art/1"))
    assertTrue(withUri.getBoolean("hasArt"))
    val none = RemoteMedia.stateJson(NowPlayingState(PlaybackState.PLAYING, title = "t"))
    assertFalse(none.getBoolean("hasArt"))
  }

  @Test
  fun artVersion_stableForSameTrackChangesAcrossTracks() {
    fun ver(title: String) =
        RemoteMedia.stateJson(NowPlayingState(PlaybackState.PLAYING, title = title, artist = "A")).getInt("artVersion")
    // Position/playback changes shouldn't churn the cover; the title (track) changing should.
    assertEquals(ver("Track One"), ver("Track One"))
    assertNotEquals(ver("Track One"), ver("Track Two"))
  }
}
