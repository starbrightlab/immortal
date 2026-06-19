/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.security.SecureRandom

/**
 * Config for the [MqttService] — the on-device MQTT publisher that exposes this Portal
 * to Home Assistant via MQTT Discovery. Mirrors the [FleetConfig] / [ImmortalSettings]
 * prefs idiom.
 *
 * Off by default: an un-configured device never opens a connection. The HA device name
 * is shared with the fleet agent ([FleetConfig.name]) so a Portal shows up under one name
 * everywhere.
 */
object MqttConfig {
  private const val PREFS = "mqtt_publisher"
  const val DEFAULT_PORT = 1883

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

  fun host(c: Context): String = prefs(c).getString("host", "")?.trim().orEmpty()

  fun setHost(c: Context, v: String) = prefs(c).edit().putString("host", v.trim()).apply()

  fun port(c: Context): Int = prefs(c).getInt("port", DEFAULT_PORT)

  fun setPort(c: Context, p: Int) =
      prefs(c).edit().putInt("port", if (p in 1..65535) p else DEFAULT_PORT).apply()

  fun username(c: Context): String = prefs(c).getString("username", "")?.trim().orEmpty()

  fun setUsername(c: Context, v: String) = prefs(c).edit().putString("username", v.trim()).apply()

  fun password(c: Context): String = prefs(c).getString("password", "").orEmpty()

  fun setPassword(c: Context, v: String) = prefs(c).edit().putString("password", v).apply()

  /** True once a broker host is set — the publisher stays idle until then. */
  fun isConfigured(c: Context): Boolean = host(c).isNotBlank()

  /**
   * A stable per-device id used in MQTT topics and the HA `unique_id` / device-registry
   * identifiers. Generated once (SecureRandom, 16 hex chars) and persisted, so the HA
   * device survives reinstalls of the broker but stays unique across a fleet.
   */
  fun deviceId(c: Context): String {
    prefs(c).getString("device_id", null)?.let { if (it.isNotBlank()) return it }
    val bytes = ByteArray(8)
    SecureRandom().nextBytes(bytes)
    val id = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    prefs(c).edit().putString("device_id", id).apply()
    return id
  }
}
