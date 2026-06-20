/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * How the frame relates to the Portal's presence-driven power policy.
 *
 *  - [ALWAYS_ON]  — pin the screen so the frame is permanent (the original wall-frame
 *    behaviour). The dream/sleep presence proxy is masked, so presence reads UNKNOWN and the
 *    music must defer to Home Assistant / a manual override.
 *  - [PRESENCE]   — don't pin: let the Portal's presence policy sleep the screen when the room
 *    empties and re-dream when someone returns. This is the shared baseline the screensaver and
 *    the music both follow (see docs/design/multi-room-audio.md → *Presence*). Confirmed on the Portal Go
 *    on battery; verify empty-room sleep on a mains Portal before making it the global default.
 */
enum class FrameMode {
  ALWAYS_ON,
  PRESENCE,
}

/**
 * User configuration for the photo-frame screensaver, persisted across restarts.
 *
 * The default source is Immortal's built-in photo feed; the user can instead point
 * the screensaver at a local folder of photos/videos — including one on a USB-C or
 * SD card plugged into the Portal (any folder reachable through the system file
 * picker) — or paste a public share link from iCloud or Google Photos. If the
 * chosen source can't be read (e.g. the drive is unplugged, the album was unshared)
 * the screensaver falls back to the default feed, so it's never blank.
 */
object ScreensaverConfig {

  private const val PREFS = "immortal_screensaver"

  const val SOURCE_DEFAULT = "default"
  const val SOURCE_FOLDER = "folder"
  const val SOURCE_URL = "url"
  const val SOURCE_IMMICH = "immich"
  const val SOURCE_SMB = "smb"
  const val SOURCE_DAV = "dav"
  const val SOURCE_WEBURL = "weburl"
  const val FIT_FILL = "fill" // crop to fill the screen
  const val FIT_FIT = "fit" // letterbox to show the whole image
  const val DEFAULT_INTERVAL = 30
  const val DEFAULT_ALBUM_REFRESH_MIN = 60

