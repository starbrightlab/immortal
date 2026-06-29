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
 * Orthodox feast-day calendar (Romanian Orthodox use). Covers the Twelve Great
 * Feasts plus Pascha and a few widely-marked days:
 *  - FIXED feasts fall on the same Gregorian date each year (the Romanian Church
 *    keeps the revised-Julian calendar for fixed feasts, so e.g. Christmas is Dec 25);
 *  - MOVABLE feasts are computed relative to Orthodox Pascha, whose date follows the
 *    Julian paschalion. We compute it with the Meeus Julian algorithm, then convert
 *    to the Gregorian calendar (+13 days, valid 1900–2099).
 *
 * [forToday] returns the feast for today, or "" if it isn't a tracked feast.
 */
object FeastDays {

  // Fixed great feasts: month*100 + day -> name.
  private val FIXED: Map<Int, String> = mapOf(
      106 to "Boboteaza (Theophany)",
      107 to "Soborul Sf. Ioan Botezătorul",
      202 to "Întâmpinarea Domnului",
      325 to "Buna Vestire",
      806 to "Schimbarea la Față",
      815 to "Adormirea Maicii Domnului",
      908 to "Nașterea Maicii Domnului",
      914 to "Înălțarea Sfintei Cruci",
      1121 to "Intrarea în Biserică a Maicii Domnului",
      1225 to "Nașterea Domnului (Crăciun)",
  )

  // Movable feasts: day offset from Pascha -> name.
  private val MOVABLE: Map<Int, String> = mapOf(
      -48 to "Lăsatul secului",
      -7 to "Florii (Palm Sunday)",
      -2 to "Vinerea Mare (Good Friday)",
      0 to "Învierea Domnului (Paște)",
      1 to "A doua zi de Paște",
      39 to "Înălțarea Domnului",
      49 to "Rusaliile (Pentecost)",
  )

  /** Orthodox Pascha for [year] as a Gregorian-calendar date (midnight, local zone). */
  fun orthodoxEaster(year: Int): Calendar {
    val a = year % 4
    val b = year % 7
    val c = year % 19
    val d = (19 * c + 15) % 30
    val e = (2 * a + 4 * b - d + 34) % 7
    val julianMonth = (d + e + 114) / 31 // 3 = March, 4 = April
    val julianDay = ((d + e + 114) % 31) + 1
    // Julian → Gregorian: +13 days for 1900–2099. Let Calendar normalize the rollover.
    val cal = GregorianCalendar(year, julianMonth - 1, julianDay, 0, 0, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_YEAR, 13)
    return cal
  }

  /** Today's feast name, or "" if today isn't a tracked feast. Movable feasts win
   * ties (they're the rarer, more notable events). */
  fun forToday(now: Calendar = Calendar.getInstance()): String {
    val today = startOfDay(now)
    val pascha = startOfDay(orthodoxEaster(now.get(Calendar.YEAR)))
    val offset = ((today.timeInMillis - pascha.timeInMillis) / DAY_MS).toInt()
    MOVABLE[offset]?.let { return it }
    return FIXED[(now.get(Calendar.MONTH) + 1) * 100 + now.get(Calendar.DAY_OF_MONTH)] ?: ""
  }

  private const val DAY_MS = 24L * 60 * 60 * 1000

  private fun startOfDay(c: Calendar): Calendar {
    val x = c.clone() as Calendar
    x.set(Calendar.HOUR_OF_DAY, 0)
    x.set(Calendar.MINUTE, 0)
    x.set(Calendar.SECOND, 0)
    x.set(Calendar.MILLISECOND, 0)
    return x
  }
}
