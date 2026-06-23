/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import com.immortal.launcher.CalendarFeed
import com.immortal.launcher.CalendarUrlEntryActivity
import com.immortal.launcher.DreamPolicy
import com.immortal.launcher.FaceCatalog
import com.immortal.launcher.FacePickerActivity
import com.immortal.launcher.FleetCalendar
import com.immortal.launcher.FleetScreensaver
import com.immortal.launcher.FrameMode
import com.immortal.launcher.PhotoFramePreviewActivity
import com.immortal.launcher.ScreensaverDismiss
import com.immortal.launcher.ScreensaverDismissAppActivity
import com.immortal.launcher.ScreensaverSourcesActivity
import com.immortal.launcher.ImmortalSettings
import com.immortal.launcher.MqttConfig
import com.immortal.launcher.MqttService
import com.immortal.launcher.MultiRoomService
import com.immortal.launcher.QuickBar
import com.immortal.launcher.QuickBarConfig
import com.immortal.launcher.ScreensaverConfig
import com.immortal.launcher.SettingsGuard
import org.json.JSONArray

/**
 * The registered settings domains — the single source of truth that the persistence façades, the
 * phone-remote PWA, and (later) the on-device settings UI all read from. Each domain binds to an
 * existing `*Config` object's getters/setters, so storage and its clamps are untouched.
 */
object SettingsDomains {

  private val CAL_SIZE_LABELS = listOf("Small", "Medium", "Large")

  private fun rangeLabel(range: String): String =
      when (range) {
        CalendarFeed.RANGE_DAY -> "Day"
        CalendarFeed.RANGE_3DAY -> "3 days"
        CalendarFeed.RANGE_WEEK -> "Week"
        CalendarFeed.RANGE_AGENDA -> "Agenda"
        else -> range
      }

  /**
   * The screensaver calendar widget. Mirrors the legacy `FleetCalendar` wire format exactly: the
   * writable controls are `widgetOn` (the on/off toggle, also accepted as the legacy alias
   * `enabled`), the feed `url`, and the `range`/`size`/`side`. The remaining keys are derived,
   * read-only views the client uses to distinguish "linked but hidden" from "no link" and to warn
   * on an unfetchable feed — `enabled` (effective: link set AND toggle on), `hasLink`, `provider`,
   * `supported`, and the advertised `ranges`.
   */
  val calendar: SettingsDomain<ScreensaverConfig.Settings> =
      SettingsDomain(
          id = "calendar",
          title = "Calendar",
          load = ScreensaverConfig::load,
          specs =
              listOf(
                  BoolSpec(
                      key = "widgetOn",
                      title = "Show calendar",
                      get = { it.calendarEnabled },
                      set = ScreensaverConfig::setCalendarEnabled,
                      aliases = listOf("enabled"),
                      visible = { _, s -> s.hasCalendarLink },
                  ),
                  StringSpec(
                      key = "url",
                      title = "Calendar feed",
                      get = { it.calendarUrl ?: "" },
                      set = ScreensaverConfig::setCalendarUrl,
                      entry = Entry.Nav(CalendarUrlEntryActivity::class.java),
                      help =
                          "A public iCalendar (.ics) link - a Google \"secret iCal\" address or an Apple " +
                              "iCloud public-calendar link. Shows your upcoming events on the frame.",
                  ),
                  EnumSpec(
                      key = "range",
                      title = "Show",
                      get = { it.calendarRange },
                      set = ScreensaverConfig::setCalendarRange,
                      options = FleetCalendar.RANGES.map { it to rangeLabel(it) },
                      visible = { _, s -> s.usesCalendar },
                  ),
                  IntSpec(
                      key = "size",
                      title = "Size",
                      get = { it.calendarSize },
                      set = ScreensaverConfig::setCalendarSize,
                      min = 0,
                      max = 2,
                      step = 1,
                      format = { CAL_SIZE_LABELS.getOrElse(it) { _ -> it.toString() } },
                      visible = { _, s -> s.usesCalendar },
                  ),
                  EnumSpec(
                      key = "side",
                      title = "Side",
                      get = { it.calendarSide },
                      set = ScreensaverConfig::setCalendarSide,
                      options =
                          listOf(
                              ScreensaverConfig.CAL_SIDE_LEFT to "Left",
                              ScreensaverConfig.CAL_SIDE_RIGHT to "Right"),
                      visible = { _, s -> s.usesCalendar },
                  ),
                  // Read-only derived views (flat-payload-only — they round-trip the legacy format).
                  DerivedSpec(key = "enabled", get = { it.usesCalendar }),
                  DerivedSpec(key = "hasLink", get = { it.hasCalendarLink }),
                  DerivedSpec(
                      key = "provider",
                      get = {
                        val u = it.calendarUrl.orEmpty()
                        if (u.isBlank()) "" else CalendarFeed.providerName(u)
                      }),
                  DerivedSpec(
                      key = "supported",
                      get = {
                        val u = it.calendarUrl.orEmpty()
                        u.isNotBlank() && CalendarFeed.isSupported(u)
                      }),
                  DerivedSpec(key = "ranges", get = { JSONArray(FleetCalendar.RANGES) }),
              ),
          defaults = { ScreensaverConfig.Settings() },
      )

