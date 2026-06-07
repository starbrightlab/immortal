/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Open-Meteo forecast parsing + URL building (no device/network needed).
 *
 * Timezone and locale are pinned so the day/hour labels and the "drop past hours"
 * cutoff are deterministic: the response carries local times (`timezone=auto`), which
 * the parser reads in the default zone, and the labels are locale-formatted.
 */
class WeatherTest {

  private lateinit var savedTz: TimeZone
  private lateinit var savedLocale: Locale

  @Before
  fun pinZoneAndLocale() {
    savedTz = TimeZone.getDefault()
    savedLocale = Locale.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Locale.setDefault(Locale.US)
  }

  @After
  fun restore() {
    TimeZone.setDefault(savedTz)
    Locale.setDefault(savedLocale)
  }

  /** Epoch millis for a UTC wall-clock time like "2026-06-07T13:30". */
  private fun utc(stamp: String): Long =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
          .apply { timeZone = TimeZone.getTimeZone("UTC") }
          .parse(stamp)!!
          .time

  // 7 daily entries; 18 hourly entries spanning 10:00 today → 03:00 tomorrow.
  private val sample =
      """
      {
        "daily": {
          "time": ["2026-06-07","2026-06-08","2026-06-09","2026-06-10","2026-06-11","2026-06-12","2026-06-13"],
          "weather_code": [0,1,2,3,45,61,71],
          "temperature_2m_max": [70.4,71.6,72.0,73.0,74.0,75.0,76.0],
          "temperature_2m_min": [50.6,51.0,52.0,53.0,54.0,55.0,56.0]
        },
        "hourly": {
          "time": [
            "2026-06-07T10:00","2026-06-07T11:00","2026-06-07T12:00","2026-06-07T13:00",
            "2026-06-07T14:00","2026-06-07T15:00","2026-06-07T16:00","2026-06-07T17:00",
            "2026-06-07T18:00","2026-06-07T19:00","2026-06-07T20:00","2026-06-07T21:00",
            "2026-06-07T22:00","2026-06-07T23:00","2026-06-08T00:00","2026-06-08T01:00",
            "2026-06-08T02:00","2026-06-08T03:00"
          ],
          "weather_code": [0,0,0,61,0,0,0,0,0,0,0,0,0,0,3,0,0,0],
          "temperature_2m": [50.0,51.0,52.0,60.4,61.6,63.0,64.0,65.0,66.0,67.0,68.0,69.0,70.0,71.0,72.0,73.0,74.0,75.0]
        }
      }
      """.trimIndent()

  @Test
  fun `daily labels today then weekday, temps rounded`() {
    val days = Weather.parseForecast(sample, utc("2026-06-07T13:30")).days
    assertEquals(7, days.size)
    assertEquals("Today", days[0].label)
    assertEquals(0, days[0].code)
    assertEquals(70, days[0].hi) // 70.4 -> 70
    assertEquals(51, days[0].lo) // 50.6 -> 51

    // Index > 0 is the weekday of that date (not "Today", never blank).
    val expectedWeekday =
        SimpleDateFormat("EEE", Locale.US)
            .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-06-08")!!)
    assertEquals(expectedWeekday, days[1].label)
    assertNotEquals("Today", days[1].label)
  }

  @Test
  fun `hourly drops past hours, labels Now then clock, caps at 12`() {
    // now = 13:30 -> cutoff 12:30, so 10/11/12:00 are dropped; 13:00 is the first kept.
    val hours = Weather.parseForecast(sample, utc("2026-06-07T13:30")).hours
    assertEquals(12, hours.size) // 15 candidates from 13:00, capped to 12

    assertEquals("Now", hours[0].label)
    assertEquals(60, hours[0].temp) // 13:00, 60.4 -> 60
    assertEquals(61, hours[0].code) // weather_code flows through

    assertEquals("2 PM", hours[1].label) // 14:00
    assertEquals(62, hours[1].temp) // 61.6 -> 62

    // 12th kept entry is 00:00 the next day.
    assertEquals("12 AM", hours.last().label)
    assertEquals(3, hours.last().code)
  }

  @Test
  fun `hourly is empty when every hour is in the past`() {
    val hours = Weather.parseForecast(sample, utc("2030-01-01T00:00")).hours
    assertTrue(hours.isEmpty())
  }

  @Test
  fun `forecast url carries coordinates, span and the chosen unit`() {
    val f = Weather.forecastUrl(1.5, -2.25, fahrenheit = true)
    assertTrue(f.contains("latitude=1.5"))
    assertTrue(f.contains("longitude=-2.25"))
    assertTrue(f.contains("forecast_days=7"))
    assertTrue(f.contains("timezone=auto"))
    assertTrue(f.contains("temperature_unit=fahrenheit"))

    val c = Weather.forecastUrl(1.5, -2.25, fahrenheit = false)
    assertTrue(c.contains("temperature_unit=celsius"))
  }
}
