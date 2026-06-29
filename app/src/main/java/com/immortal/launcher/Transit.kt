/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Worldwide real-time public-transport departures via Transitous — a free,
 * community-run [MOTIS](https://github.com/motis-project/motis) instance that
 * aggregates GTFS + GTFS-realtime feeds from across the globe. No API key, no GMS.
 *
 * Two calls:
 *  - [searchStops] geocodes a free-text query ("Connolly", "Alexanderplatz",
 *    "Times Sq 42 St") to a list of stops the user picks from;
 *  - [fetch] returns the next departures from a chosen stop, soonest first, with
 *    realtime where the operator publishes it.
 *
 * Coverage depends on which feeds Transitous has imported, but it spans most of
 * Europe, North America and beyond — anywhere with an open GTFS feed.
 */
object Transit {

  private const val PREFS = "immortal_transit"
  private const val BASE = "https://api.transitous.org/api/v1"

  /** A transit stop/station the user can pick. [region] is a short locality hint. */
  data class Stop(val id: String, val name: String, val region: String)

  data class Departure(val route: String, val destination: String, val due: String)

  fun savedStopId(context: Context): String = prefs(context).getString("stop_id", "") ?: ""

  fun savedStopName(context: Context): String = prefs(context).getString("stop_name", "") ?: ""

  fun saveStop(context: Context, stop: Stop) =
      prefs(context).edit()
          .putString("stop_id", stop.id.trim())
          .putString("stop_name", stop.name.trim())
          .apply()

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Geocode [query] to transit stops (empty on failure / no match). */
  fun searchStops(query: String, max: Int = 12): List<Stop> {
    if (query.isBlank()) return emptyList()
    return runCatching {
      val json = httpGet("$BASE/geocode?text=${enc(query)}")
      val arr = org.json.JSONArray(json)
      val out = ArrayList<Stop>()
      for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        if (o.optString("type") != "STOP") continue
        val id = o.optString("id")
        val name = o.optString("name")
        if (id.isBlank() || name.isBlank()) continue
        out.add(Stop(id = id, name = name, region = regionOf(o)))
        if (out.size >= max) break
      }
      out
    }.getOrDefault(emptyList())
  }

  /** A short locality hint from the geocoder's admin "areas" (e.g. "Dublin, IE"). */
  private fun regionOf(o: JSONObject): String {
    val country = o.optString("country", "")
    val areas = o.optJSONArray("areas")
    var city = ""
    if (areas != null) {
      // Prefer the area the geocoder marked as the default/most relevant locality.
      for (i in 0 until areas.length()) {
        val a = areas.getJSONObject(i)
        if (a.optBoolean("default", false) || a.optBoolean("matched", false)) {
          city = a.optString("name"); break
        }
      }
    }
    return listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
  }

  /** Next departures for [stopId], soonest first (empty on any failure / no data). */
  fun fetch(stopId: String, max: Int = 8): List<Departure> {
    if (stopId.isBlank()) return emptyList()
    return runCatching {
      val json = httpGet("$BASE/stoptimes?stopId=${enc(stopId)}&n=$max")
      val arr = JSONObject(json).optJSONArray("stopTimes") ?: return emptyList()
      val now = System.currentTimeMillis()
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val place = o.optJSONObject("place")
        // Realtime departure if present, else the scheduled time.
        val depIso = place?.optString("departure")?.takeIf { it.isNotBlank() }
            ?: place?.optString("scheduledDeparture")?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val depMs = parseIso(depIso) ?: return@mapNotNull null
        val route =
            o.optString("routeShortName").ifBlank { o.optString("displayName") }
                .ifBlank { o.optString("mode") }.ifBlank { "?" }
        Departure(
            route = route,
            destination = o.optString("headsign").ifBlank { o.optString("routeLongName") },
            due = dueLabel(depMs - now),
        )
      }
    }.getOrDefault(emptyList())
  }

  private fun dueLabel(deltaMs: Long): String {
    val mins = Math.round(deltaMs / 60000.0).toInt()
    return if (mins <= 0) "Due" else "$mins min"
  }

  /** Parse a UTC ISO-8601 timestamp like "2026-06-14T16:05:00Z" (handles whole secs). */
  private fun parseIso(s: String): Long? = runCatching {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    fmt.parse(s.removeSuffix("Z"))?.time
  }.getOrNull()

  private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

  private fun httpGet(spec: String): String {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    c.setRequestProperty("User-Agent", "Immortal/1.0")
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
  }
}
