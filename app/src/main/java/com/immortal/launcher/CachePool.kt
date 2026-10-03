/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * The resident set of a [MediaCache]: which assets from the current source live on this device,
 * persisted beside the media files so it survives dream sessions and app restarts.
 *
 * This is what stops a large album from turning the cache into a treadmill. Instead of playing
 * the whole album and letting LRU eviction swap files in and out forever, the screensaver plays
 * only the pool. The pool is filled once with a random selection of the source's assets up to the
 * storage budget, and after that it changes only on a slow, user-set cadence (the "Refresh from
 * server every" setting, daily by default): a sync forgets assets that left the source, swaps out
 * a user-set share of the cache (5% by default) for new random picks, and tops the cache back up. Between syncs the source server is not contacted at all,
 * and when the server is unreachable the pool simply keeps playing what it holds.
 *
 * Bound to one [sourceKey] (source type + server + album + video setting): loading the file
 * under a different key yields an empty pool, and the controller's reconcile pass then deletes
 * the previous source's files to make room.
 *
 * Mutators are synchronized, because the live-play image path (io thread) and the sync worker
 * (transcode thread) can both add entries. Removals and timestamps persist immediately; adds are
 * saved in batches that grow with the pool ([flush] writes the rest), so filling a pool of tens
 * of thousands of images doesn't rewrite the whole file per image. Adds lost to a process death
 * just become orphan files that the next [reconcile] deletes.
 */
class CachePool private constructor(private val file: File, val sourceKey: String) {

  /** One resident asset, keyed by its source URL (the same key [MediaCache] hashes). */
  data class Entry(val url: String, val isVideo: Boolean)

  private val entries = LinkedHashMap<String, Entry>()
  private var unsaved = 0

  /** When the last sync pass completed (0 = never; the first fill is still outstanding). */
  var lastSyncMs: Long = 0L
    private set

  /** When the last rotation dropped entries, so a retried sync doesn't drop a second batch. */
  var lastRotationMs: Long = 0L
    private set

  @Synchronized fun snapshot(): List<Entry> = entries.values.toList()

  @Synchronized fun size(): Int = entries.size

  @Synchronized fun contains(url: String): Boolean = entries.containsKey(url)

  @Synchronized
  fun add(e: Entry) {
    if (entries.put(e.url, e) != null) return
    if (++unsaved >= maxOf(SAVE_BATCH_MIN, entries.size / 20)) save()
  }

  /** Persist any adds still held back by batching. */
  @Synchronized
  fun flush() {
    if (unsaved > 0) save()
  }

  @Synchronized
  fun remove(urls: Collection<String>) {
    var changed = false
    for (u in urls) if (entries.remove(u) != null) changed = true
    if (changed) save()
  }

  @Synchronized
  fun markSynced(now: Long) {
    lastSyncMs = now
    save()
  }

  @Synchronized
  fun markRotated(now: Long) {
    lastRotationMs = now
    save()
  }

  /**
   * Make the pool and the cache directory agree: drop entries whose file is gone (evicted, or
   * deleted after a playback error) and delete media files no entry claims (a previous source's
   * assets, or leftovers from the old LRU design). Run once per session, off the UI thread.
   */
  @Synchronized
  fun reconcile(cache: MediaCache) {
    val missing = entries.values.filter { !cache.isCached(it.url, it.isVideo) }.map { it.url }
    for (u in missing) entries.remove(u)
    val keep = entries.values.mapTo(HashSet()) { cache.fileFor(it.url, it.isVideo).name }
    cache.deleteMediaExcept(keep)
    if (missing.isNotEmpty()) save()
  }

  private fun save() {
    unsaved = 0
    runCatching {
      val arr = JSONArray()
      for (e in entries.values) arr.put(JSONObject().put("u", e.url).put("v", e.isVideo))
      val json =
          JSONObject()
              .put("key", sourceKey)
              .put("lastSync", lastSyncMs)
              .put("lastRotation", lastRotationMs)
              .put("entries", arr)
      val tmp = File(file.parentFile, ".${file.name}.tmp")
      tmp.writeText(json.toString())
      if (!tmp.renameTo(file)) tmp.delete()
    }
  }

  companion object {
    /** Retry delay after a sync that couldn't reach the server or was cut short. */
    const val RETRY_MS = 60L * 60 * 1000

    /** Fewest adds batched into one save (the batch grows to 5% of the pool). */
    const val SAVE_BATCH_MIN = 25

    /**
     * Load the pool persisted at [file] for [sourceKey]. A missing or unreadable file, or one
     * written for a different source, yields an empty pool (the first fill then starts).
     */
    fun load(file: File, sourceKey: String): CachePool {
      val pool = CachePool(file, sourceKey)
      runCatching {
        if (!file.exists()) return pool
        val o = JSONObject(file.readText())
        if (o.optString("key") != sourceKey) return pool
        pool.lastSyncMs = o.optLong("lastSync", 0L)
        pool.lastRotationMs = o.optLong("lastRotation", 0L)
        val arr = o.optJSONArray("entries") ?: return pool
        for (i in 0 until arr.length()) {
          val e = arr.getJSONObject(i)
          val url = e.optString("u")
          if (url.isNotEmpty()) pool.entries[url] = Entry(url, e.optBoolean("v", false))
        }
      }
      return pool
    }

    /**
     * Whether a timestamp-gated step is due: never done, the interval has elapsed, or the clock
     * went backwards (a Portal that booted without network time can stamp a future date).
     */
    fun isDue(lastMs: Long, now: Long, intervalMs: Long): Boolean =
        lastMs <= 0L || now < lastMs || now - lastMs >= intervalMs

    /** Source assets not yet resident, in random order: the fill queue. */
    fun pickNew(candidates: List<Entry>, resident: Set<String>, random: Random = Random): List<Entry> =
        candidates.filter { it.url !in resident }.distinctBy { it.url }.shuffled(random)

    /**
     * A random selection of [entries] whose sizes add up to at least [fraction] of their total
     * (at least one entry when there is anything to drop): the rotation's victims.
     */
    fun pickDrops(
        entries: List<Entry>,
        sizeOf: (Entry) -> Long,
        fraction: Double,
        random: Random = Random,
    ): List<Entry> {
      if (entries.isEmpty() || fraction <= 0.0) return emptyList()
      val total = entries.sumOf { sizeOf(it) }
      val target = (total * fraction).toLong()
      val out = ArrayList<Entry>()
      var freed = 0L
      for (e in entries.shuffled(random)) {
        if (out.isNotEmpty() && freed >= target) break
        out.add(e)
        freed += sizeOf(e)
      }
      return out
    }

    /**
     * [playlist] with [url] added among the items still to come after [index]: at a random
     * upcoming slot when [shuffle], else at the end. The current position is unchanged.
     */
    fun insertUpcoming(
        playlist: List<String>,
        index: Int,
        url: String,
        shuffle: Boolean,
        random: Random = Random,
    ): List<String> {
      val from = (index + 1).coerceIn(0, playlist.size)
      val at = if (shuffle) random.nextInt(from, playlist.size + 1) else playlist.size
      return ArrayList<String>(playlist.size + 1).apply {
        addAll(playlist)
        add(at, url)
      }
    }

    /**
     * [playlist] without [removed], plus the index that keeps the slideshow where it was: the
     * same item if it survived, else the last surviving item before it (so the next advance
     * lands on what would have come next). -1 when nothing before it survived.
     */
    fun removeFromPlaylist(
        playlist: List<String>,
        index: Int,
        removed: Set<String>,
    ): Pair<List<String>, Int> {
      val kept = playlist.filter { it !in removed }
      if (index !in playlist.indices) return kept to -1
      val survivorsUpTo = (0..index).count { playlist[it] !in removed }
      return kept to survivorsUpTo - 1
    }
  }
}
