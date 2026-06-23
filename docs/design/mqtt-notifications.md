# MQTT notifications for Portals — design

An MQTT-driven notification surface that lets Home Assistant push a transient toast
(with optional image, sound, and a tap target) to any Portal running Immortal, plus
a way to fire a sound on its own. Two HA entry points: a discoverable
`notify.send_message` action for trivial one-line alerts, and a raw
`mqtt.publish` to the device's notify topic for rich doorbell-shaped payloads.
Either way the Portal renders a Portal-native bottom toast.

**Status:** designed, not yet implemented. This document records the schema we'll
ship, the behavior rules, and the implementation surface.

## Goal

- A doorbell event in HA reaches the kitchen Portal as a toast with the camera
  image; tapping the toast opens the security dashboard.
- A "package delivered" event chimes the bedroom Portal without changing what's on
  screen.
- The integration shows up in HA's notify picker for trivial alerts (a one-line
  `notify.send_message` action targets the Portal entity); rich doorbell-shaped
  payloads fall back to a single `mqtt.publish` against a documented topic.
- The broker is assumed to be trusted-LAN-only; the design does not defend
  against an attacker with publish credentials. See *Trust assumptions* below.
- Nothing about this requires the user to grant `SYSTEM_ALERT_WINDOW` or any other
  permission beyond what Immortal already has — the toast piggybacks on infra the
  launcher already owns.

## What it is not (kept narrow on purpose)

- **Not** a full-screen takeover. If you want a doorbell-shaped "drop everything,
  show me the camera" experience, fire the existing `open` text entity in the same
  automation — that already deep-links to any HA dashboard path. Notify is the
  ephemeral surface; `open` is the destination surface; HA automations compose them.
- **Not** a notification tray / shade. Portal didn't have one, and stacking past
  alerts on a 1–10" smart display is the wrong shape. A new toast replaces the
  current one.
- **Not** a multi-button action menu. One `on_tap`, applied to the toast as a
  whole. If you need [Unlock] [Talk] [Dismiss], wire HA buttons in the destination
  dashboard.
- **Not** a fleet broadcast. Each Portal subscribes only to its own
  `immortal/<id>/notify/set` topic; there is no "all Portals" address.
  HA automations that want to ring every Portal name them individually
  (`target: entity_id: [notify.kitchen, notify.bedroom]` on Track 1, or N
  `mqtt.publish` actions on Track 2). HA's editor handles this natively.

## Payload schema

The canonical interface is a JSON document published to
`immortal/<device_id>/notify/set`. All fields are optional.

```json
{
  "title": "Front door",
  "message": "Motion at 6:42pm",
  "image": "http://homeassistant.local:8123/api/camera_proxy/camera.front_door",
  "sound": "http://nas.local/doorbell.mp3",
  "position": "bottom",
  "duration": 8,
  "volume": 1.0,
  "wake_screen": true,
  "on_tap": "lovelace/security"
}
```

Field semantics:

| Field         | Type   | Default    | Notes                                                                                          |
|---------------|--------|------------|------------------------------------------------------------------------------------------------|
| `title`       | string | `""`       | Bolded line at the top of the toast.                                                           |
| `message`     | string | `""`       | Body text. Long messages wrap to two lines, then ellipsize.                                    |
| `image`       | string | `null`     | `http(s)://` URL → fetched, or `data:image/...;base64,...` → decoded inline. See *Image* below. |
| `sound`       | string | `null`     | `http(s)://` URL or local URI fed to `MediaPlayer`. See *Sound* below.                          |
| `position`    | enum   | `"bottom"` | `"top"` or `"bottom"` — bottom matches Portal's own ephemeral-UI gravity (Quick Controls, "Hey Portal" listener). |
| `duration`    | int    | `6`        | Auto-dismiss timeout for the visual toast, in **seconds**. `0` = no auto-dismiss; the toast stays until the user taps it. Sound has its own lifecycle. |
| `volume`      | float  | `1.0`      | Sound volume `0.0`–`1.0` of the alarm-stream max. Bounded by the user's system alarm-volume slider (so `1.0` ≠ "loudest possible," it's "loudest the alarm slider is set to"). On Portal, alarm is the only stream independent from the "media volume" group — see *Sound → Portal volume quirk*. |
| `wake_screen` | bool   | `true`     | If the screen is off when the notify arrives, call `ScreenControl.wake` so the toast is visible. Set `false` for low-priority chimes that shouldn't wake a sleeping room. |
| `on_tap`      | string | `null`     | Same string grammar as the existing `open` entity: full URL, installed package name, or bare HA dashboard path. |

