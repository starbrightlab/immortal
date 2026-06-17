/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keep-screen-on policy (pure decision). Whether the frame HOLDS the screen decides
 * permanent-frame ([FrameMode.ALWAYS_ON]) vs presence-driven sleep ([FrameMode.PRESENCE]).
 * The dream-stop classification itself is tested in [PresenceStateTest].
 */
class DreamPolicyTest {

  @Test
  fun presenceMode_neverHoldsTheScreen() {
    // PRESENCE hands control to the Portal's presence policy regardless of power/battery.
    assertFalse(DreamPolicy.holdScreenOn(FrameMode.PRESENCE, hasBattery = false, batterySaver = true, powered = true))
    assertFalse(DreamPolicy.holdScreenOn(FrameMode.PRESENCE, hasBattery = true, batterySaver = false, powered = true))
    assertFalse(DreamPolicy.holdScreenOn(FrameMode.PRESENCE, hasBattery = true, batterySaver = true, powered = false))
  }

  @Test
  fun alwaysOn_mainsPoweredPortals_holdTheScreen() {
    // No battery (Portal+, Mini, gen-2, TV): permanent frame, saver irrelevant.
    assertTrue(DreamPolicy.holdScreenOn(FrameMode.ALWAYS_ON, hasBattery = false, batterySaver = true, powered = false))
  }

  @Test
  fun alwaysOn_batterySaver_releasesTheScreenOnlyWhileUnplugged() {
    // Unplugged + saver on: don't hold — presence decides photos vs sleep.
    assertFalse(DreamPolicy.holdScreenOn(FrameMode.ALWAYS_ON, hasBattery = true, batterySaver = true, powered = false))
    // Charging: permanent frame.
    assertTrue(DreamPolicy.holdScreenOn(FrameMode.ALWAYS_ON, hasBattery = true, batterySaver = true, powered = true))
    // Saver off: permanent frame on battery too.
    assertTrue(DreamPolicy.holdScreenOn(FrameMode.ALWAYS_ON, hasBattery = true, batterySaver = false, powered = false))
  }
}
