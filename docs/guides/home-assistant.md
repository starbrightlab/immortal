# Home Assistant & MQTT setup

Immortal can expose a Portal to [Home Assistant](https://www.home-assistant.io/) over **MQTT
Discovery**, so the device appears automatically — no YAML. For the overview, see
[Smart home (Home Assistant / MQTT)](../features/smart-home.md).

## What you get in Home Assistant

The Portal arrives as one device with everything below. Entities that depend on hardware only
appear where the hardware exists.

**Sensors**

| Entity | Notes |
| --- | --- |
| Presence | Meta's own camera detection, read off the system log — see [presence](../features/smart-home.md#presence). Falls back to the screensaver-lifecycle proxy; the `source` attribute says which. |
| Screen state | `off` / `dreaming` / `interactive`. |
| Temperature | Ambient temperature, with a calibration offset. Only on models with the sensor. |
| Ambient light | Illuminance in lux. Only on models with the sensor. |
| Humidity, Pressure | Only on models with the sensor. |
| Media, Media title, Media artist | The current track — see [now-playing](../features/multi-room-audio.md). |
| Battery, Charging | On models that have a battery (Portal Go). |
| IP address | Diagnostic. |
| Stream URL | Where the live video is served, for a dashboard card. Diagnostic. Only when the camera is switched on. |

**Controls**

| Entity | Notes |
| --- | --- |
| Screen | Wake or sleep the display (uses the screen-off device admin). |
| Play / pause, Next track, Previous track | Transport for whatever is playing. |
| Media volume, Speaker mute, Volume up / down | Hidden on Portal TV, where volume changes are inaudible. |
| Microphone mute | Follows the device: muting from anywhere on the Portal updates the switch. |
| Home, Screensaver | Go to the launcher, or show the photo frame. |
| Open | Send the Portal to a URL, an installed app, or a Home Assistant dashboard path. |
| Notify | Push a toast with optional image, sound and tap target — see [notifications](../features/smart-home.md#notifications). |
| Identify | Pop a toast naming the device, for finding which Portal is which. |
| Camera streaming | Start/stop live RTSP video — see [the camera](../features/smart-home.md#camera). |
| Camera audio | Include sound in the stream. Silenced entirely while the microphone is muted. |

The Portal registers with a stable per-device id, so it survives broker reinstalls but stays unique
across a fleet. Its **device name is shared with the [fleet agent](../features/fleet.md)**, so a
Portal shows up under one name everywhere.

## Prerequisites

- A running **MQTT broker**. In Home Assistant, the easiest is the **Mosquitto broker** add-on.
- The **MQTT integration** enabled in Home Assistant.

New to MQTT? See [home-assistant.io/integrations/mqtt](https://www.home-assistant.io/integrations/mqtt/).

## Steps

1. **In Home Assistant**, add the **Mosquitto broker** add-on (Settings → Add-ons) and enable the
   **MQTT** integration. Create a broker user/password if you don't have one.
2. **On the Portal**, go to **Immortal → Settings → Home Assistant (MQTT)**, turn on the toggle, and
   enter:

    | Field | Value |
    | --- | --- |
    | Host | Your broker's address (e.g. the Home Assistant host) |
    | Port | `1883` by default |
    | Username / Password | Your broker login, if it requires one |

3. The Portal appears automatically under **Settings → Devices** in Home Assistant as a new MQTT
   device. No YAML needed.

A live status line on the settings screen tells you whether the connection is up. The publisher
stays idle until a broker host is set, so an un-configured device never opens a connection.

## Example automation

Sleep the kitchen Portal's screen at night:

```yaml
automation:
  - alias: "Kitchen Portal screen off at 23:00"
    triggers:
      - trigger: time
        at: "23:00:00"
    actions:
      - action: switch.turn_off
        target:
          entity_id: switch.kitchen_portal_screen
```

(Entity names follow your Portal's device name; check **Settings → Devices** for the exact ids.)

!!! info "TLS"
    As well as broker username/password, the publisher can wrap the connection in **TLS** (turn on
    *Use TLS* and set the port, conventionally `8883`). Certificate and hostname validation is on by
    default; turn it off only for a self-signed broker on a network you trust.

## Troubleshooting

- **Portal doesn't appear in HA** — confirm the MQTT integration is set up and the broker
  host/port/login on the Portal are correct; watch the status line for a connection error.
- **Screen control does nothing** — the screen-off **device admin** must be granted (the
  [provisioning kit](../provisioning.md) does this; you can also enable it in Immortal settings).
- **Presence says `proxy`, not `portal`** — check **Use the Portal's own detector** is on under
  Settings → Immortal, then the `READ_LOGS` permission. The provisioning kit grants it, so re-run
  the provisioner on a Portal set up before that grant existed.
- **No camera entities** — the camera is off by default. Turn it on under Settings →
  Home Assistant (MQTT) on the device; Home Assistant can't enable it remotely, by design.
- **The stream stops when you open an app on the Portal** — expected, and not fixable: Android 9
  gives the camera to the foreground app only. Immortal takes it back within a few seconds of
  returning to the launcher, logging `camera taken by another app` and then `camera back —
  streaming resumed`. This is also why you can't watch the stream *on* the Portal producing it.
- **Camera streaming won't stay on** — check `adb logcat -s ImmortalStream:V`. A
  `MediaCodec ... configure` failure means the encoder refused our settings; Immortal retries
  without the profile hint, so this should now start anyway. `no record-audio permission` means
  sound is ungranted: re-run the [provisioning kit](../provisioning.md), or turn **Camera sound**
  on from the device, which asks for it.
- **Testing the stream** — do it from another machine, never on the Portal itself. Home
  Assistant's **Stream URL** or **IP address** sensor has the address, or
  `adb shell ip addr show wlan0`; then open `rtsp://<portal-ip>:8554/` in VLC.
- **No picture at all** — check the camera permission is granted: re-run the
  [provisioning kit](../provisioning.md), or
  `adb shell pm grant com.immortal.launcher android.permission.CAMERA`.
- **No temperature entity** — not every Portal has an ambient temperature sensor. Entities are only
  advertised for hardware the device actually reports, so a missing one means the sensor isn't
  there.
