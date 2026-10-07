package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The microWakeWord models only work on features that match the ones they were trained on, so
 * [MicroFrontend] must reproduce TFLM's micro frontend exactly. The reference CSV holds the
 * features pymicro-features (the C frontend, ESPHome's settings) computes for the signal below.
 */
class MicroFrontendTest {

  /** Integer-only test signal, mirrored by the script that generated the reference CSV. */
  private fun signal(n: Int = 32000): ShortArray {
    val out = ShortArray(n)
    var state = 12345L
    for (i in 0 until n) {
      state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
      val noise = ((state shr 16) % 2001).toInt() - 1000
      val period = 40 + i / 800
      val saw = (i % period) * 16000 / period - 8000
      val loud = (i % 8000) < 4000
      val v = if (loud) saw + noise else (saw shr 3) + (noise shr 2)
      out[i] = v.coerceIn(-32768, 32767).toShort()
    }
    return out
  }

  @Test
  fun matchesTheReferenceFrontendBitForBit() {
    val expected =
        javaClass.classLoader!!.getResourceAsStream("microfrontend_reference.csv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line -> line.split(",").map { it.trim().toInt() } }

    val audio = signal()
    val fe = MicroFrontend()
    val out = IntArray(MicroFrontend.NUM_CHANNELS)
    val frames = ArrayList<List<Int>>()
    var i = 0
    while (i + 160 <= audio.size) {
      // Feed 10 ms at a time, as the reference did.
      var off = i
      var left = 160
      while (left > 0) {
        val r = fe.process(audio, off, left, out)
        off += r.samplesRead
        left -= r.samplesRead
        if (r.produced) frames.add(out.toList())
        if (r.samplesRead == 0) break
      }
      i += 160
    }

    assertEquals("frame count", expected.size, frames.size)
    for (f in expected.indices) {
      assertEquals("frame $f", expected[f], frames[f])
    }
  }

  @Test
  fun quantize_matchesEsphomeIntegerMapping() {
    assertEquals(-128, MicroFrontend.quantize(0).toInt())
    assertEquals(-128 + (100 * 256 + 333) / 666, MicroFrontend.quantize(100).toInt())
    assertEquals(127, MicroFrontend.quantize(670).toInt())
  }
}
