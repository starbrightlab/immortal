# Install on a Portal

There are two ways to get Immortal onto a Portal: the **provisioning kit** (recommended)
or a **build from source**.

## The easy path: the provisioning kit

The [provisioning kit](provisioning.md) does everything in one double-click: it installs
Immortal, optionally sets it as the home screen and screensaver, pre-grants permissions,
and can freeze OS updates so the setup sticks. It fetches the latest release automatically,
and it's fully reversible.

1. **Enable ADB on the Portal** (one time): Settings → Debug → **ADB Enabled**.
2. **Connect** the Portal to your computer with a USB-C cable.
3. **Double-click** `Provision-Portal.command` (macOS/Linux) or `Provision-Portal.bat` (Windows).
4. When the Portal shows **"Allow USB debugging?"**, tap **Allow** (check "Always allow").
5. Wait for "Done."

To undo everything, double-click `Restore-Portal`.

See [Provisioning kit](provisioning.md) for what each step does, the configuration options,
and platform-specific notes (macOS Gatekeeper, Windows "unblock").

## Build from source

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.immortal.launcher/.HomeActivity
```

!!! note "Release builds must be signed with one stable key"
    In-place self-update is signature-checked, so every release must be signed with the
    **same** key. See [Releasing](releasing.md) for the signing setup. Debug builds are
    fine for trying things out on a device you provision by hand.

## First-gen Portals need one extra thing

The original **Portal+ (Gen-1, Android 9)** and the **Portal TV** have a broken built-in
installer dialog. The provisioning kit fixes this automatically and the fix survives
reboots — but it's worth understanding what's going on. See
[First-gen Portals](first-gen-portals.md).
