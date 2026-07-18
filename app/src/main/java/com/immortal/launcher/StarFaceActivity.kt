/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Star's native face — the embodiment counterpart to the SPA's StarBreathing
 * page, rendered in Compose so the Portal draws it at a smooth frame rate with
 * no WebView in the loop.
 *
 * Scene 2: the crystal-star character art (same images as the SPA's
 * StarBreathing page) breathing over a drifting starfield. Which portrait is
 * shown follows Star's live pipeline state, polled from the same WebAPI
 * endpoint the SPA uses (`GET /Star/live/status` — see StarLiveController in
 * stargazermi.com):
 *
 *   idle      — calm portrait, slow breath, quiet starfield
 *   listening — calm portrait, quick breath, amber ripple rings (mic is hot)
 *   thinking  — orbit-ring portrait, restless breath
 *   speaking  — open-mouth portrait, strong pulse
 *
 * The API base is configurable over the fleet channel:
 *   POST /config { "set": { "star.api": "http://192.168.1.158:5205" } }
 *
 * Launched remotely via fleet `POST /action { "action": "starface" }`.
 */
class StarFaceActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    var state by mutableStateOf(StarLive.State.IDLE)
    var detail by mutableStateOf<String?>(null)

    lifecycleScope.launch {
      val api = FleetConfig.getValue(this@StarFaceActivity, "star.api") ?: StarLive.DEFAULT_API
      var lastTs = ""
      while (isActive) {
        val payload = withContext(Dispatchers.IO) { StarLive.fetchStatus(api) }
        if (payload != null) {
          val ts = payload.optString("timestamp")
          if (ts.isEmpty() || ts != lastTs) {
            lastTs = ts
            state = StarLive.State.parse(payload.optString("state"))
            // optString turns a JSON null into the literal string "null"
            detail =
                if (payload.isNull("detail")) null
                else payload.optString("detail").ifBlank { null }
          }
        }
        delay(POLL_MS)
      }
    }

    setContent { StarFaceScreen(state, detail) }
  }

  private companion object {
    const val POLL_MS = 1000L
  }
}

/** Shared bits of the live-state pipeline (poller + state enum). */
object StarLive {
  const val DEFAULT_API = "http://192.168.1.158:5205"

  enum class State {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING;

    companion object {
      fun parse(s: String?): State =
          when (s?.lowercase()) {
            "listening" -> LISTENING
            "thinking" -> THINKING
            "speaking" -> SPEAKING
            else -> IDLE
          }
    }
  }

  fun fetchStatus(apiBase: String): JSONObject? =
      runCatching {
            val conn = URL("$apiBase/Star/live/status").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            try {
              if (conn.responseCode != 200) return@runCatching null
              JSONObject(conn.inputStream.bufferedReader().readText())
            } finally {
              conn.disconnect()
            }
          }
          .getOrNull()
}

// --- air quality -----------------------------------------------------------

/** One location's current reading from Open-Meteo's keyless air-quality API. */
data class AqiReading(val label: String, val aqi: Int, val pm25: Int)

object AirQuality {
  // Same two spots as the SPA's AirQualityCard and the wake-turn weather
  // context: home and the beach house.
  private val SPOTS =
      listOf(
          Triple("Lehman PA", 41.32, -76.02),
          Triple("Lewes DE", 38.77, -75.14))

  fun fetch(): List<AqiReading> =
      SPOTS.mapNotNull { (label, lat, lon) ->
        runCatching<AqiReading?> {
              val url =
                  URL(
                      "https://air-quality-api.open-meteo.com/v1/air-quality" +
                          "?latitude=$lat&longitude=$lon" +
                          "&current=us_aqi,pm2_5&timezone=America%2FNew_York")
              val conn = url.openConnection() as HttpURLConnection
              conn.connectTimeout = 5000
              conn.readTimeout = 5000
              try {
                if (conn.responseCode != 200) {
                  android.util.Log.w("StarFaceAqi", "$label: HTTP ${conn.responseCode}")
                  return@runCatching null
                }
                val cur =
                    JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONObject("current") ?: return@runCatching null
                AqiReading(
                    label,
                    cur.optDouble("us_aqi", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        ?: return@runCatching null,
                    cur.optDouble("pm2_5", 0.0).toInt())
              } finally {
                conn.disconnect()
              }
            }
            .getOrNull()
      }

  fun bandColor(aqi: Int): Color =
      when {
        aqi <= 50 -> Color(0xFF4ADE80)
        aqi <= 100 -> Color(0xFFFACC15)
        aqi <= 150 -> Color(0xFFFB923C)
        aqi <= 200 -> Color(0xFFF87171)
        aqi <= 300 -> Color(0xFFC084FC)
        else -> Color(0xFFE879F9)
      }

  fun bandName(aqi: Int): String =
      when {
        aqi <= 50 -> "good"
        aqi <= 100 -> "moderate"
        aqi <= 150 -> "unhealthy (sensitive)"
        aqi <= 200 -> "unhealthy"
        aqi <= 300 -> "very unhealthy"
        else -> "hazardous"
      }
}

// --- scene ---------------------------------------------------------------

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float)

