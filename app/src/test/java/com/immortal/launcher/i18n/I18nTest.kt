/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {

  @Test
  fun isSpanish_respectsExplicitUserPreference() {
    assertTrue(I18n.isSpanish("es"))
    assertFalse(I18n.isSpanish("en"))
  }

  @Test
  fun soundscapeLabel_translatesCorrectly() {
    assertEquals("Olas del mar", I18n.soundscapeLabel("ocean", "es"))
    assertEquals("Ocean waves", I18n.soundscapeLabel("ocean", "en"))
    assertEquals("Lluvia", I18n.soundscapeLabel("rain", "es"))
    assertEquals("Rain", I18n.soundscapeLabel("rain", "en"))
  }

  @Test
  fun feedLabel_translatesCorrectly() {
    assertEquals("El Met — Arte", I18n.feedLabel("met", "es"))
    assertEquals("The Met — art", I18n.feedLabel("met", "en"))
  }

  @Test
  fun rangeLabel_translatesCorrectly() {
    assertEquals("Hoy", I18n.rangeLabel("day", "es"))
    assertEquals("Day", I18n.rangeLabel("day", "en"))
  }

  @Test
  fun sourceLabel_translatesCorrectly() {
    assertEquals(
        "Álbum compartido",
        I18n.sourceLabel(
            usesImmich = false,
            usesSmb = false,
            usesDav = false,
            usesWebUrl = false,
            usesUrl = true,
            usesFolder = false,
            userLang = "es"))
    assertEquals(
        "Shared album",
        I18n.sourceLabel(
            usesImmich = false,
            usesSmb = false,
            usesDav = false,
            usesWebUrl = false,
            usesUrl = true,
            usesFolder = false,
            userLang = "en"))
  }

  @Test
  fun noTranslationStartsWithListMarker() {
    val file =
        listOf(
                java.io.File("src/main/java/com/immortal/launcher/i18n/I18n.kt"),
                java.io.File("app/src/main/java/com/immortal/launcher/i18n/I18n.kt"))
            .firstOrNull { it.exists() }

    if (file != null) {
      val regex = Regex("""->\s*"([0-9]+[.)]\s+.*)"""")
      val matches = file.readLines().mapNotNull { line -> regex.find(line)?.groupValues?.get(1) }
      assertTrue("Found translations starting with list markers: $matches", matches.isEmpty())
    }

    assertFalse(
        I18n.translate(
                "In Home Assistant, add the Mosquitto broker add-on (Settings → Add-ons) and the MQTT integration. New to MQTT? See home-assistant.io/integrations/mqtt",
                "es")
            .matches(Regex("""^\d+[.)]\s.*""")))
    assertFalse(
        I18n.translate(
                "Install and set up Music Assistant as a server on your home network. New to Music Assistant? Learn more at music-assistant.io",
                "es")
            .matches(Regex("""^\d+[.)]\s.*""")))
  }
}
