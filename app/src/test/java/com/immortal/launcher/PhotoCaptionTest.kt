package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCaptionTest {

  @Test
  fun parsePlace_cityAndCountry() {
    assertEquals(
        "Arezzo, Italy",
        PhotoCaption.parsePlace("""{"city":"Arezzo","countryName":"Italy"}"""))
  }

  @Test
  fun parsePlace_prefersCityOverRegion() {
    assertEquals(
        "Paris, France",
        PhotoCaption.parsePlace(
            """{"city":"Paris","principalSubdivision":"Ile-de-France","countryName":"France"}"""))
  }

  @Test
  fun parsePlace_fallsBackLocalityThenRegionThenCountry() {
    assertEquals(
        "Townsville, Australia",
        PhotoCaption.parsePlace("""{"locality":"Townsville","countryName":"Australia"}"""))
    assertEquals(
        "Tuscany, Italy",
        PhotoCaption.parsePlace("""{"principalSubdivision":"Tuscany","countryName":"Italy"}"""))
    assertEquals("France", PhotoCaption.parsePlace("""{"countryName":"France"}"""))
  }

  @Test
  fun parsePlace_nullWhenEmpty() {
    assertNull(PhotoCaption.parsePlace("""{}"""))
    assertNull(PhotoCaption.parsePlace("""{"city":"","countryName":""}"""))
  }

  // --- caption lines ----------------------------------------------------------

  @Test
  fun formatPeople_readsAsASentence() {
    assertEquals("Alice", PhotoCaption.formatPeople(listOf("Alice")))
    assertEquals("Alice & Bob", PhotoCaption.formatPeople(listOf("Alice", " Bob ")))
    assertEquals("Alice, Bob & Carol", PhotoCaption.formatPeople(listOf("Alice", "Bob", "Carol")))
    assertEquals("Alice", PhotoCaption.formatPeople(listOf("Alice", "Alice")))
  }

  @Test
  fun formatPeople_summarisesACrowdInsteadOfOverflowingTheLine() {
    assertEquals(
        "Alice, Bob, Carol, Dave +2",
        PhotoCaption.formatPeople(listOf("Alice", "Bob", "Carol", "Dave", "Erin", "Frank")))
  }

  @Test
  fun formatPeople_nullWhenNobodyIsNamed() {
    assertNull(PhotoCaption.formatPeople(emptyList()))
    assertNull(PhotoCaption.formatPeople(listOf("", "   ")))
  }

  @Test
  fun formatTags_joinsAndCaps() {
    assertNull(PhotoCaption.formatTags(emptyList()))
    assertEquals("Beach  \u00b7  Sunset", PhotoCaption.formatTags(listOf("Beach", " Sunset ")))
    assertEquals(
        "a  \u00b7  b  \u00b7  c  \u00b7  d  \u00b7  e  \u00b7  +2",
        PhotoCaption.formatTags(listOf("a", "b", "c", "d", "e", "f", "g")))
  }

  @Test
  fun caption_isEmptyUntilSomeLineHasContent() {
    assertTrue(PhotoCaption.Caption().isEmpty)
    assertTrue(PhotoCaption.Caption(place = "   ", date = "").isEmpty)
    assertFalse(PhotoCaption.Caption(tags = "Beach").isEmpty)
    assertFalse(PhotoCaption.Caption(place = "Arezzo, Italy").isEmpty)
  }
}
