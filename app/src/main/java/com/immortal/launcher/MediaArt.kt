/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.net.URL

/**
 * Resolve album art that a media session hands over as a **URI** rather than an embedded bitmap.
 * Shared by everything that shows now-playing cover art — the screensaver card, the home-header
 * mini-player, and the phone remote — so they all behave the same: prefer the in-memory bitmap,
 * and otherwise resolve the URI (a device-local `content://`/`file://` only this app can read, or
 * an `http(s)://` URL). Best-effort, off the main thread; any failure returns null (placeholder).
 */
object MediaArt {
  /** Cap on the re-decoded edge — enough for a header thumb or a frame card, cheap to decode. */
  private const val MAX_EDGE = 384

  /** Load and downscale the art at [url]. Returns null for a blank URL or any read/decode failure. */
  fun resolveUri(context: Context, url: String): Bitmap? {
    if (url.isBlank()) return null
    return runCatching {
          val bytes =
              if (url.startsWith("http://") || url.startsWith("https://")) {
                val conn = URL(url).openConnection()
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.getInputStream().use { it.readBytes() }
              } else {
                // content://, file://, android.resource:// — device-local, only this app can read it.
                context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
              }
          bytes?.let { decodeDownscaled(it) }
        }
        .getOrNull()
  }

  /** Decode [bytes] with an inSampleSize that keeps the longest edge near [MAX_EDGE]. */
  private fun decodeDownscaled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    val opts =
        BitmapFactory.Options().apply {
          inSampleSize = if (longest > MAX_EDGE) Integer.highestOneBit(longest / MAX_EDGE) else 1
        }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
  }
}
