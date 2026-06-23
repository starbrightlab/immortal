# AGENTS.md

Guidance for AI coding agents working in the Immortal repository. This is the canonical
agent guide; tool-specific files (e.g. `CLAUDE.md`) just point here. Human contributors
should read [`CONTRIBUTING.md`](CONTRIBUTING.md) — this file is a superset aimed at agents.

## What this project is

Immortal is a custom home-screen layer (launcher + screensaver + app store + fleet tooling)
for discontinued Meta Portal devices. It is a **single Android app**, package
`com.immortal.launcher`, Jetpack Compose, Kotlin, `minSdk 24` / `targetSdk 36`, targeting
Portal hardware on Android 9 (API 28) and Android 10 (API 29), arm64, **no Google services**.
See [`README.md`](README.md) for the full feature tour.

## Build, test, and validate

Always run the relevant check below after a change and fix failures before finishing.

| Task | Command |
|------|---------|
| Build the app (debug) | `./gradlew :app:assembleDebug` |
| Run unit tests (CI gate) | `./gradlew :app:testDebugUnitTest --no-daemon` |
| Install to a connected Portal | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Launch | `adb shell am start -n com.immortal.launcher/.HomeActivity` |
| Validate the store catalog | `python3 scripts/validate_catalog.py --network` |
| Lint provisioning scripts | `bash -n provisioning/provision.sh` |
| Build the fleet CLI | `cd provisioning && rustc -O fleet.rs -o fleetctl` |

CI workflows live in [`.github/workflows/`](.github/workflows/): `tests.yml` (unit tests),
`catalog.yml` (catalog schema + network checks), `provisioning.yml` (bash/PowerShell parse +
ASCII guard), `docs.yml`, `release-guard.yml`.

The debug build uses a `.debug` application-id suffix so it installs alongside a provisioned
release.

## Repository layout

```
app/                         The Android app (single module)
  src/main/java/com/immortal/launcher/   ~99 Kotlin files, flat package, grouped by name prefix
  src/main/assets/           bundled catalog.json fallback, clock faces, fonts, fallback photos
  src/main/res/              Compose theme lives in .../launcher/ui/theme/
  src/test/java/             unit tests
  build.gradle.kts           app build config (signing via keystore.properties)
provisioning/                Provisioning kit + the fleet CLI
  provision.sh / provision.ps1   one-double-click device setup (macOS/Linux, Windows)
  fleet.rs                   source of the fleet CLI (std-lib-only Rust, no crates)
  fleetctl                   prebuilt fleet CLI binary
  fleet-backup.sh / fleet-restore.sh   snapshot/restore a Portal's app data
  fleet/<serial>.json        device registry — SECRETS, git-ignored, never commit
  config.env                 provisioning options
docs/                        MkDocs site; docs/features/*.md and docs/design/*.md
scripts/                     validate_catalog.py, cut-release.sh, check-version-sync.sh, …
catalog.json                 hosted app-store catalog (schema v2)
version.json                 self-update manifest (versionCode/versionName + apkUrl)
skills/                      portable agent skills, Agent Skills SKILL.md format (see below)
```

### Finding code by feature

The Kotlin package is flat; files are grouped by name prefix:

- **Launcher / home grid:** `HomeActivity`, `UserLayout`, `QuickBar*`, `AppSwitcherActivity`
- **Screensaver / photo frame:** `PhotoDreamService`, `PhotoFrameController`, `Screensaver*`,
  `Face*` / `ClockFaces` / `FlipWebClockFaceView`, `DreamPolicy`, `PresenceState`, `AntiBurnIn`
- **Photo sources:** `LocalMedia`, `ImmichSource`, `SmbSource`, `DavSource`, `RemoteAlbum`,
  `Weather`, `CalendarFeed`
- **App store / install:** `StoreActivity`, `StoreCatalog`, `UpdateManager`, `InstallDaemon`,
  `HeadlessInstaller`, `ApkInstallActivity`, `ApkBrowserActivity`, `InstallConfirmService`
- **Fleet agent (on-device HTTP API):** `FleetAgentService`, `FleetHttpServer`, `FleetRoutes`,
  `FleetConfig`, `FleetFs`, `FleetDiag`, `FleetCalendar`, `FleetScreensaver`
