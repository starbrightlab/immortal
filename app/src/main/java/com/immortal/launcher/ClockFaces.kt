/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
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

// ─── Flip (Fliqlo) ───────────────────────────────────────────────────────────

/**
 * A flip clock reproducing the Fliqlo screensaver — the canonical reference. One panel per
 * group (hour / minute / optional second): a near-black rounded card with a light-grey digit
 * centred on it. The digit font (Fliqlo.otf) bakes the centre split into each glyph, so the
 * resting card is correct by construction — no manual half-offset. On a value change the old
 * top half folds down (rotateX 0→-90, pivot bottom) while the new bottom half unfolds
 * (90→0, pivot top) over static halves, the authentic split-flap mechanic.
 *
 * Spec sampled from the Fliqlo bundle: digit #B9B9B9, card #0E0E0E, monospaced advance 0.45·S,
 * the hour card sized to its digit count (single-digit hours get a narrower card). Sizes are in
 * device px (DPR 1 on the Portal), scaled by [ClockSpec.sizeScale].
 *
 * NOTE: Fliqlo.otf is proprietary (Yuji Adachi). Bundled here to match the spec during
 * development; the redistribution licence must be cleared (or the glyphs reproduced in a
 * cleared font) before this ships. See [[quality-bar-screensaver]].
 */
