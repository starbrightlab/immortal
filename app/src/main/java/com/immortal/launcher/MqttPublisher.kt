/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import org.json.JSONArray
import org.json.JSONObject

/** Live status string for the settings screen (mirrors MultiRoomStatus). */
object MqttStatus {
  @Volatile var text: String = ""
}

/**
 * Publishes this Portal to Home Assistant over MQTT Discovery, reusing the state immortal
 * already holds: [PresenceHub] (presence + screen), [NowPlayingHub] (media), the battery
 * broadcast, and [ScreenControl] for the one command we can honour. Owns a worker thread
 * with a connect → publish → read loop and a fixed-interval reconnect, like
 * [SnapcastControlClient].
 *
 * Topic layout (id = [MqttConfig.deviceId]):
 *  - discovery  `homeassistant/<component>/immortal_<id>/<obj>/config`  (retained)
 *  - state      `immortal/<id>/<obj>/state`                            (retained)
 *  - command    `immortal/<id>/<obj>/set`
 *  - availability `immortal/<id>/availability`  (LWT: offline / online)
 */
class MqttPublisher(private val appContext: Context) {
  @Volatile private var running = false
  private var worker: Thread? = null
  @Volatile private var client: MqttClient? = null
  @Volatile private var hasBattery = false
  private val main = Handler(Looper.getMainLooper())

