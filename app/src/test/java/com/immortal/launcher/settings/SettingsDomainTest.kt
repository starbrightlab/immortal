package com.immortal.launcher.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.mockito.Mockito.mock

/**
 * Unit tests for the registry's WRITE path — `apply` (partial writes, alias resolution, coerce/skip,
 * applyWhen guards), the per-batch `onApplied`, and `schemaJson` (sections / defaults / visibleWhen
 * gating). The `Context` is only ever passed through to the setter lambdas, so a Mockito mock that's
 * never dereferenced is enough — no Robolectric needed. Storage itself (the real `*Config` setters)
 * is exercised on-device; here we lock the machinery with recording setters.
 */
class SettingsDomainTest {

  private data class Snap(
      val flag: Boolean = false,
      val n: Int = 0,
      val choice: String = "a",
      val text: String = "",
  )

  private val ctx: Context = mock(Context::class.java)
  private val writes = mutableListOf<Pair<String, Any?>>()
  private val appliedBatches = mutableListOf<Set<String>>()

  private fun domain(specs: List<SettingSpec<Snap>>) =
      SettingsDomain(
          id = "t",
          title = "T",
          load = { Snap() },
          specs = specs,
          onApplied = { _, keys -> appliedBatches.add(keys) },
      )

  @Test
  fun apply_writesPresentKeys_firesOnAppliedOnce() {
    val d =
        domain(
            listOf(
                BoolSpec("flag", "F", get = { it.flag }, set = { _, v -> writes.add("flag" to v) }),
                IntSpec("n", "N", get = { it.n }, set = { _, v -> writes.add("n" to v) }, min = 0, max = 10),
                StringSpec("text", "T", get = { it.text }, set = { _, v -> writes.add("text" to v) }),
            ))
    val applied = d.apply(ctx, JSONObject().put("flag", true).put("n", 5))
    assertEquals(setOf("flag", "n"), applied) // "text" absent → not applied
    assertEquals(listOf<Pair<String, Any?>>("flag" to true, "n" to 5), writes)
    assertEquals(1, appliedBatches.size) // onApplied fired exactly once for the batch
    assertEquals(setOf("flag", "n"), appliedBatches[0])
  }

  @Test
  fun apply_emptyBodyDoesNotFireOnApplied() {
    domain(listOf(BoolSpec("flag", "F", get = { it.flag }, set = { _, _ -> }))).apply(ctx, JSONObject())
    assertTrue(appliedBatches.isEmpty())
  }

  @Test
  fun apply_resolvesAliasToCanonicalKey() {
    val d =
        domain(
            listOf(
                BoolSpec(
                    "widgetOn",
                    "W",
                    get = { it.flag },
                    set = { _, v -> writes.add("widgetOn" to v) },
                    aliases = listOf("enabled"))))
    val applied = d.apply(ctx, JSONObject().put("enabled", false)) // legacy alias
    assertEquals(setOf("widgetOn"), applied) // reported under the canonical key, not the alias
    assertEquals(listOf<Pair<String, Any?>>("widgetOn" to false), writes)
  }

  @Test
  fun enum_skipsValueWhenCoerceReturnsNull() {
    val d =
        domain(
            listOf(
                EnumSpec(
                    "choice",
                    "C",
                    get = { it.choice },
                    set = { _, v -> writes.add("choice" to v) },
                    options = listOf("a" to "A", "b" to "B"),
                    coerce = { if (it == "a" || it == "b") it else null })))
    assertTrue(d.apply(ctx, JSONObject().put("choice", "z")).isEmpty()) // unknown → skipped
    assertTrue(writes.isEmpty())
    assertEquals(setOf("choice"), d.apply(ctx, JSONObject().put("choice", "b"))) // valid → applied
  }

  @Test
  fun string_applyWhenGuardSkipsBlank() {
    val d =
        domain(
            listOf(
                StringSpec(
                    "text",
                    "T",
                    get = { it.text },
                    set = { _, v -> writes.add("text" to v) },
                    applyWhen = { it.isNotBlank() })))
    assertTrue(d.apply(ctx, JSONObject().put("text", "")).isEmpty()) // blank guarded out
    assertEquals(setOf("text"), d.apply(ctx, JSONObject().put("text", "hi")))
  }

