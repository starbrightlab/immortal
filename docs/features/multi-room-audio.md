# Multi-room audio & now-playing

Synchronized, whole-home music across the Portals running Immortal — the same audio, in tight
sync, in every room — plus now-playing and transport controls on **every** device in a group.

!!! info "Design deep-dive"
    This page is the user-facing overview. The architecture, what's reused vs built, and the
    licensing boundary are in the [multi-room audio design notes](../design/multi-room-audio.md).

## The stack

- **[Snapcast](https://github.com/badaix/snapcast)** plays the synced audio (the stock Snapcast
  app, installable from the [App Store](app-store.md)).
- **[Music Assistant](https://music-assistant.io/)** is the server/source manager and room UI.
- **Immortal** adds an in-launcher relay (`MultiRoomService`) on top.

No Google services, no root, no new hardware — within the Portal's
[hard limits](../limitations.md).

## Now-playing on every Portal

When a Portal is part of a Snapcast group, `MultiRoomService` reads what the group is playing off
the snapserver and republishes it as a device **media session**. The launcher's now-playing card
and the Portal's system media controls then show the track — title, artist, cover art — and the
transport controls (play/pause/skip) forward to Music Assistant. It's **group-aware**: a Portal
only shows what its own group is actually playing, not just the device that started playback.

**AirPlay** cast into a group works too: Immortal reads that metadata from Music Assistant, since
AirPlay streams don't carry it over Snapcast.

## The device's own now-playing

Independently of multi-room, the home header and screensaver show a now-playing mini-player for
whatever is playing on the device itself, sourced from the device's media session via
`NowPlayingHub` / `MediaSessionReader`. It works with any media app and stays out of the way when
nothing's playing.

## Set-and-forget

A room joins on boot, survives for days, gets out of the way for calls, and comes back on its
own. The "Start on boot" setting (and `BOOT_APPS` in [provisioning](../provisioning.md))
relaunches the player app after a power blip. Settings live under **Immortal → Multi-room audio**,
with an in-app setup guide.
