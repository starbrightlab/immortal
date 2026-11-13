/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Build
import java.io.File
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Config + identity for the [FleetAgentService] — the on-device WiFi management
 * agent that a laptop tool drives to deploy/update/configure a fleet of Portals.
 * Mirrors the [ImmortalSettings] prefs idiom.
 *
 * Two extra responsibilities beyond plain prefs, both to bridge the
 * shell-privileged provisioning kit (uid `shell`, which can't read our
 * app-private prefs):
 *  - We mirror `{name, token, port, enabled}` to the EXTERNAL files dir
 *    (`…/files/fleet/agent.json`) — shell-readable, the same place the install
 *    queue lives ([InstallDaemon]) — so the kit can read the generated token
 *    back into its host-side inventory.
 *  - We read a kit-written `…/files/fleet/provision.json` (`{name?, enabled?}`)
 *    on start/boot and apply it, then delete it. That's how the `--fleet`
 *    provisioning step names a device and turns the agent on without needing
 *    app-private access.
 *
 * The agent is OFF by default: an un-provisioned device never opens a port.
 */
object FleetConfig {
  private const val PREFS = "fleet_agent"
  const val DEFAULT_PORT = 8723
  private const val KV_PREFIX = "kv:"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)

  fun setEnabled(c: Context, on: Boolean) {
    prefs(c).edit().putBoolean("enabled", on).apply()
    writeManifest(c)
  }

  /**
   * Accept notifications on `POST /notify` — a toast, a sound, and/or a spoken line sent by
   * anything on the LAN that holds the agent token.
   *
   * OFF by default and independent of the MQTT notify path: a Home Assistant automation
   * publishing to `immortal/<id>/notify/set` is unaffected by this flag (that surface has its
   * own enable switch in MQTT settings). This gates only the HTTP door.
   */
  fun notifyEnabled(c: Context): Boolean = prefs(c).getBoolean("notify_enabled", false)

  fun setNotifyEnabled(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("notify_enabled", on).apply()

  /** Friendly device/room name shown in the fleet dashboard. Defaults to the model. */
  fun name(c: Context): String =
      prefs(c).getString("name", null)?.ifBlank { null } ?: (Build.MODEL ?: "Portal")

  fun setName(c: Context, name: String) {
    prefs(c).edit().putString("name", name).apply()
    writeManifest(c)
  }

  fun port(c: Context): Int = prefs(c).getInt("port", DEFAULT_PORT)

  /**
   * The bearer token every request must carry. Generated once (SecureRandom, 32
   * hex chars) on first read and persisted, so it's stable for the kit/inventory.
   */
  fun token(c: Context): String {
    prefs(c).getString("token", null)?.let { if (it.isNotBlank()) return it }
    val fresh = newToken()
    prefs(c).edit().putString("token", fresh).apply()
    writeManifest(c)
    return fresh
  }

  private fun newToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  // --- free-form config bag (POST /config { "set": {...} }) ------------------
  // Arbitrary keys the laptop tool can push without a device release; an
  // allow-list in FleetRoutes additionally applies known keys to real subsystems.

  fun getValue(c: Context, key: String): String? = prefs(c).getString(KV_PREFIX + key, null)

  fun setValue(c: Context, key: String, value: String) {
    prefs(c).edit().putString(KV_PREFIX + key, value).apply()
  }

  fun allValues(c: Context): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for ((k, v) in prefs(c).all) {
      if (k.startsWith(KV_PREFIX) && v is String) out[k.removePrefix(KV_PREFIX)] = v
    }
    return out
  }

  // --- external-files bridge for the provisioning kit ------------------------

  private fun fleetDir(c: Context) = File(c.getExternalFilesDir(null), "fleet")

  /** Mirror identity to the shell-readable manifest so provisioning can read the token. */
  fun writeManifest(c: Context) {
    runCatching {
      val dir = fleetDir(c).apply { mkdirs() }
      val json =
          JSONObject()
              .put("name", name(c))
              .put("token", prefs(c).getString("token", "") ?: "")
              .put("port", port(c))
              .put("enabled", isEnabled(c))
      File(dir, "agent.json").writeText(json.toString())
    }
  }

  /**
   * Apply a kit-written `provision.json` (`{name?, enabled?}`) if present, then
   * delete it. Called from [FleetAgentService.ensureRunning] on start/boot.
   * Returns true if a file was found and consumed.
   */
  fun applyPendingProvisioning(c: Context): Boolean {
    val f = File(fleetDir(c), "provision.json")
    if (!f.exists()) return false
    runCatching {
      val o = JSONObject(f.readText())
      if (o.has("name")) setName(c, o.getString("name"))
      if (o.has("enabled")) setEnabled(c, o.getBoolean("enabled"))
      // Once enabled, force a token to exist so the manifest carries it for the kit.
      if (isEnabled(c)) token(c)
    }
    runCatching { f.delete() }
    writeManifest(c)
    return true
  }
}