  @Test
  fun secretString_redactsInSchema_andBlankSubmitKeepsValue() {
    var stored = "hunter2"
    val spec = StringSpec<Snap>("pw", "Password", get = { stored }, set = { _, v -> stored = v }, secret = true)
    val meta = spec.metaJson(ctx, Snap())
    assertEquals("", meta.getString("value")) // cleartext never leaves the device
    assertTrue(meta.getBoolean("secret"))
    assertTrue(meta.getBoolean("hasValue")) // but the client knows one is set
    assertFalse(spec.applyFrom(ctx, JSONObject().put("pw", ""))) // blank = leave unchanged
    assertEquals("hunter2", stored)
    assertTrue(spec.applyFrom(ctx, JSONObject().put("pw", "newpass"))) // a real value writes
    assertEquals("newpass", stored)
  }

  @Test
  fun schemaJson_tagsSections_injectsDefaults_gatesHidden() {
    val d =
        SettingsDomain(
            id = "t",
            title = "T",
            load = { Snap(flag = true, n = 3) },
            specs =
                listOf(
                    BoolSpec("flag", "F", get = { it.flag }, set = { _, _ -> }),
                    IntSpec("n", "N", get = { it.n }, set = { _, _ -> }, min = 0, max = 10, visible = { _, s -> s.flag }),
                    IntSpec("hid", "H", get = { 1 }, set = { _, _ -> }, min = 0, max = 10, visible = { _, s -> !s.flag }),
                ),
            sections = mapOf("n" to "Group"),
            defaults = { Snap(flag = false, n = 7) },
        )
    val controls = d.schemaJson(ctx).getJSONArray("controls")
    val byKey = (0 until controls.length()).associate { controls.getJSONObject(it).getString("key") to controls.getJSONObject(it) }
    assertEquals(2, controls.length()) // "hid" gated out (flag=true → !flag=false)
    assertFalse(byKey.getValue("flag").has("section")) // ungrouped
    assertEquals("Group", byKey.getValue("n").getString("section"))
    assertEquals(false, byKey.getValue("flag").getBoolean("default")) // from the defaults snapshot
    assertEquals(7, byKey.getValue("n").getInt("default"))
    assertEquals(3, byKey.getValue("n").getInt("value")) // current value, not default
  }

  @Test
  fun screensaverRegistry_coversEveryPersistedField_orExplicitlyAccountsForIt() {
    // Instance fields only — drops Compose's synthetic static `$stable` and any Companion.
    val fields =
        com.immortal.launcher.ScreensaverConfig.Settings::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
    val specKeys = (SettingsDomains.screensaver.specs + SettingsDomains.calendar.specs).map { it.key }.toSet()
    // Fields not bound by a direct value spec, and why: photo-source credentials + the clock-face
    // and dismiss-target pickers are managed by their own Activities (reached via a NavSpec); the
    // calendar fields are owned by the calendar domain under different wire keys (widgetOn/url/
    // range/size/side). New persisted settings must get a spec or be added here — a deliberate gate.
    val managedElsewhere =
        setOf(
            "immichUrl", "immichKey", "immichAlbumId", "immichAlbumName",
            "smbHost", "smbShare", "smbPath", "smbUser", "smbPass",
            "davUrl", "davUser", "davPass", "webUrl",
            "facesEnabled", "faceId", "faceSizeIndex",
            "dismissAppComponent", "dismissHaDashboard",
            "calendarUrl", "calendarEnabled", "calendarRange", "calendarSize", "calendarSide",
        )
    val uncovered = fields - specKeys - managedElsewhere
    assertTrue(
        "ScreensaverConfig.Settings has persisted fields neither in the registry nor accounted for: $uncovered",
        uncovered.isEmpty())
  }

  @Test
  fun allDomains_sectionKeysMatchRealSpecs() {
    SettingsRegistry.domains.forEach { dom ->
      val specKeys = dom.specs.map { it.key }.toSet()
      val orphaned = dom.sections.keys - specKeys
      assertTrue("domain '${dom.id}' has section keys with no matching spec: $orphaned", orphaned.isEmpty())
    }
  }
}
