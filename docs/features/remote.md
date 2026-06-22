# Phone remote

Use a phone or tablet on the same Wi-Fi as a **remote control** for a Portal — navigation
buttons and an app launcher in a web page, with nothing to install on the phone. It rides on
the same always-on [fleet agent](fleet.md) that already manages the device over the network.

## What it does (Phase 1)

- **Navigation buttons** — Back, Home, Recents, Power dialog.
- **App launcher grid** — every launchable app on the Portal, tap to open.

Back/Home/Power go through the device's accessibility layer (see *How input works* below) and work
across all apps. **Recents** opens Immortal's own app switcher — the Portal has no system
overview, so the standard recents action is a no-op there; the in-app switcher is the working
equivalent. (Notifications and Quick settings are deliberately omitted: Meta's Portal SystemUI
ships no notification shade or quick-settings panel, so those actions do nothing.)

## Turning it on

On the Portal: **Settings → Remote → Control from your phone**. That enables the remote and shows
a **pairing screen** with a QR code and a 6-digit PIN. The remote is **off by default**.

On the phone: scan the QR (it opens the remote page and pairs automatically) or browse to the
address shown and type the PIN. Once paired, the phone keeps a session token and reconnects on
its own — pairing survives a Portal reboot.

## Security

- The remote is served by the fleet agent, which only accepts **LAN/loopback** peers.
- The page and PIN exchange are open on the LAN, but driving any input requires a **paired
  session token**. Pairing requires the **PIN shown on the Portal's screen**, so only someone in
  the room can pair. (The fleet bearer token also works, so the laptop CLI can drive it.)
- App icons are served unauthenticated (they aren't secrets), which keeps the token out of image
  URLs. Everything that reads the app list or sends input is authenticated.

## How input works (and its limits)

The Portals are non-root Android 9/10 and Immortal holds no `INJECT_EVENTS` permission, so raw
D-pad/key events **cannot** be injected into other apps. Instead the remote routes through an
`AccessibilityService` (`BarWatchService`), which turning the remote on enables automatically
(via `WRITE_SECURE_SETTINGS` — the same service the quick-button cluster uses):

- **Global actions** need no extra permission. Verified on a PortalGo, **Back / Home / Power**
  work; **Recents / Notifications / Quick settings** are accepted by the framework but no-op
  (Meta's Portal SystemUI has no overview, shade, or QS). So the remote exposes Back/Home/Power
  as global actions and routes **Recents** to the in-app app switcher instead.
- A live **screen mirror** isn't practical on these models (the accessibility screenshot API is
  Android 11+; `MediaProjection` needs a per-session consent dialog), so the remote follows the
  *TV-remote* model: you look at the TV, not the phone.

### Roadmap

Later phases extend the same `/remote/*` routes:

- **Phase 2** — on-screen keyboard (text entry into focused fields) and directional focus
  navigation for standard UIs.
- **Phase 3** — a gesture **touchpad** (tap/swipe anywhere, since the Portal is a touchscreen
  underneath) and **user-definable presets** (named macros that combine input + config pushes).
- **Phase 4** — drive **any Portal on the fleet** from one remote (multi-room).

## API

All under the agent's port (default `8723`). `/remote/ui` and `/remote/pair` are open on the LAN;
the rest require `Authorization: Bearer <session-or-fleet-token>`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/remote/ui` | The remote web page |
| `POST` | `/remote/pair` | `{"pin":"123456"}` → `{ok, token}` |
| `GET` | `/remote/apps` | Launchable apps `[{label, packageName}]` |
| `GET` | `/remote/icon?pkg=…` | App icon (PNG) |
| `POST` | `/remote/key` | `{"action":"…"}` — `back`/`home`/`power` (global action) or `apps` (in-app switcher) |
| `POST` | `/remote/launch` | `{"packageName":"…"}` → open an app |
