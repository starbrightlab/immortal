/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONObject

/**
 * A screensaver **face** — the overlay composition (clock + widgets) drawn over the photo
 * layer. Deliberately a 1:1 mirror of mantelframe's `WidgetConfig` JSON
 * (`src/lib/schemas/widget-config.ts`): the `clock` and `weather` blocks match it
 * field-for-field, so a Pro user's saved `widget_configs` row renders here unchanged. Two
 * additive blocks ([NowPlayingSpec], [BatterySpec]) cover what a Portal has that a browser
 * doesn't; mantelframe ignores them, and absent blocks fall back to defaults here.
 *
 * The photo source, fit,
 * interval, and transition are NOT part of a face — they live in [ScreensaverConfig].
 */
data class Face(
    val id: String,
    val name: String = "",
    val description: String = "",
    val clock: ClockSpec = ClockSpec(),
    val weather: WeatherSpec = WeatherSpec(),
    val nowPlaying: NowPlayingSpec = NowPlayingSpec(),
    val battery: BatterySpec = BatterySpec(),
    val caption: CaptionSpec = CaptionSpec(),
) {
  companion object {
    /**
     * The built-in default face, reproducing Immortal's original hardcoded overlay: a
     * sans-serif-light clock bottom-left with a "date • battery • weather" meta row beneath,
     * and the now-playing card bottom-right. Reads the user's global 24-hour and
     * show-now-playing preferences so behaviour matches the pre-face-renderer build exactly.
     */
    fun immortalClassic(context: Context): Face =
        Face(
            id = "immortal-classic",
            name = "Immortal",
            clock =
                ClockSpec(
                    mode = ClockMode.DIGITAL,
                    font = FONT_SANS_LIGHT,
                    fontWeight = 200,
                    color = "#ffffff",
                    format = if (ImmortalSettings.use24HourClock(context)) "24h" else "12h",
                    separator = Separator.COLON,
                    sizeScale = 100,
                    position = GridPosition.BOTTOM_LEFT,
                    shadow = Shadow.SOFT,
                    showDate = true,
                    dateFormat = DateFormat.SHORT,
                ),
            weather = WeatherSpec(enabled = true, position = GridPosition.BOTTOM_LEFT),
            nowPlaying =
                NowPlayingSpec(
                    enabled = ScreensaverConfig.load(context).showNowPlaying,
                    position = GridPosition.BOTTOM_RIGHT,
                ),
            battery = BatterySpec(enabled = true, position = GridPosition.BOTTOM_LEFT),
        )

    /**
     * The full-bleed flip clock (Fliqlo split-flap), honouring the user's 12/24-hour preference.
     * Full-bleed, so [FaceRenderer] suppresses every other widget and it shows clean on its own
     * near-black backdrop.
     */
    fun flip(context: Context): Face =
        Face(
            id = "flip",
            name = "Flip clock",
            clock =
                ClockSpec(
                    mode = ClockMode.FLIP,
                    format = if (ImmortalSettings.use24HourClock(context)) "24h" else "12h",
                ),
        )

    /** The overnight bedside variant of [flip] — same clock at a comfortable size, shown dimmed. */
    fun flipNight(context: Context): Face =
        flip(context).let {
          it.copy(id = "flip-night", name = "Night clock", clock = it.clock.copy(sizeScale = 90))
        }

    /** A sentinel font name the resolver maps to the system light typeface (no bundled TTF). */
    const val FONT_SANS_LIGHT = "sans-serif-light"

    /** Parse a mantelframe-shaped widget-config JSON into a [Face]. Tolerant of missing keys. */
    fun fromJson(id: String, json: JSONObject): Face {
      val clock = json.optJSONObject("clock") ?: JSONObject()
      val weather = json.optJSONObject("weather") ?: JSONObject()
      val np = json.optJSONObject("nowPlaying")
      val batt = json.optJSONObject("battery")
      val cap = json.optJSONObject("caption")
      return Face(
          id = id,
          name = json.optString("name", ""),
          description = json.optString("description", ""),
          clock = ClockSpec.fromJson(clock),
          weather = WeatherSpec.fromJson(weather),
          nowPlaying = if (np != null) NowPlayingSpec.fromJson(np) else NowPlayingSpec(),
          battery = if (batt != null) BatterySpec.fromJson(batt) else BatterySpec(),
          caption = if (cap != null) CaptionSpec.fromJson(cap) else CaptionSpec(),
      )
    }
  }
}

