/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Daily Islamic prayer times computed on-device for the household that wants them — a
 * calendar pack like the name-day/feast packs. Uses the Muslim World League convention
 * (Fajr 18°, Isha 17°, standard Asr shadow factor) and the device's location + time
 * zone. Pure astronomy; no network, no key.
 */
object PrayerTimes {

  private const val DEG = PI / 180.0
  private const val FAJR_ANGLE = 18.0
  private const val ISHA_ANGLE = 17.0

  /** The five times as "HH:mm" strings for [date], or null if location is unknown. */
  fun forToday(context: Context, date: Calendar = Calendar.getInstance()): Map<String, String>? {
    val (lat, lon) = Weather.coordinates(context) ?: return null
    val tzHours = TimeZone.getDefault().getOffset(date.timeInMillis) / 3_600_000.0
    return compute(lat, lon, tzHours, date)
  }

  /** Today's next (or current) prayer as a header line, e.g. "🕌 Maghrib 21:34". */
  fun nextLine(context: Context, now: Calendar = Calendar.getInstance()): String {
    val times = forToday(context, now) ?: return ""
    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val order = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
    for (name in order) {
      val t = times[name] ?: continue
      val (h, m) = t.split(":").map { it.toInt() }
      if (h * 60 + m >= nowMin) return "🕌 $name $t"
    }
    // All passed today → first prayer tomorrow.
    return times["Fajr"]?.let { "🕌 Fajr $it (tomorrow)" } ?: ""
  }

  /** Pure computation, exposed for tests. [tzHours] is the UTC offset in hours.
   * Follows the standard PrayTimes sun-position model; all working angles are kept in
   * degrees and normalized (the mean longitude grows unbounded, so it MUST be wrapped to
   * 0–360 before it feeds the equation of time). */
  fun compute(lat: Double, lon: Double, tzHours: Double, date: Calendar): Map<String, String> {
    val d = julianDay(date) - 2451545.0
    val g = fixAngle(357.529 + 0.98560028 * d) // mean anomaly, deg
    val q = fixAngle(280.459 + 0.98564736 * d) // mean longitude, deg (normalized!)
    val l = fixAngle(q + 1.915 * sin(g * DEG) + 0.020 * sin(2 * g * DEG)) // ecliptic lon, deg
    val e = 23.439 - 0.00000036 * d // obliquity, deg
    val decl = kotlin.math.asin(sin(e * DEG) * sin(l * DEG)) // radians
    val ra = fixHours(atan2(cos(e * DEG) * sin(l * DEG), cos(l * DEG)) / DEG / 15.0) // hours
    val eqt = q / 15.0 - ra // equation of time, hours

    // Solar noon (Dhuhr) in local time.
    val dhuhr = 12.0 + tzHours - lon / 15.0 - eqt
    val latR = lat * DEG

    // Hour angle (hours) for the sun reaching altitude [angleDeg] below(+)/above horizon.
    fun hourAngle(angleDeg: Double): Double {
      val cosH = (-sin(angleDeg * DEG) - sin(latR) * sin(decl)) / (cos(latR) * cos(decl))
      return acos(cosH.coerceIn(-1.0, 1.0)) / DEG / 15.0
    }

    // Asr: shadow length factor 1 (Shafi'i) — the (positive) sun altitude where an
    // object's shadow equals its length plus the noon shadow: alt = arccot(1 + tan|lat−decl|).
    val asrAlt = atan2(1.0, 1.0 + tan(abs(latR - decl))) // radians, above horizon
    val asrHA = run {
      val cosH = (sin(asrAlt) - sin(latR) * sin(decl)) / (cos(latR) * cos(decl))
      acos(cosH.coerceIn(-1.0, 1.0)) / DEG / 15.0
    }

    val sunrise = dhuhr - hourAngle(0.833)
    val sunset = dhuhr + hourAngle(0.833)
    val fajr = dhuhr - hourAngle(FAJR_ANGLE)
    val asr = dhuhr + asrHA
    val isha = dhuhr + hourAngle(ISHA_ANGLE)

    return linkedMapOf(
        "Fajr" to fmt(fajr),
        "Sunrise" to fmt(sunrise),
        "Dhuhr" to fmt(dhuhr),
        "Asr" to fmt(asr),
        "Maghrib" to fmt(sunset),
        "Isha" to fmt(isha),
    )
  }

  private fun fixHours(h: Double): Double {
    var x = h % 24.0
    if (x < 0) x += 24.0
    return x
  }

  private fun fixAngle(a: Double): Double {
    var x = a % 360.0
    if (x < 0) x += 360.0
    return x
  }

  private fun fmt(hours: Double): String {
    val h = fixHours(hours)
    var hh = h.toInt()
    var mm = ((h - hh) * 60).toInt()
    if (mm >= 60) { mm -= 60; hh = (hh + 1) % 24 }
    return "%02d:%02d".format(hh, mm)
  }

  private fun julianDay(c: Calendar): Double {
    val year = c.get(Calendar.YEAR)
    val month = c.get(Calendar.MONTH) + 1
    val day = c.get(Calendar.DAY_OF_MONTH)
    val a = (14 - month) / 12
    val y = year + 4800 - a
    val m = month + 12 * a - 3
    val jdn = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    return jdn - 0.5
  }
}
