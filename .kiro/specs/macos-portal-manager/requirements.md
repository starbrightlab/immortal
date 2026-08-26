# Requirements Document

## Introduction

The macOS Portal Manager is a native SwiftUI/AppKit fleet dashboard and separate macOS Xcode project for Immortal-compatible Meta Portal hardware. The application has no Android/Kotlin runtime and consumes, without modifying, Android/Kotlin code, Fleet Agent routes or authentication, Settings Registry semantics, provisioning scripts, or Portal behavior. The application discovers and onboards Portals on the local subnet, connects to each Portal's existing Immortal Fleet Agent, exposes the existing user-facing settings contract, and performs approved individual or multi-Portal operations from a keyboard- and menu-driven desktop interface.

Version 1 is a LAN appliance manager. Portal control and Music Assistant/Snapcast management use local-network endpoints or a USB/ADB onboarding connection; version 1 has no cloud control plane, remote relay, or broad server-administration surface. Existing Fleet Agent routes for raw files, logs, arbitrary installs/updates, developer mode, and other escape-hatch behavior remain outside this product.

## Repository Alignment and Assumptions

- Immortal's Fleet Agent listens on port `8723` by default. The current Android contract authenticates `/info`, `/apps`, `/config`, `/calendar`, `/screensaver`, and `/action` with the Fleet bearer token, while `RemoteRoutes` handles `/remote/*` separately and accepts either the Fleet bearer token or a paired session token on authenticated remote routes. The Portal Manager will not change this Android contract in version one.
- The credential split is intentional: a verified bearer credential is required for identity/health and for `/screensaver`, `/calendar`, and `/action`; a remote-session credential is limited to the approved `/remote/settings` and `/remote/sources` operations. `/remote/pair` accepts a PIN in the request body and creates the remote-session credential; all other `/remote/*` routes remain excluded.
- Fleet Agent connections are accepted only from loopback, private/site-local, or link-local peers by the existing server. The Portal Manager applies one LAN policy to Portal, Music Assistant, Snapcast, discovery, manual endpoints, and reconnects, including post-resolution address validation.
- Immortal advertises `_immortal-remote._tcp.` through Android NSD/mDNS with a friendly name and agent port. Discovery metadata is not treated as proof of identity; a verified bearer `/info` response is required before a device becomes a fully managed, identity-verified Portal. A validated Manual-IP Endpoint can begin PIN pairing without an mDNS record.
- The repository provisioning kit has two distinct concerns: Fleet Agent Enablement/Recovery writes `provision.json`, relaunches Immortal, and reads `agent.json`; Full USB Provisioning installs/configures Immortal and may invoke enablement as a separately reported final step. The macOS app consumes those boundaries with an operator-supplied local ADB/platform-tools installation and local provisioning artifact; the app does not download platform-tools, APKs, packages, or release artifacts.
- A six-digit on-screen pairing PIN is short-lived, single-use, and exchanges through `/remote/pair` for a persistent session token. The fleet bearer token remains the direct laptop/API credential. The Portal Manager supports both paths without treating a remote session as a bearer credential.
- `GET /remote/settings` returns the declarative Settings Registry schema. The current registry contains the `screensaver`, `calendar`, `immortal`, `mqtt`, `quickbar`, `fleet`, `chime`, `digitalclock`, `welcome`, and `sunrise` domains. Source credentials and richer source editors use the existing `/remote/sources` surface rather than pretending that every on-device navigation row is a generic setting.
- `GET /remote/sources` can return legacy cleartext source fields including `immichKey`, `smbUser`, `smbPass`, `davUser`, and `davPass`. The Portal Manager treats those fields as sensitive response data, removes them before UI/state/log/export use, and stores a value only through an explicit per-Portal service Keychain migration or replacement flow.
- The Portal-side Music Assistant client uses a WebSocket at `/ws`, optional `auth/login` and `auth` messages, `players/all`, and `players/cmd/*`. The Portal-side Snapcast client uses the Snapcast JSON-RPC control socket, normally port `1705`, including `Server.GetStatus`. The repository currently reads player/group topology and sends transport commands but does not contain a group-mutation client; v1 group mutations are unavailable unless a concrete versioned service-specific contract, fixtures, capability evidence, and release gate are present.
- LAN-only means that the application data plane cannot target public or cloud endpoints. A local provisioning artifact may be selected by the operator; obtaining an artifact from an external service is outside the macOS application's data plane and is not a version-one dependency.

## Glossary

