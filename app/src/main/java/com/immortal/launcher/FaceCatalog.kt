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

  /** A pickable face: a stable [id] (persisted), a display [name]/[tagline], and its builder. */
  data class Entry(
      val id: String,
      val name: String,
      val tagline: String,
      val build: (Context) -> Face,
  )

  /** The id used when nothing is selected (and the load default in [ScreensaverConfig]). */
  const val DEFAULT_ID = "immortal-classic"

  val entries: List<Entry> =
      listOf(
          Entry(
              "immortal-classic",
              "Immortal",
              "Clock, date, weather and now-playing in the corners",
          ) {
            Face.immortalClassic(it)
          },
          Entry("flip", "Flip clock", "The retro split-flap clock, full screen") { Face.flip(it) },
          Entry("big", "Big clock", "A large clock, centred and clean") { bigClock(it) },
          Entry("bold", "Bold", "A tall condensed clock with the date") { boldClock(it) },
          Entry("minimal", "Minimal", "Just the time, quietly in the corner") { minimalClock(it) },
      )

  /** The face the user has selected (falls back to the first entry if the id is unknown). */
  fun active(context: Context): Face = entryFor(ScreensaverConfig.load(context).faceId).build(context)

  fun entryFor(id: String?): Entry = entries.firstOrNull { it.id == id } ?: entries.first()

  private fun format(context: Context): String =
      if (ImmortalSettings.use24HourClock(context)) "24h" else "12h"

  private fun nowPlaying(context: Context): NowPlayingSpec =
      NowPlayingSpec(
          enabled = ScreensaverConfig.load(context).showNowPlaying,
          position = GridPosition.BOTTOM_RIGHT,
      )

  /** A large, centred light clock — nothing else but the now-playing card. */
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
                  sizeScale = 150,
                  position = GridPosition.MIDDLE_CENTER,
                  shadow = Shadow.SOFT,
              ),
          nowPlaying = nowPlaying(context),
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
                  sizeScale = 180,
                  position = GridPosition.MIDDLE_CENTER,
                  shadow = Shadow.STRONG,
                  showDate = true,
                  dateFormat = DateFormat.LONG,
              ),
          nowPlaying = nowPlaying(context),
          battery = BatterySpec(enabled = false),
      )

  /** Quiet small time in the corner — no date, weather, battery or now-playing. */
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
          nowPlaying = NowPlayingSpec(enabled = false),
          battery = BatterySpec(enabled = false),
      )
}
