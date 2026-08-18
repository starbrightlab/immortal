/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import kotlin.math.hypot

/**
 * Dwell-to-fold: the rules deciding when dragging one app onto another makes a folder, as opposed
 * to just reordering the grid. Pure — no Compose, no Android — because this is the part that has
 * regressed twice ([HomeActivity] owns only the state plumbing around it).
 *
 * The interaction: drag an app over another app, hold still for [DWELL_MS], and the target
 * highlights; releasing then folds the pair. A quick pass-through reorders instead.
 *
 * Two properties matter more than they look, and both are what broke last time:
 *
 *  - **A gap between tiles is "no news", not "cancel".** The home grid spaces its tiles apart, and
 *    those gutters belong to no slot, so a finger crossing one hit-tests as nothing. Treating that
 *    as "the user left the app" cleared the pending target — and since the dwell timer only
 *    restarts on movement, which is exactly what stops when someone holds still to fold, the fold
 *    could then never arm at all. See [keepCandidate].
 *  - **A resting finger still jitters.** Pointer changes are reported for a single pixel, so a
 *    dwell that restarts on *any* movement never completes on a capacitive panel. Movement has to
 *    clear [TOLERANCE_DP] before it counts as the user moving on. See [movedEnough].
 */
object HomeFold {
  /** Tile-key prefixes. Canonical here so the pure rules can read a key without Compose. */
  const val APP_KEY = "app:"
  const val FOLDER_KEY = "folder:"

  /** How long an app must rest over another before releasing folds instead of reorders. */
  const val DWELL_MS = 1000L

  /** Movement under this (in dp) is hand tremor, not the user moving to another tile. */
  const val TOLERANCE_DP = 12f

  /**
   * True when the pointer has moved far enough from where the dwell started to count as real
   * movement, restarting the timer. Null [anchorX]/[anchorY] means no dwell is in progress yet.
   */
  fun movedEnough(
      anchorX: Float?,
      anchorY: Float?,
      x: Float,
      y: Float,
      tolerancePx: Float,
  ): Boolean {
    if (anchorX == null || anchorY == null) return true
    return hypot(x - anchorX, y - anchorY) > tolerancePx
  }

  /**
   * The fold candidate after a pointer sample: the app a pause would fold with, or null for none.
   *
   * [slotIndexUnderPointer] null means the finger is in the gutter between tiles, which hit-tests
   * as nothing. That says nothing about intent, so [current] is kept — clearing it there is the
   * regression this function exists to prevent.
   */
  fun nextCandidate(
      dragged: String,
      slotIndexUnderPointer: Int?,
      tileUnderPointer: String?,
      current: String?,
  ): String? =
      if (slotIndexUnderPointer == null) current
      else candidateFor(dragged, tileUnderPointer)

  /**
   * The app a pause here could fold with, or null if this tile isn't a fold target. Only
   * app-on-app folds; a folder, widget, built-in, blank slot, or the dragged tile's own slot all
   * clear the candidate.
   */
  fun candidateFor(dragged: String, tileUnderPointer: String?): String? =
      if (dragged.startsWith(APP_KEY) &&
          tileUnderPointer != null &&
          tileUnderPointer.startsWith(APP_KEY) &&
          tileUnderPointer != dragged) {
        tileUnderPointer
      } else {
        null
      }

  /** True when an armed target can actually become a folder with [dragged]. */
  fun canFold(dragged: String?, armed: String?): Boolean =
      dragged != null &&
          armed != null &&
          dragged.startsWith(APP_KEY) &&
          armed.startsWith(APP_KEY) &&
          armed != dragged
}