private fun makeStarfield(n: Int, seed: Int): List<Star> {
  val rnd = Random(seed)
  return List(n) {
    Star(
        x = rnd.nextFloat(),
        y = rnd.nextFloat(),
        r = 0.6f + rnd.nextFloat() * 1.8f,
        phase = rnd.nextFloat() * 6.2832f,
        speed = 0.25f + rnd.nextFloat() * 0.9f)
  }
}

@Composable
fun StarFaceScreen(state: StarLive.State, detail: String?) {
  // Single time driver for the whole scene — everything is drawn as f(t).
  var t by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(Unit) {
    var start = -1L
    while (isActive) {
      androidx.compose.runtime.withFrameNanos { now ->
        if (start < 0) start = now
        t = (now - start) / 1e9f
      }
    }
  }

  val stars = remember { makeStarfield(140, seed = 7) }

  val idleArt = ImageBitmap.imageResource(R.drawable.star_idle)
  val thinkingArt = ImageBitmap.imageResource(R.drawable.star_thinking)
  val speakingArt = ImageBitmap.imageResource(R.drawable.star_speaking)
  val art =
      when (state) {
        StarLive.State.THINKING -> thinkingArt
        StarLive.State.SPEAKING -> speakingArt
        else -> idleArt // listening reuses the calm portrait + ripple rings
      }

  // Crossfade: the incoming portrait ramps up over the outgoing one, driven
  // off the same clock as everything else.
  var shown by remember { mutableStateOf(art) }
  var previous by remember { mutableStateOf<ImageBitmap?>(null) }
  var fadeStart by remember { mutableFloatStateOf(-10f) }
  if (art !== shown) {
    previous = shown
    shown = art
    fadeStart = t
  }
  val fade = ((t - fadeStart) / 0.65f).coerceIn(0f, 1f)
  if (fade >= 1f && previous != null) previous = null

  val core by
      animateColorAsState(
          targetValue =
              when (state) {
                StarLive.State.IDLE -> Color(0xFF22D3EE) // cyan
                StarLive.State.LISTENING -> Color(0xFFF59E0B) // amber
                StarLive.State.THINKING -> Color(0xFF3B82F6) // blue
                StarLive.State.SPEAKING -> Color(0xFF10B981) // green
              },
          animationSpec = tween(1100),
          label = "core")

  // Upper-right clock + date, ticking once a second.
  var clock by remember { mutableStateOf(clockNow()) }
  LaunchedEffect(Unit) {
    while (isActive) {
      clock = clockNow()
      delay(1000)
    }
  }

  // Upper-left air quality, refreshed every 30 min (Open-Meteo caps free
  // usage generously; two calls per refresh is nothing). Until the first
  // successful fetch, retry every 30s so a transient network hiccup at
  // launch doesn't blank the widget for half an hour.
  var aqi by remember { mutableStateOf<List<AqiReading>>(emptyList()) }
  LaunchedEffect(Unit) {
    while (isActive) {
      val readings = withContext(Dispatchers.IO) { AirQuality.fetch() }
      android.util.Log.i("StarFaceAqi", "fetched ${readings.size} readings: " +
          readings.joinToString { "${it.label}=${it.aqi}" })
      if (readings.isNotEmpty()) aqi = readings
      delay(if (aqi.isEmpty()) 30 * 1000L else 30 * 60 * 1000L)
    }
  }

  Box(Modifier.fillMaxSize().background(Color(0xFF04060D))) {
    Canvas(Modifier.fillMaxSize()) {
      drawStarfield(stars, t)
      drawPortrait(t, state, shown, previous, fade, core)
    }
    if (aqi.isNotEmpty()) {
      androidx.compose.foundation.layout.Column(
          modifier = Modifier.align(Alignment.TopStart).padding(top = 24.dp, start = 30.dp)) {
        Text(
            text = "AIR QUALITY",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp)
        for (r in aqi) {
          Text(
              text = "${r.aqi}  ${r.label}",
              color = AirQuality.bandColor(r.aqi).copy(alpha = 0.90f),
              fontSize = 40.sp,
              fontWeight = FontWeight.Light,
              letterSpacing = 1.sp,
              modifier = Modifier.padding(top = 12.dp))
          Text(
              text = "${AirQuality.bandName(r.aqi)} · pm2.5 ${r.pm25}",
              color = Color.White.copy(alpha = 0.45f),
              fontSize = 18.sp,
              fontWeight = FontWeight.Light,
              letterSpacing = 1.5.sp)
        }
      }
    }
    Text(
        text = clock.first,
        color = Color.White.copy(alpha = 0.78f),
        fontSize = 46.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 2.sp,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 30.dp))
    Text(
        text = clock.second,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 18.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 2.sp,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 82.dp, end = 31.dp))
    Text(
        text = buildString {
          append(
              when (state) {
                StarLive.State.IDLE -> "star"
                StarLive.State.LISTENING -> "listening"
                StarLive.State.THINKING -> "thinking"
                StarLive.State.SPEAKING -> "speaking"
              })
          if (!detail.isNullOrBlank() && state != StarLive.State.IDLE) {
            append("  ·  ")
            append(detail.take(80))
          }
        },
        color = core.copy(alpha = 0.55f),
        fontSize = 20.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 3.sp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
  }
}

