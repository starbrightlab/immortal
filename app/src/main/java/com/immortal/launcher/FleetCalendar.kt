/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.SettingsDomains
import org.json.JSONObject

/**
 * Calendar-widget slice of the Fleet Agent API (see [FleetRoutes] `/calendar`). Lets the laptop
 * fleet tool read and push the screensaver calendar's feed link and display range over WiFi.
 *
 * This is now a thin façade over the `calendar` [com.immortal.launcher.settings.SettingsDomain]
 * (see [SettingsDomains.calendar]) — the wire format and validation live there, declaratively,
 * so the same definition also drives `/remote/settings` and the on-device renderer. [toJson] folds
 * the domain's specs into the flat legacy payload; [apply] funnels present keys through them.
 *
 * Wire format (both directions use the same field names):
 * ```
 * { "url": "https://…/basic.ics",   // "" clears the calendar (widget off)
 *   "range": "day|3day|week|agenda" } // unknown values clamp to "day"
 * ```
 */
object FleetCalendar {

  /** The display ranges a client can choose from, advertised in [toJson]. */
  val RANGES =
      listOf(
          CalendarFeed.RANGE_DAY,
          CalendarFeed.RANGE_3DAY,
          CalendarFeed.RANGE_WEEK,
          CalendarFeed.RANGE_AGENDA,
      )

  /** Pure render of the calendar settings the agent reports back (the flat legacy payload). */
  fun toJson(s: ScreensaverConfig.Settings): JSONObject = SettingsDomains.calendar.flatJson(s)

  /**
   * Apply a pushed calendar config. Only the keys actually present are touched, so a partial push
   * (e.g. just `{"range":"week"}`) leaves the link untouched. Returns the list of applied keys.
   */
  fun apply(context: Context, body: JSONObject): List<String> =
      SettingsDomains.calendar.apply(context, body).toList()
}