### Special cases

- **Empty payload** (`{}` or empty string): no-op. Safe so an HA automation can't
  silently produce a "ghost" toast by forgetting a template field.
- **Sound-only**: `sound` present, both `title` and `message` empty → no visual,
  just audio. Covers "chime when the package arrives, don't touch the screen."
- **No `on_tap`**: tap dismisses the toast early. Auto-dismiss still fires
  after `duration`.
- **Acknowledgement-required (`duration: 0`)**: the toast renders and stays.
  No timer; only a tap dismisses it. Combine with `on_tap` to make the tap
  also navigate (smoke alarm: tap to acknowledge → opens the alarm dashboard).
- **Image load failure**: image times out, 404s, or fails to decode → render the
  toast without the image, keep text and sound. Don't suppress the whole toast for
  one missing asset — a doorbell event with text-but-no-camera-frame is still
  useful.
- **Replace, don't stack**: a new toast arriving while one is showing replaces it
  immediately. The new sound starts; the old sound is cut off only if the new
  payload has its own `sound` field (otherwise an in-flight chime keeps playing).
- **Wake the screen (unless opted out)**: if the screen is off when the notify
  arrives and `wake_screen` is `true` (the default), call `ScreenControl.wake`
  before showing the toast so the user actually sees it. `wake_screen: false`
  on a low-priority chime keeps a sleeping bedroom dark.
- **Respect Do Not Disturb (audio only)**: before playing sound, check
  `NotificationManager.getCurrentInterruptionFilter()`. If it's anything other
  than `INTERRUPTION_FILTER_ALL`, suppress the sound — the toast still
  renders. DND silences audible interruptions; the visual stays because the
  screen is the screen, and a silent on-screen alert is also the feedback
  signal that the payload reached the device (no separate state echo needed
  for debugging).

### Image: URL vs. inline

Two paths, detected by prefix:

- `data:image/<mime>;base64,<payload>` — decoded synchronously via
  `Base64.decode` + `BitmapFactory.decodeByteArray`. Use this when the producer
  already has the bytes (HA template using `state_attr('camera.foo', 'snapshot')`).
- Anything else starting with `http://` or `https://` — fetched on a background
  thread with a short timeout (~3s), `BitmapFactory.decodeStream`. Use this for
  the common case of HA camera-proxy URLs on the LAN.

Decoding uses `BitmapFactory.Options.inSampleSize` to downscale **during**
decode, sized so the long edge lands at ≤512px. Decoding full-resolution and
then `Bitmap.createScaledBitmap`-ing OOMs the Gen-1 Portal+ on anything 4K —
the in-decode subsample is non-optional, not an optimization.

**Producer note — HA camera URLs need auth.** A URL like
`http://homeassistant.local:8123/api/camera_proxy/camera.front_door` requires
a bearer token; the Portal fetches it anonymously and gets `401`. The working
patterns are:

- Save a snapshot in HA to `/config/www/snapshot.jpg` (the `camera.snapshot`
  service writes there), then reference it as
  `http://homeassistant.local:8123/local/snapshot.jpg` — `/local/` is served
  without auth.
- Embed a long-lived access token in the URL:
  `http://homeassistant.local:8123/api/camera_proxy/camera.front_door?token=<long-lived>`.
- For Track 1 senders inside HA, template the bytes inline as `data:image/...;base64,...`
  — HA fetches the snapshot itself (auth in scope) and the Portal never has to.

This is producer-side; the Portal can't help. Worth surfacing in the user-facing
HA setup guide too.

### Sound

`MediaPlayer.setDataSource(url)` for both HTTP and local URIs. Lifecycle is
independent of the toast:

- Routed to `STREAM_ALARM` via `AudioAttributes` (`USAGE_ALARM` +
  `CONTENT_TYPE_SONIFICATION`) — **not** `STREAM_NOTIFICATION` or `STREAM_MUSIC`.
  See *Portal volume quirk* below for the on-hardware reason.
- `MediaPlayer.setVolume(volume, volume)` from the payload's `volume` field
  (`0.0`–`1.0`), defaulting to `1.0`. The multiplier is bounded by the user's
  system alarm-volume slider — `volume: 1.0` means "as loud as the user set the
  Alarm slider," not "as loud as the hardware can go."

#### Portal volume quirk

On Portal hardware (verified on Portal+ / Android 10), the "media volume"
slider drives a single linked group of seven streams: `STREAM_SYSTEM`,
`STREAM_RING`, `STREAM_MUSIC`, `STREAM_NOTIFICATION`, `STREAM_SYSTEM_ENFORCED`,
`STREAM_DTMF`, `STREAM_TTS`. Only `STREAM_VOICE_CALL` and `STREAM_ALARM` are
independent. So `USAGE_NOTIFICATION → STREAM_NOTIFICATION`, which sounds like
the "right" semantic match on regular Android, in practice routes to the same
slider as Spotify — meaning every chime is at music volume and "doorbell at 5%
because Spotify was quiet earlier" is the default failure mode.

`STREAM_ALARM` is the only stream that's both **independent** (one persistent
slider position, doesn't drift) and **loud-by-default** on Portal. It's also
the semantic match for "audible alert that needs attention." So we route there.

