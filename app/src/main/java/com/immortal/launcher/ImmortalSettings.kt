/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.util.Locale

/**
 * Immortal's own user preferences (as opposed to the screensaver's, which live in
 * [ScreensaverConfig]). Reached from the "Immortal" tile in the launcher's
 * Settings folder. Everything here defaults to the pre-1.25 behaviour so existing
 * installs are unaffected until the user changes something.
 */
object ImmortalSettings {

  private const val PREFS = "immortal_settings"

  // Weather temperature unit.
  const val UNIT_AUTO = "auto" // follow the device locale
  const val UNIT_F = "f"
  const val UNIT_C = "c"

  // Home-grid tile size.
  const val SIZE_STANDARD = "standard" // 6 columns, 88dp tiles (the original look)
  const val SIZE_LARGE = "large" // 5 columns, 110dp tiles (closer to the stock launcher)
  const val SIZE_XL = "xl" // 4 columns, 140dp tiles (for the big-screen Portal+)

  // Optional home-screen weather forecast widget, shown below the app grid.
  const val WIDGET_OFF = "off" // no forecast (default)
  const val WIDGET_HOURLY = "hourly" // hour-by-hour for the next several hours
  const val WIDGET_DAILY = "daily" // a high/low for each of the next 7 days

  data class Settings(
      val weatherUnit: String = UNIT_AUTO,
      val tileSize: String = SIZE_STANDARD,
      val weatherWidget: String = WIDGET_OFF,
  )

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    return Settings(
        weatherUnit = p.getString("weather_unit", UNIT_AUTO) ?: UNIT_AUTO,
        tileSize = p.getString("tile_size", SIZE_STANDARD) ?: SIZE_STANDARD,
        weatherWidget = p.getString("weather_widget", WIDGET_OFF) ?: WIDGET_OFF,
    )
  }

  fun setWeatherUnit(c: Context, unit: String) =
      prefs(c).edit().putString("weather_unit", unit).apply()

  fun setTileSize(c: Context, size: String) = prefs(c).edit().putString("tile_size", size).apply()

  fun setWeatherWidget(c: Context, mode: String) =
      prefs(c).edit().putString("weather_widget", mode).apply()

  /** Resolved unit for a fetch: true → Fahrenheit, false → Celsius. */
  fun useFahrenheit(context: Context): Boolean =
      when (load(context).weatherUnit) {
        UNIT_F -> true
        UNIT_C -> false
        else -> localeUsesFahrenheit()
      }

  /**
   * The handful of territories that use Fahrenheit day-to-day; everywhere else
   * gets Celsius. Pure + injectable for unit tests.
   */
  fun localeUsesFahrenheit(locale: Locale = Locale.getDefault()): Boolean =
      locale.country.uppercase(Locale.ROOT) in FAHRENHEIT_COUNTRIES

  private val FAHRENHEIT_COUNTRIES =
      setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH", "PR", "GU", "VI", "AS")
}
