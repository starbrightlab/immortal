/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import com.immortal.airplay.AirPlayOptions

/**
 * Config for the AirPlay receiver (`:airplay`) — stream audio, mirror a screen, or hand over an
 * AirPlay video URL from an iPhone/iPad/Mac. Off by default; opening [AirPlayPairActivity] turns it
 * on, the way [RemotePairActivity] opts the phone remote in.
 *
 * No server-name field: the Portal advertises under [FleetConfig.name], the same value the phone
 * remote and Home Assistant use, so there is no second name to drift. [options] maps this config
 * onto the module's own settings, which it reads directly ([AirPlayOptions.applyTo] writes them).
 * The module keeps those in a prefs file named plain `settings`; Immortal's own files are all
 * `immortal_*`, so they don't collide.
 */
object AirPlayConfig {
  private const val PREFS = "airplay"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

  /** Ask the sender for an on-screen PIN before accepting a connection. */
  fun requirePin(c: Context): Boolean = prefs(c).getBoolean("require_pin", false)

  fun setRequirePin(c: Context, on: Boolean) = prefs(c).edit().putBoolean("require_pin", on).apply()

  /** Advertise screen mirroring / AirPlay video. Off leaves an audio-only speaker. */
  fun allowMirroring(c: Context): Boolean = prefs(c).getBoolean("allow_mirroring", true)

  fun setAllowMirroring(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("allow_mirroring", on).apply()

  /** Advertised mirroring resolution: "auto" (the real panel size) or `WxH`. */
  fun resolution(c: Context): String = prefs(c).getString("resolution", "auto") ?: "auto"

  fun setResolution(c: Context, v: String) = prefs(c).edit().putString("resolution", v).apply()

  /** Bring [AirPlayActivity] to the front when a *video* session starts. */
  fun showOnConnect(c: Context): Boolean = prefs(c).getBoolean("show_on_connect", true)

  fun setShowOnConnect(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("show_on_connect", on).apply()

  /** Immutable snapshot, so the settings domain renders through the generic on-device list. */
  data class Settings(
      val enabled: Boolean = false,
      val requirePin: Boolean = false,
      val allowMirroring: Boolean = true,
      val resolution: String = "auto",
      val showOnConnect: Boolean = true,
  )

  fun load(c: Context): Settings =
      Settings(
          enabled = isEnabled(c),
          requirePin = requirePin(c),
          allowMirroring = allowMirroring(c),
          resolution = resolution(c),
          showOnConnect = showOnConnect(c),
      )

  /**
   * This config as the module's typed options.
   *
   * `launchOnConnect` is pinned OFF regardless of [showOnConnect]: the module launches the surface
   * Activity at TCP connect, before the session's kind is known, which would tear the screensaver
   * down for an audio-only stream. [AirPlayControl] launches [AirPlayActivity] itself once a real
   * video session is confirmed, and gates that on [showOnConnect].
   */
  fun options(c: Context): AirPlayOptions {
    val s = load(c)
    return AirPlayOptions(
        deviceName = FleetConfig.name(c),
        requirePin = s.requirePin,
        allowMirroring = s.allowMirroring,
        // "auto" means "advertise the real panel resolution"; a fixed WxH overrides it.
        autoResolution = s.resolution.equals("auto", ignoreCase = true),
        resolution = s.resolution,
        launchOnConnect = false,
    )
  }
}
