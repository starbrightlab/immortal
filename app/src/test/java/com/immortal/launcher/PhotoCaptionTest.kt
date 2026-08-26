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
  fun caption_dropsBlanksAndIsEmptyUntilSomeLineHasContent() {
    assertTrue(PhotoCaption.Caption.EMPTY.isEmpty)
    assertTrue(
        PhotoCaption.Caption.of(
                PhotoCaption.Line.LOCATION to "   ",
                PhotoCaption.Line.DATE to "",
                PhotoCaption.Line.TAGS to null,
            )
            .isEmpty)
    val c =
        PhotoCaption.Caption.of(
            PhotoCaption.Line.LOCATION to "  Arezzo, Italy  ",
            PhotoCaption.Line.DATE to null,
        )
    assertFalse(c.isEmpty)
    assertEquals("Arezzo, Italy", c[PhotoCaption.Line.LOCATION]) // trimmed
    assertNull(c[PhotoCaption.Line.DATE]) // absent, not blank
  }

  // --- caption layout (order + per-line style) --------------------------------

  @Test
  fun parseOrder_defaultsAndRoundTrips() {
    assertEquals(PhotoCaption.DEFAULT_ORDER, PhotoCaption.parseOrder(null))
    assertEquals(PhotoCaption.DEFAULT_ORDER, PhotoCaption.parseOrder(""))
    val custom = listOf(PhotoCaption.Line.TAGS, PhotoCaption.Line.PEOPLE, PhotoCaption.Line.DATE,
        PhotoCaption.Line.LOCATION, PhotoCaption.Line.DESCRIPTION)
    assertEquals(custom, PhotoCaption.parseOrder(PhotoCaption.serializeOrder(custom)))
  }

  @Test
  fun parseOrder_repairsAPartialOrGarbageString() {
    // A line the string forgot is appended in default order rather than vanishing from the frame.
    assertEquals(
        listOf(PhotoCaption.Line.TAGS, PhotoCaption.Line.LOCATION, PhotoCaption.Line.DATE,
            PhotoCaption.Line.DESCRIPTION, PhotoCaption.Line.PEOPLE),
        PhotoCaption.parseOrder("tags,location"))
    // Unknown keys and duplicates are dropped, not honoured twice.
    assertEquals(
        listOf(PhotoCaption.Line.DATE, PhotoCaption.Line.LOCATION, PhotoCaption.Line.DESCRIPTION,
            PhotoCaption.Line.PEOPLE, PhotoCaption.Line.TAGS),
        PhotoCaption.parseOrder("date,nonsense,date"))
    assertEquals(PhotoCaption.DEFAULT_ORDER, PhotoCaption.parseOrder("!!!"))
  }

  @Test
  fun parseStyles_defaultsMatchTheOriginalCaption() {
    val d = PhotoCaption.parseStyles(null)
    // 95% and 70% of the 20sp base are the 19sp/14sp the hardcoded caption used.
    assertEquals(95, d.getValue(PhotoCaption.Line.LOCATION).size)
    assertEquals(PhotoCaption.Weight.BOLD, d.getValue(PhotoCaption.Line.LOCATION).weight)
    assertEquals(70, d.getValue(PhotoCaption.Line.DATE).size)
    assertEquals(PhotoCaption.Weight.LIGHT, d.getValue(PhotoCaption.Line.DATE).weight)
    assertEquals(PhotoCaption.Line.entries.size, d.size)
  }

  @Test
  fun parseStyles_roundTripsAndFallsBackPerField() {
    val custom =
        PhotoCaption.parseStyles(null) +
            (PhotoCaption.Line.TAGS to PhotoCaption.LineStyle(120, PhotoCaption.Weight.REGULAR))
    assertEquals(custom, PhotoCaption.parseStyles(PhotoCaption.serializeStyles(custom)))
    // A malformed entry falls back to that line's default without taking the others with it.
    val patched = PhotoCaption.parseStyles("tags:oops:nope,people:130:bold")
    assertEquals(PhotoCaption.Line.TAGS.defaultStyle, patched.getValue(PhotoCaption.Line.TAGS))
    assertEquals(
        PhotoCaption.LineStyle(130, PhotoCaption.Weight.BOLD),
        patched.getValue(PhotoCaption.Line.PEOPLE))
  }

  @Test
  fun clampSize_keepsALineInsideTheStepperRange() {
    assertEquals(PhotoCaption.LineStyle.MIN_SIZE, PhotoCaption.clampSize(0))
    assertEquals(PhotoCaption.LineStyle.MAX_SIZE, PhotoCaption.clampSize(9999))
    assertEquals(100, PhotoCaption.clampSize(100))
    // Out-of-range values in a persisted string are clamped, never taken literally.
    assertEquals(
        PhotoCaption.LineStyle.MAX_SIZE,
        PhotoCaption.parseStyles("tags:9999:bold").getValue(PhotoCaption.Line.TAGS).size)
  }
}
