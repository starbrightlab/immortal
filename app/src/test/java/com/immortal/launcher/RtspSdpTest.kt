/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDP is how a client decides whether it can play the stream at all, and a mistake here shows
 * up as a black picture with no error — so it's checked byte for byte rather than by eye.
 */
class RtspSdpTest {

  // A plausible constrained-baseline SPS: NAL header, then profile_idc / constraints / level_idc.
  private val sps = byteArrayOf(0x67, 0x42.toByte(), 0xC0.toByte(), 0x1F, 0x11, 0x22)
  private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

  @Test
  fun `base64 matches the standard, including padding`() {
    assertEquals("", RtspSdp.base64(ByteArray(0)))
    assertEquals("TQ==", RtspSdp.base64("M".toByteArray()))
    assertEquals("TWE=", RtspSdp.base64("Ma".toByteArray()))
    assertEquals("TWFu", RtspSdp.base64("Man".toByteArray()))
    assertEquals("cGxlYXN1cmUu", RtspSdp.base64("pleasure.".toByteArray()))
  }

  @Test
  fun `base64 handles bytes above 127`() {
    // Signed-byte sloppiness here would corrupt the parameter sets and break decoding.
    assertEquals("/w==", RtspSdp.base64(byteArrayOf(0xFF.toByte())))
    assertEquals("gID/", RtspSdp.base64(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0xFF.toByte())))
  }

  @Test
  fun `profile-level-id comes from the SPS, not from what we asked for`() {
    // 0x42 = baseline, 0xC0 = constrained, 0x1F = level 3.1.
    assertEquals("42c01f", RtspSdp.profileLevelId(sps))
  }

  @Test
  fun `a too-short SPS falls back rather than producing nonsense`() {
    assertEquals("42001f", RtspSdp.profileLevelId(byteArrayOf(0x67)))
  }

  @Test
  fun `the session describes video alone, or video and audio`() {
    val videoOnly = RtspSdp.sessionSdp(640, 480, sps, pps)
    assertTrue(videoOnly.contains("m=video"))
    assertTrue("no audio track when there's no sound", !videoOnly.contains("m=audio"))

    val withAudio = RtspSdp.sessionSdp(640, 480, sps, pps, audioSampleRate = 16000)
    assertTrue(withAudio.contains("m=video"))
    assertTrue(withAudio.contains("m=audio"))
    // Separate control URLs are what let a client set up one track and not the other.
    assertTrue(withAudio.contains("a=control:trackID=0"))
    assertTrue(withAudio.contains("a=control:trackID=1"))
    assertTrue("video must come first", withAudio.indexOf("m=video") < withAudio.indexOf("m=audio"))
  }

  @Test
  fun `the SDP carries everything a client needs`() {
    val sdp = RtspSdp.videoSdp(640, 480, sps, pps)
    assertTrue(sdp.startsWith("v=0\r\n"))
    assertTrue("rtpmap", sdp.contains("a=rtpmap:96 H264/90000\r\n"))
    assertTrue("fragmentation announced", sdp.contains("packetization-mode=1"))
    assertTrue("profile from SPS", sdp.contains("profile-level-id=42c01f"))
    assertTrue("parameter sets", sdp.contains("sprop-parameter-sets=${RtspSdp.base64(sps)},${RtspSdp.base64(pps)}"))
    assertTrue("frame size", sdp.contains("a=framesize:96 640-480"))
    assertTrue("control track", sdp.contains("a=control:trackID=0"))
    // CRLF throughout: some clients reject bare LF.
    assertTrue("no bare LF", sdp.split("\n").dropLast(1).all { it.endsWith("\r") })
  }
}
