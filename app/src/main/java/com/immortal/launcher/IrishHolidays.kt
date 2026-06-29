/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Irish calendar pack — public/bank holidays and a few well-known saints' days, for the
 * "Irish" household. Pure date math (Easter is computed), so it works offline forever.
 */
object IrishHolidays {

  /** Today's holiday/observance label, or "" if there isn't one. */
  fun forToday(cal: Calendar = Calendar.getInstance()): String {
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    val weekOfMonth = (d - 1) / 7 + 1
    val isLastMonday = dow == Calendar.MONDAY && d + 7 > daysInMonth(y, m)

    // Fixed-date holidays + saints.
    when (m to d) {
      1 to 1 -> return "New Year's Day"
      2 to 1 -> return "St Brigid's Day"
      3 to 17 -> return "St Patrick's Day"
      6 to 9 -> return "St Columba's Day"
      12 to 25 -> return "Christmas Day"
      12 to 26 -> return "St Stephen's Day"
    }

    // Movable bank holidays (first/last Monday of certain months).
    if (dow == Calendar.MONDAY) {
      if (m == 2 && weekOfMonth == 1) return "St Brigid's Day (bank holiday)"
      if (m == 5 && weekOfMonth == 1) return "May Bank Holiday"
      if (m == 6 && weekOfMonth == 1) return "June Bank Holiday"
      if (m == 8 && weekOfMonth == 1) return "August Bank Holiday"
      if (m == 10 && isLastMonday) return "October Bank Holiday"
    }

    // Easter Monday.
    val easter = easterSunday(y)
    val easterMonday = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    if (sameDay(cal, easterMonday)) return "Easter Monday"
    if (sameDay(cal, easter)) return "Easter Sunday"
    val goodFriday = (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -2) }
    if (sameDay(cal, goodFriday)) return "Good Friday"

    return ""
  }

  private fun sameDay(a: Calendar, b: Calendar): Boolean =
      a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
          a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

  private fun daysInMonth(year: Int, month1: Int): Int =
      GregorianCalendar(year, month1 - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH)

  /** Western (Gregorian) Easter Sunday — Anonymous/Meeus algorithm. */
  fun easterSunday(year: Int): Calendar {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val mth = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * mth + 114) / 31 // 3=March, 4=April
    val day = ((h + l - 7 * mth + 114) % 31) + 1
    return GregorianCalendar(year, month - 1, day)
  }
}
