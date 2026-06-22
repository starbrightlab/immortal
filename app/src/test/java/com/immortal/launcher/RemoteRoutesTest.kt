/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure Authorization-header parsing behind the remote's session auth. */
class RemoteRoutesTest {

  @Test
  fun bearer_extractsToken() {
    assertEquals("abc123", RemoteRoutes.bearer("Bearer abc123"))
    assertEquals("abc123", RemoteRoutes.bearer("  Bearer abc123  "))
  }

  @Test
  fun bearer_isSchemeCaseInsensitive() {
    assertEquals("tok", RemoteRoutes.bearer("bearer tok"))
    assertEquals("tok", RemoteRoutes.bearer("BEARER tok"))
  }

  @Test
  fun bearer_rejectsMalformed() {
    assertNull(RemoteRoutes.bearer(null))
    assertNull(RemoteRoutes.bearer("abc123")) // no scheme
    assertNull(RemoteRoutes.bearer("Basic abc123")) // wrong scheme
    assertNull(RemoteRoutes.bearer("Bearer ")) // blank token
    assertNull(RemoteRoutes.bearer("Bearer    "))
  }

  @Test
  fun clampWaitMs_boundsPresetWaits() {
    assertEquals(300L, RemoteRoutes.clampWaitMs(300L))
    assertEquals(0L, RemoteRoutes.clampWaitMs(-5L))
    assertEquals(10_000L, RemoteRoutes.clampWaitMs(999_999L))
  }
}
