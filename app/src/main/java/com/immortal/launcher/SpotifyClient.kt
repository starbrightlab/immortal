/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Base64
import android.util.Log
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Spotify Web API client for the StarFace shell's Spotify mode.
 *
 * Auth flow: Authorization Code with PKCE. No client secret needed. The user
 * signs in on a browser (native Chrome tab, not our WebView), Spotify
 * redirects to `stargazer-star://spotify-callback?code=…`, Android routes the
 * intent back to StarFaceActivity, which extracts the code + hands it off
 * here for exchange.
 *
 * Tokens: refresh_token persists in FleetConfig (`star.spotify.refresh_token`);
 * access_token lives in memory only (short-lived, ~1h), refreshed on demand.
 */
object SpotifyClient {
  const val REDIRECT_URI = "stargazer-star://spotify-callback"
  private const val TAG = "SpotifyClient"
  private const val AUTH_HOST = "https://accounts.spotify.com"
  private const val API_HOST = "https://api.spotify.com/v1"
  // Scopes: read current playback + transport control. `streaming` would let
  // us play through the Portal via the Playback SDK, which needs Play Services
  // we don't have — omitted for now.
  private const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing"

  data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)

  data class Track(
      val name: String,
      val artist: String,
      val album: String,
      val artUrl: String?,
      val isPlaying: Boolean,
      val progressMs: Long,
      val durationMs: Long,
  )

  // --- PKCE ---------------------------------------------------------------

  /** RFC-7636 code verifier: 64 random URL-safe bytes → base64url (43-64 chars).
   *  Held in memory between authorize() and exchangeCode(). */
  fun makeVerifier(): String {
    val bytes = ByteArray(64)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }

  fun computeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
  }

  fun authorizeUrl(clientId: String, verifier: String, state: String): String {
    val challenge = computeChallenge(verifier)
    val params = mapOf(
        "client_id" to clientId,
        "response_type" to "code",
        "redirect_uri" to REDIRECT_URI,
        "code_challenge_method" to "S256",
        "code_challenge" to challenge,
        "state" to state,
        "scope" to SCOPES,
    )
    return "$AUTH_HOST/authorize?" + params.entries.joinToString("&") {
      "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
  }

  // --- Token exchange + refresh ------------------------------------------

  /** Exchange the one-time authorization code for tokens. Runs on caller's
   *  thread — assume off the main thread. */
  fun exchangeCode(clientId: String, code: String, verifier: String): Tokens? =
      postForm("$AUTH_HOST/api/token", mapOf(
          "grant_type" to "authorization_code",
          "code" to code,
          "redirect_uri" to REDIRECT_URI,
          "client_id" to clientId,
          "code_verifier" to verifier,
      ))?.let(::parseTokens)

  fun refresh(clientId: String, refreshToken: String): Tokens? =
      postForm("$AUTH_HOST/api/token", mapOf(
          "grant_type" to "refresh_token",
          "refresh_token" to refreshToken,
          "client_id" to clientId,
      ))?.let { obj ->
        // Spotify sometimes rotates the refresh token, sometimes not — if the
        // response omits it, keep the one we already had.
        parseTokens(obj, fallbackRefresh = refreshToken)
      }

  private fun parseTokens(obj: JSONObject, fallbackRefresh: String? = null): Tokens? {
    val access = obj.optString("access_token", "")
    if (access.isEmpty()) return null
    val refresh = obj.optString("refresh_token").ifEmpty { fallbackRefresh ?: "" }
    if (refresh.isEmpty()) return null
    val expiresIn = obj.optInt("expires_in", 3600)
    val expiresAt = System.currentTimeMillis() + expiresIn * 1000L - 60_000L  // 1-min guard
    return Tokens(access, refresh, expiresAt)
  }

  // --- Player API --------------------------------------------------------

  /** Currently playing track, or null if nothing is active. Returns null on
   *  any error (401, 429, no active device) — caller shows a fallback. */
  fun currentlyPlaying(access: String): Track? {
    val obj = getJson("$API_HOST/me/player/currently-playing", access) ?: return null
    val item = obj.optJSONObject("item") ?: return null
    val name = item.optString("name")
    val artistArr = item.optJSONArray("artists")
    val artist = if (artistArr != null && artistArr.length() > 0) {
      (0 until artistArr.length()).mapNotNull { artistArr.optJSONObject(it)?.optString("name") }
          .joinToString(", ")
    } else ""
    val albumObj = item.optJSONObject("album")
    val album = albumObj?.optString("name") ?: ""
    val artUrl = albumObj?.optJSONArray("images")?.let { imgs ->
      // Spotify returns images widest-first; pick the first (typically 640x640).
      (0 until imgs.length()).firstNotNullOfOrNull { imgs.optJSONObject(it)?.optString("url") }
    }
    val isPlaying = obj.optBoolean("is_playing", false)
    val progress = obj.optLong("progress_ms", 0)
    val duration = item.optLong("duration_ms", 0)
    return Track(name, artist, album, artUrl, isPlaying, progress, duration)
  }

  fun play(access: String): Boolean = putEmpty("$API_HOST/me/player/play", access)
  fun pause(access: String): Boolean = putEmpty("$API_HOST/me/player/pause", access)
  fun next(access: String): Boolean = postEmpty("$API_HOST/me/player/next", access)
  fun previous(access: String): Boolean = postEmpty("$API_HOST/me/player/previous", access)

  // --- HTTP helpers ------------------------------------------------------

  private fun postForm(url: String, params: Map<String, String>): JSONObject? {
    val body = params.entries.joinToString("&") {
      "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }.toByteArray()
    return try {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.connectTimeout = 5000
      conn.readTimeout = 8000
      conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      conn.setFixedLengthStreamingMode(body.size)
      conn.outputStream.use { it.write(body) }
      readJson(conn, expect = 200)
    } catch (ex: Throwable) {
      Log.w(TAG, "postForm $url failed: $ex")
      null
    }
  }

  private fun getJson(url: String, access: String): JSONObject? = withAuth(url, "GET", access, null)
  private fun putEmpty(url: String, access: String): Boolean = withAuth(url, "PUT", access, ByteArray(0)) != null
  private fun postEmpty(url: String, access: String): Boolean = withAuth(url, "POST", access, ByteArray(0)) != null

  private fun withAuth(url: String, method: String, access: String, body: ByteArray?): JSONObject? =
      try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        conn.setRequestProperty("Authorization", "Bearer $access")
        if (body != null) {
          conn.doOutput = true
          conn.setFixedLengthStreamingMode(body.size)
          conn.setRequestProperty("Content-Type", "application/json")
          conn.outputStream.use { it.write(body) }
        }
        // Spotify returns 204 for successful playback commands (no body) and
        // 200 for JSON responses. Both are OK.
        val rc = conn.responseCode
        if (rc == 204) JSONObject() else readJson(conn, expect = 200)
      } catch (ex: Throwable) {
        Log.w(TAG, "$method $url failed: $ex")
        null
      }

  private fun readJson(conn: HttpURLConnection, expect: Int): JSONObject? {
    if (conn.responseCode != expect) {
      Log.w(TAG, "HTTP ${conn.responseCode} on ${conn.url}")
      // Drain error stream so the connection can be returned to the pool.
      runCatching { conn.errorStream?.bufferedReader()?.readText() }
      return null
    }
    val text = conn.inputStream.bufferedReader().readText()
    if (text.isEmpty()) return JSONObject()
    return JSONObject(text)
  }
}
