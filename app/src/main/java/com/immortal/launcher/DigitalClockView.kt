/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

/**
 * Shared builder for the digital clock UI. Used by both the
 * [DigitalClockDreamService] (the actual screensaver) and the
 * [DigitalClockPreviewActivity] (in-app preview). Returns a root view
 * plus a [Controller] that the caller uses to update the time.
 *
 * The caller is responsible for the update loop: call [Controller.update]
 * periodically to refresh the clock text and (for analog) the hands.
 */
object DigitalClockView {

  data class Controller(
      val root: View,
      val clockText: TextView?,
      val dateText: TextView?,
      val flipHour: TextView?,
      val flipMinute: TextView?,
      val flipSecond: TextView?,
      val analog: AnalogClockView?,
  )

  fun build(context: Context, settings: DigitalClockConfig.Settings): Controller {
    val root = FrameLayout(context)
    root.setBackgroundColor(backgroundColor(settings))

    if (settings.background == DigitalClockConfig.BG_GRADIENT) {
      val gradient = android.graphics.drawable.GradientDrawable(
          android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
          intArrayOf(Color.parseColor("#FF1A1A2E"), Color.parseColor("#FF16213E"), Color.BLACK)
      )
      root.background = gradient
    }

    val textColor = clockColor(settings)
    val sizeSp = clockSizeSp(settings)
    val analog = AnalogClockView(context).apply {
      setColor(textColor)
      setSize(sizeSp)
      setShowSeconds(settings.showSeconds)
    }

    // Analog style: single custom view centered, optional date at bottom.
    if (settings.style == DigitalClockConfig.STYLE_ANALOG) {
      // Size to ~55% of the smaller screen dimension so it fits comfortably
      // on any Portal model (Portal+, TV, Go, Mini) with margin for the date.
      val dm = context.resources.displayMetrics
      val screenMin = kotlin.math.min(dm.widthPixels, dm.heightPixels)
      val analogSize = (screenMin * 0.55f).toInt()
      root.addView(analog, FrameLayout.LayoutParams(analogSize, analogSize, Gravity.CENTER))
      val dateView = if (settings.showDate) buildDateView(context, settings, textColor).also {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lp.bottomMargin = 80
        root.addView(it, lp)
      } else null
      return Controller(root, null, dateView, null, null, null, analog)
    }

    val container = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
    }