  private fun hhmm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

  /** Short label for the active photo source, shown on the "Photo source" nav row. */
  private fun sourceLabel(s: ScreensaverConfig.Settings): String =
      when {
        s.usesImmich -> "Immich"
        s.usesSmb -> "Network share"
        s.usesDav -> "WebDAV"
        s.usesWebUrl -> "Web page"
        s.usesUrl -> "Shared album"
        s.usesFolder -> "Local folder"
        else -> "Immortal photos"
      }

  /**
   * The photo-frame display settings — the slice the legacy `FleetScreensaver.toJson` reports.
   * Mirrors that flat wire format exactly (16 keys). The 13 plain display controls are writable
   * specs bound to the proven `ScreensaverConfig` setters; `source`/`folderPath`/`albumUrl` are
   * read-only here because their writes flip the active source atomically and belong to the
   * credential-group apply path (migrated, with the `/remote/settings` work, in a later phase).
   *
   * NOTE: this domain's [SettingsDomain.apply] is not yet wired into `FleetScreensaver.apply`; only
   * the read side ([SettingsDomain.flatJson]) backs the façade today. When apply migrates (with the
   * `/remote/settings` work), this domain gains the credential `GroupSpec`s and an `onApplied` hook
   * lifting the route-layer side effects (FleetRoutes reaffirm + overnight reschedule).
   */
  val screensaver: SettingsDomain<ScreensaverConfig.Settings> =
      SettingsDomain(
          id = "screensaver",
          title = "Screensaver",
          load = ScreensaverConfig::load,
          specs =
              listOf(
                  BoolSpec(
                      "enabled",
                      "Show the photo-frame screensaver",
                      get = { it.enabled },
                      set = ScreensaverConfig::setEnabled,
                      help =
                          "Turn this off to let your Portal's screen sleep on its own timer (or run " +
                              "your own screensaver). Immortal won't switch it back on."),
                  NavSpec(
                      "clockFace",
                      "Clock face",
                      value = { _, s -> FaceCatalog.entryFor(s.faceId).name },
                      activity = FacePickerActivity::class.java,
                      help =
                          "Choose how the time looks - the classic corner clock, a big centred clock, " +
                              "or the full-screen flip clock.",
                      visible = { _, s -> s.enabled }),
                  NavSpec(
                      "photoSource",
                      "Photo source",
                      value = { _, s -> sourceLabel(s) },
                      activity = ScreensaverSourcesActivity::class.java,
                      help =
                          "Where your photos come from - the built-in feed, your own folder or a shared " +
                              "album, or a self-hosted source like Immich or a NAS.",
                      visible = { _, s -> s.enabled }),
                  NavSpec(
                      "dismissTarget",
                      "Open when you tap to exit",
                      value = { c, _ -> ScreensaverDismiss.chosenLabel(c)?.let { "Opens $it" } ?: "Immortal launcher" },
                      activity = ScreensaverDismissAppActivity::class.java,
                      help =
                          "Tapping the screensaver wakes the Portal. By default that brings you home to " +
                              "Immortal - or pick an app (like Home Assistant) to drop straight into.",
                      visible = { _, s -> s.enabled }),
                  DerivedSpec("source", get = { it.source }),
                  DerivedSpec("folderPath", get = { it.folderPath ?: "" }),
                  DerivedSpec("albumUrl", get = { it.albumUrl ?: "" }),
                  IntSpec(
                      "albumRefreshMin",
                      "Album refresh",
                      get = { it.albumRefreshMin },
                      set = ScreensaverConfig::setAlbumRefreshMin,
                      min = 15,
                      max = 24 * 60,
                      step = 15,
                      format = { "$it min" }),
                  EnumSpec(
                      "fit",
                      "Fit",
                      get = { it.fit },
                      set = ScreensaverConfig::setFit,
                      options =
                          listOf(ScreensaverConfig.FIT_FILL to "Fill", ScreensaverConfig.FIT_FIT to "Fit"),
                      coerce = { FleetScreensaver.coerceFit(it) }),
                  IntSpec(
                      "intervalSec",
                      "Photo interval",
                      get = { it.intervalSec },
                      set = ScreensaverConfig::setInterval,
                      min = 5,
                      max = 600,
                      step = 5,
                      format = { "${it}s" }),
                  BoolSpec("shuffle", "Shuffle", get = { it.shuffle }, set = ScreensaverConfig::setShuffle),
                  BoolSpec(
                      "includeVideo",
                      "Play videos",
                      get = { it.includeVideo },
                      set = ScreensaverConfig::setIncludeVideo),
                  BoolSpec(
                      "batterySaver",
                      "Sleep on battery when nobody's around",
                      get = { it.batterySaver },
                      set = ScreensaverConfig::setBatterySaver,
                      help =
                          "Unplugged, keep showing photos while someone's nearby and sleep when the room " +
                              "empties (saves the battery). Off: the frame stays on, on battery too.",
                      visible = { c, _ -> DreamPolicy.hasBattery(c) }),
                  BoolSpec(
                      "showNowPlaying",
                      "Show now playing",
                      get = { it.showNowPlaying },
                      set = ScreensaverConfig::setShowNowPlaying),
                  BoolSpec(
                      "antiBurnIn",
                      "Reduce screen burn-in",
                      get = { it.antiBurnIn },
                      set = ScreensaverConfig::setAntiBurnIn),
                  EnumSpec(
                      "presenceMode",
                      "Power",
                      get = { it.presenceMode.name },
                      set = { c, v -> ScreensaverConfig.setPresenceMode(c, FrameMode.valueOf(v)) },
                      options =
                          listOf(
                              FrameMode.ALWAYS_ON.name to "Always on",
                              FrameMode.PRESENCE.name to "Follow presence"),
                      coerce = { FleetScreensaver.coercePresenceMode(it)?.name },
                      help =
                          "Follow presence: photos while someone's around, screen off (and multi-room " +
                              "music paused) when the room empties. Always on: a permanent frame on mains power."),
                  IntSpec(
                      "idleSleepMin",
                      "Idle screen-off",
                      get = { it.idleSleepMin },
                      set = ScreensaverConfig::setIdleSleepMin,
                      min = 0,
                      max = 120,
                      step = 5,
                      format = { if (it <= 0) "Off" else "$it min" },
                      help =
                          "After the screensaver shows this long with no touch, the screen turns off; " +
                              "tap to wake. A simple timer - it can't tell whether someone's in the room."),
                  BoolSpec(
                      "overnightEnabled",
                      "Overnight screen-off",
                      get = { it.overnightEnabled },
                      set = ScreensaverConfig::setOvernightEnabled,
                      help = "Keep the screen off (or show a dim night clock) between two times every night."),
                  IntSpec(
                      "overnightStartMin",
                      "Off from",
                      get = { it.overnightStartMin },
                      set = ScreensaverConfig::setOvernightStartMin,
                      min = 0,
                      max = 24 * 60 - 1,
                      step = 15,
                      wrap = true,
                      format = ::hhmm,
                      visible = { _, s -> s.overnightEnabled }),
                  IntSpec(
                      "overnightEndMin",
                      "Off until",
                      get = { it.overnightEndMin },
                      set = ScreensaverConfig::setOvernightEndMin,
                      min = 0,
                      max = 24 * 60 - 1,
                      step = 15,
                      wrap = true,
                      format = ::hhmm,
                      visible = { _, s -> s.overnightEnabled }),
                  BoolSpec(
                      "overnightNightClock",
                      "Show a dim night clock",
                      get = { it.overnightNightClock },
                      set = ScreensaverConfig::setOvernightNightClock,
                      visible = { _, s -> s.overnightEnabled }),
              ),
          sections =
              mapOf(
                  "albumRefreshMin" to "Display",
                  "fit" to "Display",
                  "intervalSec" to "Display",
                  "shuffle" to "Display",
                  "includeVideo" to "Display",
                  "showNowPlaying" to "Display",
                  "antiBurnIn" to "Display",
                  "batterySaver" to "Power & sleep",
                  "presenceMode" to "Power & sleep",
                  "idleSleepMin" to "Power & sleep",
                  "overnightEnabled" to "Power & sleep",
                  "overnightStartMin" to "Power & sleep",
                  "overnightEndMin" to "Power & sleep",
                  "overnightNightClock" to "Power & sleep"),
          defaults = { ScreensaverConfig.Settings() },
          // The screensaver's post-apply side effects, lifted from the route layer (the same
          // reaffirm + overnight reschedule that `RemoteRoutes.applyConfig` / `FleetRoutes` run).
          // Fires once per /remote/settings batch; the legacy /screensaver and /remote/sources
          // routes keep their own reaffirm, so there's no double-fire.
          onApplied = { c, keys -> SettingsGuard.afterScreensaverApply(c, keys) },
      )

