/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Immortal's own settings (weather unit, home-screen tile size), reached from the
 * "Immortal" tile in the launcher's Settings folder. The launcher re-reads these
 * on resume, so changes apply the moment the user returns home.
 */
class ImmortalSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ImmortalSettingsScreen() } }
  }
}

@Composable
private fun ImmortalSettingsScreen() {
  // The sub-screens (multi-room, MQTT, device health, boot apps, world clock) are now their own
  // Activities, reached by launching them from the nav rows below — one nav model.
  val context = LocalContext.current
  var settings by remember { mutableStateOf(ImmortalSettings.load(context)) }

  // Re-read on resume so values changed in a sub-screen Activity reflect when we come back.
  val resumeOwner = LocalLifecycleOwner.current
  DisposableEffect(resumeOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) settings = ImmortalSettings.load(context)
    }
    resumeOwner.lifecycle.addObserver(obs)
    onDispose { resumeOwner.lifecycle.removeObserver(obs) }
  }

  // Remote support: focus the first control on open; Back exits the screen.
  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  // Every change routes through the registry's apply (so the domain's onApplied side effects —
  // status-bar re-apply, multi-room resync — fire on the on-device path too), then we re-read.
  fun apply(key: String, value: Any) {
    SettingsDomains.immortal.apply(context, JSONObject().put(key, value))
    settings = ImmortalSettings.load(context)
  }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) activity?.finish()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusRequester(firstFocus).focusGroup()) {
      Text("Immortal", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Tune how the launcher looks and what it shows.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))

      // Top-level controls render from the `immortal` domain (the same registry that drives the
      // remote). The multi-room fields are excluded here — they live behind the Multi-room sub-screen.
      SettingsList(
          SettingsDomains.immortal,
          settings,
          exclude = setOf("multiRoomEnabled", "snapcastHost", "maPort", "maUsername", "maPassword"),
      ) { k, v ->
        apply(k, v)
      }

      WallpaperSection()

      Spacer(Modifier.size(26.dp))
      SectionLabel("World clock")
      Card {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .tvFocusableRow { context.startActivity(Intent(context, WorldClockActivity::class.java)) }
                    .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("World clock locations", color = Color.White, fontSize = 17.sp)
            Text(
                "Pick which cities the World Clock widget shows (first four are displayed).",
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
          }
          Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
        }
      }

      MultiRoomNavRow(onOpen = { context.startActivity(Intent(context, MultiRoomActivity::class.java)) })

      MqttNavRow(onOpen = { context.startActivity(Intent(context, MqttActivity::class.java)) })

      RemoteNavRow()

      FeatureSettingsNavRow(
          "Sounds", "Chimes & spoken time",
          "Hourly chime, spoken time, golden-hour tone, quiet hours") {
            context.startActivity(Intent(context, ChimeSettingsActivity::class.java))
          }
      FeatureSettingsNavRow(
          "Welcome overlay", "Welcome-back greeting",
          "A time-of-day greeting when the screensaver starts") {
            context.startActivity(Intent(context, WelcomeSettingsActivity::class.java))
          }
      FeatureSettingsNavRow(
          "Digital clock", "Clock screensaver",
          "Show a large digital clock as the screensaver") {
            context.startActivity(Intent(context, ClockSettingsActivity::class.java))
          }
      FeatureSettingsNavRow(
          "Sleep & idle", "Screen-off timers",
          "Idle timeout and the overnight sleep window") {
            context.startActivity(Intent(context, SleepSettingsActivity::class.java))
          }
      FeatureSettingsNavRow(
          "Wake-up light", "Sunrise alarm",
          "Brighten the screen gradually at a set time") {
            context.startActivity(Intent(context, SunriseSettingsActivity::class.java))
          }

      QuickButtonsSection()

      Spacer(Modifier.size(26.dp))
      BootAppsNavRow(
          count = BootLaunch.packages(context).size,
          onOpen = { context.startActivity(Intent(context, BootAppsActivity::class.java)) })

      DeviceHealthNavRow(onOpen = { context.startActivity(Intent(context, DeviceHealthActivity::class.java)) })

      Text(
          "Changes apply as soon as you go back to the home screen.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
  }
}

/**
 * Nav row on the main settings page that opens the Multi-room audio subpage. Install-
 * context-aware: shown only when the Snapcast player ([MultiRoomService.SNAPCAST_PACKAGE])
 * is installed — i.e. when this Portal is set up as a synced speaker.
 */
