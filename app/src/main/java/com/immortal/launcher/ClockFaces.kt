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
  /** True when [view] is a full-screen layer that positions itself (e.g. a WebView clock),
   *  rather than a small view placed in a grid bucket. */
  val fullBleed: Boolean
    get() = false
  /** Release resources (e.g. a WebView) when the overlay tears down. */
  fun dispose() {}
}

/** Pick the renderer for a clock spec. ANALOG / WORD fall back to digital until built. */
fun makeClockFace(context: Context, spec: ClockSpec, assets: AssetResolver): ClockFaceView =
    when (spec.mode) {
      ClockMode.NONE -> NoClockFaceView(context)
      ClockMode.FLIP -> FlipWebClockFaceView(context, spec)
      else -> DigitalClockFaceView(context, spec, assets)
    }

/** A clock that draws nothing — used when the user turns clock faces off (photos only). */
class NoClockFaceView(context: Context) : ClockFaceView {
  override val view: View = View(context).apply { visibility = View.GONE }
  override fun update(now: Date, blinkOn: Boolean) {}
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
    // NB: a blurred shadow overflows the tight text box and is clipped by the parent unless the
    // parent sets clipChildren=false (FaceRenderer does). We deliberately don't pad the view to
    // make room — that would widen the meta-row gaps and the clock→meta spacing.
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
// The flip clock is rendered by [FlipWebClockFaceView] (a transparent WebView running
// assets/faces/flip.html — a CSS/3D port of Fliqlo). An earlier native-View split-flap lived
// here; CSS handles the 3D fold and the Fliqlo glyphs far better, so it was removed.