- **Portal Manager**: The native macOS SwiftUI/AppKit application specified by this document.
- **Portal**: An Immortal-compatible Meta Portal device running the Immortal Android application and, when managed over the network, the Fleet Agent.
- **Compatible Portal**: A Portal model that Immortal targets: the 2018 Portal, Portal+ including the first-generation Portal+, Portal Go, Portal Mini, Portal (gen-2), or Portal TV, using the repository's Android 9/API 28 or Android 10/API 29 device families.
- **Portal TV**: The remote/D-pad-driven Portal model without a touchscreen; the Fleet API still exposes its identity and configuration endpoints.
- **Fleet Agent**: Immortal's reboot-persistent foreground service that serves the Fleet API over the Portal's LAN address.
- **Fleet API**: The existing HTTP API served by the Fleet Agent, including separate bearer-authenticated direct routes and session-authenticated `/remote/*` routes.
- **LAN**: A loopback, IPv4 private (`10/8`, `172.16/12`, `192.168/16`), IPv4 link-local (`169.254/16`), IPv6 loopback (`::1`), IPv6 unique-local (`fc00::/7`), or IPv6 link-local (`fe80::/10`) path that is reachable without a public relay.
- **mDNS**: Multicast DNS service discovery used by Immortal's `_immortal-remote._tcp.` service advertisement.
- **Manual-IP Endpoint**: A Portal host address entered by the operator using `host[:port]` or bracketed IPv6 `[address]:port` syntax; the default Portal Agent port is `8723`.
- **Portal Registry**: The local managed-device roster containing non-secret identity and connection metadata plus references to Keychain credentials.
- **Portal Credential**: A credential associated with one Portal, either a Verified Bearer Credential or a Remote Session Credential.
- **Verified Bearer Credential**: A Fleet bearer token that has successfully authenticated `/info` for the associated Portal and is authorized for all approved direct Fleet routes.
- **Remote Session Credential**: A persistent token minted by `/remote/pair` from a valid Pairing PIN and authorized only for approved `/remote/settings` and `/remote/sources` operations.
- **Bearer Token**: The per-Portal secret sent in the HTTP `Authorization: Bearer <token>` header to direct Fleet routes and, where permitted, remote routes.
- **Pairing PIN**: The six-digit, on-screen, five-minute credential used once with `/remote/pair` to mint a Pairing Session.
- **Pairing Session**: The persistent token returned by successful PIN redemption; the Portal Manager treats the session as a Remote Session Credential, not as a Verified Bearer Credential.
- **ADB**: Android Debug Bridge, used over an authorized USB connection when Fleet Agent Enablement/Recovery or Full USB Provisioning requires device access.
- **Fleet Agent Enablement/Recovery**: A local ADB operation for an already installed Immortal application that writes the allowlisted `provision.json` handoff, relaunches Immortal, and reads the generated `agent.json` manifest without installing an APK or applying full device setup.
- **Full USB Provisioning**: The separate local ADB setup flow that installs/configures Immortal with a verified Local Artifact, applies required device setup, and then may run Fleet Agent Enablement/Recovery as a separately reported step.
- **Artifact Verification**: The pre-install checks for a Local Artifact: readable regular-file access, expected package identity, supported device/ABI/API compatibility, accepted signature policy, and a recorded SHA-256 digest.
- **Settings Registry**: Immortal's declarative registry of user-facing settings, their domains, typed controls, visibility rules, constraints, defaults, help text, side-effect semantics, and secret metadata.
- **Settings Domain**: One named group returned by the Settings Registry, such as `screensaver` or `immortal`, applied as a validated batch.
- **Settings Policy Classification**: An explicit Portal Manager policy label for a domain/control: approved editable, approved read-only, endpoint-bearing, credential-bearing, excluded, or unknown.
- **User-facing Setting**: A control represented by the Settings Registry or an approved existing source/calendar endpoint; raw preference keys and arbitrary configuration keys are not user-facing settings.
- **Approved Management Action**: A Portal operation allowed by this product: bearer-authenticated identity/health reads, read/apply User-facing Settings through an approved route, remote-session-limited settings/source operations, bearer-authenticated screensaver/calendar operations, and bearer-authenticated `identify` or `reaffirm`. App installation and update controls are excluded.
- **Source Credential**: A credential carried by a photo-source or calendar configuration, including `immichKey`, `smbUser`, `smbPass`, `davUser`, or `davPass`, plus any future source field classified as credential-bearing.
- **Music Assistant**: The local Music Assistant server whose WebSocket API supplies player identity, authentication, transport, and player/group state.
- **Snapcast**: The local synchronized-audio server whose control API supplies client, group, stream, and topology state.
- **Music Group**: A synchronized collection of Music Assistant players or Snapcast clients represented by a service-specific server topology.
- **Versioned Service Contract**: A documented, version-specific Music Assistant or Snapcast mutation request/response contract implemented by a typed adapter.
- **Mutation Evidence**: The fixture, capability response, or controlled-service result proving that a Versioned Service Contract and its operation semantics are supported.
- **Release Gate**: A mandatory validation condition that must pass before a v1 release can claim support for a capability or model.
- **Release Process**: The documented evaluation procedure that records Release Gate evidence and determines which version-one support claims are publishable.
- **Security Release Validation**: Evidence that credential matrix enforcement, Keychain-only secret handling, redaction, redirect rejection, route allowlisting, and unknown-settings safety pass.
- **LAN Release Validation**: Evidence that DNS-resolution checks, reconnect checks, IPv4/IPv6 parsing, public-address rejection, no-redirect behavior, and trust-warning scope pass.
- **Provisioning Release Validation**: Evidence that Fleet Agent Enablement/Recovery and Full USB Provisioning use local inputs, enforce Artifact Verification, recover and verify the bearer manifest, and perform no downloads from the macOS app.
- **Model Matrix Validation**: Evidence for each listed Compatible Portal family, Android API 28/API 29 behavior, and the release claims made for each family.
- **Portal TV Validation**: Evidence that discovery, bearer verification, approved management, provisioning/recovery, and the Native UI operate without touchscreen or D-pad input on the Portal.
- **Native UI**: The macOS desktop interface implemented with SwiftUI and AppKit rather than a browser-hosted remote page.
- **Keychain**: macOS secure credential storage used for Portal Credentials, Source Credentials, Music Assistant credentials, and Snapcast credentials.
- **Bulk Operation**: One explicitly confirmed action dispatched independently to multiple selected Portals.
- **Capability**: An endpoint or feature advertised by a verified bearer `/info` response or discovered through an approved, non-mutating API probe.
- **Connection State**: A managed Portal state such as discovered, pairing required, remote-session paired, bearer authenticated, provisioning, online, offline, unsupported, or error.
- **Sensitive Value**: A bearer token, remote session token, Pairing PIN, Source Credential, Music Assistant username/password/access token, Snapcast credential, or other value that can authorize access or identify a protected service account.
- **Local Artifact**: An APK or provisioning input selected from the Mac or attached local media for Full USB Provisioning.
- **Trust Warning Scope**: One non-secret acknowledgement keyed by service kind and normalized resolved LAN endpoint (host/address, port, and protocol); a changed resolved endpoint or service kind requires a new warning, and the acknowledgement never bypasses LAN validation.
- **Excluded Control**: Raw file browsing or transfer, logcat access, arbitrary/raw endpoint access, remote or ad hoc APK installation/update controls outside Full USB Provisioning, developer-mode toggles, reboot or shell access, cloud/remote relay, or broad operating-system/server administration.

## Requirements

### Requirement 1: Discover and register Portals on the local subnet

**User Story:** As a fleet operator, I want the Portal Manager to find Portals automatically and let me add a Portal by address, so that managed devices remain usable when discovery is unavailable or DHCP changes an address.

#### Acceptance Criteria

1. WHEN the Portal Manager starts discovery or the operator requests a refresh, THE Portal Manager SHALL browse `_immortal-remote._tcp.` on active LAN interfaces and show each resolved service name, host, port, and discovery source.
2. WHEN mDNS resolves a candidate service, THE Portal Manager SHALL apply LAN policy, send `/info` only with a supplied Verified Bearer Credential in the bearer `Authorization` header, send no `/info` request when that credential is absent, and mark the candidate as an identity-verified Portal only after the bearer-authenticated request succeeds.
3. WHEN the operator submits a Manual-IP Endpoint, THE Portal Manager SHALL parse `host[:port]` or bracketed IPv6 syntax, default an omitted port to `8723`, resolve the host, apply LAN policy to the resolved address, and label the candidate as manually added without requiring an mDNS record.
4. WHEN a validated Manual-IP Endpoint has a Pairing PIN but no Verified Bearer Credential, THE Portal Manager SHALL permit direct `/remote/pair` redemption without mDNS and SHALL retain a remote-session-paired state with only the `/remote/settings` and `/remote/sources` operation scope.
5. WHEN the operator supplies a Verified Bearer Credential for a discovered or manually added endpoint, THE Portal Manager SHALL verify `/info` before recording identity, health, or the endpoint as authenticated.
6. IF a discovered or manually entered host resolves to no LAN address, THEN THE Portal Manager SHALL reject the endpoint and explain that version-one management is LAN-only.
7. IF two discovery records resolve to the same Portal identity or the same host and port, THEN THE Portal Manager SHALL merge the records into one Portal Registry entry while retaining valid credential references and the newest authenticated address.
8. WHILE a registered Portal cannot be reached, THE Portal Manager SHALL retain the Portal Registry entry, show an offline Connection State with the last successful contact time, and keep the entry available for retry or address editing.

