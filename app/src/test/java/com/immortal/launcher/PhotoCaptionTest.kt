package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
