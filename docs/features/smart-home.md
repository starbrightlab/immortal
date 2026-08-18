# Smart home (Home Assistant / MQTT)

`MqttService` / `MqttPublisher` — Immortal can expose a Portal to
[Home Assistant](https://www.home-assistant.io/) over **MQTT Discovery**, so the device shows up
as something you can see and control.

## What it exposes

The publisher reuses the state Immortal already holds and surfaces it to Home Assistant:

- **Presence** and **screen** state (`PresenceHub`) — see [Presence](#presence) below.
- **Ambient sensors** — temperature and light, where the model has them. See
  [Ambient sensors](#ambient-sensors).
- **Now-playing** media (`NowPlayingHub`) — see [Multi-room audio](multi-room-audio.md).
- **Battery** (on models that have one).
- **IP address** (a diagnostic sensor).

## Presence

The **Presence** entity reads Meta's own camera detection. Portal's presence service logs a
heartbeat about every 30 seconds *while it can see someone*, and goes quiet when the room
empties — so Immortal tails the system log and reports presence from whether that beat is still
arriving (`PortalPresenceMonitor`). It's the real signal, not an inference, and it keeps working
while the photo frame is pinned on.

This needs the `READ_LOGS` permission, which the [provisioning kit](../provisioning.md) already
grants for the fleet agent — so on a provisioned Portal there's nothing to do. If your Portal was
set up before that grant existed, re-run the provisioner.

Where the detector can't be read — no grant, or a Portal that doesn't log it — the entity falls
back to inferring presence from the screensaver's dream/sleep lifecycle. The entity's
`source` attribute says which you're getting:

| `source` | Meaning |
| --- | --- |
| `portal` | Meta's own camera detection. Direct observation of the room. |
| `proxy` | Inferred from the dream/sleep lifecycle. Blind while the frame is pinned on — see the `confident` attribute. |
| `screen` | Last resort: whether the panel is on. Used only before the proxy has seen a transition. |

Silence is never read as an empty room: until a first heartbeat arrives the log reader says
nothing at all and the proxy stays in charge, so a Portal that never reports presence doesn't sit
in Home Assistant claiming the room is permanently empty.

Turn it off with **Use the Portal's own detector** under **Settings → Immortal** to go back to the
proxy. It lives there rather than under Home Assistant because it isn't MQTT-specific: the
[fleet agent](fleet.md) and [multi-room audio](multi-room-audio.md) read the same presence signal,
so they get the better reading too — whether or not Home Assistant is set up.

## Ambient sensors

Immortal publishes the Portal's ambient sensors as Home Assistant entities:

| Entity | Unit | Notes |
| --- | --- | --- |
| Temperature | °C | Has a calibration offset — see below. |
| Ambient light | lx | |
| Humidity | % | Only if the model has the hardware. |
| Pressure | hPa | Only if the model has the hardware. |

**Sensors are auto-detected**, so only the ones your Portal physically has ever appear — no entity
is advertised for hardware that isn't there, and switching the feature off clears the entities from
Home Assistant rather than leaving them behind as "unavailable".

!!! tip "Calibrate the temperature"
    A temperature sensor inside a powered device reads its own waste heat, so it runs a few degrees
    warm. Put a thermometer next to the Portal, then set **Temperature offset** (Settings → Home
    Assistant (MQTT)) to the difference — it applies immediately, without waiting for the room to
    change.

Readings are rate-limited rather than forwarded raw: a value has to move by a meaningful amount and
wait out an interval before it's published, so a twitchy light sensor doesn't bury the broker. A
large jump — someone switching a lamp on — skips most of that wait, because that's the event an
automation is actually waiting for.

## Camera

A Portal can be a camera for Home Assistant — live H.264 video over RTSP, with optional sound.
It is **off by default**, and the consent is deliberately on the device only: turn it on under
**Settings → Home Assistant (MQTT) → Camera**. Nothing arriving over MQTT can enable it, and
while it is off the camera is never opened.

Once on, three entities appear: a **Camera streaming** switch, a **Camera sound** switch, and a
**Stream URL** diagnostic sensor telling you where to point a player — typically
`rtsp://<portal-ip>:8554/`.

It plays directly in VLC. For a Home Assistant dashboard, feed it through go2rtc — the
**WebRTC Camera** card is the usual route:

```yaml
type: custom:webrtc-camera
url: 'rtsp://<portal-ip>:8554/'
```

### The Portal has to be showing Immortal

This is the one real constraint, and it comes from the platform rather than from us. **Android 9
gives the camera to the foreground app only**, so the stream runs while the Portal is sitting on
its home screen or photo frame — its normal state — and stops the moment somebody opens another
app on it.

That means two practical things:

- **You can't watch the stream on the Portal that's producing it.** Opening a player there puts
  that player in the foreground, which takes the camera away. Watch from anywhere else.
- **Using the Portal pauses the stream, briefly.** It comes back on its own within a few seconds
  of returning to the launcher — the switch stays on, the RTSP address doesn't change, and the
  picture resumes. Viewers see a gap, not a dead stream.

A foreground service isn't enough to avoid this, an overlay window isn't either, and neither is
forcing the `CAMERA` app-op with adb; all three were measured on a Portal Go. `docs/design/camera-streaming.md`
records what was tried.

### Other points worth knowing

- **Sound is optional and separate.** Turn on **Camera sound** (the Home Assistant switch, or
  Settings → Home Assistant (MQTT)) and the stream carries audio as well. It's off by default,
  and a viewer that only wants a picture can take the video track alone.
- **Muting the microphone silences the stream.** Not quietened — no audio leaves the device at
  all while the `Microphone mute` switch is on. That switch is the truth.
- **Someone talking takes the microphone back.** A live [intercom](tools.md) announcement, or
  recording a voice note, outranks a background stream: the sound drops out for the duration and
  the video carries on.
- **Streaming does not survive a reboot.** The camera *permission* persists, the live stream
  doesn't — a Portal that loses power comes back idle rather than quietly broadcasting a room.
- **Home Assistant can start the stream, but never enable the camera.** The switch only works
  within the consent given on the device.
- **While it runs, the camera is held.** The photo frame's wave-to-advance gesture and a Portal
  call want the same camera, so they can't overlap with streaming.
- **The Portal's green camera light is on** for as long as the stream is, alongside a permanent
  notification. That light is wired below the operating system, so it's a signal Immortal can
  neither fake nor forget to turn off — which is why there's no in-app badge competing with it.
- **Anyone with broker credentials can start it.** The same trust assumption as
  [notifications](#notifications) — but with a camera on the other end of it, so treat broker
  access accordingly.

## What it can control

- **Screen on/off** (`ScreenControl`, which uses the screen-off device-admin granted during
  [provisioning](../provisioning.md)) — wake or sleep a Portal's display as part of an automation.
- **Open** a URL, an installed package, or a Home Assistant dashboard path on the Portal — the
  same string grammar the screensaver picker accepts.
- **Screensaver** — show the photo frame on demand (a `Screensaver` button entity). This is the
  same photo-frame surface the launcher's header button opens; `Home` dismisses it. Note it's the
  in-app photo frame, not the system dream, so the `Screen state` sensor stays `interactive` while
  it's showing.
- **Notifications** — push a toast (with optional image, sound, and a tap target) from any
  Home Assistant automation. See below.

## Notifications

Immortal renders a Portal-native bottom toast in response to MQTT-driven notify messages from
Home Assistant. Two ways to fire one — pick whichever fits the automation:

![Immortal's settings page showing the Notifications section with "Accept notifications from your
network" switched on, and a "Backup complete" toast card at the bottom of the
screen](../img/notifications.png)

### Simple alerts: `notify.send_message`

Each configured Portal shows up in HA's notify picker. For a plain text alert, use the
standard `send_message` action:

```yaml
action: notify.send_message
target:
  entity_id: notify.kitchen_portal
data:
  message: "Door unlocked"
```

This produces a bottom toast at the default duration (6s). Only the `message` reaches the
device — Home Assistant's MQTT notify entity (2024.7+) doesn't pass `title` or `data:` through
the `command_template`, so use the raw-topic path below for anything richer.

### Rich alerts: `mqtt.publish`

For doorbells, motion events, or anything wanting an image / sound / tap-action, publish the
full JSON payload directly to the device's notify topic:

```yaml
action: mqtt.publish
data:
  topic: immortal/<device-id>/notify/set
  payload: |
    {
      "title": "Front door",
      "message": "Motion at 6:42pm",
      "image": "http://homeassistant.local:8123/local/snapshot.jpg",
      "sound": "http://homeassistant.local:8123/local/sounds/doorbell.mp3",
      "on_tap": "lovelace/security",
      "duration": 8
    }
```

All fields are optional. Full behavior rules in
[`docs/design/mqtt-notifications.md`](../design/mqtt-notifications.md).

### Without Home Assistant: `POST /notify`

The same payload reaches the same renderer over the fleet agent's HTTP API, so any script on
the LAN can raise a toast or speak a line with no broker in the picture:

```bash
./fleetctl notify "Backup finished" --speak "Backup finished" --device "Kitchen"
```

It is **off by default** — turn on *Accept notifications from your network* in
**Settings > Notifications** (the row appears once the fleet agent is enabled), or the route
answers `403 notify_disabled`.
See [Fleet management](fleet.md#pushing-a-notification).

### Payload fields

| Field         | Type   | Default    | Notes                                                                                                                          |
|---------------|--------|------------|--------------------------------------------------------------------------------------------------------------------------------|
| `title`       | string | `""`       | Bold line at the top of the toast. One line, ellipsizes if too long.                                                            |
| `message`     | string | `""`       | Body text below the title. Wraps to two lines, then ellipsizes.                                                                |
| `image`       | string | `null`     | `http(s)://` URL → fetched, or `data:image/...;base64,...` → decoded inline. Decode is downsampled to ≤512px to stay safe on Portal heap. |
| `sound`       | string | `null`     | `http(s)://` URL or local URI fed to `MediaPlayer`. Plays through `STREAM_ALARM` — see *Portal volume quirk* below.              |
| `position`    | enum   | `"bottom"` | `"top"` or `"bottom"`. Bottom matches Portal's own ephemeral-UI gravity. See note on top-overlap below.                          |
| `duration`    | int    | `6`        | Auto-dismiss timeout for the visual toast, in **seconds**. `0` = no auto-dismiss; toast stays until tapped. Sound has its own lifecycle. |
| `volume`      | float  | `1.0`      | Sound volume `0.0`–`1.0` of the alarm-stream max. Bounded by the user's system alarm-volume slider.                              |
| `wake_screen` | bool   | `true`     | If the screen is off when the notify arrives, wake it so the toast is visible. Set `false` for low-priority chimes that shouldn't wake a sleeping room. |
| `on_tap`      | string | `null`     | Tap target. URL, installed package name, or HA dashboard path. See *Tap targets* below.                                          |
| `speak`       | string | `null`     | Spoken aloud through the device's TTS voice (the one picked in Sounds settings). Independent of `message` — a payload with only `speak` says its line and renders no toast. Suppressed under Do Not Disturb, like `sound`. |

### Special cases

- **Empty payload** (`{}` or empty string): no-op. An automation that drops all
  template fields can't accidentally produce a "ghost" toast.
- **Sound-only**: `sound` present, both `title` and `message` empty → no visual,
  just audio. Useful for chimes that shouldn't change what's on screen.
- **Replace, don't stack**: a new toast arriving while one is showing replaces
  it. The previous sound keeps playing unless the new payload has its own `sound`.
- **DND audio gate**: when the system is in Do Not Disturb, sound is suppressed
  but the toast still renders. The visual is the polite signal that the device
  received the alert.
- **Acknowledgement-required**: `duration: 0` means the toast stays until the
  user taps it. Combine with `on_tap` so the tap also navigates.

### Tap targets (`on_tap`)

The `on_tap` field is what the toast does when tapped (in addition to
dismissing). The Portal's router accepts four forms by prefix:

| You write                                | Behavior                                                                                                  |
|------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `http://...` / `https://...`             | Opens in the default browser via `ACTION_VIEW`.                                                            |
| `homeassistant://...`                    | Opens directly in the HA companion app (HA's custom URI scheme).                                           |
| An installed package name, e.g. `com.android.chrome` | Launches that app's main activity.                                                                |
| Anything else (bare path)                | Treated as an HA dashboard path. Routed via `homeassistant://navigate/<path>` to the installed HA app.     |

For HA dashboards, all of these mean the same thing:

| Input                                                 | Goes to                                            |
|--------------------------------------------------------|----------------------------------------------------|
| `lovelace`                                             | Main HA dashboard.                                 |
| `lovelace/security`                                    | The *security* view inside the main dashboard.     |
| `lovelace-doorbell/0`                                  | First view of the custom `lovelace-doorbell` dashboard. |
| `/lovelace/security`                                   | Leading slash trimmed → same as above.             |
| `http://homeassistant.local:8123/lovelace/security`    | Host stripped → same as above.                     |
| `homeassistant://navigate/lovelace/security`           | Passed through verbatim.                           |

You can find a dashboard's slug at **Settings → Dashboards** (the *URL* column), and view slugs inside each dashboard's URL bar.

Requirements: the HA companion app must be installed (either
`io.homeassistant.companion.android` or the minimal F-Droid flavor, which is
what no-GMS Portals use), and the user must be logged in. If neither is true,
the tap is a no-op with a logcat warning.

!!! note "Position `top` overlaps the launcher header"
    The default `position: "bottom"` lands the toast safely below Immortal's home
    grid. `position: "top"` renders the toast in the same vertical band as the
    launcher's clock / photos / weather row and partially obscures it — fine
    when something needs the user's attention right where their eyes already
    are, but worth knowing if you're tempted to use top by default.

### Media hosting

The Portal fetches images and sounds anonymously over HTTP — no bearer token, no
session cookie. The simplest place to host them on Home Assistant is the auth-less
`/config/www/` directory, which serves at `http://homeassistant.local:8123/local/...`:

```
/config/www/sounds/doorbell.mp3       →  /local/sounds/doorbell.mp3
/config/www/snapshots/front-door.jpg  →  /local/snapshots/front-door.jpg
```

**Camera snapshots** with `/api/camera_proxy/...` URLs **won't work directly** — that path
needs a bearer token the Portal doesn't have. Either save the snapshot to `www/` first (use the
`camera.snapshot` service in your automation) or embed a long-lived access token in the URL.

#### Using HA media-source URIs

If you'd rather keep media in HA's structured media library (`/media/`, surfaced
under *Media* in the HA UI) than copy into `/config/www/`, resolve a signed URL
at automation time and pass that to the notify payload. Signed URLs expire
(default ~30 minutes), so the resolve must happen as part of the firing
automation — caching the URL won't work.

Wrap the resolve + publish into a reusable script so each automation is a single
call:

```yaml
# scripts.yaml
portal_notify:
  alias: "Portal: send rich notification"
  fields:
    topic:           { description: "MQTT notify topic, e.g. immortal/<device-id>/notify/set" }
    title:           { description: "Bold line" }
    message:         { description: "Body text" }
    sound_media:     { description: "media-source:// URI for the chime" }
    image_media:     { description: "media-source:// URI for the image (optional)" }
    on_tap:          { description: "HA dashboard path, URL, or package name (optional)" }
    duration:        { description: "Auto-dismiss seconds; 0 = stays until tapped", default: 6 }
  sequence:
    - variables:
        base_url: "http://homeassistant.local:8123"
        sound_url: ""
        image_url: ""
    - if: "{{ sound_media is defined and sound_media }}"
      then:
        - action: media_source.resolve_media
          data:
            media_content_id: "{{ sound_media }}"
          response_variable: sound_r
        - variables:
            sound_url: "{{ base_url }}{{ sound_r.url }}"
    - if: "{{ image_media is defined and image_media }}"
      then:
        - action: media_source.resolve_media
          data:
            media_content_id: "{{ image_media }}"
          response_variable: image_r
        - variables:
            image_url: "{{ base_url }}{{ image_r.url }}"
    - action: mqtt.publish
      data:
        topic: "{{ topic }}"
        payload: >-
          {
            "title": {{ (title | default('')) | tojson }},
            "message": {{ (message | default('')) | tojson }},
            "sound": {{ sound_url | tojson }},
            "image": {{ image_url | tojson }},
            {% if on_tap is defined and on_tap %}"on_tap": {{ on_tap | tojson }},{% endif %}
            "duration": {{ duration | default(6) }}
          }
```

Then automations call it with media-source URIs directly:

```yaml
action: script.portal_notify
data:
  topic: immortal/<device-id>/notify/set
  title: "Front door"
  message: "Motion at 6:42pm"
  sound_media: media-source://media_source/local/sounds/doorbell.mp3
  image_media: media-source://media_source/local/snapshots/front-door.jpg
  on_tap: lovelace/security
```

Edit `base_url` if your HA instance is reached at a different hostname. If you
only have one Portal, hardcode the `topic` inside the script and drop it from
the field list.

### Portal volume quirk

The Portal has a *single* "media volume" slider that drives almost every audio stream: music,
ring, notification, system. The only streams that are independent on Portal are **call** and
**alarm**. So notify sounds route through `STREAM_ALARM` — that's the only way to get a chime
that's loud-by-default and doesn't drift when you change Spotify's volume. The alarm slider
becomes your "notification volume" on this hardware; set it once to a level that's audible
from across the room and forget it. Do Not Disturb still silences notify sounds (Immortal
gates the audio on the system DND state before playback) — the visual toast still renders.

## Setup

It's a long-running, reboot-proof on-device foreground service that mirrors the
[fleet agent](fleet.md), and it's **off until you configure a broker**. An un-configured device
never opens a connection.

Configure it under **Immortal → Settings → Home Assistant (MQTT)**: turn on the toggle and enter
your broker **host** (default port `1883`) and, if your broker requires it, a **username and
password**. The Portal then appears automatically under **Settings → Devices** in Home Assistant —
no YAML. Its device name is shared with the fleet agent, so a Portal shows up under **one name**
everywhere, and a live status line tells you whether the connection is up.

!!! tip "Full walkthrough"
    See the [Home Assistant & MQTT setup guide](../guides/home-assistant.md) for prerequisites
    (Mosquitto add-on, MQTT integration), an example automation, and troubleshooting.
