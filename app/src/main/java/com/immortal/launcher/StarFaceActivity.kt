/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.ui.graphics.asImageBitmap
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

    // Load persisted Spotify refresh token on cold start.
    SpotifyState.loadFromFleet(this)
    handleMaybeSpotifyRedirect(intent)

    var state by mutableStateOf(StarLive.State.IDLE)
    var detail by mutableStateOf<String?>(null)
    var apiBase by mutableStateOf(StarLive.DEFAULT_API)

    lifecycleScope.launch {
      val api = FleetConfig.getValue(this@StarFaceActivity, "star.api") ?: StarLive.DEFAULT_API
      apiBase = api
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

    val onTapWake: () -> Unit = {
      lifecycleScope.launch {
        val ok = withContext(Dispatchers.IO) { StarLive.manualWake(apiBase) }
        android.util.Log.i("StarFace", "tap-to-wake -> $apiBase ok=$ok")
      }
    }
    val onEnroll: (String) -> Unit = { name ->
      lifecycleScope.launch {
        val ok = withContext(Dispatchers.IO) { StarLive.manualEnroll(apiBase, name) }
        android.util.Log.i("StarFace", "enroll $name -> ok=$ok")
      }
    }

    setContent { StarShellScreen(state, detail, onTapWake, onEnroll) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleMaybeSpotifyRedirect(intent)
  }

  private fun handleMaybeSpotifyRedirect(intent: Intent?) {
    val data = intent?.data ?: return
    if (data.scheme != "stargazer-star" || data.host != "spotify-callback") return
    val code = data.getQueryParameter("code")
    val state = data.getQueryParameter("state")
    val err = data.getQueryParameter("error")
    if (err != null) {
      android.util.Log.w("StarFace", "spotify redirect error: $err")
      SpotifyState.error = err
      SpotifyState.connecting = false
      return
    }
    val verifier = SpotifyState.pendingVerifier
    if (code == null || verifier == null || state != SpotifyState.pendingState) {
      android.util.Log.w("StarFace", "spotify redirect mismatched (code=${code != null} state-ok=${state == SpotifyState.pendingState})")
      SpotifyState.error = "callback_mismatch"
      SpotifyState.connecting = false
      return
    }
    val clientId = FleetConfig.getValue(this, "star.spotify.client_id") ?: ""
    if (clientId.isEmpty()) {
      SpotifyState.error = "missing_client_id"
      SpotifyState.connecting = false
      return
    }
    SpotifyState.connecting = true
    lifecycleScope.launch {
      val tokens = withContext(Dispatchers.IO) {
        SpotifyClient.exchangeCode(clientId, code, verifier)
      }
      SpotifyState.pendingVerifier = null
      SpotifyState.pendingState = null
      if (tokens == null) {
        SpotifyState.error = "token_exchange_failed"
        SpotifyState.connecting = false
        return@launch
      }
      SpotifyState.applyTokens(this@StarFaceActivity, tokens)
      SpotifyState.error = null
      SpotifyState.connecting = false
      android.util.Log.i("StarFace", "spotify connected")
    }
  }

  private companion object {
    const val POLL_MS = 1000L
  }
}

/** Compose-observable Spotify state shared between the activity (intent handler)
 *  and the panel (UI). Refresh token persists in FleetConfig; access token is
 *  in-memory only and refreshed on demand. */
object SpotifyState {
  var refreshToken by mutableStateOf<String?>(null)
  var accessToken: String? = null
  var accessExpiresAt: Long = 0L
  var pendingVerifier: String? = null
  var pendingState: String? = null
  var connecting by mutableStateOf(false)
  var error by mutableStateOf<String?>(null)

  val connected: Boolean get() = !refreshToken.isNullOrEmpty()

  fun loadFromFleet(ctx: Context) {
    val stored = FleetConfig.getValue(ctx, "star.spotify.refresh_token")
    refreshToken = if (stored.isNullOrEmpty()) null else stored
  }

  fun applyTokens(ctx: Context, tokens: SpotifyClient.Tokens) {
    accessToken = tokens.accessToken
    accessExpiresAt = tokens.expiresAt
    refreshToken = tokens.refreshToken
    FleetConfig.setValue(ctx, "star.spotify.refresh_token", tokens.refreshToken)
  }

