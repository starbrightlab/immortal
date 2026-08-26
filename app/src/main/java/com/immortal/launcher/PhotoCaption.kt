/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.exifinterface.media.ExifInterface
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Photo metadata for the screensaver caption — the "taken at <place> · <date>" block,
 * tvOS-style — plus the formatting of every line the frame draws ([Caption]).
 *
 * Two things can fill it in:
 *  - **EXIF**, for the user's *own* photo files: the local folder and SMB (NAS) sources, which
 *    decode real images. That yields the capture date and (reverse-geocoded) place.
 *  - **Immich**, which is asked for each photo's stored detail directly
 *    ([ImmichSource.details]) — date, description, place, people and tags. Its previews are
 *    re-encoded with EXIF stripped, so the server is the only thing that knows them.
 *
 * The remaining web/CDN sources (Picsum/Unsplash, iCloud/Google shared albums, WebDAV) serve
 * stripped images with no companion API, so there's nothing to show and the caption stays hidden.
 *
 * Everything is best-effort: a missing date, absent GPS, or a failed lookup just means
 * less (or no) caption — never a crash and never a blocked slideshow.
 */
object PhotoCaption {

  /**
   * The five things a caption can say about a photo. This is the identity the whole feature is
   * keyed on: the user's chosen [order], the per-line [LineStyle], the settings toggles, and the
   * icon each line draws all hang off it, so adding a sixth line is one entry here plus a
   * drawable.
   *
   * The defaults reproduce the original two-line caption exactly — a bold place over a lighter
   * date — with the Immich-only lines tucked underneath, each a step smaller.
   */
  enum class Line(
      /** Stable key used in prefs and on the wire. Never rename — it's persisted. */
      val key: String,
      /** Label for the settings screen. */
      val label: String,
      /** The glyph drawn ahead of the text when icons are on. */
      val iconRes: Int,
      val defaultSize: Int,
      val defaultWeight: Weight,
  ) {
    LOCATION("location", "Location", R.drawable.ic_caption_location, 95, Weight.BOLD),
    DATE("date", "Photo date", R.drawable.ic_caption_date, 70, Weight.LIGHT),
    DESCRIPTION("description", "Description", R.drawable.ic_caption_description, 75, Weight.LIGHT),
    PEOPLE("people", "People", R.drawable.ic_caption_people, 70, Weight.LIGHT),
    TAGS("tags", "Tags", R.drawable.ic_caption_tags, 65, Weight.LIGHT);

    val defaultStyle: LineStyle
      get() = LineStyle(defaultSize, defaultWeight)

    companion object {
      fun fromKey(key: String?): Line? = entries.firstOrNull { it.key == key?.trim() }
    }
  }

  /**
   * How heavy a line's type is. Rendered from the *face's own font* (see
   * [FaceStyle.typeface]) rather than a face of its own, so the caption reads as part of the same
   * overlay as the clock, date and weather: [LIGHT] takes the face's light variant, [BOLD] bolds
   * the face's regular one.
   */
  enum class Weight(val key: String, val label: String) {
    LIGHT("light", "Light"),
    REGULAR("regular", "Regular"),
    BOLD("bold", "Bold");

    companion object {
      fun fromKey(key: String?): Weight? = entries.firstOrNull { it.key == key?.trim() }
    }
  }

  /** How one line is drawn: [size] as a percentage of the caption base size, plus its [weight]. */
  data class LineStyle(val size: Int, val weight: Weight) {
    companion object {
      const val MIN_SIZE = 50
      const val MAX_SIZE = 200
      const val SIZE_STEP = 5
    }
  }

  /** Keep a line size inside the range the settings stepper offers. */
  fun clampSize(size: Int): Int = size.coerceIn(LineStyle.MIN_SIZE, LineStyle.MAX_SIZE)

  /**
   * One photo's caption: the text for each line that has something to say, in no particular
   * order — the *drawing* order is the user's ([parseOrder]), not this map's.
   */
  data class Caption(val lines: Map<Line, String>) {
    val isEmpty: Boolean
      get() = lines.isEmpty()

