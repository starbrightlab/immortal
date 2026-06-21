# Installing apps & app stores

Immortal's own [App Store](../features/app-store.md) is the easiest way to add apps — a curated,
on-device catalog that installs with a tap. But a Portal is a normal (if unusual) Android device,
so you have several ways to get software onto it.

!!! note "First-gen Portals: read this first"
    On the original Portal+ and Portal TV the built-in installer dialog is broken until the
    provisioning kit's overlay fix is applied. Everything below assumes that's done — see
    [First-gen Portals](../first-gen-portals.md).

## 1. The Immortal App Store (recommended)

Open the **App Store** tile. The catalog includes media apps (VLC, Jellyfin, AntennaPod), smart
-home apps (Home Assistant, Snapcast), utilities (Material Files, Termux), and more. F-Droid
entries resolve the latest APK at install time, so they never go stale. Installed apps get an
**Updates** section.

If something you want isn't listed, anyone can [submit it](../submitting-apps.md).

## 2. Play-Store apps via Aurora Store

[Aurora Store](https://auroraoss.com) installs Play-Store apps (Spotify, etc.) **without a Google
account**. Install **Aurora Store** from the Immortal App Store, then pick an installer mode:

- **First-gen Portal:** use Aurora's **Shizuku** installer (the kit installs Shizuku for you), or
  its **Session** installer through the overlay-fixed system dialog. Full walkthrough:
  [First-gen Portals → Play-Store apps via Aurora](../first-gen-portals.md#play-store-apps-via-aurora).
- **Newer Portals:** the system installer works out of the box — Session is fine; use Shizuku for
  apps that ship as split APKs.

!!! warning "No Google Play Services"
    Aurora installs the apps, but the Portal has no Google Play Services and can't get them — apps
    that depend on Google for sign-in, push, or DRM may be limited or won't run. See
    [Hardware limitations](../limitations.md).

## 3. F-Droid apps

Most open-source apps in the Immortal catalog are sourced from **F-Droid** and install directly —
you don't need a separate client. If you want to browse F-Droid yourself, you can sideload the
F-Droid client APK (below) like any other app.

## 4. Sideloading an APK

Two built-in entry points handle loose APKs:

- **Install with Immortal** (`ApkInstallActivity`) — open any `.apk` from Chrome or a file manager
  (e.g. [Material Files](https://github.com/zhanghai/MaterialFiles), in the store) and choose
  *Install with Immortal*.
- **Install an APK** (`ApkBrowserActivity`) — lists APKs already in your **Downloads** folder so
  you can install them without hunting through a file manager.

On a first-gen Portal these go through the now-readable system installer dialog (thanks to the
overlay fix). On newer Portals they use the standard dialog directly.

## What to expect

| Path | Best for | Notes |
| --- | --- | --- |
| Immortal App Store | Most apps | Curated, auto-updating, one tap |
| Aurora Store | Play-Store-only apps | No Google account; Shizuku for split APKs |
| F-Droid | Open-source apps | Already resolved by the store |
| Sideload | One-off / your own builds | "Install with Immortal" or the Downloads browser |
