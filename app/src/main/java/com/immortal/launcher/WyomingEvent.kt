/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject

/**
 * One event of the Wyoming protocol (https://github.com/rhasspy/wyoming), the JSONL + PCM
 * protocol Home Assistant speaks to voice satellites. On the wire an event is:
 *
 * ```
 * {"type": "...", "data": {...}, "data_length": N, "payload_length": M}\n
 * <N bytes of extra JSON data, merged over "data">
 * <M bytes of binary payload, typically PCM audio>
 * ```
 *
 * Pure: reads from / writes to plain streams, so it is unit-tested without a socket.
 */
data class WyomingEvent(
    val type: String,
    val data: JSONObject = JSONObject(),
    val payload: ByteArray? = null,
) {
  /** Serialise to [out]. Data goes inline in the header; the payload (if any) follows it. */
  fun write(out: OutputStream) {
    val header = JSONObject().put("type", type).put("version", PROTOCOL_VERSION)
    if (data.length() > 0) header.put("data", data)
    if (payload != null && payload.isNotEmpty()) header.put("payload_length", payload.size)
    out.write(header.toString().toByteArray(Charsets.UTF_8))
    out.write('\n'.code)
    if (payload != null && payload.isNotEmpty()) out.write(payload)
    out.flush()
  }

  override fun equals(other: Any?): Boolean =
      other is WyomingEvent &&
          type == other.type &&
          data.toString() == other.data.toString() &&
          (payload ?: EMPTY).contentEquals(other.payload ?: EMPTY)

  override fun hashCode(): Int = type.hashCode()

  companion object {
    const val PROTOCOL_VERSION = "1.5.4"
    private val EMPTY = ByteArray(0)
    /** A header line longer than this is not a Wyoming peer; refuse rather than buffer it. */
    private const val MAX_HEADER = 64 * 1024
    private const val MAX_PAYLOAD = 8 * 1024 * 1024

    /**
     * Read the next event from [input], or null at a clean end of stream. Throws [IOException]
     * on a truncated or malformed event, so the caller drops the connection.
     */
    fun read(input: InputStream): WyomingEvent? {
      val line = readLine(input) ?: return null
      val header = runCatching { JSONObject(line) }.getOrElse { throw IOException("bad header") }
      val type = header.optString("type").ifBlank { throw IOException("event without type") }
      val data = header.optJSONObject("data") ?: JSONObject()
      val dataLength = header.optInt("data_length", 0)
      if (dataLength > 0) {
        val extra = JSONObject(String(readExactly(input, dataLength), Charsets.UTF_8))
        extra.keys().forEach { k -> data.put(k, extra.get(k)) }
      }
      val payloadLength = header.optInt("payload_length", 0)
      if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) throw IOException("bad payload length")
      val payload = if (payloadLength > 0) readExactly(input, payloadLength) else null
      return WyomingEvent(type, data, payload)
    }

    private fun readLine(input: InputStream): String? {
      val buf = ByteArrayOutputStream()
      while (true) {
        val b = input.read()
        if (b < 0) {
          if (buf.size() == 0) return null
          throw IOException("truncated header")
        }
        if (b == '\n'.code) return String(buf.toByteArray(), Charsets.UTF_8)
        buf.write(b)
        if (buf.size() > MAX_HEADER) throw IOException("header too long")
      }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
      val out = ByteArray(n)
      var off = 0
      while (off < n) {
        val r = input.read(out, off, n - off)
        if (r < 0) throw IOException("truncated event")
        off += r
      }
      return out
    }

    /** `audio-start` / `audio-chunk` format fields: 16-bit mono at [rate]. */
    fun audioFormat(rate: Int, width: Int = 2, channels: Int = 1): JSONObject =
        JSONObject().put("rate", rate).put("width", width).put("channels", channels)
  }
}
