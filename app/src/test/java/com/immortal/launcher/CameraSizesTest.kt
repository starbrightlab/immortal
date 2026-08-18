/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which of the camera's offered sizes a snapshot asks for. Bounded deliberately: this runs on
 * Android 9 hardware with no largeHeap, often while the photo frame holds full-screen bitmaps.
 */
class CameraSizesTest {

  // A plausible Portal front-camera list: a few small, a couple far too big.
  private val offered =
      listOf(176 to 144, 320 to 240, 640 to 480, 1280 to 720, 1920 to 1080)

  @Test
  fun `picks the largest that fits the cap`() {
    assertEquals(640 to 480, CameraSizes.choose(offered, maxEdge = 640))
  }

  @Test
  fun `a bigger cap admits a bigger size`() {
    assertEquals(1280 to 720, CameraSizes.choose(offered, maxEdge = 1280))
  }

  @Test
  fun `the cap is on the longest edge, not the width`() {
    // 480 tall fits a 640 cap only because its LONGEST edge is 640 - a portrait-mounted sensor
    // offering 480x640 must be judged the same way.
    assertEquals(480 to 640, CameraSizes.choose(listOf(480 to 640, 1080 to 1920), maxEdge = 640))
  }

  @Test
  fun `falls back to the smallest when nothing fits`() {
    // A device that only offers large sizes should still produce a snapshot, not fail outright.
    assertEquals(1280 to 720, CameraSizes.choose(listOf(1280 to 720, 1920 to 1080), maxEdge = 640))
  }

  @Test
  fun `no sizes means no capture`() {
    assertNull(CameraSizes.choose(emptyList(), maxEdge = 640))
  }
}