  /**
   * Immortal's own launcher preferences ([ImmortalSettings]). The multi-room fields gate on the
   * master toggle. `hideStatusBar` re-applies the immersive policy; the multi-room fields resync
   * the Snapcast/MA bridge — the same side effects the on-device screen runs.
   */
  val immortal: SettingsDomain<ImmortalSettings.Settings> =
      SettingsDomain(
          id = "immortal",
          title = "Immortal",
          load = ImmortalSettings::load,
          specs =
              listOf(
                  EnumSpec(
                      "weatherUnit",
                      "Temperature unit",
                      get = { it.weatherUnit },
                      set = ImmortalSettings::setWeatherUnit,
                      options =
                          listOf(
                              ImmortalSettings.UNIT_AUTO to "Auto",
                              ImmortalSettings.UNIT_F to "Fahrenheit",
                              ImmortalSettings.UNIT_C to "Celsius")),
                  EnumSpec(
                      "tileSize",
                      "App tile size",
                      get = { it.tileSize },
                      set = ImmortalSettings::setTileSize,
                      options =
                          listOf(
                              ImmortalSettings.SIZE_STANDARD to "Standard",
                              ImmortalSettings.SIZE_LARGE to "Large",
                              ImmortalSettings.SIZE_XL to "XL")),
                  EnumSpec(
                      "weatherWidget",
                      "Weather widget",
                      get = { it.weatherWidget },
                      set = ImmortalSettings::setWeatherWidget,
                      options =
                          listOf(
                              ImmortalSettings.WIDGET_OFF to "Off",
                              ImmortalSettings.WIDGET_HOURLY to "Hourly",
                              ImmortalSettings.WIDGET_DAILY to "Daily")),
                  EnumSpec(
                      "clockFormat",
                      "Clock",
                      get = { it.clockFormat },
                      set = ImmortalSettings::setClockFormat,
                      options =
                          listOf(
                              ImmortalSettings.CLOCK_AUTO to "Auto",
                              ImmortalSettings.CLOCK_12 to "12-hour",
                              ImmortalSettings.CLOCK_24 to "24-hour")),
                  BoolSpec(
                      "showMiniPlayer",
                      "Mini player",
                      get = { it.showMiniPlayer },
                      set = ImmortalSettings::setShowMiniPlayer),
                  BoolSpec(
                      "hideStatusBar",
                      "Hide status bar",
                      get = { it.hideStatusBar },
                      set = ImmortalSettings::setHideStatusBar),
                  BoolSpec(
                      "multiRoomEnabled",
                      "Multi-room audio",
                      get = { it.multiRoomEnabled },
                      set = ImmortalSettings::setMultiRoomEnabled),
                  StringSpec(
                      "snapcastHost",
                      "Snapcast host",
                      get = { it.snapcastHost },
                      set = ImmortalSettings::setSnapcastHost,
                      visible = { _, s -> s.multiRoomEnabled }),
                  StringSpec(
                      "maUsername",
                      "Music Assistant user",
                      get = { it.maUsername },
                      set = ImmortalSettings::setMaUsername,
                      visible = { _, s -> s.multiRoomEnabled }),
                  StringSpec(
                      "maPassword",
                      "Music Assistant password",
                      get = { it.maPassword },
                      set = ImmortalSettings::setMaPassword,
                      secret = true,
                      visible = { _, s -> s.multiRoomEnabled }),
              ),
          sections =
              mapOf(
                  "weatherUnit" to "Display",
                  "tileSize" to "Display",
                  "weatherWidget" to "Display",
                  "clockFormat" to "Display",
                  "showMiniPlayer" to "Display",
                  "hideStatusBar" to "Display",
                  "multiRoomEnabled" to "Audio",
                  "snapcastHost" to "Audio",
                  "maUsername" to "Audio",
                  "maPassword" to "Audio"),
          defaults = { ImmortalSettings.Settings() },
          onApplied = { c, keys ->
            if ("hideStatusBar" in keys) SettingsGuard.applyStatusBar(c)
            if (keys.any { it in setOf("multiRoomEnabled", "snapcastHost", "maUsername", "maPassword") })
                MultiRoomService.sync(c)
          },
      )

