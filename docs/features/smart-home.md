# Smart home (Home Assistant / MQTT)

`MqttService` / `MqttPublisher` — Immortal can expose a Portal to
[Home Assistant](https://www.home-assistant.io/) over **MQTT Discovery**, so the device shows up
as something you can see and control.

## What it exposes

The publisher reuses the state Immortal already holds and surfaces it to Home Assistant:

- **Presence** and **screen** state (`PresenceHub`).
- **Now-playing** media (`NowPlayingHub`) — see [Multi-room audio](multi-room-audio.md).
- **Battery** (on models that have one).

## What it can control

- **Screen on/off** (`ScreenControl`, which uses the screen-off device-admin granted during
  [provisioning](../provisioning.md)) — wake or sleep a Portal's display as part of an automation.
- **Open** a URL, an installed package, or a Home Assistant dashboard path on the Portal — the
  same string grammar the screensaver picker accepts.
- **Notifications** — push a toast (with optional image, sound, and a tap target) from any
  Home Assistant automation. See below.

## Notifications

Immortal renders a Portal-native bottom toast in response to MQTT-driven notify messages from
Home Assistant. Two ways to fire one — pick whichever fits the automation:

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

All fields are optional. Schema and behavior rules in
[`docs/design/mqtt-notifications.md`](../design/mqtt-notifications.md).

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
