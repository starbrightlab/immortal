/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class MicBackoffTest {
  @Test
  fun idle_retriesQuickly() {
    assertEquals(0, MicBackoff.Idle.failures)
    assertEquals(MicBackoff.MIN_MS, MicBackoff.Idle.delayMs)
  }

  @Test
  fun failures_doubleTheDelay() {
    var b = MicBackoff.Idle
    val delays = (1..5).map { b = MicBackoff.failed(b); b.delayMs }
    assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L), delays)
  }

  @Test
  fun delay_isCapped_evenAfterManyFailures() {
    var b = MicBackoff.Idle
    repeat(1000) { b = MicBackoff.failed(b) }
    assertEquals(MicBackoff.MAX_MS, b.delayMs)
    assertEquals(1000, b.failures)
  }
}
