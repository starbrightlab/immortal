/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared Open-Meteo response cache behind [Weather] (no network). */
class WeatherCacheTest {
  private var now = 0L
  private val cache = Weather.ResponseCache(ttlMs = 600_000L) { now }

  @Test
  fun withinTtl_servesTheCachedBody() {
    val loads = AtomicInteger()
    assertEquals("a", cache.get("u") { loads.incrementAndGet(); "a" })
    now += 599_999L
    assertEquals("a", cache.get("u") { loads.incrementAndGet(); "b" })
    assertEquals(1, loads.get())
  }

  @Test
  fun afterTtl_refetches() {
    cache.get("u") { "a" }
    now += 600_000L
    assertEquals("b", cache.get("u") { "b" })
  }

  @Test
  fun differentUrls_areSeparate() {
    cache.get("celsius") { "c" }
    assertEquals("f", cache.get("fahrenheit") { "f" })
  }

  @Test
  fun failure_isNotCached() {
    runCatching { cache.get("u") { throw java.io.IOException("offline") } }
    assertEquals("a", cache.get("u") { "a" })
  }

  @Test
  fun concurrentMisses_shareOneRequest() {
    // The screensaver's face and photo caption fetch the same URL the instant it starts.
    val loads = AtomicInteger()
    val inFlight = CountDownLatch(1)
    val release = CountDownLatch(1)
    val first = Thread {
      cache.get("u") {
        loads.incrementAndGet()
        inFlight.countDown()
        release.await(5, TimeUnit.SECONDS)
        "a"
      }
    }
    first.start()
    assertTrue(inFlight.await(5, TimeUnit.SECONDS))
    var second: String? = null
    val other = Thread { second = cache.get("u") { loads.incrementAndGet(); "b" } }
    other.start()
    // Let the second caller reach the per-URL lock while the first request is still in flight.
    val deadline = System.currentTimeMillis() + 5_000L
    while (other.state != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) Thread.sleep(5)
    assertEquals(Thread.State.BLOCKED, other.state)
    release.countDown()
    first.join(5_000L)
    other.join(5_000L)
    assertEquals("a", second)
    assertEquals(1, loads.get())
  }
}