- **Multi-room audio / now playing:** `MultiRoom*`, `NowPlaying*`, `Snapcast*`, `Ma*`,
  `MediaSession*`, `MediaNotificationListenerService`
- **Smart home (MQTT):** `Mqtt*`
- **Remote / Portal TV:** `Remote*`, `TvFocus`
- **Boot / lifecycle:** `ImmortalApp`, `BootReceiver`, `BootLaunch`, `Sleep*`, `ScreenControl`
- **Settings (the registry — read [Settings infrastructure](#settings-infrastructure) before
  touching any setting):** `settings/SettingSpec`, `settings/SettingsDomain` + `SettingsDomains`,
  `settings/SettingsRegistry`, `SettingsRenderer` + `SettingsComponents` (on-device UI), the
  `*Config` SharedPreferences objects, `FleetScreensaver` / `FleetCalendar` (wire façades)

When in doubt, search by symbol rather than guessing the file.

## Settings infrastructure

Every user-facing setting flows through one **declarative registry** in
`com.immortal.launcher.settings`. A setting is defined **once** as a `SettingSpec`, and that single
definition drives three consumers automatically:

1. **Persistence** — the existing `*Config` SharedPreferences objects (the spec binds to their
   getter/setter; storage is untouched).
2. **On-device UI** — `SettingsRenderer.SettingsList(domain, …)` renders the specs as Compose rows.
3. **Phone remote** — the `/remote/settings` schema + the generic PWA renderer in `RemoteHtml`.

The domains live in `SettingsDomains.kt`: `screensaver`, `calendar`, `immortal`, `mqtt`, `quickbar`.

### Adding or changing a setting — the rules

- **Add a `SettingSpec`; never read/write prefs directly from UI or routes.** Put a `BoolSpec` /
  `IntSpec` / `EnumSpec` / `StringSpec` in the right `SettingsDomain`, bound to the `*Config`
  getter/setter — it then appears on-device **and** on the remote with no extra code. Touching
  `SharedPreferences` straight from a Compose screen or an HTTP route is the anti-pattern: it
  silently skips the remote, the validation, and the side-effect hook.
- **Constraints belong on the spec** (`IntSpec` min/max/step/wrap, `EnumSpec` options/coerce). The
  apply boundary **validates and rejects** out-of-range / wrong-typed input, so don't re-clamp in
  the UI and don't rely on the setter to mask a bad value.
- **Side effects go in the domain's `onApplied`** (screensaver reaffirm, `MqttService.sync`,
  status-bar re-apply, …), not at the call site — the on-device and remote paths funnel through it
  so it can't drift. Apply is `synchronized` per domain.
- **Gating is declarative:** `visible = { ctx, snapshot -> … }` hides a spec on both surfaces.
- **A persisted field with no spec fails the build.** `SettingsDomainTest` has per-domain
  completeness tripwires (e.g. `immortalRegistry_coversEveryPersistedField`) — add the spec, or add
  the field to the explicit exclusion set, or the test goes red. Deliberate gate.

### Sub-screens (pickers, connect forms, complex editors)

Anything too rich for a generic row — a clock-face picker, a photo-source credential form, a
world-clock editor — is its **own `Activity`**, reached from a **`NavSpec`** in the domain (it
renders as a nav row). **Do not** add an in-file `var showX by mutableStateOf(false)` sub-screen
toggle: that was the old, second navigation model and it has been removed. One nav model — Activities.

### Testing

`SettingsDomainTest` (pure JVM, mockito `Context` — no Robolectric) covers apply / validation /
`onApplied` / schema per domain. Keep new domains and fields covered by those patterns; on-device
behaviour is verified manually.

### Known edges

