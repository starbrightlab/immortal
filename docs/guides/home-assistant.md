# Home Assistant & MQTT setup

Immortal can expose a Portal to [Home Assistant](https://www.home-assistant.io/) over **MQTT
Discovery**, so the device appears automatically — no YAML. For the overview, see
[Smart home (Home Assistant / MQTT)](../features/smart-home.md).

## What you get in Home Assistant

Immortal publishes the state it already tracks, and accepts the one command this hardware can
honour:

| Entity | Direction | Notes |
| --- | --- | --- |
| Presence | state | Inferred from the Portal's dream/sleep lifecycle (see [limitations](../limitations.md)). |
| Screen on/off | **state + control** | Wake or sleep the display from an automation (uses the screen-off device admin). |
| Now-playing media | state | The device's current track — see [now-playing](../features/multi-room-audio.md). |
| Battery | state | On models that have one (Portal Go). |

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

!!! info "On the roadmap: deeper MQTT security"
    Today the publisher supports broker **username/password** auth. Additional MQTT security options
    are **in testing** and not yet in a released build — this page will be updated when they ship.

## Troubleshooting

- **Portal doesn't appear in HA** — confirm the MQTT integration is set up and the broker
  host/port/login on the Portal are correct; watch the status line for a connection error.
- **Screen control does nothing** — the screen-off **device admin** must be granted (the
  [provisioning kit](../provisioning.md) does this; you can also enable it in Immortal settings).