### Requirement 2: Classify hardware and capabilities across the supported Portal fleet

**User Story:** As a fleet operator, I want the dashboard to recognize every supported Portal model and its available endpoints, so that Portal TV and older first-generation devices receive accurate controls and compatibility warnings.

#### Acceptance Criteria

1. THE Portal Manager SHALL classify a Portal from authenticated `/info` fields including `name`, `model`, `device`, `apiLevel`, application version, IP address, agent port, and advertised capabilities.
2. THE Portal Manager SHALL recognize the 2018 Portal, Portal+, first-generation Portal+, Portal Go, Portal Mini, Portal (gen-2), and Portal TV as Compatible Portal model families and shall retain the raw model string for diagnostics.
3. WHEN a Portal reports Android API 28 or API 29, THE Portal Manager SHALL evaluate endpoint availability and capability flags independently of the model label.
4. WHEN authenticated `/info` data identifies a Portal as Portal TV, THE Portal Manager SHALL present the same Fleet API configuration workflow for that Portal without requiring a touchscreen interaction on the Portal, even when model-family recognition is unknown.
5. THE Portal Manager SHALL show a compatibility warning only for a detected unsupported API level or a missing capability required by the selected operation, SHALL leave the affected operation unavailable, and SHALL not show a warning solely because model-family recognition is unknown when no such incompatibility is detected.
6. IF a Portal lacks a required endpoint such as `/remote/settings`, `/remote/sources`, `/calendar`, or `/screensaver`, THEN THE Portal Manager SHALL show that capability as unavailable and shall not substitute an arbitrary `/config` write.

### Requirement 3: Enable or recover the Fleet Agent and complete separate local USB/ADB provisioning

**User Story:** As an operator setting up or recovering a Portal, I want separate Fleet Agent Enablement/Recovery and Full USB Provisioning flows, so that an existing Immortal installation can regain LAN management without an unnecessary package installation and a new device can be provisioned from a verified local artifact.

#### Acceptance Criteria

1. WHEN an Android device appears over USB, THE Portal Manager SHALL detect the device's ADB authorization state, serial, model, API level, installed Immortal state, and current Fleet Agent state before offering either local flow.
2. WHEN the operator starts Fleet Agent Enablement/Recovery for a device with an already installed compatible Immortal application, THE Portal Manager SHALL use only the allowlisted local ADB handoff, complete and verify both required handoff steps by writing `provision.json` before relaunching Immortal and reading the generated `agent.json` after relaunch, report each enablement/recovery step, and block the flow if either handoff step fails or is omitted, without installing an APK, applying Full USB Provisioning setup, downloading an artifact, or invoking an unallowlisted ADB action.
3. WHEN the operator starts Full USB Provisioning, THE Portal Manager SHALL require a Local Artifact and SHALL run the established device setup and Immortal installation flow as a separate operation before reporting Fleet Agent Enablement/Recovery as a distinct step.
4. WHEN the operator explicitly starts Full USB Provisioning and supplies a Local Artifact, THE Portal Manager SHALL verify readable regular-file access, expected package identity, supported device/ABI/API compatibility, accepted signature policy, and a recorded SHA-256 digest before installation, and SHALL block installation when Artifact Verification fails.
5. THE Portal Manager SHALL use an operator-provided or locally selected ADB/platform-tools executable and SHALL not download platform-tools, APKs, packages, release artifacts, or setup dependencies from the network.
6. WHEN Fleet Agent Enablement/Recovery or Full USB Provisioning produces an `agent.json` manifest, THE Portal Manager SHALL extract the non-secret name and port from the manifest, combine them with serial and model from the preflight ADB snapshot and the address from the admitted/resolved endpoint, store the bearer token only in the Keychain, and retain only a credential reference in the Portal Registry.
7. WHEN a Fleet Agent manifest is recovered and its resolved Portal address passes LAN policy, THE Portal Manager SHALL verify `/info` with the recovered Verified Bearer Credential and automatically mark the Portal online and enable LAN management upon successful verification.
8. IF ADB is unauthorized, disconnected, unavailable, or attached to an unsupported device, THEN THE Portal Manager SHALL show the blocking prerequisite, preserve the existing Portal Registry, and provide a retry path without claiming that either local flow succeeded.
9. IF an enablement, recovery, artifact, installation, setup, or verification step fails or times out, THEN THE Portal Manager SHALL identify the failed step, retain sanitized diagnostic status, and require successful bearer `/info` verification before enabling LAN management for that Portal.
10. WHEN the operator explicitly starts Full USB Provisioning and selects a Local Artifact, THE Portal Manager SHALL show the artifact name, digest, and Artifact Verification result before installation begins.

### Requirement 4: Authenticate with the existing PIN and bearer-token model

**User Story:** As a fleet operator, I want secure, device-specific authentication through the existing Fleet API, so that the desktop application can connect without inventing a second Portal identity system.

The Portal Manager SHALL implement this credential matrix without changing the Android contract:

| Fleet operation | Verified Bearer Credential | Remote Session Credential | Credential handling |
|---|---:|---:|---|
| `POST /remote/pair` | Not required | Not required | A validated Pairing PIN is sent in the JSON body; the response creates a Remote Session Credential. |
| `GET /info` | Required | Not permitted | A successful response verifies Portal identity and health. |
| `GET`/`POST /remote/settings` | Permitted | Permitted only with explicit approval of the exact route and operation | A Remote Session Credential remains limited to explicitly approved settings reads and applies. |
| `GET`/`POST /remote/sources` | Permitted | Permitted only with explicit approval of the exact route and operation | A Remote Session Credential remains limited to explicitly approved source reads and applies. |
| `GET`/`POST /screensaver` | Required | Not permitted | Full screensaver management remains bearer-only. |
| `GET`/`POST /calendar` | Required | Not permitted | Full calendar management remains bearer-only. |
| `POST /action` for `identify`/`reaffirm` | Required | Not permitted | Approved action management remains bearer-only. |
| Any other `/remote/*` route or direct Fleet route | Not available | Not available | The operation remains excluded from version one. |

