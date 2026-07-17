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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
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
 * Scene 1: breathing orb over a drifting starfield. The orb's colour, breath
 * rate and ornaments follow Star's live pipeline state, polled from the same
 * WebAPI endpoint the SPA uses (`GET /Star/live/status` — see
 * StarLiveController in stargazermi.com):
 *
 *   idle      — deep cyan, slow breath, quiet starfield
 *   listening — amber, quick breath, expanding ripple rings (mic is hot)
 *   thinking  — blue, restless breath, orbiting sparks
 *   speaking  — green, strong pulse
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
            detail = payload.optString("detail").ifBlank { null }
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

  Box(Modifier.fillMaxSize().background(Color(0xFF04060D))) {
    Canvas(Modifier.fillMaxSize()) {
      drawStarfield(stars, t)
      drawOrb(t, state, core)
    }
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

private fun DrawScope.drawOrb(t: Float, state: StarLive.State, core: Color) {
  val c = Offset(size.width / 2f, size.height / 2f)
  val base = min(size.width, size.height) * 0.16f

  // Breath: rate + depth per state.
  val (rate, depth) =
      when (state) {
        StarLive.State.IDLE -> 0.28f to 0.05f
        StarLive.State.LISTENING -> 0.75f to 0.09f
        StarLive.State.THINKING -> 1.30f to 0.06f
        StarLive.State.SPEAKING -> 2.10f to 0.13f
      }
  val breath = 1f + depth * sin(t * rate * 6.2832f)
  val r = base * breath

  // Outer glow — big soft radial gradient (cheap "bloom" for this GPU).
  drawCircle(
      brush =
          Brush.radialGradient(
              colors = listOf(core.copy(alpha = 0.28f), Color.Transparent),
              center = c,
              radius = r * 2.6f),
      radius = r * 2.6f,
      center = c)

  // Listening: expanding ripples — the honest "mic is hot" signal.
  if (state == StarLive.State.LISTENING) {
    for (i in 0 until 3) {
      val p = (t * 0.55f + i / 3f) % 1f
      drawCircle(
          color = core.copy(alpha = (1f - p) * 0.35f),
          radius = r * (1.05f + p * 1.6f),
          center = c,
          style = Stroke(width = 3f))
    }
  }

  // Thinking: three orbiting sparks.
  if (state == StarLive.State.THINKING) {
    for (i in 0 until 3) {
      val a = t * 1.6f + i * 2.0944f // 120° apart
      val or2 = r * 1.45f
      val p = Offset(c.x + or2 * cos(a), c.y + or2 * sin(a) * 0.55f)
      drawCircle(color = core.copy(alpha = 0.85f), radius = 7f, center = p)
      drawCircle(
          brush =
              Brush.radialGradient(
                  colors = listOf(core.copy(alpha = 0.5f), Color.Transparent),
                  center = p,
                  radius = 26f),
          radius = 26f,
          center = p)
    }
  }

  // Body: bright core falling off to the state colour.
  drawCircle(
      brush =
          Brush.radialGradient(
              colors = listOf(Color.White.copy(alpha = 0.95f), core, core.copy(alpha = 0f)),
              center = c.copy(y = c.y - r * 0.18f),
              radius = r * 1.35f),
      radius = r,
      center = c)

  // Rim ring with its own slow counter-breath.
  drawCircle(
      color = core.copy(alpha = 0.30f),
      radius = r * (1.18f - 0.03f * sin(t * rate * 6.2832f)),
      center = c,
      style = Stroke(width = 2.5f))

  drawFace(t, state, c, r)
}

// --- character face --------------------------------------------------------
//
// Eyes + mouth on the orb so it reads as a someone, not a lava lamp. All
// deterministic f(t) like the rest of the scene — no extra state.

/**
 * 1 = open, 0 = closed. A quick triangular close-open every ~4s, with a
 * per-cycle jitter (hash of the cycle index) so blinks don't feel metronomic.
 */
private fun eyeOpenness(t: Float): Float {
  val cycle = 4.1f
  val n = (t / cycle).toInt()
  val jitter = ((n * 7919) % 13) / 13f * 1.4f
  val p = t - n * cycle - jitter
  if (p < 0f || p > 0.24f) return 1f
  return abs(p / 0.12f - 1f)
}

private fun DrawScope.drawFace(t: Float, state: StarLive.State, c: Offset, r: Float) {
  val ink = Color(0xFF071120) // dark navy — reads crisply on the bright core
  val open = eyeOpenness(t)

  // Gaze: idle wanders dreamily, thinking looks up and away, others hold centre.
  val (gx, gy) =
      when (state) {
        StarLive.State.IDLE -> r * 0.07f * sin(t * 0.33f) to r * 0.05f * sin(t * 0.21f + 1.3f)
        StarLive.State.THINKING -> r * 0.13f to -r * 0.11f
        else -> 0f to 0f
      }

  val eyeDX = r * 0.36f
  val eyeY = c.y - r * 0.16f + gy
  val eyeW = r * 0.15f
  val eyeHBase =
      when (state) {
        StarLive.State.LISTENING -> eyeW * 1.6f // wide — attentive
        StarLive.State.THINKING -> eyeW * 0.95f // slight squint
        else -> eyeW * 1.3f
      }

  if (state == StarLive.State.SPEAKING && open > 0.6f) {
    // Happy squint: ∩-shaped arcs while talking.
    for (sgn in intArrayOf(-1, 1)) {
      val ex = c.x + sgn * eyeDX + gx
      drawArc(
          color = ink,
          startAngle = 180f,
          sweepAngle = 180f,
          useCenter = false,
          topLeft = Offset(ex - eyeW, eyeY - eyeW * 0.7f),
          size = Size(eyeW * 2f, eyeW * 1.4f),
          style = Stroke(width = r * 0.045f, cap = StrokeCap.Round))
    }
  } else {
    val eyeH = (eyeHBase * open).coerceAtLeast(r * 0.015f)
    for (sgn in intArrayOf(-1, 1)) {
      val ex = c.x + sgn * eyeDX + gx
      drawOval(
          color = ink,
          topLeft = Offset(ex - eyeW / 2f, eyeY - eyeH / 2f),
          size = Size(eyeW, eyeH))
      // Catchlight — the tiny glint that keeps the eyes alive.
      if (open > 0.5f)
          drawCircle(
              color = Color.White.copy(alpha = 0.85f),
              radius = eyeW * 0.14f,
              center = Offset(ex + eyeW * 0.16f, eyeY - eyeH * 0.22f))
    }
  }

  val mouthY = c.y + r * 0.38f
  when (state) {
    StarLive.State.SPEAKING -> {
      // Talking: mouth height oscillates.
      val mh = r * (0.05f + 0.16f * abs(sin(t * 9f)))
      drawOval(
          color = ink,
          topLeft = Offset(c.x - r * 0.16f, mouthY - mh / 2f),
          size = Size(r * 0.32f, mh))
    }
    StarLive.State.LISTENING -> {
      // Small "o" — quietly taking it in.
      drawCircle(
          color = ink,
          radius = r * 0.06f,
          center = Offset(c.x, mouthY),
          style = Stroke(width = r * 0.035f))
    }
    StarLive.State.THINKING -> {
      // Slanted hmm-line.
      drawLine(
          color = ink,
          start = Offset(c.x - r * 0.12f, mouthY + r * 0.02f),
          end = Offset(c.x + r * 0.12f, mouthY - r * 0.02f),
          strokeWidth = r * 0.04f,
          cap = StrokeCap.Round)
    }
    StarLive.State.IDLE -> {
      // Soft resting smile.
      drawArc(
          color = ink,
          startAngle = 25f,
          sweepAngle = 130f,
          useCenter = false,
          topLeft = Offset(c.x - r * 0.18f, mouthY - r * 0.16f),
          size = Size(r * 0.36f, r * 0.22f),
          style = Stroke(width = r * 0.04f, cap = StrokeCap.Round))
    }
  }
}
