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

/** Encoder sizing. The Camera2/MediaCodec plumbing needs a device; these numbers don't. */
class StreamProfileTest {

  @Test
  fun `bitrate scales with pixels and frame rate`() {
    val small = StreamProfile.bitrateFor(320, 240, 15)
    val large = StreamProfile.bitrateFor(640, 480, 15)
    val faster = StreamProfile.bitrateFor(640, 480, 30)
    assertTrue("more pixels costs more", large > small)
    assertTrue("more frames costs more", faster > large)
  }

  @Test
  fun `a typical Portal stream lands in a sane range`() {
    // 640x480 at 15fps over a LAN: hundreds of kbps, not megabits.
    val bps = StreamProfile.bitrateFor(640, 480, 15)
    assertTrue("got $bps", bps in 400_000..600_000)
  }

  @Test
  fun `a tiny frame still gets a usable floor`() {
    assertEquals(200_000, StreamProfile.bitrateFor(160, 120, 5))
  }

  @Test
  fun `a large frame is capped rather than running away`() {
    assertEquals(4_000_000, StreamProfile.bitrateFor(1920, 1080, 30))
  }

  @Test
  fun `constrained baseline is what we ask for`() {
    // Browser WebRTC rejects anything higher, so a stream encoded as High plays in VLC and
    // fails in the Home Assistant card. Pinned because it's a literal (the constant is API 27+).
    assertEquals(0x10000, StreamProfile.PROFILE_CONSTRAINED_BASELINE)
  }
}