#### Acceptance Criteria

1. WHEN the Portal Manager plans a Fleet operation, THE Portal Manager SHALL enforce the credential matrix, require explicit approval of the exact route and operation whenever a Remote Session Credential is selected, and reject that credential for `/info`, `/screensaver`, `/calendar`, `/action`, or any excluded route before sending a request.
2. WHEN the Portal Manager uses a Portal Credential on an approved route, THE Portal Manager SHALL send the credential in the `Authorization` header and SHALL not place a bearer or session token in a URL query, service name, discovery record, or redirect target.
3. WHEN the operator chooses PIN pairing after a LAN-validated discovery or Manual-IP Endpoint, THE Portal Manager SHALL accept the on-screen Pairing PIN, redeem the PIN exactly once through `/remote/pair`, and associate the returned Remote Session Credential with the intended Portal Registry entry.
4. WHEN PIN redemption succeeds, THE Portal Manager SHALL permit the resulting Remote Session Credential only for explicitly approved `/remote/settings` and `/remote/sources` route operations and SHALL not represent the session as verified Portal identity, verified health, or a bearer credential.
5. WHEN a bearer token is recovered through Fleet Agent Enablement/Recovery or entered by the operator, THE Portal Manager SHALL verify the token with `/info` before marking the Portal bearer-authenticated or enabling direct Fleet operations.
6. IF the Pairing PIN is wrong, expired, blank, or already redeemed, THEN THE Portal Manager SHALL show a pairing error, discard the entered PIN, and leave existing Portal Credential references unchanged.
7. IF an authenticated request returns `401` or the Fleet Agent reports a revoked credential, THEN THE Portal Manager SHALL mark the affected credential as reauthentication-required, retain non-secret identity metadata, and stop mutating requests that require the affected credential until replacement verification succeeds.
8. WHILE a Portal has multiple valid credential types, THE Portal Manager SHALL keep credential choice scoped to that Portal and SHALL show the active credential scope without displaying the credential value.
9. WHEN the operator removes a Portal from the Portal Registry, THE Portal Manager SHALL delete all associated Portal Credential references and Keychain items and SHALL remove the device from managed-operation selections.
10. THE Portal Manager SHALL not modify Android Fleet Agent authentication, promote a Remote Session Credential into a Verified Bearer Credential, or add a bearer fallback for a bearer-only route in version one.

### Requirement 5: Show status and restrict the Portal surface to approved management actions

**User Story:** As a fleet operator, I want a trustworthy overview and a small, deliberate management surface, so that the dashboard can operate a fleet without becoming a raw device shell or installer.

#### Acceptance Criteria

1. WHEN a Portal has a Verified Bearer Credential, THE Portal Manager SHALL show the authenticated `/info` identity, model, Android API level, Immortal version, agent address, reachability, presence/screen state when supplied, and capabilities relevant to approved operations.
2. WHILE a Portal has only a Remote Session Credential, THE Portal Manager SHALL show the limited remote-session state and SHALL not present `/info` identity/health, `/screensaver`, `/calendar`, or `/action` management as verified or available.
3. WHEN the operator selects a Portal or refreshes Portal status, THE Portal Manager SHALL request fresh bearer-authenticated status when identity/health is requested and SHALL show the response time or a connection error state within 10 seconds.
4. THE Portal Manager SHALL expose the Portal Fleet API only through the credential-matrix-approved identity/health read, Settings Registry application, approved source operations, bearer-only screensaver/calendar operations, and bearer-only `identify` and `reaffirm` Approved Management Actions.
5. WHEN the operator invokes `identify` or `reaffirm`, THE Portal Manager SHALL call the existing bearer-authenticated `/action` contract, show the per-Portal result, and preserve an error response without converting the error into a success state.
6. IF an operation targets an Excluded Control such as `/apps`, `/config`, `/fs/*`, `/logcat`, `/install`, `/update`, `/dev`, `/diag`, an arbitrary/raw route, reboot, shell access, or broad server administration, THEN THE Portal Manager SHALL show an explanation at the local interface, limit blocking to the excluded request at that interface, and allow the operation workflow to proceed after the explanation without sending the excluded request or changing the managed device.
7. IF a Portal returns an unsupported method, capability, conflict, or server error for an Approved Management Action, THEN THE Portal Manager SHALL show the HTTP/API error category and leave the last confirmed setting or action result visible.

### Requirement 6: Render and apply the existing user-facing Settings Registry

**User Story:** As a fleet operator, I want the dashboard to use the Portal's existing declarative settings schema, so that desktop controls remain aligned with on-device and phone-remote behavior without granting unreviewed future controls write access.

#### Acceptance Criteria