    operator fun get(line: Line): String? = lines[line]

    companion object {
      val EMPTY = Caption(emptyMap())

      /**
       * Build a caption from whatever the source could supply. Blank and null values are dropped
       * here, once, so neither the controller nor the renderer has to keep re-checking them.
       */
      fun of(vararg values: Pair<Line, String?>): Caption =
          Caption(
              values
                  .mapNotNull { (line, v) -> v?.trim()?.ifBlank { null }?.let { line to it } }
                  .toMap())
    }
  }

  // --- order and per-line style (persisted as compact strings) -----------------
  // Both are stored as one string each rather than ten scalar prefs: they're a single editable
  // unit owned by one screen (CaptionStyleActivity), the way the photo-source credentials are.
  // Parsing is total — an unknown key, a missing line or outright garbage degrades to the
  // defaults rather than losing the caption, because a bad string must never blank the frame.

  /** The default top-to-bottom order, as declared in [Line]. */
  val DEFAULT_ORDER: List<Line> = Line.entries.toList()

  /** `"location,date,…"` → lines, de-duplicated, with anything missing appended in default order. */
  fun parseOrder(raw: String?): List<Line> {
    val named = raw.orEmpty().split(',').mapNotNull { Line.fromKey(it) }.distinct()
    return named + DEFAULT_ORDER.filterNot { it in named }
  }

  fun serializeOrder(order: List<Line>): String = order.joinToString(",") { it.key }

  /** `"location:95:bold,date:70:light,…"` → per-line style, falling back to each line's default. */
  fun parseStyles(raw: String?): Map<Line, LineStyle> {
    val out = LinkedHashMap<Line, LineStyle>()
    Line.entries.forEach { out[it] = it.defaultStyle }
    raw.orEmpty().split(',').forEach { part ->
      val bits = part.split(':')
      val line = Line.fromKey(bits.getOrNull(0)) ?: return@forEach
      val size = bits.getOrNull(1)?.trim()?.toIntOrNull()?.let(::clampSize) ?: line.defaultSize
      val weight = Weight.fromKey(bits.getOrNull(2)) ?: line.defaultWeight
      out[line] = LineStyle(size, weight)
    }
    return out
  }

  fun serializeStyles(styles: Map<Line, LineStyle>): String =
      Line.entries.joinToString(",") { line ->
        val st = styles[line] ?: line.defaultStyle
        "${line.key}:${st.size}:${st.weight.key}"
      }

  /** Capture date (epoch millis) and GPS coordinates pulled from a photo's EXIF block. */
  data class Meta(val dateMillis: Long?, val lat: Double?, val lng: Double?) {
    val hasLocation: Boolean
      get() = lat != null && lng != null

    val isEmpty: Boolean
      get() = dateMillis == null && !hasLocation
  }

  /** Pull capture date + GPS from an already-opened [ExifInterface]. Best-effort. */
  fun read(exif: ExifInterface): Meta {
    val dateMillis =
        runCatching {
              val raw =
                  exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                      ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
              raw?.let { synchronized(EXIF_DATE) { EXIF_DATE.parse(it) }?.time }
            }
            .getOrNull()
    val latLng = runCatching { exif.getLatLong() }.getOrNull()
    return Meta(dateMillis, latLng?.get(0), latLng?.get(1))
  }

  // EXIF stores capture time as "yyyy:MM:dd HH:mm:ss". SimpleDateFormat isn't thread-safe,
  // so callers (which run on a background thread) synchronize on it.
  private val EXIF_DATE = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

