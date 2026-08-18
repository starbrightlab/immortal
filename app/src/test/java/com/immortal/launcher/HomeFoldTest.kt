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
 * Dwell-to-fold, the rules behind "drag one app onto another to make a folder". This has regressed
 * twice — once by losing the interaction outright, once by making it unarmable — so the two
 * properties that broke it are pinned here rather than left to a device to discover.
 */
class HomeFoldTest {

  private val a = "app:com.example.a"
  private val b = "app:com.example.b"
  private val folder = "folder:Games"
  private val widget = "widget-tile:clock"

  // --- the gutter between tiles ---------------------------------------------

  @Test
  fun `a sample in the gutter between tiles keeps the candidate`() {
    // THE regression: the grid spaces its tiles apart, so a finger between them hit-tests as no
    // slot at all. Clearing the candidate there left the dwell with nothing to arm — and the
    // timer only restarts on movement, which is exactly what stops when you hold still to fold.
    assertEquals(b, HomeFold.nextCandidate(a, slotIndexUnderPointer = null, tileUnderPointer = null, current = b))
  }

  @Test
  fun `landing on another app makes it the candidate`() {
    assertEquals(b, HomeFold.nextCandidate(a, slotIndexUnderPointer = 4, tileUnderPointer = b, current = null))
  }

  @Test
  fun `moving onto something that isn't an app clears the candidate`() {
    // A real slot under the finger IS news, unlike the gutter: the user moved off the app.
    assertNull(HomeFold.nextCandidate(a, slotIndexUnderPointer = 4, tileUnderPointer = folder, current = b))
    assertNull(HomeFold.nextCandidate(a, slotIndexUnderPointer = 4, tileUnderPointer = widget, current = b))
    // A blank slot is a slot, so it clears too.
    assertNull(HomeFold.nextCandidate(a, slotIndexUnderPointer = 4, tileUnderPointer = null, current = b))
  }

  @Test
  fun `an app can't be a fold candidate with itself`() {
    assertNull(HomeFold.nextCandidate(a, slotIndexUnderPointer = 4, tileUnderPointer = a, current = null))
  }

  @Test
  fun `dragging a folder or widget never picks up a candidate`() {
    assertNull(HomeFold.nextCandidate(folder, slotIndexUnderPointer = 4, tileUnderPointer = b, current = null))
    assertNull(HomeFold.nextCandidate(widget, slotIndexUnderPointer = 4, tileUnderPointer = b, current = null))
  }

  // --- movement tolerance ----------------------------------------------------

  @Test
  fun `a resting finger's jitter is not movement`() {
    // Pointer changes are reported for a single pixel. Without tolerance the dwell timer restarts
    // forever and the fold can never arm on real hardware.
    assertFalse(HomeFold.movedEnough(100f, 100f, 101f, 102f, tolerancePx = 24f))
  }

  @Test
  fun `moving to another tile is movement`() {
    assertTrue(HomeFold.movedEnough(100f, 100f, 160f, 100f, tolerancePx = 24f))
  }

  @Test
  fun `tolerance is a radius, not per-axis`() {
    // 18 across and 18 down is 25.5 away — over a 24 tolerance, even though neither axis is.
    assertTrue(HomeFold.movedEnough(100f, 100f, 118f, 118f, tolerancePx = 24f))
  }

  @Test
  fun `with no anchor yet, anything counts as movement`() {
    assertTrue(HomeFold.movedEnough(null, null, 10f, 10f, tolerancePx = 24f))
  }

  // --- what may actually fold ------------------------------------------------

  @Test
  fun `only app-on-app, and never with itself`() {
    assertTrue(HomeFold.canFold(a, b))
    assertFalse(HomeFold.canFold(a, a))
    assertFalse(HomeFold.canFold(a, folder))
    assertFalse(HomeFold.canFold(folder, b))
    assertFalse(HomeFold.canFold(a, null))
    assertFalse(HomeFold.canFold(null, b))
  }
}
