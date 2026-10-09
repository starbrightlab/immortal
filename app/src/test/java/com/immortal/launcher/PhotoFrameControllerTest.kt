package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure geometry behind the screensaver's video fill mode (no Context). */
class PhotoFrameControllerTest {

  @Test
  fun videoCoverSize_widerClipCoversByHeight() {
    // 16:9 clip on a 4:3 screen: match the screen height, overflow horizontally.
    val (w, h) = PhotoFrameController.videoCoverSize(1920, 1080, 1280, 960)!!
    assertEquals(960, h)
    assertTrue("width $w must cover 1280", w >= 1280)
    // Aspect preserved: w/h == 1920/1080 (within rounding).
    assertEquals(1707, w)
  }

  @Test
  fun videoCoverSize_tallerClipCoversByWidth() {
    // Portrait phone clip on a landscape screen: match the width, overflow vertically.
    val (w, h) = PhotoFrameController.videoCoverSize(1080, 1920, 1920, 1080)!!
    assertEquals(1920, w)
    assertTrue("height $h must cover 1080", h >= 1080)
    assertEquals(3413, h)
  }

  @Test
  fun videoCoverSize_matchingAspectIsExactlyTheScreen() {
    assertEquals(Pair(1920, 1080), PhotoFrameController.videoCoverSize(3840, 2160, 1920, 1080))
  }

  @Test
  fun videoCoverSize_unknownDimensionsFallBack() {
    // Audio-only / not-yet-reported sizes and an unlaid-out host all mean "letterbox instead".
    assertNull(PhotoFrameController.videoCoverSize(0, 0, 1920, 1080))
    assertNull(PhotoFrameController.videoCoverSize(1920, 1080, 0, 0))
    assertNull(PhotoFrameController.videoCoverSize(-1, 1080, 1920, 1080))
  }

  @Test
  fun sampleSize_phonePhotoOnA1280Panel_halves() {
    // 4032×3024 (12MP): the old crash cap alone decoded it whole (48.8MB); 2016×1512 still covers
    // a 1280px panel in fill mode, either way round.
    assertEquals(2, PhotoFrameController.sampleSizeFor(4032, 3024, 1280))
    assertEquals(2, PhotoFrameController.sampleSizeFor(3024, 4032, 1280))
  }

  @Test
  fun sampleSize_phonePhotoOnA1080pPanel_staysWhole() {
    // Halving would leave a 1512px short edge, below the 1920px panel — fill mode would upscale.
    assertEquals(1, PhotoFrameController.sampleSizeFor(4032, 3024, 1920))
  }

  @Test
  fun sampleSize_neverCoarserThanThePanelAndNeverFinerThanTheCrashCap() {
    val photos = listOf(640 to 480, 1600 to 1200, 4032 to 3024, 8064 to 6048, 12000 to 2000, 6000 to 8000)
    for (panel in listOf(1280, 1920)) {
      for ((w, h) in photos) {
        val s = PhotoFrameController.sampleSizeFor(w, h, panel)
        val capOnly = PhotoFrameController.sampleSizeFor(w, h, Int.MAX_VALUE)
        assertTrue("${w}x$h on $panel: $s is not a power of two", Integer.bitCount(s) == 1)
        assertTrue("${w}x$h on $panel: $s finer than the crash cap $capOnly", s >= capOnly)
        // Any extra halving beyond the cap still leaves the short edge covering the panel.
        if (s > capOnly) assertTrue("${w}x$h on $panel: short edge under the panel", minOf(w, h) / s >= panel)
      }
    }
  }

  @Test
  fun sampleSize_48mpOnA1280Panel_quarters() {
    // 8064×6048: the cap alone gave 2 (4032×3024, 48.8MB); the panel bound gives 4 (12.2MB).
    assertEquals(2, PhotoFrameController.sampleSizeFor(8064, 6048, Int.MAX_VALUE))
    assertEquals(4, PhotoFrameController.sampleSizeFor(8064, 6048, 1280))
  }
}
