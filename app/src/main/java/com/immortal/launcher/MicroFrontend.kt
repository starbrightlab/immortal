/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * A Kotlin port of TensorFlow Lite Micro's audio "micro frontend"
 * (tensorflow/lite/experimental/microfrontend/lib, Apache-2.0), the spectrogram the microWakeWord
 * models were trained on: 30 ms Hann window every 10 ms → 512-point fixed-point FFT (kissfft
 * int16) → 40-channel mel filterbank (125–7500 Hz) → noise reduction → PCAN gain control → log.
 *
 * The models are sensitive to the exact feature values, so this reproduces the C integer
 * arithmetic step by step — int16 wrap-around, shifts and rounding included — rather than
 * approximating it in floating point. It is checked bit-for-bit against pymicro-features (the
 * reference build of the same C code) in MicroFrontendTest. Settings are ESPHome's
 * micro_wake_word preprocessor settings, which all microWakeWord models share.
 *
 * Pure and single-threaded: feed 16 kHz mono PCM16, get a 40-value feature slice every 10 ms.
 */
class MicroFrontend {
  private val windowSize = SAMPLE_RATE * WINDOW_MS / 1000 // 480
  private val step = SAMPLE_RATE * STEP_MS / 1000 // 160
  private val fftSize = 512
  private val spectrumSize = fftSize / 2 + 1

  // --- window ---
  private val windowCoefficients = ShortArray(windowSize)
  private val windowInput = ShortArray(windowSize)
  private var windowUsed = 0
  private val windowOutput = ShortArray(windowSize)
  private var maxAbsOutput = 0

  // --- fft (kissfft, FIXED_POINT=16) ---
  private val fft = KissFftrInt16(fftSize)
  private val fftInput = ShortArray(fftSize)
  private val fftRe = ShortArray(spectrumSize)
  private val fftIm = ShortArray(spectrumSize)

  // --- filterbank ---
  private val fb = Filterbank(NUM_CHANNELS, SAMPLE_RATE, spectrumSize)
  private val energy = IntArray(spectrumSize)
  private val work = LongArray(NUM_CHANNELS + 1)
  private val signal = LongArray(NUM_CHANNELS) // uint32 values

  // --- noise reduction ---
  private val estimate = LongArray(NUM_CHANNELS) // uint32 values
  private val evenSmoothing = (NR_EVEN_SMOOTHING * (1 shl NOISE_REDUCTION_BITS)).toInt()
  private val oddSmoothing = (NR_ODD_SMOOTHING * (1 shl NOISE_REDUCTION_BITS)).toInt()
  private val minSignalRemaining = (NR_MIN_SIGNAL_REMAINING * (1 shl NOISE_REDUCTION_BITS)).toInt()

  // --- pcan ---
  private val inputCorrectionBits = msb32(fftSize.toLong()) - 1 - (FILTERBANK_BITS / 2)
  private val snrShift = PCAN_GAIN_BITS - inputCorrectionBits - PCAN_SNR_BITS
  private val gainLut = buildGainLut(NR_SMOOTHING_BITS - inputCorrectionBits)

  init {
    val arg = Math.PI.toFloat() * 2.0f / windowSize.toFloat()
    for (i in 0 until windowSize) {
      val v = 0.5f - 0.5f * cos(arg * (i + 0.5f))
      windowCoefficients[i] = floor(v * (1 shl WINDOW_BITS) + 0.5f).toInt().toShort()
    }
  }

  fun reset() {
    windowInput.fill(0)
    windowOutput.fill(0)
    windowUsed = 0
    maxAbsOutput = 0
    work.fill(0)
    estimate.fill(0)
  }

