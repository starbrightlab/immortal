/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaCacheTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun cache(budget: Long) = MediaCache(File(tmp.root, "cache"), budget)

  /** Write a cache file directly (bypassing putImage's auto-enforce) with an exact size + mtime,
   *  so eviction-ordering tests can control LRU age deterministically. */
  private fun seed(c: MediaCache, url: String, isVideo: Boolean, size: Int, mtime: Long) {
    val f = if (isVideo) c.videoFile(url) else c.imageFile(url)
    f.outputStream().use { it.write(ByteArray(size)) }
    f.setLastModified(mtime)
  }

  @Test
  fun key_isStableAndUrlDistinct() {
    val c = cache(Long.MAX_VALUE)
    assertEquals(c.key("http://a/1"), c.key("http://a/1"))
    assertTrue(c.key("http://a/1") != c.key("http://a/2"))
  }

  @Test
  fun imageAndVideo_keySameUrlToDifferentFiles() {
    val c = cache(Long.MAX_VALUE)
    val url = "http://immich/api/assets/x/thumbnail?size=preview"
    assertTrue(c.imageFile(url).name.endsWith(".jpg"))
    assertTrue(c.videoFile(url).name.endsWith(".mp4"))
    assertTrue(c.imageFile(url).name != c.videoFile(url).name)
  }

  @Test
  fun putImage_thenGetIfPresent_roundTrips() {
    val c = cache(Long.MAX_VALUE)
    val url = "http://host/pic"
    assertNull(c.getIfPresent(url, isVideo = false))
    val f = c.putImage(url, ByteArray(1234) { 7 })
    assertNotNull(f)
    val hit = c.getIfPresent(url, isVideo = false)
    assertNotNull(hit)
    assertEquals(1234L, hit!!.length())
    assertTrue(c.isCached(url, isVideo = false))
  }

  @Test
  fun getIfPresent_ignoresEmptyFile() {
    val c = cache(Long.MAX_VALUE)
    val url = "http://host/empty"
    c.imageFile(url).createNewFile() // zero-length
    assertNull(c.getIfPresent(url, isVideo = false))
  }

  @Test
  fun enforceBudget_evictsLeastRecentlyUsedFirst() {
    val c = cache(budget = 2_500L) // holds ~2 of the 1000-byte entries
    seed(c, "u1", isVideo = false, size = 1000, mtime = 1_000L) // oldest
    seed(c, "u2", isVideo = false, size = 1000, mtime = 1_001L)
    seed(c, "u3", isVideo = false, size = 1000, mtime = 1_002L) // newest
    // Budget forces one eviction; u1 (oldest) must go, u2/u3 stay.
    c.enforceBudget()
    assertFalse("oldest evicted", c.isCached("u1", isVideo = false))
    assertTrue(c.isCached("u2", isVideo = false))
    assertTrue(c.isCached("u3", isVideo = false))
    assertTrue(c.sizeBytes() <= 2_500L)
  }

  @Test
  fun getIfPresent_touchProtectsFromEviction() {
    val c = cache(budget = 2_500L)
    seed(c, "a", isVideo = false, size = 1000, mtime = 1_000L) // oldest
    seed(c, "b", isVideo = false, size = 1000, mtime = 1_001L)
    seed(c, "c", isVideo = false, size = 1000, mtime = 1_002L)
    // Touch the oldest so it becomes most-recently-used; the next-oldest ('b') should be evicted.
    assertNotNull(c.getIfPresent("a", isVideo = false))
    c.enforceBudget()
    assertTrue("touched survives", c.isCached("a", isVideo = false))
    assertFalse("next-oldest evicted", c.isCached("b", isVideo = false))
  }

  @Test
  fun commit_movesTempIntoPlace() {
    val c = cache(Long.MAX_VALUE)
    val target = c.videoFile("http://host/clip")
    val tmpFile = c.tempFor(target)
    tmpFile.outputStream().use { it.write(ByteArray(5000)) }
    assertTrue(c.commit(tmpFile, target))
    assertTrue(c.isCached("http://host/clip", isVideo = true))
    assertFalse(tmpFile.exists())
  }

  @Test
  fun init_sweepsStrandedTempFiles() {
    // A process death mid-download strands hidden temp files the budget can't see. A fresh
    // cache instance must reap them — and must not touch real entries.
    val dir = File(tmp.root, "cache").apply { mkdirs() }
    File(dir, ".abc.mp4.src.tmp").outputStream().use { it.write(ByteArray(500)) }
    File(dir, ".def.mp4.tmp").outputStream().use { it.write(ByteArray(500)) }
    val real = File(dir, "0123456789abcdef.mp4")
    real.outputStream().use { it.write(ByteArray(500)) }

    val c = MediaCache(dir, Long.MAX_VALUE)
    assertFalse(File(dir, ".abc.mp4.src.tmp").exists())
    assertFalse(File(dir, ".def.mp4.tmp").exists())
    assertTrue("real entries survive the sweep", real.exists())
    assertEquals(500L, c.sizeBytes())
  }

  @Test
  fun hasRoom_stopsShortOfTheBudget() {
    val c = cache(budget = 10_000L) // room threshold = 9,000
    assertTrue(c.hasRoom())
    seed(c, "a", isVideo = true, size = 8_000, mtime = 1_000L)
    assertTrue("8000 < 9000", c.hasRoom())
    seed(c, "b", isVideo = true, size = 1_500, mtime = 1_001L)
    assertFalse("9500 >= 9000", c.hasRoom())
  }

  @Test
  fun defaultBudget_capsAndReservesHeadroom() {
    val gb = 1024L * 1024 * 1024
    // Plenty free → capped at the ceiling (default 4 GB).
    assertEquals(4 * gb, MediaCache.defaultBudget(100 * gb))
    // Free space is the binding limit → free minus the 2 GB headroom (8 GB free, 32 GB cap → 6 GB).
    assertEquals(6 * gb, MediaCache.defaultBudget(8 * gb, ceilingBytes = 32 * gb))
    // Nearly-full device (free <= headroom) → 0, so caching never fills the disk.
    assertEquals(0L, MediaCache.defaultBudget(1 * gb, ceilingBytes = 32 * gb))
  }
}
