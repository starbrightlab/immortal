/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rate limiting and rendering for the ambient sensor entities. */
class AmbientPolicyTest {

  private val now = 1_760_000_000_000L
  private val delta = AmbientKind.TEMPERATURE.minDelta

  @Test
  fun `the first reading always publishes`() {
    assertTrue(AmbientPolicy.shouldPublish(null, 21.4, 0L, now, delta))
  }

  @Test
  fun `sensor noise below the threshold is dropped`() {
    assertFalse(
        AmbientPolicy.shouldPublish(21.4, 21.42, now - AmbientPolicy.MIN_INTERVAL_MS, now, delta))
  }

  @Test
  fun `a real change waits out the interval`() {
    assertFalse(AmbientPolicy.shouldPublish(21.4, 22.0, now - 5_000L, now, delta))
    assertTrue(
        AmbientPolicy.shouldPublish(21.4, 22.0, now - AmbientPolicy.MIN_INTERVAL_MS, now, delta))
  }

  @Test
  fun `a big jump jumps the queue`() {
    // Someone switching a lamp on is the event an automation is waiting for — it shouldn't sit
    // in the rate limiter for twenty seconds.
    val lux = AmbientKind.ILLUMINANCE.minDelta
    assertTrue(
        AmbientPolicy.shouldPublish(5.0, 400.0, now - AmbientPolicy.FAST_MIN_MS, now, lux))
  }

  @Test
  fun `even a big jump can't publish faster than the floor`() {
    val lux = AmbientKind.ILLUMINANCE.minDelta
    assertFalse(AmbientPolicy.shouldPublish(5.0, 400.0, now - 100L, now, lux))
  }

  @Test
  fun `values render with a dot decimal whatever the device locale`() {
    // A comma-decimal locale would publish "21,4", which Home Assistant reads as non-numeric.
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.GERMANY)
      assertEquals("21.4", AmbientPolicy.format(21.44, decimals = 1))
      assertEquals("415", AmbientPolicy.format(414.6, decimals = 0))
    } finally {
      Locale.setDefault(original)
    }
  }

  @Test
  fun `the temperature offset shifts the reading`() {
    assertEquals(19.5, AmbientPolicy.calibrate(21.5, offsetC = -2), 0.0001)
    assertEquals(21.5, AmbientPolicy.calibrate(21.5, offsetC = 0), 0.0001)
  }
}
