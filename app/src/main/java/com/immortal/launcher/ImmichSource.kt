/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * A photo source backed by a self-hosted [Immich](https://immich.app) server — the most
 * requested screensaver source. Lists image *preview* URLs (and, when the "Play videos" setting
 * is on, video *playback* URLs) for a chosen album (or the whole library); the screensaver then
 * fetches each through the shared remote path, sending the same `x-api-key` header (see
 * [authHeaders]).
 *
 * Verified against Immich **v2.5.2** and **v3.0.1**:
 *  - auth: header `x-api-key: <key>` (no key → 401)
 *  - albums (picker): `GET  /api/albums`          → `[{id, albumName, assetCount}]`
 *  - assets:        `POST /api/search/metadata`   → `{assets: {items: [{id, type}], nextPage}}`,
 *    filtered by `albumIds` for a single album or unfiltered for the whole library. (Immich 3.0
 *    dropped the `assets` array that `GET /api/albums/{id}` used to embed, so both paths now go
 *    through search — see #135.)
 *  - image bytes:   `GET  /api/assets/{id}/thumbnail?size=preview` → JPEG sized to the screen
 *  - video stream:  `GET  /api/assets/{id}/video/playback` → the (server-transcoded) clip
 *  - one asset:     `GET  /api/assets/{id}` → `{localDateTime, exifInfo, people, tags}`, the
 *    caption detail a re-encoded preview can't carry (see [details])
 *
 * Everything is best-effort: any failure returns null and the caller falls back to the default
 * feed, so the frame is never blank — but every failure is logged under [TAG], because a silent
 * fallback is undiagnosable from a user report (issue #142).
 */
object ImmichSource {
  private const val TAG = "ImmortalImmich"

  /** A library album, for the connection picker. */
  data class Album(val id: String, val name: String, val count: Int)

  /** One playable asset: its Immich id, plus a preview-image or video-playback URL. */
  data class Media(val id: String, val url: String, val isVideo: Boolean)

  /**
   * What Immich knows about one photo that its re-encoded preview JPEG can't carry: when and
   * where it was taken, the description the user typed on it, who Immich recognised in it, and
   * the tags it's filed under. Every field is optional — an unnamed face or an untagged photo
   * simply contributes nothing to the caption.
   */
  data class Details(
      val dateMillis: Long? = null,
      val description: String? = null,
      val place: String? = null,
      val people: List<String> = emptyList(),
      val tags: List<String> = emptyList(),
  ) {
    val isEmpty: Boolean
      get() =
          dateMillis == null &&
              description == null &&
              place == null &&
              people.isEmpty() &&
              tags.isEmpty()
  }

  /** Header(s) every Immich request (including image downloads) must carry. */
  fun authHeaders(apiKey: String): Map<String, String> = mapOf("x-api-key" to apiKey.trim())

  /** Normalise a user-entered base URL to `scheme://host:port` with no trailing slash or /api. */
  fun normalizeBase(raw: String): String {
    var s = raw.trim().trimEnd('/')
    if (s.endsWith("/api", ignoreCase = true)) s = s.dropLast(4)
    return s
  }

  /** The preview-image URL for an asset (sized to the screen; ~150KB JPEG). */
  fun previewUrl(base: String, assetId: String): String =
      "${normalizeBase(base)}/api/assets/$assetId/thumbnail?size=preview"

  /** The streaming-playback URL for a video asset (the server transcodes as needed). */
  fun playbackUrl(base: String, assetId: String): String =
      "${normalizeBase(base)}/api/assets/$assetId/video/playback"

  /** Confirm the server is reachable and the key is valid (`GET /api/users/me` → 200). */
  fun testConnection(base: String, apiKey: String): Boolean =
      runCatching {
            httpGet("${normalizeBase(base)}/api/users/me", authHeaders(apiKey)) != null
          }
          .getOrDefault(false)

  /** List the user's albums (for the picker). Null on any failure. */
  fun listAlbums(base: String, apiKey: String): List<Album>? =
      runCatching {
            val body = httpGet("${normalizeBase(base)}/api/albums", authHeaders(apiKey)) ?: return null
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
              val o = arr.getJSONObject(i)
              Album(o.getString("id"), o.optString("albumName", "Album"), o.optInt("assetCount", 0))
            }
          }
          .onFailure { Log.w(TAG, "album list failed", it) }
          .getOrNull()

  /**
   * Playable media for [albumId] (or the whole library when null/blank), capped at [cap] items:
   * preview URLs for images, playback URLs for videos when [includeVideo] is on (the "Play
   * videos" setting — videos stream through the same auth'd remote path). Null on failure.
   */
  fun listMedia(
      base: String,
      apiKey: String,
      albumId: String?,
      includeVideo: Boolean = false,
      cap: Int = 1000,
  ): List<Media>? =
      runCatching {
            val b = normalizeBase(base)
            val headers = authHeaders(apiKey)
            val assets = searchAssets(b, headers, cap, albumId?.takeIf { it.isNotBlank() }, includeVideo)
            if (assets.isEmpty()) {
              Log.w(TAG, "search returned no assets (album=${albumId ?: "library"})")
            }
            assets.map { (id, isVideo) ->
              Media(id, if (isVideo) playbackUrl(b, id) else previewUrl(b, id), isVideo)
            }
          }
          .onFailure { Log.w(TAG, "media list failed (album=${albumId ?: "library"})", it) }
          .getOrNull()

  /**
   * Everything the caption can show for one asset, from `GET /api/assets/{id}` — the single
   * endpoint that carries `exifInfo`, `people` and `tags` together. (The search listing used by
   * [listMedia] can be asked for some of these via `withExif`/`withPeople`, but which relations it
   * actually returns has moved between Immich versions, and tags aren't among them; one small GET
   * per displayed photo is both complete and version-proof.)
   *
   * Best-effort like the rest: null on any failure, so the photo simply shows uncaptioned.
   */
  fun details(base: String, apiKey: String, assetId: String): Details? =
      runCatching {
            val body =
                httpGet("${normalizeBase(base)}/api/assets/$assetId", authHeaders(apiKey))
                    ?: return null
            parseDetails(body)
          }
          .onFailure { Log.w(TAG, "asset details failed ($assetId)", it) }
          .getOrNull()

  /** Pull the caption fields out of an Immich asset body. Pure, so it's unit-testable. */
  internal fun parseDetails(body: String): Details {
    val o = JSONObject(body)
    val exif = o.optJSONObject("exifInfo")
    return Details(
        // `localDateTime` is the wall clock where the shot was taken, which is the day a photo
        // frame should name; `dateTimeOriginal` carries the same digits with a separate offset.
        // `fileCreatedAt` is a true UTC instant and is only a last resort (an import can set it
        // long after the shutter fired).
        dateMillis =
            parseIso(stringOf(o, "localDateTime"))
                ?: parseIso(stringOf(exif, "dateTimeOriginal"))
                ?: parseIso(stringOf(o, "fileCreatedAt"), UTC),
        description = stringOf(exif, "description"),
        place = placeOf(exif),
        people = namesOf(o.optJSONArray("people")),
        tags = tagsOf(o.optJSONArray("tags")),
    )
  }

  /** "City, Country" from an `exifInfo` block — the shape [PhotoCaption] already renders for EXIF. */
  private fun placeOf(exif: JSONObject?): String? {
    val primary = stringOf(exif, "city") ?: stringOf(exif, "state")
    val country = stringOf(exif, "country")
    return when {
      primary != null && country != null -> "$primary, $country"
      else -> primary ?: country
    }
  }

  /** Named people only — a face Immich hasn't been given a name for has nothing to show. */
  private fun namesOf(arr: JSONArray?): List<String> = fieldsOf(arr) { stringOf(it, "name") }

  /**
   * Tag labels. Immich nests tags, so `value` is the full path ("Holiday/Beach") and `name` the
   * leaf; prefer the leaf, and derive it from the path on a server that only sends `value`.
   */
  private fun tagsOf(arr: JSONArray?): List<String> =
      fieldsOf(arr) { t ->
        stringOf(t, "name") ?: stringOf(t, "value")?.substringAfterLast('/')?.ifBlank { null }
      }

  private fun fieldsOf(arr: JSONArray?, pick: (JSONObject) -> String?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(pick) }.distinct()
  }

  /**
   * A trimmed string field, or null when it's absent, blank, or an explicit JSON null — Immich
   * sends nulls for the fields it has nothing for (no GPS → `city`/`country` null), and Android's
   * `JSONObject.optString` hands those back as the literal text "null".
   */
  private fun stringOf(o: JSONObject?, key: String): String? =
      o?.takeIf { !it.isNull(key) }?.optString(key)?.trim()?.ifBlank { null }

  // Immich timestamps are ISO-8601 ("2026-06-22T18:11:12.000Z" / "...+02:00"). Only the calendar
  // day ever reaches the caption, so we read the leading "yyyy-MM-dd'T'HH:mm:ss" and drop the
  // fractional seconds and offset — in the device's own zone by default, which is what makes
  // `localDateTime`'s digits land on the day the shutter fired. SimpleDateFormat isn't
  // thread-safe (and this is shared by the caption threads), so every use is synchronized.
  private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
  private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

  private fun parseIso(raw: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
    if (raw == null || raw.length < 19) return null
    return runCatching {
          synchronized(ISO) {
            ISO.timeZone = zone
            ISO.parse(raw.substring(0, 19))
          }?.time
        }
        .getOrNull()
  }

  /**
   * Asset (id, isVideo) pairs via the paged `POST /api/search/metadata` endpoint. When [albumId]
   * is given the search is filtered to that album (`albumIds`), otherwise it covers the whole
   * library. This one path replaces the old album `GET /api/albums/{id}` embed, which stopped
   * returning an `assets` array in Immich 3.0 (#135). Images only searches keep the server-side
   * `type: IMAGE` filter; with [includeVideo] the type filter is dropped and IMAGE/VIDEO are
   * accepted client-side, preserving the library's natural ordering.
   */
  private fun searchAssets(
      base: String,
      headers: Map<String, String>,
      cap: Int,
      albumId: String?,
      includeVideo: Boolean,
  ): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    var page = 1
    while (out.size < cap) {
      val req = JSONObject().put("size", 1000).put("page", page)
      if (!includeVideo) req.put("type", "IMAGE")
      if (albumId != null) req.put("albumIds", JSONArray().put(albumId))
      val body = httpPostJson("$base/api/search/metadata", headers, req.toString()) ?: break
      val assets = JSONObject(body).optJSONObject("assets") ?: break
      val items = assets.optJSONArray("items") ?: break
      if (items.length() == 0) break
      out.addAll(assetsFrom(items, cap - out.size, includeVideo))
      val next = assets.opt("nextPage")
      if (next == null || next == JSONObject.NULL) break
      page = (next as? String)?.toIntOrNull() ?: (next as? Int) ?: break
    }
    return out
  }

  private fun assetsFrom(
      assets: JSONArray,
      limit: Int,
      includeVideo: Boolean,
  ): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    var i = 0
    while (i < assets.length() && out.size < limit) {
      val o = assets.getJSONObject(i)
      when (o.optString("type")) {
        "IMAGE" -> out.add(o.getString("id") to false)
        "VIDEO" -> if (includeVideo) out.add(o.getString("id") to true)
      }
      i++
    }
    return out
  }

  // --- HTTP (mirrors the app's other best-effort fetchers) --------------------
  private fun httpGet(spec: String, headers: Map<String, String>): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    return if (c.responseCode in 200..299) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    else null.also { logHttpFailure("GET", spec, c) }
  }

  private fun httpPostJson(spec: String, headers: Map<String, String>, json: String): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 12000
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    return if (c.responseCode in 200..299) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    else null.also { logHttpFailure("POST", spec, c) }
  }

  /** A rejected request is the one clue a user report can carry — keep the server's own words. */
  private fun logHttpFailure(method: String, spec: String, c: HttpURLConnection) {
    val detail =
        runCatching { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8).take(300) } }
            .getOrNull()
    Log.w(TAG, "$method $spec -> HTTP ${c.responseCode}${detail?.let { ": $it" } ?: ""}")
  }
}
