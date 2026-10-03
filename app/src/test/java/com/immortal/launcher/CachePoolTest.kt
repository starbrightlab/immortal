/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.CachePool.Entry
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachePoolTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun cache() = MediaCache(File(tmp.root, "cache"), Long.MAX_VALUE)

  private fun seed(c: MediaCache, e: Entry, size: Int = 100) {
    c.fileFor(e.url, e.isVideo).outputStream().use { it.write(ByteArray(size)) }
  }

  @Test
  fun persistsAcrossLoads() {
    val c = cache()
    val p = CachePool.load(c.poolFile(), "immich|a")
    p.add(Entry("u1", isVideo = false))
    p.add(Entry("u2", isVideo = true))
    p.markSynced(1_000L)
    p.markRotated(900L)

    val again = CachePool.load(c.poolFile(), "immich|a")
    assertEquals(listOf(Entry("u1", false), Entry("u2", true)), again.snapshot())
    assertEquals(1_000L, again.lastSyncMs)
    assertEquals(900L, again.lastRotationMs)
  }

  @Test
  fun adds_areBatchedUntilFlush() {
    val c = cache()
    val p = CachePool.load(c.poolFile(), "k")
    repeat(CachePool.SAVE_BATCH_MIN - 1) { p.add(Entry("u$it", false)) }
    assertEquals("below the batch: nothing written yet", 0, CachePool.load(c.poolFile(), "k").size())
    p.add(Entry("last", false))
    assertEquals(CachePool.SAVE_BATCH_MIN, CachePool.load(c.poolFile(), "k").size())
    p.add(Entry("extra", false))
    p.flush()
    assertEquals(CachePool.SAVE_BATCH_MIN + 1, CachePool.load(c.poolFile(), "k").size())
  }

  @Test
  fun differentSourceKey_startsEmpty() {
    val c = cache()
    CachePool.load(c.poolFile(), "immich|a").apply {
      add(Entry("u1", false))
      markSynced(1_000L)
    }
    val other = CachePool.load(c.poolFile(), "immich|b")
    assertEquals(0, other.size())
    assertEquals(0L, other.lastSyncMs)
  }

  @Test
  fun corruptFile_startsEmpty() {
    val c = cache()
    c.poolFile().writeText("not json")
    assertEquals(0, CachePool.load(c.poolFile(), "k").size())
  }

  @Test
  fun reconcile_dropsMissingEntriesAndDeletesOrphans() {
    val c = cache()
    val kept = Entry("kept", isVideo = false)
    val missing = Entry("missing", isVideo = true)
    val orphan = Entry("orphan", isVideo = false) // e.g. a previous album's file
    seed(c, kept)
    seed(c, orphan)
    val p = CachePool.load(c.poolFile(), "k")
    p.add(kept)
    p.add(missing)

    p.reconcile(c)
    assertEquals(listOf(kept), p.snapshot())
    assertTrue(c.isCached("kept", false))
    assertFalse(c.isCached("orphan", false))
    assertEquals(listOf(kept), CachePool.load(c.poolFile(), "k").snapshot())
  }

  @Test
  fun isDue() {
    val day = 24L * 60 * 60 * 1000
    assertTrue("never synced", CachePool.isDue(0L, 5_000L, day))
    assertFalse(CachePool.isDue(1_000L, 1_000L + day - 1, day))
    assertTrue(CachePool.isDue(1_000L, 1_000L + day, day))
    assertTrue("clock went backwards", CachePool.isDue(10_000L, 5_000L, day))
  }

  @Test
  fun pickNew_excludesResidentAndDuplicates() {
    val candidates = listOf(Entry("a", false), Entry("b", true), Entry("c", false), Entry("a", false))
    val fresh = CachePool.pickNew(candidates, setOf("b"), Random(1))
    assertEquals(setOf("a", "c"), fresh.map { it.url }.toSet())
    assertEquals(2, fresh.size)
  }

  @Test
  fun pickDrops_freesAtLeastTheFraction() {
    val entries = (1..40).map { Entry("u$it", false) }
    val drops = CachePool.pickDrops(entries, { 100L }, 0.05, Random(7))
    assertEquals("5% of 40 equal items", 2, drops.size)
    assertEquals(2, drops.toSet().size)

    // One big video can satisfy the fraction alone; the pick never returns more than needed + 1.
    val mixed = listOf(Entry("big", true)) + (1..19).map { Entry("p$it", false) }
    val sizes = mapOf("big" to 1_000L).withDefault { 10L }
    val d = CachePool.pickDrops(mixed, { sizes.getValue(it.url) }, 0.05, Random(3))
    val freed = d.sumOf { sizes.getValue(it.url) }
    assertTrue(freed >= (1_190 * 0.05).toLong())
  }

  @Test
  fun pickDrops_edgeCases() {
    assertEquals(emptyList<Entry>(), CachePool.pickDrops(emptyList(), { 1L }, 0.05))
    // A tiny pool still drops one item, so rotation always makes progress.
    assertEquals(1, CachePool.pickDrops(listOf(Entry("a", false), Entry("b", false)), { 1L }, 0.05).size)
  }

  @Test
  fun insertUpcoming_neverLandsBeforeTheCurrentItem() {
    val list = listOf("a", "b", "c", "d")
    repeat(50) { seed ->
      val out = CachePool.insertUpcoming(list, 1, "x", shuffle = true, random = Random(seed))
      assertEquals(5, out.size)
      assertEquals(listOf("a", "b"), out.take(2))
      assertTrue(out.indexOf("x") >= 2)
    }
    assertEquals(listOf("a", "b", "c", "d", "x"), CachePool.insertUpcoming(list, 1, "x", shuffle = false))
    assertEquals(listOf("x"), CachePool.insertUpcoming(emptyList(), -1, "x", shuffle = true))
  }

  @Test
  fun removeFromPlaylist_keepsThePlace() {
    val list = listOf("a", "b", "c", "d", "e")
    // Current item survives: index follows it.
    assertEquals(listOf("a", "c", "d", "e") to 1, CachePool.removeFromPlaylist(list, 2, setOf("b")))
    // Current item removed: index lands just before what would have come next ("d").
    val (kept, idx) = CachePool.removeFromPlaylist(list, 2, setOf("c"))
    assertEquals(listOf("a", "b", "d", "e"), kept)
    assertEquals("d", kept[idx + 1])
    // Nothing survives up to the current item: -1, so the next advance starts at the top.
    assertEquals(listOf("c", "d", "e") to -1, CachePool.removeFromPlaylist(list, 1, setOf("a", "b")))
    // Not started yet.
    assertEquals(listOf("a", "c", "d", "e") to -1, CachePool.removeFromPlaylist(list, -1, setOf("b")))
  }
}
