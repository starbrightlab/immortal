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

class MultiRoomSpeakerTest {

  @Test
  fun isSpeaker_trueForEachSupportedPlayer() {
    for (pkg in listOf("de.badaix.snapcast", "com.sendspindroid", "io.music_assistant.client")) {
      assertTrue(pkg, MultiRoomService.isSpeaker(enabled = false) { it == pkg })
    }
  }

  @Test
  fun isSpeaker_trueWhenEnabledWithNoPlayerInstalled() {
    assertTrue(MultiRoomService.isSpeaker(enabled = true) { false })
  }

  @Test
  fun isSpeaker_falseWithoutPlayerOrSetting() {
    assertFalse(MultiRoomService.isSpeaker(enabled = false) { it == "com.example.other" })
  }
}