  /** Friendly capture date, e.g. "June 22, 2026" in the device locale. Null when absent. */
  fun formatDate(millis: Long?): String? =
      millis?.let { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(it)) }

  // A caption line has to stay one row on a 1280px-wide Portal, and a well-tagged photo of a
  // family gathering can carry a dozen of each — so both lists are capped and the remainder is
  // summarised rather than truncated mid-name.
  private const val MAX_PEOPLE = 4
  private const val MAX_TAGS = 5

  /**
   * The people line: "Alice, Bob & Carol", or "Alice, Bob, Carol, Dave +3" past [MAX_PEOPLE].
   * Null when nobody is named.
   */
  fun formatPeople(names: List<String>): String? {
    val list = cleaned(names)
    return when {
      list.isEmpty() -> null
      list.size > MAX_PEOPLE -> list.take(MAX_PEOPLE).joinToString(", ") + " +${list.size - MAX_PEOPLE}"
      list.size == 1 -> list[0]
      else -> list.dropLast(1).joinToString(", ") + " & " + list.last()
    }
  }

  /** The tags line: "Beach · Sunset · Italy", with a "+n" tail past [MAX_TAGS]. Null when none. */
  fun formatTags(names: List<String>): String? {
    val list = cleaned(names)
    if (list.isEmpty()) return null
    val more = list.size - MAX_TAGS
    val shown = list.take(MAX_TAGS) + (if (more > 0) listOf("+$more") else emptyList())
    return shown.joinToString("  ·  ")
  }

  /** Trimmed, de-duplicated, blanks dropped — what both lines start from. */
  private fun cleaned(names: List<String>): List<String> =
      names.mapNotNull { it.trim().ifBlank { null } }.distinct()

  // --- reverse geocoding (keyless) -------------------------------------------
  // BigDataCloud's reverse-geocode-client endpoint needs no key — the same keyless-web-service
  // approach Immortal already uses for weather (Open-Meteo) and IP geolocation. Results are
  // cached by a coarse (~1km) lat/lng key, and misses are cached too, so a place is looked up
  // at most once and the slideshow never re-hits the network for the same spot.
  private val cache = ConcurrentHashMap<String, String>()

  /**
   * Reverse-geocode coordinates to a "City, Country" label. Network call, best-effort:
   * returns null on any failure. MUST be called off the main thread.
   */
  fun placeName(lat: Double, lng: Double): String? {
    val key = "%.2f,%.2f".format(Locale.US, lat, lng)
    cache[key]?.let { return it.ifEmpty { null } }
    // Cache only a COMPLETED lookup — a resolved place, or a valid response with nothing to show.
    // A network/redirect failure is transient, so leave it UNCACHED and let the next photo cycle
    // retry; caching it would brand this spot permanently blank on a single bad fetch.
    val result = runCatching { fetchPlace(lat, lng) }
    if (result.isFailure) return null
    val place = result.getOrNull()
    cache[key] = place ?: ""
    return place
  }

  private fun fetchPlace(lat: Double, lng: Double): String? {
    // BigDataCloud 307-redirects api.bigdatacloud.net -> api-bdc.io (a different host).
    // HttpURLConnection's auto-redirect does NOT follow 307/308, so we'd otherwise read the empty
    // redirect body and fail to parse. Follow redirects ourselves (any 3xx, cross-host/protocol).
    var spec =
        "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=$lat&longitude=$lng&localityLanguage=en"
    var hops = 0
    while (true) {
      val c = URL(spec).openConnection() as HttpURLConnection
      c.connectTimeout = 8000
      c.readTimeout = 8000
      c.instanceFollowRedirects = false // we handle every hop, so 307/308 aren't silently dropped
      c.setRequestProperty("User-Agent", "PortalPhotoFrame/1.0")
      val code = c.responseCode
      val location = if (code in 300..399) c.getHeaderField("Location") else null
      if (location != null && hops < 3) {
        c.disconnect()
        spec = URL(URL(spec), location).toString() // resolve relative or absolute
        hops++
        continue
      }
      val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
      return parsePlace(body)
    }
  }

  /** Pull a "City, Country" label from a BigDataCloud reverse-geocode JSON body. Pure. */
  internal fun parsePlace(body: String): String? {
    val o = JSONObject(body)
    val city = o.optString("city", "").ifBlank { o.optString("locality", "") }
    val region = o.optString("principalSubdivision", "")
    val country = o.optString("countryName", "")
    val primary = city.ifBlank { region }
    return when {
      primary.isNotBlank() && country.isNotBlank() -> "$primary, $country"
      primary.isNotBlank() -> primary
      country.isNotBlank() -> country
      else -> null
    }
  }
}
