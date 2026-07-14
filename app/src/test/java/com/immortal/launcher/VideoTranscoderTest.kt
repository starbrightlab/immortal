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

/**
 * Pure geometry behind the transcoder's output sizing. This math is what keeps black bars out of
 * cached derivatives: the Presentation is sized to the *source's* aspect (fitted in the box), so
 * there is never a mismatch to pad. See the PR-#153 review finding.
 */
class VideoTranscoderTest {

  @Test
  fun fitWithin_landscape4kFitsByWidth() {
    // 16:9 into a 3:2 box: width binds. 3840x2160 * (1200/3840) = 1200x675 -> 674 (even floor).
    assertEquals(Pair(1200, 674), VideoTranscoder.fitWithin(3840, 2160, 1200, 800))
  }

  @Test
  fun fitWithin_portraitPhoneFitsByHeight() {
    // 9:16 into a 3:2 box: height binds. 1080x1920 * (800/1920) = 450x800 — no pillarbox baked in.
    assertEquals(Pair(450, 800), VideoTranscoder.fitWithin(1080, 1920, 1200, 800))
  }

  @Test
  fun fitWithin_matchingAspectFillsTheBoxExactly() {
    assertEquals(Pair(1200, 800), VideoTranscoder.fitWithin(2400, 1600, 1200, 800))
  }

  @Test
  fun fitWithin_neverUpscalesASmallSource() {
    // 640x360 is already smaller than the box: keep it, don't inflate the file.
    assertEquals(Pair(640, 360), VideoTranscoder.fitWithin(640, 360, 1200, 800))
  }

  @Test
  fun fitWithin_flooredToEvenDimensions() {
    // 1279x721 scaled by 800/721 -> width 1418... capped: scale = min(1200/1279, 800/721, 1).
    // 1200/1279 = 0.9382 -> 1200 x 676.4 -> 1200x676; both even.
    val (w, h) = VideoTranscoder.fitWithin(1279, 721, 1200, 800)!!
    assertEquals(0, w % 2)
    assertEquals(0, h % 2)
    assertEquals(Pair(1200, 676), Pair(w, h))
  }

  @Test
  fun fitWithin_rejectsDegenerateInput() {
    assertNull(VideoTranscoder.fitWithin(0, 1080, 1200, 800))
    assertNull(VideoTranscoder.fitWithin(1920, -1, 1200, 800))
    assertNull(VideoTranscoder.fitWithin(1920, 1080, 0, 800))
    // A pathological 1xN source collapses below 2px wide -> null rather than an invalid encode.
    assertNull(VideoTranscoder.fitWithin(1, 4000, 1200, 800))
  }
}
