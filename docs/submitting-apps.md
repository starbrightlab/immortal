# Submitting an app

The Immortal [App Store](features/app-store.md) is open to the community. If you've built (or
know of) an app that runs well on Meta Portal, you can have it listed so every Immortal user
can find and install it.

!!! tip "Canonical reference"
    The exact catalog schema, the CI checks, and the trust-and-safety policy live in
    [`SUBMISSIONS.md`](https://github.com/starbrightlab/immortal/blob/main/SUBMISSIONS.md).
    This page is the short version.

## What can be listed

- **Your own Portal app** — hosted as an APK on a stable URL (e.g. a GitHub Release), built for
  Portal (Android 10 / API 29, arm64, no Google Mobile Services).
- **An existing open-source app** that already runs on Portal — typically from **F-Droid**
  (these are resolved live so they never go stale).

## How to submit

1. Open a **new issue** with the **"App submission"** template, **or**
2. Open a **pull request** adding your entry to
   [`catalog.json`](https://github.com/starbrightlab/immortal/blob/main/catalog.json).

A maintainer reviews and merges it. Every PR that touches `catalog.json` is CI-validated:
schema shape, duplicate packages, https URLs, the F-Droid id resolving, and the icon and APK
URLs actually serving. Run the same check locally:

```bash
python3 scripts/validate_catalog.py --network
```

## Compatibility notes

- The store shows a "Needs Android X+" badge (and disables install) below your `minSdk`.
  First-gen Portals and the Portal TV are **API 28**; the Go/Mini/gen-2 are **API 29** — so
  apps with `minSdk` 30+ can't run on any Portal.
- Multi-architecture apps should pin the **arm64-v8a** build with `versionCode`, since Portal
  is arm64.

## Building a Portal app

An app is just a normal Android app targeting API 29, arm64, no GMS, designed for a landscape
touchscreen. Meta's Portal sample app and "build apps for Portal" developer materials are the
best starting point. If it installs and runs on your Portal, it can be listed.
