/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure name→global-action mapping behind the phone remote's nav buttons. */
class RemoteInputTest {

  @Test
  fun globalActionCode_mapsTheActionsThatWorkOnThePortal() {
    assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, RemoteInput.globalActionCode("back"))
    assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, RemoteInput.globalActionCode("home"))
    assertEquals(
        AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, RemoteInput.globalActionCode("power"))
  }

  @Test
  fun globalActionCode_isCaseAndWhitespaceInsensitive() {
    assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, RemoteInput.globalActionCode("  HOME "))
  }

  @Test
  fun globalActionCode_unsupportedPortalActionsAreNull() {
    // RECENTS / NOTIFICATIONS / QUICK_SETTINGS no-op on the Portal (no overview/shade/QS), so
    // they're intentionally unmapped; "apps" is handled as an in-app switcher, not here.
    assertNull(RemoteInput.globalActionCode("recents"))
    assertNull(RemoteInput.globalActionCode("notifications"))
    assertNull(RemoteInput.globalActionCode("quicksettings"))
    assertNull(RemoteInput.globalActionCode("apps"))
  }

  @Test
  fun globalActionCode_unknownIsNull() {
    assertNull(RemoteInput.globalActionCode("dpad_up"))
    assertNull(RemoteInput.globalActionCode(""))
    assertNull(RemoteInput.globalActionCode("eject"))
  }

  @Test
  fun actions_areAllResolvable() {
    RemoteInput.ACTIONS.forEach { assertEquals(true, RemoteInput.globalActionCode(it) != null) }
  }

  @Test
  fun nextText_appliesEditModes() {
    assertEquals("hello", RemoteInput.nextText("old", "hello", "set"))
    assertEquals("abc", RemoteInput.nextText("ab", "c", "append"))
    assertEquals("ab", RemoteInput.nextText("abc", "", "backspace"))
    assertEquals("", RemoteInput.nextText("", "", "backspace")) // backspace on empty stays empty
    assertEquals("", RemoteInput.nextText("abc", "ignored", "clear"))
    assertEquals("x", RemoteInput.nextText("anything", "x", "weird")) // unknown mode == set
  }
}
