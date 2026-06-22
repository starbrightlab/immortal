package com.immortal.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray

/**
 * Pure machinery tests for the settings spec fold (no Context). Exercises [SettingsDomain.flatJson]
 * — the legacy wire payload every `Fleet*` façade now delegates to — across every spec type, so the
 * foundation is locked independently of any one domain. The Context-touching apply path is verified
 * end-to-end on-device (the suite has no Robolectric, matching the existing pure-test convention).
 */
class SettingSpecTest {

  private data class Snap(val on: Boolean, val n: Int, val choice: String, val text: String)

  private val domain =
      SettingsDomain<Snap>(
          id = "demo",
          title = "Demo",
          load = { Snap(true, 5, "b", "hi") }, // unused here; flatJson takes an explicit snapshot
          specs =
              listOf(
                  BoolSpec("on", "On", get = { it.on }, set = { _, _ -> }),
                  IntSpec("n", "N", get = { it.n }, set = { _, _ -> }, min = 0, max = 10, step = 5),
                  EnumSpec(
                      "choice",
                      "Choice",
                      get = { it.choice },
                      set = { _, _ -> },
                      options = listOf("a" to "A", "b" to "B")),
                  StringSpec("text", "Text", get = { it.text }, set = { _, _ -> }),
                  DerivedSpec("ready", get = { it.on && it.n > 0 }),
                  DerivedSpec("tags", get = { JSONArray(listOf("x", "y")) }),
              ))

  @Test
  fun flatJson_foldsEverySpecType() {
    val j = domain.flatJson(Snap(on = true, n = 5, choice = "b", text = "hi"))
    assertTrue(j.getBoolean("on"))
    assertEquals(5, j.getInt("n"))
    assertEquals("b", j.getString("choice"))
    assertEquals("hi", j.getString("text"))
    assertTrue(j.getBoolean("ready")) // derived: on && n>0
    assertEquals(2, j.getJSONArray("tags").length()) // derived JSONArray round-trips
  }

  @Test
  fun flatJson_reflectsSnapshot() {
    val j = domain.flatJson(Snap(on = false, n = 0, choice = "a", text = ""))
    assertFalse(j.getBoolean("on"))
    assertEquals(0, j.getInt("n"))
    assertEquals("a", j.getString("choice"))
    assertEquals("", j.getString("text"))
    assertFalse(j.getBoolean("ready")) // derived recomputed: off
  }
}
