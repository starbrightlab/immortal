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
import org.json.JSONObject

/**
 * The flip clock, rendered by a transparent full-screen [WebView] running
 * `assets/faces/flip.html` — a CSS/3D port of the Fliqlo screensaver. CSS owns the split-flap
 * fold (the right tool for it) and the Fliqlo font renders the digits exactly, so this matches
 * the reference instead of re-deriving it in Android Views.
 *
 * The WebView is transparent and non-interactive (the host consumes touch at the window level),
 * positions the clock itself from the face's grid position, and self-ticks in JS — so [update]
 * is a no-op. [fullBleed] tells [FaceRenderer] to add it as a full-screen layer.
 */
class FlipWebClockFaceView(
    private val context: Context,
    private val spec: ClockSpec,
) : ClockFaceView {

  override val fullBleed: Boolean = true

  @SuppressLint("SetJavaScriptEnabled")
  private val web =
      WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // Touch is handled by the host (tap = exit, swipe = next); don't let the WebView eat it.
        setOnTouchListener { _, _ -> false }
        settings.apply {
          javaScriptEnabled = true
          allowFileAccess = true
          // The page is file://; allow it to load the bundled @font-face from file:// too.
          allowFileAccessFromFileURLs = true
          allowUniversalAccessFromFileURLs = true
        }
        webViewClient =
            object : WebViewClient() {
              override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("startClock(${configJson()})", null)
              }

              override fun onReceivedError(
                  view: WebView,
                  request: android.webkit.WebResourceRequest,
                  error: android.webkit.WebResourceError,
              ) {
                android.util.Log.w(
                    "FlipWeb", "error ${error.errorCode} ${error.description} @ ${request.url}")
              }
            }
        loadUrl("file:///android_asset/faces/flip.html")
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

  private fun configJson(): String {
    // Fliqlo is grey; honour an explicit non-default colour from the face.
    val color = if (spec.color.equals("#ffffff", true)) "#b9b9b9" else spec.color
    return JSONObject()
        .put("format12", !spec.is24h)
        .put("showSeconds", spec.showSeconds)
        .put("showAmPm", spec.showAmPm)
        .put("leadingZero", spec.leadingZero)
        .put("color", color)
        .put("scale", spec.sizeScale)
        .put("position", spec.position.wire())
        .toString()
  }
}

/** Wire-format (kebab) name for a grid position, matching the HTML/face JSON. */
fun GridPosition.wire(): String =
    when (this) {
      GridPosition.TOP_LEFT -> "top-left"
      GridPosition.TOP_CENTER -> "top-center"
      GridPosition.TOP_RIGHT -> "top-right"
      GridPosition.MIDDLE_LEFT -> "middle-left"
      GridPosition.MIDDLE_CENTER -> "middle-center"
      GridPosition.MIDDLE_RIGHT -> "middle-right"
      GridPosition.BOTTOM_LEFT -> "bottom-left"
      GridPosition.BOTTOM_CENTER -> "bottom-center"
      GridPosition.BOTTOM_RIGHT -> "bottom-right"
    }
