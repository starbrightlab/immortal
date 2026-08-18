/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmortalSettingsTest {

  @Test
  fun `fahrenheit territories default to F`() {
    assertTrue(ImmortalSettings.localeUsesFahrenheit(Locale.US))
    assertTrue(ImmortalSettings.localeUsesFahrenheit(Locale("en", "LR")))
    assertTrue(ImmortalSettings.localeUsesFahrenheit(Locale("my", "MM")))
    assertTrue(ImmortalSettings.localeUsesFahrenheit(Locale("en", "BS")))
  }

  @Test
  fun `everywhere else defaults to C`() {
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.UK))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.CANADA))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.GERMANY))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.JAPAN))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale("es", "MX")))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale("en", "AU")))
  }

  @Test
  fun `no-country locale defaults to C`() {
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale("en")))
    assertFalse(ImmortalSettings.localeUsesFahrenheit(Locale.ROOT))
  }

  @Test
  fun `clock auto follows the system 24-hour setting`() {
    assertTrue(ImmortalSettings.resolve24Hour(ImmortalSettings.CLOCK_AUTO, systemIs24Hour = true))
    assertFalse(ImmortalSettings.resolve24Hour(ImmortalSettings.CLOCK_AUTO, systemIs24Hour = false))
  }

  @Test
  fun `clock 12 and 24 override the system setting`() {
    assertFalse(ImmortalSettings.resolve24Hour(ImmortalSettings.CLOCK_12, systemIs24Hour = true))
    assertTrue(ImmortalSettings.resolve24Hour(ImmortalSettings.CLOCK_24, systemIs24Hour = false))
  }

  @Test
  fun `city name comes from the last segment of the zone id`() {
    assertEquals("New York", ImmortalSettings.cityFromZoneId("America/New_York"))
    assertEquals("Auckland", ImmortalSettings.cityFromZoneId("Pacific/Auckland"))
    assertEquals("Ho Chi Minh", ImmortalSettings.cityFromZoneId("Asia/Ho_Chi_Minh"))
    // Argentina and friends nest one deeper; the city is still the last segment.
    assertEquals("Buenos Aires", ImmortalSettings.cityFromZoneId("America/Argentina/Buenos_Aires"))
  }

  @Test
  fun `world clock labels round-trip, including a comma`() {
    // A comma is exactly why labels aren't stored in the zone list's CSV.
    val labels = mapOf("Pacific/Auckland" to "Mum, and Dad", "Europe/London" to "Home")
    val decoded = ImmortalSettings.parseWorldClockLabels(ImmortalSettings.encodeWorldClockLabels(labels))
    assertEquals(labels, decoded)
  }

  @Test
  fun `unset or broken label prefs mean no custom names, not a crash`() {
    assertTrue(ImmortalSettings.parseWorldClockLabels(null).isEmpty())
    assertTrue(ImmortalSettings.parseWorldClockLabels("").isEmpty())
    assertTrue(ImmortalSettings.parseWorldClockLabels("not json at all").isEmpty())
  }

  @Test
  fun `blank labels are dropped rather than shown as empty names`() {
    val decoded =
        ImmortalSettings.parseWorldClockLabels(
            """{"Pacific/Auckland":"  ","Europe/London":" Home "}""")
    assertEquals(mapOf("Europe/London" to "Home"), decoded)
  }
}