@Composable
private fun MultiRoomNavRow(onOpen: () -> Unit) {
  val context = LocalContext.current
  val installed = remember { StoreCatalog.isInstalled(context, MultiRoomService.SNAPCAST_PACKAGE) }
  if (!installed) return

  Spacer(Modifier.size(26.dp))
  SectionLabel("Multi-room audio")
  Card {
    Row(
        modifier = Modifier.fillMaxWidth().tvFocusableRow { onOpen() }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Multi-room audio", color = Color.White, fontSize = 17.sp)
        Text(
            if (ImmortalSettings.multiRoomEnabled(context)) MultiRoomStatus.text.ifBlank { "On" }
            else "Off",
            color = Color(0xFF9A9A9A),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
    }
  }
}

/**
 * The Multi-room audio subpage (reached from [MultiRoomNavRow]): surface the Snapcast
 * group's track on the now-playing card and device media controls, with play/pause/skip
 * forwarded to Music Assistant. The in-launcher relay reads the snapserver for metadata;
 * the MA login is needed only to send transport. Back returns to the main settings page.
 */
/** A numbered step in the multi-room setup guide. */
@Composable
private fun MultiRoomStep(n: String, text: String) {
  Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Text(
        "$n.",
        color = Color(0xFF8AB4F8),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 10.dp),
    )
    Text(text, color = Color(0xFFB8B8B8), fontSize = 14.sp, lineHeight = 20.sp)
  }
}

@Composable
internal fun MultiRoomScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  var enabled by remember { mutableStateOf(ImmortalSettings.multiRoomEnabled(context)) }
  var host by remember { mutableStateOf(ImmortalSettings.snapcastHost(context)) }
  var maPort by remember { mutableStateOf(ImmortalSettings.maPort(context).toString()) }
  var maUser by remember { mutableStateOf(ImmortalSettings.maUser(context)) }
  var maPass by remember { mutableStateOf(ImmortalSettings.maPass(context)) }

  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
  // Chain the text fields so the keyboard's "Next" jumps to the following field instead of
  // closing — the IP field focuses the username, which focuses the password.
  val focusManager = LocalFocusManager.current
  val portFocus = remember { FocusRequester() }
  val userFocus = remember { FocusRequester() }
  val passFocus = remember { FocusRequester() }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) onBack()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusGroup()) {
      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(12.dp),
          modifier =
              Modifier.focusRequester(firstFocus).tvFocusable(RoundedCornerShape(12.dp)) { onBack() },
      ) {
        Text(
            "‹  Back",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
      }
      Spacer(Modifier.size(18.dp))

      Text(
          "Multi-room audio",
          color = Color.White,
          fontSize = 34.sp,
          fontWeight = FontWeight.SemiBold)
      Text(
          "Show the Snapcast group's track on the now-playing card, with play/pause/skip.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(22.dp))
      Card {
        Column(modifier = Modifier.padding(18.dp)) {
          Text("Setting it up", color = Color.White, fontSize = 17.sp)
          Text(
              "Group your Portals into one perfectly in-sync speaker system:",
              color = Color(0xFF9A9A9A),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
          )
          MultiRoomStep(
              "1",
              "Install and set up Music Assistant as a server on your home network. New to Music " +
                  "Assistant? Learn more at music-assistant.io")
          MultiRoomStep(
              "2",
              "Install Snapcast from the Immortal App Store, and point it at your Music Assistant " +
                  "server.")
          MultiRoomStep(
              "3", "Turn on the toggle below, then enter your Music Assistant server's address.")
        }
      }
      Spacer(Modifier.size(26.dp))
      Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Show what the group is playing", color = Color.White, fontSize = 17.sp)
            Text(
                "Surfaces the Snapcast group's track on the now-playing card — even when the " +
                    "Music Assistant app isn't open on this Portal.",
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
          }
          Segmented(
              options = listOf("Off" to "off", "On" to "on"),
              selected = if (enabled) "on" else "off",
              onSelect = {
                val on = it == "on"
                enabled = on
                ImmortalSettings.setMultiRoomEnabled(context, on)
                MultiRoomService.sync(context)
              },
          )
        }
        if (enabled) {
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
                value = host,
                onValueChange = {
                  host = it
                  ImmortalSettings.setSnapcastHost(context, it)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }),
                label = { Text("Music Assistant / Snapcast server IP") },
                modifier = Modifier.weight(1f),
            )
            Surface(
                color = Color(0xFF2E6BE6),
                shape = RoundedCornerShape(10.dp),
                modifier =
                    Modifier.padding(start = 12.dp).tvFocusable(RoundedCornerShape(10.dp)) {
                      ImmortalSettings.setSnapcastHost(context, host)
                      MultiRoomService.sync(context)
                    },
            ) {
              Text(
                  "Apply",
                  color = Color.White,
                  fontSize = 15.sp,
                  modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
              )
            }
          }
          // Music Assistant API port — 8095 by default; only change it if MA's web server moved.
          OutlinedTextField(
              value = maPort,
              onValueChange = {
                maPort = it.filter(Char::isDigit).take(5)
                maPort.toIntOrNull()?.let { p -> ImmortalSettings.setMaPort(context, p) }
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { userFocus.requestFocus() }),
              label = { Text("Music Assistant port (default 8095)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                      .focusRequester(portFocus),
          )
          // Music Assistant login — only used to forward play/pause/skip to MA, and only when MA's
          // optional authentication is enabled. Leave blank for a stock server.
          OutlinedTextField(
              value = maUser,
              onValueChange = {
                maUser = it
                ImmortalSettings.setMaUsername(context, it)
              },
              singleLine = true,
              // No auto-capitalize: a lowercase MA username must stay lowercase.
              keyboardOptions =
                  KeyboardOptions(
                      capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { passFocus.requestFocus() }),
              label = { Text("Music Assistant username") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                      .focusRequester(userFocus),
          )
          OutlinedTextField(
              value = maPass,
              onValueChange = {
                maPass = it
                ImmortalSettings.setMaPassword(context, it)
              },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
              label = { Text("Music Assistant password") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(passFocus),
          )
          Row(
              modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
                color = Color(0xFF2E6BE6),
                shape = RoundedCornerShape(10.dp),
                modifier =
                    Modifier.tvFocusable(RoundedCornerShape(10.dp)) { MaControl.testLogin(context) },
            ) {
              Text(
                  "Sign in",
                  color = Color.White,
                  fontSize = 15.sp,
                  modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
              )
            }
            val auth = MultiRoomStatus.maAuth
            if (auth.isNotBlank()) {
              Text(
                  auth,
                  color = if (auth.endsWith("✓")) Color(0xFF66BB6A) else Color(0xFFE57373),
                  fontSize = 13.sp,
                  modifier = Modifier.padding(start = 14.dp),
              )
            }
          }
          Text(
              "Sends play/pause/skip to Music Assistant, and shows now-playing for AirPlay " +
                  "sources (which don't carry it over Snapcast). Library/radio now-playing works " +
                  "without it.",
              color = Color(0xFF7C7C7C),
              fontSize = 12.sp,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
          )

          // Live relay status — gives Apply visible feedback (Connecting… → Connected).
          Text(
              MultiRoomStatus.text.ifBlank { "Starting…" },
              color = Color(0xFF8AB4F8),
              fontSize = 13.sp,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
          )
        }
      }
      Text(
          "Join this Portal to the same Snapcast group as your other rooms, then point it " +
              "at your Music Assistant server. The synced audio is played by the Snapcast app.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
  }
}

