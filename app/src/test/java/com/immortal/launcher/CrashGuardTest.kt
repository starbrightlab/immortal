/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure crash-loop breaker behind [CrashGuard]: relaunch unless crashes are hot-looping. */
class CrashGuardTest {

  @Test
  fun firstCrashEverRelaunches() {
    val (relaunch, count) = CrashGuard.decide(nowMs = 1_000_000L, lastCrashMs = 0L, prevCount = 0)
    assertTrue(relaunch)
    assertEquals(1, count)
  }

  @Test
  fun crashesSpacedApartAlwaysRelaunchAndResetTheStreak() {
    // Each crash is more than the window after the last → streak never climbs, always relaunch.
    var last = 0L
    repeat(10) {
      val now = last + CrashGuard.WINDOW_MS + 1
      val (relaunch, count) = CrashGuard.decide(now, last, prevCount = CrashGuard.MAX_RAPID)
      assertTrue(relaunch)
      assertEquals(1, count)
      last = now
    }
  }

  @Test
  fun rapidCrashesRelaunchUpToTheCapThenGiveUp() {
    val base = 5_000_000L
    // Crashes 1..MAX_RAPID, each within the window of the previous → relaunch.
    var count = 0
    for (i in 1..CrashGuard.MAX_RAPID) {
      val (relaunch, c) = CrashGuard.decide(base + i, base + i - 1, count)
      assertTrue("rapid crash #$i should still relaunch", relaunch)
      count = c
    }
    // The next rapid crash exceeds the cap → give up (no relaunch).
    val (relaunch, _) = CrashGuard.decide(base + CrashGuard.MAX_RAPID + 1, base + CrashGuard.MAX_RAPID, count)
    assertFalse("crash past the cap should not relaunch", relaunch)
  }

  @Test
  fun recoveringAfterTheWindowReenablesRelaunch() {
    // Hit the cap...
    var count = 0
    val base = 9_000_000L
    for (i in 1..(CrashGuard.MAX_RAPID + 1)) {
      count = CrashGuard.decide(base + i, base + i - 1, count).second
    }
    // ...then a well-spaced crash resets the streak and relaunches again.
    val later = base + CrashGuard.MAX_RAPID + 1 + CrashGuard.WINDOW_MS + 1
    val (relaunch, c) = CrashGuard.decide(later, base + CrashGuard.MAX_RAPID + 1, count)
    assertTrue(relaunch)
    assertEquals(1, c)
  }
}
