/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Locks the device-name validation contract and the spec-key set on the real registered `fleet`
 * domain (the one the phone-remote rename uses). The `applyWhen` guard is a pure predicate, so it's
 * exercised directly; the actual storage round-trip (FleetConfig get/set → SharedPreferences) is
 * on-device.
 */
class FleetSettingsTest {

  private val nameSpec: StringSpec<Context> =
      SettingsDomains.fleet.specs.first { it.key == "name" } as StringSpec<Context>

  @Test
  fun fleetDomain_isRegistered_withNameSpec() {
    assertTrue(SettingsDomains.all.any { it.id == "fleet" })
    assertEquals("name", nameSpec.key)
  }

  @Test
  fun fleetRegistry_specKeysArePinned() {
    // fleet uses Context as the snapshot — no aggregate `Settings` data class for the reflection
    // tripwire to enumerate (same shape as the mqtt pin in SettingsDomainTest). A new FleetConfig
    // setting has to land here and in a spec, rather than silently shipping with no on-device
    // control and no remote exposure.
    assertEquals(
        setOf("name", "notifyEnabled"), SettingsDomains.fleet.specs.map { it.key }.toSet())
  }

  @Test
  fun notifyEnabled_defaultsOff_andHidesUntilTheAgentIsOn() {
    val spec = SettingsDomains.fleet.specs.first { it.key == "notifyEnabled" }
    // Unstubbed getBoolean returns false, i.e. the shipped default for both flags: the agent is
    // off, so the row is hidden — and the toggle itself reads off.
    assertFalse(spec.visibleWhen(ctxWith(agentEnabled = false), ctxWith(agentEnabled = false)))
    assertFalse((spec as BoolSpec<Context>).get(ctxWith(agentEnabled = false)))
    // Agent listening → the row appears, so the owner can open the HTTP door deliberately.
    val on = ctxWith(agentEnabled = true)
    assertTrue(spec.visibleWhen(on, on))
  }

  /** A Context whose fleet prefs report [agentEnabled] for "enabled" and false for everything else. */
  private fun ctxWith(agentEnabled: Boolean): Context {
    val prefs = mock(SharedPreferences::class.java)
    `when`(prefs.getBoolean("enabled", false)).thenReturn(agentEnabled)
    val c = mock(Context::class.java)
    `when`(c.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
    return c
  }

  @Test
  fun name_acceptsReasonableValues() {
    assertTrue(nameSpec.applyWhen("Kitchen"))
    assertTrue(nameSpec.applyWhen("Bedroom Portal 2"))
    assertTrue(nameSpec.applyWhen("x".repeat(48))) // exactly the cap
  }

  @Test
  fun name_rejectsBlankAndWhitespaceOnly() {
    assertFalse(nameSpec.applyWhen(""))
    assertFalse(nameSpec.applyWhen("   "))
    assertFalse(nameSpec.applyWhen("\t\n"))
  }

  @Test
  fun name_rejectsOverLength_afterTrim() {
    assertFalse(nameSpec.applyWhen("x".repeat(49)))
    // Surrounding whitespace doesn't count: trims to 48, which is allowed.
    assertTrue(nameSpec.applyWhen("  " + "x".repeat(48) + "  "))
  }
}
