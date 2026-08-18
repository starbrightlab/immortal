/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 6184 packetisation. Worth testing hard: a mistake here yields a stream that almost works —
 * plays in one client, stalls in another — and no amount of staring at a Portal tells you why.
 */
class RtpH264Test {

  /** header byte for a NAL: F=0, NRI=3 (important), type = [type]. */
  private fun nalHeader(type: Int): Byte = ((3 shl 5) or type).toByte()

  private fun nal(type: Int, bodySize: Int): ByteArray =
      ByteArray(bodySize + 1).also {
        it[0] = nalHeader(type)
        for (i in 1..bodySize) it[i] = (i % 251).toByte()
      }

  // --- single NAL ------------------------------------------------------------

  @Test
  fun `a NAL that fits is sent whole, header byte included`() {
    val n = nal(type = 1, bodySize = 100)
    val packets = RtpH264.packetize(n, maxPayload = 1400)
    assertEquals(1, packets.size)
    assertArrayEquals(n, packets[0])
  }

  @Test
  fun `exactly the payload limit still fits in one packet`() {
    val n = nal(type = 1, bodySize = 1399) // 1400 bytes with the header
    assertEquals(1, RtpH264.packetize(n, maxPayload = 1400).size)
  }

  @Test
  fun `an empty NAL produces nothing`() {
    assertTrue(RtpH264.packetize(ByteArray(0)).isEmpty())
  }

  // --- FU-A fragmentation ----------------------------------------------------

  @Test
  fun `a large NAL fragments, and reassembles to the original`() {
    val n = nal(type = 5, bodySize = 5000) // an IDR, comfortably over the limit
    val packets = RtpH264.packetize(n, maxPayload = 1400)
    assertTrue("expected fragmentation", packets.size > 1)

    // Every fragment carries the FU-A indicator with the original NRI preserved.
    packets.forEach {
      assertEquals("FU-A type", RtpH264.NAL_FU_A, it[0].toInt() and 0x1F)
      assertEquals("NRI preserved", 3, (it[0].toInt() and 0x60) shr 5)
      assertTrue("within MTU", it.size <= 1400)
    }

    // Start on the first only, end on the last only.
    assertEquals(0x80, packets.first()[1].toInt() and 0x80)
    assertEquals(0, packets.first()[1].toInt() and 0x40)
    assertEquals(0x40, packets.last()[1].toInt() and 0x40)
    assertEquals(0, packets.last()[1].toInt() and 0x80)
    packets.drop(1).dropLast(1).forEach { assertEquals(0, it[1].toInt() and 0xC0) }

    // The FU header carries the original NAL type on every fragment.
    packets.forEach { assertEquals(5, it[1].toInt() and 0x1F) }

    // Reassembling the fragments rebuilds the original NAL body.
    val rebuilt = packets.flatMap { it.drop(2) }.toByteArray()
    assertArrayEquals(n.copyOfRange(1, n.size), rebuilt)
  }

  @Test
  fun `one byte over the limit still fragments correctly`() {
    val n = nal(type = 1, bodySize = 1400) // 1401 bytes
    val packets = RtpH264.packetize(n, maxPayload = 1400)
    assertEquals(2, packets.size)
    val rebuilt = packets.flatMap { it.drop(2) }.toByteArray()
    assertArrayEquals(n.copyOfRange(1, n.size), rebuilt)
  }

  // --- RTP header ------------------------------------------------------------

  @Test
  fun `the header carries version, sequence, timestamp and ssrc`() {
    val h = RtpH264.header(sequence = 0x1234, timestamp = 0x89ABCDEF, ssrc = 0x11223344, marker = false)
    assertEquals(12, h.size)
    assertEquals(0x80, h[0].toInt() and 0xff) // version 2
    assertEquals(RtpH264.PAYLOAD_TYPE, h[1].toInt() and 0x7f)
    assertEquals(0x12, h[2].toInt() and 0xff)
    assertEquals(0x34, h[3].toInt() and 0xff)
    assertEquals(0x89, h[4].toInt() and 0xff)
    assertEquals(0xEF, h[7].toInt() and 0xff)
    assertEquals(0x11, h[8].toInt() and 0xff)
    assertEquals(0x44, h[11].toInt() and 0xff)
  }

  @Test
  fun `the marker bit is what tells the decoder a frame is complete`() {
    assertEquals(0, RtpH264.header(1, 0, 0, marker = false)[1].toInt() and 0x80)
    assertEquals(0x80, RtpH264.header(1, 0, 0, marker = true)[1].toInt() and 0x80)
  }

  @Test
  fun `microseconds convert to the 90kHz clock`() {
    assertEquals(90_000L, RtpH264.timestamp(1_000_000L)) // one second
    // A 30fps frame is 33333us, which is 2999.97 ticks - it truncates. That's fine and expected:
    // every timestamp derives from the same source clock, so the sub-tick remainder doesn't
    // accumulate into drift the way a per-frame increment would.
    assertEquals(2_999L, RtpH264.timestamp(33_333L))
    assertEquals(3_000L, RtpH264.timestamp(33_334L))
  }

  // --- Annex B splitting -----------------------------------------------------

  @Test
  fun `splits a config buffer into SPS and PPS`() {
    // What MediaCodec hands over as its codec-config buffer: both NALs, start codes between.
    val sps = nal(type = 7, bodySize = 8)
    val pps = nal(type = 8, bodySize = 3)
    val buf = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps
    val nals = RtpH264.splitAnnexB(buf)
    assertEquals(2, nals.size)
    assertArrayEquals(sps, nals[0])
    assertArrayEquals(pps, nals[1])
    assertEquals(7, RtpH264.nalType(nals[0]))
    assertEquals(8, RtpH264.nalType(nals[1]))
  }

  @Test
  fun `handles the three-byte start code too`() {
    val a = nal(type = 7, bodySize = 4)
    val b = nal(type = 8, bodySize = 2)
    val buf = byteArrayOf(0, 0, 1) + a + byteArrayOf(0, 0, 1) + b
    val nals = RtpH264.splitAnnexB(buf)
    assertEquals(2, nals.size)
    assertArrayEquals(a, nals[0])
    assertArrayEquals(b, nals[1])
  }

  @Test
  fun `a buffer with no start code yields nothing`() {
    assertTrue(RtpH264.splitAnnexB(byteArrayOf(9, 9, 9, 9)).isEmpty())
  }
}