  data class Settings(
      // Master on/off for Immortal's photo-frame screensaver. When off, Immortal
      // stops asserting itself as the system Dream and lets the Portal sleep / lets
      // the user run their own screensaver (e.g. Home Assistant + Immich frame).
      val enabled: Boolean = true,
      val source: String = SOURCE_DEFAULT,
      val folderPath: String? = null,
      val albumUrl: String? = null,
      // Immich (self-hosted) connection. albumId/Name null = the whole library.
      val immichUrl: String? = null,
      val immichKey: String? = null,
      val immichAlbumId: String? = null,
      val immichAlbumName: String? = null,
      // SMB / network-share (NAS) connection. smbPath is the folder within the share.
      val smbHost: String? = null,
      val smbShare: String? = null,
      val smbPath: String? = null,
      val smbUser: String? = null,
      val smbPass: String? = null,
      // WebDAV: the full folder URL, with optional Basic-auth credentials.
      val davUrl: String? = null,
      val davUser: String? = null,
      val davPass: String? = null,
      // Web page: render an arbitrary URL fullscreen as the screensaver (the page supplies its
      // own clock/widgets, so Immortal's overlay is skipped). Covers Immich Kiosk, HA dashboards,
      // etc. A power-user "bring your own frame" option — not the promoted default.
      val webUrl: String? = null,
      val fit: String = FIT_FILL,
      val intervalSec: Int = DEFAULT_INTERVAL,
      val albumRefreshMin: Int = DEFAULT_ALBUM_REFRESH_MIN,
      val shuffle: Boolean = false,
      val includeVideo: Boolean = true,
      // Battery models (Portal Go) only: pause the screensaver while unplugged so
      // the device can actually sleep, instead of showing photos until empty.
      val batterySaver: Boolean = true,
      // Show the current track + album art on the frame while music is playing.
      val showNowPlaying: Boolean = true,
      // Whether to draw a clock face at all. Off = photos only (the now-playing card still follows
      // its own [showNowPlaying] switch). On by default.
      val facesEnabled: Boolean = true,
      // The selected clock face — a [FaceCatalog] entry id. Drives the screensaver overlay.
      val faceId: String = "immortal-classic",
      // Clock size for faces that offer size variants (0 = Small, 1 = Medium, 2 = Large). Ignored
      // by faces without variants. See [FaceCatalog].
      val faceSizeIndex: Int = 1,
      // Whether the frame is pinned on (ALWAYS_ON) or follows the Portal's presence policy
      // (PRESENCE — the shared screensaver/music baseline). Defaults to ALWAYS_ON to preserve
      // the original permanent-frame behaviour until PRESENCE is verified on mains hardware.
      val presenceMode: FrameMode = FrameMode.ALWAYS_ON,
      // Idle screen-off (off by default): minutes the screensaver runs before the
      // screen turns off. 0 = never (Immortal's always-on photo frame).
      val idleSleepMin: Int = 0,
      // Overnight screen-off (off by default): keep the screen off between two times
      // each night. Times are minutes-from-midnight (e.g. 22:00 = 1320).
      val overnightEnabled: Boolean = false,
      val overnightStartMin: Int = 22 * 60,
      val overnightEndMin: Int = 7 * 60,
      // What the overnight window shows. false = go dark (screen off). true = a dimmed flip
      // clock as a bedside clock, kept on through the window. Only meaningful when
      // [overnightEnabled].
      val overnightNightClock: Boolean = false,
      // What to open when the user taps the frame to dismiss it. null = the Immortal
      // launcher (the original behaviour). Otherwise a flattened ComponentName of an
      // installed app — Home Assistant users typically point this at their HA app so a
      // tap drops them straight back into their dashboard. See [ScreensaverDismiss].
      val dismissAppComponent: String? = null,
      // Home Assistant dashboard mode. When non-null, a tap opens the HA companion app
      // (taking precedence over [dismissAppComponent]); a non-blank value is the
      // dashboard path to deep-link to (e.g. "today-home/security"), and "" opens the
      // user's default dashboard. Only offered when an HA app is installed.
      val dismissHaDashboard: String? = null,
  ) {
    /** True when the idle screen-off timeout is active. */
    val idleSleepOn: Boolean
      get() = idleSleepMin > 0
    /** True when the user has chosen a local folder for us to read. */
    val usesFolder: Boolean
      get() = source == SOURCE_FOLDER && !folderPath.isNullOrBlank()
    /** True when the user has pasted a public album share link for us to fetch. */
    val usesUrl: Boolean
      get() = source == SOURCE_URL && !albumUrl.isNullOrBlank()
    /** True when the user has connected an Immich server for us to pull from. */
    val usesImmich: Boolean
      get() = source == SOURCE_IMMICH && !immichUrl.isNullOrBlank() && !immichKey.isNullOrBlank()
    /** True when the user has connected an SMB network share for us to read. */
    val usesSmb: Boolean
      get() = source == SOURCE_SMB && !smbHost.isNullOrBlank() && !smbShare.isNullOrBlank()
    /** True when the user has connected a WebDAV folder for us to read. */
    val usesDav: Boolean
      get() = source == SOURCE_DAV && !davUrl.isNullOrBlank()
    /** True when the screensaver should render an arbitrary web page. */
    val usesWebUrl: Boolean
      get() = source == SOURCE_WEBURL && !webUrl.isNullOrBlank()
  }

  /** Keep the slideshow interval sane (5s … 10min). */
  fun clampInterval(sec: Int): Int = sec.coerceIn(5, 600)