class FlipClockFaceView(
    private val context: Context,
    private val spec: ClockSpec,
    assets: AssetResolver,
) : ClockFaceView {
  private val scale = spec.sizeScale / 100f

  // The Fliqlo digit font (split baked into the glyph). Falls back to a bold face if absent.
  private val digitFont =
      runCatching { Typeface.createFromAsset(context.assets, "fonts/Fliqlo.otf") }
          .getOrDefault(Typeface.DEFAULT_BOLD)

  // Fliqlo is grey; honour an explicit non-default colour from the face.
  private val digitColor =
      if (spec.color.equals("#ffffff", true)) 0xFFB9B9B9.toInt()
      else FaceStyle.colorWithOpacity(spec.color, spec.opacity)

  // Geometry, all px, derived from the font size S (DPR 1 on the Portal).
  private val s = 300f * scale // digit font size
  private val cardH = 0.86f * s
  private val cardHalf = cardH / 2f
  private val advance = 0.45f * s // monospaced digit advance
  private val sidePad = 0.11f * s
  private val corner = 0.06f * s
  private val gap = (0.10f * s).toInt()
  private val dividerH = maxOf(2f, 0.012f * s)
  // Digits sit slightly above the line-box centre (ascent≠descent); nudge down so the baked
  // split lands on the card's centre divider. Tuned against the Fliqlo thumbnail.
  private val digitDy = 0.10f * s
  private val amPmSize = 0.085f * s

  private val row =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
      }

  private val hourPanel = FlipPanel(showAmPm = spec.showAmPm).also { addPanel(it) }
  private val minPanel = FlipPanel().also { addPanel(it) }
  private val secPanel = if (spec.showSeconds) FlipPanel().also { addPanel(it) } else null

  override val view: View = row

  override fun update(now: Date, blinkOn: Boolean) {
    hourPanel.set(ClockMath.hour(spec, now, pad = spec.leadingZero), amPm12(now))
    minPanel.set(ClockMath.minute(now), null)
    secPanel?.set(ClockMath.second(now), null)
  }

  private fun amPm12(now: Date): String? =
      if (!spec.is24h && spec.showAmPm) ClockMath.amPm(now).uppercase() else null

  private fun cardW(nDigits: Int): Int = (advance * nDigits + 2 * sidePad).toInt()

  private fun addPanel(p: FlipPanel) {
    val lp = LinearLayout.LayoutParams(cardW(2), cardH.toInt())
    lp.marginStart = if (row.childCount == 0) 0 else gap
    row.addView(p, lp)
  }

  /** A single flipping panel rendering a 1–2 char value on one card. */
  inner class FlipPanel(private val showAmPm: Boolean = false) : FrameLayout(context) {
    private var value: String? = null
    private val resting = digitView()
    private val divider =
        View(context).apply { setBackgroundColor(0xFF000000.toInt()) }
    private val amPmLabel: TextView? =
        if (showAmPm)
            TextView(context).apply {
              setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, amPmSize)
              setTextColor(digitColor)
              typeface = digitFont
              letterSpacing = 0.04f
            }
        else null

    init {
      clipChildren = false
      background =
          GradientDrawable().apply {
            setColor(0xFF0E0E0E.toInt())
            cornerRadius = corner
          }
      clipToOutline = true
      addView(resting, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      addView(
          divider,
          LayoutParams(LayoutParams.MATCH_PARENT, maxOf(1, dividerH.toInt()), Gravity.TOP).apply {
            topMargin = (cardHalf - dividerH / 2f).toInt()
          })
      amPmLabel?.let {
        addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
      }
      elevation = 4f * scale * resources.displayMetrics.density
    }

    fun set(v: String, amPm: String?) {
      resizeTo(v.length)
      amPmLabel?.let { lbl ->
        lbl.text = amPm ?: ""
        lbl.visibility = if (amPm.isNullOrEmpty()) View.GONE else View.VISIBLE
        val m = (0.05f * s).toInt()
        (lbl.layoutParams as LayoutParams).apply {
          gravity = Gravity.START or (if (amPm == "AM") Gravity.TOP else Gravity.BOTTOM)
          leftMargin = m
          topMargin = m
          bottomMargin = m
        }
        lbl.requestLayout()
      }
      val prev = value
      value = v
      resting.text = v
      if (prev == null || prev == v || !spec.animateDigits) return
      playFold(prev, v)
    }

    /** Resize the card to fit [n] digits (single-digit hours get a narrower card, like Fliqlo). */
    private fun resizeTo(n: Int) {
      val w = cardW(n)
      (layoutParams as? LinearLayout.LayoutParams)?.let {
        if (it.width != w) {
          it.width = w
          requestLayout()
        }
      }
    }

    private fun playFold(from: String, to: String) {
      // Static halves under the leaves: top already shows NEW (resting); cover the bottom with
      // OLD until the new bottom unfolds.
      val backBottom = halfView(isTop = false).apply { setText(from) }
      val foldTop = halfView(isTop = true).apply { setText(from) }
      val unfoldBottom = halfView(isTop = false).apply { setText(to) }
      addView(backBottom)
      addView(foldTop)
      addView(unfoldBottom)
      val cam = cardH * 3.5f
      foldTop.cameraDistance = cam
      unfoldBottom.cameraDistance = cam
      foldTop.pivotX = width / 2f
      foldTop.pivotY = cardHalf
      unfoldBottom.pivotX = width / 2f
      unfoldBottom.pivotY = 0f
      unfoldBottom.rotationX = 90f

      val phase = 300L
      foldTop
          .animate()
          .rotationX(-90f)
          .setDuration(phase)
          .setInterpolator(android.view.animation.AccelerateInterpolator())
          .withEndAction { removeView(foldTop) }
          .start()
      unfoldBottom
          .animate()
          .rotationX(0f)
          .setStartDelay(phase)
          .setDuration(phase)
          .setInterpolator(android.view.animation.DecelerateInterpolator())
          .withEndAction {
            removeView(unfoldBottom)
            removeView(backBottom)
          }
          .start()
    }

    /** A resting full-card digit, centred with the split landing on the card divider. */
    private fun digitView(): TextView =
        TextView(context).apply {
          setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, s)
          setTextColor(digitColor)
          typeface = digitFont
          gravity = Gravity.CENTER
          includeFontPadding = false
          setSingleLine()
          translationY = digitDy
        }

    /** A clipped top/bottom half of the card, showing the centred digit, for the fold. */
    private fun halfView(isTop: Boolean): FlipHalf = FlipHalf(isTop)
  }

  /** Top or bottom half of a card: a clip window onto a full-card centred digit. */
  inner class FlipHalf(isTop: Boolean) : FrameLayout(context) {
    private val label = digitLabel()

    init {
      clipChildren = true
      clipToOutline = true
      background =
          GradientDrawable().apply {
            setColor(0xFF0E0E0E.toInt())
            cornerRadii =
                if (isTop) floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f)
                else floatArrayOf(0f, 0f, 0f, 0f, corner, corner, corner, corner)
          }
      addView(label, LayoutParams(LayoutParams.MATCH_PARENT, cardH.toInt(), Gravity.TOP))
      // The half is half-card tall; offset the full-card label so the correct half shows.
      label.translationY = digitDy + (if (isTop) 0f else -cardHalf)
      // Position handled by FrameLayout add below.
      layoutParams =
          FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, cardHalf.toInt(), Gravity.TOP)
              .apply { topMargin = if (isTop) 0 else cardHalf.toInt() }
    }

    private fun digitLabel() =
        TextView(context).apply {
          setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, s)
          setTextColor(digitColor)
          typeface = digitFont
          gravity = Gravity.CENTER
          includeFontPadding = false
          setSingleLine()
        }

    fun setText(t: String) {
      label.text = t
    }
  }
}
