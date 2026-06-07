/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Keyless weather for the header and the photo frame.
 *
 * Robustness matters here: the free IP-geolocation services rate-limit hard
 * (ipapi.co returns HTTP 429 once its quota is hit), which previously blanked the
 * weather everywhere. So we:
 *   1. resolve the device's location ONCE and cache it (a fixed Portal doesn't
 *      move), so geolocation is hit at most once rather than on every refresh;
 *   2. try multiple geolocators (ipwho.is first — far more generous — then
 *      ipapi.co); and
 *   3. read the temperature from Open-Meteo, which is keyless and reliable.
 */
object Weather {

  private const val PREFS = "immortal_weather"

  /** Returns e.g. "☀️ 72°", or "" if it can't be fetched (caller can retry). */
  fun fetch(context: Context): String =
      runCatching {
            val (lat, lon) = location(context) ?: return ""
            // °F or °C per the user's setting (default: follow the device locale).
            val unit = if (ImmortalSettings.useFahrenheit(context)) "fahrenheit" else "celsius"
            val w =
                JSONObject(
                    httpGet(
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,weather_code&temperature_unit=$unit"))
            val cur = w.getJSONObject("current")
            "${emoji(cur.getInt("weather_code"))} ${cur.getDouble("temperature_2m").roundToInt()}°"
          }
          .getOrDefault("")

  /** Cached lat/lon, or a fresh geolocation (cached on success). */
  private fun location(context: Context): Pair<Double, Double>? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.getString("lat", null)?.toDoubleOrNull()?.let { la ->
      prefs.getString("lon", null)?.toDoubleOrNull()?.let { lo -> return la to lo }
    }
    for (url in listOf("https://ipwho.is/", "https://ipapi.co/json/")) {
      val coords =
          runCatching {
                val j = JSONObject(httpGet(url))
                val la = j.optDouble("latitude", Double.NaN)
                val lo = j.optDouble("longitude", Double.NaN)
                if (!la.isNaN() && !lo.isNaN() && (la != 0.0 || lo != 0.0)) la to lo else null
              }
              .getOrNull()
      if (coords != null) {
        prefs
            .edit()
            .putString("lat", coords.first.toString())
            .putString("lon", coords.second.toString())
            .apply()
        return coords
      }
    }
    return null
  }

  /** One day of the multi-day forecast. [label] is "Today" then "Mon", "Tue", … */
  data class DayForecast(val label: String, val code: Int, val hi: Int, val lo: Int)

  /** One hour of the hourly forecast. [label] is "Now" then "1 PM", "2 PM", … */
  data class HourForecast(val label: String, val code: Int, val temp: Int)

  /** A combined forecast: both views are fetched in one call so the home-screen
   * widget can switch between hourly and 7-day without re-hitting the network. */
  data class Forecast(val days: List<DayForecast>, val hours: List<HourForecast>)

  /**
   * Fetches a 7-day daily forecast and the next ~12 hours of hourly forecast from
   * Open-Meteo (keyless), for the home-screen weather widget. Returns null if it
   * can't be fetched (caller can retry). `timezone=auto` makes Open-Meteo return
   * local times, which we parse in the device's default zone.
   */
  fun fetchForecast(context: Context): Forecast? =
      runCatching {
            val (lat, lon) = location(context) ?: return null
            // °F or °C per the user's setting (default: follow the device locale).
            val json = httpGet(forecastUrl(lat, lon, ImmortalSettings.useFahrenheit(context)))
            parseForecast(json, System.currentTimeMillis())
          }
          .getOrNull()

  /** The Open-Meteo request for [fetchForecast]; split out so the unit choice can be
   * verified in tests. `timezone=auto` makes the response use the location's local
   * times, which [parseForecast] reads in the device's default zone. */
  internal fun forecastUrl(lat: Double, lon: Double, fahrenheit: Boolean): String {
    val unit = if (fahrenheit) "fahrenheit" else "celsius"
    return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
        "&hourly=weather_code,temperature_2m" +
        "&forecast_days=7&temperature_unit=$unit&timezone=auto"
  }

  /** Pure parse of an Open-Meteo forecast response. [nowMillis] is the reference "now"
   * used to drop already-past hours, passed in (rather than read from the clock) so the
   * hourly logic is deterministic under test. */
  internal fun parseForecast(json: String, nowMillis: Long): Forecast {
    val root = JSONObject(json)
    return Forecast(days = parseDays(root), hours = parseHours(root, nowMillis))
  }

  private fun parseDays(root: JSONObject): List<DayForecast> {
    val d = root.getJSONObject("daily")
    val time = d.getJSONArray("time")
    val code = d.getJSONArray("weather_code")
    val hi = d.getJSONArray("temperature_2m_max")
    val lo = d.getJSONArray("temperature_2m_min")
    val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return (0 until time.length()).map { i ->
      val label =
          if (i == 0) "Today"
          else runCatching { dayFmt.format(isoDate.parse(time.getString(i))!!) }.getOrDefault("")
      DayForecast(
          label = label,
          code = code.getInt(i),
          hi = hi.getDouble(i).roundToInt(),
          lo = lo.getDouble(i).roundToInt())
    }
  }

  private fun parseHours(root: JSONObject, nowMillis: Long): List<HourForecast> {
    val h = root.getJSONObject("hourly")
    val time = h.getJSONArray("time")
    val code = h.getJSONArray("weather_code")
    val temp = h.getJSONArray("temperature_2m")
    val isoHour = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    val hourFmt = SimpleDateFormat("h a", Locale.getDefault())
    // Show the current hour onward; everything before "now" is in the past.
    val cutoff = nowMillis - 60L * 60 * 1000
    val out = ArrayList<HourForecast>(12)
    var first = true
    for (i in 0 until time.length()) {
      val t = runCatching { isoHour.parse(time.getString(i))!!.time }.getOrNull() ?: continue
      if (t < cutoff) continue
      out.add(
          HourForecast(
              label = if (first) "Now" else hourFmt.format(time.getString(i).let { isoHour.parse(it)!! }),
              code = code.getInt(i),
              temp = temp.getDouble(i).roundToInt()))
      first = false
      if (out.size >= 12) break
    }
    return out
  }

  private fun httpGet(spec: String): String {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "Immortal/1.0")
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  fun emoji(code: Int): String =
      when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        in 45..48 -> "🌫️"
        in 51..67 -> "🌦️"
        in 71..77 -> "🌨️"
        in 80..82 -> "🌧️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
      }
}
