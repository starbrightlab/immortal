# Hardware limitations (the honest list)

These are hardware/firmware limits of the Portal itself, confirmed on-device — **not** things
a future Immortal release can fix.

## No Google Play Services

The Portal never had them, and they can't be added — it would need Google's own signed
software, or system-level write access we don't have. [Aurora Store](first-gen-portals.md)
installs plenty of apps that work fine, but anything that depends on Google for sign-in, push
notifications, or DRM may be limited or won't run.

microG isn't an option either: the firmware has no signature-spoofing support.

## No root (the bootloader can't be unlocked)

Meta ships the standard "OEM unlocking" developer toggle, but the bootloader hard-refuses
(`Flashing Unlock is not allowed`) even with it enabled, and there's no manufacturer unlock
program.

This is the root cause of several smaller constraints — for example, why non-root helpers like
Shizuku (and the old install daemon) don't survive a reboot, and why
[fleet management](features/fleet.md) runs as an in-app service rather than over adb-over-WiFi.

## USB-C thumb drives don't mount reliably

On the Portal Go the port switches to host mode and a drive powers up and enumerates, but the
storage stack doesn't bind it, so it doesn't appear as a folder. Put
[screensaver photos](features/screensaver.md) on the device's own storage instead (e.g. copy
them across while it's plugged into your computer), or use a network/self-hosted photo source.

## Can't *ask* for Meta's presence signal

The Portal won't let an unprivileged app read Meta's camera-based presence detection — it's
front-camera computer vision behind a platform-signature permission, and that door stays shut.

Immortal gets at it another way. Portal's own detector logs a heartbeat roughly every 30 seconds
**while it sees someone**, and goes quiet when the room empties, so Immortal tails the system log
and reads presence from whether that beat is still arriving. That needs the `READ_LOGS` permission,
which the [provisioning kit](provisioning.md) already grants, and it powers the **Presence** entity
in the [Home Assistant integration](features/smart-home.md#presence).

Where that isn't available — no `READ_LOGS` grant, or a Portal whose firmware doesn't log it —
Immortal falls back to inferring presence from the system's own dream/sleep lifecycle, which *is*
derived from the same signal. That fallback can't see the room while the photo frame is pinned on
(a held screen never times out), which is what the `confident` attribute reports. See the
[multi-room design notes](design/multi-room-audio.md) for how this is used.
