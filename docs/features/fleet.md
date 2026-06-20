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
