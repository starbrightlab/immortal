/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure PIN-window check behind remote pairing (no Context / prefs needed). */
class RemotePairingTest {

  private val exp = 10_000L // PIN expiry instant

  @Test
  fun pinMatches_withinWindowAndEqual() {
    assertTrue(RemotePairing.pinMatches("123456", exp, "123456", now = 9_000L))
  }

  @Test
  fun pinMatches_trimsCandidate() {
    assertTrue(RemotePairing.pinMatches("123456", exp, "  123456 ", now = 9_000L))
  }

  @Test
  fun pinMatches_rejectsWrongPin() {
    assertFalse(RemotePairing.pinMatches("123456", exp, "123457", now = 9_000L))
  }

  @Test
  fun pinMatches_rejectsExpired() {
    assertFalse(RemotePairing.pinMatches("123456", exp, "123456", now = 10_000L)) // now == expiry
    assertFalse(RemotePairing.pinMatches("123456", exp, "123456", now = 99_999L))
  }

  @Test
  fun pinMatches_rejectsMissingPin() {
    assertFalse(RemotePairing.pinMatches(null, exp, "123456", now = 9_000L))
    assertFalse(RemotePairing.pinMatches("", exp, "", now = 9_000L)) // no PIN issued, blank guess
  }
}
