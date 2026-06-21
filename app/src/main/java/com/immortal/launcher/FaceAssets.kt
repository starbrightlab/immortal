/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * Resolves face assets (fonts today; widget components / imagery later) by id, with a fixed
 * lookup order so the renderer never assumes a bundled file:
 *
 * ```
 *   1. bundled   assets/fonts/…              (shipped in the APK)
 *   2. cache     filesDir/face-assets/…      (previously downloaded)
 *   3. remote    Supabase Storage            (fetch, cache, then use)  ← built later
 * ```
 *
 * Tiers 1–2 are implemented now; tier 3 is a stub. A new Pro face pack will be able to ship a
 * font the installed APK has never seen without an app update — the descriptor and renderer
 * don't change when tier 3 lands, the resolver just gains a fallback. A missing asset always
 * degrades gracefully (nearest system typeface) rather than crashing or blanking the frame.
 */
class AssetResolver(private val context: Context) {

  private val cacheDir: File by lazy { File(context.filesDir, "face-assets").apply { mkdirs() } }
  private val typefaceCache = HashMap<String, Typeface>()

  /**
   * Resolve a font family name (a mantelframe `clockFont` value, or [Face.FONT_SANS_LIGHT])
   * to a [Typeface], applying [weight] as a faux-bold hint where the platform can't load a
   * true weight. Always returns *something* — falls back to the system default.
   */
  fun font(name: String, weight: Int = 400): Typeface {
    typefaceCache[name]?.let {
      return it
    }
    val tf = loadFont(name) ?: systemFallback(name, weight)
    typefaceCache[name] = tf
    return tf
  }

  private fun loadFont(name: String): Typeface? {
    // Sentinel: the original overlay's light system face, no TTF needed.
    if (name == Face.FONT_SANS_LIGHT) return Typeface.create("sans-serif-light", Typeface.NORMAL)

    val file = fontFileName(name)

    // Tier 1 — bundled in assets/fonts/.
    runCatching { return Typeface.createFromAsset(context.assets, "fonts/$file") }
        .onFailure { /* not bundled; fall through */ }

    // Tier 2 — previously downloaded into the on-device cache.
    val cached = File(cacheDir, file)
    if (cached.exists()) {
      runCatching { return Typeface.createFromFile(cached) }
          .onFailure { Log.w(TAG, "cached font unreadable: $file", it) }
    }

    // Tier 3 — remote (Supabase Storage). Not yet built; see doc § Asset resolution.
    // fetchRemoteFontInto(cached, name)?.let { return it }

    return null
  }

  /** Map a mantelframe font family to its bundled TTF filename. */
  private fun fontFileName(name: String): String = name.replace(" ", "") + ".ttf"

  /** Closest system typeface when a named family isn't available anywhere. */
  private fun systemFallback(name: String, weight: Int): Typeface {
    val base =
        when (name) {
          "Courier New" -> Typeface.MONOSPACE
          "Georgia",
          "Playfair Display" -> Typeface.SERIF
          else -> Typeface.SANS_SERIF
        }
    // Approximate weight: 600+ reads as bold on devices without the true cut.
    val style = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
    return Typeface.create(base, style)
  }

  private companion object {
    const val TAG = "FaceAssets"
  }
}
