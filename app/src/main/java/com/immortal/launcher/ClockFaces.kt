/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The time element of a face, abstracted so the four clock modes (digital / flip / analog /
 * word) are swappable behind one type. [FaceRenderer] builds one via [makeClockFace], adds
 * its [view] to the layout, and calls [update] each tick. The meta row (date / battery /
 * weather) and positioning are the renderer's concern, not the clock's.
 */
interface ClockFaceView {
  val view: View
  /** Refresh for the current second. [blinkOn] drives the blink separator. */
  fun update(now: Date, blinkOn: Boolean)
}

/** Pick the renderer for a clock spec. ANALOG / WORD fall back to digital until built. */
fun makeClockFace(context: Context, spec: ClockSpec, assets: AssetResolver): ClockFaceView =
    when (spec.mode) {
      ClockMode.FLIP -> FlipClockFaceView(context, spec, assets)
      else -> DigitalClockFaceView(context, spec, assets)
    }

// ─── Shared styling ──────────────────────────────────────────────────────────

/** Style helpers shared by the clock views and the renderer's meta-row text. */
object FaceStyle {
  fun colorWithOpacity(hex: String, opacity: Float): Int {
    val base = runCatching { Color.parseColor(hex) }.getOrDefault(Color.WHITE)
    val a = (opacity.coerceIn(0f, 1f) * 255).toInt()
    return (a shl 24) or (base and 0x00FFFFFF)
  }

  /** Resolve the spec's font; [light] keeps the original face's sans-serif-light default. */
  fun typeface(assets: AssetResolver, spec: ClockSpec, light: Boolean): Typeface =
      if (light && spec.font == Face.FONT_SANS_LIGHT) assets.font(Face.FONT_SANS_LIGHT, spec.fontWeight)
      else assets.font(spec.font, spec.fontWeight)

  fun applyShadow(t: TextView, shadow: Shadow, colorHex: String) {
    when (shadow) {
      Shadow.NONE -> t.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
      Shadow.SOFT -> t.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
      Shadow.STRONG -> t.setShadowLayer(12f, 0f, 3f, 0xCC000000.toInt())
      // Halo / neon glow in (roughly) the text colour — the premium look.
      Shadow.HALO -> t.setShadowLayer(20f, 0f, 0f, 0x66FFFFFF)
      Shadow.NEON -> t.setShadowLayer(24f, 0f, 0f, colorWithOpacity(colorHex, 0.9f))
    }
  }
}

/** Time-component formatting shared across clock modes. */
object ClockMath {
  /** Hour string. [pad] forces two digits regardless of [ClockSpec.leadingZero] (flip cards). */
  fun hour(spec: ClockSpec, now: Date, pad: Boolean = false): String {
    val two = spec.leadingZero || pad
    val pat = if (spec.is24h) (if (two) "HH" else "H") else (if (two) "hh" else "h")
    return SimpleDateFormat(pat, Locale.getDefault()).format(now)
  }

  fun minute(now: Date): String = SimpleDateFormat("mm", Locale.getDefault()).format(now)

  fun second(now: Date): String = SimpleDateFormat("ss", Locale.getDefault()).format(now)

  fun amPm(now: Date): String = SimpleDateFormat("a", Locale.getDefault()).format(now)

  fun separator(sep: Separator, blinkOn: Boolean): String =
      when (sep) {
        Separator.COLON -> ":"
        Separator.DOT -> "."
        Separator.NONE -> ""
        Separator.BLINK -> if (blinkOn) ":" else " "
      }

  /** The full single-line digital string (also used as the flip fallback). */
  fun digitalLine(spec: ClockSpec, now: Date, blinkOn: Boolean): String {
    val sep = separator(spec.separator, blinkOn)
    val sb = StringBuilder().append(hour(spec, now)).append(sep).append(minute(now))
    if (spec.showSeconds) sb.append(sep).append(second(now))
    if (spec.showAmPm) sb.append(" ").append(amPm(now))
    return sb.toString()
  }
}

// ─── Digital ─────────────────────────────────────────────────────────────────

/** The original single-TextView clock — large light type by default. */
class DigitalClockFaceView(
    private val context: Context,
    private val spec: ClockSpec,
    assets: AssetResolver,
) : ClockFaceView {
  private val label =
      TextView(context).apply {
        textSize = 96f * spec.sizeScale / 100f
        setTextColor(FaceStyle.colorWithOpacity(spec.color, spec.opacity))
        typeface = FaceStyle.typeface(assets, spec, light = true)
        FaceStyle.applyShadow(this, spec.shadow, spec.color)
      }

  override val view: View = label

  override fun update(now: Date, blinkOn: Boolean) {
    label.text = ClockMath.digitalLine(spec, now, blinkOn)
  }
}