  fun clear(ctx: Context) {
    accessToken = null
    accessExpiresAt = 0L
    refreshToken = null
    FleetConfig.setValue(ctx, "star.spotify.refresh_token", "")
  }

  /** Returns a currently-valid access token, refreshing if needed. Null if
   *  we're not connected or refresh failed. */
  suspend fun getAccessToken(ctx: Context): String? {
    val access = accessToken
    if (access != null && System.currentTimeMillis() < accessExpiresAt) return access
    val refresh = refreshToken ?: return null
    val clientId = FleetConfig.getValue(ctx, "star.spotify.client_id") ?: return null
    val tokens = withContext(Dispatchers.IO) { SpotifyClient.refresh(clientId, refresh) }
        ?: return null
    applyTokens(ctx, tokens)
    return tokens.accessToken
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

  /** Tap-to-wake — hits `POST $apiBase/Star/live/manual-wake` which the WebAPI
   *  proxies to star-wake on localhost. Kept behind the WebAPI so the Portal
   *  only needs :5205 open through the NUC firewall. */
  fun manualWake(apiBase: String): Boolean = postJson("$apiBase/Star/live/manual-wake", "{}")

  /** Long-press enroll picker — pass "clear" to sign out. */
  fun manualEnroll(apiBase: String, name: String): Boolean {
    val body = "{\"name\":\"${name.replace("\"", "\\\"")}\"}"
    return postJson("$apiBase/Star/live/manual-enroll", body)
  }

  private fun postJson(url: String, body: String): Boolean {
    val bytes = body.toByteArray()
    return try {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.doInput = true
      conn.useCaches = false
      conn.connectTimeout = 2500
      conn.readTimeout = 4000
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      try {
        val rc = conn.responseCode
        android.util.Log.i("StarFace", "POST $url -> $rc")
        rc == 200
      } finally {
        conn.disconnect()
      }
    } catch (ex: Throwable) {
      android.util.Log.w("StarFace", "POST $url failed: $ex")
      false
    }
  }
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

private val SPEAKER_ROSTER = listOf(
    "Steve", "Noell", "Andy", "Sam", "Jack", "Richie", "Mandy", "River",
)

/** The five modes on the right-edge wheel. Order = draw order top-to-bottom. */
private enum class StarMode(val icon: String, val label: String) {
  STAR("✨", "Star"),
  SPOTIFY("🎵", "Spotify"),
  PHOTOS("🖼", "Photos"),
  BROWSER("🌐", "Browser"),
  SETTINGS("⚙", "Settings"),
}

/** Shell composable — owns the mode-wheel state and routes to per-mode content.
 *  The wheel floats on the right and is visible in every mode so the user can
 *  always jump between them. StarFace's own gestures live behind it, so the
 *  wheel column doesn't intercept taps meant for the star face. */
@Composable
fun StarShellScreen(
    state: StarLive.State,
    detail: String?,
    onTapWake: () -> Unit = {},
    onEnroll: (String) -> Unit = {},
) {
  var mode by remember { mutableStateOf(StarMode.STAR) }
  Box(Modifier.fillMaxSize().background(Color(0xFF04060D))) {
    when (mode) {
      StarMode.STAR -> StarPresenceScreen(state, detail, onTapWake, onEnroll)
      StarMode.SPOTIFY -> SpotifyPanel()
      StarMode.PHOTOS -> PhotosPanel()
      StarMode.BROWSER -> BrowserPanel()
      StarMode.SETTINGS -> SettingsPanel()
    }
    ModeWheel(
        current = mode,
        onSelect = { mode = it },
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
    )
  }
}

