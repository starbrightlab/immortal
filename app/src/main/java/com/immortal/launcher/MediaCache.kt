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
 * On-device cache of screensaver media, shared by every HTTP remote source (Immich, WebDAV,
 * shared albums). Images are stored as fetched; videos are stored as screen-sized (1200x800)
 * H.264 derivatives produced by [VideoTranscoder]. Each Portal fills its own cache once, then
 * plays from local storage on every subsequent loop — cutting repeat fetches (and, for video,
 * most of the bytes) off the source server. This is the on-device answer to the video-wall
 * problem: the server is touched once per asset, not on every advance forever.
 *
 * Keyed by a stable hash of the item's URL, so the same asset maps to the same file across runs
 * and app restarts. Eviction is a size-budgeted LRU keyed on file last-modified, which is
 * touched on every cache hit — so the working set of a looping album stays resident and only
 * genuinely cold items are dropped.
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

  /** The cached file for [url] if present and non-empty, touched as most-recently-used; else null. */
  fun getIfPresent(url: String, isVideo: Boolean): File? {
    val f = if (isVideo) videoFile(url) else imageFile(url)
    if (f.exists() && f.length() > 0L) {
      runCatching { f.setLastModified(System.currentTimeMillis()) }
      return f
    }
    return null
  }

  fun isCached(url: String, isVideo: Boolean): Boolean =
      (if (isVideo) videoFile(url) else imageFile(url)).let { it.exists() && it.length() > 0L }

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
   * Hidden temp files ('.'-prefixed, in-flight writes) are ignored. Synchronized so a prefetch
   * commit and an image put don't evict against a stale total at the same time.
   */
  @Synchronized
  fun enforceBudget() {
    val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: return
    var total = files.sumOf { it.length() }
    if (total <= budgetBytes) return
    for (f in files.sortedBy { it.lastModified() }) {
      if (total <= budgetBytes) break
      val len = f.length()
      if (runCatching { f.delete() }.getOrDefault(false)) total -= len
    }
  }

  /** Current resident size (excludes in-flight temp files). */
  fun sizeBytes(): Long =
      dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sumOf { it.length() } ?: 0L

  /**
   * Whether the cache has meaningful room left (under ~90% of budget). The prefetch worker stops
   * here rather than transcoding clips whose commit would just evict warmer entries — on an album
   * bigger than the budget, filling past this line turns the cache into a treadmill (every add
   * evicts something the slideshow still wants, so the server keeps getting re-hit forever).
   */
  fun hasRoom(): Boolean = sizeBytes() < budgetBytes - budgetBytes / 10

  companion object {
    const val DIR = "screensaver-media-cache"

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
