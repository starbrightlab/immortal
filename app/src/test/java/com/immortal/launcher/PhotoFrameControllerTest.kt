package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
  fun stoppableExecutor_runsTasksInOrderWhileRunning() {
    val io = PhotoFrameController.stoppableExecutor()
    val seen = mutableListOf<Int>()
    val done = CountDownLatch(1)
    io.execute { seen += 1 }
    io.execute { seen += 2 }
    io.execute { done.countDown() }
    assertTrue(done.await(5, TimeUnit.SECONDS))
    assertEquals(listOf(1, 2), seen)
    io.shutdownNow()
  }

  @Test
  fun stoppableExecutor_dropsWorkAfterShutdownInsteadOfThrowing() {
    // The screensaver's stop() shuts its executors down while a fetch may still be running; that
    // fetch's callback then queues the next photo. That must be a no-op, not a launcher crash.
    val io = PhotoFrameController.stoppableExecutor()
    io.shutdownNow()
    var ran = false
    io.execute { ran = true }
    assertTrue(io.isShutdown)
    assertTrue(!ran)
  }
}