@Composable
private fun ModeWheel(
    current: StarMode,
    onSelect: (StarMode) -> Unit,
    modifier: Modifier = Modifier,
) {
  // Rounded pill rail behind all buttons — makes the wheel feel like one
  // control instead of five floating dots.
  Column(
      modifier = modifier
          .clip(RoundedCornerShape(48.dp))
          .background(Color(0xFF0B1220).copy(alpha = 0.55f))
          .padding(vertical = 14.dp, horizontal = 10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    for (m in StarMode.entries) {
      val selected = m == current
      Row(verticalAlignment = Alignment.CenterVertically) {
        // Left-side accent bar for the active mode.
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(if (selected) 60.dp else 0.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF22D3EE)))
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Box(
              modifier = Modifier
                  .size(84.dp)
                  .clip(CircleShape)
                  .background(
                      if (selected) Color(0xFF22D3EE).copy(alpha = 0.22f)
                      else Color.Transparent)
                  .clickable { onSelect(m) },
              contentAlignment = Alignment.Center,
          ) {
            Text(
                text = m.icon,
                fontSize = 40.sp,
                color = Color.White.copy(alpha = if (selected) 1f else 0.65f),
            )
          }
          if (selected) {
            Text(
                text = m.label.uppercase(),
                color = Color(0xFF22D3EE).copy(alpha = 0.75f),
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ComingSoonPanel(title: String, subtitle: String) {
  Box(Modifier.fillMaxSize().padding(end = MODE_CONTENT_END_PAD), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
          text = title,
          color = Color.White.copy(alpha = 0.85f),
          fontSize = 42.sp,
          fontWeight = FontWeight.Light,
          letterSpacing = 4.sp)
      Spacer(Modifier.height(16.dp))
      Text(
          text = subtitle,
          color = Color.White.copy(alpha = 0.45f),
          fontSize = 18.sp,
          fontWeight = FontWeight.Light,
          letterSpacing = 1.sp)
      Spacer(Modifier.height(28.dp))
      Text(
          text = "coming soon",
          color = Color(0xFF22D3EE).copy(alpha = 0.55f),
          fontSize = 14.sp,
          letterSpacing = 3.sp)
    }
  }
}

/** Every mode panel keeps this much space clear on the right so the mode
 *  wheel doesn't cover its content. Sized to match the wheel's visual width. */
private val MODE_CONTENT_END_PAD = 140.dp

/** Photos — full-bleed picsum image via WebView, auto-rotating every 30s.
 *  V0 stub — swap the URL for Immich/SMB/URL feeds later. */
@Composable
private fun PhotosPanel() {
  var version by remember { mutableStateOf(0) }
  val url = "https://picsum.photos/1920/1080?v=$version"
  LaunchedEffect(Unit) {
    while (isActive) {
      delay(30 * 1000L)
      version += 1
    }
  }
  Box(Modifier.fillMaxSize().padding(end = MODE_CONTENT_END_PAD).background(Color(0xFF000000))) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          android.webkit.WebView(ctx).apply {
            setBackgroundColor(0)
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.javaScriptEnabled = false
            loadUrl(url)
          }
        },
        update = { webView -> webView.loadUrl(url) })
  }
}

/** Browser — simple URL bar + WebView. Not a full browser: no history stack,
 *  no bookmarks, no tabs. Enough to open a page. */
@Composable
private fun BrowserPanel() {
  var url by remember { mutableStateOf("https://docs.greblunashome.org") }
  var editing by remember { mutableStateOf(url) }
  var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

  Column(
      Modifier.fillMaxSize().padding(end = MODE_CONTENT_END_PAD),
  ) {
    // URL bar
    Row(
        modifier = Modifier
            .fillMaxSize()
            .height(48.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      BasicTextField(
          value = editing,
          onValueChange = { editing = it },
          singleLine = true,
          textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
          cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF22D3EE)),
          modifier = Modifier
              .clip(RoundedCornerShape(20.dp))
              .background(Color(0xFF0B1220))
              .padding(horizontal = 16.dp, vertical = 10.dp),
      )
      Spacer(Modifier.width(8.dp))
      Box(
          modifier = Modifier
              .clip(RoundedCornerShape(20.dp))
              .background(Color(0xFF22D3EE).copy(alpha = 0.85f))
              .clickable {
                var target = editing.trim()
                if (!target.startsWith("http")) target = "https://$target"
                url = target
                webViewRef?.loadUrl(target)
              }
              .padding(horizontal = 20.dp, vertical = 10.dp),
      ) {
        Text("Go", color = Color(0xFF04060D), fontSize = 16.sp, fontWeight = FontWeight.Medium)
      }
    }
    // Bookmarks row
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      for ((label, target) in BROWSER_BOOKMARKS) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0B1220))
                .clickable {
                  editing = target
                  url = target
                  webViewRef?.loadUrl(target)
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
          Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
      }
    }
    // WebView
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          android.webkit.WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = android.webkit.WebViewClient()
            webViewRef = this
            loadUrl(url)
          }
        })
  }
}