/**
 * "Quick buttons" section: a centered overlay button cluster at the top of the screen (v1: an
 * app switcher). Enable it, and choose whether it shows only while the top bar is revealed
 * (default) or always.
 */
@Composable
private fun QuickButtonsSection() {
  val context = LocalContext.current
  var settings by remember { mutableStateOf(QuickBarConfig.load(context)) }

  Spacer(Modifier.size(26.dp))
  SectionLabel("Quick buttons")
  // Rendered from the `quickbar` registry domain (the same specs the phone remote uses), so the
  // toggle and its gated "Always show" can't drift from the remote's version. Apply routes through
  // the domain so its onApplied (reconcile + overlay refresh) fires here too.
  SettingsList(SettingsDomains.quickbar, settings) { k, v ->
    SettingsDomains.quickbar.apply(context, JSONObject().put(k, v))
    settings = QuickBarConfig.load(context)
  }
  Text(
      "Needs the accessibility-based top-bar watch enabled during setup. The switcher shows your " +
          "recently used apps; tap one to switch.",
      color = Color(0xFF7C7C7C),
      fontSize = 13.sp,
      modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
  )
}

/**
 * A settings-menu row that opens a fork feature's own settings Activity (chime, welcome, digital
 * clock, sleep, sunrise). Mirrors [MqttNavRow]'s section-label + card + chevron styling. These
 * screens are already registered SettingsDomains (so they also render on the phone remote); this
 * is the on-device link into each one — see docs/design/feature-integration.md, step 2.
 */
