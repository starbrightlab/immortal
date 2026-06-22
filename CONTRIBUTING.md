# Contributing to Immortal

Thanks for helping give discontinued Meta Portal devices a second life. Contributions
of all kinds are welcome — code, bug reports, app submissions, docs, and testing on
models we haven't covered yet.

By participating you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to contribute

- **Report a bug** — open a [Bug report](../../issues/new?template=bug_report.yml).
  Tell us your Portal model and Android version (Settings -> About); it matters a lot,
  especially for the Gen-1 Portal+ (Android 9).
- **Submit an app for the store** — use the
  [App submission](../../issues/new?template=app-submission.yml) form. See
  [SUBMISSIONS.md](SUBMISSIONS.md) for what makes a good Portal app.
- **Test on a model we haven't confirmed** — Portal Go and the Portal+ (Gen-1) are
  verified; Mini / gen-2 are expected to work but unconfirmed, and Portal TV is
  experimental (no D-pad navigation yet). Reports from any of these are valuable.
- **Send a pull request** — see below.

## Development

Immortal is a single Android app (Jetpack Compose, minSdk 24, targetSdk 36).

    ./gradlew :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n com.immortal.launcher/.HomeActivity

The debug build uses a `.debug` application-id suffix so it installs alongside a
provisioned release. The provisioning kit lives in [`provisioning/`](provisioning/).

To iterate against **real release conditions** (home role, device admin, self-update
signature checks) without the install friction, build the **`dev`** variant:
`./gradlew :app:assembleDev`. It is the release build plus `debuggable = true` — same
application-id, same signing key, minify off — so it provisions identically, but
`adb install -r -d` can freely replace or *downgrade* it. That sidesteps the two walls a pure
release build hits on a dev device: a feature branch is always a lower `versionCode`
(downgrade-blocked), and the device admin blocks `adb uninstall`. To set up a dev unit, drop the
dev APK in `provisioning/apks/` and run provisioning as usual, then iterate with
`adb install -r -d`. Validate the true `release` artifact before shipping.

A few things worth knowing:

- **Release builds must be signed with the same key every time** — in-place
  self-update is signature-checked. Signing is configured via
  `keystore.properties`, read from the repo root (git-ignored) or
  `~/.immortal-signing/` (preferred — outside the working tree); never commit a
  keystore.
- **The Gen-1 Portal+ has a broken system installer.** On-device installs route
  through the shell-privileged daemon the kit starts; read the README's first-gen
  section and the comments in `InstallDaemon` / `ApkInstallActivity` / the
  provisioning scripts before changing anything there.

## Pull requests

1. Fork and create a branch from `main`.
2. Keep changes focused and match the existing style.
3. If you changed install or provisioning behavior, test it on a real Portal and say
   which model in the PR.
4. Open the PR with a clear description of what and why.

## License

By contributing, you agree that your contributions are licensed under the
[MIT License](LICENSE) that covers this project.

## Trademark

Immortal is an independent project and is **not affiliated with, endorsed by, or
sponsored by Meta**. "Meta Portal" and "Portal" are trademarks of Meta Platforms,
Inc., used here only to identify compatible hardware. See [DISCLAIMER.md](DISCLAIMER.md).
