/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGridTest {

  @Test
  fun normalize_keepsBlanksAndOrder_appendsNew_padsTrailingRow() {
    // 2 columns; saved has a gap (null) between a and b; c is new.
    val saved = listOf<String?>("a", null, "b")
    val result = HomeGrid.normalizeSlots(saved, listOf("a", "b", "c"), cols = 2)
    // The gap is preserved, c appended, then padded to fill the row + one spare row (2 cols).
    assertEquals(listOf("a", null, "b", "c", null, null), result)
  }

  @Test
  fun normalize_dropsStaleAndDuplicateKeysToBlanks() {
    val saved = listOf<String?>("a", "gone", "a", "b")
    val result = HomeGrid.normalizeSlots(saved, listOf("a", "b"), cols = 3)
    // "gone" (stale) and the second "a" (dup) become blanks; nothing new to append.
    // out before pad: [a, null, null, b] -> size 4, rem 1 -> +2 to fill row (6) -> +3 spare = 9
    assertEquals(listOf("a", null, null, "b", null, null, null, null, null), result)
  }

  @Test
  fun normalizeWhenReady_keepsSavedSlotsUntilDynamicTilesLoad() {
    val saved = listOf<String?>("builtin:calls", "app:custom", "folder:Family")
    val partialKeys = listOf("builtin:calls", "builtin:store")

    assertEquals(
        saved,
        HomeGrid.normalizeSlotsWhenReady(
            saved, partialKeys, cols = 3, dynamicTilesLoaded = false),
    )
    // The stale slots after calls are trailing blanks, so the new store tile takes the first one.
    assertEquals(
        listOf("builtin:calls", "builtin:store", null, null, null, null),
        HomeGrid.normalizeSlotsWhenReady(
            saved, partialKeys, cols = 3, dynamicTilesLoaded = true),
    )
  }

  @Test
  fun normalize_newTileFillsRowPadding_insteadOfStartingNewRow() {
    // Saved list was padded to whole rows (as normalizeSlots persists it); c is new.
    val saved = listOf<String?>("a", "b", "x", null, null, null)
    val result = HomeGrid.normalizeSlots(saved, listOf("a", "b", "x", "c"), cols = 3)
    assertEquals(listOf("a", "b", "x", "c", null, null, null, null, null), result)
  }

  @Test
  fun normalizeToPages_newTileLandsOnFirstPage_whenSavedPageIsPadded() {
    // One saved page of 4, padded with blanks (as the launcher persists it); d is a new app.
    val saved = listOf<String?>("a", "b", "c", null)
    assertEquals(
        listOf("a", "b", "c", "d"),
        HomeGrid.normalizeToPages(saved, listOf("a", "b", "c", "d"), 4, keepSpare = false),
    )
  }

  @Test
  fun normalizeToPages_newTileKeepsInnerUserGap() {
    // The user left a gap between a and b; new tiles go after b, never into the gap.
    val saved = listOf<String?>("a", null, "b", null, null, null)
    assertEquals(
        listOf("a", null, "b", "c", "d", null),
        HomeGrid.normalizeToPages(saved, listOf("a", "b", "c", "d"), 6, keepSpare = false),
    )
  }

  @Test
  fun normalizeToPages_appendsToNextPage_whenNoBlankLeftAfterLastTile() {
    val saved = listOf<String?>("a", null, "b", "c")
    assertEquals(
        listOf("a", null, "b", "c", "d", null, null, null),
        HomeGrid.normalizeToPages(saved, listOf("a", "b", "c", "d"), 4, keepSpare = false),
    )
  }

  @Test
  fun swap_exchangesTwoSlots_includingBlanks() {
    val slots = listOf<String?>("a", null, "b")
    assertEquals(listOf<String?>(null, "a", "b"), HomeGrid.swap(slots, 0, 1))
    assertEquals(listOf<String?>("b", null, "a"), HomeGrid.swap(slots, 0, 2))
    // No-op / out of range returns the input unchanged.
    assertEquals(slots, HomeGrid.swap(slots, 1, 1))
    assertEquals(slots, HomeGrid.swap(slots, 1, 9))
  }

  @Test
  fun normalizeToPages_padsToWholePages_andKeepsSpareWhenArranging() {
    // keepSpare=true (arranging): pad the page, then add a trailing spare page to drop into.
    assertEquals(
        listOf("a", "b", "c", null, null, null, null, null),
        HomeGrid.normalizeToPages(listOf("a", "b", "c"), listOf("a", "b", "c"), 4, keepSpare = true),
    )
  }

  @Test
  fun normalizeToPages_compactsWhenNotArranging() {
    // keepSpare=false: pad to one page, no spare, no extra empties.
    assertEquals(
        listOf("a", "b", null, null),
        HomeGrid.normalizeToPages(listOf("a", "b"), listOf("a", "b"), 4, keepSpare = false),
    )
  }

  @Test
  fun normalizeToPages_autoDeletesEmptyInteriorPage() {
    // Capacity 2: tiles on page 0 and page 2, page 1 entirely blank -> empty page removed.
    val saved = listOf<String?>("a", "b", null, null, "c", "d")
    assertEquals(
        listOf("a", "b", "c", "d"),
        HomeGrid.normalizeToPages(saved, listOf("a", "b", "c", "d"), 2, keepSpare = false),
    )
  }

  @Test
  fun normalizeToPagesWhenReady_keepsSavedSlotsUntilDynamicTilesLoad() {
    val saved = listOf<String?>("app:a", "app:b", null, "folder:Media")

    assertEquals(
        saved,
        HomeGrid.normalizeToPagesWhenReady(
            saved,
            keys = listOf("builtin:calls"),
            pageCapacity = 4,
            keepSpare = false,
            dynamicTilesLoaded = false,
        ),
    )
    assertEquals(
        listOf(null, null, null, "folder:Media"),
        HomeGrid.normalizeToPagesWhenReady(
            saved,
            keys = listOf("folder:Media"),
            pageCapacity = 4,
            keepSpare = false,
            dynamicTilesLoaded = true,
        ),
    )
  }
}
