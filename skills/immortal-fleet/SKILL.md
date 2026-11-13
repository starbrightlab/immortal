---
name: immortal-fleet
description: >-
  Manage a fleet of Immortal-flashed Meta Portal devices over WiFi with the
  `fleetctl` CLI (provisioning/fleetctl). Use when asked to deploy, install, or
  update apps on Portals; push or read Immortal config; manage the screensaver
  or calendar widget over the air; iterate on a local Immortal build (dev mode /
  dev update); browse device files (ls/cat/push/pull); read logcat or
  diagnostics; or run any Fleet Agent HTTP endpoint across one or many Portals.
  Triggers on: "fleetctl", "fleet agent", "manage the Portals", "push to the
  Portal over WiFi", "install on all Portals", "update the fleet", "dev update",
  "screensaver over the air", "calendar widget on the Portal".
---

# Immortal Fleet management (`fleetctl`)

`fleetctl` is a fast, zero-dependency CLI (single-file Rust, std-lib only) that drives the
in-app **Fleet Agent** HTTP API of Immortal-flashed Meta Portals over WiFi. It is the
persistent management channel that survives reboots — unlike adb-over-WiFi, which can't on
these non-root Android 9/10 devices. Use it to manage a wall of Portals without a USB cable
per device.

- **Source:** `provisioning/fleet.rs` (std-lib only, no crates)
- **Binary:** `provisioning/fleetctl`
- **Agent docs:** `docs/features/fleet.md`, `provisioning/README.md` (Fleet sections)

## Build / locate the binary

A prebuilt `provisioning/fleetctl` usually exists. If missing or stale, rebuild from
`provisioning/`:

```bash
rustc -O fleet.rs -o fleetctl     # no Cargo needed
# or: cargo build --release       # -> target/release/fleetctl
```

Run it from `provisioning/` (so it finds the `fleet/` registry by default):

```bash
cd provisioning && ./fleetctl <command> [--device NAME|serial|all] [options]
```

## Device targeting (read this first)

Targets come from the device registry: `fleet/<serial>.json` files that
`./provision.sh --fleet` records for each provisioned Portal — `{serial, name, model, ip,
agentPort, token}`.

- `--device NAME` — match by friendly name (case-insensitive) or serial.
- `--device all` — fan out to every registered device (output is sectioned per device).
- *(omit `--device`)* — a single registered device is used automatically; with multiple
  devices the CLI errors and lists known names.
- **Not in the registry?** Skip it entirely with `--host <ip> --token <token> [--port 8723]`.
- Point at a different registry folder with `$IMMORTAL_FLEET_DIR`.

**Auth & secrets:** every request carries `Authorization: Bearer <token>`; a wrong/absent
token gets `401`. The per-device token lives in `fleet/<serial>.json`, which is **git-ignored
and a secret** (the repo is public). Never print token values or commit that folder.

**Exit codes:** `0` success · `1` HTTP/transport error (non-2xx or device down) · `2` usage
error. Default agent port is `8723`.

## Commands

Read-only / inventory:

```bash
./fleetctl devices                       # list devices in the local registry
./fleetctl info   --device all           # identity, version, install/presence state
./fleetctl apps   --device "Living Room" # catalog apps + what's installed
./fleetctl diag   --device all           # diagnostics snapshot
```

App install / update:

```bash
./fleetctl install org.videolan.vlc --device all          # install a catalog package
./fleetctl install com.example.app --apk-url https://…/app.apk   # or a direct APK URL
./fleetctl update --check                # dry-run: which apps have updates
./fleetctl update --all  --device all    # update everything
./fleetctl update org.videolan.vlc       # update one package
```

