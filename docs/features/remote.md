# Phone remote

Use a phone or tablet on the same Wi-Fi as a **remote control** for a Portal — navigation
buttons and an app launcher in a web page, with nothing to install on the phone. It rides on
the same always-on [fleet agent](fleet.md) that already manages the device over the network.

## What it does

- **Navigation buttons** — Back, Home, Recents, Power dialog.
- **Touchpad** — a trackpad on the phone moves a pointer drawn on the TV; tap to click, two-finger
  drag to scroll. Works in **any** app (the Portal is a touchscreen, so a synthesized touch lands
  anywhere) — this is the universal navigation primitive.
- **Keyboard** — type on the phone; text lands in the focused field on the Portal (set / append /
  backspace / clear), no on-Portal typing.
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

The Portals are non-root Android 9/10. Raw key events (a real D-pad) **cannot** be injected: the
OS won't grant a normal app `INJECT_EVENTS` at all — it's a signature permission with no
`development` flag, so even `pm grant` refuses it ("not a changeable permission type"). Shizuku can
inject (its helper runs as the shell uid, which holds the permission) but that helper is started by
adb and dies on reboot, so it can't be the basis of a reboot-proof feature. Everything below
instead routes through an `AccessibilityService` (`BarWatchService`), which the remote enables
automatically (via `WRITE_SECURE_SETTINGS`) and which comes back on its own after a reboot:

- **Global actions** need no extra permission. Verified on a PortalGo, **Back / Home / Power**
  work; **Recents / Notifications / Quick settings** are accepted by the framework but no-op
  (Meta's Portal SystemUI has no overview, shade, or QS). So the remote exposes Back/Home/Power
  as global actions and routes **Recents** to the in-app app switcher instead.
- **Touchpad** is `dispatchGesture` (needs `canPerformGestures`, declared on the service) — a
  synthesized tap/swipe at the pointer's screen coordinates. Because the Portal is a touchscreen
  this drives *any* app, and it's reboot-proof. The pointer itself is drawn on the TV via a
  `TYPE_ACCESSIBILITY_OVERLAY` (the same overlay class the quick-button cluster uses). This is the
  reboot-proof replacement for a hardware D-pad: you point at what you want instead of stepping a
  focus highlight. (A focus-based D-pad via `focusSearch`/`ACTION_FOCUS` was tried and dropped — it
  no-ops on the Portal's Compose/custom UIs.)
- **Text** is set directly on the focused editable node (`ACTION_SET_TEXT`) — no IME swap. It works
  wherever an editable field has input focus; `/remote/text` reports whether it applied so the UI
  can hint "focus a field first".
- A live **screen mirror** isn't practical on these models (the accessibility screenshot API is
  Android 11+; `MediaProjection` needs a per-session consent dialog), so the remote follows the
  *TV-remote* model: you look at the TV, not the phone.

### Roadmap

Later phases extend the same `/remote/*` routes:

- **Native D-pad for Immortal's own UI** — within Immortal's screens we own the Compose focus, so a
  true reboot-proof D-pad (no injection) can drive the launcher/settings/screensaver; it won't
  extend to third-party apps (use the touchpad there).
- **User-definable presets** — named macros that combine input + config pushes.
- **Multi-room** — drive any Portal on the fleet from one remote.

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
| `POST` | `/remote/text` | `{"mode":"set\|append\|backspace\|clear","text":"…"}` → edit the focused field |
| `POST` | `/remote/cursor` | `{"dx":12.0,"dy":-4.0}` → move the on-TV pointer (relative px) |
| `POST` | `/remote/tap` | tap at the pointer (synthesized touch) |
| `POST` | `/remote/swipe` | `{"dx":0.0,"dy":-300.0}` → scroll/swipe from the pointer |
