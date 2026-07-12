/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

/**
 * A photo source backed by a WebDAV server (a NAS WebDAV share, or Nextcloud's WebDAV
 * endpoint). Lists image file URLs under a folder via PROPFIND, then the screensaver downloads
 * each with a plain HTTP GET — so it reuses the remote path exactly like Immich, just with
 * Basic auth and an XML listing instead of a JSON API.
 *
 * Verified against an Apache mod_dav server (TrueNAS): `PROPFIND <dir> Depth:1` → a
 * multistatus XML listing whose `<href>`s are server-absolute paths, and a `GET <file>` returns
 * the image bytes. Optional HTTP Basic auth ([authHeaders]).
 *
 * Best-effort: any failure returns null/empty and the caller falls back to the default feed.
 */
object DavSource {

  /** HTTP Basic auth header(s) — empty when the server needs no credentials. */
  fun authHeaders(user: String?, pass: String?): Map<String, String> =
      if (user.isNullOrBlank()) emptyMap()
      else
          mapOf(
              "Authorization" to
                  "Basic " +
                      Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP))

  /** A trailing-slashed directory URL. */
  private fun dir(url: String): String = url.trim().let { if (it.endsWith("/")) it else "$it/" }

  /** Verify the folder is reachable (PROPFIND Depth:0 → a body). */
  fun testConnection(baseUrl: String, user: String?, pass: String?): Boolean =
      runCatching { propfind(dir(baseUrl), authHeaders(user, pass), "0") != null }
          .getOrDefault(false)

  /** One playable asset under a WebDAV folder: a file URL, plus whether it is a video. */
  data class Media(val url: String, val isVideo: Boolean)

  /**
   * Image file URLs under [baseUrl] (recursing into subfolders), capped at [cap]. Null on
   * failure. The base URL is the full WebDAV folder URL (e.g.
   * `http://nas:30035/media/photos/library/`).
   */
  fun listImageUrls(baseUrl: String, user: String?, pass: String?, cap: Int = 1000): List<String>? =
      crawl(baseUrl, user, pass, includeVideo = false, cap = cap)?.map { it.url }

  /**
   * Playable media (image URLs, plus video URLs when [includeVideo]) under [baseUrl], capped at
   * [cap]. Videos stream through the same auth'd remote path as Immich (a plain GET with the
   * Basic-auth header), so a WebDAV share can back a video wall — see [PhotoFrameController]'s
   * WebDAV branch. Null on failure.
   */
  fun listMedia(
      baseUrl: String,
      user: String?,
      pass: String?,
      includeVideo: Boolean = false,
      cap: Int = 1000,
  ): List<Media>? = crawl(baseUrl, user, pass, includeVideo, cap)

  /**
   * PROPFIND-crawl [baseUrl] and its subfolders, returning image files (and, when [includeVideo],
   * video files) as [Media]. The single crawl behind both [listImageUrls] and [listMedia]. Null on
   * failure; best-effort throughout.
   */
  private fun crawl(
      baseUrl: String,
      user: String?,
      pass: String?,
      includeVideo: Boolean,
      cap: Int,
  ): List<Media>? =
      runCatching {
            val headers = authHeaders(user, pass)
            val origin = origin(baseUrl) ?: return null
            val out = ArrayList<Media>()
            val stack = ArrayDeque<String>()
            stack.addLast(dir(baseUrl))
            val seen = HashSet<String>()
            while (stack.isNotEmpty() && out.size < cap) {
              val url = stack.removeLast()
              if (!seen.add(url)) continue
              val selfPath = URL(url).path
              val body = propfind(url, headers, "1") ?: continue
              for ((href, isCollection) in parseEntries(body)) {
                if (out.size >= cap) break
                if (href.trimEnd('/') == selfPath.trimEnd('/')) continue // the folder itself
                val full = origin + href
                if (isCollection) {
                  stack.addLast(dir(full))
                } else
                    when (LocalMedia.classify(href)) {
                      LocalMedia.Kind.IMAGE -> out.add(Media(full, false))
                      LocalMedia.Kind.VIDEO -> if (includeVideo) out.add(Media(full, true))
                      else -> {}
                    }
              }
            }
            out
          }
          .getOrNull()

  /** `scheme://host[:port]` for a URL. */
  private fun origin(url: String): String? =
      runCatching {
            val u = URL(url)
            val port = if (u.port == -1) "" else ":${u.port}"
            "${u.protocol}://${u.host}$port"
          }
          .getOrNull()

  /** Parse a multistatus body into (href, isCollection) pairs, tolerant of namespace prefixes. */
  internal fun parseEntries(xml: String): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    val response = Regex("<[A-Za-z0-9]*:?response\\b.*?</[A-Za-z0-9]*:?response>", RegexOption.DOT_MATCHES_ALL)
    val hrefRe = Regex("<[A-Za-z0-9]*:?href[^>]*>([^<]+)</[A-Za-z0-9]*:?href>")
    val collRe = Regex("<[A-Za-z0-9]*:?collection\\b")
    for (m in response.findAll(xml)) {
      val block = m.value
      val href = hrefRe.find(block)?.groupValues?.get(1)?.trim() ?: continue
      out.add(href to collRe.containsMatchIn(block))
    }
    return out
  }

  // --- HTTP PROPFIND ----------------------------------------------------------
  private fun propfind(url: String, headers: Map<String, String>, depth: String): String? {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 12000
    setMethod(c, "PROPFIND")
    c.setRequestProperty("Depth", depth)
    c.setRequestProperty("User-Agent", "PortalPhotoFrame/1.0")
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    return if (c.responseCode in 200..299)
        c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    else null
  }

  /**
   * Set a non-standard HTTP method. [HttpURLConnection.setRequestMethod] rejects PROPFIND, so
   * fall back to setting the inherited `method` field directly (Android's okhttp-backed impl
   * reads it from there).
   */
  private fun setMethod(c: HttpURLConnection, method: String) {
    runCatching { c.requestMethod = method }
        .onFailure {
          runCatching {
            val f = HttpURLConnection::class.java.getDeclaredField("method")
            f.isAccessible = true
            f.set(c, method)
          }
        }
  }
}