  /**
   * Consume samples from [samples] starting at [offset], up to [count]. Returns how many were
   * read; when a full window has been gathered, [out] (40 values, 0..65535) is filled and
   * [produced] is true on the returned [Result].
   */
  fun process(samples: ShortArray, offset: Int, count: Int, out: IntArray): Result {
    val toCopy = minOf(windowSize - windowUsed, count)
    System.arraycopy(samples, offset, windowInput, windowUsed, toCopy)
    windowUsed += toCopy
    if (windowUsed < windowSize) return Result(toCopy, false)

    // Window: (int32 sample * int16 coefficient) >> 12, stored as int16.
    var maxAbs = 0
    for (i in 0 until windowSize) {
      val v = ((windowInput[i].toInt() * windowCoefficients[i].toInt()) shr WINDOW_BITS).toShort()
      windowOutput[i] = v
      val a = (if (v < 0) (-v).toShort() else v).toInt()
      if (a > maxAbs) maxAbs = a
    }
    System.arraycopy(windowInput, step, windowInput, 0, windowSize - step)
    windowUsed -= step
    maxAbsOutput = maxAbs

    // FFT, with the input scaled up to use the full int16 range.
    val inputShift = 15 - msb32(maxAbsOutput.toLong() and 0xFFFFFFFFL)
    for (i in 0 until fftSize) {
      fftInput[i] =
          if (i < windowSize) (((windowOutput[i].toInt() and 0xFFFF) shl inputShift).toShort())
          else 0
    }
    fft.forward(fftInput, fftRe, fftIm)

    // Energy = re² + im² (uint32, held in an int32 array as C does).
    for (i in fb.startIndex until fb.endIndex) {
      val re = fftRe[i].toInt()
      val im = fftIm[i].toInt()
      energy[i] = re * re + im * im
    }
    fb.accumulate(energy, work)
    for (i in 0 until NUM_CHANNELS) {
      signal[i] = (sqrt64(work[i + 1]) ushr inputShift) and 0xFFFFFFFFL
    }

    noiseReduction()
    pcan()

    val correctionBits = msb32(fftSize.toLong()) - 1 - (FILTERBANK_BITS / 2)
    for (i in 0 until NUM_CHANNELS) {
      var value = signal[i]
      value = if (correctionBits < 0) value ushr -correctionBits else (value shl correctionBits)
      value = value and 0xFFFFFFFFL
      value = if (value > 1) log(value, LOG_SCALE_SHIFT) else 0
      out[i] = if (value < 0xFFFF) value.toInt() else 0xFFFF
    }
    return Result(toCopy, true)
  }

  data class Result(val samplesRead: Int, val produced: Boolean)

  private fun noiseReduction() {
    for (i in 0 until NUM_CHANNELS) {
      val smoothing = (if (i and 1 == 0) evenSmoothing else oddSmoothing).toLong()
      val oneMinus = (1L shl NOISE_REDUCTION_BITS) - smoothing
      val scaledUp = (signal[i] shl NR_SMOOTHING_BITS) and 0xFFFFFFFFL
      var est = ((scaledUp * smoothing + estimate[i] * oneMinus) ushr NOISE_REDUCTION_BITS) and 0xFFFFFFFFL
      estimate[i] = est
      if (est > scaledUp) est = scaledUp
      val floor = ((signal[i] * minSignalRemaining) ushr NOISE_REDUCTION_BITS) and 0xFFFFFFFFL
      val subtracted = ((scaledUp - est) and 0xFFFFFFFFL) ushr NR_SMOOTHING_BITS
      signal[i] = if (subtracted > floor) subtracted else floor
    }
  }

  private fun pcan() {
    for (i in 0 until NUM_CHANNELS) {
      val gain = wideDynamicFunction(estimate[i]).toLong() and 0xFFFFFFFFL
      val snr = ((signal[i] * gain) ushr snrShift) and 0xFFFFFFFFL
      signal[i] = pcanShrink(snr)
    }
  }

  private fun wideDynamicFunction(x: Long): Short {
    if (x <= 2) return gainLut[x.toInt()]
    val interval = msb32(x)
    val base = 4 * interval - 6
    val frac =
        ((if (interval < 11) (x shl (11 - interval)) else (x ushr (interval - 11))) and 0x3FF).toInt()
    var result = (gainLut[base + 2].toInt() * frac) shr 5
    result += gainLut[base + 1].toInt() shl 5
    result *= frac
    result = (result + (1 shl 14)) shr 15
    result += gainLut[base].toInt()
    return result.toShort()
  }

