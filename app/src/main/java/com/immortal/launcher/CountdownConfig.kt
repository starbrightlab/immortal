/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined countdown events shown as chips on the home screen ("🎂 Birthday ·
 * 12 days"). Stored as a small JSON array in SharedPrefs. Events can repeat yearly
 * (birthdays, anniversaries — [year] == 0) or be one-off (a trip — a real year).
 */
object CountdownConfig {

  private const val PREFS = "immortal_countdown"
  private const val KEY = "events"

  data class Event(
      val id: Long,
      val label: String,
      val emoji: String,
      val month: Int, // 1-12
      val day: Int, // 1-31
      val year: Int, // 0 = repeats yearly
  ) {
    /** Whole days from [now] until the next occurrence. 0 = today, negative is past
     * (only possible for one-off events that have already happened). */
    fun daysUntil(now: Calendar = Calendar.getInstance()): Int {
      val today = atMidnight(now)
      val target = atMidnight(now).apply {
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        if (year > 0) set(Calendar.YEAR, year)
      }
      if (year == 0 && target.before(today)) target.add(Calendar.YEAR, 1)
      return ((target.timeInMillis - today.timeInMillis) / DAY_MS).toInt()
    }

    /** "today", "tomorrow", "in N days", or "N days ago". */
    fun phrase(now: Calendar = Calendar.getInstance()): String =
        when (val d = daysUntil(now)) {
          0 -> "today"
          1 -> "tomorrow"
          -1 -> "yesterday"
          else -> if (d > 0) "in $d days" else "${-d} days ago"
        }
  }

  private const val DAY_MS = 24L * 60 * 60 * 1000

  private fun atMidnight(c: Calendar): Calendar =
      (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): List<Event> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Event(
            id = o.optLong("id", System.nanoTime()),
            label = o.optString("label", "Event"),
            emoji = o.optString("emoji", "📅"),
            month = o.optInt("month", 1),
            day = o.optInt("day", 1),
            year = o.optInt("year", 0),
        )
      }
    }.getOrDefault(emptyList())
  }

  /** Events sorted by how soon they occur (soonest first). */
  fun loadSorted(context: Context): List<Event> =
      load(context).sortedBy { it.daysUntil() }

  private fun save(context: Context, events: List<Event>) {
    val arr = JSONArray()
    events.forEach { e ->
      arr.put(
          JSONObject()
              .put("id", e.id)
              .put("label", e.label)
              .put("emoji", e.emoji)
              .put("month", e.month)
              .put("day", e.day)
              .put("year", e.year))
    }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }

  fun add(context: Context, label: String, emoji: String, month: Int, day: Int, year: Int) {
    val e = Event(System.currentTimeMillis(), label.ifBlank { "Event" }, emoji,
        month.coerceIn(1, 12), day.coerceIn(1, 31), if (year < 0) 0 else year)
    save(context, load(context) + e)
  }

  fun remove(context: Context, id: Long) {
    save(context, load(context).filterNot { it.id == id })
  }
}