enum class ClockMode {
  DIGITAL,
  FLIP,
  ANALOG,
  WORD,
  NONE; // no clock drawn (faces turned off — photos only)
  companion object {
    fun fromWire(s: String?): ClockMode =
        when (s) {
          "flip" -> FLIP
          "analog" -> ANALOG
          "word" -> WORD
          "none" -> NONE
          else -> DIGITAL
        }
  }
}

/** The 9-point grid mantelframe positions widgets on. */
enum class GridPosition {
  TOP_LEFT,
  TOP_CENTER,
  TOP_RIGHT,
  MIDDLE_LEFT,
  MIDDLE_CENTER,
  MIDDLE_RIGHT,
  BOTTOM_LEFT,
  BOTTOM_CENTER,
  BOTTOM_RIGHT;
  companion object {
    fun fromWire(s: String?): GridPosition =
        when (s) {
          "top-left" -> TOP_LEFT
          "top-center" -> TOP_CENTER
          "top-right" -> TOP_RIGHT
          "middle-left" -> MIDDLE_LEFT
          "middle-center" -> MIDDLE_CENTER
          "middle-right" -> MIDDLE_RIGHT
          "bottom-left" -> BOTTOM_LEFT
          "bottom-center" -> BOTTOM_CENTER
          "bottom-right" -> BOTTOM_RIGHT
          else -> BOTTOM_RIGHT
        }
  }
}

enum class Separator {
  COLON,
  DOT,
  NONE,
  BLINK;
  companion object {
    fun fromWire(s: String?): Separator =
        when (s) {
          "dot" -> DOT
          "none" -> NONE
          "blink" -> BLINK
          else -> COLON
        }
  }
}

enum class Shadow {
  NONE,
  SOFT,
  STRONG,
  HALO,
  NEON;
  companion object {
    fun fromWire(s: String?): Shadow =
        when (s) {
          "none" -> NONE
          "strong" -> STRONG
          "halo" -> HALO
          "neon" -> NEON
          else -> SOFT
        }
  }
}

enum class DateFormat {
  SHORT,
  LONG,
  WRITTEN,
  NUMERIC_DMY,
  NUMERIC_MDY,
  ISO;

  /** The SimpleDateFormat pattern for this format. SHORT matches the original overlay. */
  fun pattern(): String =
      when (this) {
        SHORT -> "EEE, MMM d"
        LONG -> "EEEE, MMMM d"
        WRITTEN -> "EEEE, MMMM d, yyyy"
        NUMERIC_DMY -> "dd/MM/yyyy"
        NUMERIC_MDY -> "MM/dd/yyyy"
        ISO -> "yyyy-MM-dd"
      }

  companion object {
    fun fromWire(s: String?): DateFormat =
        when (s) {
          "long" -> LONG
          "written" -> WRITTEN
          "numeric-dmy" -> NUMERIC_DMY
          "numeric-mdy" -> NUMERIC_MDY
          "iso" -> ISO
          else -> SHORT
        }
  }
}

