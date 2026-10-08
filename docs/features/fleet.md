# Fleet management

`FleetAgentService` — an optional always-on WiFi service for managing a Portal over the network,
without reaching for a USB cable each time.

## What it's for

Run a few Portals around the house and you don't want to plug each one in to make a change. The
fleet agent lets a laptop tool talk to a Portal over WiFi to:

- **deploy and update apps**
- **push config**
- **browse files**
- **read logcat**
- **push a notification** (opt-in — see below)

## Why an in-app service (not adb-over-WiFi)

adb-over-WiFi can't auto-survive a reboot on these non-root Android 9/10 Portals — the TCP port
is a root-only system property (see [Hardware limitations](../limitations.md)). An app foreground
service comes straight back: `ensureRunning` is called from app start and the boot receiver, the
same hooks that re-assert the screensaver, so the agent is reachable again after a power-cycle
with **no USB and no root**.

## Security

The agent exposes an HTTP API (`FleetHttpServer` / `FleetRoutes`). Every request must carry
`Authorization: Bearer <token>` — a per-device token from `FleetConfig`; anything else gets a
`401` before any work happens. The routes are a pure **consumer** of existing app subsystems
(catalog, installer, settings); they add no install or catalog logic of their own.

!!! warning "The per-device token is a secret"
    Provisioning records each device (name, IP, agent token) to a host-side inventory under
    `provisioning/fleet/`, which is **git-ignored** — never commit it (the repo is public).

## Enabling it

Off by default — an un-provisioned device never opens a port. Enable it per device:

```bash
./provision.sh --fleet        # provision.ps1 -Fleet on Windows
```

You're prompted for a friendly name (e.g. "Living Room Left") unless you preset `FLEET_NAME`.
After a reboot the agent comes back on its own — nothing to re-arm. The default agent port is
`8723`.

## Pushing a notification

`POST /notify` shows a message on the Portal — a toast, a sound, a spoken line, or any
combination. The body is the same schema Home Assistant publishes over MQTT (see
[Smart home > Notifications](smart-home.md#notifications)), rendered by the same code, so an
HTTP caller and an automation behave identically. **No MQTT broker is needed for this path.**

It is **off by default.** Turn on *Accept notifications from your network* in
**Settings > Notifications** on the Portal — or the same row under *Device* in the phone
remote (both appear once the fleet agent is enabled). Until then the route answers
`403 notify_disabled`. Holding the agent token is enough to manage a device, but making it
speak in someone's living room is a separate thing to say yes to.

```bash
./fleetctl notify "Backup finished" --device "Kitchen"
./fleetctl notify --speak "The washing machine is done" --device all   # no toast, just TTS
./fleetctl notify "Front door" --title "Motion" --sound http://nas.local/ding.mp3 --duration 8
```

Or straight over HTTP:

```bash
curl -X POST http://<portal-ip>:8723/notify \
  -H "Authorization: Bearer <token>" \
  -d '{"title":"Deploy","message":"Build 1.70 is live","speak":"Deploy finished"}'
```

Response reports what actually happened: `{"ok":true,"shown":true,"sound":false,"spoke":true}`.

### Every field at once

A doorbell-shaped example using the whole schema — screen wakes, a card with a camera frame
appears and stays until someone taps it, a chime plays, and the Portal says the line aloud:

```bash
curl -X POST http://<portal-ip>:8723/notify \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "title":       "Front door",
    "message":     "Motion detected at 18:42",
    "image":       "http://homeassistant.local:8123/api/camera_proxy/camera.front_door",
    "sound":       "/sdcard/doorbell.mp3",
    "speak":       "Someone is at the front door",
    "position":    "bottom",
    "duration":    0,
    "volume":      0.7,
    "wake_screen": true,
    "on_tap":      "lovelace/security"
  }'
```

Three things that bite in practice:

- **`image` takes an `http(s)://` URL or a `data:image/...;base64,...` URI — not a local path.**
  `sound` goes through `MediaPlayer`, which happily opens `/sdcard/doorbell.mp3`; `image` goes
  through `java.net.URL`, which rejects a bare path. Inline the bytes or serve them.
- **`duration: 0` never auto-dismisses.** Only a tap clears it. It survives navigating the UI —
  the toast is an accessibility overlay, so it draws over the launcher, settings, and any app.
- **A new notification replaces the current one** rather than queueing behind it, so a sticky
  toast can't wedge the channel shut.

Anything audible — `sound` and `speak` alike — is suppressed while Do Not Disturb is on. The
toast still renders, which doubles as the signal that the payload arrived.