/** ("6:32 PM", "Fri · Jul 17") in the device's local timezone. */
private fun clockNow(): Pair<String, String> {
  val now = java.util.Calendar.getInstance()
  val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(now.time)
  val date = java.text.SimpleDateFormat("EEE · MMM d", java.util.Locale.US).format(now.time)
  return time to date
}

private fun DrawScope.drawStarfield(stars: List<Star>, t: Float) {
  val w = size.width
  val h = size.height
  for (s in stars) {
    // Slow horizontal drift with wraparound; gentle twinkle.
    val x = ((s.x + t * 0.004f * s.speed) % 1f) * w
    val y = s.y * h
    val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * s.speed + s.phase))
    drawCircle(
        color = Color.White.copy(alpha = 0.10f + 0.45f * tw), radius = s.r, center = Offset(x, y))
  }
}


// --- portrait --------------------------------------------------------------
//
// The artwork is 1024x1024 RGB on a near-black background. Drawing it with
// BlendMode.Screen makes black contribute nothing, so the square edge
// disappears into the scene and the starfield shows through around the star.

private fun DrawScope.drawPortrait(
    t: Float,
    state: StarLive.State,
    shown: ImageBitmap,
    previous: ImageBitmap?,
    fade: Float,
    core: Color,
) {
  val c = Offset(size.width / 2f, size.height / 2f)

  // Breath: rate + depth per state (same feel as the old orb).
  val (rate, depth) =
      when (state) {
        StarLive.State.IDLE -> 0.28f to 0.020f
        StarLive.State.LISTENING -> 0.75f to 0.030f
        StarLive.State.THINKING -> 1.30f to 0.022f
        StarLive.State.SPEAKING -> 2.10f to 0.045f
      }
  val breath = 1f + depth * sin(t * rate * 6.2832f)
  val side = min(size.width, size.height) * 0.82f * breath
  val bob = min(size.width, size.height) * 0.008f * sin(t * 0.45f)
  val dst = IntOffset((c.x - side / 2f).toInt(), (c.y - side / 2f + bob).toInt())
  val dstSize = IntSize(side.toInt(), side.toInt())

  if (previous != null && fade < 1f) {
    drawImage(
        image = previous,
        dstOffset = dst,
        dstSize = dstSize,
        alpha = 1f - fade,
        blendMode = BlendMode.Screen,
        filterQuality = FilterQuality.High)
  }
  drawImage(
      image = shown,
      dstOffset = dst,
      dstSize = dstSize,
      alpha = if (previous != null) fade else 1f,
      blendMode = BlendMode.Screen,
      filterQuality = FilterQuality.High)

  // Listening: expanding amber ripples — the honest "mic is hot" signal.
  if (state == StarLive.State.LISTENING) {
    val r0 = side * 0.34f
    for (i in 0 until 3) {
      val p = (t * 0.55f + i / 3f) % 1f
      drawCircle(
          color = core.copy(alpha = (1f - p) * 0.35f),
          radius = r0 * (1.05f + p * 1.1f),
          center = c,
          style = Stroke(width = 3f))
    }
  }
}
