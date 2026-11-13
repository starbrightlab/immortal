/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * How long the voice satellite waits before reopening the microphone after a capture session
 * that produced no audio. On Android 9 (Portal Gen 1) only one app may capture at a time, so
 * while another app holds the mic every attempt fails at once; retrying on a fixed short delay
 * would spin. Delays double from [MIN_MS] up to [MAX_MS]. Pure, so it is unit-tested.
 */
data class MicBackoff(val failures: Int, val delayMs: Long) {
  companion object {
    const val MIN_MS = 500L
    const val MAX_MS = 30_000L
    /** A session that delivered audio for at least this long counts as healthy. */
    const val HEALTHY_MS = 3_000L

    val Idle = MicBackoff(0, MIN_MS)

    fun failed(previous: MicBackoff): MicBackoff {
      val failures = previous.failures + 1
      val delay = (MIN_MS shl (failures - 1).coerceAtMost(16)).coerceAtMost(MAX_MS)
      return MicBackoff(failures, delay)
    }
  }
}