// ─── Flip ────────────────────────────────────────────────────────────────────

/**
 * A split-flap flip clock: two digit cards per group (HH MM, plus SS when enabled), each a
 * dark rounded card with a centre hinge line. When [ClockSpec.animateDigits] is set, a card
 * flips on its horizontal axis as its digit changes. Flip clocks always show two flaps per
 * group, so hours pad to two digits here regardless of `leadingZero`.
 *
 * (A single-axis card flip — a faithful approximation of a true two-flap split-flap, which we
 * can deepen later. It's the first face the original overlay couldn't draw.)
 */
class FlipClockFaceView(
    private val context: Context,
    private val spec: ClockSpec,
    assets: AssetResolver,
) : ClockFaceView {
  private val scale = spec.sizeScale / 100f
  private val colorInt = FaceStyle.colorWithOpacity(spec.color, spec.opacity)
  private val typeface = FaceStyle.typeface(assets, spec, light = false)

  // Fixed card dimensions (real split-flap flaps are fixed): a card sized to its digit, so the
  // match-parent hinge spans the card without expanding it and starving its neighbours.
  private val digitSp = 72f * scale
  private val cardW = sp(digitSp * 0.66f) + dp(18)
  private val cardH = sp(digitSp) + dp(20)

  private val row =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }

  private val hourTens = addCard()
  private val hourOnes = addCard()
  private val colon1 = addSeparator()
  private val minTens = addCard()
  private val minOnes = addCard()
  private val secCards: Pair<FlipDigitView, FlipDigitView>? =
      if (spec.showSeconds) {
        addSeparator()
        addCard() to addCard()
      } else null

  override val view: View = row

  override fun update(now: Date, blinkOn: Boolean) {
    val h = ClockMath.hour(spec, now, pad = true)
    val m = ClockMath.minute(now)
    hourTens.setChar(h[0])
    hourOnes.setChar(h[1])
    minTens.setChar(m[0])
    minOnes.setChar(m[1])
    if (secCards != null) {
      val s = ClockMath.second(now)
      secCards.first.setChar(s[0])
      secCards.second.setChar(s[1])
    }
    if (spec.separator == Separator.BLINK) colon1.alpha = if (blinkOn) 1f else 0.25f
  }

  private fun addCard(): FlipDigitView {
    val card = FlipDigitView(context, digitSp, colorInt, typeface, spec.animateDigits)
    val lp = LinearLayout.LayoutParams(cardW, cardH)
    lp.setMargins(dp(3), 0, dp(3), 0)
    row.addView(card, lp)
    return card
  }

  private fun addSeparator(): TextView {
    val t =
        TextView(context).apply {
          text = if (spec.separator == Separator.NONE) " " else ":"
          textSize = digitSp * 0.7f
          setTextColor(colorInt)
          this.typeface = this@FlipClockFaceView.typeface
        }
    row.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    return t
  }

  private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
  private fun sp(v: Float): Int = (v * context.resources.displayMetrics.scaledDensity).toInt()
}

/**
 * A single fixed-size flip card showing one character, flipping on its horizontal axis when
 * the digit changes (when [animate] is set). Sized by its parent; the centre hinge line — the
 * split-flap's signature — spans the fixed card.
 */
class FlipDigitView(
    context: Context,
    digitSp: Float,
    colorInt: Int,
    typeface: Typeface,
    private val animate: Boolean,
) : FrameLayout(context) {

  private var current = ' '
  private val label =
      TextView(context).apply {
        textSize = digitSp
        setTextColor(colorInt)
        this.typeface = Typeface.create(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        includeFontPadding = false
      }

  init {
    background =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF23272E.toInt(), 0xFF15181D.toInt()),
        )
            .apply { cornerRadius = 8 * resources.displayMetrics.density }
    addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    // The centre hinge line, over the digit — now spanning a fixed-width card.
    val hinge = View(context).apply { setBackgroundColor(0x55000000) }
    addView(hinge, LayoutParams(LayoutParams.MATCH_PARENT, maxOf(2, (2 * resources.displayMetrics.density).toInt()), Gravity.CENTER_VERTICAL))
    cameraDistance = resources.displayMetrics.density * 8000
  }

  fun setChar(c: Char) {
    if (c == current) return
    val first = current == ' '
    current = c
    if (!animate || first) {
      label.text = c.toString()
      return
    }
    // Flip: tilt to edge-on, swap the digit, tilt back from the far side.
    animate()
        .rotationX(90f)
        .setDuration(110)
        .withEndAction {
          label.text = c.toString()
          rotationX = -90f
          animate().rotationX(0f).setDuration(110).start()
        }
        .start()
  }
}
