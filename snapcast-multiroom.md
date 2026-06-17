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
  │  Music Assistant   (Home Assistant / TrueNAS │  ← library + streaming sources,
  │                     / a laptop)              │     room UI, repair orchestration
  │     └── snapserver (built-in or external)    │  ← chunks + timestamps the audio
  └───────────────────────┬─────────────────────┘
                          │  TCP, time-synced
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
  ┌───────────┐     ┌───────────┐     ┌───────────┐
  │  Portal   │     │  Portal   │     │  Portal   │   ← Immortal companion app wraps
  │ (Kitchen) │     │ (Bedroom) │     │  (Office) │      the native snapclient
  └───────────┘     └───────────┘     └───────────┘
```

Three layers, each owned by software that's best suited to it:

1. **Music Assistant (MA)** — the front end. Manages your streaming sources and library,
   presents every room as a `media_player` in Home Assistant, groups players to play in
   sync, and is the natural home for the repair automation. MA ships a **built-in
   snapserver** but can also drive an external one.
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
unified **source management** (Spotify/library/radio) and a unified **room UI**. Squeezelite +
Lyrion (LMS) is the documented **fallback endpoint** if Snapcast's Android sync proves flaky
on real Portals; it requires none of the device-side app work below to change.

## Server side — reuse, don't build

Nothing here is Portal-specific; it's standard software that already exists in the house.
Pick **one** host and **one or more** audio sources.

**Host options** (any one):
- **Home Assistant** — run MA as the HA add-on; lowest friction since HA is already in play.
- **TrueNAS** — MA/snapserver as a Docker app; good if you want it off the HA box.
- **A laptop / always-on Linux box** — fine for testing or a small setup.

**Audio sources** (enable one or several; each becomes a selectable Snapcast *stream*):
- **librespot** — makes the server a Spotify Connect target.
- **AirPlay** — cast from phones/Macs.
- **Local library / radio** — via Music Assistant directly.

> A Portal *could* host the server via Termux, but that pins the whole system to one Portal
> staying awake. Not recommended as the default.

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
   **settings screen** (server host or mDNS name, room name, latency offset, which stream)
   living in Immortal's UI the way the screensaver settings do. Immortal sends the companion
   intents to connect/disconnect/select-stream and reads back status.
5. **Screensaver cooperation.** Keep audio playing while the photo-frame `DreamService` is
   up; decide whether playback should follow the presence sensor (probably not — music
   should outlast someone leaving the room).
6. **Self-heal watchdog.** A `WorkManager`/`AlarmManager` watchdog restarts the service if
   it's killed, on top of the in-service auto-reconnect. This handles the common failures
   (network blip, low-memory kill, reboot) **without any server cooperation**.
7. **Remote repair listener.** A lightweight command channel (MQTT via HA, or HTTP/poll) the
   companion subscribes to, so orchestration can nudge a wedged client to relaunch itself
   (see *Repair*).

### Per-device latency calibration

Portal+, Mini, Go, and Portal TV have different speakers and output paths, and Android-over-
WiFi adds jitter. `snapclient` exposes a per-client latency offset; the companion should
persist a tuned offset **per Portal model** and likely bump the default buffer (~1000 ms) up
front. Cross-room sync should be good, but verify on real hardware before promising
"Sonos-perfect."

## Managing rooms — reuse existing UIs

The "which rooms are playing" need is already solved; we choose a UI rather than build one.
Snapcast's model: **clients** (Portals) belong to **groups**, each **group** plays one
**stream**, volume is per-client, mute/stream-select is per-group.

- **Music Assistant in Home Assistant** — the recommended unified UI: rooms as
  `media_player` entities on a Lovelace dashboard, with voice and automations. This is also
  where the repair automation lives.
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
  in the App Store. Next: stand up a server (MA as a Home Assistant add-on), install Snapcast
  on 2–3 Portals, tap-connect each, and confirm sync is acceptable on real hardware.
- **Phase 1 — server + sources.** Decide the host (HA / TrueNAS / laptop) and the audio
  sources (librespot / AirPlay / local). Document the chosen setup in this repo.
- **Phase 2 — companion app.** Build *Immortal Snapcast*: autostart + foreground service +
  audio-focus + watchdog + command listener, with the launcher tile/settings integration.
  GPL-licensed, intent-driven.
- **Phase 3 — repair orchestration.** Wire the HA automation (offline → nudge) to the
  companion's command channel.

## Open decisions

1. **Server host** — Home Assistant add-on, TrueNAS, or a laptop?
2. **Audio sources** — librespot (Spotify), AirPlay, local library — which, and how many?
3. **Presence behavior** — should music follow the Portal's presence sensor, or ignore it?

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
</content>
</invoke>
