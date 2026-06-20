# Immortal

A custom home-screen layer for discontinued Meta Portal devices — a play on *Portal*, and on
keeping it alive after Meta wound the platform down. Immortal turns a Portal into a device you
own: your launcher, your screensaver, and an app store that installs a curated catalog
on-device, with remote self-update so it improves over time without a cable.

Package: `com.immortal.launcher` · Target: Meta Portal — **Android 9** (API 28: the 2018 Portal /
Portal+ and the Portal TV) and **Android 10** (API 29: the 2019 and 2021 models), arm64, no Google
services. Touch models and the remote-driven **Portal TV** are both supported.

## What's in it

- **Launcher** (`HomeActivity`) — a fullscreen app grid with a clock/date/weather header and an
  optional charge indicator (shown only on Portal Go, which has a battery). A photo-style
  **Screensaver** button sits top-left; a **Manage** button bottom-right. Manage mode lets you
  remove apps (tap the ✕) and organise the grid into **folders** by dragging one app onto
  another — name them, rename them, and drag apps back out, just like a phone. A green **Calls**
  tile bridges to the stock dialer/contacts for WhatsApp and Messenger calling.
- **Screensaver** (`PhotoDreamService` / `PhotoFrameController`) — a photo frame with stock-style
  clock/battery/date/weather widgets. Point it at a folder of **your own photos and videos**, or
  use the keyless built-in feed (Lorem Picsum, Unsplash-ready); weather is keyless Open-Meteo + IP
  geolocation. It cooperates with the Portal's presence sensor so it runs as a **permanent frame**
  while someone's around (and on mains power), and on the battery-powered **Portal Go** an optional
  "sleep when nobody's around" setting saves power. Swipe to change photos, tap to exit.
- **App Store** (`StoreActivity` / `StoreCatalog`) — a hosted JSON catalog
  ([`catalog.json`](catalog.json), schema v2) rendered with app icons, search, per-app detail
  pages (author, source, website, credit), device-compatibility badges, and an "Updates" section
  for installed apps. F-Droid entries resolve the current APK at install time so the catalog never
  goes stale; your own apps use a direct `apkUrl`. **The store is open to community submissions** —
  every catalog PR is CI-validated. Built a Portal app? [Get it listed](SUBMISSIONS.md).
- **Help tour** (`HelpActivity`) — a friendly, non-technical walkthrough on a Help tile (and once
  on first launch), so anyone can pick up a revived Portal.
- **Portal TV support** — full remote/D-pad navigation across the whole UI, a Calls tile that
  bridges to the TV's stock home, and an Immortal tile that appears on that stock home so you can
  hop back.