  /**
   * The Home Assistant MQTT publisher ([MqttConfig]). Uses the Context itself as the snapshot
   * (this config has no aggregate `Settings`); the broker fields gate on the master toggle, and
   * cert validation on TLS. [SettingsDomain.explicitApply] = batch so a future on-device renderer
   * doesn't reconnect the broker per keystroke; each apply resyncs the service once.
   */
  val mqtt: SettingsDomain<Context> =
      SettingsDomain(
          id = "mqtt",
          title = "Home Assistant (MQTT)",
          load = { it },
          explicitApply = true,
          specs =
              listOf(
                  BoolSpec("enabled", "Publish to MQTT", get = { MqttConfig.isEnabled(it) }, set = MqttConfig::setEnabled),
                  StringSpec(
                      "host",
                      "Broker host",
                      get = { MqttConfig.host(it) },
                      set = MqttConfig::setHost,
                      visible = { c, _ -> MqttConfig.isEnabled(c) }),
                  IntSpec(
                      "port",
                      "Port",
                      get = { MqttConfig.port(it) },
                      set = MqttConfig::setPort,
                      min = 1,
                      max = 65535,
                      asText = true,
                      visible = { c, _ -> MqttConfig.isEnabled(c) }),
                  StringSpec(
                      "username",
                      "Username",
                      get = { MqttConfig.username(it) },
                      set = MqttConfig::setUsername,
                      visible = { c, _ -> MqttConfig.isEnabled(c) }),
                  StringSpec(
                      "password",
                      "Password",
                      get = { MqttConfig.password(it) },
                      set = MqttConfig::setPassword,
                      secret = true,
                      visible = { c, _ -> MqttConfig.isEnabled(c) }),
                  BoolSpec(
                      "useTls",
                      "Use TLS",
                      get = { MqttConfig.useTls(it) },
                      set = MqttConfig::setUseTls,
                      visible = { c, _ -> MqttConfig.isEnabled(c) }),
                  BoolSpec(
                      "validateCert",
                      "Validate certificate",
                      get = { MqttConfig.validateCert(it) },
                      set = MqttConfig::setValidateCert,
                      visible = { c, _ -> MqttConfig.isEnabled(c) && MqttConfig.useTls(c) }),
              ),
          sections =
              mapOf(
                  "host" to "Broker",
                  "port" to "Broker",
                  "username" to "Broker",
                  "password" to "Broker",
                  "useTls" to "Security",
                  "validateCert" to "Security"),
          onApplied = { c, _ -> MqttService.sync(c) },
      )

  /** The floating quick-button cluster ([QuickBarConfig]); Context as snapshot. */
  val quickbar: SettingsDomain<Context> =
      SettingsDomain(
          id = "quickbar",
          title = "Quick buttons",
          load = { it },
          specs =
              listOf(
                  BoolSpec(
                      "enabled",
                      "App-switcher button",
                      get = { QuickBarConfig.isEnabled(it) },
                      set = QuickBarConfig::setEnabled),
                  BoolSpec(
                      "alwaysShow",
                      "Always show",
                      get = { QuickBarConfig.alwaysShow(it) },
                      set = QuickBarConfig::setAlwaysShow,
                      visible = { c, _ -> QuickBarConfig.isEnabled(c) }),
              ),
          onApplied = { c, _ ->
            SettingsGuard.reconcileBarWatch(c)
            QuickBar.applyConfig()
          },
      )

  /** Every registered domain. */
  val all: List<SettingsDomain<*>> = listOf(screensaver, calendar, immortal, mqtt, quickbar)
}
