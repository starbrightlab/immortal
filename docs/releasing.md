# Releasing

Immortal is hosted from its own GitHub repo. Two files drive what devices see.

## Cutting a release (the one safe command)

```sh
scripts/cut-release.sh <versionName> "<release notes>"
# e.g.
scripts/cut-release.sh 1.44 "Adds the home-header remote button and a tidier remote layout."
```

That single command does the whole release and **fails fast at every gate**, so the two
things that historically broke releases — forgetting the `version.json` bump, and misnaming
the APK — can't happen:

1. **Preflight** — `gh` authenticated; a clean tree on an up-to-date `main`; the signing key
   present; Android build-tools found; the tag still free.
2. **Bumps `versionCode` (auto, current + 1) / `versionName` / `notes` in both
   `app/build.gradle.kts` and `version.json` together**, then re-checks they agree.
3. **Builds the signed APK and verifies it** — `aapt` confirms the APK's version matches what
   was just written (no stale build); `apksigner` confirms it's signed with the **same key as
   the currently-published `immortal.apk`** (a different key silently breaks self-update).
4. **Builds `portal-kit.zip`** from the committed `provisioning/` tree (`git archive` — only
   tracked files, never local junk or secrets).
5. Commits, tags, pushes; creates the Release as a **draft**, uploads exactly **`immortal.apk`
   + `portal-kit.zip`**, then publishes (drafts aren't "latest", so `latest/download` keeps
   resolving to the previous release until the new assets are in place).
6. Verifies both `latest/download/` URLs resolve **and** the published `immortal.apk` reports
   the `versionCode` shipped.

Requires `gh` (write access), the Android SDK build-tools, and `keystore.properties` (see
[Signing](#signing)). A release attaches exactly two assets: `immortal.apk` and
`portal-kit.zip` — nothing else, no versioned APK copies.

!!! note "Drift is blocked in CI too"
    [`release-guard.yml`](https://github.com/starbrightlab/immortal/blob/main/.github/workflows/release-guard.yml)
    runs `scripts/check-version-sync.sh` on every change to `version.json` or
    `app/build.gradle.kts`: if their `versionCode`/`versionName` disagree (or `apkUrl` isn't
    the stable URL, or `notes` is empty), the check fails before it can reach a release.

## `version.json` — the self-update manifest

Immortal polls
[`version.json`](https://github.com/starbrightlab/immortal/blob/main/version.json); when it
advertises a higher `versionCode`, the device downloads and installs the new build over itself
(`UpdateManager`). No cable, no laptop.

It advertises the build by `versionCode`, points devices at the stable
`releases/latest/download/immortal.apk`, and carries the release `notes`. Don't hand-edit it to
cut a release — [`cut-release.sh`](#cutting-a-release-the-one-safe-command) writes it (in lockstep
with gradle) and the release guard keeps it honest.

!!! danger "The release asset **must** be named `immortal.apk`"
    The manifest's `apkUrl` (and the store catalog) point at the stable
    `releases/latest/download/immortal.apk`. If a release attaches only a versioned name, that
    URL 404s and **breaks self-update for every device** — which is exactly what happened once
    (a release shipped only `immortal-1.42.apk`). `cut-release.sh` always uploads the asset as
    `immortal.apk` and then verifies the URL resolves, so this can't recur.

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
