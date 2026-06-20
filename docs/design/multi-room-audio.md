# Multi-room audio for Portals — design

Synchronized, whole-home music across the Portal devices already running Immortal: play
the same audio, in sync, in every room — and manage which rooms are playing from one place.
This document records the architecture we settled on, what we reuse versus build, and the
phased path to get there.

**Status:** the audio path (stock **Snapcast** app for synced playback, with Music Assistant
as the server/source manager) is validated on real Portals, and the launcher now ships an
in-app **multi-room now-playing** layer: it reads the playing group's track off the snapserver,
shows it on the now-playing card + the device's media controls on *every* Portal (not just the
one driving playback), and forwards play/pause/skip to Music Assistant. Settings live under
*Immortal → Multi-room audio*. See *Licensing* for why this stays MIT.

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

## Client side — what we build

The synced audio is the **stock Snapcast app**'s job (it owns the GPL player; see *Licensing*).
The launcher adds an in-app **multi-room now-playing + control** layer — a foreground service
that reads the snapserver for the group's track, publishes it as a MediaSession, and forwards
transport to Music Assistant. The responsibilities below describe that layer.

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
5. **Screensaver cooperation.** Audio and the photo frame are independent of raw screen
   state — the frame coming and going for a slideshow or screen-bright change must not stutter
   the music. They *do* share one signal: the `PresenceState` source of truth (see *Presence*).
   In **Presence** mode both follow it together (present → frame + music; empty → screen off +
   music stopped); in **Always-on**/manual modes the companion takes presence from HA or the
   user override, never from raw screen on/off.
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

## Presence — off / presence / on

The behaviour we actually want is one rule shared by the screensaver *and* the music, so that
both react to the same thing instead of being wedged together:

| Screen idle and… | Screensaver | Music |
|---|---|---|
| **someone in the room** | photo frame up | playing |
| **room empty** (after a grace period) | screen off | stopped |

…with manual **on/off** overrides on top of that baseline. Getting this right means being
precise about *where* the occupancy signal comes from. There are two viable sources — a
**Portal-local proxy** (good enough for the common case, free) and **Home Assistant occupancy
sensors** (authoritative, decoupled) — and one that's genuinely closed to us.

### What's closed: reading Meta's presence directly

We tore apart the native `Photos_superframe.apk` (and the `aloha-gen1` system dump) to settle
this. Meta's presence is **front-camera computer vision**, not a discrete sensor:

- SuperFrame binds the **SmartCamera metadata service**
  (`ISmartCameraInternalMetadataService`) and subscribes to on-device CV "World" topics —
  `PersonCount`, `PersonIds`, `HeadBoxes`, `IsArmRaised`, `IsHandWaving`, `UpdateTimeMs`.
- That CV result is written to a system-wide presence store, exposed as
  `content://com.facebook.alohaservices.presence.state.PresenceStateContentProvider`
  (tables `TABLE_NAME_PRESENCE_CAMERA`). The Portal's PowerManager reads this store to choose
  *ambient (dream)* vs *sleep* at every screen timeout.

Access is **platform-signature-gated**. The provider/service require
`com.facebook.alohaservices.presence.fbpermission.ACCESS_APP_STATE` and
`com.facebook.aloha.permission.SMART_CAMERA_METADATA`. Meta's own grant manifest
(`assets/fbpermissions.json`) gates the presence service behind a hard-coded
`sha256withrsa` signature check, and every aloha permission defined in the dump is
`signature` / `signatureOrSystem`. So only an app signed with Meta's platform key can read
presence; an unprivileged, non-root third-party app **cannot** — directly. That part of the
old `DreamPolicy` comment was right.

What was *overstated* was concluding presence is therefore unachievable. We can't read Meta's
signal, but we already receive events **derived** from it, and that's enough for the baseline.

### What's open: the presence-derived proxy (Portal-local, free)

Because the Portal's PowerManager makes its dream/sleep decision *from* that camera presence
store, the dream/sleep lifecycle **is** presence, second-hand. Unprivileged apps receive it:
`ACTION_DREAMING_STARTED` / `ACTION_DREAMING_STOPPED`, the `DreamService` lifecycle,
`SCREEN_ON` / `SCREEN_OFF`, `PowerManager.isInteractive`. Read together:

- **dream starts** → the presence service saw someone → **PRESENT**.
- **dream stops → real sleep** (screen goes non-interactive) → empty room → **ABSENT**.
- **dream stops → re-dreams** (the periodic transient force-wake) → still **PRESENT**.

**Reliability and its one caveat.** This proxy is reliable *as long as we let the screen time
out*. Today Immortal **defeats it on mains power**: `DreamPolicy.holdScreenOn` pins
`FLAG_KEEP_SCREEN_ON` so the frame is permanent — which hides the very transition that signals
"room emptied." That was a deliberate "always-on wall frame" choice, but it's an *override*
masquerading as the default. The redesign (below) makes "let the Portal's presence policy run"
the **baseline**, and "always-on frame" an explicit opt-in — so the proxy is available on every
model, not just the Portal Go on battery-saver.

