# Screensaver & photo frame

`PhotoDreamService` / `PhotoFrameController` — a photo frame with stock-style
clock/battery/date/weather widgets that doubles as the Portal's screensaver.

Swipe to change photos, tap to exit.

## Clock faces

Choose a screensaver clock face from a face picker (`FacePickerActivity`): **flip clock**,
**big**, **bold**, or **minimal**, each with size options.

## Photo sources

Point the frame at whatever you like — most sources can be set up **from your phone by scanning
a QR code**, so you don't type URLs and credentials on the Portal:

| Source | Notes |
| --- | --- |
| Your own folder | Photos **and** videos from the device's own storage. EXIF rotation is honoured. |
| iCloud shared album | Paste a shared-album link (supports Apple's newer CloudKit link format). |
| [Immich](https://immich.app/) | A self-hosted photo library. |
| Network share (SMB) | A file server on your LAN. |
| WebDAV | Any WebDAV server. |
| Web page | Pull images from any web page. |
| Built-in feed | Keyless (Lorem Picsum; Unsplash-ready with a key). Requests photos at the device's actual resolution/orientation so they're sharp on every model. |

## Presence-aware behaviour

The screensaver cooperates with the Portal's camera-based presence detection so it can run as a
**permanent frame** while someone's around (and on mains power). On the battery-powered
**Portal Go**, an optional "sleep when nobody's around" setting saves power.

Immortal can't read Meta's presence signal directly (see
[Hardware limitations](../limitations.md)), so it infers presence from the system's own
dream/sleep lifecycle. The design notes go deep on this:
[Multi-room audio → Presence](../design/multi-room-audio.md).

## Overnight night clock

During an overnight window the screensaver can show a **dimmed clock** instead of going fully
dark — and a deliberate tap inside the window wakes the device for normal use, returning to
sleep a short while after you stop interacting.