**DND interaction:** Android's "alarm bypasses DND" behavior only applies to
system-generated notifications, not to raw `MediaPlayer` audio. Our DND
suppression happens upstream in `MqttPublisher.handleNotify` (we gate the call
to `SoundPlayer.play` on `INTERRUPTION_FILTER_ALL`), so routing through alarm
doesn't break the polite-in-DND rule.
- **In-call behavior**: if the Portal is in an active Messenger/WhatsApp call,
  the call holds `STREAM_VOICE_CALL` focus and our `STREAM_NOTIFICATION` focus
  request is denied — sound silently doesn't play. Visual toast still renders
  (bottom-anchored, doesn't obscure the call UI much). This falls out of
  Android's focus model; documented so it's expected behavior, not a bug.
- Audio focus requested as `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — music
  currently playing on `STREAM_MUSIC` (Snapcast / Music Assistant / cast)
  ducks for the chime's duration instead of stopping. Doorbell-during-Spotify
  behaves the way a phone would.
- The sound plays to completion (or until the file ends); it is **not**
  truncated by `duration` and **not** killed by tap-to-dismiss.
- If a second `notify` arrives with its own `sound`, the in-flight player is
  released and the new one starts. If the second `notify` has no `sound`, the
  in-flight player keeps going.
- A `MediaPlayer` that fails to prepare (bad URL, codec mismatch, transient
  network) is released silently. The visual toast (if any) still renders — we
  treat sound the same way we treat image: best-effort, never blocks the rest.

## Home Assistant integration

Two paths to fire a notify, by design: discoverable for the common case,
honest about its limits, and a raw escape hatch for rich payloads.

### Track 1: `notify.send_message` — simple, discoverable

An MQTT `notify` entity is published via discovery, so the Portal appears in
HA's notify picker (`notify.kitchen_portal`, `notify.bedroom_portal`, etc.)
and slots into the standard `send_message` action:

```yaml
action: notify.send_message
target:
  entity_id: notify.kitchen_portal
data:
  message: "Door unlocked"
```

This produces a plain-text toast at the device's default position (bottom)
with the default duration (6s). **Only `message` reaches the wire** — HA's
`NotifyEntity` platform (2024.7+) intentionally passes only the message
string to `command_template`; `title`, `data:`, `target` are accepted by the
service but not template-exposed and will be silently dropped. The entity is
for trivial alerts ("garage door closed", "package detected") where a one-line
toast is the whole story.

### Track 2: `mqtt.publish` — the full schema

For doorbell-shaped flows that want title, image, sound, tap-action, position,
or non-default duration, automations publish the full JSON payload directly:

```yaml
action: mqtt.publish
data:
  topic: immortal/kitchen-portal/notify/set
  payload: |
    {
      "title": "Front door",
      "message": "Motion at 6:42pm",
      "image": "http://homeassistant.local:8123/api/camera_proxy/camera.front_door",
      "sound": "http://nas.local/doorbell.mp3",
      "on_tap": "lovelace/security",
      "duration": 8000
    }
```

Same wire contract as Track 1 — the on-device handler is identical. The
only difference is that Track 1 hides everything but `message` behind HA's
notify abstraction.

### Why two tracks?

HA's `NotifyEntity` is a deliberate narrowing of the old
`BaseNotificationService` (which did pass `data:` through). The replacement
architecture exposes only `message` to the template; richer payloads now
belong to per-integration custom services (`notify.mobile_app_*` ships its
own websocket protocol to the phone, bypassing the MQTT abstraction
entirely). MQTT has no analogue of that path short of shipping our own HA
custom component — out of scope for v1, sketched in *Future hooks*. So we
accept the split: discoverable simple, raw-but-honest rich.

### Topic layout

Reusing `MqttPublisher`'s base path (`immortal/<device_id>/...`):

```
immortal/<id>/notify/set        ← JSON payload, full schema (Track 2)
                                  Track 1 publishes the same topic with just
                                  {"message": "..."} via command_template.
homeassistant/notify/immortal_<id>/notify/config  ← discovery for Track 1
```

Fire-and-forget: no state topic. HA's logbook records every `send_message`
call on Track 1; Track 2 callers can log their own `mqtt.publish` events.

**Producers MUST NOT retain notify payloads.** A retained `set` topic would
re-fire the alert on every MQTT reconnect — a 3am doorbell every time the
device's WiFi flaps. The on-device PUBLISH loop defends against this by
inspecting the retain bit in the packet header (`pkt.flags and 0x01`) and
dropping any retained delivery on a `<base>/+/set` topic, with a log line
for visibility.

Per MQTT v3.1.1 §3.3.1.3, the broker sets the retain bit on delivery **only
when the message is sent as a result of a new subscription being made**, not
on subsequent live publishes. So this check catches the exact failure mode
that matters (the broker's protocol-mandated retained replay on subscribe /
reconnect) and is silent on mid-session `mqtt.publish ... retain: true` —
which arrives at existing subscribers with retain bit cleared, indistinguishable
from a normal publish, and which fires once as the producer intended.

The retained value in the broker still persists across restarts and is re-fired
on each new subscription, so producers who set `retain: true` will see the
phantom-on-reconnect behavior the next time the device cycles. Track 1
(`notify.send_message`) doesn't retain by default; the risk is purely Track 2
callers who set `retain: true` in their `mqtt.publish` action.

### Discovery config

```json
{
  "name": "Notify",
  "unique_id": "immortal_<id>_notify",
  "command_topic": "immortal/<id>/notify/set",
  "command_template": "{\"message\": {{ value|tojson }}}",
  "availability_topic": "immortal/<id>/availability",
  "device": { ... shared device block, same as every other entity ... }
}
```

The template wraps the bare `message` string into the same JSON envelope the
device parses for Track 2 — so the on-device handler has one parsing path
regardless of which track produced the payload.

## Implementation

Four new pieces; everything else extends existing code.

### 1. `NotificationOverlay` (new)

A bottom- or top-aligned toast view attached to `BarWatchService` as a third
`wm.addView` peer alongside `QuickBar` and `RemoteCursor`. Each existing
tenant hand-rolls its own `TYPE_ACCESSIBILITY_OVERLAY` window via the
accessibility service's `WindowManager` (see `QuickBar.kt:46–72` for the
pattern); `NotificationOverlay` mirrors it as a singleton `object` with
`attach(service)` / `detach()` / `show(spec)`. No new permission required,
no `SYSTEM_ALERT_WINDOW`, no manifest change.

**Boot-race:** `MqttService` and `BarWatchService` come up independently —
there's a window where `MqttPublisher` may receive a notify before
`BarWatchService.onServiceConnected` has fired and attached the overlay. In
that case `NotificationOverlay.show(spec)` finds `host == null` and drops the
payload with a single `Log.w`. Acceptable: the producer can retry, and the
window is on the order of a second at startup.

**Lifecycle:** `BarWatchService` is baseline-enabled on every provisioned
Portal — `SettingsGuard.reconcileBarWatch` is now unconditional
(`setBarWatchEnabled(context, true)`) because the service also backs the
Calls→stock-home bridge and the phone remote, so it's always on regardless
of which feature is the caller. That means `NotificationOverlay.attach(this)`
just slots into `BarWatchService.onServiceConnected` alongside the existing
three lines (`QuickBar.attach`, `RemoteInput.register`, `RemoteCursor.attach`),
and `detach()` matches in `onUnbind`. No reconciler change. On unprovisioned
devices the accessibility service silently can't self-enable (it requires
`WRITE_SECURE_SETTINGS`, granted by the provisioning kit), but MQTT wouldn't
be configured on an unprovisioned Portal either — both are baseline-launcher
concerns gated on the same setup step.

Layout:

```
 ┌──────────────────────────────────────────────┐
 │ ┌────┐  Front door                           │
 │ │img │  Motion at 6:42pm                     │
 │ └────┘                                       │
 └──────────────────────────────────────────────┘
```

- Width: `match_parent` minus 24dp side margins.
- Height: hugs content; image is 80dp square when present.
- Background: same dark scrim Immortal uses for its other overlays (consistent
  with `FolderOverlay` / `NameOverlay` in `HomeActivity.kt`).
- Vertical anchor: gravity flip based on `position`.
- Slide-in / slide-out animation: ~180ms, from the edge it's anchored to.
- Tap target: the whole card. Tap → fire `on_tap` (if set) and dismiss.

A single instance of the overlay lives in the accessibility service; `show(spec)`
replaces whatever's currently displayed. The auto-dismiss timer is held by the
overlay and reset on each `show`.

### 2. `SoundPlayer` (new)

```kotlin
object SoundPlayer {
  fun play(context: Context, source: String): Unit
  fun stop(): Unit
}
```

- Holds a single `MediaPlayer`. `play()` releases any in-flight player and
  starts a new one.
- Requests transient-duck audio focus before `start()`, abandons it in
  `onCompletion` / `onError`.
- Surfaces failure as a single-line `Log.w`, never throws.

### 3. `MqttPublisher.handleCommand` — new branch

```kotlin
"notify" -> handleNotify(payload)
```

Retained set-topic messages are dropped upstream of `handleNotify` in the
PUBLISH-handling loop (`MqttPublisher.loop`): the retain bit in the MQTT
packet flags (`pkt.flags and 0x01`) is checked, and any retained delivery on
`<base>/<obj>/set` is logged-and-dropped before dispatching to a command
branch. Per MQTT spec this catches the broker's retained replay on
subscribe/reconnect (the bug that matters); see *Topic layout* for the
spec-nuance on mid-session retained publishes. The check covers every command
(notify, open, screen_power, etc.), not just notify — none of the command
topics should ever be retained.

`handleNotify(json)`:

1. Parses the JSON (gracefully — malformed → log + drop).
2. If `title` or `message` non-empty:
   a. If the screen is off and `wake_screen != false`, call `ScreenControl.wake`.
   b. `NotificationOverlay.show(spec)` regardless of DND (visual is always
      allowed; DND silences audio only).
3. If `sound` non-empty: check DND — if `getCurrentInterruptionFilter()` is
   `INTERRUPTION_FILTER_ALL`, `SoundPlayer.play(context, sound, volume)`;
   otherwise skip the sound. (`STREAM_VOICE_CALL` focus from an active call
   naturally denies our focus request — sound is then a silent no-op.)

The empty-payload no-op falls out naturally — neither branch fires.

### 4. `MqttPublisher.publishDiscovery` — register the entity

Add one entry to the `allEntities` list:

```kotlin
"notify" to "notify"   // (component, object)
```

And a `notifyEntity()` helper that emits the discovery config shown above.
Mirrors `textEntity()` in shape; the only difference is the `notify` component
slot and the `command_template`.

## Trust assumptions

The notify payload accepts arbitrary URLs and feeds them to `MediaPlayer`
(sound) and `BitmapFactory.decodeStream` (image). A compromised or untrusted
broker with publish access to `immortal/+/notify/set` can therefore trigger
image and sound fetches against attacker-controlled hosts — or against
private-network addresses (`localhost`, `192.168.x.x`) — on every Portal in
the fleet, with whatever side effects those endpoints expose.

This is not a new threat class. `MqttPublisher` already trusts the broker for
`open` (deep-link to any URL/package/HA path) and `screen_power` (wake/sleep)
commands; notify widens the URL-loading surface but does not introduce a new
privilege. The deployment assumption is the same as every Home Assistant
MQTT install: **the broker runs on a trusted LAN with publish-restricted
credentials.** We do not add scheme/host allow-listing in v1; producers who
want it can run their own filtering bridge between HA and the device topic.

## Future hooks (out of scope for v1)

The user has flagged eventual interest in HA-driven on-device timers and a
local wake-word path. Both fit cleanly on the same pipeline:

- `MqttPublisher.handleCommand "timer"` — start/cancel a named timer on the
  device; ring on completion via the same `SoundPlayer`.
- `MqttPublisher.handleCommand "speak"` — local TTS via Android's built-in
  engine.
- A `wake_active` binary sensor — exposes the device's mic-listening state to
  HA so the Assist conversation agent can be gated on it.

Keep the JSON-payload shape consistent across these so HA automations feel
uniform; bundle new fields under the same `data:` dict that `notify` already uses.

## Validation plan

- **Unit**: payload parser — empty, partial, malformed, mixed-case position,
  bad `duration` (negative / non-numeric), bad `volume` (out-of-range,
  non-numeric → clamp to `[0.0, 1.0]`).
- **On-device**: doorbell-style flow on a real Portal+ (Gen-1, Android 9) and
  a Portal Go (Android 10) — toast appears, image renders, sound plays at
  `volume` over Spotify (which ducks), tap navigates to the configured HA
  dashboard, screensaver doesn't swallow the toast.
- **Concurrency**: two `notify` events 200ms apart — the second replaces the
  first visually; sound behaves per the rules above.
- **MediaPlayer race**: two `notify` events with `sound` 50ms apart — verify
  no crash. `MediaPlayer.release()` racing `prepareAsync()` is a known
  Android-9 crasher and Portal+ Gen-1 is exactly that target.
- **Audio focus return**: notify with sound arrives while Snapcast is playing
  via `NowPlayingHub` — verify Snapcast resumes (un-ducks) after the chime
  ends, doesn't stay paused.
- **Image timeout**: notify with an image URL pointing at an unreachable host
  — verify the toast renders within ~5s (connect/read timeout plus render
  budget), worker thread isn't blocked beyond that. Explicit `connectTimeout`
  / `readTimeout` on the fetch.
- **Retained-replay**: publish a `notify` payload with `retain: true` (the
  misconfigured Track 2 case), bounce the device — verify the retained
  payload is dropped on reconnect (logged as `"ignoring retained set-topic
  message"`), no phantom doorbell. Mid-session retained publishes will fire
  once per the MQTT spec (broker strips the retain bit on delivery to existing
  subscribers), which is correct.
- **DND audio-only**: notify with sound arrives while DND is on — verify
  toast renders, sound suppressed; turn DND off, fire again — sound plays.
  Also verify an in-flight sound is not killed by DND toggling on mid-render
  (sound has its own lifecycle).
- **Failure modes**: bad image URL (toast still renders), bad sound URL (toast
  still renders, no audio), bad `on_tap` (toast dismisses, no navigation,
  log warning).
- **Baseline accessibility-service host**: enable MQTT/HA only, with QuickBar
  and phone-remote disabled, on a provisioned Portal — verify the toast still
  renders (the service is baseline-enabled, so its tenants attach regardless
  of which UI feature is on).