@Composable
private fun FeatureSettingsNavRow(
    section: String,
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
) {
  Spacer(Modifier.size(26.dp))
  SectionLabel(section)
  Card {
    Row(
        modifier = Modifier.fillMaxWidth().tvFocusableRow { onOpen() }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(title, color = Color.White, fontSize = 17.sp)
        Text(
            subtitle,
            color = Color(0xFF9A9A9A),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
    }
  }
}

/**
 * Nav row into the Home Assistant (MQTT) subpage. Always shown — no companion app needed;
 * the subtitle reflects whether publishing is on and the live connection status.
 */
@Composable
private fun MqttNavRow(onOpen: () -> Unit) {
  val context = LocalContext.current
  Spacer(Modifier.size(26.dp))
  SectionLabel("Home Assistant")
  Card {
    Row(
        modifier = Modifier.fillMaxWidth().tvFocusableRow { onOpen() }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Home Assistant (MQTT)", color = Color.White, fontSize = 17.sp)
        Text(
            if (MqttConfig.isEnabled(context)) MqttStatus.text.ifBlank { "On" } else "Off",
            color = Color(0xFF9A9A9A),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
    }
  }
}

/** Opens the phone-remote pairing screen ([RemotePairActivity]); shows on/off at a glance. */
@Composable
private fun RemoteNavRow() {
  val context = LocalContext.current
  Spacer(Modifier.size(26.dp))
  SectionLabel("Remote")
  Card {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .tvFocusableRow {
                  runCatching {
                    context.startActivity(Intent(context, RemotePairActivity::class.java))
                  }
                }
                .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Control from your phone", color = Color.White, fontSize = 17.sp)
        Text(
            if (RemotePairing.isEnabled(context)) "On — pair a phone as a remote" else "Off",
            color = Color(0xFF9A9A9A),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
    }
  }
}

/**
 * The Home Assistant (MQTT) subpage: publish this Portal to Home Assistant as auto-
 * discovered entities (presence, screen, battery, now-playing, controls) over a local
 * MQTT broker. Off by default; Back returns to the main settings page.
 */
@Composable
internal fun MqttScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  var enabled by remember { mutableStateOf(MqttConfig.isEnabled(context)) }
  var host by remember { mutableStateOf(MqttConfig.host(context)) }
  var port by remember { mutableStateOf(MqttConfig.port(context).toString()) }
  var user by remember { mutableStateOf(MqttConfig.username(context)) }
  var pass by remember { mutableStateOf(MqttConfig.password(context)) }
  var useTls by remember { mutableStateOf(MqttConfig.useTls(context)) }
  var validateCert by remember { mutableStateOf(MqttConfig.validateCert(context)) }
  // MqttStatus is a plain holder updated off the main thread, so poll it for live
  // "Connecting… → Connected" feedback (Compose won't recompose on its writes).
  var status by remember { mutableStateOf(MqttStatus.text) }
  LaunchedEffect(Unit) {
    while (true) {
      status = MqttStatus.text
      kotlinx.coroutines.delay(800)
    }
  }

  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
  val focusManager = LocalFocusManager.current
  val portFocus = remember { FocusRequester() }
  val userFocus = remember { FocusRequester() }
  val passFocus = remember { FocusRequester() }

  fun apply() {
    MqttConfig.setHost(context, host)
    MqttConfig.setPort(context, port.toIntOrNull() ?: MqttConfig.DEFAULT_PORT)
    MqttConfig.setUsername(context, user)
    MqttConfig.setPassword(context, pass)
    MqttConfig.setUseTls(context, useTls)
    MqttConfig.setValidateCert(context, validateCert)
    MqttService.sync(context)
  }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) onBack()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusGroup()) {
      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(12.dp),
          modifier =
              Modifier.focusRequester(firstFocus).tvFocusable(RoundedCornerShape(12.dp)) { onBack() },
      ) {
        Text(
            "‹  Back",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
      }
      Spacer(Modifier.size(18.dp))

      Text("Home Assistant", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Publish this Portal to Home Assistant as auto-discovered entities — presence, " +
              "screen, battery, now-playing, and controls — over your MQTT broker.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(22.dp))
      Card {
        Column(modifier = Modifier.padding(18.dp)) {
          Text("Setting it up", color = Color.White, fontSize = 17.sp)
          MultiRoomStep(
              "1",
              "In Home Assistant, add the Mosquitto broker add-on (Settings → Add-ons) and the " +
                  "MQTT integration. New to MQTT? See home-assistant.io/integrations/mqtt")
          MultiRoomStep(
              "2", "Turn on the toggle below and enter your broker's address (and login, if any).")
          MultiRoomStep(
              "3",
              "This Portal appears automatically under Settings → Devices as a new MQTT device — " +
                  "no YAML needed.")
        }
      }
      Spacer(Modifier.size(26.dp))
      Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Publish to Home Assistant", color = Color.White, fontSize = 17.sp)
            Text(
                "Exposes this Portal's state and controls as Home Assistant entities over MQTT.",
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
          }
          Segmented(
              options = listOf("Off" to "off", "On" to "on"),
              selected = if (enabled) "on" else "off",
              onSelect = {
                val on = it == "on"
                enabled = on
                MqttConfig.setEnabled(context, on)
                if (on) apply() else MqttService.sync(context)
              },
          )
        }
        if (enabled) {
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
                value = host,
                onValueChange = {
                  host = it
                  MqttConfig.setHost(context, it)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }),
                label = { Text("MQTT broker IP / host") },
                modifier = Modifier.weight(1f),
            )
            Surface(
                color = Color(0xFF2E6BE6),
                shape = RoundedCornerShape(10.dp),
                modifier =
                    Modifier.padding(start = 12.dp).tvFocusable(RoundedCornerShape(10.dp)) { apply() },
            ) {
              Text(
                  "Apply",
                  color = Color.White,
                  fontSize = 15.sp,
                  modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
              )
            }
          }
          OutlinedTextField(
              value = port,
              onValueChange = {
                port = it.filter { ch -> ch.isDigit() }
                MqttConfig.setPort(context, port.toIntOrNull() ?: MqttConfig.DEFAULT_PORT)
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { userFocus.requestFocus() }),
              label = { Text("Port (default 1883, or 8883 for TLS)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                      .focusRequester(portFocus),
          )
          OutlinedTextField(
              value = user,
              onValueChange = {
                user = it
                MqttConfig.setUsername(context, it)
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(
                      capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { passFocus.requestFocus() }),
              label = { Text("Username (optional)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(userFocus),
          )
          OutlinedTextField(
              value = pass,
              onValueChange = {
                pass = it
                MqttConfig.setPassword(context, it)
              },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); apply() }),
              label = { Text("Password (optional)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(passFocus),
          )
          Row(
              modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Use TLS / SSL", color = Color.White, fontSize = 15.sp)
              Text(
                  "Encrypt the connection (e.g. a broker behind a reverse proxy on port 8883).",
                  color = Color(0xFF9A9A9A),
                  fontSize = 13.sp,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            Segmented(
                options = listOf("Off" to "off", "On" to "on"),
                selected = if (useTls) "on" else "off",
                onSelect = {
                  val on = it == "on"
                  useTls = on
                  // Hop to the matching default port if the field is still on the other default.
                  if (on && port == MqttConfig.DEFAULT_PORT.toString())
                      port = MqttConfig.DEFAULT_TLS_PORT.toString()
                  else if (!on && port == MqttConfig.DEFAULT_TLS_PORT.toString())
                      port = MqttConfig.DEFAULT_PORT.toString()
                  apply()
                },
            )
          }
          if (useTls) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Validate certificate", color = Color.White, fontSize = 15.sp)
                Text(
                    "Verify the broker's certificate and hostname. Turn off only for a " +
                        "self-signed broker on a trusted network.",
                    color = Color(0xFF9A9A9A),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
              }
              Segmented(
                  options = listOf("Off" to "off", "On" to "on"),
                  selected = if (validateCert) "on" else "off",
                  onSelect = {
                    validateCert = it == "on"
                    apply()
                  },
              )
            }
          }
          // Live connection status — gives Apply visible feedback (Connecting… → Connected).
          Text(
              status.ifBlank { "Starting…" },
              color = Color(0xFF8AB4F8),
              fontSize = 13.sp,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
          )
        }
      }
      Text(
          "Connects to a broker on your LAN over plain MQTT or TLS. Your Portal shows up in " +
              "Home Assistant automatically as a device with presence, screen, battery, " +
              "now-playing and a few controls — no configuration.yaml editing.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
  }
}