private val BROWSER_BOOKMARKS = listOf(
    "Docs" to "https://docs.greblunashome.org",
    "Stargazer" to "https://stargazermi.com",
    "World" to "http://192.168.1.158:5179/world/star",
    "News" to "https://text.npr.org",
)

/** Settings — read/write the fleet-config knobs from the Portal itself, so
 *  we don't need to hit `POST /config` from the NUC to tweak the star face. */
@Composable
private fun SettingsPanel() {
  val ctx = LocalContext.current
  var starIdle by remember {
    mutableStateOf(
        FleetConfig.getValue(ctx, "star.default_idle")?.equals("true", ignoreCase = true) == true)
  }
  val apiBase = remember {
    FleetConfig.getValue(ctx, "star.api") ?: StarLive.DEFAULT_API
  }

  Box(
      Modifier
          .fillMaxSize()
          .padding(end = MODE_CONTENT_END_PAD)
          .verticalScroll(rememberScrollState())
          .padding(24.dp),
  ) {
    Column {
      Text(
          text = "SETTINGS",
          color = Color.White.copy(alpha = 0.45f),
          fontSize = 16.sp,
          fontWeight = FontWeight.Light,
          letterSpacing = 4.sp)
      Spacer(Modifier.height(20.dp))

      SettingRow(
          title = "Star as default idle",
          subtitle = "Show StarFace instead of the photo frame when the Portal dreams.",
      ) {
        Switch(
            checked = starIdle,
            onCheckedChange = {
              starIdle = it
              FleetConfig.setValue(ctx, "star.default_idle", it.toString())
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF22D3EE),
                checkedTrackColor = Color(0xFF22D3EE).copy(alpha = 0.35f),
            ))
      }

      Spacer(Modifier.height(24.dp))
      SettingLabel("Star API base")
      Text(
          text = apiBase,
          color = Color(0xFF22D3EE).copy(alpha = 0.85f),
          fontSize = 18.sp,
          fontWeight = FontWeight.Light,
          modifier = Modifier.padding(top = 4.dp))
      Text(
          text = "Set via `POST /config { \"set\": { \"star.api\": \"http://ip:port\" } }` " +
              "on this Portal's fleet agent — edit-in-place lands in a later iteration.",
          color = Color.White.copy(alpha = 0.40f),
          fontSize = 12.sp,
          modifier = Modifier.padding(top = 6.dp, end = 40.dp))

      Spacer(Modifier.height(28.dp))
      SettingLabel("Build")
      Text(
          text = "Immortal 1.66-star.x — StarFace shell + mode wheel.",
          color = Color.White.copy(alpha = 0.55f),
          fontSize = 14.sp,
          fontWeight = FontWeight.Light,
          modifier = Modifier.padding(top = 4.dp))
    }
  }
}

@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(vertical = 8.dp),
  ) {
    Column(Modifier.padding(end = 16.dp)) {
      Text(
          text = title,
          color = Color.White.copy(alpha = 0.85f),
          fontSize = 18.sp,
          fontWeight = FontWeight.Light,
          letterSpacing = 0.5.sp)
      Text(
          text = subtitle,
          color = Color.White.copy(alpha = 0.45f),
          fontSize = 13.sp,
          fontWeight = FontWeight.Light,
          modifier = Modifier.padding(top = 3.dp, end = 40.dp))
    }
    Spacer(Modifier.width(0.dp))
    control()
  }
}

@Composable
private fun SettingLabel(text: String) {
  Text(
      text = text.uppercase(),
      color = Color.White.copy(alpha = 0.45f),
      fontSize = 12.sp,
      letterSpacing = 3.sp)
}

/** Spotify — Now Playing card + transport, backed by the Web API. Auth is
 *  Authorization Code with PKCE; the "Connect" button opens the Spotify
 *  authorize URL in an external browser (Chrome or Meta's built-in), the
 *  user signs in, and the stargazer-star:// redirect brings the code back
 *  to the activity for token exchange. */
