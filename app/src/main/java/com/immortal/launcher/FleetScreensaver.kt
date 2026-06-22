/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONObject

/**
 * Screensaver slice of the Fleet Agent API (see [FleetRoutes] `/screensaver`). Lets
 * the laptop fleet tool read and push the whole photo-frame configuration over WiFi
 * — source, fit, interval, shuffle, videos, now-playing, presence/power, and the
 * idle/overnight screen-off windows — so a wall of Portals can be set up without
 * wireless ADB or a per-device tap-through. (The calendar widget has its own
 * [FleetCalendar] / `/calendar` endpoint and is intentionally left out here.)
 *
 * Like [FleetCalendar], the JSON shaping is split out so it's JVM-unit-testable:
 * [toJson] is a pure `Settings → JSON` mapping; [apply] is the only Context-touching
 * part and just funnels recognised keys to the existing [ScreensaverConfig] setters,
 * which already clamp/validate. Only keys actually present in the body are touched,
 * so a partial push leaves everything else alone.
 *
 * Note: display changes (source/fit/interval/…) take effect on the next screensaver
 * cycle, exactly as they do from the in-app settings screen — the agent doesn't
 * force-restart a running dream. The `enabled` and overnight changes apply right
 * away ([FleetRoutes] reaffirms ownership and reschedules the overnight window).
 */
object FleetScreensaver {

  /**
   * Pure render of the screensaver display settings — delegates to the `screensaver` settings
   * domain ([com.immortal.launcher.settings.SettingsDomains.screensaver]), which owns the flat wire
   * format declaratively. (`apply` below is not yet routed through the domain — see that domain's
   * note — so this is the read half of the façade only.)
   */
  fun toJson(s: ScreensaverConfig.Settings): JSONObject =
      com.immortal.launcher.settings.SettingsDomains.screensaver.flatJson(s)

  /**
   * The photo-source setup the remote's Setup form reads to pre-fill — the active source type plus
   * every source's stored fields (so editing round-trips). Served only to a paired remote on the
   * LAN; the secret fields (Immich key, share/WebDAV passwords) are included for that pre-fill,
   * matching the on-Portal connect screens.
   */
  fun sourcesJson(s: ScreensaverConfig.Settings): JSONObject =
      JSONObject()
          .put("source", currentSource(s))
          .put("immichUrl", s.immichUrl ?: "")
          .put("immichKey", s.immichKey ?: "")
          .put("smbHost", s.smbHost ?: "")
          .put("smbShare", s.smbShare ?: "")
          .put("smbPath", s.smbPath ?: "")
          .put("smbUser", s.smbUser ?: "")
          .put("smbPass", s.smbPass ?: "")
          .put("davUrl", s.davUrl ?: "")
          .put("davUser", s.davUser ?: "")
          .put("davPass", s.davPass ?: "")
          .put("webUrl", s.webUrl ?: "")
          .put("albumUrl", s.albumUrl ?: "")

  /** The active photo-source as a Setup-form key (immich/smb/dav/web/album/default). Pure. */
  internal fun currentSource(s: ScreensaverConfig.Settings): String =
      when {
        s.usesImmich -> "immich"
        s.usesSmb -> "smb"
        s.usesDav -> "dav"
        s.usesWebUrl -> "web"
        s.usesUrl -> "album"
        else -> "default"
      }

  /** Coerce a fit string to a known value, or null if unrecognised. Pure. */
  internal fun coerceFit(v: String?): String? =
      when (v) {
        ScreensaverConfig.FIT_FILL, ScreensaverConfig.FIT_FIT -> v
        else -> null
      }

  /**
   * Parse a presence-mode name, or null if unrecognised. Pure. Returning null (rather
   * than defaulting) lets [apply] skip an unknown value instead of silently flipping
   * the mode on a typo — the same fail-safe shape as [coerceFit].
   */
  internal fun coercePresenceMode(v: String?): FrameMode? =
      runCatching { FrameMode.valueOf((v ?: "").uppercase()) }.getOrNull()

