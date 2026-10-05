# AirPlay — stream from your iPhone

Turn the Portal into an AirPlay 2 receiver: play music through its speakers, or put an iPhone,
iPad or Mac screen on it. Nothing to install on the phone — it appears in Control Center like an
Apple TV or a HomePod.

Off by default. Turn it on from **Immortal Settings → Remote → Stream from your iPhone**, or with
the AirPlay glyph in the home-screen header (one tap enables it and shows the name to pick).

## What works

| Mode | How it looks on the phone | What the Portal does |
|---|---|---|
| **Audio** | Control Center → AirPlay | Plays through the speakers. Stays headless — the screensaver keeps running and the track shows on the now-playing card and the home mini-player. |
| **Screen mirroring** | Control Center → Screen Mirroring | Full-screen on the Portal. |
| **AirPlay video** | Play a video after mirroring (YouTube, TV, …) | The sender hands over a stream URL instead of mirroring it. Noticeably better quality, and the phone is free to do something else. |

The mirroring → video handover happens mid-session and is meant to be invisible; see
`AirPlayActivity` for why that needs one persistent surface rather than two.

## The name it advertises

Whatever the Portal's **device name** is — the same one the phone remote and Home Assistant show.
There is deliberately no separate AirPlay name to drift. Rename the Portal from the phone remote
and the AirPlay entry follows immediately (it re-registers mDNS).

## Settings

All of them render on-device *and* on the phone remote from one definition (the `airplay` settings
domain):

- **Stream from your iPhone** — the master toggle.
- **Allow screen mirroring** — off leaves an audio-only speaker.
- **Show the screen when casting starts** — bring the cast to the front automatically. Audio-only
  streams never interrupt the screensaver either way.
- **Ask for a code** — show a PIN on the Portal that must be typed into the phone.
- **Mirroring resolution** — set a fixed size instead of matching the Portal, if a sender
  negotiates badly with the panel.

!!! note "Gen-2 Portal: the decoder can hiccup during YouTube ads (recovers on its own)"

    A YouTube pre-roll ad makes the sender restart the video decoder several times in a few seconds
    (mirror → video → mirror, repeatedly). On a gen-2 Portal (`omni`) the Qualcomm decoder
    occasionally faults under that churn:

    ```
    OMX-VDEC-1080P: OMX_COMPONENT_GENERATE_HARDWARE_ERROR
    ACodec [OMX.qcom.video.decoder.{avc|hevc}] ERROR(0x80001009)
    VideoRenderer: Codec error, resetting
    ```

    `VideoRenderer` catches it and resets, so it self-heals — in testing the transition still
    completed and the picture came back. It is **not codec-specific**: it was reproduced on the
    H.264 (`avc`) decoder with H.265 turned off, so turning H.265 off does *not* prevent it. It is a
    decoder-level quirk of the gen-2 silicon under rapid restart, not something the receiver
    controls. A gen-1 Portal+ (`aloha`) has not shown it. Upstream PR #22 (robust video session-end
    detection) is the most relevant fix to watch.

Changing any of them restarts the receiver in place (~100 ms), which drops a session in progress.

## Audio and multi-room

Multi-room (Snapcast) and an AirPlay audio session both want the speakers, so the live AirPlay
session takes them for its duration: the multi-room now-playing relay stops and Immortal holds
audio focus. Both are handed back when the session ends.

## Notes and limits

- **arm64 only.** The native library is built for `arm64-v8a`, which is every Portal. On anything
  else the feature hides itself rather than offering something that cannot start.
- **Ports.** The receiver listens on **7000**. Don't run a second AirPlay receiver app alongside it.
- **After a reboot** it comes back on its own (`BootReceiver`). Installing and immediately rebooting
  can miss it — the package is still being optimised when `BOOT_COMPLETED` goes out; a second
  reboot with the package settled works.
- **APK cost** ≈ 8 MB (the native stack, statically linked).

## Licensing — read before releasing

The receiver is vendored from [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
and links **GPL-3.0** code that is not optional (the FairPlay handshake). Any APK bundling it is
GPL-3.0 as a whole, while Immortal is MIT. **Do not cut a release containing the `:airplay` module
until that is resolved** — the `TODO(licensing)` marker at the top of `airplay/build.gradle.kts` is
the tripwire. Dev and debug builds only.

## Source

`AirPlayConfig` (settings), `AirPlayControl` (runtime — when to raise a surface, who owns the
speakers), `AirPlayActivity` (the cast surface), `AirPlayPairActivity` (the how-to-connect screen),
and the `airplay` domain in `SettingsDomains.kt`. The receiver itself is the `:airplay` module —
read [`airplay/UPSTREAM.md`](https://github.com/starbright-lab/immortal/blob/main/airplay/UPSTREAM.md)
before touching anything under `airplay/src/main/`.
