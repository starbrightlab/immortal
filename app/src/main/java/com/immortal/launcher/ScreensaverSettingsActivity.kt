/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * Settings screen for the photo-frame screensaver. Reached from a "Screensaver"
 * tile in the launcher's Settings folder. Lets the user keep Immortal's built-in
 * photos or point the frame at a local folder of photos/videos — including one on a
 * USB-C drive plugged into the Portal.
 */
class ScreensaverSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ScreensaverSettingsScreen() } }
  }
}

@Composable
private fun ScreensaverSettingsScreen() {
  val context = LocalContext.current
  var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }

  // Re-read config when we come back from a subpage (source picker, dismiss target).
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) settings = ScreensaverConfig.load(context)
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  // Remote support: focus the first control on open; Back exits the screen.
  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) activity?.finish()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusRequester(firstFocus).focusGroup()) {
      Text("Screensaver", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Choose what shows on the photo frame when your Portal is idle.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))

      Card {
        ToggleRow("Show the photo-frame screensaver", settings.enabled) {
          ScreensaverConfig.setEnabled(context, it)
          SettingsGuard.reaffirmScreensaver(context)
          settings = settings.copy(enabled = it)
        }
      }
      Text(
          "Turn this off to let your Portal's screen turn off on its own timer (or to use " +
              "your own screensaver). Immortal won't switch it back on.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )

      if (settings.enabled) {
        Spacer(Modifier.size(26.dp))

        SectionLabel("Clock face")
        Card {
          NavRow(title = "Clock face", value = FaceCatalog.entryFor(settings.faceId).name) {
            context.startActivity(Intent(context, FacePickerActivity::class.java))
          }
        }
        Text(
            "Choose how the time looks — the classic corner clock, a big centred clock, or the " +
                "full-screen flip clock.",
            color = Color(0xFF7C7C7C),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
        )

        Spacer(Modifier.size(26.dp))

        SectionLabel("Photos")
        Card {
          NavRow(title = "Photo source", value = currentSourceLabel(settings)) {
            context.startActivity(Intent(context, ScreensaverSourcesActivity::class.java))
          }
        }
        Text(
            "Pick where your photos come from — the built-in feed, your own folder or a shared " +
                "album, or a self-hosted source like Immich or a NAS.",
            color = Color(0xFF7C7C7C),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
        )

      Spacer(Modifier.size(26.dp))

      SectionLabel("Display")
      Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Image fit", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
          Segmented(
              options =
                  listOf(
                      "Fill" to ScreensaverConfig.FIT_FILL,
                      "Fit" to ScreensaverConfig.FIT_FIT,
                  ),
              selected = settings.fit,
              onSelect = {
                ScreensaverConfig.setFit(context, it)
                settings = settings.copy(fit = it)
              },
          )
        }
        Divider()
        IntervalStepper(
            seconds = settings.intervalSec,
            onChange = { v ->
              val clamped = ScreensaverConfig.clampInterval(v)
              ScreensaverConfig.setInterval(context, clamped)
              settings = settings.copy(intervalSec = clamped)
            },
        )
        Divider()
        ToggleRow("Shuffle order", settings.shuffle) {
          ScreensaverConfig.setShuffle(context, it)
          settings = settings.copy(shuffle = it)
        }
        Divider()
        ToggleRow("Include videos", settings.includeVideo) {
          ScreensaverConfig.setIncludeVideo(context, it)
          settings = settings.copy(includeVideo = it)
        }
        Divider()
        ToggleRow("Reduce screen burn-in", settings.antiBurnIn) {
          ScreensaverConfig.setAntiBurnIn(context, it)
          settings = settings.copy(antiBurnIn = it)
        }
      }
      Text(
          "Gently drifts the clock and widgets by a few pixels to protect always-on screens from " +
              "burn-in. Subtle by design — turn it off if you notice the motion.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )

      // When dismissed — where a tap on the frame takes you. Default is the launcher;
      // Home Assistant users typically point this at their dashboard app instead.
      Spacer(Modifier.size(26.dp))
      SectionLabel("When dismissed")
      val dismissLabel =
          remember(settings.dismissAppComponent, settings.dismissHaDashboard) {
            ScreensaverDismiss.chosenLabel(context)
          }
      Card {
        NavRow(
            title = "Open when you tap to exit",
            value =
                if (dismissLabel != null) "Opens $dismissLabel"
                else "Immortal launcher",
        ) {
          context.startActivity(Intent(context, ScreensaverDismissAppActivity::class.java))
        }
      }
      Text(
          "Tap the screensaver to wake your Portal. By default that brings you home to " +
              "Immortal — or pick an app (like Home Assistant) to drop straight into it.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )

      // Music — sourced from the device's own media session, so it works with any
      // app (Spotify, podcasts, the Music Assistant player…); shown for everyone.
      Spacer(Modifier.size(26.dp))
      SectionLabel("Music")
      Card {
        ToggleRow("Show what's playing", settings.showNowPlaying) {
          ScreensaverConfig.setShowNowPlaying(context, it)
          settings = settings.copy(showNowPlaying = it)
        }
      }
      Text(
          "Show the current track and album art on the screensaver while anything is " +
              "playing music on your Portal.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )

      // Calendar — a clean upcoming-events panel, fed by a public iCalendar (.ics)
      // link from Google Calendar or Apple iCloud (no account sign-in).
      Spacer(Modifier.size(26.dp))
      SectionLabel("Calendar")
      Card {
        NavRow(
            title = "Calendar link",
            value = calendarValue(settings),
        ) {
          context.startActivity(Intent(context, CalendarUrlEntryActivity::class.java))
        }
        if (settings.hasCalendarLink) {
          Divider()
          // Show/hide the widget without forgetting the link, so it's a quick toggle.
          ToggleRow("Show calendar widget", settings.calendarEnabled) {
            ScreensaverConfig.setCalendarEnabled(context, it)
            settings = settings.copy(calendarEnabled = it)
          }
        }
        if (settings.usesCalendar) {
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Show", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Segmented(
                options =
                    listOf(
                        "1 day" to CalendarFeed.RANGE_DAY,
                        "3 days" to CalendarFeed.RANGE_3DAY,
                        "Week" to CalendarFeed.RANGE_WEEK,
                        "Events" to CalendarFeed.RANGE_AGENDA,
                    ),
                selected = settings.calendarRange,
                onSelect = {
                  ScreensaverConfig.setCalendarRange(context, it)
                  settings = settings.copy(calendarRange = it)
                },
            )
          }
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Size", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Segmented(
                options = listOf("Small" to "0", "Medium" to "1", "Large" to "2"),
                selected = settings.calendarSize.toString(),
                onSelect = {
                  val i = it.toIntOrNull() ?: 1
                  ScreensaverConfig.setCalendarSize(context, i)
                  settings = settings.copy(calendarSize = i)
                },
            )
          }
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Position", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Segmented(
                options =
                    listOf(
                        "Left" to ScreensaverConfig.CAL_SIDE_LEFT,
                        "Right" to ScreensaverConfig.CAL_SIDE_RIGHT,
                    ),
                selected = settings.calendarSide,
                onSelect = {
                  ScreensaverConfig.setCalendarSide(context, it)
                  settings = settings.copy(calendarSide = it)
                },
            )
          }
        }
      }
      Text(
          "Shows your upcoming events on the frame. \"Events\" lists the next " +
              "few whenever they are; the others show a day, three days, or the week ahead. " +
              "Use a Google \"secret iCal\" address or an Apple iCloud public-calendar link.",
          color = Color(0xFF7C7C7C),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )

      Spacer(Modifier.size(26.dp))

      val hasBattery = remember { DreamPolicy.hasBattery(context) }
      SectionLabel("Power")
      Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(18.dp)) {
        Column {
          // Presence-driven baseline (all models): let the Portal sleep the screen when the
          // room empties and re-show photos when someone returns — the same signal the
          // multi-room music follows. ALWAYS_ON is the original permanent-frame behaviour.
          ToggleRow(
              "Follow presence (sleep when the room's empty)",
              settings.presenceMode == FrameMode.PRESENCE) {
                val mode = if (it) FrameMode.PRESENCE else FrameMode.ALWAYS_ON
                ScreensaverConfig.setPresenceMode(context, mode)
                settings = settings.copy(presenceMode = mode)
              }
          Text(
              "On: the frame follows the Portal's own presence — photos while someone's " +
                  "around, screen off (and multi-room music paused) when the room empties, on " +
                  "every model. Off: a permanent photo frame on mains power. Newer behaviour — " +
                  "confirm a wall-powered Portal actually sleeps when you leave before relying " +
                  "on it.",
              fontSize = 13.sp,
              color = Color(0xFF9A9A9A),
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
          )
          Divider()

          if (hasBattery) {
            // Battery models (Portal Go): battery life vs always-on frame unplugged.
            ToggleRow("Sleep on battery when nobody's around", settings.batterySaver) {
              ScreensaverConfig.setBatterySaver(context, it)
              settings = settings.copy(batterySaver = it)
            }
            Text(
                "On: unplugged, the Portal keeps showing photos while someone is nearby " +
                    "and goes to sleep when the room is empty (saves the battery). " +
                    "Off: the photo frame stays on battery too.",
                fontSize = 13.sp,
                color = Color(0xFF9A9A9A),
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
            )
            Divider()
          }

          // Idle screen-off (all models). Off by default.
          ToggleRow("Turn the screen off after a while", settings.idleSleepOn) {
            val v = if (it) 30 else 0
            ScreensaverConfig.setIdleSleepMin(context, v)
            settings = settings.copy(idleSleepMin = v)
          }
          if (settings.idleSleepOn) {
            Divider()
            MinuteStepper(settings.idleSleepMin) { v ->
              val c = ScreensaverConfig.clampIdle(v)
              ScreensaverConfig.setIdleSleepMin(context, c)
              settings = settings.copy(idleSleepMin = c)
            }
          }
          Text(
              "After the screensaver has shown this long with no touch, the screen turns " +
                  "off; tap to wake it. This is a simple timer — the Portal can't tell us " +
                  "whether someone's in the room.",
              fontSize = 13.sp,
              color = Color(0xFF9A9A9A),
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 14.dp),
          )

          Divider()

          // Overnight window (all models). Off by default.
          ToggleRow("Overnight sleep", settings.overnightEnabled) {
            ScreensaverConfig.setOvernightEnabled(context, it)
            settings = settings.copy(overnightEnabled = it)
            SleepScheduler.applyOvernightNow(context)
          }
          if (settings.overnightEnabled) {
            Divider()
            TimeStepper("From", settings.overnightStartMin) { v ->
              ScreensaverConfig.setOvernightStartMin(context, v)
              settings = settings.copy(overnightStartMin = ScreensaverConfig.wrapMinuteOfDay(v))
              SleepScheduler.applyOvernightNow(context)
            }
            Divider()
            TimeStepper("Until", settings.overnightEndMin) { v ->
              ScreensaverConfig.setOvernightEndMin(context, v)
              settings = settings.copy(overnightEndMin = ScreensaverConfig.wrapMinuteOfDay(v))
              SleepScheduler.applyOvernightNow(context)
            }
            Divider()
            ToggleRow("Show a night clock instead of going dark", settings.overnightNightClock) {
              ScreensaverConfig.setOvernightNightClock(context, it)
              settings = settings.copy(overnightNightClock = it)
              SleepScheduler.applyOvernightNow(context)
            }
          }
          Text(
              if (settings.overnightEnabled && settings.overnightNightClock)
                  "Shows a dimmed flip clock between these times every night — a bedside clock. " +
                      "Tap it any time to use the device."
              else "Keeps the screen off between these times every night.",
              fontSize = 13.sp,
              color = Color(0xFF9A9A9A),
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 14.dp),
          )
        }
      }

      Spacer(Modifier.size(28.dp))
      Surface(
          color = Color(0xFF2E6BE6),
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
              },
      ) {
        Text(
            "Preview screensaver",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
      }
      } // end if (settings.enabled)
    }
  }
}

