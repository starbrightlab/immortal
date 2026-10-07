/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.tensorflow.lite.Interpreter

/**
 * On-device wake word detection with a microWakeWord streaming model (assets/wakeword), the same
 * models a Voice PE runs. Feed it 16 kHz mono PCM16; it returns true on the slice that completes a
 * detection.
 *
 * Mirrors ESPHome's micro_wake_word: [MicroFrontend] features every 10 ms, quantised to int8,
 * pushed `stride` slices at a time into the model (which keeps its own streaming state), and a
 * detection when the mean of the last `sliding_window_size` probabilities passes the model's
 * `probability_cutoff`. After a detection it ignores the next second, so one utterance fires once.
 */
class WakeWordDetector(context: Context, val model: String) : AutoCloseable {
  /** The phrase from the model's manifest, e.g. "Okay Nabu". */
  val phrase: String
  private val cutoff: Int
  private val window: Int
  private val interpreter: Interpreter
  private val stride: Int
  private val input: ByteBuffer
  private val output = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
  private val frontend = MicroFrontend()
  private val features = IntArray(MicroFrontend.NUM_CHANNELS)
  private val recent: IntArray
  private var recentIndex = 0
  private var strideStep = 0
  private var ignoreSlices = -MIN_SLICES_BEFORE_DETECTION
  /** Highest probability (0..255) the model produced since the last read; for diagnostics. */
  var peakProbability = 0

  init {
    val manifest =
        JSONObject(context.assets.open("wakeword/$model.json").bufferedReader().readText())
    phrase = manifest.optString("wake_word", model)
    val micro = manifest.getJSONObject("micro")
    cutoff = (micro.optDouble("probability_cutoff", 0.97) * 255).toInt()
    window = micro.optInt("sliding_window_size", 5)
    recent = IntArray(window)
    val bytes = context.assets.open("wakeword/$model.tflite").use { it.readBytes() }
    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
    buffer.put(bytes).rewind()
    interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(1))
    stride = interpreter.getInputTensor(0).shape()[1]
    input = ByteBuffer.allocateDirect(stride * MicroFrontend.NUM_CHANNELS).order(ByteOrder.nativeOrder())
  }

  /** Process [count] samples of [pcm] from [offset]; true if the wake word was just detected. */
  fun process(pcm: ShortArray, offset: Int, count: Int): Boolean {
    var off = offset
    var left = count
    var detected = false
    while (left > 0) {
      val r = frontend.process(pcm, off, left, features)
      off += r.samplesRead
      left -= r.samplesRead
      if (r.produced && onSlice()) detected = true
      if (r.samplesRead == 0) break
    }
    return detected
  }

  private fun onSlice(): Boolean {
    input.position(strideStep * MicroFrontend.NUM_CHANNELS)
    for (f in features) input.put(MicroFrontend.quantize(f))
    strideStep++
    if (strideStep >= stride) {
      strideStep = 0
      input.rewind()
      output.rewind()
      interpreter.run(input, output)
      recentIndex = (recentIndex + 1) % window
      recent[recentIndex] = output.get(0).toInt() and 0xFF
      if (recent[recentIndex] > peakProbability) peakProbability = recent[recentIndex]
    }
    if (recent[recentIndex] < cutoff) ignoreSlices = minOf(ignoreSlices + 1, 0)
    if (ignoreSlices < 0) return false
    if (recent.sum() > cutoff * window) {
      reset()
      return true
    }
    return false
  }

  /** Forget recent probabilities and hold off for a second (after a detection or a pause). */
  fun reset() {
    recent.fill(0)
    ignoreSlices = -MIN_SLICES_BEFORE_DETECTION
  }

  override fun close() = interpreter.close()

  companion object {
    /** ESPHome's cool-off: 100 slices (1 s) before a new detection can fire. */
    private const val MIN_SLICES_BEFORE_DETECTION = 100
    val MODELS = listOf("okay_nabu", "hey_jarvis")
  }
}