/** A curated set of cities for the world-clock picker (label → IANA tz id). */
private val WORLD_CLOCK_CITIES =
    listOf(
        "Cupertino" to "America/Los_Angeles",
        "Denver" to "America/Denver",
        "New York" to "America/New_York",
        "São Paulo" to "America/Sao_Paulo",
        "London" to "Europe/London",
        "Paris" to "Europe/Paris",
        "Berlin" to "Europe/Berlin",
        "Cape Town" to "Africa/Johannesburg",
        "Dubai" to "Asia/Dubai",
        "Mumbai" to "Asia/Kolkata",
        "Singapore" to "Asia/Singapore",
        "Tokyo" to "Asia/Tokyo",
        "Sydney" to "Australia/Sydney",
    )

/** The world-clock locations picker. Tapping a city toggles it; the widget shows the first four in
 * selection order. Back returns to the main settings page. */
@Composable
internal fun WorldClockScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  var selected by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) onBack()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusGroup()) {
      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(12.dp),
          modifier =
              Modifier.focusRequester(firstFocus).tvFocusable(RoundedCornerShape(12.dp)) { onBack() },
      ) {
        Text(
            "‹  Back",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
      }
      Spacer(Modifier.size(18.dp))
      Text("World clock", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Choose locations for the World Clock widget. The first four (in the order you pick them) are shown.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))
      Card {
        WORLD_CLOCK_CITIES.forEachIndexed { i, (label, zone) ->
          if (i > 0) Divider()
          val on = zone in selected
          val rank = selected.indexOf(zone)
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .tvFocusableRow {
                        selected = if (on) selected - zone else selected + zone
                        ImmortalSettings.setWorldClockZones(context, selected)
                      }
                      .padding(horizontal = 18.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(label, color = Color.White, fontSize = 16.sp)
              Text(
                  zone.replace('_', ' '),
                  color = Color(0xFF9A9A9A),
                  fontSize = 13.sp,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            if (on && rank in 0..3) {
              Text(
                  "#${rank + 1}",
                  color = Color(0xFF8AB4F8),
                  fontSize = 14.sp,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(end = 14.dp),
              )
            } else if (on) {
              Text("hidden", color = Color(0xFF7C7C7C), fontSize = 13.sp, modifier = Modifier.padding(end = 14.dp))
            }
            Switch(checked = on, onCheckedChange = null)
          }
        }
      }
      Text(
          "Tip: the widget shows up to four clocks. Re-pick a city to move it to the end of the order.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
  }
}

