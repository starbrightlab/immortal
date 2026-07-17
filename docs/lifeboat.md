# Side-by-side installs: Immortal + LIFEBOAT

Immortal can be installed **twice on the same Portal** — the release build and the debug
build have different Android package IDs, so they coexist. This is the "lifeboat" pattern:
while iterating on the launcher itself, a second, independent install keeps a working fleet
agent (and a way back in) if the build you're hacking on breaks or gets uninstalled.

## Which is which — read this before uninstalling anything

| | **Release** | **LIFEBOAT (debug)** |
|---|---|---|
| Label in Settings → Apps | **Immortal** | **Immortal LIFEBOAT** |
| Package ID | `com.immortal.launcher` | `com.immortal.launcher.debug` |
| Fleet agent port | **8723** | **8724** |
| Fleet token | its own | its own (they are **not** interchangeable) |
| Role | daily driver — launcher, screensaver, store | recovery channel + dev iteration |

**Rule of thumb: the plain "Immortal" is production — don't uninstall it casually.**
Uninstalling it wipes its app data: the fleet-agent config and token, and every
adb-granted extra (see [recovery](#recovery-reinstalling-a-deleted-release-build)).
The LIFEBOAT is the one that's safe to remove when you're done iterating.

If you're unsure which you're looking at, tap the entry in Settings → Apps and check the
package name at the bottom of the App-info screen, or query each agent:

```bash
cd provisioning
./fleetctl info --device star       # release, port 8723
./fleetctl info --device star-eye   # lifeboat, port 8724
```

Each agent's `/info` reports its own port — the port, not the label, is the ground truth.

## Deploying to a Portal without a cable

`scripts/fleet-deploy.sh` pushes and installs a locally-built APK through a fleet agent
over WiFi, using only `curl` + `jq` (no Rust toolchain needed, unlike `fleetctl`):

```bash
# Build, then deploy the release APK via the LIFEBOAT agent:
./gradlew assembleRelease
scripts/fleet-deploy.sh --to star-eye \
  --apk app/build/outputs/apk/release/app-release.apk
```

This is how you reinstall a deleted release build over WiFi. **It needs the install
confirm to succeed on-device**, which on a Gen-1 Portal means both of these are true:

- the [installer-overlay fix](first-gen-portals.md#the-broken-installer-dialog-and-the-fix)
  is applied (otherwise the confirm dialog renders blank white — invisible buttons), and
- either someone taps **Install** on the Portal, or `InstallConfirmService` is enabled as
  an accessibility service (`autoConfirm: true` in `/info`) so the agent taps it itself.

If neither holds, the install times out after ~2 minutes with `result: "failed"` and you
need the USB path below.

## Recovery: reinstalling a deleted release build

Uninstalling the release build loses state that lives outside the APK. A fresh install
restores the app but **not**:

- the fleet agent config + token (the old registry entry in `provisioning/fleet/` is dead),
- `WRITE_SECURE_SETTINGS`, `READ_LOGS`, storage grants, device admin (screen-off),
- `SYSTEM_ALERT_WINDOW` / `REQUEST_INSTALL_PACKAGES` / `GET_USAGE_STATS` appops,
- the `InstallConfirmService` accessibility enrolment (`autoConfirm`).

Full recovery, with the Portal on USB:

```bash
cd provisioning
adb install -r ../app/build/outputs/apk/release/app-release.apk
FLEET_NAME=star ./provision.sh --fleet   # re-enable agent, record fresh token
./provision.sh --overlay-fix             # re-assert the Gen-1 dialog fix
./provision.sh --grants                  # re-grant adb-only permissions + auto-confirm
```

Then verify: `./fleetctl info --device star` should show `dialogFixed: true`,
`autoConfirm: true`, `canWriteSecureSettings: true`, and a `logcat` capability.
