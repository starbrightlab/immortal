/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLinkProtocolTest {
  @Test
  fun `header round trips every message kind`() {
    val messages = listOf(
        RoomLinkProtocol.hello(1_000L) to RoomLinkHeader(RoomLinkFrameKind.HELLO, 0, 1_000L),
        RoomLinkProtocol.sync(2_000L) to RoomLinkHeader(RoomLinkFrameKind.SYNC, 0, 2_000L),
        RoomLinkProtocol.frame(7, 3_000L, byteArrayOf(1, 2, 3)) to
            RoomLinkHeader(RoomLinkFrameKind.FRAME, 7, 3_000L),
    )

    messages.forEach { (packet, expected) ->
      assertEquals(expected, RoomLinkProtocol.parse(packet))
    }
  }

  @Test
  fun `frame preserves pcm after the fixed header`() {
    val pcm = byteArrayOf(9, 8, 7, 6)
    val packet = RoomLinkProtocol.frame(11, 42L, pcm)

    assertEquals(RoomLinkProtocol.HEADER_BYTES + pcm.size, packet.size)
    assertTrue(packet.copyOfRange(RoomLinkProtocol.HEADER_BYTES, packet.size).contentEquals(pcm))
  }

  @Test
  fun `invalid magic version or kind are rejected`() {
    val valid = RoomLinkProtocol.sync(1L)
    val badMagic = valid.copyOf().also { it[0] = 0 }
    val badVersion = valid.copyOf().also { it[4] = 2 }
    val badKind = valid.copyOf().also { it[5] = 9 }
    val truncated = valid.copyOfRange(0, RoomLinkProtocol.HEADER_BYTES - 1)

    assertNull(RoomLinkProtocol.parse(badMagic))
    assertNull(RoomLinkProtocol.parse(badVersion))
    assertNull(RoomLinkProtocol.parse(badKind))
    assertNull(RoomLinkProtocol.parse(truncated))
  }

  @Test
  fun `monotonic timestamps may be negative`() {
    val packet = RoomLinkProtocol.frame(3, -42L, byteArrayOf(1))

    assertEquals(RoomLinkHeader(RoomLinkFrameKind.FRAME, 3, -42L), RoomLinkProtocol.parse(packet))
  }

  @Test
  fun `clock maps source timeline with half round trip and startup delay`() {
    val clock = RoomLinkPlaybackClock(
        sourceElapsedNanos = 100L,
        localRequestNanos = 200L,
        localReplyNanos = 400L,
        startupDelayNanos = 50L,
        lateDropNanos = 100L,
    )

    // RTT is 200 ns, so source zero maps to local 200 ns; source 100 is due at 350 ns.
    assertEquals(350L, clock.dueAt(100L))
    assertEquals(RoomLinkPlaybackDecision.Wait(1L), clock.schedule(100L, 300L))
    assertEquals(RoomLinkPlaybackDecision.PlayNow, clock.schedule(100L, 350L))
    assertEquals(RoomLinkPlaybackDecision.PlayNow, clock.schedule(100L, 450L))
    assertEquals(RoomLinkPlaybackDecision.Drop, clock.schedule(100L, 451L))
  }
}