1. WHEN the Portal Manager reads `/remote/settings`, THE Portal Manager SHALL represent every returned Settings Domain and visible control using the returned identifier and schema metadata, while preserving unknown or future domains as read-only until an explicit Settings Policy Classification approves editing.
2. THE Portal Manager SHALL maintain an explicit Settings Policy Classification for every editable current domain and control, SHALL keep every endpoint-bearing or credential-bearing domain/control read-only until the classification explicitly approves its value handling, route, and bulk-operation behavior, and SHALL make the domain/control editable after the explicit classification approval subject to returned schema `readOnly`, visibility, and constraint metadata and credential protections.
3. THE Portal Manager SHALL recognize the current Settings Registry domain identifiers `screensaver`, `calendar`, `immortal`, `mqtt`, `quickbar`, `fleet`, `chime`, `digitalclock`, `welcome`, and `sunrise` when a Portal advertises those domains, while still requiring policy classification before editing.
4. WHEN a control schema contains an unknown control type, THE Portal Manager SHALL render a safe read-only value or redacted configured-state row, SHALL preserve the last confirmed state, and SHALL not crash, invent an editor, or submit the unknown control.
5. WHEN a known control schema is returned, THE Portal Manager SHALL honor the returned type, title, section, help text, current value, default, enum options, integer bounds/step/wrap metadata, `readOnly` flag, and visibility state.
6. WHILE a returned control is classified as credential-bearing or marked `secret`, THE Portal Manager SHALL render a masked or empty editor, show only configured-state metadata such as `hasValue`, and preserve the Portal value when the operator submits an empty field.
7. WHEN the operator applies a Settings Domain batch, THE Portal Manager SHALL submit only explicitly approved editable controls through the existing `/remote/settings` contract, show the returned applied-key set, and refresh the returned domain schema as the authoritative read-back.
8. IF a value has the wrong type, violates a returned range, is not an allowed enum option, or lacks a required policy classification, THEN THE Portal Manager SHALL identify the rejected field, leave the last confirmed value unchanged in the UI, and report the partial or blocked result without retrying the invalid value.
9. WHEN the operator reads `/remote/sources`, THE Portal Manager SHALL treat nonblank `immichKey`, `smbUser`, `smbPass`, `davUser`, and `davPass` values as legacy Source Credentials, SHALL strip those values before UI, draft state, registry, logs, diagnostics, exports, and analytics, and SHALL retain only non-secret source fields and configured-state metadata.
10. WHEN the operator explicitly migrates a legacy Source Credential or enters a replacement Source Credential, THE Portal Manager SHALL write the value directly to the per-Portal, per-source Keychain item from active-operation memory and SHALL not display, persist, or log the cleartext value.
11. WHEN the operator submits a source edit with a blank or omitted credential field, THE Portal Manager SHALL omit the credential field from the request, SHALL preserve the existing device and Keychain credential, and SHALL not write a blank value or overwrite a nonblank credential.
12. IF a source edit cannot be represented by the documented field-presence and partial-update semantics of `/remote/sources`, `/screensaver`, or `/calendar`, THEN THE Portal Manager SHALL label the edit unsupported, SHALL send no request, and SHALL leave the last confirmed source state unchanged.
13. WHEN the operator configures a photo source or calendar feed, THE Portal Manager SHALL use the existing `/remote/sources`, `/screensaver`, and `/calendar` contracts according to the credential matrix, show applied fields returned by each endpoint, and preserve partial-update semantics.
14. IF a Settings Domain exposes no generic control for an on-device navigation editor, THEN THE Portal Manager SHALL show the domain's approved available controls without inventing a desktop editor or writing an unregistered preference key.
15. THE Portal Manager SHALL classify `maUsername` as a Sensitive Value and credential-bearing setting even when the returned schema does not set `secret`, and SHALL use the Keychain reference for the value rather than registry or view-model storage.
16. IF a legacy Source Credential appears in a `/remote/sources` response and no Keychain migration succeeds, THEN THE Portal Manager SHALL show a configured-but-reentry-required state and SHALL not use the response value for a later edit or request.

### Requirement 7: Configure individual Portals and perform safe bulk operations

**User Story:** As a fleet operator, I want to configure one Portal or a selected group of Portals, so that repeated settings and approved actions do not require repetitive device-by-device work.

#### Acceptance Criteria

1. THE Portal Manager SHALL allow an operator to select one Portal, inspect the selected Portal's current schema and status, edit applicable User-facing Settings, and submit an individual operation.
2. WHEN the operator selects multiple Portals for a Bulk Operation, THE Portal Manager SHALL show the target count, the selected operation, the target fields, and each target's compatibility, credential-scope, policy-classification, and capability state before confirmation.
3. IF a selected operation requires a Verified Bearer Credential or an approved Settings Policy Classification that a target lacks, THEN THE Portal Manager SHALL mark the target ineligible and SHALL not substitute a Remote Session Credential or guessed policy.
4. WHEN a confirmed Bulk Operation begins, THE Portal Manager SHALL dispatch the operation independently per Portal using the credential permitted by the credential matrix and SHALL show progress and a result for every target.
5. IF one target in a Bulk Operation is offline, unauthenticated, incompatible, unclassified, or rejects a value, THEN THE Portal Manager SHALL continue eligible targets, identify the failed target and reason, and report the overall operation as partially failed.
6. IF selected Portals expose different schemas or constraints, THEN THE Portal Manager SHALL apply only values valid for each target, identify omitted or rejected fields per target, and require confirmation before applying a reduced operation.
7. WHEN a Bulk Operation completes, THE Portal Manager SHALL provide success, partial-failure, and failure counts with per-Portal read-back status and SHALL not claim fleet-wide success from a single successful response.
8. IF a Bulk Operation actually changes a Source Credential, `maUsername`, or another Sensitive Value on more than one Portal, THEN THE Portal Manager SHALL display the target count and affected domain before dispatch and SHALL allow dispatch without an additional explicit Sensitive Value confirmation.

### Requirement 8: Inspect local Music Assistant and Snapcast topology with release-gated service-specific controls

**User Story:** As a multi-room audio operator, I want to inspect Music Assistant/Snapcast topology and see only explicitly release-gated service controls, so that Portal membership and synchronized playback remain understandable without implying unsupported cross-service mutation.

#### Acceptance Criteria

1. WHEN the operator configures a local Music Assistant/Snapcast host, THE Portal Manager SHALL support the Music Assistant WebSocket endpoint with the configured port (default `8095`) and the Snapcast control endpoint with the configured port (default `1705`) after LAN policy validation.
2. WHEN a Music Assistant/Snapcast connection succeeds, THE Portal Manager SHALL enumerate available players, Snapcast clients, streams, Music Groups, membership, online state, and the identifiers needed to distinguish similarly named devices.
3. THE Portal Manager SHALL not send Music Assistant or Snapcast group mutation requests in the v1 product surface without the conditions in Criterion 8.4, including create, rename, add-member, remove-member, and dissolve operations.
4. WHERE a service-specific Versioned Service Contract has a typed adapter, sanitized fixtures, Mutation Evidence, and a passed Release Gate for a named operation, THE Portal Manager SHALL enable only that documented operation for that service and SHALL keep all other mutation operations unavailable.
5. IF a deployed Music Assistant or Snapcast version lacks the required Versioned Service Contract, fixtures, Mutation Evidence, or Release Gate result, THEN THE Portal Manager SHALL show the confirmed service topology, SHALL leave the affected mutation unavailable/read-only, and SHALL not send a mutating request for the affected operation.
6. THE Portal Manager SHALL not discover mutation support by sending speculative requests, SHALL not emulate a service mutation through Portal preferences, and SHALL not expose a generic service method or arbitrary JSON-RPC mutation path.
7. IF Music Assistant authentication is enabled, THEN THE Portal Manager SHALL authenticate with the stored Music Assistant credential, distinguish authentication failure from network failure, and avoid issuing any mutation after authentication fails.
8. IF a player-to-client mapping is ambiguous, a player is offline, or a service operation is partially accepted, THEN THE Portal Manager SHALL identify the affected identifier or member and show the confirmed service topology rather than presenting the requested topology as fact.
9. IF an operator selects a workflow that would mutate Music Assistant and Snapcast together, THEN THE Portal Manager SHALL present separate service-specific operations, SHALL not claim cross-service atomicity, and SHALL show each service's confirmed result independently.
10. THE Portal Manager SHALL use only an approved Settings Policy Classification for `multiRoomEnabled`, `snapcastHost`, `maPort`, `maUsername`, and masked `maPassword` when applying Portal multi-room settings that reference Music Assistant or Snapcast, SHALL store Music Assistant and Snapcast credentials through Keychain for every credential entry, update, or connection, and SHALL show service-resynchronization status only after the relevant service or settings operation is acknowledged and the current service/status state is read back.

