/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichSourceTest {

  @Test
  fun normalizeBase_stripsTrailingSlashAndApiSuffix() {
    assertEquals("http://immich:2283", ImmichSource.normalizeBase("http://immich:2283/"))
    assertEquals("http://immich:2283", ImmichSource.normalizeBase(" http://immich:2283/api "))
    assertEquals("https://photos.example", ImmichSource.normalizeBase("https://photos.example/API/"))
  }

  @Test
  fun previewUrl_pointsAtScreenSizedThumbnail() {
    assertEquals(
        "http://immich:2283/api/assets/abc/thumbnail?size=preview",
        ImmichSource.previewUrl("http://immich:2283/", "abc"))
  }

  @Test
  fun playbackUrl_pointsAtVideoPlaybackEndpoint() {
    assertEquals(
        "http://immich:2283/api/assets/abc/video/playback",
        ImmichSource.playbackUrl("http://immich:2283/", "abc"))
  }

  @Test
  fun authHeaders_carryTrimmedApiKey() {
    assertEquals(mapOf("x-api-key" to "secret"), ImmichSource.authHeaders(" secret "))
  }

  @Test
  fun assetIdFrom_roundTripsTheUrlsWeMint() {
    // The caption's whole link back to the server. A pooled/cached photo is replayed from disk
    // with no listing in hand, so the id has to come back out of the URL itself.
    val base = "http://immich:2283"
    assertEquals("abc-123", ImmichSource.assetIdFrom(ImmichSource.previewUrl(base, "abc-123")))
    assertEquals("abc-123", ImmichSource.assetIdFrom(ImmichSource.playbackUrl(base, "abc-123")))
    // A real Immich id is a UUID; a sub-path base must not confuse the match.
    val uuid = "8f2a1c4e-9b3d-4a7f-8e6c-1d2b3a4c5d6e"
    assertEquals(uuid, ImmichSource.assetIdFrom(ImmichSource.previewUrl("https://photos.example/", uuid)))
    assertEquals(uuid, ImmichSource.assetIdFrom("https://host/photos/api/assets/$uuid/thumbnail?size=preview"))
    // The detail endpoint itself (no trailing segment) still yields the id.
    assertEquals("abc", ImmichSource.assetIdFrom("$base/api/assets/abc"))
  }

  @Test
  fun assetIdFrom_nullForAnythingThatIsNotAnAssetUrl() {
    assertNull(ImmichSource.assetIdFrom("https://photos.example/share/xyz/image.jpg"))
    assertNull(ImmichSource.assetIdFrom("http://immich:2283/api/albums"))
    assertNull(ImmichSource.assetIdFrom("http://immich:2283/api/assets/"))
    assertNull(ImmichSource.assetIdFrom(""))
  }

  // --- asset detail (the screensaver caption) ---------------------------------

  @Test
  fun parseDetails_readsDateDescriptionPlacePeopleAndTags() {
    val d =
        ImmichSource.parseDetails(
            """
            {
              "id": "abc",
              "type": "IMAGE",
              "localDateTime": "2026-06-22T18:11:12.000Z",
              "fileCreatedAt": "2026-06-22T16:11:12.000Z",
              "exifInfo": {
                "dateTimeOriginal": "2026-06-22T18:11:12.000+02:00",
                "description": "  Summer trip to Tuscany  ",
                "city": "Arezzo",
                "state": "Tuscany",
                "country": "Italy"
              },
              "people": [{"name": "Alice"}, {"name": ""}, {"name": "Bob"}, {"name": "Alice"}],
              "tags": [
                {"name": "Beach", "value": "Holiday/Beach"},
                {"value": "Holiday/Sunset"}
              ]
            }
            """)
    assertEquals("Summer trip to Tuscany", d.description)
    assertEquals("Arezzo, Italy", d.place)
    // Unnamed faces are dropped and duplicates collapse; tags fall back to the path's leaf.
    assertEquals(listOf("Alice", "Bob"), d.people)
    assertEquals(listOf("Beach", "Sunset"), d.tags)
    // `localDateTime` wins, and its digits are the wall clock — never shifted by the device zone.
    assertEquals(localMillis("2026-06-22 18:11:12"), d.dateMillis)
    assertFalse(d.isEmpty)
  }

  @Test
  fun parseDetails_fallsBackFromLocalDateTimeToExifThenTheUtcFileTimestamp() {
    assertEquals(
        localMillis("2026-06-22 18:11:12"),
        ImmichSource.parseDetails("""{"exifInfo":{"dateTimeOriginal":"2026-06-22T18:11:12.000+02:00"}}""")
            .dateMillis)
    // fileCreatedAt is a true UTC instant, so it is read as one.
    assertEquals(
        utcMillis("2026-01-02 03:04:05"),
        ImmichSource.parseDetails("""{"fileCreatedAt":"2026-01-02T03:04:05.000Z"}""").dateMillis)
  }

  @Test
  fun parseDetails_placeFallsBackToStateThenCountry() {
    assertEquals(
        "Tuscany, Italy",
        ImmichSource.parseDetails("""{"exifInfo":{"state":"Tuscany","country":"Italy"}}""").place)
    assertEquals("France", ImmichSource.parseDetails("""{"exifInfo":{"country":"France"}}""").place)
    assertNull(ImmichSource.parseDetails("""{"exifInfo":{}}""").place)
  }

  @Test
  fun parseDetails_treatsJsonNullsAndBlanksAsNothingToShow() {
    // What Immich actually sends for a photo with no GPS, no description and nobody tagged.
    val d =
        ImmichSource.parseDetails(
            """
            {
              "localDateTime": null,
              "exifInfo": {"description": "", "city": null, "state": null, "country": null},
              "people": [],
              "tags": []
            }
            """)
    assertNull(d.description)
    assertNull(d.place)
    assertNull(d.dateMillis)
    assertTrue(d.people.isEmpty())
    assertTrue(d.tags.isEmpty())
    assertTrue(d.isEmpty)
  }

  @Test
  fun parseDetails_survivesAnAssetWithNothingButAnId() {
    assertTrue(ImmichSource.parseDetails("""{"id":"abc"}""").isEmpty)
  }

  /** The same wall-clock instant the parser should produce, whatever zone the test host is in. */
  private fun localMillis(s: String): Long =
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)!!.time

  private fun utcMillis(s: String): Long =
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
          .apply { timeZone = TimeZone.getTimeZone("UTC") }
          .parse(s)!!
          .time
}
