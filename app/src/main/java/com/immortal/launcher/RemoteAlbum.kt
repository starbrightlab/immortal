package com.immortal.launcher

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Fetch direct image URLs from a public iCloud Shared Album or Google Photos shared
 * album link — no account or API key, only links the owner marked "anyone with the
 * link can view". On any failure [fetch] returns null and the caller falls back to
 * the default web feed so the photo frame is never blank.
 */
object RemoteAlbum {

  data class Album(val title: String?, val photoUrls: List<String>)

  fun isSupported(url: String): Boolean {
    val u = url.trim()
    return isIcloud(u) || isGooglePhotos(u)
  }

  fun providerName(url: String): String =
      when {
        isIcloud(url) -> "iCloud Shared Album"
        isGooglePhotos(url) -> "Google Photos"
        else -> "Shared album"
      }

  fun isIcloud(url: String): Boolean =
      url.contains("icloud.com/sharedalbum/", ignoreCase = true) ||
          url.contains("icloud.com/photo-stream/", ignoreCase = true) ||
          url.contains("icloud.com/photostream/", ignoreCase = true)

  fun isGooglePhotos(url: String): Boolean =
      url.contains("photos.app.goo.gl", ignoreCase = true) ||
          url.contains("photos.google.com/share/", ignoreCase = true)

  fun fetch(shareUrl: String, screenW: Int = 1920, screenH: Int = 1080): Album? {
    val url = shareUrl.trim()
    return runCatching {
          when {
            isIcloud(url) -> fetchIcloud(url, screenW, screenH)
            isGooglePhotos(url) -> fetchGoogle(url, screenW, screenH)
            else -> null
          }
        }
        .getOrNull()
  }

  internal fun icloudToken(url: String): String? {
    val frag = url.substringAfter('#', "").substringBefore('?').trim()
    if (frag.isEmpty()) return null
    return frag.trim('/')
  }

  /**
   * Apple partitions shared streams across hosts `p01…p145`; the first character of
   * the token hints which partition. A wrong guess returns HTTP 330 with the correct
   * host in `X-Apple-MMe-Host`, which [resolveIcloudHost] follows.
   */
  internal fun icloudPartition(token: String): String {
    val c = token.firstOrNull() ?: return "01"
    val n = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".indexOf(c)
    val part = if (n < 0) 1 else n + 1
    return part.toString().padStart(2, '0')
  }

  private fun fetchIcloud(url: String, screenW: Int, screenH: Int): Album? {
    val token = icloudToken(url) ?: return null
    val (host, streamJson) = resolveIcloudHost(token) ?: return null
    val base = "https://$host/$token/sharedstreams"

    // Reuse the body resolveIcloudHost already fetched on a 200; on 330 we still
    // need to call /webstream against the redirected host.
    val body = streamJson ?: postJson("$base/webstream", "{\"streamCtag\":null}") ?: return null
    val stream = JSONObject(body)
    val title = stream.optString("streamName", "").ifBlank { null }
    val photos = stream.optJSONArray("photos") ?: return null
    if (photos.length() == 0) return Album(title, emptyList())

    val guids = ArrayList<String>(photos.length())
    val checksumByGuid = HashMap<String, String>(photos.length())
    for (i in 0 until photos.length()) {
      val p = photos.optJSONObject(i) ?: continue
      val guid = p.optString("photoGuid", "")
      if (guid.isEmpty()) continue
      val derivs = p.optJSONObject("derivatives") ?: continue
      val best = pickBestDerivative(derivs, screenW, screenH) ?: continue
      guids.add(guid)
      checksumByGuid[guid] = best
    }
    if (guids.isEmpty()) return Album(title, emptyList())

    // webasseturls caps each response at ~25 items, so page through.
    val urlByChecksum = HashMap<String, String>(guids.size)
    guids.chunked(25).forEach { batch ->
      val body =
          buildString {
            append("{\"photoGuids\":[")
            batch.forEachIndexed { idx, g ->
              if (idx > 0) append(',')
              append('"').append(g).append('"')
            }
            append("]}")
          }
      val resp = postJson("$base/webasseturls", body) ?: return@forEach
      val items = JSONObject(resp).optJSONObject("items") ?: return@forEach
      val keys = items.keys()
      while (keys.hasNext()) {
        val checksum = keys.next()
        val o = items.optJSONObject(checksum) ?: continue
        val loc = o.optString("url_location", "")
        val path = o.optString("url_path", "")
        if (loc.isNotEmpty() && path.isNotEmpty()) {
          urlByChecksum[checksum] = "https://$loc$path"
        }
      }
    }

    val out = ArrayList<String>(guids.size)
    guids.forEach { guid ->
      val checksum = checksumByGuid[guid] ?: return@forEach
      val u = urlByChecksum[checksum] ?: return@forEach
      out.add(u)
    }
    return Album(title, out)
  }