> **Verify before flipping the default.** The empty-room→sleep path is confirmed on the Go on
> battery; on mains-powered Portals it's *expected* (stock SuperFrame slept the wall units when
> you left) but unconfirmed inside Immortal's relaunch loop. Phase 0 task: set a mains Portal to
> Presence mode, leave the room, confirm it sleeps and re-dreams on return. Until then, ship
> Presence as available-but-opt-in with Always-on as the safe fallback.

We also no longer need a `SensorManager.getSensorList()` probe to "find" a sensor — the APK
proves there isn't one; presence is camera-CV behind the platform signature. (A probe is still
harmless confirmation, but don't expect it to surface anything.)

### What's authoritative: Home Assistant occupancy (decoupled)

For rooms where the local proxy isn't enough — a mains frame whose sleep behaviour we haven't
verified, a room where you want occupancy independent of the screen, or finer grace tuning —
**HA room occupancy sensors** (mmWave / motion / BLE) are the robust source. They're
independent of the Portal entirely and drive group membership server-side. This is the
recommended source for any room set to **Presence** that the proxy can't cover.

### The tri-state, resolved against both sources

The per-room mode lives in launcher settings (and the room UI):

- **On** — always a candidate to play / frame always up. Manual override; ignores presence.
- **Off** — muted / removed from its group; screensaver sleeps normally. Manual override.
- **Presence** — occupancy manages this room automatically. Its signal comes from, in order of
  preference: the **HA occupancy sensor** if the room has one (authoritative), else the
  **Portal-local proxy** (free, good for the common case). When the proxy is masked
  (Always-on frame) and there's no HA sensor, the room reports presence **UNKNOWN** and falls
  back to On.

On/Off are immediate local overrides; **Presence** defers to the resolved occupancy source.
Mechanically all three resolve to Snapcast group membership / mute — and, on the Portal itself,
to screensaver frame-vs-sleep — driven by the shared `PresenceState` source of truth (next
section).

> A room set to **Presence** wants *either* an HA occupancy sensor *or* a Portal whose local
> proxy is enabled (Presence frame mode, verified to sleep when empty). A cheap per-room mmWave
> / PIR into HA is the most reliable option and the only place the design may want a small
> hardware add — optional and per-room.

### One source of truth — `PresenceState`

Both consumers (the screensaver frame and the Snapcast music companion) must react to the
**same** presence signal, or they drift — music keeps playing to an empty room, or the frame
sleeps while audio plays. Today the launcher has no such object: presence is implicit in
scattered `@Volatile` flags in `DreamPolicy` (`userExitAt`, `bridgeAt`, `inStockHandoff`) and a
lone `DREAMING_STOPPED` receiver, with no `DREAMING_STARTED` at all.

The redesign introduces a single in-process source of truth, `PresenceState`, owned by a
`PresenceHub` that the launcher initialises at startup:

```
                       Android system events (unprivileged)
   DREAMING_STARTED ─┐  DREAMING_STOPPED ─┐  SCREEN_ON/OFF ─┐  POWER_* ─┐
                     ▼                    ▼                 ▼           ▼
                ┌──────────────────────────────────────────────────────┐
                │  PresenceHub  — classifies events → PresenceState     │
                │  { presence: PRESENT | ABSENT | UNKNOWN,              │
                │    screen:   INTERACTIVE | DREAMING | OFF,            │
                │    confident: Boolean,   // false when proxy masked   │
                │    sinceMs }                                          │
                └───────────────┬───────────────────────┬──────────────┘
              in-process listener│                       │ broadcast intent
                                 ▼                       ▼  com.immortal.launcher.PRESENCE
                    ┌────────────────────────┐  ┌────────────────────────────┐
                    │ Screensaver (frame)    │  │ Immortal Snapcast companion│
                    │ PRESENT → keep/relaunch│  │ PRESENT → play / reclaim   │
                    │ ABSENT  → let it sleep │  │ ABSENT  → pause / mute     │
                    │                        │  │ UNKNOWN → defer to HA       │
                    └────────────────────────┘  └────────────────────────────┘
```

- **In-process**, the screensaver subscribes directly: a `PRESENT` verdict keeps/relaunches the
  frame, an `ABSENT` verdict lets the device sleep. This *replaces* the ad-hoc relaunch and
  bridge-suppression logic in `DreamPolicy` — that logic becomes a pure classifier
  (`classifyDreamStop`) feeding the hub, not a tangle of side-effecting flags.
- **Cross-process**, the hub publishes a `com.immortal.launcher.PRESENCE` broadcast that the
  GPL companion app subscribes to — staying inside the **intent-only** integration boundary
  (no code linking; see *Licensing*). The companion maps `PRESENT → play`, `ABSENT → pause`,
  and — crucially — `UNKNOWN → defer to HA`, so an Always-on wall frame never silently strands
  the music; HA stays authoritative there.
