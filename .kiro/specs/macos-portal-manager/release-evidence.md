# Portal Manager Release Evidence

Status: incomplete. This record tracks the current candidate and the exact
validation that remains before release.

## Candidate

- Branch: `feature/portal-manager-hardening`
- Implementation head: `d438e84`.
- Standalone Portal Manager mirror head: `d468c55`.
- Base: `ae46e16` (`v1.73`)
- Android Release APK: `app/build/outputs/apk/release/app-release.apk`
  Built artifact SHA-256 at validation:
  `cff3fe28a849190f4af425bd18e5282d7e4885d79fe65661a61543efe1add4b9`
- Android Release packaging omits VCS HEAD metadata. APK signing remains
  nondeterministic because the configured RSA key uses APK Signature Scheme v2;
  verify the exact artifact by digest immediately before deployment.
- Signing certificate SHA-256:
  `a0bdd0ab4a888d8d9ca31e78fd065bd6273c0d740672352c52900316df7bbbb0`
- macOS signed/notarized artifact: pending operator signing identity and
  Apple Notary submission.

## Automated gates

- Android JVM unit tests: pass.
- Android signed Release build: pass.
- Android Release deployment preflight: pass (identity, signer, v2 scheme, and
  artifact digest).
- Android Release deployment preflight was rechecked at the current candidate;
  identity, signature scheme, certificate digest, and artifact digest all match.
- `fleetctl` Rust unit tests: pass (9 tests), including SHA-256 boundary vectors.
- Background Service Cargo tests: pass (8 tests total: 7 units plus one
  real-process lifecycle test, locked), including readiness, clean `SIGTERM`
  shutdown, and deterministic active-request drain before shutdown.
- Portal Manager scope check: pass.
- Portal Manager direct XCTest bridge: pass (190 tests), including fail-closed
  provisioning selection/targeting, release-report export, and release-verifier
  identity, Gatekeeper, notarization, and stapler checks.
- USB provisioning now resolves an explicit target from the entered serial:
  recovery requires exactly one registered match, full provisioning updates the
  exact match or creates a new target, and ambiguous serials fail closed.
- Sanitized machine-readable release reports export as sorted JSON; a new test
  verifies missing gates remain withheld while passed mandatory gates publish
  exactly the supported claims.
- Critical casting, Room Link, app-sync, and USB-provisioning controls now have
  explicit accessibility labels, values, hints, or stable identifiers.
- Health checks invalidate their operation-local URL sessions, preventing
  session accumulation during startup and periodic readiness polling.
- Offline Release verification tool check: pass.
- Workflow YAML parse: pass.
- CI now includes macOS scope/build/tests, Rust tests, and Android unit tests.
  Upstream PR [starbrightlab/immortal#224](https://github.com/starbrightlab/immortal/pull/224)
  is clean and mergeable at implementation head `d438e84`; its
  `portal-manager`, `rust-tests`, `unit-tests`, and `version-sync` checks all
  pass in workflow runs `32969865429` and `32969865427`.
  Signed Release packaging remains a local
  gate because public runners do not have the protected signing key.

## Live Fleet baseline

Refreshed on 2026-08-26 over local LAN with authenticated Fleet requests.
mDNS found all three `_immortal-remote._tcp` services. ADB reported no USB
devices.

| Device alias | Result |
|---|---|
| Portal Mini A | Authenticated; API 29; Immortal 1.73 (67); install mode dialog; developer mode off; present. |
| Portal Mini B | Registered token rejected (`unauthorized`). |
| Portal Plus A | Authenticated; API 28; Immortal 1.60 (54); paused legacy installer; absent. |

Both authenticated devices returned `not_found` for `GET /apps/profile`, which
is the expected baseline because they have not received the candidate build.
The branch-built CLI produced the same result on 2026-08-26 for the API 29
Portal Mini and the API 28 authenticated Portal Plus.

Portal Mini also returned a successful sanitized diagnostics snapshot: the
root filesystem was 95% used and userdata was 48% used.

## Casting receiver inventory

On 2026-08-26, a read-only Bonjour refresh found two local computer receivers
under `_airplay._tcp` and three Google Home speakers plus one Cast group under
`_googlecast._tcp`. It found no television-class AirPlay receiver, so a real
AirPlay playback/stop cycle remains unavailable without another receiver.

On 2026-08-24, local Bonjour discovery found usable receiver candidates on
interface 14:

- AirPlay: one television receiver.
- Chromecast: one speaker receiver.

These are discovery targets only. They are not playback proof.

A 2026-08-25 read-only refresh again found the same Chromecast speaker, plus
additional Cast receivers and a Cast group. It did not find the previously
recorded television under `_airplay._tcp`; it found two computer receivers
instead. Receiver visibility changes do not provide playback proof, and no
connection or playback was started during discovery.

A 2026-08-26 release preflight reconfirmed the exact signed Android artifact.
No Portal deployment, developer-mode change, app-profile mutation, playback,
or other live mutation was attempted during this evidence refresh.

## Required live workflow evidence

1. Run `scripts/verify-android-release.sh` with version `67`, name `1.73`, the
   recorded certificate and APK digests, then deploy that exact APK to Portal
   Mini with explicit operator approval using `fleetctl dev update --sha256`.
2. Verify `/apps/profile` discovery, set/install, retry reporting, state
   transition, and profile removal.
3. Exercise credential sharing between authenticated Portals.
4. Exercise volume up/down/mute and media previous/play-pause/next.
5. Establish a Room Link session and capture both endpoints' sanitized state.
6. Connect and play/stop one real AirPlay target.
7. Connect and play/stop one real Chromecast target.
8. Build and verify a Developer ID signed, notarized, stapled Portal Manager
   artifact with `macos/package-release.sh`.

## Release blockers

- Upstream review and merge of PR #224 are required.
- Apple signing identity and authorized Notary submission are unavailable.
- Device deployment and mutation workflows require explicit operator approval.
