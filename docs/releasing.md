# Releasing

Immortal is hosted from its own GitHub repo. Two files drive what devices see.

## `version.json` — the self-update manifest

Immortal polls
[`version.json`](https://github.com/starbrightlab/immortal/blob/main/version.json); when it
advertises a higher `versionCode`, the device downloads and installs the new build over itself
(`UpdateManager`). No cable, no laptop.

To cut a release: bump `versionCode` / `versionName`, build a signed release, and attach it as
`immortal.apk` to a GitHub Release. Devices update on their next check.

!!! danger "The release asset **must** be named `immortal.apk`"
    The manifest's `apkUrl` (and the store catalog) point at the stable
    `releases/latest/download/immortal.apk`. If a release attaches only a versioned name, that
    URL 404s and **breaks self-update for every device.** Use
    [`scripts/publish-release.sh`](https://github.com/starbrightlab/immortal/blob/main/scripts/publish-release.sh)
    `<tag> <signed.apk>` to upload the asset under both the stable and versioned names and verify
    the URL resolves.

## `catalog.json` — the app-store catalog

The [App Store](features/app-store.md) reads
[`catalog.json`](https://github.com/starbrightlab/immortal/blob/main/catalog.json). Edit and
commit; clients pick it up on next open (a bundled copy ships as the offline fallback). Every
PR that touches it is validated by CI.

## Signing

Release builds must be signed with the **same** key every time — in-place self-update is
signature-checked, so a different key means devices can no longer update.

Signing is configured via `keystore.properties`, which the build looks for first at the repo
root (git-ignored) and then at `~/.immortal-signing/keystore.properties` — the recommended home,
since nothing in a git working tree can be considered safe from cleanup.

!!! warning "Back up the signing key"
    Keep the key backed up safely (e.g. iCloud). **Losing it means devices can no longer
    self-update.** CI guards against publishing an *unsigned* release, but it can't recover a
    lost key.
