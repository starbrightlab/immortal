# Portal TV

The **Portal TV** is a remote-driven Portal with no touchscreen. Immortal supports it fully —
the whole UI is driveable with the TV remote.

## Remote / D-pad navigation

`TvFocus` provides full remote/D-pad navigation across the entire UI: the home grid, folders,
the [App Store](app-store.md), and [screensaver](screensaver.md) settings all navigate with the
D-pad. There's nothing touch-only that you can't reach with the remote.

## Remapping the remote's dead buttons

Four buttons on the Portal TV remote no longer do anything: **Netflix**, **Prime Video**,
**Watch**, and the **Voice** button (the mic-icon one Meta labels "voice input"). Those services
and the voice assistant are gone. Immortal can give them a job instead.

Nothing is remapped by default. The feature is off, and every button is set to "Nothing" until you
choose otherwise, so a fresh install leaves the remote behaving as it always has.

### Turning it on

1. Open **Settings → Immortal** and switch on **Remap remote app buttons**. Four button rows
   appear underneath it.
2. Android needs to let Immortal see the key presses. Go to **Android Settings → Accessibility →
   Immortal Remote Buttons** and turn it on, accepting the permission dialog. (Immortal's back
   gesture uses the same kind of permission, so this may be familiar.)
3. Back in **Settings → Immortal**, set each button to whatever you want:

    | Row | The button |
    |---|---|
    | Netflix button | Netflix |
    | Prime Video button | Prime Video |
    | Watch button | Facebook Watch |
    | Voice button | The mic-icon button |

Available actions: **Mute / unmute microphone**, **Home**, **Screensaver**, **Screen off**,
**Back**, or **Nothing** to leave the button alone.

The same settings appear on the [phone remote](remote.md), which is easier than picking values with
a D-pad.

### Muting a video call

**Mute / unmute microphone** is the action most people want here. It toggles the same microphone
mute the [Home Assistant integration](smart-home.md) exposes, and it works while a call app is in
the foreground, so one press mutes a Zoom or Messenger call without leaving the call screen. Home
Assistant sees the change too, so a dashboard or automation stays in step.

!!! note "The voice button is a slightly different case"
    The three app buttons send keycodes (`PROG_RED`, `PROG_GREEN`, `PROG_BLUE`) that nothing else
    on the Portal uses, so remapping them takes nothing away. The voice button reports as `SEARCH`,
    which an app could legitimately use for its own search. While it's set to "Nothing" the key
    passes straight through untouched; give it an action and apps stop receiving it.

### Turning it off

Set a button back to "Nothing" to release just that key, or switch off **Remap remote app buttons**
to release all four at once. Turning the accessibility service off in Android Settings also stops
it completely.

## Bridging to and from the stock home

- A **Calls** tile bridges to the TV's stock home (for the Portal TV's calling experience).
- An **Immortal** tile appears on that stock home, so you can hop back to Immortal.

## Same generation as the first-gen Portal+

The Portal TV is Android 9, the same generation as the original Portal+, so the same install
mechanics apply — including the broken-installer-dialog fix. See
[First-gen Portals](../first-gen-portals.md).

## Compatibility for app authors

The Portal TV is API 28. App listings can mark themselves TV-only (or touch-only) via the
catalog's `devices` field — see [Submitting an app](../submitting-apps.md).
