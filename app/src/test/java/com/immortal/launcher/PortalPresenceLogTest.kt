/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log-tailing presence reader. Background: Portal's own `PresenceManager` beats about every
 * 30s while it sees a person and goes quiet when the room empties, so the signal is the beat's
 * liveness rather than anything it says.
 */
class PortalPresenceLogTest {

  private val now = 1_760_000_000_000L

  private fun line(
      epochSeconds: String,
      text: String = "presence detected",
      tag: String = PortalPresenceLog.TAG_PRESENCE,
  ) = "$epochSeconds  1234  1234 I $tag: $text"

  @Test
  fun `a heartbeat line yields its own timestamp`() {
    assertEquals(1_760_000_000_123L, PortalPresenceLog.beatEpochMs(line("1760000000.123")))
  }

  @Test
  fun `anything the presence service logs counts as a beat`() {
    // The tag is the signal: PresenceManager only runs while it's watching for people, so its
    // heartbeat is "it said something", not any particular wording we'd be guessing at.
    assertEquals(
        1_760_000_000_123L, PortalPresenceLog.beatEpochMs(line("1760000000.123", "still tracking")))
  }

  @Test
  fun `camera-service lines only count when they're about presence`() {
    // This tag logs plenty that has nothing to do with anyone being in the room.
    assertNull(
        PortalPresenceLog.beatEpochMs(
            line("1760000000.123", "opened camera 0", tag = PortalPresenceLog.TAG_CAMERA)))
    assertEquals(
        1_760_000_000_123L,
        PortalPresenceLog.beatEpochMs(
            line("1760000000.123", "presence: true", tag = PortalPresenceLog.TAG_CAMERA)))
  }

  @Test
  fun `the tag's own name doesn't satisfy the message test`() {
    // "PresenceManager" contains "presence", so testing the whole line rather than the message
    // would quietly accept every line from any tag whose NAME mentions presence.
    assertNull(
        PortalPresenceLog.beatEpochMs(
            line("1760000000.123", "opened camera 0", tag = "PresenceManagerCamera")))
  }

  @Test
  fun `malformed lines are ignored rather than throwing`() {
    assertNull(PortalPresenceLog.beatEpochMs("not-a-timestamp I PresenceManager: presence"))
    assertNull(PortalPresenceLog.beatEpochMs(""))
    assertNull(PortalPresenceLog.beatEpochMs(line("0.0")))
    assertNull(PortalPresenceLog.beatEpochMs("1760000000.123 no-tag-separator"))
  }

  @Test
  fun `the startup backlog doesn't count as a live beat`() {
    // logcat replays history on attach; an hour-old beat must not read as someone in the room.
    assertFalse(PortalPresenceLog.isFreshBeat(now - 3_600_000L, now))
    assertTrue(PortalPresenceLog.isFreshBeat(now - 1_000L, now))
  }

  @Test
  fun `a beat stamped slightly in the future is still fresh`() {
    // The log writer and we read the same clock, but not at the same instant.
    assertTrue(PortalPresenceLog.isFreshBeat(now + 500L, now))
  }

  @Test
  fun `silence before the first beat is unknown, not absent`() {
    // The safety property: on a device where these tags never appear (or without READ_LOGS),
    // reporting an empty room forever would be worse than deferring to the dream proxy.
    assertNull(PortalPresenceLog.verdict(lastBeatMs = 0L, nowMs = now))
  }

  @Test
  fun `a recent beat means present, and a gap means the room emptied`() {
    assertEquals(true, PortalPresenceLog.verdict(now - 30_000L, now))
    assertEquals(false, PortalPresenceLog.verdict(now - 60_000L, now))
  }

  @Test
  fun `one missed beat is tolerated before declaring absence`() {
    // Beats land ~30s apart, so the absence window has to clear a single dropped one.
    assertTrue(PortalPresenceLog.ABSENT_MS > 30_000L)
    assertEquals(true, PortalPresenceLog.verdict(now - (PortalPresenceLog.ABSENT_MS - 1), now))
    assertEquals(false, PortalPresenceLog.verdict(now - PortalPresenceLog.ABSENT_MS, now))
  }
}