### Requirement 9: Protect Portal and music-service secrets

**User Story:** As a security-conscious fleet operator, I want all desktop credentials protected by macOS, so that a lost laptop or diagnostic export does not expose control of Portals or the music system.

#### Acceptance Criteria

1. THE Portal Manager SHALL store Portal bearer tokens, Remote Session Credentials, Source Credentials (`immichKey`, `smbUser`, `smbPass`, `davUser`, `davPass`), Music Assistant usernames/passwords/access tokens, and supported Snapcast credentials only in the macOS Keychain or in process memory while an operation is active, and SHALL keep Pairing PINs only in process memory during active pairing.
2. THE Portal Manager SHALL classify `maUsername` as sensitive regardless of schema `secret` metadata and SHALL store the value through a per-service Keychain reference rather than a Portal Registry field, UserDefaults value, or persistent view state.
3. THE Portal Manager SHALL store only non-secret Portal identity, connection, capability, configured-state, and policy metadata in the Portal Registry, SHALL store references to corresponding Keychain items rather than Sensitive Values, and SHALL permit a Sensitive Value to remain temporarily in active-operation memory during a credential update before writing the value to Keychain and clearing the temporary value after save, cancellation, or failure.
4. WHEN a Sensitive Value is entered or displayed, THE Portal Manager SHALL mask the value, SHALL avoid placing the value in clipboard-oriented previews or URLs, and SHALL clear transient input after successful save or cancellation.
5. IF `/remote/sources` or another legacy response contains a Source Credential, THEN THE Portal Manager SHALL strip the value before UI/state/log/export/diagnostic use and SHALL expose only a configured-state indicator or a Keychain migration result.
6. IF the operator requests logs, diagnostics, exports, or error details, THEN THE Portal Manager SHALL redact Sensitive Values and SHALL not include authorization headers, PINs, passwords, source credentials, usernames, or access tokens in those outputs.
7. WHEN the Portal Manager opens the first credentialed connection for a service kind and normalized resolved LAN endpoint, THE Portal Manager SHALL show a local-network trust warning before sending credentials, SHALL record only the non-secret Trust Warning Scope acknowledgement, and SHALL request a new acknowledgement for a changed endpoint or service kind.
8. THE Portal Manager SHALL restrict Portal, Music Assistant, and Snapcast connections to LAN endpoints after DNS resolution and on every reconnect, SHALL reject HTTP redirects, and SHALL send credentials only in the established authorization or protocol-authentication messages.
9. IF a Keychain read fails or a credential is unavailable, THEN THE Portal Manager SHALL request reauthentication and SHALL not fall back to a cleartext file, UserDefaults value, URL parameter, registry field, clipboard, or shared credential.

### Requirement 10: Provide a native sidebar/split-view desktop workflow with keyboard and menu actions

**User Story:** As a macOS operator managing many devices, I want a fast native desktop workflow, so that discovery, inspection, configuration, and recovery are usable without relying on touch-oriented web controls.

#### Acceptance Criteria

1. THE Portal Manager SHALL provide a Native UI with a sidebar listing Portal Registry entries and a split-view detail area for overview, settings, approved actions, provisioning, and Music Group work.
2. WHEN a Portal is selected in the sidebar, THE Portal Manager SHALL show the selected Portal's Connection State, model/capability summary, last refresh, credential state, and available operations before presenting mutation controls.
3. THE Portal Manager SHALL provide menu commands and keyboard equivalents for discovery refresh, manual-IP onboarding, sidebar navigation, Portal selection, status refresh, identify, apply/retry, and Bulk Operation confirmation.
4. WHEN a command cannot run because no Portal is selected, a capability or credential is missing, or an ADB, Local Artifact, or Artifact Verification prerequisite for USB Provisioning is unmet, THE Portal Manager SHALL explain the unmet prerequisite before allowing retry, SHALL keep the command discoverable but disabled when dispatch cannot proceed, and SHALL not silently target another Portal.
5. WHILE a Bulk Operation or USB Provisioning flow is active, including when USB Provisioning is running without a Bulk Operation, THE Portal Manager SHALL show progress, allow safe cancellation where the current step permits cancellation, and preserve the final per-target or provisioning result.
6. THE Portal Manager SHALL present connection, authentication, compatibility, validation, and server errors in the selected Portal's detail view with a recovery action appropriate to the error category.

### Requirement 11: Enforce version-one LAN-only scope and explicit exclusions

**User Story:** As a fleet operator, I want predictable local-only behavior and a deliberately narrow control surface, so that version one cannot unexpectedly become a cloud manager or raw server-administration tool.

#### Acceptance Criteria

1. THE Portal Manager SHALL perform Portal discovery, authentication, settings management, approved actions, Music Assistant access, Snapcast access, and provisioning verification through local-network or USB/ADB connections owned by the operator.
2. WHEN a hostname is resolved or a connection is re-established, THE Portal Manager SHALL reapply LAN policy to the selected resolved address before reading a credential or opening a socket, and SHALL apply the same check on every reconnect.
3. IF the selected resolved address is public or otherwise non-LAN, THEN THE Portal Manager SHALL reject the destination before reading a credential or opening a socket.
4. IF resolution produces no loopback, RFC1918/private, IPv4 link-local, IPv6 loopback, IPv6 unique-local, or IPv6 link-local address for the selected connection, THEN THE Portal Manager SHALL reject the endpoint and SHALL not connect to a public or other non-LAN address.
5. WHEN the operator enters a bracketed IPv6 Manual-IP Endpoint such as `[fe80::1%25en0]:8723`, THE Portal Manager SHALL preserve the IPv6 address and link-local interface scope for connection and policy evaluation, SHALL default an omitted port to `8723`, and SHALL reject an ambiguous unbracketed IPv6 address with an appended port.
6. IF an HTTP Fleet request returns a `3xx` redirect or a redirect target, THEN THE Portal Manager SHALL reject the response, SHALL not follow the redirect, and SHALL not forward a credential to the redirect target.
7. WHEN the Portal Manager opens the first credentialed connection for a service kind and Trust Warning Scope, THE Portal Manager SHALL show the local-network trust warning before sending the credential and SHALL not use the acknowledgement to bypass later DNS, reconnect, or LAN checks.
8. IF a requested operation requires a cloud service, public relay, internet-routable Portal address, remote shell, operating-system administration, package-manager administration, arbitrary server configuration, or a downloaded platform tool/package, THEN THE Portal Manager SHALL explain that the operation is outside version-one scope and SHALL leave the managed device unchanged.
9. THE Portal Manager SHALL keep raw file/log and remote or ad hoc install/update controls, cloud/remote access, broad server administration, and unverified Music Group mutation controls absent from the Native UI, command menu, bulk-operation model, and internal operation allowlist.
10. IF a future Fleet Agent response advertises a route, setting domain, setting control, action, or service mutation outside the approved policy set, THEN THE Portal Manager SHALL treat the item as unavailable or read-only until an explicit product approval and corresponding validation evidence exist.
11. WHEN the Portal Manager starts or resumes a session, THE Portal Manager SHALL preserve the LAN-only policy, credential protections, route restrictions, and no-cloud behavior without requiring a cloud account or remote service registration.

