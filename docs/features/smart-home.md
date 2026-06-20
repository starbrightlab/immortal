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

The one command Immortal can honour on this hardware is **screen on/off** (`ScreenControl`,
which uses the screen-off device-admin granted during [provisioning](../provisioning.md)). So
from Home Assistant you can, for example, wake or sleep a Portal's display as part of an
automation.

## Setup

It's a long-running, reboot-proof on-device foreground service that mirrors the
[fleet agent](fleet.md), and it's **off until you configure a broker**. An un-configured device
never opens a connection.

Configure it under **Immortal → Settings** with your MQTT broker host (default port `1883`). The
Home Assistant device name is shared with the fleet agent, so a Portal shows up under **one name**
everywhere. A live status string on the settings screen tells you whether the connection is up.