@Composable
private fun SpotifyPanel() {
  val ctx = LocalContext.current
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val refreshToken = SpotifyState.refreshToken
  val connecting = SpotifyState.connecting
  val error = SpotifyState.error
  var track by remember { mutableStateOf<SpotifyClient.Track?>(null) }
  var pollTick by remember { mutableStateOf(0) }

  // Poll currently-playing every 5s while the Spotify tab is open.
  LaunchedEffect(refreshToken, pollTick) {
    if (refreshToken.isNullOrEmpty()) {
      track = null
      return@LaunchedEffect
    }
    while (isActive) {
      val access = SpotifyState.getAccessToken(ctx)
      if (access != null) {
        val t = withContext(Dispatchers.IO) { SpotifyClient.currentlyPlaying(access) }
        track = t
      }
      delay(5000)
    }
  }

  Box(Modifier.fillMaxSize().padding(end = MODE_CONTENT_END_PAD), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      SpotifyArt(track?.artUrl)
      Spacer(Modifier.height(20.dp))

      if (!SpotifyState.connected) {
        Text(
            text = "Not connected",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (connecting) "Signing in…" else "Tap connect to authorize with your Spotify account.",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Light)
        if (error != null) {
          Spacer(Modifier.height(4.dp))
          Text(text = "error: $error", color = Color(0xFFF87171), fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1DB954).copy(alpha = 0.90f))
                .clickable(enabled = !connecting) {
                  val clientId = FleetConfig.getValue(ctx, "star.spotify.client_id") ?: ""
                  if (clientId.isEmpty()) {
                    SpotifyState.error = "missing_client_id"
                    return@clickable
                  }
                  val verifier = SpotifyClient.makeVerifier()
                  val state = SpotifyClient.makeVerifier().take(32)
                  SpotifyState.pendingVerifier = verifier
                  SpotifyState.pendingState = state
                  SpotifyState.error = null
                  SpotifyState.connecting = true
                  val url = SpotifyClient.authorizeUrl(clientId, verifier, state)
                  ctx.startActivity(
                      Intent(Intent.ACTION_VIEW, Uri.parse(url))
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                  // Launch the loopback catcher — it blocks on ServerSocket.accept()
                  // until the browser follows Spotify's redirect back to 127.0.0.1.
                  scope.launch {
                    val cb = withContext(Dispatchers.IO) {
                      SpotifyClient.awaitLoopbackCallback()
                    }
                    if (cb == null) {
                      SpotifyState.error = "loopback_timeout"
                      SpotifyState.connecting = false
                      return@launch
                    }
                    if (cb.error != null) {
                      SpotifyState.error = cb.error
                      SpotifyState.connecting = false
                      return@launch
                    }
                    val code = cb.code
                    if (code == null || cb.state != SpotifyState.pendingState) {
                      SpotifyState.error = "callback_mismatch"
                      SpotifyState.connecting = false
                      return@launch
                    }
                    val tokens = withContext(Dispatchers.IO) {
                      SpotifyClient.exchangeCode(clientId, code, verifier)
                    }
                    SpotifyState.pendingVerifier = null
                    SpotifyState.pendingState = null
                    if (tokens == null) {
                      SpotifyState.error = "token_exchange_failed"
                      SpotifyState.connecting = false
                      return@launch
                    }
                    SpotifyState.applyTokens(ctx, tokens)
                    SpotifyState.error = null
                    SpotifyState.connecting = false
                  }
                }
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
          Text(
              text = if (connecting) "Waiting…" else "Connect Spotify",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.Medium,
              letterSpacing = 1.sp)
        }
      } else {
        val t = track
        if (t == null) {
          Text(
              text = "Nothing playing",
              color = Color.White.copy(alpha = 0.85f),
              fontSize = 22.sp,
              fontWeight = FontWeight.Light)
          Spacer(Modifier.height(4.dp))
          Text(
              text = "Start a track on any Spotify device — controls land here.",
              color = Color.White.copy(alpha = 0.45f),
              fontSize = 13.sp)
        } else {
          Text(
              text = t.name,
              color = Color.White.copy(alpha = 0.90f),
              fontSize = 24.sp,
              fontWeight = FontWeight.Medium,
              textAlign = TextAlign.Center)
          Spacer(Modifier.height(4.dp))
          Text(
              text = t.artist,
              color = Color.White.copy(alpha = 0.60f),
              fontSize = 16.sp,
              fontWeight = FontWeight.Light,
              textAlign = TextAlign.Center)
          Text(
              text = t.album,
              color = Color.White.copy(alpha = 0.40f),
              fontSize = 13.sp,
              textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
          val isPlaying = t?.isPlaying == true
          SpotifyTransportButton("⏮") {
            scope.launch {
              val access = SpotifyState.getAccessToken(ctx) ?: return@launch
              withContext(Dispatchers.IO) { SpotifyClient.previous(access) }
              // Kick a fresh poll so UI updates without waiting 5s.
              pollTick++
            }
          }
          SpotifyTransportButton(if (isPlaying) "⏸" else "▶", primary = true) {
            scope.launch {
              val access = SpotifyState.getAccessToken(ctx) ?: return@launch
              withContext(Dispatchers.IO) {
                if (isPlaying) SpotifyClient.pause(access) else SpotifyClient.play(access)
              }
              pollTick++
            }
          }
          SpotifyTransportButton("⏭") {
            scope.launch {
              val access = SpotifyState.getAccessToken(ctx) ?: return@launch
              withContext(Dispatchers.IO) { SpotifyClient.next(access) }
              pollTick++
            }
          }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Sign out",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            modifier = Modifier
                .clickable { SpotifyState.clear(ctx) }
                .padding(8.dp))
      }
    }
  }
}

