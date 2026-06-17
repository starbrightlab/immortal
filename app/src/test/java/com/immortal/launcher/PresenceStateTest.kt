/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dream-stop classifier — the single pure decision that used to be smeared across
 * DreamPolicy's `shouldRelaunch` / `bridging` / volatile flags. Background: Meta's power
 * manager force-wakes any dream ~2 min after idle and decides ambient-vs-sleep from its
 * (camera-based) presence service; we read the resulting transition as the presence proxy.
 */
class PresenceStateTest {

  private fun classify(
      userExitAgoMs: Long,
      bridgeAgoMs: Long = Long.MAX_VALUE,
      inStockHandoff: Boolean = false,
      interactive: Boolean,
  ) = classifyDreamStop(userExitAgoMs, bridgeAgoMs, inStockHandoff, interactive)

  @Test
  fun forceWakeWhileAwake_isRedream() {
    // Long after any user tap, screen still interactive → transient force-wake, someone's here.
    assertEquals(DreamStopVerdict.REDREAM, classify(userExitAgoMs = 125_000, interactive = true))
  }

  @Test
  fun sleptAtTimeout_isSleep() {
    // Screen went non-interactive: the device truly slept → the room is empty.
    assertEquals(DreamStopVerdict.SLEEP, classify(userExitAgoMs = 60_000, interactive = false))
  }

  @Test
  fun freshUserTap_isUserExit() {
    // The user tapped out of the dream within the grace window → present & interacting.
    assertEquals(DreamStopVerdict.USER_EXIT, classify(userExitAgoMs = 500, interactive = true))
  }

  @Test
  fun handoffInFlight_isSuppressed() {
    // Tapping "Calls" cold-starts the stock launcher, whose idle dream stops and fires
    // DREAMING_STOPPED. While the bridge is in flight we must NOT act over the stock home.
    assertEquals(DreamStopVerdict.SUPPRESSED, classify(userExitAgoMs = 125_000, inStockHandoff = true, interactive = true))
    assertEquals(DreamStopVerdict.SUPPRESSED, classify(userExitAgoMs = 125_000, bridgeAgoMs = 0, interactive = true))
    assertEquals(DreamStopVerdict.SUPPRESSED, classify(userExitAgoMs = 125_000, bridgeAgoMs = 3_000, interactive = true))
  }

  @Test
  fun afterBridgeGrace_normalClassificationResumes() {
    // Long after a bridge, normal behaviour is back; a stale/never-set timestamp must not suppress.
    assertEquals(DreamStopVerdict.REDREAM, classify(userExitAgoMs = 125_000, bridgeAgoMs = 30_000, interactive = true))
    assertEquals(DreamStopVerdict.REDREAM, classify(userExitAgoMs = 125_000, bridgeAgoMs = Long.MAX_VALUE, interactive = true))
  }

  @Test
  fun handoffTrumpsAFreshUserTap() {
    // A handoff in flight wins even over a recent tap — we never act during a call flow.
    assertEquals(DreamStopVerdict.SUPPRESSED, classify(userExitAgoMs = 500, inStockHandoff = true, interactive = true))
  }
}
