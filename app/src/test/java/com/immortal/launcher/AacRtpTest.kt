/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AAC over RTP. Failures here are quiet ones — a stream that connects and plays silence, or plays
 * at the wrong speed — so the wire format is pinned rather than eyeballed.
 */
class AacRtpTest {

  @Test
  fun `the config encodes AAC-LC, the rate index and the channel count`() {
    // 16 kHz mono: object type 2, rate index 8, 1 channel.
    // 00010 1000 0001 000 -> 0x14 0x08
    assertArrayEquals(byteArrayOf(0x14, 0x08), AacRtp.audioSpecificConfig(16000, 1))
    assertEquals("1408", AacRtp.configHex(16000, 1))
  }

  @Test
  fun `44_1kHz stereo encodes differently, as it must`() {
    // rate index 4, 2 channels -> 00010 0100 0010 000 = 0x12 0x10
    assertEquals("1210", AacRtp.configHex(44100, 2))
  }

  @Test
  fun `the rate index is an index, not the rate`() {
    assertEquals(3, AacRtp.sampleRateIndex(48000))
    assertEquals(8, AacRtp.sampleRateIndex(16000))
    assertEquals(-1, AacRtp.sampleRateIndex(12345))
  }

  @Test
  fun `a packet carries the AU header then the frame, unaltered`() {
    val frame = ByteArray(200) { (it % 251).toByte() }
    val packet = AacRtp.packetize(frame)
    assertEquals(204, packet.size)
    assertEquals(0x00, packet[0].toInt())
    assertEquals(0x10, packet[1].toInt()) // one 16-bit AU header
    // 13-bit size, 3-bit index: 200 << 3 = 1600 = 0x0640
    assertEquals(0x06, packet[2].toInt() and 0xff)
    assertEquals(0x40, packet[3].toInt() and 0xff)
    assertArrayEquals(frame, packet.copyOfRange(4, packet.size))
  }

  @Test
  fun `audio timestamps run at the sample rate, not 90kHz`() {
    // One second of 16 kHz audio is 16000 ticks - using the video clock here would desync sound.
    assertEquals(16_000L, AacRtp.timestamp(1_000_000L, 16000))
    assertEquals(1_024L, AacRtp.timestamp(64_000L, 16000)) // one AAC frame
  }

  @Test
  fun `the fmtp line agrees with the header size the packetiser writes`() {
    val sdp = AacRtp.audioSdp(16000, 1, trackId = 1)
    assertTrue(sdp.contains("m=audio 0 RTP/AVP 97\r\n"))
    assertTrue(sdp.contains("a=rtpmap:97 mpeg4-generic/16000/1\r\n"))
    assertTrue("13+3 split must match packetize()", sdp.contains("sizelength=13;indexlength=3"))
    assertTrue(sdp.contains("mode=AAC-hbr"))
    assertTrue(sdp.contains("config=1408"))
    assertTrue(sdp.contains("a=control:trackID=1"))
  }
}

/**
 * The rule that decides whether captured audio may leave the device. Small, but it's the one that
 * has to be right: Home Assistant shows a mic-mute switch, and a stream that keeps sending sound
 * while that switch says muted is both a lie and a privacy surprise.
 */
class AudioMuteGateTest {

  @Test
  fun `muting the microphone stops audio leaving the device`() {
    assertFalse(AudioStream.shouldEmit(micMuted = true, holdsMic = true))
  }

  @Test
  fun `audio flows only while unmuted and holding the mic`() {
    assertTrue(AudioStream.shouldEmit(micMuted = false, holdsMic = true))
    assertFalse(AudioStream.shouldEmit(micMuted = false, holdsMic = false))
    assertFalse(AudioStream.shouldEmit(micMuted = true, holdsMic = false))
  }
}

/**
 * Microphone arbitration. Before this nothing arbitrated at all — the intercom and voice notes
 * both opened the mic directly, and whoever asked second silently got nothing.
 */
class MicOwnerTest {

  @Before fun reset() = MicOwner.resetForTest()

  @Test
  fun `a free microphone is granted`() {
    assertTrue(MicOwner.acquire("stream", MicOwner.PRIORITY_STREAM))
    assertEquals("stream", MicOwner.holder)
  }

  @Test
  fun `someone speaking takes it from a background stream`() {
    MicOwner.acquire("stream", MicOwner.PRIORITY_STREAM)
    assertTrue(MicOwner.acquire("intercom", MicOwner.PRIORITY_INTERCOM))
    assertEquals("intercom", MicOwner.holder)
    // The preempted owner can tell, which is how it knows to stop capturing.
    assertFalse(MicOwner.holds("stream"))
  }

  @Test
  fun `a background stream cannot take it from someone speaking`() {
    MicOwner.acquire("intercom", MicOwner.PRIORITY_INTERCOM)
    assertFalse(MicOwner.acquire("stream", MicOwner.PRIORITY_STREAM))
    assertEquals("intercom", MicOwner.holder)
  }

  @Test
  fun `equal priority does not preempt the incumbent`() {
    MicOwner.acquire("note", MicOwner.PRIORITY_NOTE)
    assertFalse(MicOwner.acquire("other", MicOwner.PRIORITY_NOTE))
    assertEquals("note", MicOwner.holder)
  }

  @Test
  fun `re-acquiring what you already hold is fine`() {
    MicOwner.acquire("stream", MicOwner.PRIORITY_STREAM)
    assertTrue(MicOwner.acquire("stream", MicOwner.PRIORITY_STREAM))
  }

  @Test
  fun `a preempted owner releasing does not free the new holder`() {
    // The bug this prevents: the stream's cleanup running after the intercom took over, and
    // handing the microphone away from the person actually talking.
    MicOwner.acquire("stream", MicOwner.PRIORITY_STREAM)
    MicOwner.acquire("intercom", MicOwner.PRIORITY_INTERCOM)
    MicOwner.release("stream")
    assertEquals("intercom", MicOwner.holder)
  }

  @Test
  fun `releasing frees it for the next claim`() {
    MicOwner.acquire("intercom", MicOwner.PRIORITY_INTERCOM)
    MicOwner.release("intercom")
    assertEquals(null, MicOwner.holder)
    assertTrue(MicOwner.acquire("stream", MicOwner.PRIORITY_STREAM))
  }
}
