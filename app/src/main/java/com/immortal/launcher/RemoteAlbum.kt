package com.immortal.launcher

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetch direct image URLs from a public iCloud Shared Album, Google Photos, or
 * Synology Photos shared-album link — no account or API key, only links the owner
 * marked "anyone with the link can view". On any failure [fetch] returns null and
 * the caller falls back to the default web feed so the photo frame is never blank.
 *
 * iCloud comes in two flavours, both supported here:
 *  - **Legacy "Shared Streams"** — `www.icloud.com/sharedalbum/#TOKEN` (token in the
 *    URL fragment). Retired with iOS/macOS 26; handled by [fetchIcloud].
 *  - **CloudKit shared albums** — `photos.icloud.com/shared/album/TOKEN` (token in the
 *    path). The current format; handled by [fetchIcloudCloudKit], which resolves the
 *    share's CloudKit zone and pages its photo records straight from CloudKit Web
 *    Services (no account — the resolve step mints a short-lived anonymous token).
 */
object RemoteAlbum {

  data class Album(
      val title: String?,
      val photoUrls: List<String>,
      val headers: Map<String, String> = emptyMap(),
  )

  fun isSupported(url: String): Boolean {
    val u = url.trim()
    return isIcloud(u) || isGooglePhotos(u) || isSynologyPhotos(u)
  }

  fun providerName(url: String): String =
      when {
        isIcloud(url) -> "iCloud Shared Album"
        isGooglePhotos(url) -> "Google Photos"
        isSynologyPhotos(url) -> "Synology Photos"
        else -> "Shared album"
      }

  fun isIcloud(url: String): Boolean = isIcloudLegacy(url) || isIcloudCloudKit(url)

  /** Legacy "Shared Streams" links (pre iOS/macOS 26); token lives in the `#` fragment. */
  internal fun isIcloudLegacy(url: String): Boolean =
      url.contains("icloud.com/sharedalbum/", ignoreCase = true) ||
          url.contains("icloud.com/photo-stream/", ignoreCase = true) ||
          url.contains("icloud.com/photostream/", ignoreCase = true)

  /** Current CloudKit shared albums: `photos.icloud.com/shared/album/<shortGUID>`. */
  internal fun isIcloudCloudKit(url: String): Boolean =
      url.contains("icloud.com/shared/album/", ignoreCase = true)

  fun isGooglePhotos(url: String): Boolean =
      url.contains("photos.app.goo.gl", ignoreCase = true) ||
          url.contains("photos.google.com/share/", ignoreCase = true)

  fun isSynologyPhotos(url: String): Boolean = synologyShare(url) != null

