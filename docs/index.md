# Immortal

**A custom home-screen layer that keeps discontinued Meta Portal devices alive.**

A play on *Portal* — and on keeping it going after Meta wound the platform down.
Immortal turns a Portal into a device you own: your launcher, your screensaver, and
an app store that installs a curated catalog on-device, with remote self-update so it
keeps improving without a cable.

!!! info "At a glance"
    - **Package:** `com.immortal.launcher`
    - **Targets:** Meta Portal — **Android 9** (API 28: the 2018 Portal / Portal+ and
      Portal TV) and **Android 10** (API 29: the 2019 and 2021 models), arm64, no Google
      services.
    - **Hardware:** touch models and the remote-driven **Portal TV** are both supported.

## What Immortal gives you

| Area | What it does |
| --- | --- |
| [Launcher](features/launcher.md) | A fullscreen app grid with clock/date/weather, folders, an app switcher, and a Calls tile. |
| [Screensaver](features/screensaver.md) | A photo frame with clock faces and many photo sources (your own folder, Immich, SMB, WebDAV, web pages, iCloud albums, or a keyless built-in feed). |
| [App Store](features/app-store.md) | A hosted, community-submittable catalog that installs apps on-device, with updates and self-update. |
| [Multi-room audio](features/multi-room-audio.md) | Synced whole-home music across Portals, with now-playing and transport controls on every device in a group. |
| [Smart home](features/smart-home.md) | Home Assistant integration over MQTT — state and control, including the screen. |
| [Fleet management](features/fleet.md) | Manage a Portal over WiFi — deploy apps, push config, browse files, read logs — no cable. |
| [Portal TV](features/portal-tv.md) | Full remote/D-pad navigation on the no-touchscreen model. |

## Get started

1. [Install on a Portal](install.md) — the easiest path is the provisioning kit.
2. On the original Portal+/Portal TV, read [First-gen Portals](first-gen-portals.md)
   for the one install quirk worth knowing up front.
3. Building a Portal app? [Submit it to the store](submitting-apps.md).

## A note on scope

Some things are hardware/firmware limits of the Portal itself and can't be fixed by any
Immortal release — no Google Play Services, no root, and a few others. The
[Hardware limitations](limitations.md) page is the honest list.

!!! warning "Not affiliated with Meta"
    Immortal is an independent community project — **not affiliated with, endorsed by, or
    sponsored by Meta**. "Meta Portal" and "Portal" are trademarks of Meta Platforms, Inc.,
    used here only to identify compatible hardware. Provisioning modifies device settings and
    is **use-at-your-own-risk** (reversible, but no guarantees; may void warranty).
