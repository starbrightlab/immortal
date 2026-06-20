/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * The built-in catalog of clock faces the user can pick from (Screensaver → Clock face). Each
 * entry is a named [Face] descriptor built on demand (some read the user's 12/24-hour and
 * now-playing preferences). [active] resolves the user's choice from [ScreensaverConfig.faceId];
 * [PhotoFrameController] renders it.
 *
 * These are the free, bundled faces. Premium faces authored in mantelframe and delivered on demand
 * are a later sprint — they'll slot in here as additional entries, no renderer change needed.
 */
object FaceCatalog {

  /**
   * A pickable face: a stable [id] (persisted), a display [name]/[tagline], its builder, and an
   * optional set of size variants — the [ClockSpec.sizeScale] for Small/Medium/Large, applied by
   * [active] from the user's [ScreensaverConfig.faceSizeIndex]. Empty = a fixed-size face (no size
   * control shown). Tuned per face so even Large doesn't overflow the frame.
   */
  data class Entry(
      val id: String,
      val name: String,
      val tagline: String,
      val sizes: List<Int> = emptyList(),
      val build: (Context) -> Face,
  )

  /** The id used when nothing is selected (and the load default in [ScreensaverConfig]). */
  const val DEFAULT_ID = "immortal-classic"

  /** Labels for the size variants, indexed by [ScreensaverConfig.faceSizeIndex]. */
  val SIZE_LABELS = listOf("Small", "Medium", "Large")

  val entries: List<Entry> =
      listOf(
          Entry(
              "immortal-classic",
              "Immortal",
              "Clock, date, weather and now-playing in the corners",
          ) {
            Face.immortalClassic(it)
          },
          Entry(
              "flip",
              "Flip clock",
              "The retro split-flap clock, full screen",
              sizes = listOf(76, 88, 96),
          ) {
            Face.flip(it)
          },
          Entry("big", "Big clock", "A large clock, centred and clean", sizes = listOf(280, 380, 460)) {
            bigClock(it)
          },
          Entry("bold", "Bold", "A tall condensed clock with the date", sizes = listOf(320, 430, 500)) {
            boldClock(it)
          },
          Entry("minimal", "Minimal", "Just the time, quietly in the corner") { minimalClock(it) },
      )

  /** A face that draws no clock (faces turned off) — photos only; now-playing still follows its switch. */
  private fun noClock(): Face =
      Face(id = "none", clock = ClockSpec(mode = ClockMode.NONE), battery = BatterySpec(enabled = false))

  /** The face the user has selected, with their size variant applied. */
  fun active(context: Context): Face {
    val cfg = ScreensaverConfig.load(context)
    if (!cfg.facesEnabled) return noClock()
    val entry = entryFor(cfg.faceId)
    val face = entry.build(context)
    if (entry.sizes.isEmpty()) return face
    val scale = entry.sizes[cfg.faceSizeIndex.coerceIn(0, entry.sizes.size - 1)]
    return face.copy(clock = face.clock.copy(sizeScale = scale))
  }

  fun entryFor(id: String?): Entry = entries.firstOrNull { it.id == id } ?: entries.first()

  private fun format(context: Context): String =
      if (ImmortalSettings.use24HourClock(context)) "24h" else "12h"

  // The now-playing card is no longer a per-face widget — it's a global switch
  // (ScreensaverConfig.showNowPlaying) the renderer honours on every face, at BOTTOM_RIGHT. Faces
  // below only need to set the clock + which corner widgets (date/battery/weather) they want.

  /** A large, centred light clock — just the time (plus the global now-playing card). */
  private fun bigClock(context: Context): Face =
      Face(
          id = "big",
          name = "Big clock",
          clock =
              ClockSpec(
                  mode = ClockMode.DIGITAL,
                  font = Face.FONT_SANS_LIGHT,
                  fontWeight = 200,
                  color = "#ffffff",
                  format = format(context),
                  separator = Separator.COLON,
                  sizeScale = 380,
                  position = GridPosition.MIDDLE_CENTER,
                  shadow = Shadow.SOFT,
              ),
          battery = BatterySpec(enabled = false),
      )

  /** A tall condensed (Bebas Neue) statement clock with the date beneath. */
  private fun boldClock(context: Context): Face =
      Face(
          id = "bold",
          name = "Bold",
          clock =
              ClockSpec(
                  mode = ClockMode.DIGITAL,
                  font = "Bebas Neue",
                  fontWeight = 400,
                  color = "#ffffff",
                  format = format(context),
                  separator = Separator.COLON,
                  sizeScale = 440,
                  position = GridPosition.MIDDLE_CENTER,
                  shadow = Shadow.STRONG,
                  showDate = true,
                  dateFormat = DateFormat.LONG,
              ),
          battery = BatterySpec(enabled = false),
      )

  /** Quiet small time in the corner — no date, weather or battery (now-playing stays global). */
  private fun minimalClock(context: Context): Face =
      Face(
          id = "minimal",
          name = "Minimal",
          clock =
              ClockSpec(
                  mode = ClockMode.DIGITAL,
                  font = Face.FONT_SANS_LIGHT,
                  fontWeight = 200,
                  color = "#ffffff",
                  format = format(context),
                  separator = Separator.COLON,
                  sizeScale = 90,
                  position = GridPosition.BOTTOM_LEFT,
                  shadow = Shadow.SOFT,
              ),
          battery = BatterySpec(enabled = false),
      )
}
