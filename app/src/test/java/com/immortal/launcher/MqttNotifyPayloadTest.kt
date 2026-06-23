/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [NotifyPayload.parse] across the inputs the publisher will see in practice — the
 * two production paths (HA notify entity message-only / raw `mqtt.publish` rich) and the
 * forgiving fallbacks (out-of-range numbers, unknown enums, malformed JSON).
 */
class MqttNotifyPayloadTest {

  @Test
  fun emptyString_returnsNull() {
    assertNull(NotifyPayload.parse(""))
    assertNull(NotifyPayload.parse("   "))
  }

  @Test
  fun emptyJson_returnsNull() {
    // {} has no visible text and no sound — caller no-ops, parser returns null.
    assertNull(NotifyPayload.parse("{}"))
  }

  @Test
  fun malformedJson_returnsNull() {
    assertNull(NotifyPayload.parse("not json"))
    assertNull(NotifyPayload.parse("{\"title\":"))
    assertNull(NotifyPayload.parse("[\"array\"]")) // JSONObject rejects non-object roots
  }

  @Test
  fun messageOnly_parsesWithDefaults() {
    val p = NotifyPayload.parse("""{"message":"Door unlocked"}""")!!
    assertEquals("", p.title)
    assertEquals("Door unlocked", p.message)
    assertNull(p.image)
    assertNull(p.sound)
    assertEquals(NotifyPayload.Position.BOTTOM, p.position)
    assertEquals(6, p.durationSec)
    assertEquals(1.0f, p.volume, 0.0001f)
    assertTrue(p.wakeScreen)
    assertNull(p.onTap)
    assertTrue(p.hasVisual)
    assertFalse(p.isEmpty)
  }

  @Test
  fun titleOnly_isVisual() {
    val p = NotifyPayload.parse("""{"title":"Alarm"}""")!!
    assertTrue(p.hasVisual)
    assertFalse(p.isEmpty)
  }

  @Test
  fun soundOnly_isNotEmpty_butNotVisual() {
    val p = NotifyPayload.parse("""{"sound":"http://nas/chime.mp3"}""")!!
    assertFalse(p.hasVisual)
    assertFalse(p.isEmpty)
    assertEquals("http://nas/chime.mp3", p.sound)
  }

  @Test
  fun emptyStringFields_treatedAsBlank() {
    // Track 1 templates can render empty strings for missing data dict entries — make sure
    // we don't treat ""-valued image/sound/on_tap as present.
    val p = NotifyPayload.parse("""{"message":"hi","image":"","sound":"","on_tap":""}""")!!
    assertNull(p.image)
    assertNull(p.sound)
    assertNull(p.onTap)
  }

  @Test
  fun fullPayload_parses() {
    val raw =
        """
        {
          "title": "Front door",
          "message": "Motion at 6:42pm",
          "image": "http://homeassistant.local:8123/local/snapshot.jpg",
          "sound": "http://nas.local/doorbell.mp3",
          "position": "top",
          "duration": 8,
          "volume": 0.6,
          "wake_screen": false,
          "on_tap": "lovelace/security"
        }
        """
            .trimIndent()
    val p = NotifyPayload.parse(raw)!!
    assertEquals("Front door", p.title)
    assertEquals("Motion at 6:42pm", p.message)
    assertEquals("http://homeassistant.local:8123/local/snapshot.jpg", p.image)
    assertEquals("http://nas.local/doorbell.mp3", p.sound)
    assertEquals(NotifyPayload.Position.TOP, p.position)
    assertEquals(8, p.durationSec)
    assertEquals(0.6f, p.volume, 0.0001f)
    assertFalse(p.wakeScreen)
    assertEquals("lovelace/security", p.onTap)
  }

  @Test
  fun position_mixedCase_falls_back_predictably() {
    assertEquals(NotifyPayload.Position.TOP, NotifyPayload.parse("""{"title":"x","position":"TOP"}""")!!.position)
    assertEquals(NotifyPayload.Position.TOP, NotifyPayload.parse("""{"title":"x","position":"Top"}""")!!.position)
    assertEquals(NotifyPayload.Position.BOTTOM, NotifyPayload.parse("""{"title":"x","position":"BOTTOM"}""")!!.position)
    // Unknown values default to BOTTOM rather than failing the whole payload.
    assertEquals(NotifyPayload.Position.BOTTOM, NotifyPayload.parse("""{"title":"x","position":"side"}""")!!.position)
  }

  @Test
  fun durationZero_meansNoAutoDismiss() {
    val p = NotifyPayload.parse("""{"title":"Smoke","duration":0}""")!!
    assertEquals(0, p.durationSec)
  }

  @Test
  fun durationNegative_clampedToZero() {
    val p = NotifyPayload.parse("""{"title":"x","duration":-5}""")!!
    assertEquals(0, p.durationSec)
  }

  @Test
  fun volumeOutOfRange_clamps() {
    val low = NotifyPayload.parse("""{"title":"x","volume":-0.5}""")!!
    assertEquals(0f, low.volume, 0.0001f)
    val high = NotifyPayload.parse("""{"title":"x","volume":2.5}""")!!
    assertEquals(1f, high.volume, 0.0001f)
  }

  @Test
  fun volumeNonNumeric_fallsBackToDefault() {
    // optDouble returns the default when the string can't be parsed.
    val p = NotifyPayload.parse("""{"title":"x","volume":"loud"}""")!!
    assertEquals(NotifyPayload.DEFAULT_VOLUME, p.volume, 0.0001f)
  }

  @Test
  fun unknownFields_areIgnored() {
    val p = NotifyPayload.parse("""{"message":"hi","priority":"high","color":"red"}""")!!
    assertEquals("hi", p.message)
    // Forward-compat: ignored, not rejected.
  }

  @Test
  fun inlineImageDataUri_passesThroughAsString() {
    val raw = """{"message":"x","image":"data:image/png;base64,iVBOR"}"""
    val p = NotifyPayload.parse(raw)!!
    assertEquals("data:image/png;base64,iVBOR", p.image)
  }
}