    val clockText: TextView?
    val flipHour: TextView?
    val flipMinute: TextView?
    val flipSecond: TextView?
    if (settings.style == DigitalClockConfig.STYLE_FLIP) {
      val h = flipHourView(context, settings, textColor)
      val m = flipMinuteView(context, settings, textColor)
      val s = if (settings.showSeconds) flipSecondView(context, settings, textColor) else null
      val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
      }
      row.addView(h)
      row.addView(flipSeparator(context, settings, textColor))
      row.addView(m)
      if (s != null) {
        row.addView(flipSeparator(context, settings, textColor))
        row.addView(s)
      }
      container.addView(row)
      clockText = null
      flipHour = h
      flipMinute = m
      flipSecond = s
    } else {
      val text = TextView(context).apply {
        textSize = sizeSp
        setTextColor(textColor)
        typeface = clockTypefaceForStyle(context, settings)
        gravity = Gravity.CENTER
        applyClockStyleEffects(context, this, settings, textColor)
      }
      container.addView(text)
      clockText = text
      flipHour = null
      flipMinute = null
      flipSecond = null
    }

    val dateText: TextView? = if (settings.showDate) {
      val v = buildDateView(context, settings, textColor)
      container.addView(v)
      v
    } else null

    val containerGravity = when (settings.layout) {
      DigitalClockConfig.LAYOUT_TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
      DigitalClockConfig.LAYOUT_BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
      else -> Gravity.CENTER
    }
    val lp = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        containerGravity
    )
    if (settings.layout == DigitalClockConfig.LAYOUT_TOP) lp.topMargin = 120
    else if (settings.layout == DigitalClockConfig.LAYOUT_BOTTOM) lp.bottomMargin = 120
    root.addView(container, lp)

    clockText?.textSize = sizeSp

    return Controller(root, clockText, dateText, flipHour, flipMinute, flipSecond, analog)
  }

  fun update(controller: Controller, settings: DigitalClockConfig.Settings) {
    val cal = Calendar.getInstance()
    val use24Hour = ImmortalSettings.use24HourClock(controller.root.context)
    val now = java.util.Date()

    controller.analog?.update()
    controller.clockText?.let {
      val pattern = when {
        settings.showSeconds -> if (use24Hour) "H:mm:ss" else "h:mm:ss"
        use24Hour -> "H:mm"
        else -> "h:mm"
      }
      it.text = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(now)
    }
    if (controller.flipHour != null) {
      val h = if (use24Hour) cal.get(Calendar.HOUR_OF_DAY) else cal.get(Calendar.HOUR)
      val hour12 = if (h == 0) 12 else if (h > 12) h - 12 else h
      val displayHour = if (use24Hour) h else hour12
      controller.flipHour.text = displayHour.toString().padStart(2, '0')
      controller.flipMinute?.text = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
      controller.flipSecond?.text = cal.get(Calendar.SECOND).toString().padStart(2, '0')
    }
    controller.dateText?.text = DateFormatter.format(now, "EEEEMMMMd")
  }

  private fun buildDateView(context: Context, settings: DigitalClockConfig.Settings, color: Int): TextView {
    val v = TextView(context)
    v.textSize = dateSizeSp(settings)
    v.setTextColor(color)
    v.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    v.gravity = Gravity.CENTER
    v.alpha = 0.8f
    applyGlow(v, settings, color, 6f, 2f)
    return v
  }

  private fun flipHourView(context: Context, settings: DigitalClockConfig.Settings, color: Int) = makeFlipCard(context, settings, color)
  private fun flipMinuteView(context: Context, settings: DigitalClockConfig.Settings, color: Int) = makeFlipCard(context, settings, color)
  private fun flipSecondView(context: Context, settings: DigitalClockConfig.Settings, color: Int) = makeFlipCard(context, settings, color)
  private fun makeFlipCard(context: Context, settings: DigitalClockConfig.Settings, color: Int): TextView {
    return TextView(context).apply {
      textSize = clockSizeSp(settings) * 0.6f
      setTextColor(color)
      gravity = Gravity.CENTER
      setPadding(24, 12, 24, 12)
    }
  }
  private fun flipSeparator(context: Context, settings: DigitalClockConfig.Settings, color: Int): TextView {
    return TextView(context).apply {
      text = ":"
      textSize = clockSizeSp(settings) * 0.5f
      setTextColor(color)
      gravity = Gravity.CENTER
    }
  }

  private fun applyClockStyleEffects(context: Context, view: TextView, settings: DigitalClockConfig.Settings, color: Int) {
    when (settings.style) {
      DigitalClockConfig.STYLE_BOLD -> {
        view.setTypeface(Typeface.create(clockTypefaceForStyle(context, settings), Typeface.BOLD))
        view.setShadowLayer(8f, 2f, 2f, color and 0x00FFFFFF or 0xAA000000.toInt())
      }
      DigitalClockConfig.STYLE_NEON -> {
        view.setShadowLayer(30f, 0f, 0f, color and 0x00FFFFFF or 0xCC000000.toInt())
        view.setShadowLayer(12f, 0f, 0f, color)
      }
      DigitalClockConfig.STYLE_SEGMENT -> {
        view.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        view.letterSpacing = 0.15f
        view.setShadowLayer(8f, 0f, 2f, color and 0x00FFFFFF or 0xAA000000.toInt())
      }
      else -> applyGlow(view, settings, color)
    }
  }

  private fun applyGlow(view: TextView, settings: DigitalClockConfig.Settings, baseColor: Int, radius: Float = 12f, dy: Float = 4f) {
    when (settings.glow) {
      DigitalClockConfig.GLOW_NONE -> view.setShadowLayer(0f, 0f, 0f, 0)
      DigitalClockConfig.GLOW_SOFT -> {
        val softColor = (baseColor and 0x00FFFFFF) or 0x66000000.toInt()
        view.setShadowLayer(radius, 0f, dy, softColor)
      }
      DigitalClockConfig.GLOW_STRONG -> {
        val wideColor = (baseColor and 0x00FFFFFF) or 0x99000000.toInt()
        view.setShadowLayer(radius * 2, 0f, 0f, wideColor)
        view.setShadowLayer(radius * 1.4f, 0f, dy * 0.5f, baseColor)
      }
    }
  }

  private fun backgroundColor(settings: DigitalClockConfig.Settings): Int = when (settings.background) {
    DigitalClockConfig.BG_BLACK -> Color.BLACK
    DigitalClockConfig.BG_RED -> Color.parseColor("#FF1A0500")
    else -> Color.BLACK
  }

  private fun clockSizeSp(settings: DigitalClockConfig.Settings): Float = when (settings.size) {
    DigitalClockConfig.SIZE_SMALL -> 120f
    DigitalClockConfig.SIZE_MEDIUM -> 180f
    DigitalClockConfig.SIZE_LARGE -> 240f
    DigitalClockConfig.SIZE_XL -> 320f
    else -> 240f
  }

  private fun dateSizeSp(settings: DigitalClockConfig.Settings): Float = when (settings.size) {
    DigitalClockConfig.SIZE_SMALL -> 24f
    DigitalClockConfig.SIZE_MEDIUM -> 32f
    DigitalClockConfig.SIZE_LARGE -> 40f
    DigitalClockConfig.SIZE_XL -> 52f
    else -> 40f
  }

  private fun clockColor(settings: DigitalClockConfig.Settings): Int = when (settings.color) {
    DigitalClockConfig.COLOR_RED -> Color.parseColor("#FFF44336")
    DigitalClockConfig.COLOR_GREEN -> Color.parseColor("#FF4CAF50")
    DigitalClockConfig.COLOR_BLUE -> Color.parseColor("#FF2196F3")
    DigitalClockConfig.COLOR_YELLOW -> Color.parseColor("#FFFFEB3B")
    DigitalClockConfig.COLOR_CYAN -> Color.parseColor("#FF00BCD4")
    DigitalClockConfig.COLOR_PINK -> Color.parseColor("#FFE91E63")
    DigitalClockConfig.COLOR_ORANGE -> Color.parseColor("#FFFF9800")
    else -> Color.WHITE
  }

  private fun clockTypeface(context: Context, settings: DigitalClockConfig.Settings): Typeface = when (settings.font) {
    // Use the guaranteed-distinct Typeface constants so every font option
    // actually looks different (previous versions used family-name strings
    // that silently fell back to the same default on Android 9).
    DigitalClockConfig.FONT_LIGHT -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
        .let { if (it == Typeface.DEFAULT) Typeface.SANS_SERIF else it }
    DigitalClockConfig.FONT_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    DigitalClockConfig.FONT_MONO -> Typeface.MONOSPACE
    DigitalClockConfig.FONT_SERIF -> Typeface.SERIF
    // Bundled custom fonts — drop the .ttf into app/src/main/assets/fonts/
    DigitalClockConfig.FONT_SEGMENT_LED -> loadBundledFont(context, "fonts/segment_led.ttf")
    DigitalClockConfig.FONT_DIGITAL_7 -> loadBundledFont(context, "fonts/digital_7.ttf")
    DigitalClockConfig.FONT_TECHNOLOGY -> loadBundledFont(context, "fonts/technology.ttf")
    else -> Typeface.SANS_SERIF
  }

  /** Cached bundled fonts keyed by asset path so we only load each one once. */
  private val bundledFontCache = mutableMapOf<String, Typeface>()

  /**
   * Load a bundled .ttf font from the assets/fonts/ directory. Returns a
   * sensible fallback if the file is missing, so a font option silently
   * degrades to the default instead of crashing.
   */
  private fun loadBundledFont(context: Context, assetPath: String): Typeface {
    bundledFontCache[assetPath]?.let { return it }
    val tf = runCatching {
      Typeface.createFromAsset(context.assets, assetPath)
    }.getOrNull() ?: return Typeface.SANS_SERIF
    bundledFontCache[assetPath] = tf
    return tf
  }

  private fun clockTypefaceForStyle(context: Context, settings: DigitalClockConfig.Settings): Typeface = when (settings.style) {
    DigitalClockConfig.STYLE_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    DigitalClockConfig.STYLE_SEGMENT -> Typeface.MONOSPACE
    DigitalClockConfig.STYLE_NEON -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
        .let { if (it == Typeface.DEFAULT) Typeface.SANS_SERIF else it }
    DigitalClockConfig.STYLE_FLIP -> Typeface.MONOSPACE
    else -> clockTypeface(context, settings)
  }
}

