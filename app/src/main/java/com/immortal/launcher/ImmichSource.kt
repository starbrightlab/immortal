/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * A photo source backed by a self-hosted [Immich](https://immich.app) server — the most
 * requested screensaver source. Lists image *preview* URLs for a chosen album (or the whole
 * library); the screensaver then downloads each through the shared remote path, sending the
 * same `x-api-key` header (see [authHeaders]).
 *
 * Verified against Immich **v2.5.2**:
 *  - auth: header `x-api-key: <key>` (no key → 401)
 *  - albums:        `GET  /api/albums`            → `[{id, albumName, assetCount}]`
 *  - album assets:  `GET  /api/albums/{id}`       → `{assets: [{id, type}]}`
 *  - whole library: `POST /api/search/metadata`   → `{assets: {items: [{id, type}], nextPage}}`
 *  - image bytes:   `GET  /api/assets/{id}/thumbnail?size=preview` → JPEG sized to the screen
 *
 * Everything is best-effort: any failure returns null and the caller falls back to the default
 * feed, so the frame is never blank.
 */
object ImmichSource {

  /** A library album, for the connection picker. */
  data class Album(val id: String, val name: String, val count: Int)

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
          .getOrNull()

  /**
   * Image preview URLs for [albumId] (or the whole library when null/blank), capped at [cap].
   * Images only — videos are skipped (the frame can't stream auth'd video). Null on failure.
   */
  fun listImageUrls(base: String, apiKey: String, albumId: String?, cap: Int = 1000): List<String>? =
      runCatching {
            val b = normalizeBase(base)
            val headers = authHeaders(apiKey)
            val ids =
                if (albumId.isNullOrBlank()) libraryImageIds(b, headers, cap)
                else albumImageIds(b, headers, albumId, cap)
            ids.map { previewUrl(b, it) }
          }
          .getOrNull()

  private fun albumImageIds(base: String, headers: Map<String, String>, albumId: String, cap: Int): List<String> {
    val body = httpGet("$base/api/albums/$albumId", headers) ?: return emptyList()
    val assets = JSONObject(body).optJSONArray("assets") ?: return emptyList()
    return imageIdsFrom(assets, cap)
  }

  private fun libraryImageIds(base: String, headers: Map<String, String>, cap: Int): List<String> {
    val out = ArrayList<String>()
    var page = 1
    while (out.size < cap) {
      val req = JSONObject().put("type", "IMAGE").put("size", 1000).put("page", page)
      val body = httpPostJson("$base/api/search/metadata", headers, req.toString()) ?: break
      val assets = JSONObject(body).optJSONObject("assets") ?: break
      val items = assets.optJSONArray("items") ?: break
      if (items.length() == 0) break
      out.addAll(imageIdsFrom(items, cap - out.size))
      val next = assets.opt("nextPage")
      if (next == null || next == JSONObject.NULL) break
      page = (next as? String)?.toIntOrNull() ?: (next as? Int) ?: break
    }
    return out
  }

  private fun imageIdsFrom(assets: JSONArray, limit: Int): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < assets.length() && out.size < limit) {
      val o = assets.getJSONObject(i)
      if (o.optString("type") == "IMAGE") out.add(o.getString("id"))
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
    else null
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
    else null
  }
}
