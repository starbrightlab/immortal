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
 * Sunrise alarm / wake light. At the set time on the chosen days, the screen brightens
 * gradually from a deep ember to full daylight over [rampMinutes], optionally finishing
 * with a soft chime crescendo — turning a bedroom Portal into a wake light. Persisted in
 * SharedPrefs and re-armed by [ImmortalApp] / [BootReceiver].
 */
object SunriseConfig {

  private const val PREFS = "immortal_sunrise"

  data class Config(
      val enabled: Boolean,
      val hour: Int,
      val minute: Int,
      val rampMinutes: Int,
      val chime: Boolean,
      /** Sun-Sat; if all false the alarm is treated as one-shot (next occurrence). */
      val days: Set<Int>, // Calendar.SUNDAY..Calendar.SATURDAY
  )

  fun load(context: Context): Config {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val days = p.getStringSet("days", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: defaultDays()
    return Config(
        enabled = p.getBoolean("enabled", false),
        hour = p.getInt("hour", 7),
        minute = p.getInt("minute", 0),
        rampMinutes = p.getInt("ramp", 20),
        chime = p.getBoolean("chime", true),
        days = days,
    )
  }

  fun save(context: Context, c: Config) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean("enabled", c.enabled)
        .putInt("hour", c.hour)
        .putInt("minute", c.minute)
        .putInt("ramp", c.rampMinutes)
        .putBoolean("chime", c.chime)
        .putStringSet("days", c.days.map { it.toString() }.toSet())
        .apply()
  }

  // Per-field setters for the registry: clamping, side-effect-free, (Context, value) signature.
  // The `days` Set<Int> has no scalar setter — it's managed by the bespoke day-picker in
  // SunriseSettingsActivity (the registry models scalars, not sets).

  fun setEnabled(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("enabled", on).apply()

  fun setHour(c: Context, h: Int) =
      prefs(c).edit().putInt("hour", h.coerceIn(0, 23)).apply()

  fun setMinute(c: Context, m: Int) =
      prefs(c).edit().putInt("minute", m.coerceIn(0, 59)).apply()

  fun setRampMinutes(c: Context, m: Int) =
      prefs(c).edit().putInt("ramp", m.coerceIn(1, 60)).apply()

  fun setChime(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("chime", on).apply()

  fun setDays(c: Context, days: Set<Int>) =
      prefs(c).edit().putStringSet("days", days.map { it.toString() }.toSet()).apply()

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun defaultDays(): Set<Int> =
      setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)

  /** Next fire time (epoch millis) at/after now for [c], or null if disabled. Pure +
   * testable: pass [from] to compute deterministically. */
  fun nextTrigger(c: Config, from: Calendar = Calendar.getInstance()): Long? {
    if (!c.enabled) return null
    val cal = from.clone() as Calendar
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.set(Calendar.HOUR_OF_DAY, c.hour)
    cal.set(Calendar.MINUTE, c.minute)
    // Search up to 8 days out for the next matching day-of-week (or one-shot).
    for (i in 0..7) {
      val candidate = cal.clone() as Calendar
      candidate.add(Calendar.DAY_OF_YEAR, i)
      if (candidate.timeInMillis <= from.timeInMillis) continue
      val dow = candidate.get(Calendar.DAY_OF_WEEK)
      if (c.days.isEmpty() || dow in c.days) return candidate.timeInMillis
    }
    return null
  }
}