/**
 * High-quality analog clock face drawn with Canvas. Hour, minute, and
 * optional second hands rotate to show the current time.
 *
 * Supports the full color palette: the face, markers, numbers, and hands
 * all tint with the selected clock color, with the second hand in a
 * contrasting red for legibility. Hour and minute hands use a slight
 * taper (drawn as two segments) for a more refined look.
 *
 * Pure drawable view — no Compose dependency.
 */
class AnalogClockView(context: android.content.Context) : View(context) {
  private var hour = 0
  private var minute = 0
  private var second = 0
  private var showSeconds = false
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private var clockColor = Color.WHITE
  // Size preference: small=0.10, medium=0.13, large=0.16, xl=0.20 (fraction of view width).
  // Numbers scale with the view size while still respecting the user's chosen size setting.
  private var sizeFraction = 0.13f

  fun setColor(c: Int) { clockColor = c; invalidate() }
  fun setSize(sp: Float) { /* kept for API compat; size is now derived from the view */ }
  fun setSizeFraction(f: Float) { sizeFraction = f; invalidate() }
  fun setShowSeconds(s: Boolean) { showSeconds = s; invalidate() }

  init {
    update()
  }

  fun update() {
    val cal = Calendar.getInstance()
    hour = cal.get(Calendar.HOUR)
    minute = cal.get(Calendar.MINUTE)
    second = cal.get(Calendar.SECOND)
    invalidate()
  }