/** Short summary of the active source, shown on the "Photo source" nav row. */
private fun currentSourceLabel(s: ScreensaverConfig.Settings): String =
    when {
      s.usesFolder -> "My photos & videos"
      s.usesUrl -> "Shared album link"
      s.usesImmich ->
          if (s.immichAlbumName.isNullOrBlank()) "Immich — whole library"
          else "Immich — ${s.immichAlbumName}"
      s.usesSmb -> "Network share"
      s.usesDav -> "WebDAV folder"
      s.usesWebUrl -> "Web page"
      else -> "Immortal photos"
    }

private fun calendarValue(s: ScreensaverConfig.Settings): String =
    when {
      s.calendarUrl.isNullOrBlank() -> "Off"
      !s.calendarEnabled -> "${CalendarFeed.providerName(s.calendarUrl)} - hidden"
      else -> "${CalendarFeed.providerName(s.calendarUrl)} - ${calendarRangeLabel(s.calendarRange)}"
    }

private fun calendarRangeLabel(range: String): String =
    when (CalendarFeed.clampRange(range)) {
      CalendarFeed.RANGE_DAY -> "1 day"
      CalendarFeed.RANGE_3DAY -> "3 days"
      CalendarFeed.RANGE_WEEK -> "Week"
      CalendarFeed.RANGE_AGENDA -> "Events"
      else -> "1 day"
    }

