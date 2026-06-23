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
import android.net.Uri
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

  private val presenceListener = PresenceHub.Listener { st -> runCatching { publishPresence(st) } }
  private val nowPlayingListener = NowPlayingHub.Listener { st -> runCatching { publishMedia(st) } }
  private var batteryReceiver: BroadcastReceiver? = null
  private var screenReceiver: BroadcastReceiver? = null

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
    publishScreen()
    publishIp()
  }

  private fun detach() {
    runCatching { PresenceHub.removeListener(presenceListener) }
    runCatching { NowPlayingHub.removeListener(nowPlayingListener) }
    batteryReceiver?.let { r -> runCatching { appContext.unregisterReceiver(r) } }
    batteryReceiver = null
    screenReceiver?.let { r -> runCatching { appContext.unregisterReceiver(r) } }
    screenReceiver = null
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
          "notify" -> handleNotify(payload)
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
    val t = payload.trim()
    if (t.isBlank()) return
    val pm = appContext.packageManager
    when {
      t.startsWith("http://") || t.startsWith("https://") || t.startsWith("homeassistant://") ->
          appContext.startActivity(
              Intent(Intent.ACTION_VIEW, Uri.parse(t)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      pm.getLaunchIntentForPackage(t) != null ->
          appContext.startActivity(pm.getLaunchIntentForPackage(t)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      else -> {
        val pkg = ScreensaverDismiss.installedHaPackage(appContext) ?: return
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(ScreensaverDismiss.haDeepLink(t)))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }
    // Echo the last target so the text entity shows what it was set to.
    client?.publish("$base/open/state", t, retain = true)
  }

  /**
   * Render a notify payload (toast + optional sound). Schema: [NotifyPayload]; behavior
   * rules: `docs/design/mqtt-notifications.md`. The handler is forgiving — malformed JSON
   * is a silent no-op; partial payloads use defaults.
   */
  private fun handleNotify(raw: String) {
    val spec = NotifyPayload.parse(raw) ?: return // empty/malformed/no-op
    if (spec.hasVisual) {
      // wake_screen defaults to true. Always call wake when requested — idempotent if the
      // device is already interactive, dismisses the photo dream if it's in front (PowerManager
      // reports isInteractive=true while dreaming, so a screen-on check alone misses it; and
      // PresenceHub.current.screen only reflects THIS process's dream service, which doesn't
      // help if a sibling package owns the active dream). 3s auto-release wake lock, no harm.
      if (spec.wakeScreen) ScreenControl.wake(appContext)
      val tap: (() -> Unit)? = spec.onTap?.let { target -> { openTarget(target) } }
      NotificationOverlay.show(spec, tap)
    }
    if (!spec.sound.isNullOrBlank()) {
      val nm =
          appContext.getSystemService(Context.NOTIFICATION_SERVICE)
              as? android.app.NotificationManager
      val dndOff =
          nm == null ||
              nm.currentInterruptionFilter ==
                  android.app.NotificationManager.INTERRUPTION_FILTER_ALL
      if (dndOff) SoundPlayer.play(appContext, spec.sound, spec.volume)
      else Log.i(TAG, "DND active; suppressing notify sound")
    }
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
            .put("source", if (st.presence == Presence.UNKNOWN) "screen" else "proxy")
            .put("raw", st.presence.name.lowercase())
            .toString(),
        retain = true,
    )
    publishScreen()
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

  private fun publishIp() {
    client?.publish("$base/ip/state", currentIp().ifBlank { "unknown" }, retain = true)
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

    sensor(c, "media_state", "Media", icon = "mdi:music")
    sensor(c, "media_title", "Media title", icon = "mdi:music-note")
    sensor(c, "media_artist", "Media artist", icon = "mdi:account-music")
    button(c, "media_play_pause", "Play / pause", icon = "mdi:play-pause")
    button(c, "media_next", "Next track", icon = "mdi:skip-next")
    button(c, "media_previous", "Previous track", icon = "mdi:skip-previous")

    button(c, "go_home", "Home", icon = "mdi:home")
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
          "button" to "go_home",
          "text" to "open",
          "notify" to "notify",
          "button" to "identify",
          "sensor" to "ip",
          "button" to "screen_off", // legacy (pre-1.41)
      )

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
    const val KEEPALIVE_SEC = 45
    const val PING_MS = 20_000L
    const val BACKOFF_MS = 4_000L
  }
}