  /** Keep the album refresh sane (15 min … 24h). Floor matches the settings stepper. */
  fun clampAlbumRefresh(min: Int): Int = min.coerceIn(15, 24 * 60)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    return Settings(
        enabled = p.getBoolean("enabled", true),
        source = p.getString("source", SOURCE_DEFAULT) ?: SOURCE_DEFAULT,
        folderPath = p.getString("folder_path", null),
        albumUrl = p.getString("album_url", null),
        immichUrl = p.getString("immich_url", null),
        immichKey = p.getString("immich_key", null),
        immichAlbumId = p.getString("immich_album_id", null),
        immichAlbumName = p.getString("immich_album_name", null),
        smbHost = p.getString("smb_host", null),
        smbShare = p.getString("smb_share", null),
        smbPath = p.getString("smb_path", null),
        smbUser = p.getString("smb_user", null),
        smbPass = p.getString("smb_pass", null),
        davUrl = p.getString("dav_url", null),
        davUser = p.getString("dav_user", null),
        davPass = p.getString("dav_pass", null),
        webUrl = p.getString("web_url", null),
        fit = p.getString("fit", FIT_FILL) ?: FIT_FILL,
        intervalSec = clampInterval(p.getInt("interval_sec", DEFAULT_INTERVAL)),
        albumRefreshMin =
            clampAlbumRefresh(p.getInt("album_refresh_min", DEFAULT_ALBUM_REFRESH_MIN)),
        shuffle = p.getBoolean("shuffle", false),
        includeVideo = p.getBoolean("include_video", true),
        batterySaver = p.getBoolean("battery_saver", true),
        showNowPlaying = p.getBoolean("show_now_playing", true),
        facesEnabled = p.getBoolean("faces_enabled", true),
        faceId = p.getString("face_id", "immortal-classic") ?: "immortal-classic",
        faceSizeIndex = p.getInt("face_size_index", 1),
        presenceMode =
            runCatching { FrameMode.valueOf(p.getString("presence_mode", FrameMode.ALWAYS_ON.name)!!) }
                .getOrDefault(FrameMode.ALWAYS_ON),
        idleSleepMin = p.getInt("idle_sleep_min", 0),
        overnightEnabled = p.getBoolean("overnight_enabled", false),
        overnightStartMin = p.getInt("overnight_start_min", 22 * 60),
        overnightEndMin = p.getInt("overnight_end_min", 7 * 60),
        overnightNightClock = p.getBoolean("overnight_night_clock", false),
        dismissAppComponent = p.getString("dismiss_app_component", null),
        dismissHaDashboard = p.getString("dismiss_ha_dashboard", null),
    )
  }

  /** Keep the idle timeout sane (0 = off, else 1…120 min). */
  fun clampIdle(min: Int): Int = if (min <= 0) 0 else min.coerceIn(1, 120)

  /** Minutes-from-midnight wrapped into 0…1439. */
  fun wrapMinuteOfDay(min: Int): Int = ((min % 1440) + 1440) % 1440

  fun setFolder(c: Context, path: String) =
      prefs(c).edit().putString("folder_path", path).putString("source", SOURCE_FOLDER).apply()

  fun setAlbumUrl(c: Context, url: String) =
      prefs(c).edit().putString("album_url", url.trim()).putString("source", SOURCE_URL).apply()

  /** Connect an Immich server and make it the active source (whole library by default). */
  fun setImmich(c: Context, url: String, key: String) =
      prefs(c)
          .edit()
          .putString("immich_url", ImmichSource.normalizeBase(url))
          .putString("immich_key", key.trim())
          .putString("source", SOURCE_IMMICH)
          .apply()

  /** Pick which Immich album to show; null/blank id = the whole library. */
  fun setImmichAlbum(c: Context, albumId: String?, albumName: String?) =
      prefs(c)
          .edit()
          .apply {
            if (albumId.isNullOrBlank()) {
              remove("immich_album_id")
              remove("immich_album_name")
            } else {
              putString("immich_album_id", albumId)
              putString("immich_album_name", albumName)
            }
          }
          .apply()

  /** Connect an SMB network share and make it the active source. */
  fun setSmb(c: Context, host: String, share: String, path: String, user: String, pass: String) =
      prefs(c)
          .edit()
          .putString("smb_host", host.trim())
          .putString("smb_share", share.trim())
          .putString("smb_path", path.trim())
          .putString("smb_user", user.trim())
          .putString("smb_pass", pass)
          .putString("source", SOURCE_SMB)
          .apply()

  /** Connect a WebDAV folder and make it the active source (credentials optional). */
  fun setDav(c: Context, url: String, user: String, pass: String) =
      prefs(c)
          .edit()
          .putString("dav_url", url.trim())
          .putString("dav_user", user.trim())
          .putString("dav_pass", pass)
          .putString("source", SOURCE_DAV)
          .apply()

  /** Render an arbitrary web page as the screensaver (e.g. Immich Kiosk, a dashboard). */
  fun setWebUrl(c: Context, url: String) =
      prefs(c).edit().putString("web_url", url.trim()).putString("source", SOURCE_WEBURL).apply()

  fun useDefault(c: Context) = prefs(c).edit().putString("source", SOURCE_DEFAULT).apply()

  fun setFit(c: Context, fit: String) = prefs(c).edit().putString("fit", fit).apply()

  fun setInterval(c: Context, sec: Int) =
      prefs(c).edit().putInt("interval_sec", clampInterval(sec)).apply()

  fun setAlbumRefreshMin(c: Context, min: Int) =
      prefs(c).edit().putInt("album_refresh_min", clampAlbumRefresh(min)).apply()

  fun setShuffle(c: Context, on: Boolean) = prefs(c).edit().putBoolean("shuffle", on).apply()

  fun setIncludeVideo(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("include_video", on).apply()

  fun setBatterySaver(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("battery_saver", on).apply()

  fun setShowNowPlaying(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("show_now_playing", on).apply()

  /** Turn the clock face on/off (off = photos only). */
  fun setFacesEnabled(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("faces_enabled", on).apply()

  /** Select a clock face by its [FaceCatalog] entry id. */
  fun setFaceId(c: Context, id: String) = prefs(c).edit().putString("face_id", id).apply()

  /** Set the clock size variant (0 = Small, 1 = Medium, 2 = Large) for faces that offer it. */
  fun setFaceSizeIndex(c: Context, i: Int) = prefs(c).edit().putInt("face_size_index", i).apply()

  fun setPresenceMode(c: Context, mode: FrameMode) =
      prefs(c).edit().putString("presence_mode", mode.name).apply()

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

  fun setIdleSleepMin(c: Context, min: Int) =
      prefs(c).edit().putInt("idle_sleep_min", clampIdle(min)).apply()

  fun setOvernightEnabled(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("overnight_enabled", on).apply()

  fun setOvernightStartMin(c: Context, min: Int) =
      prefs(c).edit().putInt("overnight_start_min", wrapMinuteOfDay(min)).apply()

  fun setOvernightEndMin(c: Context, min: Int) =
      prefs(c).edit().putInt("overnight_end_min", wrapMinuteOfDay(min)).apply()

  /** Choose the overnight display: false = screen off, true = a dimmed flip night clock. */
  fun setOvernightNightClock(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("overnight_night_clock", on).apply()

  // --- Dismiss target (launcher / app / Home Assistant dashboard) ---------------
  // The three are mutually exclusive, so each setter clears the others.

  /** Return to the Immortal launcher on dismiss (the default). */
  fun setDismissLauncher(c: Context) =
      prefs(c).edit().remove("dismiss_app_component").remove("dismiss_ha_dashboard").apply()

  /** Open [component] (a flattened ComponentName) when the frame is tapped to dismiss. */
  fun setDismissApp(c: Context, component: String) =
      prefs(c)
          .edit()
          .putString("dismiss_app_component", component)
          .remove("dismiss_ha_dashboard")
          .apply()

  /**
   * Open Home Assistant on dismiss. [path] is the dashboard to deep-link to (e.g.
   * "today-home/security"); blank opens the user's default dashboard.
   */
  fun setDismissHaDashboard(c: Context, path: String) =
      prefs(c)
          .edit()
          .putString("dismiss_ha_dashboard", path.trim())
          .remove("dismiss_app_component")
          .apply()
}
