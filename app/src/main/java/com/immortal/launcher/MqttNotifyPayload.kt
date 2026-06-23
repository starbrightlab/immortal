/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.json.JSONObject

/**
 * Schema and parser for the MQTT notify payload (see `docs/design/mqtt-notifications.md`).
 *
 * Payload arrives on `immortal/<id>/notify/set` as a JSON document; all fields are
 * optional and the parser is forgiving — malformed JSON, out-of-range numbers, and
 * unknown enums fall back to defaults rather than rejecting the whole message. An
 * empty payload (`{}` / `""`) returns null so the caller can no-op cleanly.
 *
 * Two production paths arrive here: Track 1 (HA `notify.send_message`) ships
 * `{"message": "..."}` via the entity's `command_template`; Track 2 (raw
 * `mqtt.publish`) ships the full schema. Same parsing path for both.
 */
data class NotifyPayload(
    val title: String,
    val message: String,
    val image: String?,
    val sound: String?,
    val position: Position,
    val durationSec: Int,
    val volume: Float,
    val wakeScreen: Boolean,
    val onTap: String?,
) {
  enum class Position {
    TOP,
    BOTTOM
  }

  /** True when the payload would render a toast (has any visible text). */
  val hasVisual: Boolean
    get() = title.isNotEmpty() || message.isNotEmpty()

  /** True when there's nothing to do — neither visual nor audio. */
  val isEmpty: Boolean
    get() = !hasVisual && sound.isNullOrBlank()

  companion object {
    const val DEFAULT_DURATION_SEC = 6
    const val DEFAULT_VOLUME = 1.0f

    /**
     * Parse a payload string. Returns null for empty/malformed/no-op payloads — the
     * caller treats both "couldn't parse" and "parsed to no-op" the same way. Out-of-range
     * `duration` / `volume` are clamped, unknown `position` falls back to BOTTOM.
     */
    fun parse(raw: String): NotifyPayload? {
      val trimmed = raw.trim()
      if (trimmed.isEmpty()) return null
      val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
      val payload =
          NotifyPayload(
              title = obj.optString("title", ""),
              message = obj.optString("message", ""),
              image = obj.optString("image", "").ifBlank { null },
              sound = obj.optString("sound", "").ifBlank { null },
              position =
                  when (obj.optString("position", "bottom").lowercase()) {
                    "top" -> Position.TOP
                    else -> Position.BOTTOM
                  },
              // `duration` is seconds. Negative → 0 (no auto-dismiss). HA may publish it
              // as a string via templated automations — accept either via optInt's coercion.
              durationSec = obj.optInt("duration", DEFAULT_DURATION_SEC).coerceAtLeast(0),
              // `volume` clamps to [0.0, 1.0]; the cap is also bounded by the user's
              // notification-stream slider at playback time (see SoundPlayer).
              volume =
                  obj.optDouble("volume", DEFAULT_VOLUME.toDouble())
                      .toFloat()
                      .coerceIn(0f, 1f),
              wakeScreen = obj.optBoolean("wake_screen", true),
              onTap = obj.optString("on_tap", "").ifBlank { null },
          )
      return if (payload.isEmpty) null else payload
    }
  }
}
