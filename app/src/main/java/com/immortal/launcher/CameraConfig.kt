/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved RTSP camera streams for the camera viewer (kitchen Portal showing the
 * driveway/door cam). Stored as a small JSON array in SharedPrefs. URLs are plain
 * `rtsp://user:pass@host:554/path` strings, played by Android's built-in MediaPlayer
 * (which supports RTSP natively — no extra library or GMS needed).
 */
object CameraConfig {

  private const val PREFS = "immortal_cameras"
  private const val KEY = "cameras"

  data class Camera(val id: Long, val name: String, val url: String)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): List<Camera> {
    val raw = prefs(context).getString(KEY, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Camera(o.optLong("id", System.nanoTime()), o.optString("name", "Camera"), o.optString("url", ""))
      }.filter { it.url.isNotBlank() }
    }.getOrDefault(emptyList())
  }

  private fun save(context: Context, cams: List<Camera>) {
    val arr = JSONArray()
    cams.forEach { c -> arr.put(JSONObject().put("id", c.id).put("name", c.name).put("url", c.url)) }
    prefs(context).edit().putString(KEY, arr.toString()).apply()
  }

  fun add(context: Context, name: String, url: String) {
    save(context, load(context) + Camera(System.currentTimeMillis(), name.ifBlank { "Camera" }, url.trim()))
  }

  fun remove(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })
}