  /**
   * Apply a pushed screensaver config. Returns the list of applied keys, plus a flag
   * (via [applied] containing any "overnight*" key) the caller uses to reschedule the
   * overnight window.
   */
  fun apply(context: Context, body: JSONObject): List<String> {
    val applied = ArrayList<String>()

    if (body.has("enabled")) {
      ScreensaverConfig.setEnabled(context, body.optBoolean("enabled"))
      applied.add("enabled")
    }
    // Source: "default" resets to the built-in feed; folder/url are driven by their
    // value keys below (which also flip the source), so an explicit folder/url here
    // is a no-op unless its path/url is supplied.
    if (body.has("source") && body.optString("source") == ScreensaverConfig.SOURCE_DEFAULT) {
      ScreensaverConfig.useDefault(context)
      applied.add("source")
    }
    if (body.has("folderPath")) {
      val p = body.optString("folderPath")
      if (p.isNotBlank()) {
        ScreensaverConfig.setFolder(context, p)
        applied.add("folderPath")
      }
    }
    if (body.has("albumUrl")) {
      val u = body.optString("albumUrl")
      if (u.isNotBlank()) {
        ScreensaverConfig.setAlbumUrl(context, u)
        applied.add("albumUrl")
      }
    }
    // Credentialed photo sources (the remote's Setup form / fleet push). Each is atomic — it only
    // applies when its required fields are present — so a partial push or a different source's
    // fields don't accidentally switch the source. Mirrors the same ScreensaverConfig setters the
    // on-Portal connect screens use.
    run {
      val url = body.optString("immichUrl")
      val key = body.optString("immichKey")
      if (url.isNotBlank() && key.isNotBlank()) {
        ScreensaverConfig.setImmich(context, url, key)
        applied.add("immich")
      }
    }
    run {
      val host = body.optString("smbHost")
      val share = body.optString("smbShare")
      if (host.isNotBlank() && share.isNotBlank()) {
        ScreensaverConfig.setSmb(
            context, host, share, body.optString("smbPath"), body.optString("smbUser"), body.optString("smbPass"))
        applied.add("smb")
      }
    }
    run {
      val url = body.optString("davUrl")
      if (url.isNotBlank()) {
        ScreensaverConfig.setDav(context, url, body.optString("davUser"), body.optString("davPass"))
        applied.add("dav")
      }
    }
    run {
      val url = body.optString("webUrl")
      if (url.isNotBlank()) {
        ScreensaverConfig.setWebUrl(context, url)
        applied.add("webUrl")
      }
    }
    if (body.has("albumRefreshMin")) {
      ScreensaverConfig.setAlbumRefreshMin(context, body.optInt("albumRefreshMin"))
      applied.add("albumRefreshMin")
    }
    coerceFit(if (body.has("fit")) body.optString("fit") else null)?.let {
      ScreensaverConfig.setFit(context, it)
      applied.add("fit")
    }
    if (body.has("intervalSec")) {
      ScreensaverConfig.setInterval(context, body.optInt("intervalSec"))
      applied.add("intervalSec")
    }
    if (body.has("shuffle")) {
      ScreensaverConfig.setShuffle(context, body.optBoolean("shuffle"))
      applied.add("shuffle")
    }
    if (body.has("includeVideo")) {
      ScreensaverConfig.setIncludeVideo(context, body.optBoolean("includeVideo"))
      applied.add("includeVideo")
    }
    if (body.has("batterySaver")) {
      ScreensaverConfig.setBatterySaver(context, body.optBoolean("batterySaver"))
      applied.add("batterySaver")
    }
    if (body.has("showNowPlaying")) {
      ScreensaverConfig.setShowNowPlaying(context, body.optBoolean("showNowPlaying"))
      applied.add("showNowPlaying")
    }
    if (body.has("presenceMode")) {
      // Ignore an unrecognised mode rather than defaulting (which would silently flip
      // the setting on a typo); a valid value is applied.
      coercePresenceMode(body.optString("presenceMode"))?.let {
        ScreensaverConfig.setPresenceMode(context, it)
        applied.add("presenceMode")
      }
    }
    if (body.has("idleSleepMin")) {
      ScreensaverConfig.setIdleSleepMin(context, body.optInt("idleSleepMin"))
      applied.add("idleSleepMin")
    }
    if (body.has("overnightEnabled")) {
      ScreensaverConfig.setOvernightEnabled(context, body.optBoolean("overnightEnabled"))
      applied.add("overnightEnabled")
    }
    if (body.has("overnightStartMin")) {
      ScreensaverConfig.setOvernightStartMin(context, body.optInt("overnightStartMin"))
      applied.add("overnightStartMin")
    }
    if (body.has("overnightEndMin")) {
      ScreensaverConfig.setOvernightEndMin(context, body.optInt("overnightEndMin"))
      applied.add("overnightEndMin")
    }
    return applied
  }
}