/** Album art — Compose has no built-in image loader for URLs; fetch bytes in
 *  a coroutine and swap into an ImageBitmap. Falls back to a green ♫ tile
 *  while loading or on failure. */
@Composable
private fun SpotifyArt(url: String?) {
  var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
  LaunchedEffect(url) {
    if (url.isNullOrEmpty()) return@LaunchedEffect
    val loaded = withContext(Dispatchers.IO) {
      runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        val bytes = conn.inputStream.use { it.readBytes() }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      }.getOrNull()
    }
    if (loaded != null) bitmap = loaded.asImageBitmap()
  }
  Box(
      modifier = Modifier
          .size(240.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(Color(0xFF1DB954).copy(alpha = 0.18f)),
      contentAlignment = Alignment.Center,
  ) {
    val bm = bitmap
    if (bm != null) {
      Image(
          bitmap = bm,
          contentDescription = null,
          modifier = Modifier.fillMaxSize())
    } else {
      Text("♫", fontSize = 96.sp, color = Color(0xFF1DB954).copy(alpha = 0.75f))
    }
  }
}

@Composable
private fun SpotifyTransportButton(glyph: String, primary: Boolean = false, onTap: () -> Unit) {
  Box(
      modifier = Modifier
          .size(if (primary) 76.dp else 56.dp)
          .clip(CircleShape)
          .background(
              if (primary) Color(0xFF1DB954).copy(alpha = 0.85f)
              else Color(0xFF0B1220))
          .clickable { onTap() },
      contentAlignment = Alignment.Center,
  ) {
    Text(glyph, fontSize = if (primary) 32.sp else 22.sp, color = Color.White)
  }
}