  private val id = MqttConfig.deviceId(appContext)
  private val base = "immortal/$id"
  private val audio by lazy { appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

  /**
   * The Portal's ambient sensors. Owned by the publisher rather than the app because they exist
   * only to feed Home Assistant entities: they run exactly while a broker is connected, and cost
   * an un-configured device nothing. (Presence is the opposite case — every [PresenceHub] reader
   * wants it — so that one lives in [PortalPresenceDetector], app-wide.)
   */
  private val ambient by lazy {
    AmbientSensors(appContext) { kind, value ->
      client?.publish("$base/${kind.key}/state", value, retain = true)
    }
  }

  private val presenceListener = PresenceHub.Listener { st -> runCatching { publishPresence(st) } }
  private val nowPlayingListener = NowPlayingHub.Listener { st -> runCatching { publishMedia(st) } }
  private var batteryReceiver: BroadcastReceiver? = null
  private var screenReceiver: BroadcastReceiver? = null
  private var micMuteReceiver: BroadcastReceiver? = null

  fun start() {
    if (running) return
    running = true
    worker = Thread { loop() }.apply {
      isDaemon = true
      name = "mqtt-publisher"
      start()
    }
  }

  /**
   * Stop publishing. When [clearDiscovery] is true (a deliberate disable, not a transient
   * service kill), first remove this device's entities from HA by publishing empty retained
   * configs — otherwise HA would keep showing the Portal forever as "unavailable".
   */
  fun stop(removeFromHa: Boolean) {
    running = false
    val c = client // still connected here; the read loop hasn't torn it down yet
    Log.i(TAG, "stop(removeFromHa=$removeFromHa) connected=${c != null}")
    detach()
    if (removeFromHa && c != null) {
      // stop() runs on the main thread (Service.onDestroy), so the socket writes can't go
      // here (NetworkOnMainThreadException). Do the removal — empty retained configs so HA
      // drops the entities rather than leaving them "unavailable" — then a clean DISCONNECT
      // (suppresses the LWT) on a worker, and block briefly so it actually reaches the broker.
      val t =
          Thread {
                runCatching {
                      clearDiscovery(c)
                      Thread.sleep(300) // let the retained clears flush before we disconnect
                      c.disconnect()
                      Log.i(TAG, "teardown: cleared ${allEntities.size} entity configs")
                    }
                    .onFailure { Log.w(TAG, "teardown clear failed", it) }
              }
              .apply {
                isDaemon = true
                start()
              }
      runCatching { t.join(2500) } // onDestroy can wait a moment; ANR budget is generous
    }
    runCatching { c?.close() }
    client = null
    worker?.interrupt()
    worker = null
    MqttStatus.text = "Off"
  }

  /**
   * Re-read the feature toggles and republish, without dropping the broker connection. Called
   * when the user changes a sensor/presence setting: [MqttService.sync] alone would not do it,
   * because starting an already-running service re-enters `onStartCommand` and touches nothing.
   *
   * The sensors are restarted rather than left alone so that a changed temperature offset shows
   * up at once — re-registering replays each sensor's current reading — instead of waiting for
   * the room to move. Current state is republished too, so this is also the deterministic
   * "something changed underneath you, catch up" nudge for callers outside the publisher.
   */
  fun reconfigure() {
    val c = client ?: return
    Thread {
          runCatching {
                ambient.stop()
                if (MqttConfig.ambientSensors(appContext)) ambient.start()
                publishDiscovery(c)
                publishPresence(PresenceHub.current)
                publishAudioState()
                publishStreamState()
              }
              .onFailure { Log.w(TAG, "reconfigure failed", it) }
        }
        .apply {
          isDaemon = true
          name = "mqtt-reconfigure"
          start()
        }
  }

  // --- connection lifecycle ---------------------------------------------------

  private fun loop() {
    while (running) {
      val host = MqttConfig.host(appContext)
      if (host.isBlank()) {
        MqttStatus.text = "No broker set"
        sleep(BACKOFF_MS)
        continue
      }
      val c =
          MqttClient(
              host = host,
              port = MqttConfig.port(appContext),
              clientId = "immortal-$id",
              username = MqttConfig.username(appContext),
              password = MqttConfig.password(appContext),
              will = MqttClient.Will("$base/availability", "offline", retain = true),
              tls =
                  if (MqttConfig.useTls(appContext))
                      MqttClient.Tls(validateCert = MqttConfig.validateCert(appContext))
                  else null,
          )
      MqttStatus.text = "Connecting to $host…"
      val ok = runCatching { c.connect(KEEPALIVE_SEC) }.getOrDefault(false)
      if (!ok) {
        runCatching { c.close() }
        MqttStatus.text = "Can't reach broker at $host"
        sleep(BACKOFF_MS)
        continue
      }
      client = c
      MqttStatus.text = "Connected to $host"
      Log.i(TAG, "connected to $host:${MqttConfig.port(appContext)}")

      val pinger =
          Thread {
                while (running && client === c) {
                  sleep(PING_MS)
                  if (running && client === c) {
                    runCatching { c.ping() }.getOrElse { return@Thread }
                    // Heartbeat: refresh live-derived state so HA stays current even if the
                    // Portal's screen on/off broadcasts are unreliable (they are, for admin
                    // sleep) — keeps the screen sensor and presence from going stale.
                    runCatching { publishScreen() }
                    runCatching { publishPresence(PresenceHub.current) }
                    // Audio for the same reason: the system volume keys and any local control
                    // move these without telling us, so HA's slider and switches would sit on
                    // whatever we last published until the next reconnect.
                    runCatching { publishAudioState() }
                    runCatching { publishStreamState() }
                  }
                }
              }
              .apply {
                isDaemon = true
                name = "mqtt-ping"
                start()
              }

      runCatching {
            hasBattery = readBatteryPresent()
            publishDiscovery(c)
            c.publish("$base/availability", "online", retain = true)
            c.subscribe("$base/+/set")
            attach() // hub listeners + battery receiver replay current state immediately
            while (running) {
              val pkt = c.readPacket() ?: break
              if (pkt.type == 0x30) {
                val (t, p) = c.parsePublish(pkt)
                // Drop retained set-topic deliveries — both the broker's protocol-mandated
                // replay on (re)subscribe AND any future producer publish with retain=true.
                // Command topics should never be retained; if one is, dropping it avoids
                // replaying stale doorbells on every reconnect.
                val retained = (pkt.flags and 0x01) != 0
                if (retained) {
                  Log.i(TAG, "ignoring retained set-topic message: $t")
                } else {
                  handleCommand(t, String(p, Charsets.UTF_8))
                }
              }
            }
          }
          .onFailure { if (running) Log.w(TAG, "connection ended: ${it.message}") }

      detach()
      pinger.interrupt()
      client = null
      runCatching { c.close() }
      if (running) {
        MqttStatus.text = "Reconnecting…"
        sleep(BACKOFF_MS)
      }
    }
    MqttStatus.text = "Off"
  }

  private fun attach() {
    // Each addListener replays current state immediately, so this also does the initial publish.
    PresenceHub.addListener(presenceListener)
    NowPlayingHub.addListener(nowPlayingListener)
    val r =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) = runCatching { publishBattery(i) }.let {}
        }
    batteryReceiver = r
    // Registering for a sticky broadcast returns the current battery intent — publish it now.
    val sticky = appContext.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    sticky?.let { runCatching { publishBattery(it) } }
    // Keep the screen switch state accurate as the display turns on/off (PresenceHub's
    // screen field can lag; the system broadcast is immediate).
    val sr =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) = runCatching { publishScreen() }.let {}
        }
    screenReceiver = sr
    appContext.registerReceiver(
        sr,
        IntentFilter().apply {
          addAction(Intent.ACTION_SCREEN_ON)
          addAction(Intent.ACTION_SCREEN_OFF)
        })
    // Mic mute can be changed by anything on the device — a remapped remote button, another
    // app, the system — and HA's switch would then sit stale until the next reconnect. The
    // platform broadcasts every change from API 28 (every Portal), so follow that rather than
    // asking each caller to remember to republish.
    val mr =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) = runCatching { publishMicMute() }.let {}
        }
    micMuteReceiver = mr
    // The literal avoids gating on AudioManager.ACTION_MICROPHONE_MUTE_CHANGED (API 28) when
    // minSdk is 24; on an older device the action simply never fires.
    appContext.registerReceiver(mr, IntentFilter(ACTION_MIC_MUTE_CHANGED))
    publishScreen()
    publishIp()
    publishAudioState()
    publishStreamState()
    if (MqttConfig.ambientSensors(appContext)) ambient.start()
    // The service knows when it actually came up; publishing straight after sync() reported the
    // state from BEFORE the start, which read as the switch refusing to stay on.
    CameraStreamService.onStateChanged = { runCatching { publishStreamState() }.let {} }
  }

  private fun detach() {
    runCatching { ambient.stop() }
    CameraStreamService.onStateChanged = null
    runCatching { PresenceHub.removeListener(presenceListener) }
    runCatching { NowPlayingHub.removeListener(nowPlayingListener) }
    batteryReceiver?.let { r -> runCatching { appContext.unregisterReceiver(r) } }
    batteryReceiver = null
    screenReceiver?.let { r -> runCatching { appContext.unregisterReceiver(r) } }
    screenReceiver = null
    micMuteReceiver?.let { r -> runCatching { appContext.unregisterReceiver(r) } }
    micMuteReceiver = null
  }

  // --- commands (broker → device) ---------------------------------------------

  private fun handleCommand(topic: String, payload: String) {
    // topic = immortal/<id>/<obj>/set
    val obj = topic.removePrefix("$base/").removeSuffix("/set")
    Log.i(TAG, "command: $obj = $payload")
    main.post {
      runCatching {
        when (obj) {
          // Optimistic switch (no state_topic), so HA reflects the command itself — we don't
          // echo state. The Portal reports the screen as "interactive" for ~10s after
          // lockNow, so reading it back here would wrongly flip the switch on then off.
          "screen_power" ->
              if (payload.trim().equals("ON", ignoreCase = true)) ScreenControl.wake(appContext)
              else ScreenControl.sleep(appContext)
          "go_home" ->
              appContext.startActivity(
                  Intent(Intent.ACTION_MAIN)
                      .addCategory(Intent.CATEGORY_HOME)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          "open" -> openTarget(payload)
          "identify" ->
              Toast.makeText(appContext, "Immortal · ${FleetConfig.name(appContext)}", Toast.LENGTH_LONG)
                  .show()
          "media_play_pause" -> NowPlayingHub.playPause()
          "media_next" -> NowPlayingHub.next()
          "media_previous" -> NowPlayingHub.previous()
          "media_volume" -> {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val value = payload.trim().toIntOrNull()
            if (value != null) {
              audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(0, max), 0)
              publishMediaVolume()
              publishSpeakerMute()
            }
          }
          "speaker_mute" -> {
            val mute = payload.trim().equals("ON", ignoreCase = true)
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0,
            )
            publishSpeakerMute()
            publishMediaVolume()
          }
          "volume_up" -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            publishMediaVolume()
            publishSpeakerMute()
          }
          "volume_down" -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            publishMediaVolume()
            publishSpeakerMute()
          }
          "mic_mute" -> {
            val on = payload.trim().equals("ON", ignoreCase = true)
            audio.isMicrophoneMute = on
            // Echo the commanded state. isMicrophoneMute can return stale state
            // right after a set, so re-reading it here desyncs the HA switch
            // (mutes only every other press).
            publishMicMute(on)
          }
          // Streaming can only be switched on within consent already given on the device;
          // CameraStreamService.sync enforces that, and we report back what actually happened
          // rather than what was asked for.
          // No publish here: the service reports its own state once it has actually started or
          // stopped, through onStateChanged.
          "camera_stream" ->
              CameraStreamService.sync(appContext, payload.trim().equals("ON", ignoreCase = true))
          "camera_audio" -> {
            val on = payload.trim().equals("ON", ignoreCase = true)
            ImmortalSettings.setCameraAudio(appContext, on)
            // Audio is set up when the stream starts, so a change mid-stream needs a restart to
            // take effect — the SDP has to describe the track for a client to ask for it.
            if (CameraStreamService.running) {
              CameraStreamService.sync(appContext, false)
              CameraStreamService.sync(appContext, true)
            }
            publishStreamState()
          }
          "notify" -> handleNotify(payload)
          // Show the photo frame on demand — the same surface the launcher's header
          // screensaver button launches (HomeActivity.onStartScreensaver). This is the
          // in-app photo frame as a foreground Activity, not the system dream, so it stays
          // consistent with the header button: screen/state remains "interactive", and the
          // go_home command dismisses it. We don't try to start the system dream (no public
          // API; Somnambulator/IDreamManager reflection is device-fragile on Portal).
          "screensaver" ->
              appContext.startActivity(
                  Intent(appContext, PhotoFramePreviewActivity::class.java)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          else -> Log.w(TAG, "unknown command $obj")
        }
      }
    }
  }

  /**
   * Open whatever Home Assistant asked for via the "Open" entity. Accepts a full URL
   * (http/https → browser, homeassistant:// → HA), an installed package name (launch it),
   * or a bare HA dashboard path like "today-home/security" (deep-linked into the HA app).
   * Reuses [ScreensaverDismiss]'s HA helpers so the behaviour matches the screensaver picker.
   */
  private fun openTarget(payload: String) {
    if (!routeTarget(payload)) return
    // Echo the last target so the text entity shows what it was set to. Only the "Open"
    // entity echoes; notify taps route via routeTarget() directly so a doorbell tap doesn't
    // rewrite this entity's retained state.
    client?.publish("$base/open/state", payload.trim(), retain = true)
  }

  /**
   * Dispatch a target string without touching any entity state. Same grammar as [openTarget]
   * (full URL, installed package name, or bare HA dashboard path). Returns true when a target
   * was launched, false for a blank or unroutable input (e.g. no HA app installed for a path).
   * The grammar itself lives in [NotifyDispatch] so notify taps and the fleet agent route the
   * same way.
   */
  private fun routeTarget(payload: String): Boolean =
      NotifyDispatch.routeTarget(appContext, payload)

  /**
   * Render a notify payload (toast + optional sound + optional speech). Schema:
   * [NotifyPayload]; delivery: [NotifyDispatch]; behavior rules:
   * `docs/design/mqtt-notifications.md`. The handler is forgiving — malformed JSON is a
   * silent no-op; partial payloads use defaults.
   */
  private fun handleNotify(raw: String) {
    val spec = NotifyPayload.parse(raw) ?: return // empty/malformed/no-op
    NotifyDispatch.deliver(appContext, spec)
  }

  // --- state (device → broker) ------------------------------------------------

  private fun publishPresence(st: PresenceState) {
    val c = client ?: return
    // Use immortal's presence proxy when it has a reading; when it doesn't yet (UNKNOWN —
    // e.g. just after boot, before the first screensaver cycle) fall back to whether the
    // screen is on, a coarse "panel in use" signal, so the entity is never just
    // "unavailable". The `confident` attribute tells HA how far to trust it.
    val on =
        when (st.presence) {
          Presence.PRESENT -> true
          Presence.ABSENT -> false
          Presence.UNKNOWN -> isScreenOn()
        }
    c.publish("$base/presence/state", if (on) "ON" else "OFF", retain = true)
    c.publish(
        "$base/presence/attributes",
        JSONObject()
            .put("confident", st.confident)
            .put("source", presenceSource(st))
            .put("raw", st.presence.name.lowercase())
            .toString(),
        retain = true,
    )
    publishScreen()
  }

  /**
   * How much to trust the presence entity, for the `source` attribute: `portal` is Meta's own
   * camera detector (direct observation), `proxy` the dream/sleep inference, and `screen` the
   * last-resort "is the panel on" fallback used while the proxy has no reading at all.
   */
  private fun presenceSource(st: PresenceState): String =
      when {
        st.source == PresenceSource.PORTAL -> "portal"
        st.presence == Presence.UNKNOWN -> "screen"
        else -> "proxy"
      }

  /**
   * Publish the screen-state sensor from the LIVE display state. immortal's PresenceHub
   * screen field only changes on dream/sleep/interaction events, so it can read "off" while
   * the panel is actually on — reconcile against PowerManager, using the proxy only to tell
   * interactive from the screensaver. (The switch is optimistic, so it has no state topic.)
   */
  private fun publishScreen() {
    val c = client ?: return
    val enum =
        when {
          !isScreenOn() -> "off"
          PresenceHub.current.screen == ScreenState.DREAMING -> "dreaming"
          else -> "interactive"
        }
    c.publish("$base/screen/state", enum, retain = true)
  }

  private fun isScreenOn(): Boolean =
      runCatching {
            (appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
          }
          .getOrDefault(false)

  private fun publishMedia(st: NowPlayingState?) {
    val c = client ?: return
    val state =
        when (st?.state) {
          PlaybackState.PLAYING -> "playing"
          PlaybackState.PAUSED -> "paused"
          else -> "idle"
        }
    c.publish("$base/media_state/state", state, retain = true)
    c.publish("$base/media_title/state", st?.title.orEmpty(), retain = true)
    c.publish("$base/media_artist/state", st?.artist.orEmpty(), retain = true)
  }

  private fun publishBattery(i: Intent) {
    if (!hasBattery) return
    val c = client ?: return
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level >= 0 && scale > 0) {
      c.publish("$base/battery/state", (level * 100 / scale).toString(), retain = true)
    }
    val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    c.publish("$base/charging/state", if (charging) "ON" else "OFF", retain = true)
  }

  /**
   * Whether the stream is live, and where to point a player at it. Reported from the service's
   * own state, so a stream that refused to start (no permission, camera busy) shows as off
   * rather than leaving the switch stuck on.
   */
  private fun publishStreamState() {
    val c = client ?: return
    if (!ImmortalSettings.cameraEnabled(appContext)) return
    c.publish("$base/camera_stream/state", if (CameraStreamService.running) "ON" else "OFF", retain = true)
    c.publish(
        "$base/camera_audio/state",
        if (ImmortalSettings.cameraAudio(appContext)) "ON" else "OFF",
        retain = true)
    val ip = currentIp()
    c.publish(
        "$base/stream_url/state",
        if (ip.isBlank()) "unknown" else "rtsp://$ip:${RtspServer.DEFAULT_PORT}/",
        retain = true,
    )
  }

  private fun publishIp() {
    client?.publish("$base/ip/state", currentIp().ifBlank { "unknown" }, retain = true)
  }

  /**
   * True when changing STREAM_MUSIC volume audibly does something on this device.
   *
   * The obvious gate, [AudioManager.isVolumeFixed], returns false on the Portal TV (verified on
   * ripley via dumpsys: mUseFixedVolume=false), so it can't carry this decision alone. What
   * actually happens there: setStreamVolume moves the STREAM_MUSIC index (and reads back
   * correctly), but HDMI output plays at full scale whatever the index says, and
   * adjustStreamVolume from an app is silently dropped by the firmware — the identical call from
   * an adb shell works. The audible volume path is the IR blaster to the TV, reachable only from
   * system volume-key events an app can't inject. So treat any device with an HDMI output as
   * fixed-volume: tablet Portals (Portal, Portal+, Go, Mini) have none, the Portal TV always has
   * one. isVolumeFixed stays OR-ed in for any firmware that does report fixed volume honestly.
   */
  private fun volumeIsControllable(): Boolean =
      !audio.isVolumeFixed &&
          audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
            it.type == AudioDeviceInfo.TYPE_HDMI
          }

  private fun publishAudioState() {
    // Volume/speaker state is only meaningful where volume changes are audible;
    // on the Portal TV those entities aren't published (see publishDiscovery).
    if (volumeIsControllable()) {
      publishMediaVolume()
      publishSpeakerMute()
    }
    publishMicMute()
  }

  private fun publishMediaVolume() {
    val c = client ?: return
    c.publish(
        "$base/media_volume/state",
        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toString(),
        retain = true,
    )
  }

  private fun publishSpeakerMute() {
    val c = client ?: return
    val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
    c.publish("$base/speaker_mute/state", if (muted) "ON" else "OFF", retain = true)
  }

  private fun publishMicMute(muted: Boolean = audio.isMicrophoneMute) {
    val c = client ?: return
    c.publish("$base/mic_mute/state", if (muted) "ON" else "OFF", retain = true)
  }

  private fun readBatteryPresent(): Boolean {
    val i =
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    return i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
  }

  // --- discovery --------------------------------------------------------------

  private fun publishDiscovery(c: MqttClient) {
    // Two-way screen control (wake / off) now that ScreenControl.wake exists, plus a
    // read-only detail sensor.
    switchEntity(c, "screen_power", "Screen", icon = "mdi:monitor", optimistic = true)
    sensor(c, "screen", "Screen state", icon = "mdi:monitor")
    // Pre-1.41 shipped a one-way "Screen off" button; remove it so it doesn't orphan.
    publishConfig(c, "button", "screen_off", null)
    binarySensor(c, "presence", "Presence", deviceClass = "occupancy", jsonAttributes = true)

    if (hasBattery) {
      sensor(c, "battery", "Battery", deviceClass = "battery", unit = "%", stateClass = "measurement")
      binarySensor(c, "charging", "Charging", deviceClass = "battery_charging")
    }

    // Ambient sensors, auto-detected. A Portal without the hardware never advertises the entity;
    // one that has it but with the feature switched off gets its retained config cleared, so a
    // previously-published entity doesn't linger in HA as "unavailable".
    val ambientKinds = if (MqttConfig.ambientSensors(appContext)) ambient.available() else emptyList()
    AmbientKind.values().forEach { kind ->
      if (kind in ambientKinds) {
        sensor(
            c,
            kind.key,
            kind.title,
            deviceClass = kind.deviceClass,
            unit = kind.unit,
            stateClass = "measurement",
        )
      } else {
        publishConfig(c, "sensor", kind.key, null)
      }
    }

    sensor(c, "media_state", "Media", icon = "mdi:music")
    sensor(c, "media_title", "Media title", icon = "mdi:music-note")
    sensor(c, "media_artist", "Media artist", icon = "mdi:account-music")
    button(c, "media_play_pause", "Play / pause", icon = "mdi:play-pause")
    button(c, "media_next", "Next track", icon = "mdi:skip-next")
    button(c, "media_previous", "Previous track", icon = "mdi:skip-previous")
    // Volume and speaker mute act on STREAM_MUSIC, which only works on Portals
    // that own their speakers (Portal, Portal+, Go, Mini). On the Portal TV the
    // index is writable but inaudible — HDMI plays at full scale regardless, and
    // the app-side adjust path is dropped by the firmware (see
    // volumeIsControllable). Publish these only where volume changes are audible.
    if (volumeIsControllable()) {
      numberEntity(
          c,
          "media_volume",
          "Media volume",
          icon = "mdi:volume-high",
          min = 0,
          max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
          step = 1,
      )
      switchEntity(c, "speaker_mute", "Speaker mute", icon = "mdi:volume-off")
      button(c, "volume_up", "Volume up", icon = "mdi:volume-plus")
      button(c, "volume_down", "Volume down", icon = "mdi:volume-minus")
    } else {
      // Fixed-volume device (Portal TV / HDMI): clear these in case an earlier
      // version published them, so they don't orphan in Home Assistant.
      publishConfig(c, "number", "media_volume", null)
      publishConfig(c, "switch", "speaker_mute", null)
      publishConfig(c, "button", "volume_up", null)
      publishConfig(c, "button", "volume_down", null)
    }
    switchEntity(c, "mic_mute", "Microphone mute", icon = "mdi:microphone-off")

    // The camera, only while the user has switched it on for this Portal. Off (the default)
    // clears the configs, so HA drops the entities rather than showing them dead.
    if (ImmortalSettings.cameraEnabled(appContext)) {
      switchEntity(c, "camera_stream", "Camera streaming", icon = "mdi:video")
      switchEntity(c, "camera_audio", "Camera audio", icon = "mdi:volume-high")
      sensor(c, "stream_url", "Stream URL", icon = "mdi:link-variant", diagnostic = true)
    } else {
      publishConfig(c, "switch", "camera_stream", null)
      publishConfig(c, "switch", "camera_audio", null)
      publishConfig(c, "sensor", "stream_url", null)
    }
    // Unconditional: 1.69-1.71 published a still-image camera and a snapshot button. They are
    // gone, and a retained discovery config outlives the version that wrote it — so clear them
    // for everyone, or every Portal upgraded from those versions keeps two dead entities in HA.
    publishConfig(c, "camera", "camera", null)
    publishConfig(c, "button", "snapshot", null)

    button(c, "go_home", "Home", icon = "mdi:home")
    button(c, "screensaver", "Screensaver", icon = "mdi:image-multiple")
    textEntity(c, "open", "Open", icon = "mdi:open-in-app")
    notifyEntity(c, "notify", "Notify")
    button(c, "identify", "Identify", icon = "mdi:bullhorn")
    sensor(c, "ip", "IP address", icon = "mdi:ip-network", diagnostic = true)
  }

  /** The (component, object) of every entity we publish — used to tear them down on disable. */
  private val allEntities: List<Pair<String, String>> =
      listOf(
          "switch" to "screen_power",
          "sensor" to "screen",
          "binary_sensor" to "presence",
          "sensor" to "battery",
          "binary_sensor" to "charging",
          "sensor" to "media_state",
          "sensor" to "media_title",
          "sensor" to "media_artist",
          "button" to "media_play_pause",
          "button" to "media_next",
          "button" to "media_previous",
          "number" to "media_volume",
          "switch" to "speaker_mute",
          "button" to "volume_up",
          "button" to "volume_down",
          "switch" to "mic_mute",
          "button" to "go_home",
          "button" to "screensaver",
          "text" to "open",
          "notify" to "notify",
          "button" to "identify",
          "sensor" to "ip",
          "camera" to "camera", // legacy (1.69-1.71 stills)
          "button" to "snapshot", // legacy (1.69-1.71 stills)
          "switch" to "camera_stream",
          "switch" to "camera_audio",
          "sensor" to "stream_url",
          "button" to "screen_off", // legacy (pre-1.41)
      ) + AmbientKind.values().map { "sensor" to it.key }

  /** Remove this device's entities from HA by clearing their retained discovery configs. */
  private fun clearDiscovery(c: MqttClient) {
    allEntities.forEach { (component, obj) -> publishConfig(c, component, obj, null) }
  }

  private fun device(): JSONObject =
      JSONObject()
          .put("identifiers", JSONArray().put("immortal_$id"))
          .put("name", FleetConfig.name(appContext))
          .put("model", Build.MODEL ?: "Portal")
          .put("manufacturer", "Meta")
          .put("sw_version", appVersion())

  private fun base(obj: String, name: String): JSONObject =
      JSONObject()
          .put("name", name)
          .put("unique_id", "immortal_${id}_$obj")
          .put("device", device())

  private fun sensor(
      c: MqttClient,
      obj: String,
      name: String,
      icon: String? = null,
      deviceClass: String? = null,
      unit: String? = null,
      stateClass: String? = null,
      diagnostic: Boolean = false,
  ) {
    val cfg =
        base(obj, name)
            .put("state_topic", "$base/$obj/state")
            .put("availability_topic", "$base/availability")
    icon?.let { cfg.put("icon", it) }
    deviceClass?.let { cfg.put("device_class", it) }
    unit?.let { cfg.put("unit_of_measurement", it) }
    stateClass?.let { cfg.put("state_class", it) }
    if (diagnostic) cfg.put("entity_category", "diagnostic")
    publishConfig(c, "sensor", obj, cfg)
  }

  private fun binarySensor(
      c: MqttClient,
      obj: String,
      name: String,
      deviceClass: String,
      jsonAttributes: Boolean = false,
  ) {
    val cfg =
        base(obj, name)
            .put("state_topic", "$base/$obj/state")
            .put("availability_topic", "$base/availability")
            .put("device_class", deviceClass)
            .put("payload_on", "ON")
            .put("payload_off", "OFF")
    if (jsonAttributes) cfg.put("json_attributes_topic", "$base/$obj/attributes")
    publishConfig(c, "binary_sensor", obj, cfg)
  }

  private fun button(c: MqttClient, obj: String, name: String, icon: String) {
    val cfg =
        base(obj, name)
            .put("command_topic", "$base/$obj/set")
            .put("payload_press", "PRESS")
            .put("availability_topic", "$base/availability")
            .put("icon", icon)
    publishConfig(c, "button", obj, cfg)
  }

  private fun switchEntity(
      c: MqttClient,
      obj: String,
      name: String,
      icon: String,
      optimistic: Boolean = false,
  ) {
    val cfg =
        base(obj, name)
            .put("command_topic", "$base/$obj/set")
            .put("availability_topic", "$base/availability")
            .put("payload_on", "ON")
            .put("payload_off", "OFF")
            .put("icon", icon)
    // Optimistic: no state_topic, so HA tracks the commanded state itself (avoids the
    // post-lockNow flap where the Portal still reports the screen on for ~10s).
    if (!optimistic) cfg.put("state_topic", "$base/$obj/state")
    publishConfig(c, "switch", obj, cfg)
  }

  private fun textEntity(c: MqttClient, obj: String, name: String, icon: String) {
    val cfg =
        base(obj, name)
            .put("command_topic", "$base/$obj/set")
            .put("state_topic", "$base/$obj/state")
            .put("availability_topic", "$base/availability")
            // It's really an automation target ("tell the panel to show X"), not a control
            // to mix in with the dashboard — config category keeps it off auto-dashboards.
            .put("entity_category", "config")
            .put("icon", icon)
    publishConfig(c, "text", obj, cfg)
  }

  private fun numberEntity(
      c: MqttClient,
      obj: String,
      name: String,
      icon: String,
      min: Int,
      max: Int,
      step: Int,
  ) {
    val cfg =
        base(obj, name)
            .put("command_topic", "$base/$obj/set")
            .put("state_topic", "$base/$obj/state")
            .put("availability_topic", "$base/availability")
            .put("mode", "slider")
            .put("min", min)
            .put("max", max)
            .put("step", step)
            .put("icon", icon)
    publishConfig(c, "number", obj, cfg)
  }

  /**
   * The MQTT `notify` discovery component (HA 2024.7+ `NotifyEntity`). HA exposes the
   * entity as a target for the `notify.send_message` action; only `message` reaches the
   * `command_template`, so Track 1 ships `{"message": "..."}` to the device. Rich payloads
   * use Track 2 (raw `mqtt.publish` to `<base>/notify/set`). See
   * `docs/design/mqtt-notifications.md` § *Home Assistant integration*.
   */
  private fun notifyEntity(c: MqttClient, obj: String, name: String) {
    val cfg =
        base(obj, name)
            .put("command_topic", "$base/$obj/set")
            .put("command_template", "{\"message\": {{ value|tojson }}}")
            .put("availability_topic", "$base/availability")
    publishConfig(c, "notify", obj, cfg)
  }

  /** Publish (or, when [cfg] is null, clear) a retained discovery config. */
  private fun publishConfig(c: MqttClient, component: String, obj: String, cfg: JSONObject?) {
    c.publish("homeassistant/$component/immortal_$id/$obj/config", cfg?.toString() ?: "", retain = true)
  }

  // --- misc -------------------------------------------------------------------

  private fun appVersion(): String =
      runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
          }
          .getOrDefault("?")

  private fun currentIp(): String =
      runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
                .orEmpty()
          }
          .getOrDefault("")

  private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }.let {}

  private companion object {
    const val TAG = "ImmortalMqtt"
    /** [android.media.AudioManager.ACTION_MICROPHONE_MUTE_CHANGED], usable below API 28. */
    const val ACTION_MIC_MUTE_CHANGED = "android.media.action.MICROPHONE_MUTE_CHANGED"
    const val KEEPALIVE_SEC = 45
    const val PING_MS = 20_000L
    const val BACKOFF_MS = 4_000L
  }
}
