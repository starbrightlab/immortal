# Multi-room audio for Portals — design

Synchronized, whole-home music across the Portal devices already running Immortal: play
the same audio, in sync, in every room — and manage which rooms are playing from one place.
This document records the architecture we settled on, what we reuse versus build, and the
phased path to get there. It's a design note, not a built feature yet; the only thing shipped
so far is the stock **Snapcast** client in the App Store, for testing (see *Phase 0*).

## Goal

- The same music, in tight sync, on any subset of Portals in the house.
- A single UI to pick **which rooms** play and at what volume.
- Set-and-forget on each Portal: it joins on boot, survives for days, gets out of the way
  for calls, and comes back on its own.
- No Google services, no root, no new hardware required (these are the Portal's hard limits).

## The stack we chose

```
  ┌─────────────────────────────────────────────┐
  │  Music Assistant   (Docker on TrueNAS)       │  ← library + streaming sources,
  │     └── snapserver (built-in)                │     room UI, repair orchestration
  └───────────────────────┬─────────────────────┘
                          │  TCP, time-synced       Home Assistant (Raspberry Pi)
        ┌─────────────────┼─────────────────┐       stays as the control front end +
        ▼                 ▼                 ▼        presence automation brain, pointing
  ┌───────────┐     ┌───────────┐     ┌───────────┐  at the MA server over the network.
  │  Portal   │     │  Portal   │     │  Portal   │   ← Immortal companion app wraps
  │ (Kitchen) │     │ (Bedroom) │     │  (Office) │      the native snapclient
  └───────────┘     └───────────┘     └───────────┘
```

Three layers, each owned by software that's best suited to it:

1. **Music Assistant (MA)** — the front end. Manages your streaming sources and library,
   presents every room as a `media_player` in Home Assistant, groups players to play in
   sync, and is the natural home for the repair automation. MA ships a **built-in
   snapserver**. It runs as a standalone Docker container (it does **not** have to be the
   Home Assistant add-on), so it can live on TrueNAS while HA stays on the Pi.
2. **Snapcast** — the sync transport. `snapserver` timestamps and streams audio over TCP;
   each Portal's `snapclient` continuously syncs its clock to the server and adds/drops
   samples to stay within sub-millisecond of every other room.
3. **Immortal companion app** — a Portal-tuned wrapper around the native `snapclient`,
   doing the always-on, audio-focus, and self-heal work the stock app doesn't. Immortal
   the launcher *presents and drives* it; it does not contain it (see *Licensing*).

### Why this and not bare snapserver

The Portal's no-GMS / no-root constraint eliminates almost every multi-room ecosystem
(Chromecast audio groups need GMS; Sonos/Roon/AirPlay-2 are hardware or closed). What's
left are the two ecosystems with a real **synchronized native Android player on F-Droid**:
**Snapcast** and **Squeezelite/Lyrion**. We picked Snapcast for the device sync, and put
**Music Assistant in front of it** rather than a bare snapserver, because MA gives us — for
free, inside the Home Assistant we already run — the two things we'd otherwise hand-build:
unified **source management** (library/AirPlay/Spotify) and a unified **room UI**.
Squeezelite + Lyrion (LMS) is the documented **fallback endpoint** if Snapcast's Android sync
proves flaky on real Portals; it requires none of the device-side app work below to change.

## Server side — reuse, don't build

Nothing here is Portal-specific; it's standard software that already exists in the house.

### Host — TrueNAS (decided)

Run **Music Assistant (with its built-in snapserver) as a Docker app on the TrueNAS server**,
and keep **Home Assistant on the Raspberry Pi** as the control/dashboard front end and
presence-automation brain (it connects to the MA server over the LAN via HA's *Music
Assistant* integration).

Why not host it on the Pi: the Pi HA box is already resource-constrained, and MA is not a
negligible load — it does on-the-fly transcoding, runs an AirPlay receiver, indexes the local
library, and fans out a Snapcast stream per group. That belongs on the TrueNAS box, which has
the headroom and is almost certainly already where the music files live. snapserver *itself*
is light; **Music Assistant is the part that wants the resources.**

> A Portal *could* host the server via Termux, but that pins the whole system to one Portal
> staying awake. Not recommended.

### Audio sources (decided)

Each enabled source becomes a selectable Snapcast *stream* that any group can play.

- **AirPlay — primary.** The server runs an AirPlay receiver (shairport-sync), exposed as a
  Snapcast stream. **This is the path for Apple Music** (your main streaming service): AirPlay
  from the Apple Music app on an iPhone/Mac into the server, and it fans out to every grouped
  Portal in sync. Also covers podcasts, browser audio, anything else you can AirPlay.
- **Local library.** MA indexes your music files on the TrueNAS and plays them directly —
  fully controllable from the room UI (no phone needed).
- **Spotify — bonus.** `librespot` makes the server a Spotify Connect target; pick it in the
  Spotify app like any speaker.

**Honest limit — Apple Music has no server-side provider.** Apple exposes no public
full-playback API and the catalog is DRM-protected, so MA/Snapcast cannot pull Apple Music in
the way `librespot` pulls Spotify. **AirPlay is the supported workaround and works well** — the
only trade-off is that you *start* Apple Music playback from the Apple device, not from the
in-room UI (once it's playing, room grouping/volume still work normally). Local library and
Spotify are the sources controllable directly from the room UI.

## Client side — what we build (the Immortal companion app)

A **separate** app — working name *Immortal Snapcast* — that owns the native player. Immortal
the launcher integrates it by **intent**, not by linking its code (see *Licensing*).

### Responsibilities

1. **Autostart on boot.** A `BOOT_COMPLETED` receiver (the launcher already uses this
   pattern in `BootReceiver`) starts the player service and auto-connects to the configured
   server/room — no "open app, tap connect."
2. **Long-running survival.** A **foreground service** with a persistent notification holds
   the `snapclient` so Android 9/10 doesn't reap it. The connection auto-reconnects on a
   dropped TCP link.
3. **Yield speakers, then reclaim.** Request Android **audio focus** for media; on
   *transient loss* (a WhatsApp/Messenger call, an assistant) duck or pause, and resume when
   focus returns. The Portal's calling apps are the case to test hardest — confirm they
   request focus politely rather than seizing the output.
4. **Launcher integration (UX, not code).** A Snapcast **tile** on the home grid and a
   **settings screen** (server host or mDNS name, room name, latency offset, which stream,
   and the off/presence/on mode below) living in Immortal's UI the way the screensaver
   settings do. Immortal sends the companion intents to connect/disconnect/select-stream and
   reads back status.
5. **Screensaver cooperation.** Keep audio playing while the photo-frame `DreamService` is
   up — music should outlast someone leaving the room and is independent of the screen state.
   (Presence gating, when enabled, is handled server-side by Home Assistant — see *Presence*
   — not by the Portal, which can't read its own presence sensor.)
6. **Self-heal watchdog.** A `WorkManager`/`AlarmManager` watchdog restarts the service if
   it's killed, on top of the in-service auto-reconnect. This handles the common failures
   (network blip, low-memory kill, reboot) **without any server cooperation**.
7. **Remote repair listener.** A lightweight command channel (MQTT via HA, or HTTP/poll) the
   companion subscribes to, so orchestration can nudge a wedged client to relaunch itself
   (see *Repair*). This same channel carries the presence on/off commands from HA.

### Per-device latency calibration

Portal+, Mini, Go, and Portal TV have different speakers and output paths, and Android-over-
WiFi adds jitter. `snapclient` exposes a per-client latency offset; the companion should
persist a tuned offset **per Portal model** and likely bump the default buffer (~1000 ms) up
front. Cross-room sync should be good, but verify on real hardware before promising
"Sonos-perfect."

## Presence — off / presence / on (server-side, via Home Assistant)

### Access finding (determined)

**An unprivileged app on the Portal cannot read the presence sensor.** This is confirmed in
Immortal's own `DreamPolicy` (measured on-device, `app/.../DreamPolicy.kt`): Meta's presence
signal is gated behind `signature|privileged` permissions Immortal can't hold, and there's no
root to escalate. Immortal only ever *reacts* to the system's presence-driven dream/sleep
transitions; it never reads occupancy directly.

The only on-device presence *proxy* — screen truly asleep vs awake — exists solely on the
**Portal Go on battery with battery-saver on**. On mains-powered Portals (the wall/counter
case where you'd most want presence) Immortal deliberately **holds the screen on**, so there's
no presence reflection at all. So a Portal-local presence signal is **not reliable** and we
should not build on it. (A quick `SensorManager.getSensorList()` probe in Phase 0 will confirm
nothing useful is exposed, but plan around HA regardless.)

### Reliable design

Put presence where it actually works: **Home Assistant room occupancy sensors** (mmWave /
motion / BLE — independent of the Portal) drive an HA automation that controls each room's
Portal. This sidesteps the Portal's locked-down sensor entirely.

Your requested tri-state is a **per-room mode**, surfaced in the launcher settings and the room
UI:

- **On** — the Portal is always in its group (always a candidate to play). Manual override.
- **Off** — the Portal is muted / removed from its group. Manual override.
- **Presence** — HA's room occupancy sensor manages this Portal's group membership
  automatically (occupied → joins/unmutes; empty after a grace period → leaves/mutes).

On/Off are immediate local overrides; **Presence** hands that room over to the HA automation.
Mechanically, all three resolve to Snapcast group membership / mute, driven either locally
(the companion) or by HA over the repair/command channel.

> Requires a room occupancy sensor in HA for any room set to **Presence**. Rooms without one
> simply use On/Off. This is the one place the design may want a small hardware add (a cheap
> mmWave or PIR sensor per room) — but it's optional and per-room.

## Managing rooms — reuse existing UIs

The "which rooms are playing" need is already solved; we choose a UI rather than build one.
Snapcast's model: **clients** (Portals) belong to **groups**, each **group** plays one
**stream**, volume is per-client, mute/stream-select is per-group.

- **Music Assistant in Home Assistant** — the recommended unified UI: rooms as
  `media_player` entities on a Lovelace dashboard, with voice and automations. This is also
  where the repair and presence automations live.
- **Snapweb** — ships with snapserver at `http://<server>:1780`; drag clients between groups,
  set volume, mute, pick a stream. Zero install, good for quick control.
- A bespoke dashboard only if MA/snapweb ever feels limiting — wait for a concrete gap.

## Repair — detection orchestrated centrally, action runs on-device

The constraint that shapes this: **the server cannot reach into Android to relaunch a dead
app.** snapserver knows a client dropped (it emits `Client.OnDisconnect`; HA flips the
entity to *unavailable*), but neither it nor HA can *start* an Android process from outside.
So repair is two layers, both anchored on the Portal:

1. **Local (the reliable 90%)** — the companion heals itself: in-service auto-reconnect plus
   the watchdog/`BootReceiver` restart. No server involvement.
2. **Remote nudge (the orchestrated 10%)** — for a wedged client that didn't self-recover:
   HA's Snapcast integration sees the client go unavailable → automation fires → it pokes the
   Portal over the **command channel the companion already listens on** (MQTT/HTTP) → the
   companion relaunches its own service. Detection and orchestration live centrally; the
   *action* runs on-device, which is the only place it can.

## Considerations / honest limits

- **No GMS / no root is fine here.** `snapclient` is self-contained native code over plain
  TCP; none of the Portal's usual limitations bite.
- **Apple Music** can only enter via AirPlay (no server-side provider) — see *Audio sources*.
- **Presence** must come from HA room sensors, not the Portal — see *Presence*.
- **Network.** Everything on one LAN/subnet; mDNS/Avahi discovery works but mind WiFi
  multicast quirks on some routers — allow a manual host as a fallback.
- **Android background limits.** A foreground service with a notification is mandatory on
  Android 9/10 to avoid being killed.
- **Sync realism.** Android-over-WiFi is the hardest client to keep tight; expect to tune
  buffer and per-model latency. Validate before over-promising.

## Licensing — important

`snapclient`/Snapcast is **GPL-3.0**; Immortal is **MIT**. We **cannot** compile or bundle
the native `snapclient` *into* `com.immortal.launcher` without forcing the whole launcher to
become GPL. Therefore:

- The player is a **separate, GPL-licensed companion app** that owns the `snapclient` binary.
- Immortal **drives and presents** it over **intents** — a tile, a settings screen, status —
  but never links its code.

To the user it's one integrated feature; legally and architecturally it's two apps. The
companion installs silently through Immortal's own App Store / install daemon like any other
catalog app.

## Phased plan

- **Phase 0 — validate (no new code).** ✅ *Done:* Snapcast (stock `de.badaix.snapcast`) is
  in the App Store. Next: stand up the server (MA as a Docker app on TrueNAS), install
  Snapcast on 2–3 Portals, tap-connect each, and confirm sync is acceptable on real hardware.
  Also: a one-off `SensorManager.getSensorList()` probe on a Portal to confirm no occupancy
  sensor is exposed, and a check of which rooms already have (or need) an HA occupancy sensor.
- **Phase 1 — server + sources.** ✅ *Decided* (below). Stand up MA on TrueNAS with AirPlay +
  local library (+ Spotify), wire the HA *Music Assistant* integration on the Pi, and document
  the concrete setup in this repo.
- **Phase 2 — companion app.** Build *Immortal Snapcast*: autostart + foreground service +
  audio-focus + watchdog + command listener, with the launcher tile/settings integration
  (including the off/presence/on mode). GPL-licensed, intent-driven.
- **Phase 3 — orchestration.** Wire the HA automations: repair (offline → nudge) and presence
  (room occupancy → group membership) onto the companion's command channel.

## Decisions (resolved)

1. **Server host** — **TrueNAS** runs Music Assistant + snapserver (Docker); the Raspberry Pi
   Home Assistant stays as the control front end and automation brain. (Chosen because the Pi
   HA box is under-resourced and MA is the heavy component.)
2. **Audio sources** — **AirPlay** (primary; the path for Apple Music), **local library**, and
   **Spotify** via librespot (bonus). Apple Music has no server-side provider — AirPlay only.
3. **Presence** — **off / presence / on** per room, driven **server-side by Home Assistant
   room occupancy sensors** (the Portal can't read its own presence sensor). On/Off are manual
   overrides; Presence defers to HA.

### Still to confirm

- Which rooms already have an HA occupancy sensor, and which would need one added (only rooms
  set to **Presence** need one).
- Where the music library lives on TrueNAS (the path MA should index).

## Fallback

If Snapcast's Android sync misbehaves on the Portals, swap the endpoint stack to
**Squeezelite for Android (`org.lyrion.squeezelite`, on F-Droid) + Lyrion Music Server
(LMS)**, whose sync groups are very mature. Crucially, **none of the device-side companion
design above changes** — only the binary it wraps and the server it points at.

## References

- Snapcast — <https://github.com/badaix/snapcast>
- Snapdroid (the Android client we list) — <https://github.com/snapcast/snapdroid>
- Music Assistant, Snapcast player provider — <https://www.music-assistant.io/player-support/snapcast/>
- Squeezelite for Android (fallback) — <https://f-droid.org/en/packages/org.lyrion.squeezelite/>
