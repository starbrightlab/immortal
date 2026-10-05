# Portal Provisioner

A one-double-click setup tool that turns a connected Meta Portal into a custom device:
installs your client app, replaces the home screen and screensaver, pre-grants permissions,
and enables installing other apps directly on the device. A matching restore tool puts
everything back.

The owner never touches a terminal — they plug in a USB-C cable, accept one prompt on the
Portal, and double-click.

> **New here?** The full walkthrough — with the prompts you'll see, how to verify it worked,
> screenshots, and troubleshooting — is at <https://starbrightlab.github.io/immortal/provisioning/>.
> This file is the same steps for when you only have the downloaded folder in front of you.

## For the end user

1. **Enable ADB on the Portal** (one time): Settings > Debug > **ADB Enabled**.
2. **Connect** the Portal to the computer with a USB-C cable.
3. **Double-click**:
   - macOS: `Provision-Portal.command`
   - Windows: `Provision-Portal.bat`
4. When the Portal shows **"Allow USB debugging?"**, tap **Allow** (check "Always allow").
5. Wait for "Done." To undo: double-click `Restore-Portal` (`.command` / `.bat`).

> macOS may warn that the file is from an unidentified developer. Right-click → Open the first
> time, or remove the quarantine flag: `xattr -d com.apple.quarantine Provision-Portal.command`.

> **Windows: "unblock" the downloaded files first.** Windows marks files downloaded from the
> internet as blocked, which makes the PowerShell script error out. After extracting the release,
> right-click the provisioning folder (or the individual files inside it) → **Properties** → check
> **Unblock** at the bottom → OK. Or do it in one PowerShell command from inside the folder:
> `Get-ChildItem -Recurse | Unblock-File`. (Thanks to a community member on Reddit for the tip.)

No Android tools required — if `adb` isn't found, the script downloads Google's official
platform-tools automatically into this folder.

## What it does (and how to change it)

Steps, in order: install client APK → push photos → grant permissions → enable on-device
installs → freeze OS updates → set launcher → set screensaver. Each step is toggleable in
`config.env`:

| Key | Meaning |
|---|---|
| `PKG`, `HOME_ACTIVITY`, `DREAM_SERVICE` | Your client app's package and components |
| `SET_LAUNCHER`, `SET_SCREENSAVER`, `DISABLE_VERIFIER`, `DISABLE_OTA` | `true`/`false` per step |
| `PERMISSIONS` | Runtime permissions to pre-grant |
| `PREINSTALL_FDROID`, `PREINSTALL_APKS` | Apps to pre-install during setup (see below) |
| `APK_GLOB` | Which APK to install (drop yours in `apks/`) |
| `ASSET_DIR` | Photos pushed to the frame (first becomes `frame.jpg`) |
| `RESTORE_ALEXA`, `INSTALL_ALEXA_WAKE_WORD` | First-gen Alexa restore and the separate opt-in wake-word helper |

To ship your own app instead of the sample, replace `apks/app-debug.apk`, drop photos in
`assets/`, and update `PKG`/`HOME_ACTIVITY`/`DREAM_SERVICE` in `config.env`.

### On-device installs

For Immortal's App Store and self-update to install apps on the Portal at all,
provisioning **disables Meta's package verifier** (`com.facebook.appverifier`),
which otherwise blocks non-allowlisted installs, and **grants Immortal the
install-source permission** (`REQUEST_INSTALL_PACKAGES`) over ADB — the Portal's
on-device "install unknown apps" toggle is non-functional, so it can't be enabled
by hand. Installs then go through Android's standard `PackageInstaller`: the store
hands the APK to the system installer, which shows its confirm dialog.

On the **Gen-1 Portal+** (Android 9) that confirm dialog is broken out of the box —
a Meta Runtime Resource Overlay (`com.facebook.aloha.rro.niu.android`) re-themes it
white-on-white, so the text and buttons are invisible. Provisioning **disables that
overlay**, making the dialog readable again — and unlike a running helper, the change
persists across reboots (the framework stores overlay state in
`/data/system/overlays.xml`). It's applied with `cmd overlay disable` (immediate, no
reboot), gated to API < 29, and reversed by `--restore`. Newer Portals (API ≥ 29) have
a working dialog and ship no such overlay, so this step is skipped on them.

```bash
./provision.sh --overlay-fix       # macOS/Linux (apply the Gen-1 dialog fix on its own)
# powershell ... provision.ps1 -OverlayFix   # Windows
```

