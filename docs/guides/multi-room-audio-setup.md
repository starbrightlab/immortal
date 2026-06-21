# Setting up multi-room audio

This is the end-to-end walkthrough for synced, whole-home music across your Portals. For what the
feature does and how the pieces fit, see [Multi-room audio & now-playing](../features/multi-room-audio.md)
and the [design notes](../design/multi-room-audio.md).

## How it fits together

```
Music Assistant (server, somewhere on your network)
   └── snapserver (built-in)  ──audio, time-synced──►  Snapcast app on each Portal
                                                        └── Immortal relays now-playing
                                                            + forwards play/pause/skip to MA
```

- **Music Assistant** is the brain: your library and streaming sources, the room/group UI, and a
  built-in **snapserver**.
- The **Snapcast app** on each Portal plays the synced audio.
- **Immortal** reads the playing group off the snapserver and shows now-playing (and transport
  controls) on every Portal in the group.

You need one always-on machine for Music Assistant, plus each Portal you want as a speaker. No
Google services, no root — within the Portal's [hard limits](../limitations.md).

## Step 1 — Run Music Assistant as a server

Pick wherever you keep always-on home services:

- **Running Home Assistant?** Install the **Music Assistant Server** add-on
  (Settings → Add-ons → Add-on Store). This is the simplest route if you already have HA.
- **Otherwise**, run Music Assistant in **Docker** on a NAS or a small always-on box.

New to it? Start at [music-assistant.io](https://music-assistant.io/). Add at least one music
provider and confirm you can play something from the Music Assistant UI before moving on. Music
Assistant's built-in snapserver is what the Portals will connect to.

## Step 2 — Install Snapcast on each Portal

1. On the Portal, open the **App Store** and install **Snapcast**.
2. Open Snapcast and point it at your **Music Assistant server's address** (the snapserver).
3. Join the Portal to the **same Snapcast group** as your other rooms.

!!! tip "Make rooms rejoin after a reboot"
    So a room comes back on its own after a power blip, add the player to
    **Immortal → Settings → Start on boot** (the provisioning kit's `BOOT_APPS` does this by
    default for the Music Assistant player). See [Provisioning](../provisioning.md).

## Step 3 — Connect Immortal

On each Portal, go to **Immortal → Settings → Multi-room audio** (this row appears once Snapcast is
installed) and follow the in-app guide:

1. **Turn on the toggle.**
2. Enter your **Music Assistant / Snapcast server IP**.
3. *(Optional)* Enter your **Music Assistant username and password** and tap **Sign in**.

The relay status shows **Connecting… → Connected**, and a green check next to the sign-in confirms
the Music Assistant login.

### Do I need the Music Assistant login?

| With sign-in | Without sign-in |
| --- | --- |
| Forwards **play/pause/skip** to Music Assistant | Now-playing for **library/radio** still works |
| Shows now-playing for **AirPlay** sources (which don't carry it over Snapcast) | AirPlay metadata won't appear |

So you can skip it for a view-only now-playing card, but add it for transport controls and AirPlay
metadata.

## Result

Once connected, the track playing in a Portal's group — title, artist, cover art — appears on that
Portal's **now-playing card** and its system media controls, on **every** Portal in the group, with
working play/pause/skip. Cast via AirPlay into a group and (with sign-in) that shows up too.

## Troubleshooting

- **The Multi-room row is missing** — install Snapcast from the App Store first.
- **"Music Assistant app isn't open on this Portal"** — start the player; use *Start on boot* so it
  stays up.
- **No now-playing** — check the server IP, that the Portal is in the right Snapcast group, and that
  something is actually playing to that group from Music Assistant.
- **No transport controls / no AirPlay metadata** — add the Music Assistant login (Step 3) and
  confirm the green check.