@Composable
fun StarPresenceScreen(
    state: StarLive.State,
    detail: String?,
    onTapWake: () -> Unit = {},
    onEnroll: (String) -> Unit = {},
) {
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

  // Two cap wardrobes — Eagles on even hours, Phillies on odd, unless the
  // user tapped the hat to override. The override persists until the next
  // hour flip (i.e. it doesn't stick forever — you'd forget you set it).
  var wardrobeOverride by remember { mutableStateOf<Boolean?>(null) }
  var lastAutoHourOdd by remember { mutableStateOf(hourIsOdd()) }
  val phillies = wardrobeOverride ?: lastAutoHourOdd
  LaunchedEffect(Unit) {
    while (isActive) {
      val now = hourIsOdd()
      if (now != lastAutoHourOdd) {
        // Hour just flipped — drop any manual override so the schedule resumes.
        wardrobeOverride = null
        lastAutoHourOdd = now
      }
      delay(60 * 1000L)
    }
  }

  val idleArt =
      ImageBitmap.imageResource(if (phillies) R.drawable.star_idle_phillies else R.drawable.star_idle)
  val thinkingArt =
      ImageBitmap.imageResource(
          if (phillies) R.drawable.star_thinking_phillies else R.drawable.star_thinking)
  val speakingArt =
      ImageBitmap.imageResource(
          if (phillies) R.drawable.star_speaking_phillies else R.drawable.star_speaking)
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

  // Local tap feedback — a short amber ring pulse rendered immediately on
  // touch so the interaction feels responsive even before the NUC state poll
  // catches up (up to POLL_MS lag). Value is the frame-clock timestamp of the
  // last tap; the ring fades over ~0.55s.
  var tapAt by remember { mutableFloatStateOf(-10f) }
  var showEnroll by remember { mutableStateOf(false) }

  Box(
      Modifier.fillMaxSize()
          .background(Color(0xFF04060D))
          .pointerInput(Unit) {
            detectTapGestures(
                onTap = { offset ->
                  // Portrait geometry has to match drawPortrait: centered,
                  // side = 82% of min(width,height), hat is the top ~30% of
                  // that face square. A tap inside the hat region flips the
                  // wardrobe locally (until the next hour); anywhere else
                  // fires the wake path.
                  val side = min(size.width, size.height) * 0.82f
                  val faceLeft = size.width / 2f - side / 2f
                  val faceTop = size.height / 2f - side / 2f
                  val inHat =
                      offset.x >= faceLeft &&
                          offset.x <= faceLeft + side &&
                          offset.y >= faceTop &&
                          offset.y <= faceTop + side * 0.30f
                  if (inHat) {
                    // Toggle the local override — if we were following the
                    // schedule, override with the opposite; if we were
                    // overriding, flip it (still overriding).
                    wardrobeOverride = !(wardrobeOverride ?: lastAutoHourOdd)
                    tapAt = t
                    return@detectTapGestures
                  }
                  tapAt = t
                  onTapWake()
                },
                onLongPress = { showEnroll = true })
          }) {
    Canvas(Modifier.fillMaxSize()) {
      drawStarfield(stars, t)
      drawPortrait(t, state, shown, previous, fade, core)
      drawTapPulse(t, tapAt)
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

    if (showEnroll) {
      EnrollPickerDialog(
          onPick = { name ->
            onEnroll(name)
            showEnroll = false
          },
          onDismiss = { showEnroll = false })
    }
  }
}

@Composable
private fun EnrollPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = Color(0xFF0B1220),
        contentColor = Color(0xFFE5E7EB),
    ) {
      androidx.compose.foundation.layout.Column(
          modifier = Modifier.padding(24.dp),
      ) {
        Text(
            text = "Who's here?",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 3.sp,
            modifier = Modifier.padding(bottom = 12.dp))
        for (name in SPEAKER_ROSTER) {
          androidx.compose.material3.TextButton(
              onClick = { onPick(name) },
              modifier = Modifier.fillMaxSize().padding(vertical = 2.dp)) {
            Text(
                text = name,
                color = Color(0xFF22D3EE),
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 1.sp)
          }
        }
        androidx.compose.material3.TextButton(
            onClick = { onPick("clear") },
            modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
          Text(
              text = "Sign out",
              color = Color.White.copy(alpha = 0.55f),
              fontSize = 20.sp,
              fontWeight = FontWeight.Light,
              letterSpacing = 2.sp)
        }
      }
    }
  }
}

private fun hourIsOdd(): Boolean =
    java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) % 2 == 1

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

  // (drawTapPulse lives below — it uses the same clock and center.)

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

/** A single amber ring expanding from the portrait center, ~0.55s lifetime,
 *  triggered by a tap on the face so the touch feels immediate. */
private fun DrawScope.drawTapPulse(t: Float, tapAt: Float) {
  val age = t - tapAt
  if (age < 0f || age > 0.55f) return
  val c = Offset(size.width / 2f, size.height / 2f)
  val r0 = min(size.width, size.height) * 0.34f
  val p = age / 0.55f
  drawCircle(
      color = Color(0xFFF59E0B).copy(alpha = (1f - p) * 0.55f),
      radius = r0 * (1f + p * 1.2f),
      center = c,
      style = Stroke(width = 4f))
}
