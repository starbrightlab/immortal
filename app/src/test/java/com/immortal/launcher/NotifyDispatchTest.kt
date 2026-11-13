/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the Do Not Disturb rule behind [NotifyDispatch.plan] — and therefore what `POST /notify`
 * reports back: the toast always renders, audio only when allowed, and the response never claims
 * a sound or a spoken line that DND swallowed.
 */
class NotifyDispatchTest {

  private val everything =
      NotifyPayload.parse("""{"message":"Doorbell","sound":"/sdcard/ding.mp3","speak":"Door"}""")!!

  @Test
  fun audible_reportsEverythingRequested() {
    assertEquals(
        NotifyDispatch.Delivery(shown = true, sound = true, spoke = true),
        NotifyDispatch.plan(everything, audible = true))
  }

  @Test
  fun dnd_keepsTheToast_butReportsAudioAsNotPlayed() {
    assertEquals(
        NotifyDispatch.Delivery(shown = true, sound = false, spoke = false),
        NotifyDispatch.plan(everything, audible = false))
  }

  @Test
  fun audible_doesNotClaimAudioThatWasNeverRequested() {
    val textOnly = NotifyPayload.parse("""{"message":"Backup finished"}""")!!
    assertEquals(
        NotifyDispatch.Delivery(shown = true, sound = false, spoke = false),
        NotifyDispatch.plan(textOnly, audible = true))
  }

  @Test
  fun dnd_speakOnly_doesNothingAndSaysSo() {
    val speakOnly = NotifyPayload.parse("""{"speak":"Dinner is ready"}""")!!
    assertEquals(
        NotifyDispatch.Delivery(shown = false, sound = false, spoke = false),
        NotifyDispatch.plan(speakOnly, audible = false))
  }
}
