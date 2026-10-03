/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * On-device cache of screensaver media, shared by every HTTP remote source (Immich, WebDAV).
 * Images are stored as fetched; videos are stored as screen-sized (1200x800) H.264 derivatives
 * produced by [VideoTranscoder]. Which assets are resident is decided by the [CachePool] stored
 * beside the media ([poolFile]): a random selection of the source filled up to the budget, then
 * rotated slowly, so the source server is touched once per asset and the slideshow plays from
 * local storage on every loop.
 *
 * Keyed by a stable hash of the item's URL, so the same asset maps to the same file across runs
 * and app restarts. The budget is still enforced as a size-capped LRU ([enforceBudget]) as a
 * backstop for a lowered storage limit; in normal operation the pool stops filling at [hasRoom]
 * and nothing is evicted.
 *
 * All operations are best-effort: a failure returns null/false and the caller falls back to a
 * direct fetch, so the frame is never blank because of a cache problem.
 */
class MediaCache internal constructor(private val dir: File, private val budgetBytes: Long) {

  constructor(
      context: Context,
      budgetBytes: Long,
  ) : this(File(context.filesDir, DIR), budgetBytes)

  init {
    runCatching { dir.mkdirs() }
    // Sweep temp files stranded by a process death mid-download/transcode. They're '.'-prefixed,
    // so the budget never counts them — without this sweep a crashy stretch could quietly fill
    // the disk with invisible half-downloaded sources (~100-200 MB each). Any live temp belongs
    // to a previous controller instance, and only one screensaver runs at a time.
    runCatching {
      dir.listFiles()?.filter { it.isFile && it.name.startsWith(".") }?.forEach { it.delete() }
    }
  }

  /** Stable SHA-1 hex of the source URL — the cache key, independent of source or session. */
  fun key(url: String): String {
    val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
    return h.joinToString("") { "%02x".format(it) }
  }

  fun imageFile(url: String): File = File(dir, "${key(url)}.jpg")

  fun videoFile(url: String): File = File(dir, "${key(url)}.mp4")

  fun fileFor(url: String, isVideo: Boolean): File = if (isVideo) videoFile(url) else imageFile(url)

  /** Where the [CachePool] for this cache is persisted (not a media file; never counted or evicted). */
  fun poolFile(): File = File(dir, POOL_FILE)

  /** Bytes on disk for a cached item (0 when absent). */
  fun sizeOf(url: String, isVideo: Boolean): Long = fileFor(url, isVideo).length()

  /** Remove one cached item. */
  fun delete(url: String, isVideo: Boolean) {
    runCatching { fileFor(url, isVideo).delete() }
  }

  /** Delete every media file whose name is not in [keepNames] (orphans no pool entry claims). */
  fun deleteMediaExcept(keepNames: Set<String>) {
    mediaFiles().filter { it.name !in keepNames }.forEach { runCatching { it.delete() } }
  }

  /** Resident media files: committed images and videos, never temps or the pool file. */
  private fun mediaFiles(): List<File> =
      dir.listFiles()?.filter {
        it.isFile && !it.name.startsWith(".") && (it.name.endsWith(".jpg") || it.name.endsWith(".mp4"))
      } ?: emptyList()

  /** The cached file for [url] if present and non-empty, touched as most-recently-used; else null. */
  fun getIfPresent(url: String, isVideo: Boolean): File? {
    val f = fileFor(url, isVideo)
    if (f.exists() && f.length() > 0L) {
      runCatching { f.setLastModified(System.currentTimeMillis()) }
      return f
    }
    return null
  }

  fun isCached(url: String, isVideo: Boolean): Boolean =
      fileFor(url, isVideo).let { it.exists() && it.length() > 0L }

  /** Store image bytes atomically, then enforce the budget. Returns the file, or null on failure. */
  fun putImage(url: String, bytes: ByteArray): File? =
      runCatching {
            val f = imageFile(url)
            writeAtomic(f, bytes)
            enforceBudget()
            f
          }
          .getOrNull()

  /** A temp path beside a target file, for a transcoder/downloader to write before committing. */
  fun tempFor(target: File): File = File(dir, ".${target.name}.tmp")

  /** Atomically move a finished temp file into place as [target], then enforce the budget. */
  fun commit(tmp: File, target: File): Boolean =
      runCatching {
            val ok = tmp.renameTo(target)
            if (ok) enforceBudget()
            ok
          }
          .getOrDefault(false)

  private fun writeAtomic(f: File, bytes: ByteArray) {
    val tmp = tempFor(f)
    tmp.outputStream().use { it.write(bytes) }
    if (!tmp.renameTo(f)) {
      tmp.delete()
      error("rename failed for ${f.name}")
    }
  }

  /**
   * Delete least-recently-used files until the total resident size is within [budgetBytes].
   * Hidden temp files ('.'-prefixed, in-flight writes) and the pool file are ignored. Synchronized so a prefetch
   * commit and an image put don't evict against a stale total at the same time.
   */
  @Synchronized
  fun enforceBudget() {
    val files = mediaFiles()
    var total = files.sumOf { it.length() }
    if (total <= budgetBytes) return
    for (f in files.sortedBy { it.lastModified() }) {
      if (total <= budgetBytes) break
      val len = f.length()
      if (runCatching { f.delete() }.getOrDefault(false)) total -= len
    }
  }

  /** Current resident size (excludes in-flight temp files). */
  fun sizeBytes(): Long = mediaFiles().sumOf { it.length() }

  /**
   * Whether the cache has meaningful room left (under ~90% of budget). The pool fill stops here:
   * filling past this line would make [enforceBudget] evict resident items to admit new ones, and
   * on an album bigger than the budget that is a treadmill that re-hits the server forever.
   */
  fun hasRoom(): Boolean = sizeBytes() < budgetBytes - budgetBytes / 10

  companion object {
    const val DIR = "screensaver-media-cache"
    const val POOL_FILE = "pool.json"

    /** Delete the whole cache directory — used to reclaim storage when the user turns caching off. */
    fun purge(context: Context) {
      runCatching {
        val dir = File(context.filesDir, DIR)
        if (dir.exists()) dir.deleteRecursively()
      }
    }

    /** Storage always left free for the OS (updates, logs), never handed to the cache. */
    const val HEADROOM_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * A safe default budget: the user's [ceilingBytes] cap, bounded by what's actually free minus a
     * fixed [HEADROOM_BYTES] reserve. The wall Portals are dedicated to the frame, so we let the
     * cache use nearly all their spare space (keep-2GB) rather than only half — while a nearly-full
     * device (free ≤ headroom) yields 0, so caching simply never fills the disk.
     */
    fun defaultBudget(freeBytes: Long, ceilingBytes: Long = 4L * 1024 * 1024 * 1024): Long =
        minOf(ceilingBytes, (freeBytes - HEADROOM_BYTES).coerceAtLeast(0L))
  }
}