  override fun onDraw(canvas: android.graphics.Canvas) {
    super.onDraw(canvas)
    val w = width.toFloat()
    val h = height.toFloat()
    val cx = w / 2f
    val cy = h / 2f
    val radius = kotlin.math.min(w, h) / 2f * 0.88f

    // Base unit: ~1.2% of radius. All stroke widths and text sizes scale
    // proportionally so the clock looks balanced at any physical size.
    val unit = radius * 0.012f

    // Subtle outer ring for depth (very faint, same hue as the clock)
    paint.color = clockColor and 0x00FFFFFF or 0x33000000.toInt()
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = unit * 0.5f
    canvas.drawCircle(cx, cy, radius + unit * 2, paint)

    // Main face circle
    paint.color = clockColor
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = unit * 1.2f
    canvas.drawCircle(cx, cy, radius, paint)

    // Hour markers — longer and slightly thicker at 12/3/6/9 (cardinal points)
    paint.strokeCap = Paint.Cap.ROUND
    for (i in 0 until 12) {
      val isCardinal = (i % 3) == 0
      val angle = (i * 30 - 90) * Math.PI / 180.0
      val outerR = radius - unit * 2
      val innerR =
          if (isCardinal) radius - unit * 10
          else radius - unit * 5
      paint.strokeWidth = if (isCardinal) unit * 2.5f else unit * 1.5f
      val outerX = cx + (outerR * kotlin.math.cos(angle)).toFloat()
      val outerY = cy + (outerR * kotlin.math.sin(angle)).toFloat()
      val innerX = cx + (innerR * kotlin.math.cos(angle)).toFloat()
      val innerY = cy + (innerR * kotlin.math.sin(angle)).toFloat()
      canvas.drawLine(innerX, innerY, outerX, outerY, paint)
    }

    // Hour numbers — moved further inward (radius * 0.68f) so they don't
    // collide with the hour markers. Cardinals are 1.2x the base size,
    // non-cardinals are 0.85x. All sized in absolute pixels relative to
    // the view radius, not a fraction of an arbitrary "size fraction".
    paint.style = Paint.Style.FILL
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    val numbers = listOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    for ((i, n) in numbers.withIndex()) {
      val isCardinal = (n % 3) == 0
      val angle = (i * 30 - 90) * Math.PI / 180.0
      val r = radius * 0.68f
      paint.textSize = unit * (if (isCardinal) 5.0f else 3.6f)
      if (isCardinal) {
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
      } else {
        paint.typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
      }
      val fontMetrics = paint.fontMetrics
      val textOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f
      val x = cx + (r * kotlin.math.cos(angle)).toFloat()
      val y = cy + (r * kotlin.math.sin(angle)).toFloat() + textOffset
      canvas.drawText(n.toString(), x, y, paint)
    }

    // Hour hand — shorter, slightly thicker
    val hourAngle = ((hour + minute / 60f) * 30 - 90) * Math.PI / 180.0
    paint.color = clockColor
    paint.strokeWidth = unit * 3.5f
    paint.strokeCap = Paint.Cap.ROUND
    canvas.drawLine(
        cx,
        cy,
        cx + (radius * 0.50f * kotlin.math.cos(hourAngle)).toFloat(),
        cy + (radius * 0.50f * kotlin.math.sin(hourAngle)).toFloat(),
        paint
    )

    // Minute hand — longer, thinner
    val minuteAngle = (minute * 6 - 90) * Math.PI / 180.0
    paint.strokeWidth = unit * 2.2f
    paint.strokeCap = Paint.Cap.ROUND
    canvas.drawLine(
        cx,
        cy,
        cx + (radius * 0.78f * kotlin.math.cos(minuteAngle)).toFloat(),
        cy + (radius * 0.78f * kotlin.math.sin(minuteAngle)).toFloat(),
        paint
    )

    // Second hand (optional) — properly centered: drawn as a single line
    // from a point behind the center to the tip, passing through center.
    if (showSeconds) {
      val secondAngle = (second * 6 - 90) * Math.PI / 180.0
      // Use red for the second hand for classic contrast
      paint.color = Color.parseColor("#FFE53935")
      paint.strokeWidth = unit * 0.7f
      paint.strokeCap = Paint.Cap.ROUND
      val tailLen = radius * 0.12f
      val tipLen = radius * 0.85f
      // Draw from -tailLen behind center through center to +tipLen
      val startX = cx + (tailLen * kotlin.math.cos(secondAngle + Math.PI)).toFloat()
      val startY = cy + (tailLen * kotlin.math.sin(secondAngle + Math.PI)).toFloat()
      val endX = cx + (tipLen * kotlin.math.cos(secondAngle)).toFloat()
      val endY = cy + (tipLen * kotlin.math.sin(secondAngle)).toFloat()
      canvas.drawLine(startX, startY, endX, endY, paint)
      paint.color = clockColor
    }

    // Center dot — a filled circle with a small inner ring for detail
    paint.color = clockColor
    paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, unit * 4, paint)
    // Inner cutout (use the background color — black for the default theme)
    fillPaint.color = Color.BLACK
    fillPaint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, unit * 1.8f, fillPaint)
  }
}