  // body is non-null on a 200 hit (caller can skip its own /webstream POST), null
  // on a 330 redirect (the request never landed on the right host).
  private fun resolveIcloudHost(token: String): Pair<String, String?>? {
    val firstGuess = "p${icloudPartition(token)}-sharedstreams.icloud.com"
    val c =
        URL("https://$firstGuess/$token/sharedstreams/webstream").openConnection()
            as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    c.setRequestProperty("User-Agent", USER_AGENT)
    c.setRequestProperty("Origin", "https://www.icloud.com")
    c.outputStream.use { it.write("{\"streamCtag\":null}".toByteArray()) }
    val code = runCatching { c.responseCode }.getOrDefault(0)
    val redirect = c.getHeaderField("X-Apple-MMe-Host")
    return try {
      when {
        code == 200 -> {
          val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
          firstGuess to body
        }
        code == 330 && !redirect.isNullOrBlank() -> redirect to null
        else -> null
      }
    } catch (_: Throwable) {
      null
    } finally {
      runCatching { c.disconnect() }
    }
  }

  /**
   * iCloud derivatives are keyed by max-edge pixel size; pick the smallest that
   * still covers the screen, else the largest available.
   */
  internal fun pickBestDerivative(derivs: JSONObject, screenW: Int, screenH: Int): String? {
    val target = maxOf(screenW, screenH)
    val keys = derivs.keys()
    var bestChecksum: String? = null
    var bestSize = -1
    var fallbackChecksum: String? = null
    var fallbackSize = -1
    while (keys.hasNext()) {
      val k = keys.next()
      val size = k.toIntOrNull() ?: continue
      val d = derivs.optJSONObject(k) ?: continue
      val checksum = d.optString("checksum", "")
      if (checksum.isEmpty()) continue
      if (size >= target && (bestSize < 0 || size < bestSize)) {
        bestSize = size
        bestChecksum = checksum
      }
      if (size > fallbackSize) {
        fallbackSize = size
        fallbackChecksum = checksum
      }
    }
    return bestChecksum ?: fallbackChecksum
  }

  private val LH3_REGEX =
      Regex("""https://lh3\.googleusercontent\.com/[A-Za-z0-9_\-/]+(?:=[A-Za-z0-9\-_]+)?""")

  private fun fetchGoogle(url: String, screenW: Int, screenH: Int): Album? {
    val finalUrl = followRedirects(url) ?: url
    val html = httpGet(finalUrl) ?: return null

    val raw = LH3_REGEX.findAll(html).map { it.value }.toList()
    if (raw.isEmpty()) return Album(null, emptyList())

    // Strip the size suffix so we can ask Google for a screen-sized variant, and
    // skip avatar-service URLs (`/a/` and `/a-/`) — those embed on every share page
    // as the owner's profile picture and would otherwise lead the slideshow.
    val sizeSuffix = "=w${screenW}-h${screenH}-no"
    val seen = LinkedHashSet<String>()
    raw.forEach { u ->
      if (isGoogleAvatarUrl(u)) return@forEach
      val stripped = u.substringBefore('=')
      seen.add(stripped + sizeSuffix)
    }
    return Album(extractGoogleTitle(html), seen.toList())
  }

  internal fun isGoogleAvatarUrl(url: String): Boolean {
    val path = url.removePrefix("https://lh3.googleusercontent.com")
    return path.startsWith("/a/") || path.startsWith("/a-/")
  }

  private fun extractGoogleTitle(html: String): String? {
    val m = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html) ?: return null
    return m.groupValues[1].substringBefore(" - Google Photos").trim().ifBlank { null }
  }

  private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
          "Chrome/120.0.0.0 Safari/537.36 PortalPhotoFrame/1.0"

  private fun followRedirects(spec: String, maxHops: Int = 5): String? {
    var current = spec
    repeat(maxHops) {
      val c = URL(current).openConnection() as HttpURLConnection
      c.connectTimeout = 8000
      c.readTimeout = 8000
      c.instanceFollowRedirects = false
      c.requestMethod = "GET"
      c.setRequestProperty("User-Agent", USER_AGENT)
      val code = runCatching { c.responseCode }.getOrDefault(0)
      if (code in 300..399) {
        val loc = c.getHeaderField("Location")
        runCatching { c.disconnect() }
        if (loc.isNullOrBlank()) return current
        current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
      } else {
        runCatching { c.disconnect() }
        return current
      }
    }
    return current
  }

  private fun httpGet(spec: String): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", USER_AGENT)
    c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
    return runCatching { c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
  }

  private fun postJson(spec: String, body: String): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json")
    c.setRequestProperty("User-Agent", USER_AGENT)
    c.setRequestProperty("Origin", "https://www.icloud.com")
    return runCatching {
          c.outputStream.use { it.write(body.toByteArray()) }
          c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        .getOrNull()
  }
}