@Composable
private fun SectionLabel(text: String) {
  Text(
      text.uppercase(),
      color = Color(0xFF7C7C7C),
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
  )
}

@Composable
private fun ArrowButton(glyph: String, rowFocused: Boolean, onClick: () -> Unit) {
  Box(
      modifier =
          Modifier.size(48.dp)
              .clip(RoundedCornerShape(12.dp))
              .focusProperties { canFocus = false }
              .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        glyph,
        color = if (rowFocused) Color.White else Color(0xFFBBBBBB),
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
  Surface(
      color = Color(0xFF1C1C1E),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.fillMaxWidth(),
  ) {
    Column { content() }
  }
}

@Composable
private fun Divider() {
  Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
}

/** A row that opens a sub-page; shows the current [value] and a chevron. */
@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onClick() }
              .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    Text(value, color = Color(0xFF9A9A9A), fontSize = 15.sp)
    Text("  ›", color = Color(0xFF7C7C7C), fontSize = 20.sp)
  }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onChange(!checked) }
              .padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    // Visual only — the row toggles it (so the remote's center button works).
    Switch(checked = checked, onCheckedChange = null)
  }
}

/**
 * Remote-friendly replacement for a slider: focusable, with LEFT/RIGHT adjusting the
 * value and UP/DOWN passing through so the remote can move off it (a focused Slider
 * traps all four directions). On touch it's still adjustable via the on-screen arrows.
 */
