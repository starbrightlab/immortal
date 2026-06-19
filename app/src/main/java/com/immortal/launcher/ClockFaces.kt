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
/**
 * A split-flap flip clock, ported from mantelframe's `FlipClock.tsx`. One panel per group
 * (hour, minute, optional second), each a rounded card split top/bottom by a centre divider.
 * On a value change the old top half folds down (rotateX 0→-90, pivot bottom) while the new
 * bottom half unfolds (rotateX 90→0, pivot top) over static halves — the authentic split-flap
 * mechanic. Bebas Neue, oversized to fill the panel.
 *
 * Dimensions mirror the reference at sizeScale 100 (panel 280 / font 310, in device px since
 * the Portal browser runs at DPR 1), scaled by [ClockSpec.sizeScale].
 */
class FlipClockFaceView(
    private val context: Context,
    private val spec: ClockSpec,
    assets: AssetResolver,
) : ClockFaceView {
  private val scale = spec.sizeScale / 100f
  private val colorInt = FaceStyle.colorWithOpacity(spec.color, spec.opacity)
  private val typeface = assets.font("Bebas Neue", 400)

  // px (not dp) — the reference's CSS px map to device px on the Portal (DPR 1).
  private val panelH = 280f * scale
  private val halfH = panelH / 2f
  private val digitW = 120f * scale
  private val padX = 10f * scale
  private val radius = 24f * scale
  private val fontSize = 310f * scale
  private val panelGap = (18f * scale).toInt()
  private val dividerH = maxOf(1f, 4f * scale)
  private val offsetPx = fontSize * 0.062f // Bebas ascender nudge so caps centre on the split
  private val amPmSize = 20f * scale

  private val row =
      LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false // let the fold animation draw past panel bounds
      }

  // Fixed two-digit panel width keeps the layout stable (no width jitter on 9→10 / 12→1).
  private val panelW = digitW * 2 + padX * 2
  private val hourPanel = FlipPanel(showAmPm = spec.showAmPm).also { addPanel(it) }
  private val minPanel = FlipPanel().also { addPanel(it) }
  private val secPanel = if (spec.showSeconds) FlipPanel().also { addPanel(it) } else null

  override val view: View = row

  override fun update(now: Date, blinkOn: Boolean) {
    val h = ClockMath.hour(spec, now, pad = spec.leadingZero)
    hourPanel.set(h, amPm12(now))
    minPanel.set(ClockMath.minute(now), null)
    secPanel?.set(ClockMath.second(now), null)
  }

  /** AM/PM string for 12h, else null. */
  private fun amPm12(now: Date): String? =
      if (!spec.is24h && spec.showAmPm) ClockMath.amPm(now).uppercase() else null

  private fun addPanel(p: FlipPanel) {
    val lp = LinearLayout.LayoutParams(panelW.toInt(), panelH.toInt())
    lp.marginStart = if (row.childCount == 0) 0 else panelGap
    row.addView(p, lp)
  }

  /** A single flipping panel rendering a 1–2 char value. */
  inner class FlipPanel(private val showAmPm: Boolean = false) : FrameLayout(context) {
    private var value: String? = null
    private val staticTop = half(isTop = true)
    private val staticBottom = half(isTop = false)
    private val divider =
        View(context).apply { setBackgroundColor(0xE6000000.toInt()) }
    private val amPmLabel: TextView? =
        if (showAmPm)
            TextView(context).apply {
              textSize = pxToSp(amPmSize)
              setTextColor(colorInt)
              alpha = 0.7f
              typeface = this@FlipClockFaceView.typeface
              letterSpacing = 0.06f
            }
        else null

    init {
      clipChildren = false
      addView(staticBottom, halfLp(isTop = false))
      addView(staticTop, halfLp(isTop = true))
      addView(
          divider,
          LayoutParams(panelW.toInt(), maxOf(1, dividerH.toInt()), Gravity.TOP).apply {
            topMargin = (halfH - dividerH / 2f).toInt()
          })
      amPmLabel?.let {
        addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
      }
      // Material drop shadow for the whole card.
      outlineProvider =
          object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
              o.setRoundRect(0, 0, panelW.toInt(), panelH.toInt(), radius)
            }
          }
      elevation = 6f * scale * resources.displayMetrics.density
    }

    fun set(v: String, amPm: String?) {
      amPmLabel?.let { lbl ->
        lbl.text = amPm ?: ""
        lbl.visibility = if (amPm.isNullOrEmpty()) View.GONE else View.VISIBLE
        val m = (14f * scale).toInt()
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
      staticTop.setText(v)
      if (prev == null || prev == v || !spec.animateDigits) {
        staticBottom.setText(v)
        return
      }
      staticBottom.setText(prev) // bottom keeps the old value until the fold lands
      playFold(prev, v)
    }

    /** Fold the old top half down, then unfold the new bottom half. */
    private fun playFold(from: String, to: String) {
      val foldTop = half(isTop = true).apply { setText(from) }
      val unfoldBottom = half(isTop = false).apply { setText(to) }
      addView(foldTop, halfLp(isTop = true))
      addView(unfoldBottom, halfLp(isTop = false))
      val cam = panelH * 3.5f
      foldTop.cameraDistance = cam
      unfoldBottom.cameraDistance = cam
      foldTop.pivotX = panelW / 2f
      foldTop.pivotY = halfH // bottom edge of the top half
      unfoldBottom.pivotX = panelW / 2f
      unfoldBottom.pivotY = 0f // top edge of the bottom half
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
            staticBottom.setText(to)
            removeView(unfoldBottom)
          }
          .start()
    }

    private fun half(isTop: Boolean) = FlipHalf(isTop)

    private fun halfLp(isTop: Boolean) =
        LayoutParams(panelW.toInt(), halfH.toInt(), Gravity.TOP).apply {
          topMargin = if (isTop) 0 else halfH.toInt()
        }
  }

  /** The top or bottom half of a panel — a clipped window onto a full-height centred glyph. */
  inner class FlipHalf(isTop: Boolean) : FrameLayout(context) {
    private val label =
        TextView(context).apply {
          setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSize)
          setTextColor(colorInt)
          typeface = this@FlipClockFaceView.typeface
          gravity = Gravity.CENTER
          includeFontPadding = false
          setSingleLine()
          letterSpacing = -0.02f
          translationY = if (isTop) offsetPx else -(halfH - offsetPx)
        }

    init {
      clipChildren = true
      clipToOutline = true
      background =
          GradientDrawable(
                  GradientDrawable.Orientation.TOP_BOTTOM,
                  if (isTop) intArrayOf(0xFA262626.toInt(), 0xFA1E1E1E.toInt())
                  else intArrayOf(0xFA181818.toInt(), 0xFA121212.toInt()))
              .apply {
                cornerRadii =
                    if (isTop) floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    else floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
              }
      addView(label, LayoutParams(panelW.toInt(), panelH.toInt(), Gravity.TOP))
    }

    fun setText(s: String) {
      label.text = s
    }
  }

  private fun pxToSp(px: Float): Float = px / context.resources.displayMetrics.scaledDensity
}
