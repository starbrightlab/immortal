# Provisioning kit

The provisioning kit turns a connected Meta Portal into an Immortal device with one
double-click per device: it installs Immortal, optionally sets it as the home screen and
screensaver, pre-grants permissions, fixes the [first-gen installer](first-gen-portals.md),
and can freeze OS updates so the setup sticks. A matching restore tool puts everything back.

The owner never touches a terminal — they plug in a USB-C cable, accept one prompt on the
Portal, and double-click.

!!! tip "Source"
    The kit lives in
    [`provisioning/`](https://github.com/starbrightlab/immortal/tree/main/provisioning).
    The full reference — including platform notes and every command-line flag — is in
    [`provisioning/README.md`](https://github.com/starbrightlab/immortal/blob/main/provisioning/README.md).

## Quick start

1. **Enable ADB on the Portal**: Settings → Debug → **ADB Enabled**.
2. **Connect** over USB-C.
3. **Double-click** `Provision-Portal.command` (macOS/Linux) or `Provision-Portal.bat` (Windows).
4. Tap **Allow** on the Portal's "Allow USB debugging?" prompt.
5. To undo: double-click `Restore-Portal`.

!!! warning "macOS / Windows: clear the download quarantine first"
    - **macOS** may warn the file is from an unidentified developer — right-click → Open the
      first time, or `xattr -d com.apple.quarantine Provision-Portal.command`.
    - **Windows** marks downloaded files as blocked, which makes the PowerShell script error
      out — "unblock" the extracted files first.

## What it does

Configuration lives in
[`config.env`](https://github.com/starbrightlab/immortal/blob/main/provisioning/config.env).
The defaults are sensible; the highlights:

| Step | Default | Notes |
| --- | --- | --- |
| `SET_LAUNCHER` | on | Replace the system home with Immortal (reversible). |
| `SET_SCREENSAVER` | on | Set Immortal's photo frame as the screensaver. |
| `DISABLE_VERIFIER` | on | Disable Meta's package verifier, which otherwise blocks on-device installs. |
| `DISABLE_INSTALLER_OVERLAY` | on | Fix the [Gen-1 white-on-white installer dialog](first-gen-portals.md). API < 29 only. |
| `DISABLE_OTA` | ask | Block Meta OS updates so an OTA can't undo the setup. |
| Shizuku | installed | Privileged broker — useful for Aurora's silent installs. |
| `BOOT_APPS` | MA player | Apps to relaunch after a reboot (also editable in Settings → Start on boot). |
| `ENABLE_FLEET` | off | Opt-in always-on WiFi [management agent](features/fleet.md). |

On the first run the kit snapshots the device's real stock launcher and screensaver to
`/sdcard/immortal_restore.env`, so `--restore` puts the correct components back on any Portal
model.

## Useful commands

```bash
./provision.sh                 # full provision (default)
./provision.sh --restore       # undo everything
./provision.sh --overlay-fix   # apply just the Gen-1 dialog fix
./provision.sh --shizuku       # (re)start the Shizuku server
./provision.sh --fleet         # enable the WiFi fleet agent on this device
./provision.sh --wifi-adb      # enable raw adb-over-WiFi on demand
```

On Windows the same flags are PowerShell switches, e.g. `provision.ps1 -OverlayFix`.

## Reversibility

Everything the kit does is reversible with `Restore-Portal` / `--restore`: it restores the
original home and screensaver, re-enables the verifier and OS updates, and re-enables the
installer overlay. Some device-admin removal must be finished on-device — `--restore` tells you
when and how, rather than claiming it did something Android won't allow from a script.
