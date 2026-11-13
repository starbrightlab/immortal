/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Pure helpers for the free-placement home grid. The grid is modelled as a flat list of "slots":
 * each slot is either a tile key or `null` (an intentional blank cell). This lets the user leave
 * gaps and move tiles freely within the grid (a drop swaps two slots) rather than everything
 * auto-packing to the top-left.
 */
object HomeGrid {

  /**
   * Keep blanks, drop stale/duplicate keys (→ blank), place new tiles, trim trailing blanks.
   *
   * New tiles fill the blanks after the last kept tile first (the saved list is usually padded to
   * whole rows/pages), and are appended only when none are left. Blanks before the last kept tile
   * are gaps the user made, so they are never filled.
   */
  private fun reconcile(saved: List<String?>, keys: List<String>): MutableList<String?> {
    val keySet = keys.toHashSet()
    val seen = HashSet<String>()
    val out = ArrayList<String?>(saved.size + keys.size)
    for (s in saved) {
      if (s != null && s in keySet && seen.add(s)) out.add(s) else out.add(null)
    }
    var free = out.indexOfLast { it != null } + 1
    for (k in keys) {
      if (!seen.add(k)) continue
      if (free < out.size) out[free++] = k else out.add(k)
    }
    while (out.isNotEmpty() && out.last() == null) out.removeAt(out.lastIndex)
    return out
  }

  /**
   * Reconcile a saved slot list with the tiles that currently exist:
   * - keeps blanks (`null`) where the user left them,
   * - drops keys that no longer exist or are duplicated (their slot becomes blank),
   * - places brand-new tiles in the first blanks after the last tile, else appends them,
   * - trims trailing blanks, then pads so the last row is full plus one spare blank row to drop into.
   *
   * [cols] is the column count; padding is by slot count (wide widgets are an approximation, which
   * only affects how many trailing blanks appear, never correctness of placement).
   */
  fun normalizeSlots(saved: List<String?>, keys: List<String>, cols: Int): List<String?> {
    val out = reconcile(saved, keys)
    if (cols > 0) {
      val rem = out.size % cols
      if (rem != 0) repeat(cols - rem) { out.add(null) }
      repeat(cols) { out.add(null) }
    }
    return out
  }

  /**
   * Normalize only once the caller has loaded dynamic launcher tiles. Before that, [keys] contains
   * only built-ins/widgets, so reconciling would treat persisted app/folder slots as stale and
   * overwrite the user's custom layout.
   */
  fun normalizeSlotsWhenReady(
      saved: List<String?>,
      keys: List<String>,
      cols: Int,
      dynamicTilesLoaded: Boolean,
  ): List<String?> = if (dynamicTilesLoaded) normalizeSlots(saved, keys, cols) else saved

  /**
   * Like [normalizeSlots] but page-aware: pads to whole [pageCapacity] pages, **auto-deletes any
   * fully-empty page**, and (when [keepSpare]) appends one trailing empty page so the user can drag
   * onto a fresh page. In-page blanks (gaps inside a page that still has tiles) are preserved.
   */
  fun normalizeToPages(
      saved: List<String?>,
      keys: List<String>,
      pageCapacity: Int,
      keepSpare: Boolean = true,
  ): List<String?> {
    val reconciled = reconcile(saved, keys)
    if (pageCapacity <= 0) return reconciled
    val padded = reconciled.toMutableList()
    val rem = padded.size % pageCapacity
    if (rem != 0) repeat(pageCapacity - rem) { padded.add(null) }
    // Drop pages that are entirely blank.
    val out = ArrayList<String?>(padded.size)
    padded.chunked(pageCapacity).forEach { page ->
      if (page.any { it != null }) out.addAll(page)
    }
    // Keep one spare empty page to drop into (always at least one page exists).
    if (keepSpare || out.isEmpty()) repeat(pageCapacity) { out.add(null) }
    return out
  }

  /**
   * Page-aware variant of [normalizeSlotsWhenReady]. Used by the launcher once page capacity is
   * known; it must obey the same dynamic-load gate before persisting reconciled slots.
   */
  fun normalizeToPagesWhenReady(
      saved: List<String?>,
      keys: List<String>,
      pageCapacity: Int,
      keepSpare: Boolean = true,
      dynamicTilesLoaded: Boolean,
  ): List<String?> =
      if (dynamicTilesLoaded) normalizeToPages(saved, keys, pageCapacity, keepSpare) else saved

  /** Swap the contents of two slots (either may be blank). Out-of-range/no-op returns the input. */
  fun swap(slots: List<String?>, a: Int, b: Int): List<String?> {
    if (a == b || a !in slots.indices || b !in slots.indices) return slots
    val m = slots.toMutableList()
    val tmp = m[a]
    m[a] = m[b]
    m[b] = tmp
    return m
  }
}
