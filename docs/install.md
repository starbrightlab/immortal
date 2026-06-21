# Install on a Portal

There are two ways to get Immortal onto a Portal: the **provisioning kit** (recommended)
or a **build from source**.

## The easy path: the provisioning kit

The [provisioning kit](provisioning.md) does everything in one double-click: it installs
Immortal, optionally sets it as the home screen and screensaver, pre-grants permissions, fixes
the [first-gen installer](first-gen-portals.md), and can freeze OS updates so the setup sticks. It
fetches the latest release automatically (and even downloads `adb` for you if needed), and it's
fully reversible.

**→ Follow the full step-by-step walkthrough: [Provisioning a Portal](provisioning.md)** — it
covers downloading the kit, enabling ADB, the prompts you'll see, how to verify it worked, and
troubleshooting.

The gist: enable **Settings → Debug → ADB Enabled** on the Portal, connect it over USB-C,
double-click **`Provision-Portal`**, and tap **Allow** on the Portal. To undo, double-click
**`Restore-Portal`**.

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
