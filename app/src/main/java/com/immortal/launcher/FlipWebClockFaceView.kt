/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Date

/**
 * The flip clock, rendered by a full-screen [WebView] running `assets/faces/flip.html` — a
 * faithful Fliqlo recreation (HTML/CSS split-flap with the proper fold animation and brightness
 * fades) using the bundled, original `Flip-Regular` font we own. The page owns the whole frame
 * (opaque black background, like Fliqlo), self-ticks in JS, and reads its time format from the
 * URL — so [update] is a no-op and there's nothing to push from Kotlin. [fullBleed] tells
 * [FaceRenderer] to add it as a full-screen layer; the host consumes touch for tap-to-exit.
 */
class FlipWebClockFaceView(
    private val context: Context,
    private val spec: ClockSpec,
) : ClockFaceView {

  override val fullBleed: Boolean = true

  @SuppressLint("SetJavaScriptEnabled")
  private val web =
      WebView(context).apply {
        // Matches the page's slate backdrop so there's no black flash before it paints.
        setBackgroundColor(0xFF0F1012.toInt())
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // Touch is handled by the host (tap = exit, swipe = next); don't let the WebView eat it.
        setOnTouchListener { _, _ -> false }
        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true // the page persists its config via localStorage
          allowFileAccess = true
          allowFileAccessFromFileURLs = true
          // Honour the page's `width=device-width` viewport so the clock's auto-fit measures the
          // real screen and fills it, instead of the WebView's default ~980px layout width.
          useWideViewPort = true
          loadWithOverviewMode = true
        }
        webViewClient =
            object : WebViewClient() {
              override fun onReceivedError(
                  view: WebView,
                  request: android.webkit.WebResourceRequest,
                  error: android.webkit.WebResourceError,
              ) {
                android.util.Log.w(
                    "FlipWeb", "error ${error.errorCode} ${error.description} @ ${request.url}")
              }
            }
        loadUrl("file:///android_asset/faces/flip.html?tf=${tf()}&sc=${sc()}")
      }

  override val view: View = web

  override fun update(now: Date, blinkOn: Boolean) {
    // The HTML self-ticks; nothing to push from here.
  }

  override fun dispose() {
    runCatching {
      web.stopLoading()
      web.destroy()
    }
  }

  /** Fliqlo time-format code: 0 = 1-12 with AM/PM, 1 = 00-23, 2 = 0-23. */
  private fun tf(): Int = if (!spec.is24h) 0 else if (spec.leadingZero) 1 else 2

  /**
   * Clock scale as the page's 50–100 percent (the clock fills the frame edge-to-edge at 100).
   * Must be passed explicitly: the page clamps an unset value up from 0 to its 50% minimum, so
   * omitting it would leave the clock at half size. Callers ([FaceCatalog] size variants, the
   * night clock) pass a tuned value directly.
   */
  private fun sc(): Int = spec.sizeScale.coerceIn(50, 100)
}