data class ClockSpec(
    val mode: ClockMode = ClockMode.DIGITAL,
    val font: String = "Plus Jakarta Sans",
    val fontWeight: Int = 200,
    val color: String = "#ffffff",
    val opacity: Float = 1f,
    val gradient: String? = null,
    val format: String = "12h",
    val showAmPm: Boolean = false,
    val showSeconds: Boolean = false,
    val leadingZero: Boolean = false,
    val separator: Separator = Separator.COLON,
    val sizeScale: Int = 100,
    val position: GridPosition = GridPosition.BOTTOM_RIGHT,
    val shadow: Shadow = Shadow.SOFT,
    val stackGap: Int = 8,
    val animateDigits: Boolean = false,
    val showDate: Boolean = false,
    val dateFormat: DateFormat = DateFormat.SHORT,
    val dateLocale: String = "en-US",
    val wordShowPrefix: Boolean = false,
    val wordDialect: String = "standard",
) {
  val is24h: Boolean
    get() = format == "24h"

  companion object {
    fun fromJson(o: JSONObject): ClockSpec =
        ClockSpec(
            mode = ClockMode.fromWire(o.optString("mode", "digital")),
            font = o.optString("font", "Plus Jakarta Sans"),
            fontWeight = o.optInt("fontWeight", 200),
            color = o.optString("color", "#ffffff"),
            opacity = o.optDouble("opacity", 1.0).toFloat(),
            gradient = if (o.isNull("gradient")) null else o.optString("gradient", null),
            format = o.optString("format", "12h"),
            showAmPm = o.optBoolean("showAmPm", false),
            showSeconds = o.optBoolean("showSeconds", false),
            leadingZero = o.optBoolean("leadingZero", false),
            separator = Separator.fromWire(o.optString("separator", "colon")),
            sizeScale = o.optInt("sizeScale", 100),
            position = GridPosition.fromWire(o.optString("position", "bottom-right")),
            shadow = Shadow.fromWire(o.optString("shadow", "soft")),
            stackGap = o.optInt("stackGap", 8),
            animateDigits = o.optBoolean("animateDigits", false),
            showDate = o.optBoolean("showDate", false),
            dateFormat = DateFormat.fromWire(o.optString("dateFormat", "short")),
            dateLocale = o.optString("dateLocale", "en-US"),
            wordShowPrefix = o.optBoolean("wordShowPrefix", false),
            wordDialect = o.optString("wordDialect", "standard"),
        )
  }
}

data class WeatherSpec(
    val enabled: Boolean = false,
    val unit: String = "C",
    val position: GridPosition = GridPosition.BOTTOM_LEFT,
    val showCondition: Boolean = true,
    val showHighLow: Boolean = false,
    val sizeScale: Int = 100,
) {
  companion object {
    fun fromJson(o: JSONObject): WeatherSpec =
        WeatherSpec(
            enabled = o.optBoolean("enabled", false),
            unit = o.optString("unit", "C"),
            position = GridPosition.fromWire(o.optString("position", "bottom-left")),
            showCondition = o.optBoolean("showCondition", true),
            showHighLow = o.optBoolean("showHighLow", false),
            sizeScale = o.optInt("sizeScale", 100),
        )
  }
}

/** Immortal extension: the now-playing card (album art + track/artist). */
data class NowPlayingSpec(
    val enabled: Boolean = true,
    val position: GridPosition = GridPosition.BOTTOM_RIGHT,
    val showArt: Boolean = true,
    val sizeScale: Int = 100,
) {
  companion object {
    fun fromJson(o: JSONObject): NowPlayingSpec =
        NowPlayingSpec(
            enabled = o.optBoolean("enabled", true),
            position = GridPosition.fromWire(o.optString("position", "bottom-right")),
            showArt = o.optBoolean("showArt", true),
            sizeScale = o.optInt("sizeScale", 100),
        )
  }
}

/** Immortal extension: battery percent (self-hides on mains-only Portals). */
data class BatterySpec(
    val enabled: Boolean = true,
    val position: GridPosition = GridPosition.BOTTOM_LEFT,
) {
  companion object {
    fun fromJson(o: JSONObject): BatterySpec =
        BatterySpec(
            enabled = o.optBoolean("enabled", true),
            position = GridPosition.fromWire(o.optString("position", "bottom-left")),
        )
  }
}

/**
 * Immortal extension: the "place · date" photo caption (own-photos only; see [PhotoCaption]).
 * A grid element like the rest, so it stacks with whatever shares its cell — notably the
 * now-playing card, which also defaults to bottom-right — instead of overlapping it.
 */
data class CaptionSpec(
    val enabled: Boolean = true,
    val position: GridPosition = GridPosition.BOTTOM_RIGHT,
) {
  companion object {
    fun fromJson(o: JSONObject): CaptionSpec =
        CaptionSpec(
            enabled = o.optBoolean("enabled", true),
            position = GridPosition.fromWire(o.optString("position", "bottom-right")),
        )
  }
}