private data class BootAppOption(val pkg: String, val label: String, val icon: ImageBitmap)
/** Every launchable app except our own launcher, for the boot-launch picker. */
private fun loadLaunchableApps(context: Context): List<BootAppOption> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return pm.queryIntentActivities(intent, 0)
      .filter { it.activityInfo.packageName != context.packageName }
      .mapNotNull { ri ->
        runCatching {
              BootAppOption(
                  pkg = ri.activityInfo.packageName,
                  label = ri.loadLabel(pm).toString(),
                  icon = ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap())
            }
            .getOrNull()
      }
      .distinctBy { it.pkg }
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

/**
 * Row on the main settings page into the "Device health" subpage. Subtitle reflects how
 * many provisioned permissions are missing, so a problem is visible without drilling in.
 */
@Composable
private fun DeviceHealthNavRow(onOpen: () -> Unit) {
  val context = LocalContext.current
  val issues = remember { DevicePermissions.issueCount(context) }
  Spacer(Modifier.size(26.dp))
  SectionLabel("Device")
  Card {
    Row(
        modifier = Modifier.fillMaxWidth().tvFocusableRow { onOpen() }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Device health", color = Color.White, fontSize = 17.sp)
        Text(
            if (issues == 0) "All set up"
            else "$issues setting${if (issues == 1) " needs" else "s need"} attention",
            color = if (issues == 0) Color(0xFF9A9A9A) else Color(0xFFE0A030),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
    }
  }
}

/**
 * The "Device health" subpage: a live status of every special permission provisioning
 * grants (screen-off device admin, notification access, install, overlay, secure settings),
 * with — for anything that's missing — what's degraded and how to fix it. A diagnostic to
 * point a struggling user at. Replaces the old destructive "turn off device admin" button;
 * the uninstall path it served is kept as a clearly-warned advanced action at the bottom.
 */
@Composable
internal fun DeviceHealthScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val checks = remember { DevicePermissions.all(context) }
  val issues = checks.count { !it.granted }
  var adminActive by remember { mutableStateOf(ScreenControl.isAdminActive(context)) }
  var confirmingDisable by remember { mutableStateOf(false) }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) onBack()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusGroup()) {
      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.tvFocusable(RoundedCornerShape(12.dp)) { onBack() },
      ) {
        Text(
            "‹  Back",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
      }
      Spacer(Modifier.size(18.dp))

      Text("Device health", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "The permissions your Portal was set up with, and what each one powers.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(22.dp))

      // Summary banner — green when healthy, amber when something needs attention.
      Surface(
          color = if (issues == 0) Color(0xFF18301C) else Color(0xFF332813),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
            if (issues == 0) "✓  Everything's set up correctly."
            else "!  $issues setting${if (issues == 1) " needs" else "s need"} attention — your Portal " +
                "still works, but some features are limited.",
            color = if (issues == 0) Color(0xFF7FD18B) else Color(0xFFE0A030),
            fontSize = 15.sp,
            modifier = Modifier.padding(16.dp),
        )
      }
      Spacer(Modifier.size(20.dp))

      Card {
        checks.forEachIndexed { i, c ->
          if (i > 0) Divider()
          HealthRow(c)
        }
      }

      if (issues > 0) {
        Spacer(Modifier.size(22.dp))
        SectionLabel("How to fix")
        Text(
            "Reconnect your Portal to a computer and re-run Immortal setup — it re-grants all of " +
                "these. (Advanced: re-run provision.sh / provision.ps1 from the provisioning kit.)",
            color = Color(0xFFB8B8B8),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
      }

      // Advanced: deactivating the screen-off admin is the clean path to uninstall Immortal
      // (the shell can't force-remove a non-test admin). Tucked away and clearly warned — it
      // turns off screen-off until re-provisioned.
      if (adminActive) {
        Spacer(Modifier.size(28.dp))
        Text(
            "Allow uninstall",
            color = Color(0xFF8A8A8A),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Text(
            "Disabling the screen-off device admin lets Immortal be uninstalled, but it also stops " +
                "automatic screen-off (screensaver sleep and the Home Assistant control) until you " +
                "re-run setup. Only do this if you know what you're doing.",
            color = Color(0xFF7C7C7C),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Text(
            if (confirmingDisable) "Tap again to confirm — this stops automatic screen-off"
            else "Disable screen-off admin",
            color = Color(0xFFE0908A),
            fontSize = 15.sp,
            fontWeight = if (confirmingDisable) FontWeight.SemiBold else FontWeight.Normal,
            modifier =
                Modifier.tvFocusable(RoundedCornerShape(8.dp)) {
                      // Two-tap arm: a single stray remote press shouldn't tear down device admin
                      // (which silently breaks screensaver sleep + the HA screen-off control).
                      if (confirmingDisable) {
                        ScreenControl.deactivateAdmin(context)
                        adminActive = ScreenControl.isAdminActive(context)
                        confirmingDisable = false
                      } else {
                        confirmingDisable = true
                      }
                    }
                    .padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        )
      }
    }
  }
}

@Composable
private fun HealthRow(c: DevicePermissions.Check) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Text(
        if (c.granted) "✓" else "!",
        color = if (c.granted) Color(0xFF7FD18B) else Color(0xFFE0A030),
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 14.dp, top = 1.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(c.title, color = Color.White, fontSize = 17.sp)
      Text(
          c.enables,
          color = Color(0xFF9A9A9A),
          fontSize = 13.sp,
          lineHeight = 18.sp,
          modifier = Modifier.padding(top = 2.dp),
      )
      if (!c.granted) {
        Text(
            "Without it: ${c.degraded}",
            color = Color(0xFFE0A030),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            c.fix,
            color = Color(0xFF8AB4F8),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
  }
}

/**
 * Row on the main settings page that opens the "Start on boot" subpage. The full app
 * list lives on its own page so it doesn't flood the main settings screen; here we just
 * summarise how many apps are set to relaunch after a reboot.
 */
@Composable
private fun BootAppsNavRow(count: Int, onOpen: () -> Unit) {
  SectionLabel("Start on boot")
  Card {
    Row(
        modifier = Modifier.fillMaxWidth().tvFocusableRow { onOpen() }.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Apps that start on boot", color = Color.White, fontSize = 17.sp)
        Text(
            if (count == 0) "None — pick apps that relaunch after a reboot"
            else "$count app${if (count == 1) "" else "s"} relaunch after a reboot",
            color = Color(0xFF9A9A9A),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
      }
      Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
    }
  }
  Text(
      "Handy for players like Music Assistant that don't restart themselves after a reboot.",
      color = Color(0xFF7C7C7C),
      fontSize = 13.sp,
      modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
  )
}

/**
 * The "Start on boot" subpage: pick which installed apps Immortal relaunches after a
 * reboot — the same per-device list provisioning seeds (so e.g. the Music Assistant /
 * Sendspin player, which has no boot receiver, comes back on its own). Toggling a row
 * writes the list straight to the file [BootLaunch] reads on boot. Back returns to the
 * main settings page (handled by the parent via [onBack]).
 */
@Composable
internal fun BootAppsScreen(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
) {
  val context = LocalContext.current
  var apps by remember { mutableStateOf<List<BootAppOption>?>(null) }
  LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) } }

  // Open with the Back control focused so the remote can leave the subpage immediately.
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) onBack()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusGroup()) {
      Surface(
          color = Color(0xFF1C1C1E),
          shape = RoundedCornerShape(12.dp),
          modifier =
              Modifier.focusRequester(firstFocus).tvFocusable(RoundedCornerShape(12.dp)) { onBack() },
      ) {
        Text(
            "‹  Back",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
      }
      Spacer(Modifier.size(18.dp))

      Text("Start on boot", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Pick which installed apps Immortal relaunches after a reboot.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))

      Card {
        val list = apps
        if (list == null) {
          Text(
              "Loading apps…",
              color = Color(0xFF9A9A9A),
              fontSize = 14.sp,
              modifier = Modifier.padding(18.dp),
          )
        } else if (list.isEmpty()) {
          Text(
              "No other apps installed yet.",
              color = Color(0xFF9A9A9A),
              fontSize = 14.sp,
              modifier = Modifier.padding(18.dp),
          )
        } else {
          list.forEachIndexed { i, app ->
            if (i > 0) Divider()
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .tvFocusableRow { onToggle(app.pkg) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(34.dp))
              Text(
                  app.label,
                  color = Color.White,
                  fontSize = 16.sp,
                  modifier = Modifier.weight(1f).padding(start = 14.dp),
              )
              // Visual only — the row toggles it (so the remote's center button works).
              Switch(checked = app.pkg in selected, onCheckedChange = null)
            }
          }
        }
      }
      Text(
          "These apps relaunch automatically after a reboot — handy for players like Music " +
              "Assistant that don't restart themselves.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
  }
}