- **Universal installer** — on the Gen-1 Portal+ (Android 9) the *built-in* Android installer
  dialog is broken (renders white-on-white with no visible buttons), so sideloading normally fails.
  The provisioning kit fixes this for the whole device by **disabling the Meta display overlay**
  (`com.facebook.aloha.rro.niu.android`) that re-themes the dialog white-on-white — a change that
  survives a reboot — so the stock installer dialog becomes usable. Immortal's store and self-update
  then install through Android's standard installer; an **"Install with Immortal"** handler
  (`ApkInstallActivity`) catches any APK you open from Chrome or a file manager, and an **"Install an
  APK"** browser (`ApkBrowserActivity`) lists APKs in your Downloads. (The kit also grants Immortal
  the install-source permission, since the Portal's on-device "install unknown apps" toggle doesn't
  work.) For Play-Store apps via **Aurora Store**, use Aurora's *Shizuku* installer with Shizuku
  (`provision.sh --shizuku`) — see
  [Play-Store apps on a first-gen Portal](#play-store-apps-via-aurora-on-a-first-gen-portal) below.
  Newer Portals have a working installer and don't need the Gen-1 fix.
- **Self-update** (`UpdateManager`) — Immortal polls [`version.json`](version.json); when it
  advertises a higher `versionCode`, it downloads and installs the new build over itself. No
  cable, no laptop.
- **Provisioning kit** ([`provisioning/`](provisioning/)) — one double-click per device: installs
  Immortal, optionally sets it as the home screen and screensaver, and can freeze OS updates so
  the setup sticks. Fully reversible (`Restore-Portal` / `--restore`).

## Install on a Portal

The easiest path is the [provisioning kit](provisioning/): connect the Portal over USB-C with
ADB enabled, then double-click `Provision-Portal` (macOS/Linux) or `Provision-Portal.bat`
(Windows). It fetches the latest release automatically.

To build from source instead:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.immortal.launcher/.HomeActivity
```

## On first-gen Portals (Portal+ Gen-1, Android 9)

Immortal runs on the original Portal+ too — launcher, screensaver, and app store all work. There's
one quirk worth knowing up front, because it's a quirk of that hardware's older software, not a
fault in Immortal:

**The first-gen Portal's built-in Android installer is broken** (it opens a dialog with no
buttons), so apps can't be installed the normal way. Immortal works around this with a small helper
that the provisioning kit starts over USB — after that, the app store, "Install with Immortal," and
sideloading all install silently with no dialog.

That helper can't survive a reboot on this generation (a limitation of running without root on
Android 9 — the same reason tools like Shizuku need re-starting). To keep installs working across
reboots anyway, the kit **also repairs the Portal's own installer dialog**: the blank-with-no-buttons
screen is caused by one of Meta's display overlays re-theming it white-on-white — a great community
discovery by [u/keremimo](https://www.reddit.com/user/keremimo/) on r/FacebookPortal — and disabling
that overlay (`cmd overlay disable`) brings the normal dialog back. Unlike the helper, that change is
remembered across reboots — so after a restart, the app store, "Install with Immortal," and
sideloading fall back to the **now-visible system dialog** with no reconnect needed. (The silent
helper is still the nicer path when it's running — no dialog at all — so reconnecting to restart it
is still worthwhile, just no longer required to install anything.)

If you'd rather leave the stock dialog untouched, set `DISABLE_INSTALLER_OVERLAY=false` in
`config.env`; then a rebooted Gen-1 with the helper down **pauses new installs until you reconnect
and re-run the installer** — a 30-second step — and Immortal shows a clear note in the store so it's
never a mystery. Either way, everything else keeps working across reboots: your home screen,
screensaver, and every app you've already installed.

The **Portal TV** is the same generation (Android 9), so the same install mechanics apply. It has
no touchscreen, but Immortal is fully driveable with the TV remote — the home grid, folders, App
Store, and screensaver settings all navigate with the D-pad.

Newer Portals (Portal Go, Mini, gen-2) have a working installer and don't need any of this — though
their silent-install helper also stops after a reboot, so a new app installed then goes through the
system dialog (and some Play-Store split apps won't parse there) until you re-run the kit. The store
shows a note when that happens.

### Play-Store apps via Aurora on a first-gen Portal

[Aurora Store](https://auroraoss.com) lets you install Play-Store apps (Spotify, etc.) without a
Google account. On the Gen-1 Portal+ its two default installer modes — "Session" and "Native" —
both run into the broken stock installer, and unlike a file you download in Chrome, Aurora keeps
its APK in private storage where Immortal's helper can't reach it. The path that *does* work is
Aurora's **Shizuku** installer, which installs through the same privileged channel Immortal uses.

The provisioning kit sets most of this up for you: on a Gen-1 Portal it **installs Shizuku and
starts its server automatically** (it skips this on newer Portals, which don't need it). So all
that's left is to point Aurora at it, a one-time, two-tap step:

1. Install **Aurora Store** from the Immortal App Store.
2. In **Aurora → Settings → Installation → Installation method**, choose **Shizuku installer**.
   The first install prompts "Allow Aurora Store to access Shizuku?" — tap **Allow all the time**.

After that, Aurora installs Play-Store apps silently, including split APKs — no dialog, no broken
installer (verified end-to-end on a Portal+ installing Spotify). Shizuku's server, like Immortal's
own helper, doesn't survive a reboot; the kit restarts it on its next run, or run
`./provision.sh --shizuku` (`provision.ps1 -Shizuku`).

#### Simpler alternative: skip Shizuku

Shizuku's server can fail to stay up on some Gen-1 firmware (Android 9 may kill it right after
launch — the kit now detects this and tells you instead of falsely reporting success). You can skip
Shizuku entirely: Aurora's **Session** installer hands its APKs to Immortal's own silent-install
daemon, which just needs Aurora to hold `REQUEST_INSTALL_PACKAGES`.

**When you install Aurora from the Immortal App Store, the daemon grants this for you automatically**
(it runs as the shell user, so it can set the app-op). After that, set **Aurora → Settings →
Installation → Installation method → Session** and installs work — no Shizuku, no broken dialog
(community-verified on a Gen-1 Portal+, including split APKs).

If you installed Aurora some other way (so the daemon never saw it), grant it once manually:

```bash
adb shell appops set com.aurora.store REQUEST_INSTALL_PACKAGES allow
```

## Releasing

Hosted from this repo:

- [`version.json`](version.json) — the self-update manifest. Bump `versionCode`/`versionName`,
  build a signed release, and attach it as `immortal.apk` to a GitHub Release; devices update on
  their next check. The asset **must** be named `immortal.apk` — the manifest's `apkUrl` (and the
  store catalog) point at the stable `releases/latest/download/immortal.apk`, which 404s and breaks
  self-update for every device if a release attaches only a versioned name. Use
  [`scripts/publish-release.sh`](scripts/publish-release.sh) `<tag> <signed.apk>` to upload the
  asset under both the stable and versioned names and verify the URL resolves.
- [`catalog.json`](catalog.json) — the app-store catalog. Edit and commit; clients pick it up on
  next open (a bundled copy ships as the offline fallback).

Release builds must be signed with the **same** key every time (in-place self-update is
signature-checked). Signing is configured via `keystore.properties`, which the build looks for
first at the repo root (git-ignored) and then at `~/.immortal-signing/keystore.properties` — the
recommended home, since nothing in a git working tree can be considered safe from cleanup. Keep
that key backed up safely (e.g. iCloud) — losing it means devices can no longer self-update.

## Limitations (the honest list)

These are hardware/firmware limits of the Portal itself, confirmed on-device — not things a future
Immortal release can fix:

- **No Google Play Services.** The Portal never had them, and they can't be added (it would need
  Google's own signed software, or system-level write access we don't have). Aurora Store installs
  plenty of apps that work fine, but anything that depends on Google for sign-in, push
  notifications, or DRM may be limited or won't run. microG isn't an option either — the firmware
  has no signature-spoofing support.
- **The bootloader can't be unlocked, so there's no root.** Meta ships the standard "OEM unlocking"
  developer toggle, but the bootloader hard-refuses (`Flashing Unlock is not allowed`) even with it
  enabled, and there's no manufacturer unlock program. This is why the first-gen install helper
  can't be made permanent — root was the only path, and it's welded shut.
- **USB-C thumb drives don't mount reliably.** On the Portal Go the port switches to host mode and a
  drive powers up and enumerates, but the storage stack doesn't bind it, so it doesn't appear as a
  folder. Put screensaver photos on the device's own storage instead (e.g. copy them across while
  it's plugged into your computer).

## Disclaimer

Immortal is an independent community project — **not affiliated with, endorsed
by, or sponsored by Meta**. "Meta Portal" and "Portal" are trademarks of Meta
Platforms, Inc., used here only to identify compatible hardware. Provisioning
modifies device settings and is **use-at-your-own-risk** (reversible, but no
guarantees; may void warranty). See [DISCLAIMER.md](DISCLAIMER.md) for the full
text and privacy notes.

## License

MIT — see [LICENSE](LICENSE).