  private fun pcanShrink(x: Long): Long =
      if (x < (2L shl PCAN_SNR_BITS)) {
        ((x * x) ushr (2 + 2 * PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) and 0xFFFFFFFFL
      } else {
        ((x ushr (PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) - (1L shl PCAN_OUTPUT_BITS)) and 0xFFFFFFFFL
      }

  private fun buildGainLut(inputBits: Int): ShortArray {
    // C allocates the LUT then indexes it 6 slots back; here index i of that view is i-6+6.
    val lut = ShortArray(4 * WIDE_DYNAMIC_FUNCTION_BITS - 3)
    fun gain(x: Long): Short {
      val xf = x.toFloat() / (1L shl inputBits).toFloat()
      val g = (1L shl PCAN_GAIN_BITS).toFloat() * (xf + PCAN_OFFSET).toDouble().pow(-PCAN_STRENGTH.toDouble()).toFloat()
      return if (g > 0x7FFF) 0x7FFF.toShort() else (g + 0.5f).toInt().toShort()
    }
    lut[0] = gain(0)
    lut[1] = gain(1)
    for (interval in 2..WIDE_DYNAMIC_FUNCTION_BITS) {
      val x0 = 1L shl (interval - 1)
      val x1 = x0 + (x0 shr 1)
      val x2 = if (interval == WIDE_DYNAMIC_FUNCTION_BITS) x0 + (x0 - 1) else 2 * x0
      val y0 = gain(x0)
      val y1 = gain(x1)
      val y2 = gain(x2)
      val diff1 = y1 - y0
      val diff2 = y2 - y0
      val a1 = 4 * diff1 - diff2
      val a2 = diff2 - a1
      lut[4 * interval - 6] = y0
      lut[4 * interval - 6 + 1] = a1.toShort()
      lut[4 * interval - 6 + 2] = a2.toShort()
    }
    return lut
  }

  /** The mel filterbank: channel layout and quantised triangle weights, as filterbank_util.c. */
  private class Filterbank(numChannels: Int, sampleRate: Int, spectrumSize: Int) {
    val channels = numChannels + 1
    val freqStarts = IntArray(channels)
    val weightStarts = IntArray(channels)
    val widths = IntArray(channels)
    val weights: ShortArray
    val unweights: ShortArray
    val startIndex: Int
    var endIndex = 0
      private set

    init {
      val melLow = freqToMel(LOWER_BAND)
      val melHi = freqToMel(UPPER_BAND)
      val melSpacing = (melHi - melLow) / channels.toFloat()
      val centers = FloatArray(channels) { melLow + melSpacing * (it + 1) }
      val hzPerSbin = 0.5f * sampleRate / (spectrumSize.toFloat() - 1)
      startIndex = (1.5f + LOWER_BAND / hzPerSbin).toInt()
      val actualStarts = IntArray(channels)
      val actualWidths = IntArray(channels)
      var chanFreqIndexStart = startIndex
      var weightIndexStart = 0
      var needsZeros = false
      for (chan in 0 until channels) {
        var freqIndex = chanFreqIndexStart
        while (freqToMel(freqIndex * hzPerSbin) <= centers[chan]) freqIndex++
        val width = freqIndex - chanFreqIndexStart
        actualStarts[chan] = chanFreqIndexStart
        actualWidths[chan] = width
        if (width == 0) {
          freqStarts[chan] = 0
          weightStarts[chan] = 0
          widths[chan] = CHANNEL_BLOCK_SIZE
          if (!needsZeros) {
            needsZeros = true
            for (j in 0 until chan) weightStarts[j] += CHANNEL_BLOCK_SIZE
            weightIndexStart += CHANNEL_BLOCK_SIZE
          }
        } else {
          val alignedStart = (chanFreqIndexStart / INDEX_ALIGNMENT) * INDEX_ALIGNMENT
          val alignedWidth = chanFreqIndexStart - alignedStart + width
          val paddedWidth = (((alignedWidth - 1) / CHANNEL_BLOCK_SIZE) + 1) * CHANNEL_BLOCK_SIZE
          freqStarts[chan] = alignedStart
          weightStarts[chan] = weightIndexStart
          widths[chan] = paddedWidth
          weightIndexStart += paddedWidth
        }
        chanFreqIndexStart = freqIndex
      }
      weights = ShortArray(weightIndexStart)
      unweights = ShortArray(weightIndexStart)
      for (chan in 0 until channels) {
        var frequency = actualStarts[chan]
        val offset = frequency - freqStarts[chan]
        val denom = if (chan == 0) melLow else centers[chan - 1]
        for (j in 0 until actualWidths[chan]) {
          val w = (centers[chan] - freqToMel(frequency * hzPerSbin)) / (centers[chan] - denom)
          val idx = weightStarts[chan] + offset + j
          weights[idx] = floor(w * (1 shl FILTERBANK_BITS) + 0.5f).toInt().toShort()
          unweights[idx] = floor((1.0f - w) * (1 shl FILTERBANK_BITS) + 0.5f).toInt().toShort()
          frequency++
        }
        if (frequency > endIndex) endIndex = frequency
      }
    }

    /** uint64 accumulation of weighted energies per channel (FilterbankAccumulateChannels). */
    fun accumulate(energy: IntArray, work: LongArray) {
      var weightAcc = 0L
      var unweightAcc = 0L
      for (i in 0 until channels) {
        var m = freqStarts[i]
        var w = weightStarts[i]
        for (j in 0 until widths[i]) {
          val mag = energy.getOrElse(m) { 0 }.toLong() // int32 → uint64 (sign-extends, as C)
          weightAcc += weights[w].toLong() * mag
          unweightAcc += unweights[w].toLong() * mag
          m++
          w++
        }
        work[i] = weightAcc
        weightAcc = unweightAcc
        unweightAcc = 0
      }
    }

    private fun freqToMel(freq: Float): Float = 1127.0f * Math.log1p((freq / 700.0f).toDouble()).toFloat()
  }

  /**
   * kissfft 1.3.0 real FFT in FIXED_POINT=16, as TFLM builds it: every butterfly divides its
   * inputs by the radix, products round with `(x + 2^14) >> 15`, and results wrap to int16.
   * Only radix 4 is needed for the 256-point complex transform behind a 512-point real FFT.
   */
  private class KissFftrInt16(nfft: Int) {
    private val ncfft = nfft / 2
    private val twRe = ShortArray(ncfft)
    private val twIm = ShortArray(ncfft)
    private val superRe = ShortArray(ncfft / 2)
    private val superIm = ShortArray(ncfft / 2)
    private val factors = IntArray(32)
    private val bufRe = ShortArray(ncfft)
    private val bufIm = ShortArray(ncfft)
    private val inRe = ShortArray(ncfft)
    private val inIm = ShortArray(ncfft)

    init {
      for (i in 0 until ncfft) {
        val phase = -2 * Math.PI * i / ncfft
        twRe[i] = floor(.5 + SAMP_MAX * cos(phase)).toInt().toShort()
        twIm[i] = floor(.5 + SAMP_MAX * sin(phase)).toInt().toShort()
      }
      for (i in 0 until ncfft / 2) {
        val phase = -Math.PI * ((i + 1).toDouble() / ncfft + .5)
        superRe[i] = floor(.5 + SAMP_MAX * cos(phase)).toInt().toShort()
        superIm[i] = floor(.5 + SAMP_MAX * sin(phase)).toInt().toShort()
      }
      // kf_factor: powers of 4, then 2, then odd primes.
      var n = ncfft
      var p = 4
      val floorSqrt = floor(kotlin.math.sqrt(n.toDouble()))
      var k = 0
      do {
        while (n % p != 0) {
          p =
              when (p) {
                4 -> 2
                2 -> 3
                else -> p + 2
              }
          if (p > floorSqrt) p = n
        }
        n /= p
        factors[k++] = p
        factors[k++] = n
      } while (n > 1)
    }

    fun forward(time: ShortArray, outRe: ShortArray, outIm: ShortArray) {
      for (i in 0 until ncfft) {
        inRe[i] = time[2 * i]
        inIm[i] = time[2 * i + 1]
      }
      work(0, 0, 1, 0)
      // DC and Nyquist.
      var tdcR = bufRe[0]
      var tdcI = bufIm[0]
      tdcR = divScalar(tdcR, 2)
      tdcI = divScalar(tdcI, 2)
      outRe[0] = (tdcR + tdcI).toShort()
      outRe[ncfft] = (tdcR - tdcI).toShort()
      outIm[0] = 0
      outIm[ncfft] = 0
      for (k in 1..ncfft / 2) {
        val fpkR = divScalar(bufRe[k], 2)
        val fpkI = divScalar(bufIm[k], 2)
        val fpnkR = divScalar(bufRe[ncfft - k], 2)
        val fpnkI = divScalar((-bufIm[ncfft - k]).toShort(), 2)
        val f1kR = (fpkR + fpnkR).toShort()
        val f1kI = (fpkI + fpnkI).toShort()
        val f2kR = (fpkR - fpnkR).toShort()
        val f2kI = (fpkI - fpnkI).toShort()
        val twR = sround(f2kR * superRe[k - 1] - f2kI * superIm[k - 1])
        val twI = sround(f2kR * superIm[k - 1] + f2kI * superRe[k - 1])
        outRe[k] = ((f1kR + twR) shr 1).toShort()
        outIm[k] = ((f1kI + twI) shr 1).toShort()
        outRe[ncfft - k] = ((f1kR - twR) shr 1).toShort()
        outIm[ncfft - k] = ((twI - f1kI) shr 1).toShort()
      }
    }

    /** kf_work: recursive decimation-in-time over [factors] starting at index [fi]. */
    private fun work(out: Int, inIdx: Int, fstride: Int, fi: Int) {
      val p = factors[fi]
      val m = factors[fi + 1]
      val end = out + p * m
      var o = out
      var f = inIdx
      if (m == 1) {
        do {
          bufRe[o] = inRe[f]
          bufIm[o] = inIm[f]
          f += fstride
        } while (++o != end)
      } else {
        do {
          work(o, f, fstride * p, fi + 2)
          f += fstride
          o += m
        } while (o != end)
      }
      check(p == 4) { "only radix-4 FFT sizes are supported" }
      bfly4(out, fstride, m)
    }

    private fun bfly4(out: Int, fstride: Int, m: Int) {
      var tw1 = 0
      var tw2 = 0
      var tw3 = 0
      val m2 = 2 * m
      val m3 = 3 * m
      var o = out
      var k = m
      do {
        for (idx in intArrayOf(o, o + m, o + m2, o + m3)) {
          bufRe[idx] = divScalar(bufRe[idx], 4)
          bufIm[idx] = divScalar(bufIm[idx], 4)
        }
        val s0R = cmulR(bufRe[o + m], bufIm[o + m], twRe[tw1], twIm[tw1])
        val s0I = cmulI(bufRe[o + m], bufIm[o + m], twRe[tw1], twIm[tw1])
        val s1R = cmulR(bufRe[o + m2], bufIm[o + m2], twRe[tw2], twIm[tw2])
        val s1I = cmulI(bufRe[o + m2], bufIm[o + m2], twRe[tw2], twIm[tw2])
        val s2R = cmulR(bufRe[o + m3], bufIm[o + m3], twRe[tw3], twIm[tw3])
        val s2I = cmulI(bufRe[o + m3], bufIm[o + m3], twRe[tw3], twIm[tw3])
        val s5R = (bufRe[o] - s1R).toShort()
        val s5I = (bufIm[o] - s1I).toShort()
        bufRe[o] = (bufRe[o] + s1R).toShort()
        bufIm[o] = (bufIm[o] + s1I).toShort()
        val s3R = (s0R + s2R).toShort()
        val s3I = (s0I + s2I).toShort()
        val s4R = (s0R - s2R).toShort()
        val s4I = (s0I - s2I).toShort()
        bufRe[o + m2] = (bufRe[o] - s3R).toShort()
        bufIm[o + m2] = (bufIm[o] - s3I).toShort()
        tw1 += fstride
        tw2 += fstride * 2
        tw3 += fstride * 3
        bufRe[o] = (bufRe[o] + s3R).toShort()
        bufIm[o] = (bufIm[o] + s3I).toShort()
        bufRe[o + m] = (s5R + s4I).toShort()
        bufIm[o + m] = (s5I - s4R).toShort()
        bufRe[o + m3] = (s5R - s4I).toShort()
        bufIm[o + m3] = (s5I + s4R).toShort()
        o++
      } while (--k != 0)
    }

    private fun cmulR(ar: Short, ai: Short, br: Short, bi: Short): Short = sround(ar * br - ai * bi)

    private fun cmulI(ar: Short, ai: Short, br: Short, bi: Short): Short = sround(ar * bi + ai * br)

    private fun divScalar(x: Short, k: Int): Short = sround(x * (SAMP_MAX / k))

    private fun sround(x: Int): Short = ((x + (1 shl 14)) shr 15).toShort()

    companion object {
      private const val SAMP_MAX = 32767
    }
  }

  companion object {
    const val SAMPLE_RATE = 16000
    const val NUM_CHANNELS = 40
    private const val WINDOW_MS = 30
    const val STEP_MS = 10
    private const val WINDOW_BITS = 12
    private const val FILTERBANK_BITS = 12
    private const val LOWER_BAND = 125.0f
    private const val UPPER_BAND = 7500.0f
    private const val INDEX_ALIGNMENT = 2 // kFilterbankIndexAlignment(4) / sizeof(int16)
    private const val CHANNEL_BLOCK_SIZE = 4
    private const val NOISE_REDUCTION_BITS = 14
    private const val NR_SMOOTHING_BITS = 10
    private const val NR_EVEN_SMOOTHING = 0.025f
    private const val NR_ODD_SMOOTHING = 0.06f
    private const val NR_MIN_SIGNAL_REMAINING = 0.05f
    private const val PCAN_STRENGTH = 0.95f
    private const val PCAN_OFFSET = 80.0f
    private const val PCAN_GAIN_BITS = 21
    private const val PCAN_SNR_BITS = 12
    private const val PCAN_OUTPUT_BITS = 6
    private const val WIDE_DYNAMIC_FUNCTION_BITS = 32
    private const val LOG_SCALE_SHIFT = 6
    private const val LOG_SCALE_LOG2 = 16
    private const val LOG_SEGMENTS_LOG2 = 7
    private const val LOG_SCALE = 65536L
    private const val LOG_COEFF = 45426L

    private val LOG_LUT =
        intArrayOf(
            0, 224, 442, 654, 861, 1063, 1259, 1450, 1636, 1817, 1992, 2163, 2329, 2490, 2646,
            2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848, 3960, 4068, 4172, 4272, 4368,
            4460, 4549, 4633, 4714, 4791, 4864, 4934, 5001, 5063, 5123, 5178, 5231, 5280, 5326,
            5368, 5408, 5444, 5477, 5507, 5533, 5557, 5578, 5595, 5610, 5622, 5631, 5637, 5640,
            5641, 5638, 5633, 5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406,
            5370, 5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762, 4697,
            4630, 4561, 4490, 4416, 4341, 4264, 4184, 4103, 4020, 3935, 3848, 3759, 3668, 3575,
            3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659, 2549, 2437, 2323, 2207, 2090,
            1971, 1851, 1729, 1605, 1480, 1353, 1224, 1094, 963, 830, 695, 559, 421, 282, 142, 0,
            0)

    /** 32 - clz32(n) on the low 32 bits; 0 for 0. */
    fun msb32(n: Long): Int {
      val v = (n and 0xFFFFFFFFL).toInt()
      return 32 - Integer.numberOfLeadingZeros(v)
    }

    private fun msb64(n: Long): Int = 64 - java.lang.Long.numberOfLeadingZeros(n)

    private fun sqrt32(num0: Long): Long {
      var num = num0
      if (num == 0L) return 0
      var res = 0L
      var maxBit = 32 - msb32(num)
      maxBit = maxBit or 1
      var bit = 1L shl (31 - maxBit)
      var iterations = (31 - maxBit) / 2 + 1
      while (iterations-- > 0) {
        if (num >= res + bit) {
          num -= res + bit
          res = (res ushr 1) + bit
        } else {
          res = res ushr 1
        }
        bit = bit ushr 2
      }
      if (num > res && res != 0xFFFFL) res++
      return res and 0xFFFF // uint16 result
    }

    /** Integer square root of an unsigned 64-bit value, as filterbank.c's Sqrt64. */
    fun sqrt64(num0: Long): Long {
      if ((num0 ushr 32) == 0L) return sqrt32(num0)
      var num = num0
      var res = 0L
      var maxBit = 64 - msb64(num)
      maxBit = maxBit or 1
      var bit = 1L shl (63 - maxBit)
      var iterations = (63 - maxBit) / 2 + 1
      while (iterations-- > 0) {
        if (java.lang.Long.compareUnsigned(num, res + bit) >= 0) {
          num -= res + bit
          res = (res ushr 1) + bit
        } else {
          res = res ushr 1
        }
        bit = bit ushr 2
      }
      if (java.lang.Long.compareUnsigned(num, res) > 0 && res != 0xFFFFFFFFL) res++
      return res and 0xFFFFFFFFL // uint32 result
    }

    private fun log2FractionPart(x: Long, log2x: Int): Long {
      var frac = (x - (1L shl log2x)).toInt()
      frac = if (log2x < LOG_SCALE_LOG2) frac shl (LOG_SCALE_LOG2 - log2x) else frac shr (log2x - LOG_SCALE_LOG2)
      val baseSeg = (frac.toLong() and 0xFFFFFFFFL) ushr (LOG_SCALE_LOG2 - LOG_SEGMENTS_LOG2)
      val segUnit = (1L shl LOG_SCALE_LOG2) ushr LOG_SEGMENTS_LOG2
      val c0 = LOG_LUT[baseSeg.toInt()]
      val c1 = LOG_LUT[baseSeg.toInt() + 1]
      val segBase = (segUnit * baseSeg).toInt()
      val relPos = ((c1 - c0) * (frac - segBase)) shr LOG_SCALE_LOG2
      return (frac + c0 + relPos).toLong() and 0xFFFFFFFFL
    }

    /** log_scale.c's fixed-point natural log, scaled by 2^[scaleShift]. */
    fun log(x: Long, scaleShift: Int): Long {
      val integer = msb32(x) - 1
      val fraction = log2FractionPart(x, integer)
      val log2 = ((integer.toLong() shl LOG_SCALE_LOG2) + fraction) and 0xFFFFFFFFL
      val round = LOG_SCALE / 2
      val loge = ((LOG_COEFF * log2 + round) ushr LOG_SCALE_LOG2) and 0xFFFFFFFFL
      return ((((loge shl scaleShift) and 0xFFFFFFFFL) + round) ushr LOG_SCALE_LOG2) and 0xFFFFFFFFL
    }

    /** ESPHome's mapping of a frontend value (0..~670) to the models' int8 input. */
    fun quantize(feature: Int): Byte {
      val v = (feature * 256 + 333) / 666 - 128
      return v.coerceIn(-128, 127).toByte()
    }
  }
}
