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
 * Storage for the phone-remote's user-defined **presets** — named macros the user builds
 * on the remote page and fires with one tap. A preset is `{id, name, steps[]}`; each step
 * is one action the remote can already perform:
 *
 *  - `{"type":"launch","packageName":"…"}`  — open an app
 *  - `{"type":"key","action":"home|back|power|apps"}` — a nav button
 *  - `{"type":"text","mode":"set|append|backspace|clear","text":"…"}` — edit the focused field
 *  - `{"type":"wait","ms":500}` — pause between steps
 *
 * Persisted as one JSON blob in its own prefs (mirrors [QuickBarConfig]/[RemotePairing]).
 * Execution lives in [RemoteRoutes] (it drives [RemoteApps]/[RemoteInput]); this object is
 * just the store. The step set is intentionally open-ended — a future `{"type":"config",…}`
 * step can push screensaver/calendar settings (the remote×fleet bridge) without changing
 * storage.
 */
object RemotePresets {
  private const val PREFS = "remote_presets"
  private const val KEY = "json"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** All presets as a JSON array (empty if none saved or the stored blob is corrupt). */
  fun listJson(c: Context): JSONArray =
      runCatching { JSONArray(prefs(c).getString(KEY, "[]")) }.getOrDefault(JSONArray())

  /** Replace the whole preset list (the editor saves the full set at once). */
  fun save(c: Context, presets: JSONArray) {
    prefs(c).edit().putString(KEY, presets.toString()).apply()
  }

  /** Find a preset by id, or null. */
  fun find(c: Context, id: String): JSONObject? {
    val arr = listJson(c)
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      if (o.optString("id") == id) return o
    }
    return null
  }
}