- **`confident`** encodes the honest caveat in one field: it's `false` exactly when the frame
  is pinned on (proxy masked), telling every consumer "presence here is UNKNOWN — use HA or the
  manual override." This is the single place the proxy-vs-HA decision is expressed.

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
- **Presence** has two viable sources — a free Portal-local proxy (the dream/sleep lifecycle,
  reliable once we stop pinning the screen) and authoritative HA occupancy sensors. We *can't*
  read Meta's camera presence directly (platform-signed) — see *Presence*.
- **Network.** Everything on one LAN/subnet; mDNS/Avahi discovery works but mind WiFi
  multicast quirks on some routers — allow a manual host as a fallback.
- **Android background limits.** A foreground service with a notification is mandatory on
  Android 9/10 to avoid being killed.
- **Sync realism.** Android-over-WiFi is the hardest client to keep tight; expect to tune
  buffer and per-model latency. Validate before over-promising.

## Licensing

`snapclient`/Snapcast is **GPL-3.0**; Immortal is **MIT**. The boundary that keeps the
launcher MIT is simple and absolute: **the launcher never bundles `snapclient`.** The synced
audio is rendered by the **standalone stock Snapcast app** (`de.badaix.snapcast`, installed
from the App Store), which owns the GPL binary in its own process.

Everything the launcher does for multi-room is original MIT code that talks over the network
and to Android's media APIs — never linking GPL code:

- Now-playing metadata is read from the snapserver's JSON-RPC **control protocol** over a
  socket (speaking a protocol to a GPL program is not a derivative work).
- Transport is sent to **Music Assistant's** WebSocket API.
- The track is published as a standard Android **MediaSession**.

So multi-room is two apps — the GPL Snapcast player and the MIT launcher — that coexist on
the device; the launcher drives and presents, the Snapcast app plays the audio.

## Phased plan

- **Phase 0 — validate (no new code).** ✅ *Done:* Snapcast (stock `de.badaix.snapcast`) is
  in the App Store. Next: stand up the server (MA as a Docker app on TrueNAS), install
  Snapcast on 2–3 Portals, tap-connect each, and confirm sync is acceptable on real hardware.
  Also: **verify the presence proxy on a mains Portal** — set Presence mode, leave the room,
  confirm it sleeps (and re-dreams on return); this is the gate for making Presence the default
  (see *Presence*). And check which rooms already have (or need) an HA occupancy sensor for the
  rooms the proxy can't cover. (The APK teardown already proved no occupancy *sensor* is
  exposed — presence is camera-CV behind the platform signature — so no `getSensorList()` hunt
  is needed.)
- **Phase 1 — server + sources.** ✅ *Decided* (below). Stand up MA on TrueNAS with AirPlay +
  local library (+ Spotify), wire the HA *Music Assistant* integration on the Pi, and document
  the concrete setup in this repo.
- **Phase 2 — companion app.** Build *Immortal Snapcast*: autostart + foreground service +
  audio-focus + watchdog + command listener, with the launcher tile/settings integration
  (including the off/presence/on mode). GPL-licensed, intent-driven. It subscribes to the
  launcher's `com.immortal.launcher.PRESENCE` broadcast (the `PresenceState` source of truth,
  scaffolded in the launcher under *Presence*) for the local-proxy occupancy signal.
- **Phase 3 — orchestration.** Wire the HA automations: repair (offline → nudge) and presence
  (room occupancy → group membership) onto the companion's command channel — the authoritative
  occupancy source that overrides the local proxy where a room has an HA sensor.

## Decisions (resolved)

1. **Server host** — **TrueNAS** runs Music Assistant + snapserver (Docker); the Raspberry Pi
   Home Assistant stays as the control front end and automation brain. (Chosen because the Pi
   HA box is under-resourced and MA is the heavy component.)
2. **Audio sources** — **AirPlay** (primary; the path for Apple Music), **local library**, and
   **Spotify** via librespot (bonus). Apple Music has no server-side provider — AirPlay only.
3. **Presence** — **off / presence / on** per room, baseline behaviour shared by the
   screensaver and the music via a single `PresenceState` source of truth. We *can't* read
   Meta's camera presence (platform-signed), but the dream/sleep lifecycle is a free, reliable
   **presence proxy** once we stop pinning the screen; **HA occupancy sensors** are the
   authoritative source that overrides it per room. On/Off are manual overrides; Presence
   resolves to HA-if-available, else the proxy. (Revised from the earlier "HA-only" decision
   after the APK teardown — see *Presence*.)

### Still to confirm

- **Does the presence proxy sleep a mains Portal when the room empties?** The gate for making
  Presence the default frame mode (Go-on-battery is already confirmed) — see *Presence*.
- Which rooms already have an HA occupancy sensor, and which would need one added (only rooms
  set to **Presence** *and* not covered by the local proxy need one).
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