  fun fetch(shareUrl: String, screenW: Int = 1920, screenH: Int = 1080): Album? {
    val url = shareUrl.trim()
    return runCatching {
          when {
            isIcloudCloudKit(url) -> fetchIcloudCloudKit(url, screenW, screenH)
            isIcloudLegacy(url) -> fetchIcloud(url, screenW, screenH)
            isGooglePhotos(url) -> fetchGoogle(url, screenW, screenH)
            isSynologyPhotos(url) -> fetchSynology(url)
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
   * The CloudKit shortGUID from a `…/shared/album/<token>` link. Unlike the legacy
   * token it lives in the PATH (not the `#` fragment), so strip any trailing query,
   * fragment, or path segment.
   */
  internal fun cloudKitToken(url: String): String? {
    val marker = "shared/album/"
    val idx = url.indexOf(marker, ignoreCase = true)
    if (idx < 0) return null
    val rest = url.substring(idx + marker.length)
    return rest.substringBefore('?').substringBefore('#').substringBefore('/').trim().ifEmpty { null }
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

    // webasseturls caps each response at ~25 items, so page through. Each batch is
    // contained: one bad response (transport error, unexpected body) skips only its 25
    // photos instead of throwing away the whole album (issue #175 hardening).
    val urlByChecksum = HashMap<String, String>(guids.size)
    guids.chunked(25).forEach { batch ->
      runCatching {
        val body =
            buildString {
              append("{\"photoGuids\":[")
              batch.forEachIndexed { idx, g ->
                if (idx > 0) append(',')
                append('"').append(g).append('"')
              }
              append("]}")
            }
        val resp = postJson("$base/webasseturls", body) ?: return@runCatching
        val items = JSONObject(resp).optJSONObject("items") ?: return@runCatching
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

  // --- CloudKit shared albums (iOS/macOS 26+) --------------------------------

  private const val CK_HOST = "https://ckdatabasews.icloud.com"
  private const val CK_CONTAINER = "com.apple.photos.cloud"
  // CloudKit Web Services treats these as telemetry, not auth — the server accepts
  // any value (verified with a dummy build), so a constant is safe and stable.
  private const val CK_BUILD = "2620BuildBeta48"
  private const val CK_PARAMS = "clientBuildNumber=$CK_BUILD&clientMasteringNumber=$CK_BUILD"
  // The asset-and-master index the iCloud web app pages, newest first.
  private const val CK_RECORD_TYPE = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"
  private const val CK_PAGE_SIZE = 200
  private const val CK_MAX_PAGES = 50 // hard cap so a huge album can't loop forever

  /**
   * Resolve a `…/shared/album/<shortGUID>` link to its CloudKit zone, then page the
   * shared zone's photo records straight from CloudKit Web Services. No account: the
   * public `records/resolve` call returns a short-lived anonymous access token that
   * authorises the (per-partition) `records/query` calls.
   */
  private fun fetchIcloudCloudKit(url: String, screenW: Int, screenH: Int): Album? {
    val token = cloudKitToken(url) ?: return null

    // 1. Resolve the share: zone + anonymous token + the partition host to query.
    val resolveUrl = "$CK_HOST/database/1/$CK_CONTAINER/production/public/records/resolve?$CK_PARAMS"
    // Build the body with JSONObject so a pasted token (or any field) is escaped, not interpolated.
    val resolvePayload =
        JSONObject().put("shortGUIDs", JSONArray().put(JSONObject().put("value", token)))
    val resolveBody = postJson(resolveUrl, resolvePayload.toString()) ?: return null
    val resolved = JSONObject(resolveBody).optJSONArray("results")?.optJSONObject(0) ?: return null
    val zone = resolved.optJSONObject("zoneID") ?: return null
    val zoneName = zone.optString("zoneName", "").ifBlank { return null }
    val ownerRecordName = zone.optString("ownerRecordName", "")
    val zoneType = zone.optString("zoneType", "REGULAR_CUSTOM_ZONE")
    val access = resolved.optJSONObject("anonymousPublicAccess") ?: return null
    val authToken = access.optString("token", "").ifBlank { return null }
    // Records live on the share's partition host (e.g. p117-…); fall back to the
    // generic host if the resolve didn't pin one. Strip the explicit `:443`.
    val partition =
        access.optString("databasePartition", "").ifBlank { CK_HOST }.removeSuffix(":443")
    val title =
        resolved
            .optJSONObject("share")
            ?.optJSONObject("fields")
            ?.optJSONObject("cloudkit.title")
            ?.optString("value", "")
            ?.ifBlank { null }

    // 2. Page the shared zone's assets. downloadURLs come inline — unlike the legacy
    //    API there's no separate webasseturls round-trip.
    val zoneId =
        JSONObject()
            .put("zoneName", zoneName)
            .put("ownerRecordName", ownerRecordName)
            .put("zoneType", zoneType)
    val queryUrl =
        "$partition/database/1/$CK_CONTAINER/production/shared/records/query?$CK_PARAMS" +
            "&publicAccessAuthToken=${URLEncoder.encode(authToken, "UTF-8")}"

    val out = ArrayList<String>()
    var marker: String? = null
    var pages = 0
    do {
      val m = marker // local for a stable smart-cast
      // A later page must never cost the photos already collected: records come as
      // CPLAsset+CPLMaster PAIRS, so one [CK_PAGE_SIZE] page is only ~100 photos and a
      // >100-photo album (issue #175) always crosses at least one page boundary. Any
      // failure here — transport, an error body that isn't the expected JSON shape —
      // degrades to a shorter album, not a throw that nukes the whole fetch.
      val page =
          runCatching {
                val query =
                    JSONObject()
                        .put("recordType", CK_RECORD_TYPE)
                        .put(
                            "filterBy",
                            JSONArray().put(
                                JSONObject()
                                    .put("fieldName", "direction")
                                    .put("comparator", "EQUALS")
                                    .put(
                                        "fieldValue",
                                        JSONObject().put("value", "DESCENDING").put("type", "STRING"))))
                val payload =
                    JSONObject()
                        .put("query", query)
                        .put("zoneID", zoneId)
                        .put("resultsLimit", CK_PAGE_SIZE)
                        .apply { if (m != null) put("continuationMarker", m) }
                val resp = postJson(queryUrl, payload.toString()) ?: return@runCatching null
                val obj = JSONObject(resp)
                val records: JSONArray = obj.optJSONArray("records") ?: return@runCatching null
                val pageUrls = ArrayList<String>()
                for (i in 0 until records.length()) {
                  val rec = records.optJSONObject(i) ?: continue
                  if (rec.optString("recordType") != "CPLMaster") continue
                  val fields = rec.optJSONObject("fields") ?: continue
                  if (!isCloudKitImage(fields)) continue
                  pickBestCloudKitAsset(fields, screenW, screenH)?.let { pageUrls.add(it) }
                }
                pageUrls to obj.optString("continuationMarker", "").ifBlank { null }
              }
              .getOrNull() ?: break
      out.addAll(page.first)
      marker = page.second
      pages++
    } while (marker != null && pages < CK_MAX_PAGES)

    return Album(title, out)
  }

  /** True for still-image masters; skips videos (we only feed photos to the frame). */
  internal fun isCloudKitImage(fields: JSONObject): Boolean {
    val itemType = fields.optJSONObject("itemType")?.optString("value", "") ?: ""
    if (itemType.isEmpty()) return true // unknown → keep, picker still needs a JPEG derivative
    val t = itemType.lowercase()
    return !t.contains("movie") && !t.contains("video")
  }

  /**
   * Choose the best downloadable derivative on a CPLMaster: the smallest whose long
   * edge still covers the screen, else the largest available. Mirrors the legacy
   * [pickBestDerivative] policy. Each `res*Res` field carries the asset blob (with a
   * `downloadURL`); its pixel width is in the sibling `res*Width` field.
   */
  internal fun pickBestCloudKitAsset(fields: JSONObject, screenW: Int, screenH: Int): String? {
    val target = maxOf(screenW, screenH)
    var bestUrl: String? = null
    var bestW = -1
    var fallbackUrl: String? = null
    var fallbackW = -1
    val keys = fields.keys()
    while (keys.hasNext()) {
      val k = keys.next()
      // res*Res hold the image blobs; res*Vid*Res are video tracks — never photos.
      if (!k.startsWith("res") || !k.endsWith("Res") || k.contains("Vid")) continue
      val value = fields.optJSONObject(k)?.optJSONObject("value") ?: continue
      val dl = value.optString("downloadURL", "")
      if (dl.isEmpty() || dl == "null") continue
      val width = fields.optJSONObject(k.removeSuffix("Res") + "Width")?.optInt("value", 0) ?: 0
      if (width in target..Int.MAX_VALUE && (bestW < 0 || width < bestW)) {
        bestW = width
        bestUrl = dl
      }
      if (width > fallbackW) {
        fallbackW = width
        fallbackUrl = dl
      }
    }
    return bestUrl ?: fallbackUrl
  }

  private val LH3_REGEX =
      Regex("""https://lh3\.googleusercontent\.com/[A-Za-z0-9_\-/]+(?:=[A-Za-z0-9\-_]+)?""")

  private fun fetchGoogle(url: String, screenW: Int, screenH: Int): Album? {
    val finalUrl = followRedirects(url) ?: url
    val html = httpGet(finalUrl) ?: return null

    val raw = LH3_REGEX.findAll(html).map { it.value }.toList()
    if (raw.isEmpty()) return Album(null, emptyList())

    // Target the maximum screen dimension so both landscape and portrait photos render
    // crisp without downscaling portrait photos to low resolution inside a bounding box.
    val maxDim = maxOf(screenW, screenH)
    val sizeSuffix = "=w${maxDim}-h${maxDim}-no"
    val seen = LinkedHashSet<String>()
    raw.forEach { u ->
      if (isGoogleAvatarUrl(u)) return@forEach
      val stripped = u.substringBefore('=')
      seen.add(stripped + sizeSuffix)
    }

    // Crawl pagination tokens if the Google Photos album contains more photos than page 1.
    val albumKey = extractGoogleAlbumKey(finalUrl, html)
    var token = extractGoogleContinuationToken(html)
    var page = 0
    val maxPages = 100 // Cap to 100 pages (~10,000 photos) for memory/network safety

    while (!token.isNullOrBlank() && !albumKey.isNullOrBlank() && page < maxPages) {
      // Contained per page: a failed continuation fetch keeps the photos already found
      // rather than propagating out of the crawl (issue #175 hardening).
      val rpcBody = runCatching { fetchGoogleBatchRpc(albumKey, token!!) }.getOrNull() ?: break
      val prevSize = seen.size
      LH3_REGEX.findAll(rpcBody).forEach { m ->
        val u = m.value
        if (!isGoogleAvatarUrl(u)) {
          val stripped = u.substringBefore('=')
          seen.add(stripped + sizeSuffix)
        }
      }
      val nextToken = extractGoogleContinuationToken(rpcBody)
      if (nextToken == token || seen.size == prevSize) {
        break
      }
      token = nextToken
      page++
    }

    return Album(extractGoogleTitle(html), seen.toList())
  }

  internal fun extractGoogleAlbumKey(url: String, html: String): String? {
    val mUrl = Regex("""photos\.google\.com/share/([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE).find(url)
    if (mUrl != null) return mUrl.groupValues[1]
    val mHtml = Regex("""["'](AF1Qa1[A-Za-z0-9_\-]+)["']""").find(html)
    return mHtml?.groupValues?.get(1)
  }

  internal fun extractGoogleContinuationToken(text: String): String? {
    val m = Regex("""["'](C[A-Za-z0-9_\-]{30,})["']""").find(text)
    return m?.groupValues?.get(1)
  }

  private fun fetchGoogleBatchRpc(albumKey: String, token: String): String? {
    val spec = "https://photos.google.com/_/PhotosUi/data/batched/ds:1?rpcids=snAcAc&_reqid=1000&rt=c"
    val reqData = """[[["snAcAc","[\"$albumKey\",\"$token\"]",null,"1"]]]"""
    val body = "f.req=" + URLEncoder.encode(reqData, "UTF-8")
    return postForm(spec, body)
  }

  private fun postForm(spec: String, body: String): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
    c.setRequestProperty("User-Agent", USER_AGENT)
    return runCatching {
      c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
      c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
  }

  internal fun isGoogleAvatarUrl(url: String): Boolean {
    val path = url.removePrefix("https://lh3.googleusercontent.com")
    return path.startsWith("/a/") || path.startsWith("/a-/")
  }

  private fun extractGoogleTitle(html: String): String? {
    val m = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html) ?: return null
    return m.groupValues[1].substringBefore(" - Google Photos").trim().ifBlank { null }
  }

  // --- Synology Photos public shares -----------------------------------------

  private const val SYNO_PAGE_SIZE = 500
  private const val SYNO_MAX_PAGES = 100

  /**
   * Synology's public viewer uses the same private WebAPI as the Photos web app:
   * opening `/mo/sharing/<passphrase>` mints a `sharing_sid` cookie, then album and
   * item metadata are JSON calls under `/mo/sharing/webapi/entry.cgi`. Thumbnail
   * responses require that cookie too, so it is returned in [Album.headers].
   */
  private fun fetchSynology(url: String): Album? {
    val share = synologyShare(url) ?: return null
    val landing = httpGetResponse(url) ?: return null
    val cookie = synologySharingCookie(landing.setCookies) ?: return null
    val headers = mapOf("Cookie" to cookie)

    val albumJson =
        synologyGet(
            share.apiUrl,
            "SYNO.Foto.Browse.Album",
            4,
            "get",
            share.passphrase,
            headers,
            mapOf("additional" to """["sharing_info","flex_section","provider_count"]"""),
        ) ?: return null
    val album =
        JSONObject(albumJson)
            .optJSONObject("data")
            ?.optJSONArray("list")
            ?.optJSONObject(0) ?: return null
    val title = album.optString("name", "").ifBlank { null }
    val sortBy = album.optString("sort_by", "takentime").ifBlank { "takentime" }
    val sortDirection = album.optString("sort_direction", "asc").ifBlank { "asc" }

    val out = ArrayList<String>()
    var offset = 0
    var pages = 0
    var lastPageLen: Int
    do {
      // Contained per page, like the other providers: a failed or malformed later page
      // keeps the photos already collected (issue #175 hardening).
      val items =
          runCatching {
                val body =
                    synologyGet(
                        share.apiUrl,
                        "SYNO.Foto.Browse.Item",
                        1,
                        "list",
                        share.passphrase,
                        headers,
                        mapOf(
                            "offset" to offset.toString(),
                            "limit" to SYNO_PAGE_SIZE.toString(),
                            "sort_by" to jsonString(sortBy),
                            "sort_direction" to jsonString(sortDirection),
                            "additional" to """["thumbnail"]""",
                        ),
                    ) ?: return@runCatching null
                JSONObject(body).optJSONObject("data")?.optJSONArray("list")
              }
              .getOrNull() ?: break
      for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        if (item.optString("type") == "video") continue
        val thumb = item.optJSONObject("additional")?.optJSONObject("thumbnail") ?: continue
        val unitId = thumb.optLong("unit_id", 0L)
        val cacheKey = thumb.optString("cache_key", "")
        if (unitId <= 0L || cacheKey.isEmpty()) continue
        out.add(synologyThumbnailUrl(share.apiUrl, share.passphrase, unitId, cacheKey))
      }
      lastPageLen = items.length()
      offset += lastPageLen
      pages++
    } while (lastPageLen == SYNO_PAGE_SIZE && pages < SYNO_MAX_PAGES)

    return Album(title, out, headers)
  }

  internal data class SynologyShare(val apiUrl: String, val passphrase: String)

  internal fun synologyShare(spec: String): SynologyShare? {
    val parsed = runCatching { URL(spec.trim()) }.getOrNull() ?: return null
    if (parsed.protocol != "http" && parsed.protocol != "https") return null
    val marker = "/mo/sharing/"
    val idx = parsed.path.indexOf(marker, ignoreCase = true)
    if (idx < 0) return null
    val token = parsed.path.substring(idx + marker.length).substringBefore('/').trim()
    if (token.isEmpty()) return null
    val basePath = parsed.path.substring(0, idx + marker.length)
    val port = if (parsed.port >= 0) ":${parsed.port}" else ""
    return SynologyShare(
        "${parsed.protocol}://${parsed.host}$port${basePath}webapi/entry.cgi",
        token,
    )
  }

  internal fun synologySharingCookie(setCookies: List<String>): String? =
      setCookies
          .asSequence()
          .map { it.substringBefore(';').trim() }
          .firstOrNull { it.startsWith("sharing_sid=") && it.length > "sharing_sid=".length }

  internal fun synologyThumbnailUrl(
      apiUrl: String,
      passphrase: String,
      unitId: Long,
      cacheKey: String,
  ): String =
      buildUrl(
          apiUrl,
          linkedMapOf(
              "id" to unitId.toString(),
              "cache_key" to jsonString(cacheKey),
              "type" to jsonString("unit"),
              "size" to jsonString("xl"),
              "passphrase" to jsonString(passphrase),
              "api" to jsonString("SYNO.Foto.Thumbnail"),
              "method" to jsonString("get"),
              "version" to "2",
              "_sharing_id" to jsonString(passphrase),
          ),
      )

  private fun synologyGet(
      apiUrl: String,
      api: String,
      version: Int,
      method: String,
      passphrase: String,
      headers: Map<String, String>,
      params: Map<String, String>,
  ): String? {
    val all =
        linkedMapOf(
            "api" to jsonString(api),
            "version" to version.toString(),
            "method" to jsonString(method),
            "_sharing_id" to jsonString(passphrase),
            "passphrase" to jsonString(passphrase),
        )
    all.putAll(params)
    val response = httpGetResponse(buildUrl(apiUrl, all), headers) ?: return null
    val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
    return if (json.optBoolean("success")) response.body else null
  }

  private fun jsonString(value: String): String = JSONObject.quote(value)

  private fun buildUrl(base: String, params: Map<String, String>): String =
      buildString {
        append(base).append('?')
        params.entries.forEachIndexed { idx, (key, value) ->
          if (idx > 0) append('&')
          append(URLEncoder.encode(key, "UTF-8"))
          append('=')
          append(URLEncoder.encode(value, "UTF-8"))
        }
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
    return httpGetResponse(spec)?.body
  }

  private data class HttpResponse(val body: String, val setCookies: List<String>)

  private fun httpGetResponse(
      spec: String,
      headers: Map<String, String> = emptyMap(),
  ): HttpResponse? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 10000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", USER_AGENT)
    c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
    headers.forEach { (key, value) -> c.setRequestProperty(key, value) }
    return runCatching {
          val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
          val cookies =
              c.headerFields.entries
                  .filter { (key, _) -> key.equals("Set-Cookie", ignoreCase = true) }
                  .flatMap { it.value.orEmpty() }
          HttpResponse(body, cookies)
        }
        .getOrNull()
        .also { runCatching { c.disconnect() } }
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