### Requirement 12: Gate version-one release on mandatory validation evidence

**User Story:** As a release owner, I want security, LAN, provisioning, hardware-model, and Portal TV validation to block unsupported releases, so that version-one support claims match verified behavior.

#### Acceptance Criteria

1. THE Release Process SHALL maintain a Release Gate checklist with named evidence, test results, supported model claims, and unresolved deviations for every version-one candidate.
2. WHEN a version-one candidate is evaluated, THE Release Process SHALL require passing Security Release Validation before publishing the candidate as a supported build.
3. WHEN a version-one candidate is evaluated, THE Release Process SHALL require passing LAN Release Validation before publishing the candidate as a supported build.
4. WHEN a version-one candidate is evaluated, THE Release Process SHALL require passing Provisioning Release Validation for both Fleet Agent Enablement/Recovery and Full USB Provisioning before publishing provisioning support.
5. WHEN a version-one candidate is evaluated, THE Release Process SHALL require passing Model Matrix Validation for every claimed Compatible Portal family and Android API level before publishing the corresponding model claim.
6. WHEN a version-one candidate claims Portal TV support, THE Release Process SHALL require passing Portal TV Validation without touchscreen or D-pad input on the Portal.
7. WHERE a future Music Group mutation feature is included in a release, THE Release Process SHALL require a Versioned Service Contract, sanitized fixtures, Mutation Evidence, service-specific read-back tests, and a passed Release Gate before enabling the named mutation.
8. IF any mandatory release gate lacks passing evidence, THEN THE Release Process SHALL withhold the affected v1 support claim and SHALL keep the affected operation unavailable or read-only.

## Edge Cases and Compatibility Notes

- The Fleet API authentication split is fixed for this feature: `/info`, `/screensaver`, `/calendar`, and `/action` require a Verified Bearer Credential; only approved `/remote/settings` and `/remote/sources` operations accept a Remote Session Credential; `/remote/pair` accepts the PIN body without a credential. The macOS feature does not alter Android routes or authentication.
- Manual-IP onboarding remains a first-class fallback when mDNS is unavailable, duplicated, or delayed. A LAN-validated manual host can redeem a PIN directly, but a Remote Session Credential does not provide identity/health verification or bearer-only management.
- A Portal TV has no touchscreen. USB flows and the Native UI must remain operable without touching the Portal; PIN pairing still requires reading the on-screen PIN when that path is selected.
- DHCP can change a Portal's address after mDNS discovery or provisioning. The Portal Registry retains the stable serial/name association and updates the endpoint only after the applicable credential and route checks succeed.
- The LAN parser accepts IPv4 private/link-local addresses, IPv6 loopback/unique-local/link-local addresses, and bracketed IPv6 endpoint syntax. IPv6 link-local connections retain an interface zone; ambiguous unbracketed IPv6 host-and-port input is rejected. DNS results and every reconnect are revalidated, and HTTP redirects are not followed.
- The first credentialed connection warning is scoped to service kind plus normalized resolved LAN endpoint. A changed address, port, protocol, or service kind requires a new acknowledgement; an acknowledgement never bypasses LAN policy.
- `GET /remote/sources` may expose legacy `immichKey`, `smbUser`, `smbPass`, `davUser`, and `davPass` values. The Portal Manager strips those values before UI/state/log/export use, can migrate a value directly to the per-service Keychain only during an explicit migration operation, and otherwise shows only configured-state metadata. Blank source-credential edits preserve the device and Keychain value; unsupported partial edits are blocked and labeled.
- The current `/remote/settings` schema may mark secrets with `secret` and `hasValue`, but endpoint-bearing and credential-bearing controls also require Portal Manager policy classification. Unknown/future domains, unknown controls, and unknown types remain read-only until approved. `maUsername` is sensitive even when schema metadata omits `secret`.
- Fleet Agent Enablement/Recovery is separate from Full USB Provisioning. Enablement/recovery uses the existing installed application and the `provision.json`/`agent.json` handoff; full provisioning uses a verified local artifact. The macOS app does not download platform-tools, APKs, packages, or release artifacts.
- The first-generation Portal+ and Portal TV use Android 9/API 28 and may require the provisioning kit's installer-overlay and shell-daemon path. The Portal Manager must not infer that a failed standard installer means the Fleet Agent is incompatible.
- Portal Go, Portal Mini, and Portal (gen-2) are Android 10/API 29 families in the repository documentation; repository validation describes Go and first-generation Portal+ as verified, Mini/gen-2 as expected but not fully confirmed, and Portal TV as experimental for D-pad navigation. Release claims require Model Matrix Validation.
- Music Assistant authentication is optional in the existing Portal client. An unauthenticated local server is a valid read/transport state; an invalid supplied credential is an authentication error, not evidence that the server is offline.
- The repository's current Portal-side code reads group topology and transport state but does not establish a stable cross-version group-mutation contract. v1 group mutations remain unavailable unless the named service has a concrete Versioned Service Contract, fixtures, Mutation Evidence, and a passed Release Gate. Music Assistant and Snapcast mutations are never represented as one atomic cross-service operation.
- The current Fleet API is plain HTTP on the trusted LAN rather than a cloud TLS service. The manager must preserve the existing bearer-header contract, warn about the local-network trust boundary, and avoid redirecting credentials to another host.

## Validation Needs

