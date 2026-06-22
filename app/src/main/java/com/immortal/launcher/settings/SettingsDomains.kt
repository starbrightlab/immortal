/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import com.immortal.launcher.CalendarFeed
import com.immortal.launcher.CalendarUrlEntryActivity
import com.immortal.launcher.FleetCalendar
import com.immortal.launcher.FleetScreensaver
import com.immortal.launcher.FrameMode
import com.immortal.launcher.ScreensaverConfig
import com.immortal.launcher.SettingsGuard
import com.immortal.launcher.SleepScheduler
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
                  ),
                  StringSpec(
                      key = "url",
                      title = "Calendar feed (.ics)",
                      get = { it.calendarUrl ?: "" },
                      set = ScreensaverConfig::setCalendarUrl,
                      entry = Entry.Nav(CalendarUrlEntryActivity::class.java),
                  ),
                  EnumSpec(
                      key = "range",
                      title = "Show",
                      get = { it.calendarRange },
                      set = ScreensaverConfig::setCalendarRange,
                      options = FleetCalendar.RANGES.map { it to rangeLabel(it) },
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
      )

  private fun hhmm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

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
                  BoolSpec("enabled", "Photo frame", get = { it.enabled }, set = ScreensaverConfig::setEnabled),
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
                      "Battery saver",
                      get = { it.batterySaver },
                      set = ScreensaverConfig::setBatterySaver),
                  BoolSpec(
                      "showNowPlaying",
                      "Show now playing",
                      get = { it.showNowPlaying },
                      set = ScreensaverConfig::setShowNowPlaying),
                  EnumSpec(
                      "presenceMode",
                      "Power",
                      get = { it.presenceMode.name },
                      set = { c, v -> ScreensaverConfig.setPresenceMode(c, FrameMode.valueOf(v)) },
                      options =
                          listOf(
                              FrameMode.ALWAYS_ON.name to "Always on",
                              FrameMode.PRESENCE.name to "Follow presence"),
                      coerce = { FleetScreensaver.coercePresenceMode(it)?.name }),
                  IntSpec(
                      "idleSleepMin",
                      "Idle screen-off",
                      get = { it.idleSleepMin },
                      set = ScreensaverConfig::setIdleSleepMin,
                      min = 0,
                      max = 120,
                      step = 5,
                      format = { if (it <= 0) "Off" else "$it min" }),
                  BoolSpec(
                      "overnightEnabled",
                      "Overnight screen-off",
                      get = { it.overnightEnabled },
                      set = ScreensaverConfig::setOvernightEnabled),
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
              ),
          // The screensaver's post-apply side effects, lifted from the route layer (the same
          // reaffirm + overnight reschedule that `RemoteRoutes.applyConfig` / `FleetRoutes` run).
          // Fires once per /remote/settings batch; the legacy /screensaver and /remote/sources
          // routes keep their own reaffirm, so there's no double-fire.
          onApplied = { c, keys ->
            SettingsGuard.reaffirmScreensaver(c)
            if (keys.any { it.startsWith("overnight") }) SleepScheduler.applyOvernightNow(c)
          },
      )

  /** Every registered domain. */
  val all: List<SettingsDomain<*>> = listOf(screensaver, calendar)
}
