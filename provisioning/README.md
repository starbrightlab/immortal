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