@Composable
private fun WallpaperSection() {
  val context = LocalContext.current
  var mode by remember { mutableStateOf(WallpaperConfig.load(context).mode) }
  var grain by remember { mutableStateOf(WallpaperConfig.load(context).grain) }

  Spacer(Modifier.size(26.dp))
  SectionLabel("Wallpaper")
  Card {
    Column(modifier = Modifier.padding(18.dp)) {
      Text("Background", color = Color.White, fontSize = 17.sp)
      Text(
          "A gradient, a photo, or sync with your screensaver (shown blurred). Tap to choose.",
          color = Color(0xFF9A9A9A),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp),
      )
      Spacer(Modifier.size(14.dp))
      Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        WallpaperSwatch("Dark", selected = mode == WallpaperConfig.DARK, onClick = {
          mode = WallpaperConfig.DARK
          WallpaperConfig.setMode(context, mode)
        }) {
          Spacer(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)))
        }
        WallpaperConfig.GRADIENTS.forEach { (id, colors) ->
          WallpaperSwatch(id.removePrefix("g_").replaceFirstChar { it.uppercase() }, selected = mode == id, onClick = {
            mode = id
            WallpaperConfig.setMode(context, id)
          }) {
            Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(colors.map { Color(it) })))
          }
        }
        WallpaperConfig.PHOTOS.forEach { (id, label) ->
          val thumb =
              remember(id) {
                runCatching {
                      context.assets.open("photoframe_fallback/${id.removePrefix(WallpaperConfig.PHOTO_PREFIX)}")
                          .use { android.graphics.BitmapFactory.decodeStream(it) }
                          ?.let { android.graphics.Bitmap.createScaledBitmap(it, 96, 96, true).asImageBitmap() }
                    }
                    .getOrNull()
              }
          WallpaperSwatch(label, selected = mode == id, onClick = {
            mode = id
            WallpaperConfig.setMode(context, id)
          }) {
            if (thumb != null) {
              Image(bitmap = thumb, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
              Spacer(Modifier.fillMaxSize().background(Color(0xFF333740)))
            }
          }
        }
        WallpaperSwatch("Screensaver", selected = mode == WallpaperConfig.SCREENSAVER, onClick = {
          mode = WallpaperConfig.SCREENSAVER
          WallpaperConfig.setMode(context, WallpaperConfig.SCREENSAVER)
        }) {
          Spacer(
              Modifier.fillMaxSize()
                  .background(Brush.verticalGradient(listOf(Color(0xFF2C3E50), Color(0xFF4CA1AF)))))
        }
        WallpaperSwatch("Sky", selected = mode == WallpaperConfig.SKY, onClick = {
          mode = WallpaperConfig.SKY
          WallpaperConfig.setMode(context, WallpaperConfig.SKY)
        }) {
          Spacer(
              Modifier.fillMaxSize()
                  .background(Brush.verticalGradient(listOf(Color(0xFF3A7BD5), Color(0xFF8FD3F4)))))
        }
        WallpaperSwatch("Star field", selected = mode == WallpaperConfig.STARFIELD, onClick = {
          mode = WallpaperConfig.STARFIELD
          WallpaperConfig.setMode(context, WallpaperConfig.STARFIELD)
        }) {
          Spacer(
              Modifier.fillMaxSize()
                  .background(Brush.verticalGradient(listOf(Color(0xFF05060F), Color(0xFF0C1430)))))
        }
      }
      Divider()
      Row(
          modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Film grain", color = Color.White, fontSize = 17.sp)
          Text(
              "Adds a subtle grain texture over the wallpaper.",
              color = Color(0xFF9A9A9A),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 2.dp),
          )
        }
        Segmented(
            options = listOf("Off" to "off", "On" to "on"),
            selected = if (grain) "on" else "off",
            onSelect = {
              val on = it == "on"
              grain = on
              WallpaperConfig.setGrain(context, on)
            },
        )
      }
    }
  }
}

@Composable
private fun WallpaperSwatch(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.tvFocusable(RoundedCornerShape(16.dp)) { onClick() },
  ) {
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier.size(74.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) Color(0xFF2E6BE6) else Color(0x33FFFFFF),
                    shape = RoundedCornerShape(16.dp),
                ),
    ) {
      preview()
    }
    Text(
        label,
        color = if (selected) Color.White else Color(0xFFBBBBBB),
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier.padding(top = 6.dp),
    )
  }
}

