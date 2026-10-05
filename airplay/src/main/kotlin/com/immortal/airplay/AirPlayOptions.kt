/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.airplay

import android.content.Context
import io.github.jqssun.airplay.Prefs

/**
 * Typed configuration for the receiver, owned by the host app.
 *
 * The vendored [io.github.jqssun.airplay.service.AirPlayService] reads its settings straight from
 * SharedPreferences via [Prefs] — around thirty call sites. Rewriting that to take an options
 * object would fork the single highest-churn file in the module, so instead this class *writes*
 * those same keys and the vendored code keeps reading them unchanged. The host still gets a typed,
 * validated surface; upstream still syncs cleanly.
 *
 * Only the settings a host realistically exposes are modelled here. Everything else keeps
 * upstream's default, which is what [Prefs] already encodes.
 *
 * Immortal maps its `airplay` settings domain onto this; portal-receiver's own Settings screen
 * writes the same prefs directly.
 */
data class AirPlayOptions(
    /**
     * Name advertised over mDNS. Never hardcode a model: portal-receiver seeds [android.os.Build.MODEL],
     * Immortal passes its `FleetConfig` device name. Blank falls back to upstream's default.
     */
    val deviceName: String,
    /** RAOP/AirPlay port. Upstream default is 7000. */
    val port: Int = Prefs.DEF_SERVER_PORT,
    /** Ask the sender for an on-screen PIN before accepting a connection. */
    val requirePin: Boolean = Prefs.DEF_REQUIRE_PIN,
    /** Advertise screen mirroring. Turn off for an audio-only receiver. */
    val allowMirroring: Boolean = Prefs.DEF_ADVERTISE_VIDEO,
    /** Advertise audio streaming — off hides the device from music senders entirely. */
    val allowAudio: Boolean = Prefs.DEF_ADVERTISE_AUDIO,
    /** Offer H.265/HEVC when the device has a decoder for it. */
    val h265: Boolean = Prefs.DEF_H265_ENABLED,
    /** Advertise the panel's real resolution instead of a fixed one. */
    val autoResolution: Boolean = Prefs.DEF_AUTO_RES,
    /** Fixed resolution as `WxH`, used only when [autoResolution] is false. */
    val resolution: String = Prefs.DEF_RESOLUTION,
    /** Maximum frame rate advertised to senders. */
    val maxFps: Int = Prefs.DEF_MAX_FPS,
    /** Bring the host's surface Activity to the front when a sender connects. */
    val launchOnConnect: Boolean = Prefs.DEF_LAUNCH_ON_CONNECT,
    /** Let a new sender take over from the current one instead of being refused. */
    val allowNewConnections: Boolean = Prefs.DEF_ALLOW_NEW_CONN,
) {

    /**
     * Persist these values into the prefs the vendored service reads.
     *
     * Call before starting the receiver, or from the host's settings-applied hook — the service
     * re-reads them on start, so a change takes effect on the next start/restart.
     */
    fun applyTo(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            // Written even when blank resolves to the default: skipping the key would leave the
            // *previous* name advertised, and a host comparing options objects would see no change
            // to apply, so the stale name would stick for good.
            putString(Prefs.SERVER_NAME, resolvedName())
            putInt(Prefs.SERVER_PORT, port.coerceIn(1, 65535))
            putBoolean(Prefs.REQUIRE_PIN, requirePin)
            putBoolean(Prefs.ADVERTISE_VIDEO, allowMirroring)
            putBoolean(Prefs.ADVERTISE_AUDIO, allowAudio)
            putBoolean(Prefs.H265_ENABLED, h265)
            putBoolean(Prefs.AUTO_RES, autoResolution)
            putString(Prefs.RESOLUTION, sanitizedResolution())
            putInt(Prefs.MAX_FPS, maxFps.coerceIn(1, 240))
            putBoolean(Prefs.LAUNCH_ON_CONNECT, launchOnConnect)
            putBoolean(Prefs.ALLOW_NEW_CONN, allowNewConnections)
        }.apply()
    }

    /** [deviceName] as it will be advertised: trimmed, or upstream's default when blank. */
    private fun resolvedName(): String =
        deviceName.trim().takeIf { it.isNotEmpty() } ?: Prefs.DEF_SERVER_NAME

    /**
     * Normalise [resolution] to something the service can actually parse, falling back to "auto".
     *
     * This has to happen here rather than being left to the setter: the service splits the stored
     * string on a literal lowercase "x" and calls `toInt()` on both halves with no guard, so a
     * value like "1920X1080", "1920*1080" or "1920x" throws out of `startServer` and takes the
     * foreground service down on *every* start attempt — a persisted crash loop from one bad
     * setting.
     */
    private fun sanitizedResolution(): String {
        val value = resolution.trim().lowercase()
        if (value == "auto" || value.isEmpty()) return Prefs.DEF_RESOLUTION
        val parts = value.split("x")
        if (parts.size != 2) return Prefs.DEF_RESOLUTION
        val w = parts[0].trim().toIntOrNull() ?: return Prefs.DEF_RESOLUTION
        val h = parts[1].trim().toIntOrNull() ?: return Prefs.DEF_RESOLUTION
        if (w !in 1..7680 || h !in 1..7680) return Prefs.DEF_RESOLUTION
        return "${w}x$h"
    }
}