- A domain needs an **immutable `Settings` snapshot** to render through `SettingsList` (with a
  `Context`-as-snapshot domain the on-device toggle won't recompose). `mqtt` / multi-room keep
  bespoke on-device screens for live connection status — their specs still drive the remote, and a
  spec-key pin test guards them from drifting.
- Photo-source credentials (Immich / SMB / WebDAV) are atomic multi-field groups that live in their
  own connect Activities + `FleetScreensaver` (the registry models scalars; a `GroupSpec` is their
  planned home).

## Fleet management and the `fleetctl` skill

A wall of Portals is managed over WiFi with the **`fleetctl`** CLI, which drives each device's
in-app Fleet Agent HTTP API. The full workflow is captured as a portable agent skill:

- **Skill:** [`skills/immortal-fleet/SKILL.md`](skills/immortal-fleet/SKILL.md)
- **Format:** the open [Agent Skills](https://agents.md) `SKILL.md` convention — YAML
  frontmatter (`name` + `description`) plus a Markdown body — which is read by Claude Code,
  Cursor, GitHub Copilot, Codex CLI, Gemini CLI, Windsurf, OpenCode, and other agents. Tools
  that auto-discover skills from their own directory (e.g. `.claude/skills/`, `.kiro/skills/`)
  can symlink or copy this folder; nothing here is tool-specific.
- **Use it when** deploying/installing/updating apps on Portals, pushing config, managing the
  screensaver or calendar over the air, iterating on a local build (`dev update`), browsing
  device files, or reading logcat/diagnostics across one or many devices.
- **Source / docs:** `provisioning/fleet.rs`, [`docs/features/fleet.md`](docs/features/fleet.md),
  and the Fleet sections of [`provisioning/README.md`](provisioning/README.md).

Quick reference (run from `provisioning/`):

```bash
./fleetctl devices                       # list registered Portals
./fleetctl info --device all             # identity/version/state for every device
./fleetctl install <pkg> --device all    # roll an app to the whole fleet
./fleetctl update --check                # dry-run available updates
./fleetctl dev update <app.apk> --device "<name>"   # install a local build (same signing key!)
```

## Conventions and hard rules

- **Match existing style.** Keep changes focused; prefer editing over rewrites. Reuse existing
  subsystems (the Fleet routes, for example, are a pure consumer of the catalog/installer/
  settings — don't duplicate that logic).
- **Copyright headers: never use the Meta Platforms template.** Many AI tools default to emitting
  `Copyright (c) Meta Platforms, Inc. and affiliates.` (a holdover from the original template this
  repo was derived from). **Do not** add that line to any file. New source files use exactly:
  ```
  /*
   * Copyright (c) 2026 Starbright Lab.
   *
   * This source code is licensed under the MIT license found in the
   * LICENSE file in the root directory of this source tree.
   */
  ```
  Immortal is **not affiliated with Meta** (see Trademark / scope below), so a Meta copyright line
  is both wrong and misleading. If you copy an existing file as a starting point, fix the header.
- **Release builds must be signed with the same key every time** — in-place self-update and
  `dev update` are signature-checked by Android. Signing comes from `keystore.properties` (repo
  root, git-ignored) or `~/.immortal-signing/` (preferred). **Never commit a keystore.**
- **Secrets:** `provisioning/fleet/<serial>.json` holds per-device agent tokens and is
  git-ignored. Never print token values or commit that folder. The repo is public.
- **Windows-executed scripts must be pure ASCII** (`provision.ps1`, `*.bat`) — Windows
  PowerShell 5.1 mis-decodes non-ASCII bytes and breaks parsing. CI enforces this. Use `-`
  instead of `—`. `provision.sh` is exempt (macOS/Linux, UTF-8).
- **Gen-1 Portal+ installer is broken** (white-on-white system dialog). On-device installs route
  through the shell-privileged daemon the kit starts — read the README's first-gen section and
  the comments in `InstallDaemon` / `ApkInstallActivity` / the provisioning scripts before
  touching that path.
- **Catalog changes** (`catalog.json`) must pass `scripts/validate_catalog.py`; keep the bundled
  fallback at `app/src/main/assets/catalog.json` in mind.
- **Releases:** the self-update asset must be named `immortal.apk`; bump `version.json`. See
  [`docs/releasing.md`](docs/releasing.md) and `scripts/cut-release.sh`.

## Hardware limits (don't try to "fix" these)

No Google Play Services; bootloader can't be unlocked (no root); USB-C thumb drives don't mount
reliably. See [`docs/limitations.md`](docs/limitations.md). These are firmware facts, not bugs.

## Trademark / scope

Immortal is an independent community project, **not affiliated with or endorsed by Meta**.
See [`DISCLAIMER.md`](DISCLAIMER.md).
