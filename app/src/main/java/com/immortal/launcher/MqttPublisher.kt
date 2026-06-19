/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

  fun start() {
    if (running) return
    running = true
    worker = Thread { loop() }.apply {
      isDaemon = true
      name = "mqtt-publisher"
      start()
    }
  }

  fun stop() {
    running = false
    detach()
    runCatching { client?.disconnect() }
    runCatching { client?.close() }
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
                  if (running && client === c) runCatching { c.ping() }.getOrElse { return@Thread }
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
              if (pkt.type == 0x30) handleCommand(c.parsePublish(pkt).first)
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
    publishIp()
  }

  private fun detach() {
    runCatching { PresenceHub.removeListener(presenceListener) }
    runCatching { NowPlayingHub.removeListener(nowPlayingListener) }
    batteryReceiver?.let { r -> runCatching { appContext.unregisterReceiver(r) } }
    batteryReceiver = null
  }

  // --- commands (broker → device) ---------------------------------------------

  private fun handleCommand(topic: String) {
    // topic = immortal/<id>/<obj>/set
    val obj = topic.removePrefix("$base/").removeSuffix("/set")
    Log.i(TAG, "command: $obj")
    main.post {
      runCatching {
        when (obj) {
          "screen_off" -> ScreenControl.sleep(appContext)
          "identify" ->
              Toast.makeText(appContext, "Immortal · ${FleetConfig.name(appContext)}", Toast.LENGTH_LONG)
                  .show()
          "media_play_pause" -> NowPlayingHub.playPause()
          "media_next" -> NowPlayingHub.next()
          "media_previous" -> NowPlayingHub.previous()
          else -> Log.w(TAG, "unknown command $obj")
        }
      }
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
    c.publish("$base/screen/state", st.screen.name.lowercase(), retain = true)
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
    sensor(c, "screen", "Screen", icon = "mdi:monitor")
    button(c, "screen_off", "Screen off", icon = "mdi:monitor-off")
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

    button(c, "identify", "Identify", icon = "mdi:bullhorn")
    sensor(c, "ip", "IP address", icon = "mdi:ip-network", diagnostic = true)
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

  private fun publishConfig(c: MqttClient, component: String, obj: String, cfg: JSONObject) {
    c.publish("homeassistant/$component/immortal_$id/$obj/config", cfg.toString(), retain = true)
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
