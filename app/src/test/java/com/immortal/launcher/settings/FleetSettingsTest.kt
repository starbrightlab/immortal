/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the device-name validation contract on the real registered `fleet` domain (the one the
 * phone-remote rename uses). The `applyWhen` guard is a pure predicate, so it's exercised directly;
 * the actual storage round-trip (FleetConfig get/set → SharedPreferences) is on-device.
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
