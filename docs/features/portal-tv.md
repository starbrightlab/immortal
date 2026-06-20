# Portal TV

The **Portal TV** is a remote-driven Portal with no touchscreen. Immortal supports it fully —
the whole UI is driveable with the TV remote.

## Remote / D-pad navigation

`TvFocus` provides full remote/D-pad navigation across the entire UI: the home grid, folders,
the [App Store](app-store.md), and [screensaver](screensaver.md) settings all navigate with the
D-pad. There's nothing touch-only that you can't reach with the remote.

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