Prefer to leave the stock dialog alone? Set `DISABLE_INSTALLER_OVERLAY=false` in
`config.env` — but then the Gen-1 confirm dialog stays white-on-white and on-device
installs there won't be usable.

### Pre-installing apps

`PREINSTALL_FDROID` / `PREINSTALL_APKS` also install apps during setup via a
silent `adb install`, so every freshly provisioned Portal has a few useful apps
out of the box. You can run just this step later:

```bash
./provision.sh --apps          # macOS/Linux
# powershell ... provision.ps1 -Apps   # Windows
```

F-Droid entries are `id` or `id:versionCode` (pin the arm64 build for multi-ABI
apps like VLC). `PREINSTALL_APKS` are direct APK URLs for your own apps.

### WiFi fleet management

`ENABLE_FLEET=true` turns on Immortal's in-app **Fleet Agent** during setup: a
small foreground service the device runs so you can deploy/update apps, push
config, browse files, and read logcat **over WiFi**, from the
[portal-explorer](../../portal-explorer) "Fleet" view — no USB cable per device.
Because it's an app service it **survives a reboot**, which adb-over-WiFi can't
here (the TCP port is a root-only system property on these non-root Portals).
Provisioning records this device — name, IP, and the agent's auth token — to
`fleet/<serial>.json` for the laptop tool. (That folder holds secrets and is
gitignored.)

```bash
./provision.sh --fleet          # macOS/Linux: name + enable the agent, record the device
# powershell ... provision.ps1 -Fleet   # Windows
```

You're prompted for a friendly name (e.g. "Living Room Left") unless you preset
`FLEET_NAME`. After a reboot the agent comes back on its own — nothing to re-arm.

#### Calendar widget over the air

The screensaver's calendar widget is managed through the agent too, so you can
point a whole wall of frames at a calendar without touching each one. The agent
exposes a `/calendar` endpoint (bearer-token auth, like every fleet route):

```bash
TOKEN=$(jq -r .token fleet/<serial>.json)   # recorded by --fleet
DEV=http://<device-ip>:8723

# Read the current calendar config
curl -s -H "Authorization: Bearer $TOKEN" $DEV/calendar

# Push a public iCalendar (.ics) feed link and a display range
curl -s -X POST -H "Authorization: Bearer $TOKEN" $DEV/calendar \
  -d '{"url":"https://calendar.google.com/calendar/ical/…/basic.ics","range":"week"}'

# Turn the widget off again (clear the link)
curl -s -X POST -H "Authorization: Bearer $TOKEN" $DEV/calendar -d '{"url":""}'
```

`url` takes a Google "secret address in iCal format" or an Apple iCloud
public-calendar / `webcal://` link (empty string clears it). `range` is one of
`day`, `3day`, `week`, or `agenda` (unknown values fall back to `day`). Both
fields are optional in a POST, so `{"range":"agenda"}` re-ranges without
re-sending the link. The response echoes the stored config plus `provider` and a
`supported` flag (whether the link looks like a fetchable feed). Pushes apply
**live** — the running photo frame reloads the calendar on its next refresh, no
reboot or re-dream needed.

#### Screensaver over the air

The whole photo-frame configuration is managed the same way, via a `/screensaver`
endpoint (GET reads it, POST pushes a partial update — only the fields you send
change). Recognised fields mirror the in-app Screensaver settings: `enabled`,
`source` (`default`; `folderPath`/`albumUrl` set the folder/URL sources), `fit`
(`fill`/`fit`), `intervalSec`, `albumRefreshMin`, `shuffle`, `includeVideo`,
`showNowPlaying`, `batterySaver`, `presenceMode` (`ALWAYS_ON`/`PRESENCE`),
`idleSleepMin` (0 = never), and the overnight window (`overnightEnabled`,
`overnightStartMin`, `overnightEndMin`, minutes-from-midnight). Display changes take
effect on the next screensaver cycle (as in the in-app settings); `enabled` and the
overnight window apply immediately. The calendar widget keeps its own `/calendar`
endpoint above. The `fleetctl` CLI below wraps all of this.

#### Dev mode + local builds (iterate over WiFi)

When you're hacking on Immortal itself, you don't want to cut a GitHub release for
every change — and you don't want the device's official self-updater to clobber your
test build. Dev mode + the local-install path handle both:

```bash
./fleetctl dev on  --device "Living Room"      # pause the official self-updater
APK_SHA256=$(shasum -a 256 ./app/build/outputs/apk/release/app-release.apk | awk '{print $1}')
# Set VERSION_CODE, VERSION_NAME, and CERT_SHA256 from the signed release evidence.
../scripts/verify-android-release.sh ./app/build/outputs/apk/release/app-release.apk \
  "$VERSION_CODE" "$VERSION_NAME" "$CERT_SHA256" "$APK_SHA256"
./fleetctl dev update ./app/build/outputs/apk/release/app-release.apk \
  --sha256 "$APK_SHA256" --device "Living Room"
./fleetctl dev status --device "Living Room"
./fleetctl dev off --device "Living Room"       # resume official updates
```

`dev update` enables dev mode (unless `--no-pause`), pushes the local APK to the
device, and installs it over Immortal via the same silent path the store uses — no
cable, no `version.json`, no catalog/versionCode gate. `--package`/`--path` override
the defaults (`com.immortal.launcher` and a temp path in the app's files dir).
Before any push, `--sha256 DIGEST` compares the local APK with the expected
digest; a mismatch exits before device targeting and cannot modify a Portal.
`scripts/verify-android-release.sh` adds the release preflight: it checks the
recorded version identity, signer certificate, v2 signature scheme, and exact
APK digest before any deployment command runs.

**Sign with the same key.** An in-place update is signature-checked by Android, so
your local build must be signed with the **same key** as the installed Immortal (the
release key in `keystore.properties` — see the top-level README's *Releasing*) or
the install is rejected. Reinstalling the same `versionCode` is fine; a *lower*
versionCode is a downgrade and needs an uninstall first. On the device side this is
just a flag (`/dev`) and a local-path option on `/install`. The local-path install
is **only honoured while dev mode is on** (otherwise `/install` returns
`403 dev_mode_required` and the token can still only install known catalog apps) —
`dev update` enables it for you. Dev mode itself pauses `UpdateManager` and nothing
else.

#### The `fleetctl` CLI

`fleetctl` is a fast, **zero-dependency** CLI — a single-file Rust program (standard
library only, no crates) that drives the agent for you so you don't hand-write
`curl` calls. It's in the same native-code spirit as the Portal's own hand-rolled
HTTP, compiles to a small static-ish binary, and starts instantly. Build it once:

```bash
rustc -O fleet.rs -o fleetctl     # no Cargo needed; or:
cargo build --release             # produces target/release/fleetctl
```

It reads the same `fleet/<serial>.json` files that `--fleet` records, so you can
target devices by name:

```bash
./fleetctl devices                       # list registered Portals
./fleetctl info --device all             # identity/version/state for every device
./fleetctl apps --device "Living Room"   # catalog + what's installed
./fleetctl install org.videolan.vlc --device all
./fleetctl update --check                # dry-run: what has updates
./fleetctl update --all --device all
./fleetctl dev on --device "Living Room"            # pause official self-update
./fleetctl dev update ./app/build/outputs/apk/release/app-release.apk \
  --sha256 "$(shasum -a 256 ./app/build/outputs/apk/release/app-release.apk | awk '{print $1}')" \
  --device "Living Room"
./fleetctl dev off --device "Living Room"           # resume official self-update
./fleetctl calendar set --url 'https://…/basic.ics' --range week --device all
./fleetctl calendar off --device "Living Room"
./fleetctl screensaver get --device "Living Room"
./fleetctl screensaver set --enabled true --fit fill --interval 45 --shuffle true --device all
./fleetctl screensaver set --overnight true --overnight-start 22:00 --overnight-end 07:00 --device all
./fleetctl screensaver set --album-url 'https://www.icloud.com/sharedalbum/#…' --device all
./fleetctl push ./frame.jpg /sdcard/Android/data/com.immortal.launcher/files/frame.jpg
./fleetctl pull /sdcard/some.log ./some.log
./fleetctl ls /sdcard/Android/data/com.immortal.launcher/files
./fleetctl logcat --lines 200 --device Kitchen
./fleetctl diag --device all
./fleetctl action reaffirm --device all  # re-assert launcher/screensaver ownership
./fleetctl raw POST /calendar '{"range":"agenda"}'   # call any endpoint directly
```

Pick a target with `--device NAME|serial|all` (a single registered device is used
by default). To reach a Portal that isn't in the registry, skip it with
`--host <ip> --token <token> [--port 8723]`. Point at a different registry folder
with `$IMMORTAL_FLEET_DIR`.

**What it can and can't touch.** The agent runs as the *app* user, so the CLI
reaches everything the agent exposes — installing/updating apps, reading and
writing files on shared and app-external storage (`push`/`pull`/`ls`/`cat`),
Immortal's own config and the screensaver calendar, logcat, and diagnostics. It is
**not** a full adb shell: it can't read other apps' private data dirs or run
`pm` / `appops` / `settings` directly — those need the shell user (a USB adb run or
the provisioning kit). The `raw` subcommand calls any endpoint the agent grows
later, so the CLI keeps working as the API expands.

#### Back up & restore a Portal (before a risky reinstall)

A clean reinstall — e.g. switching a Portal onto a build signed with a **different
key** (your own debug/release key instead of the one currently installed) — requires
an uninstall first, which **wipes that Portal's app data**: Immortal's config, its
saved credentials (Immich / SMB / WebDAV, multiroom user/pass, …), and the list of
apps you'd installed. `fleet-backup.sh` snapshots all of that first, and
`fleet-restore.sh` puts it back afterward.

```bash
./fleet-backup.sh                 # snapshot every registered Portal
./fleet-backup.sh "Portal Mini"   # …or one (by name or serial)
```

Each run writes `backups/<serial>/<timestamp>/` with `info.json`, `apps.json`
(the installed-app list), `registry.json` (host/port/token), and every readable
`shared_prefs/*.xml` — the agent runs *as* Immortal, so it reads its own
`/data/data/.../shared_prefs` over `/fs/read`, no root or adb needed. **The
`backups/` tree holds tokens and cleartext credentials, so it is git-ignored —
keep it private.**

After reinstalling the new build and re-registering the agent
(`./provision.sh --fleet` over USB, which mints a fresh token):

```bash
./fleet-restore.sh "Portal Mini"                 # reinstall apps + restore prefs, then reboot
./fleet-restore.sh "Portal Mini" --config        # instead: re-apply only screensaver/calendar
./fleet-restore.sh "Portal Mini" --apps --dry-run # preview what would run
```

By default restore reinstalls the third-party apps that were present and pushes the
saved `shared_prefs` back **verbatim** (full fidelity, including stored credentials),
skipping `fleet_agent.xml` so the freshly-provisioned token is kept, then reboots so
the app reloads them from disk. `--config` is a lighter alternative that re-applies
only the endpoint-covered screensaver + calendar subset (no stored credentials, no
reboot). `--prefs`, `--apps`, and `--dry-run` can be combined as needed.

Provisioning deliberately does **not** switch the Portal into raw adb-over-WiFi:
`adb tcpip` restarts adbd, which would stop Shizuku, and it doesn't survive a
reboot anyway. The agent is the persistent channel. If
you occasionally need a raw adb shell or scrcpy mirroring, enable it on demand
(it pauses those helpers until the next USB run / reboot):

```bash
./provision.sh --wifi-adb       # macOS/Linux
# powershell ... provision.ps1 -WifiAdb   # Windows
```

## Command line (optional)

```bash
./provision.sh            # provision
./provision.sh --status   # show current home / screensaver / install state / client
./provision.sh --restore  # undo
```

Windows: `powershell -ExecutionPolicy Bypass -File provision.ps1 [-Status|-Restore]`.

## What you should know

- **Enabling on-device installs is a security tradeoff.** It relaxes the device's default
  check that otherwise blocks installing apps that aren't signed by Meta. A production store
  should add its own package verification. Restore puts the default back.
- **Disabling OS updates (`DISABLE_OTA`)** stops Meta's updater (`alohaotasetup`) and update UI
  (`otaui`) via reversible `pm disable-user`, so a future OTA can't silently undo this setup or
  reset your launcher/screensaver. Portal is a discontinued line, so this mainly forgoes
  (unlikely) future patches; it's the right call to keep a provisioned device stable, but set
  `DISABLE_OTA=false` if you'd rather keep updates. Restore re-enables them.
- **This is a one-time, per-device step.** It requires the USB/ADB connection once. After
  that, the client app runs normally and can install/update other apps with a single on-screen
  tap — no computer needed again.
- **Reversible.** Restore puts the stock Aloha launcher and screensaver back and undoes the
  install changes. The client app is left installed (uninstall with `adb uninstall <PKG>`).
- Portal receives no further OS updates, so the provisioned state is stable.