### Mandatory v1 release gates

- **Security Release Validation (mandatory):** Test the credential matrix for bearer and remote-session routes; verify Keychain-only storage for Portal, source, Music Assistant, and Snapcast credentials; verify `maUsername` sensitivity; verify redaction of legacy `/remote/sources` values; verify closed route/action allowlists; verify unknown settings are read-only; verify HTTP redirects cannot forward credentials.
- **LAN Release Validation (mandatory):** Test IPv4 private/link-local, IPv6 loopback/unique-local/link-local, bracketed IPv6 with and without zone identifiers, malformed/ambiguous endpoints, DNS results containing public addresses, re-resolution on reconnect, no-redirect behavior, and Trust Warning Scope acknowledgements for Portal, Music Assistant, and Snapcast.
- **Provisioning Release Validation (mandatory):** Test Fleet Agent Enablement/Recovery without APK installation, Full USB Provisioning with a locally selected artifact, Artifact Verification failures, operator-provided ADB/platform-tools, generated `agent.json` recovery, bearer `/info` verification, retry after failure, and proof that the macOS app makes no artifact/platform-tools/package download.
- **Model Matrix Validation (mandatory):** Test the 2018 Portal, Portal+, first-generation Portal+, Portal Go, Portal Mini, Portal (gen-2), and Portal TV across Android 9/API 28 and Android 10/API 29 where applicable, including arm64 deployment, DHCP changes, mDNS loss, and the release claim recorded for each model.
- **Portal TV Validation (mandatory):** Test discovery, bearer authentication, approved settings/actions, Fleet Agent recovery, Full USB Provisioning, and Native UI workflows without touchscreen or D-pad input on the Portal.
- **Music mutation gate (conditional but mandatory when enabled):** Require a concrete Versioned Service Contract, sanitized fixtures, Mutation Evidence, service-specific read-back, and a passed Release Gate for every enabled Music Assistant or Snapcast mutation. Otherwise test and ship read-only topology only.

### Supporting validation

- **Pure macOS tests:** LAN-address and bracketed-IPv6 validation, DNS result selection, reconnect policy, mDNS record normalization, duplicate merging, manual-IP parsing, connection-state transitions, redirect rejection, route allowlisting, credential-matrix planning, and Sensitive Value redaction.
- **Credential tests:** Keychain save/read/delete behavior, per-Portal and per-service isolation, PIN success/wrong/expired/redeemed handling, manual-IP PIN pairing without mDNS, bearer-header construction, remote-session route restrictions, and assurance that tokens never enter URLs, UserDefaults, logs, exports, or registry JSON.
- **Source-secret tests:** Fixtures in which `GET /remote/sources` contains each legacy source credential; direct Keychain migration without UI/state/log exposure; blank edit preservation; deliberate replacement; missing-Keychain behavior; unsupported partial source edits; and applied-field/read-back redaction.
- **Schema tests:** Fixture-driven `/remote/settings` parsing for all current domains and control types; policy classification for endpoint/credential-bearing controls; visibility, sections, defaults, integer constraints, enum options, read-only fields, unknown domains, unknown controls/types, `secret`/`hasValue` semantics, `maUsername` handling, partial apply results, and capability-dependent domains.
- **Fleet operation tests:** Individual and bulk fan-out with bearer-only and remote-session operations, online, offline, unauthorized, incompatible, unclassified, timeout, partial-apply, and stale-schema targets; confirmation summaries and per-Portal read-back results.
- **Protocol tests:** Local Fleet Agent fixtures for `/info`, `/remote/pair`, `/remote/settings`, `/remote/sources`, `/screensaver`, `/calendar`, and approved `/action` values; exact authorization and method checks; 3xx redirect rejection; Music Assistant WebSocket auth/player fixtures; Snapcast JSON-RPC topology fixtures; and absence of mutation requests without release evidence.
- **Provisioning tests:** Deterministic ADB/process fixtures for authorized and unauthorized devices, enablement-only handoff, full local-artifact installation, artifact identity/signature/digest/API checks, generated `agent.json`, first-generation installer behavior, cancellation, retry, reboot, bearer verification, sanitized output, and no-download assertions.
- **Music tests:** Read-only Music Assistant/Snapcast topology, authentication-state separation, identifier preservation, ambiguity handling, service-specific mutation fixtures only for a release-gated contract, post-mutation read-back, rejection/partial handling, and explicit proof that no cross-service atomic mutation is implied.
- **Native UI validation:** Sidebar/split-view navigation, manual-IP pairing state, credential-scope badges, read-only unknown settings, Keychain-backed source editors, disabled prerequisites, VoiceOver/accessibility labels where applicable, cancellation behavior, Portal TV workflows, and large-fleet bulk progress/read-back presentation.

## Requirement Traceability

| Requirement | Primary validation evidence | Release status |
|---|---|---|
| 1. Discovery and registration | mDNS/manual-IP fixtures, bearer identity probes, direct manual PIN pairing, LAN parser, duplicate/offline tests | Required for v1 |
| 2. Model and capability classification | `/info` fixtures, capability tests, Model Matrix Validation, Portal TV Validation | Required for each claimed model |
| 3. Enablement/recovery and full provisioning | ADB/process fixtures, local artifact verification, `agent.json`/`/info` verification, Provisioning Release Validation | Required for provisioning claims |
| 4. Credential matrix and pairing | Route/credential matrix fixtures, PIN tests, bearer verification, manual-IP pairing tests, Security Release Validation | Required for v1 |
| 5. Status and approved management | allowlist tests, bearer-only direct-route tests, status/error fixtures, UI scope checks | Required for v1 |
| 6. Settings and source safety | schema/policy fixtures, unknown read-only tests, source-secret migration tests, partial-update tests | Required for v1 |
| 7. Individual and bulk operations | target-specific preflight, credential-scope checks, partial fan-out/read-back tests | Required for v1 |
| 8. Music topology and gated controls | read-only topology/auth fixtures; conditional Versioned Service Contract evidence and Release Gate | Read-only required; mutation conditional |
| 9. Secret protection | Keychain/redaction/legacy-source fixtures, redirect tests, Trust Warning Scope tests | Required for v1 |
| 10. Native desktop workflow | UI tests for selection, commands, read-only states, provisioning, Portal TV, and bulk results | Required for v1 |
| 11. LAN-only scope and exclusions | IPv4/IPv6/DNS/reconnect/redirect tests, route/UI static checks, no-download checks | Required for v1 |
| 12. Release gates | Recorded Security, LAN, Provisioning, Model Matrix, and Portal TV evidence | Required before v1 claims |