Config (Immortal's free-form agent config):

```bash
./fleetctl config                        # read current config
./fleetctl config --name "Living Room Left"
./fleetctl config --set key=value --set other=value   # repeatable
```

Screensaver photo frame (GET reads; `set` pushes a partial update — only fields you pass
change). Display changes apply on the next screensaver cycle; `--enabled` and the overnight
window apply immediately:

```bash
./fleetctl screensaver get --device "Living Room"
./fleetctl screensaver set --enabled true --fit fill --interval 45 --shuffle true --device all
./fleetctl screensaver set --album-url 'https://www.icloud.com/sharedalbum/#…' --device all
./fleetctl screensaver set --overnight true --overnight-start 22:00 --overnight-end 07:00 --device all
```

`screensaver set` options: `--enabled BOOL` `--source default` `--folder PATH`
`--album-url URL` `--album-refresh MIN` `--fit fill|fit` `--interval SEC` `--shuffle BOOL`
`--videos BOOL` `--now-playing BOOL` `--battery-saver BOOL` `--presence always_on|presence`
`--idle-min N` `--overnight BOOL` `--overnight-start HH:MM` `--overnight-end HH:MM`.

Calendar widget (its own endpoint; applies live on next refresh):

```bash
./fleetctl calendar get --device "Living Room"
./fleetctl calendar set --url 'https://…/basic.ics' --range week --device all
./fleetctl calendar set --range agenda            # re-range without resending the link
./fleetctl calendar enable|disable --device …     # toggle the widget, keep the link
./fleetctl calendar off --device "Living Room"    # clear the link entirely
```

`--url` = Google "secret address in iCal format" or Apple iCloud public / `webcal://` link.
`--range day|3day|week|agenda` · `--size small|medium|large` · `--side left|right`.

Files / logs:

```bash
./fleetctl ls   /sdcard/Android/data/com.immortal.launcher/files
./fleetctl cat  /sdcard/some.txt
./fleetctl push ./frame.jpg /sdcard/Android/data/com.immortal.launcher/files/frame.jpg
./fleetctl pull /sdcard/some.log ./some.log        # local "-" or omit = stdout
./fleetctl logcat --lines 200 --device Kitchen
```

`pull` to a local file path targets one device; with `--device all` write to stdout (`-`).

Device actions:

```bash
./fleetctl action reaffirm --device all   # re-assert launcher/screensaver ownership
./fleetctl action identify --device …     # flash a banner to find the physical device
./fleetctl action reboot   --device …
```

Push a notification (toast / sound / spoken line). **Opt-in per device:** the owner must
turn on *Accept notifications from your network* in Settings > Notifications, else the route answers
`403 notify_disabled`. Same payload schema as the MQTT notify topic, no broker involved:

```bash
./fleetctl notify "Backup finished" --device "Kitchen"
./fleetctl notify --speak "The washing machine is done" --device all   # TTS only, no toast
./fleetctl notify "Front door" --title "Motion" --duration 8 --on-tap lovelace/security
```

Options: `--title TEXT` `--speak TEXT` `--image URL` `--sound URL` `--on-tap TARGET`
`--duration SEC` (0 = until tapped) `--volume 0.0-1.0` `--position top|bottom` `--no-wake`.
The positional argument is the toast body; `--speak` is read aloud through the device's TTS
voice. The reply says what happened: `{"shown":…,"sound":…,"spoke":…}`.

Escape hatch — call any agent endpoint directly (keeps working as the API grows):

```bash
./fleetctl raw GET  /info
./fleetctl raw POST /calendar '{"range":"agenda"}'   # body must be valid JSON
```

## Dev mode — iterate on Immortal itself over WiFi

When hacking on Immortal, avoid cutting a GitHub release per change and stop the official
self-updater (`UpdateManager`) from clobbering your test build:

```bash
./fleetctl dev status --device "Living Room"   # dev mode + installed version
./fleetctl dev on     --device "Living Room"   # pause the official self-updater
./fleetctl dev update ./app/build/outputs/apk/release/app-release.apk --device "Living Room"
./fleetctl dev off    --device "Living Room"   # resume official updates
```

`dev update` enables dev mode first (unless `--no-pause`), pushes the local APK, and installs
it over Immortal via the same silent path the store uses. `--package PKG` / `--path REMOTE`
override the defaults (`com.immortal.launcher` and a temp path in the app's files dir).

**CRITICAL — sign with the same key.** An in-place update is signature-checked by Android.
The local build *must* be signed with the **same key** as the installed Immortal, or the
install is rejected. Switching a Portal onto a differently-keyed build requires an uninstall
(which wipes app data) — snapshot first with `provisioning/fleet-backup.sh` and restore with
`fleet-restore.sh`.

## Scope — what the CLI can and can't touch

The agent runs as the *app* user, so `fleetctl` reaches everything the agent exposes:
install/update apps, read/write shared and app-external storage (`push`/`pull`/`ls`/`cat`),
Immortal's config and screensaver/calendar, logcat, diagnostics. It is **not** a full adb
shell — it cannot read other apps' private data dirs or run `pm` / `appops` / `settings`
directly. Those need the shell user (USB adb or the provisioning kit). The token can only
install known catalog apps unless dev mode is on (`403 dev_mode_required` otherwise).

## Quick recipes

- **Inventory the whole fleet:** `./fleetctl info --device all` then `./fleetctl apps --device all`.
- **Roll an app to every Portal:** `./fleetctl install <pkg> --device all`.
- **See pending updates, then apply:** `./fleetctl update --check` → `./fleetctl update --all --device all`.
- **Point a wall of frames at one calendar:** `./fleetctl calendar set --url '<ics>' --range week --device all`.
- **Test a local build on one device:** `./fleetctl dev update <app.apk> --device "<name>"` (same signing key!).
- **A device looks down:** check it's on WiFi and registered (`./fleetctl devices`); transport errors exit `1`.