@Composable
private fun IntervalStepper(seconds: Int, onChange: (Int) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> {
                      onChange(seconds - 5)
                      true
                    }
                    Key.DirectionRight -> {
                      onChange(seconds + 5)
                      true
                    }
                    else -> false // let UP/DOWN/CENTER move focus away
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0x402E6BE6) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Time per item", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    // Tappable arrows for touchscreens. They're excluded from D-pad focus
    // traversal (focusProperties canFocus=false) so on the remote the row itself
    // keeps handling left/right — the value adjusts the same way with either input.
    ArrowButton("◀", focused) { onChange(seconds - 5) }
    Text(
        "${seconds}s",
        color = if (focused) Color.White else Color(0xFFDDDDDD),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 52.dp),
    )
    ArrowButton("▶", focused) { onChange(seconds + 5) }
  }
}

/** Remote-friendly minute stepper for the idle screen-off timeout (5-min steps). */
@Composable
private fun MinuteStepper(minutes: Int, onChange: (Int) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> { onChange(minutes - 5); true }
                    Key.DirectionRight -> { onChange(minutes + 5); true }
                    else -> false
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0x402E6BE6) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Turn off after", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onChange(minutes - 5) }
    Text(
        "${minutes}m",
        color = if (focused) Color.White else Color(0xFFDDDDDD),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 64.dp),
    )
    ArrowButton("▶", focused) { onChange(minutes + 5) }
  }
}

/** Remote-friendly time-of-day stepper for the overnight window (15-min steps). */
@Composable
private fun TimeStepper(label: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> { onChange(minuteOfDay - 15); true }
                    Key.DirectionRight -> { onChange(minuteOfDay + 15); true }
                    else -> false
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) Color(0x402E6BE6) else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onChange(minuteOfDay - 15) }
    Text(
        formatMinuteOfDay(minuteOfDay),
        color = if (focused) Color.White else Color(0xFFDDDDDD),
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 80.dp),
    )
    ArrowButton("▶", focused) { onChange(minuteOfDay + 15) }
  }
}

private fun formatMinuteOfDay(min: Int): String {
  val m = ((min % 1440) + 1440) % 1440
  return "%02d:%02d".format(m / 60, m % 60)
}

@Composable
private fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
  Row(
      modifier = Modifier.background(Color(0xFF2A2A2C), RoundedCornerShape(12.dp)).padding(3.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    options.forEach { (label, value) ->
      val on = value == selected
      Surface(
          color = if (on) Color(0xFF2E6BE6) else Color.Transparent,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.tvFocusable(RoundedCornerShape(10.dp)) { onSelect(value) },
      ) {
        Text(
            label,
            color = if (on) Color.White else Color(0xFFBBBBBB),
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
      }
    }
  }
}
