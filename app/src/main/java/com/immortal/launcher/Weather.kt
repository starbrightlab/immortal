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

  /** Public accessor for the device's cached lat/lon (resolving + caching it once if
   * needed). Used by location-aware features like the ISS pass predictor. Null on
   * failure. */
  fun coordinates(context: Context): Pair<Double, Double>? = location(context)

  /** Cached lat/lon, or a fresh geolocation (cached on success). Also caches the city name. */
  private fun location(context: Context): Pair<Double, Double>? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // A manually chosen location always wins (issue #177): IP geolocation often lands on
    // the ISP's city, and because the first answer is cached forever, a wrong first
    // answer used to be permanent with no way to correct it.
    prefs.getString("manual_lat", null)?.toDoubleOrNull()?.let { la ->
      prefs.getString("manual_lon", null)?.toDoubleOrNull()?.let { lo -> return la to lo }
    }
    prefs.getString("lat", null)?.toDoubleOrNull()?.let { la ->
      prefs.getString("lon", null)?.toDoubleOrNull()?.let { lo -> return la to lo }
    }
    for (url in listOf("https://ipwho.is/", "https://ipapi.co/json/")) {
      val coords =
          runCatching {
                val j = JSONObject(httpGet(url))
                val la = j.optDouble("latitude", Double.NaN)
                val lo = j.optDouble("longitude", Double.NaN)
                val city = j.optString("city", "")
                if (!la.isNaN() && !lo.isNaN() && (la != 0.0 || lo != 0.0)) Triple(la, lo, city)
                else null
              }
              .getOrNull()
      if (coords != null) {
        prefs
            .edit()
            .putString("lat", coords.first.toString())
            .putString("lon", coords.second.toString())
            .putString("city", coords.third)
            .apply()
        return coords.first to coords.second
      }
    }
    return null
  }

  /** Today's sunrise/sunset as epoch millis (location-local, which on a fixed Portal
   * equals the device zone). [formatted] gives "HH:mm" / "h:mm a" per the clock setting. */
  data class SunTimes(val sunriseMillis: Long, val sunsetMillis: Long) {
    fun formatted(use24Hour: Boolean): Pair<String, String> {
      val fmt = SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
      return fmt.format(java.util.Date(sunriseMillis)) to fmt.format(java.util.Date(sunsetMillis))
    }
  }

  /** Today's sunrise + sunset from Open-Meteo (keyless). `timezone=auto` returns the
   * location's local times, parsed in the device's default zone. Null on failure. */
  fun fetchSunTimes(context: Context): SunTimes? =
      runCatching {
            val (lat, lon) = location(context) ?: return null
            val daily =
                JSONObject(
                        httpGet(
                            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                                "&daily=sunrise,sunset&timezone=auto&forecast_days=1"))
                    .getJSONObject("daily")
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
            val sr = iso.parse(daily.getJSONArray("sunrise").getString(0))!!.time
            val ss = iso.parse(daily.getJSONArray("sunset").getString(0))!!.time
            SunTimes(sr, ss)
          }
          .getOrNull()

  /** Last known city name (empty until geolocation has succeeded once). A manual
   * location's place name wins, matching [location]'s precedence. */
  fun cachedCity(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    manualPlace(context)?.let { return it.substringBefore(',') }
    return prefs.getString("city", "") ?: ""
  }

  // --- manual location override (issue #177) ----------------------------------

  /** A geocoding match from Open-Meteo's keyless search endpoint. [label] is e.g.
   * "Boston, Massachusetts, US". */
  data class Place(val name: String, val region: String, val lat: Double, val lon: Double) {
    val label: String
      get() = if (region.isBlank()) name else "$name, $region"
  }

  /** The manually chosen place label, or null when the location is auto-detected. */
  fun manualPlace(context: Context): String? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getString("manual_lat", null)?.toDoubleOrNull() == null) return null
    return prefs.getString("manual_city", null)?.ifBlank { null }
  }

  fun setManualLocation(context: Context, place: Place) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString("manual_lat", place.lat.toString())
        .putString("manual_lon", place.lon.toString())
        .putString("manual_city", place.label)
        .apply()
  }

  /** Back to automatic: drops the manual choice AND the cached IP result, so the next
   * weather refresh re-detects from scratch instead of reviving a stale first answer. */
  fun redetectAutomatically(context: Context) {
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove("manual_lat")
        .remove("manual_lon")
        .remove("manual_city")
        .remove("lat")
        .remove("lon")
        .remove("city")
        .apply()
  }

  /** Row label for the settings screen: the manual place, the detected city, or a hint. */
  fun locationLabel(context: Context): String {
    manualPlace(context)?.let { return it }
    val auto =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("city", "").orEmpty()
    return if (auto.isNotBlank()) "Auto: $auto" else "Automatic (from your internet connection)"
  }

  /** Search Open-Meteo's keyless geocoder for a city. Blocking; call off the main thread. */
  fun searchPlaces(query: String): List<Place> =
      runCatching {
            parseGeocoding(
                httpGet(
                    "https://geocoding-api.open-meteo.com/v1/search?name=" +
                        URLEncoder.encode(query, "UTF-8") +
                        "&count=8&language=en&format=json"))
          }
          .getOrDefault(emptyList())

  /** Pure parse of an Open-Meteo geocoding response (unit-tested). */
  internal fun parseGeocoding(json: String): List<Place> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    val out = ArrayList<Place>(results.length())
    for (i in 0 until results.length()) {
      val r = results.optJSONObject(i) ?: continue
      val name = r.optString("name", "")
      val la = r.optDouble("latitude", Double.NaN)
      val lo = r.optDouble("longitude", Double.NaN)
      if (name.isBlank() || la.isNaN() || lo.isNaN()) continue
      val region =
          listOf(r.optString("admin1", ""), r.optString("country_code", ""))
              .filter { it.isNotBlank() }
              .joinToString(", ")
      out.add(Place(name, region, la, lo))
    }
    return out
  }

  /** Current conditions for the redesigned weather widget: city, rounded temp, and weather code. */
  data class Current(val city: String, val temp: Int, val code: Int)

  fun fetchCurrent(context: Context): Current? =
      runCatching {
            val (lat, lon) = location(context) ?: return null
            val unit = if (ImmortalSettings.useFahrenheit(context)) "fahrenheit" else "celsius"
            val w =
                JSONObject(
                    httpGet(
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,weather_code&temperature_unit=$unit"))
            val cur = w.getJSONObject("current")
            Current(
                city = cachedCity(context),
                temp = cur.getDouble("temperature_2m").roundToInt(),
                code = cur.getInt("weather_code"),
            )
          }
          .getOrNull()

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
            parseForecast(json, System.currentTimeMillis(), ImmortalSettings.use24HourClock(context))
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
  internal fun parseForecast(json: String, nowMillis: Long, use24Hour: Boolean = false): Forecast {
    val root = JSONObject(json)
    return Forecast(days = parseDays(root), hours = parseHours(root, nowMillis, use24Hour))
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

  private fun parseHours(
      root: JSONObject,
      nowMillis: Long,
      use24Hour: Boolean = false
  ): List<HourForecast> {
    val h = root.getJSONObject("hourly")
    val time = h.getJSONArray("time")
    val code = h.getJSONArray("weather_code")
    val temp = h.getJSONArray("temperature_2m")
    val isoHour = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    // 24-hour: a bare hour ("13", "00"); 12-hour: "1 PM".
    val hourFmt = SimpleDateFormat(if (use24Hour) "HH" else "h a", Locale.getDefault())
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
