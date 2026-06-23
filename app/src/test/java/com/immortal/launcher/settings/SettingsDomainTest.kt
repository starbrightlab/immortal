package com.immortal.launcher.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
  fun int_rejectsNonNumericAndOutOfRange_soAppliedSetIsTruthful() {
    val d =
        domain(
            listOf(IntSpec("n", "N", get = { it.n }, set = { _, v -> writes.add("n" to v) }, min = 0, max = 10)))
    // Wrong type and out-of-range are REJECTED (not coerced to 0 / silently clamped + reported applied).
    assertTrue("non-numeric rejected", d.apply(ctx, JSONObject().put("n", "abc")).isEmpty())
    assertTrue("above max rejected", d.apply(ctx, JSONObject().put("n", 99)).isEmpty())
    assertTrue("below min rejected", d.apply(ctx, JSONObject().put("n", -1)).isEmpty())
    assertTrue("no write happened for any rejected value", writes.isEmpty())
    // In-range applies; a numeric string coerces.
    assertEquals(setOf("n"), d.apply(ctx, JSONObject().put("n", 7)))
    assertEquals(setOf("n"), d.apply(ctx, JSONObject().put("n", "3")))
    assertEquals(listOf<Pair<String, Any?>>("n" to 7, "n" to 3), writes)
  }

  @Test
  fun int_wrapField_acceptsOutOfRange_forTheStepperWrap() {
    val seen = mutableListOf<Int>()
    val d =
        domain(
            listOf(
                IntSpec(
                    "t", "T", get = { it.n }, set = { _, v -> seen.add(v) }, min = 0, max = 1439, wrap = true)))
    // A time-of-day (wrap) field legitimately receives the stepper's -step at 0; range check is skipped
    // and the setter wraps it. (Non-wrap fields would reject this — see the test above.)
    assertEquals(setOf("t"), d.apply(ctx, JSONObject().put("t", -15)))
    assertEquals(listOf(-15), seen)
  }

  @Test
  fun bool_rejectsNonBoolean() {
    val d = domain(listOf(BoolSpec("flag", "F", get = { it.flag }, set = { _, v -> writes.add("flag" to v) })))
    assertTrue(d.apply(ctx, JSONObject().put("flag", "maybe")).isEmpty()) // garbage string → skipped
    assertTrue(d.apply(ctx, JSONObject().put("flag", 3)).isEmpty()) // number → skipped
    assertTrue(writes.isEmpty())
    assertEquals(setOf("flag"), d.apply(ctx, JSONObject().put("flag", true)))
    assertEquals(setOf("flag"), d.apply(ctx, JSONObject().put("flag", "false"))) // "true"/"false" accepted
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
  fun immortalRegistry_coversEveryPersistedField_orExplicitlyAccountsForIt() {
    // The on-device Immortal screen now renders its top-level controls from this domain, so a new
    // ImmortalSettings.Settings field that nobody adds a spec for would silently never appear. Every
    // field is currently bound by a value spec (the multi-room ones gate on the master toggle), so
    // managedElsewhere is empty — a new field must get a spec or be listed here, a deliberate gate.
    val fields =
        com.immortal.launcher.ImmortalSettings.Settings::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
    val specKeys = SettingsDomains.immortal.specs.map { it.key }.toSet()
    val managedElsewhere = emptySet<String>()
    val uncovered = fields - specKeys - managedElsewhere
    assertTrue(
        "ImmortalSettings.Settings has persisted fields neither in the registry nor accounted for: $uncovered",
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

  @Test
  fun onDeviceRenderedDomains_haveNoUnrenderableInlineStrings() {
    // SettingsRenderer.SettingControl renders NOTHING for an Entry.Inline StringSpec (there's no
    // inline free-text entry on a D-pad screen — those use a dedicated Entry.Nav Activity). So an
    // inline string in a generically-rendered domain would silently show as a blank row only on the
    // (manual-only) device. Mirror the exclude sets the two on-device screens pass to SettingsList
    // and assert every spec they DO render is renderable. If these exclude sets change, update here.
    val rendered =
        listOf(
            SettingsDomains.screensaver to setOf("enabled"),
            SettingsDomains.calendar to emptySet(),
            SettingsDomains.immortal to setOf("multiRoomEnabled", "snapcastHost", "maUsername", "maPassword"),
        )
    rendered.forEach { (dom, exclude) ->
      val blank =
          dom.specs
              .filter { it.key !in exclude }
              .filterIsInstance<StringSpec<*>>()
              .filter { it.entry is Entry.Inline }
              .map { it.key }
      assertTrue(
          "domain '${dom.id}' renders Entry.Inline StringSpec(s) on-device that show nothing: $blank",
          blank.isEmpty())
    }
  }

  @Test
  fun contextSnapshotDomains_specKeysArePinned() {
    // mqtt & quickbar use Context as the snapshot — they have no aggregate `Settings` data class, so
    // the reflection completeness tripwires above can't enumerate their fields. Pin the spec-key set
    // instead: adding a new MqttConfig/QuickBarConfig setting now forces a conscious update here (and
    // a spec), rather than the field silently shipping with no remote/registry exposure.
    assertEquals(
        setOf("enabled", "host", "port", "username", "password", "useTls", "validateCert"),
        SettingsDomains.mqtt.specs.map { it.key }.toSet())
    assertEquals(setOf("enabled", "alwaysShow"), SettingsDomains.quickbar.specs.map { it.key }.toSet())
  }

  @Test
  fun immortalEnums_rejectValuesOutsideTheirOptions() {
    // Guards the strict coercers: the Immortal display enums write the raw string straight to prefs
    // (no normalising setter), so without a coercer a remote push of an unrecognised value would
    // persist garbage into a constrained field. Each must accept its options and skip anything else.
    listOf("weatherUnit", "tileSize", "weatherWidget", "clockFormat").forEach { key ->
      val spec = SettingsDomains.immortal.specs.first { it.key == key } as EnumSpec<*>
      spec.options.forEach { (value, _) ->
        assertEquals("enum '$key' must accept its own option '$value'", value, spec.coerce(value))
      }
      assertNull("enum '$key' must skip an unrecognised value", spec.coerce("not-a-real-value"))
    }
  }
}
