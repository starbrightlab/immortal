/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.util.Calendar

/**
 * Installable calendar packs — the name-day idea, broadened. A household adds the pack
 * that fits it: Romanian name-days + Orthodox feasts (the original, toggled by the
 * existing header flags), Irish bank holidays + saints, or daily Islamic prayer times.
 * Each pack contributes one or more lines to the home header; they're all keyless and
 * computed on-device.
 *
 * The Romanian/Orthodox pack keeps using [ImmortalSettings] flags (so existing installs
 * are untouched); the newer packs store their on/off here.
 */
object CalendarPacks {

  private const val PREFS = "immortal_packs"

  const val IRISH = "irish"
  const val PRAYER = "prayer"

  data class Pack(val id: String, val title: String, val blurb: String)

  /** Packs the user can switch on (beyond the built-in Romanian/Orthodox header flags). */
  val AVAILABLE =
      listOf(
          Pack(IRISH, "Irish holidays", "Bank holidays + saints' days (St Patrick's, St Brigid's…)"),
          Pack(PRAYER, "Prayer times", "Daily Islamic prayer times for your location"),
      )

  fun isEnabled(context: Context, id: String): Boolean =
      prefs(context).getBoolean(id, false)

  fun setEnabled(context: Context, id: String, on: Boolean) {
    prefs(context).edit().putBoolean(id, on).apply()
  }

  /** Header lines contributed by the enabled add-on packs, in display order. */
  fun headerLines(context: Context, now: Calendar = Calendar.getInstance()): List<String> {
    val out = ArrayList<String>(2)
    if (isEnabled(context, IRISH)) {
      val h = IrishHolidays.forToday(now)
      if (h.isNotEmpty()) out.add("🍀 $h")
    }
    if (isEnabled(context, PRAYER)) {
      val line = PrayerTimes.nextLine(context, now)
      if (line.isNotEmpty()) out.add(line)
    }
    return out
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
