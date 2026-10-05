# Implementation Plan: macos-portal-manager

## Overview

Build the macOS Portal Manager as an independent SwiftUI/AppKit Xcode project under `macos/PortalManager`, targeting macOS 13 or newer. The plan consumes the existing Android Fleet Agent, Settings Registry, Music Assistant/Snapcast, and USB/ADB provisioning contracts; it does not modify Android/Kotlin code, Gradle configuration, Fleet Agent routes, Settings Registry semantics, provisioning scripts, or Portal runtime behavior.

The implementation is ordered from an independent Xcode bootstrap and pure domain/LAN admission through secure persistence, the closed Fleet route/auth surface, mDNS/manual onboarding, separate USB modes, forward-compatible settings/source adapters, target-specific bulk preflight, read-only music topology, release evidence, and the native UI. All network/process dependencies are injected, credentials stay in Keychain or active operation memory, and every request is admitted by the shared LAN policy before credential or socket access.

Tasks marked `*` are optional property, unit, fixture, integration, or UI tests for the Fast Task MVP. The Security, LAN, Provisioning, Model Matrix, Portal TV, and conditional Music Mutation release-validation tasks are deliberately unstarred and are mandatory validation work. Conditional Music tasks remain mandatory when a named mutation is enabled; otherwise they must prove the default-disabled read-only path. Every task is limited to writing, modifying, or automatically validating code.

## Tasks

- [x] 1. Bootstrap the independent macOS Xcode project and test boundary
  - [x] 1.1 Create `macos/PortalManager.xcodeproj` with `PortalManager`, `PortalManagerTests`, and `PortalManagerUITests` targets and the `App`, `Domain`, `Application`, `Infrastructure`, `UI`, `Resources`, and fixture layouts defined by the design.
    - Make the macOS target independent of the Android Gradle project and use Swift concurrency with Foundation, SwiftUI, AppKit, Network, and Security.
    - _Requirements: 10.1, 11.1; Design: §§1-2, 16_

  - [x] 1.2 Configure macOS 13+ Debug/Release schemes, arm64-capable settings, resource/localization handling, App Sandbox network-client access, user-selected local-file access, security-scoped bookmarks, and hardened-runtime-ready build settings.
    - Exclude registries, Keychain exports, ADB binaries, APKs, provisioning artifacts, archives, DerivedData, and fixture secrets from resources and source control.
    - _Requirements: 3.4-3.5, 9.1, 10.1, 11.1, 11.11; Design: §§7.2-7.3, 11, 16_

  - [x] 1.3 Add the native application shell and dependency container with typed ports for DNS, Bonjour, Fleet HTTP, Keychain, registry, ADB, artifact verification, Music Assistant, Snapcast, clocks, redaction, and evidence storage.
    - Keep URL construction, credential selection, process execution, and raw protocol requests out of SwiftUI views and AppKit commands.
    - _Requirements: 10.1-10.2, 10.4; Design: §§2.1-2.2, 12.2-12.3_

  - [x] 1.4 Add a macOS-only build/test entry point for the Portal Manager Xcode scheme and a scope check that keeps Xcode validation separate from Gradle while rejecting cloud/download dependencies and Android-runtime edits.
    - Use the project/scheme and `xcodebuild` destinations from the design; do not alter `app/`, `settings.gradle.kts`, Android CI, Fleet routes, Settings Registry code, or provisioning scripts.
    - _Requirements: 11.1, 11.8-11.11; Design: §§1, 16-17_

- [x] 2. Implement domain identity, assurance states, and shared LAN admission
  - [x] 2.1 Implement `PortalID`, `PortalIdentity`, `LANEndpoint`, endpoint sources, credential references, `PortalRegistryEntry`, `PortalStatus`, `PortalCapabilities`, `ConnectionState`, and typed `ManagerError` values.
    - Preserve raw `/info` identity/model data, stable serial metadata, endpoint history, credential references, last-confirmed state, explicit pairing/bearer/provisioning/offline states, and sanitized recovery actions without storing secrets.
    - _Requirements: 1.5-1.8, 2.1-2.6, 4.7-4.8, 5.1-5.2, 10.2, 10.6; Design: §§3.1-3.5, 14_

  - [x] 2.2 Implement `ManualEndpointParser` and the pure shared `LANPolicy` for Portal, Music Assistant, Snapcast, discovery, manual endpoints, reconnects, and provisioning verification.
    - Accept IPv4 loopback/private/link-local, IPv6 loopback/unique-local/link-local, bracketed IPv6 with native or percent-encoded zones, and default Portal port `8723`; reject malformed, ambiguous unbracketed host-and-port IPv6, unresolved, public, or unsupported destinations.
    - _Requirements: 1.3, 1.6, 8.1, 9.7-9.8, 11.2-11.5; Design: §§4.1-4.2_

  - [x] 2.3 Implement injected DNS resolution, permitted-address selection, `ConnectionAdmission`, and `TrustWarningScope` handling.
    - Enforce parse → resolve → LAN validation → normalized service/protocol/endpoint trust scope → acknowledgement → Keychain read → request/socket ordering; repeat resolution, LAN validation, scope derivation, and warning evaluation on every reconnect, including IPv6 interface zones.
    - _Requirements: 1.3-1.4, 8.1, 9.7-9.8, 11.2-11.7; Design: §§4.3-4.5, 14_

  - [x] 2.4 Implement authenticated `/info` model-family and capability classification.
    - Recognize the 2018 Portal, Portal+, first-generation Portal+, Portal Go, Portal Mini, Portal (gen-2), and Portal TV; retain raw model strings; evaluate API 28/API 29, endpoint presence, and capability flags independently; produce operation-specific unsupported warnings.
    - _Requirements: 2.1-2.6; Design: §3.2_

  - [x]* 2.5 Add the property test for LAN endpoint parsing and admission.
    - **Property 1: LAN endpoint parsing and admission**
    - Generate manual, mDNS, provisioning, Portal, Music Assistant, and Snapcast endpoints; verify defaulting, IPv4/IPv6/zone preservation, public/unresolved rejection, and no credential/socket access before admission.
    - **Validates: Requirements 1.3, 8.1, 9.8, 11.2-11.5; Design: §18 Property 1**

  - [x]* 2.6 Add the property test for model and capability decisions.
    - **Property 6: Model and capability decisions are independent of labels**
    - Generate authenticated `/info` combinations; verify raw identity retention, every supported family, independent API/endpoint evaluation, and affected-operation warnings.
    - **Validates: Requirements 2.1-2.6; Design: §18 Property 6**

  - [x]* 2.7 Add pure domain tests for IPv4/private/link-local and IPv6/zone parsing, ambiguous endpoint rejection, connection-state transitions, trust-scope normalization, model families, API levels, and Portal TV/unknown-model examples.
    - _Requirements: 1.3, 1.6, 2.2-2.6, 9.7, 11.2-11.7; Design: §§3.2-3.4, 4.1-4.5_

- [x] 3. Checkpoint - Run the independent Xcode bootstrap and pure domain/LAN tests before adding persistence or protocol orchestration.
  - Ensure the project builds, the domain tests pass, and no task has introduced an Android, cloud, public-endpoint, or raw-route dependency.
  - _Requirements: 1.3, 1.6, 2.1-2.6, 9.7-9.8, 10.1, 11.1-11.7; Design: §§2-4, 16-17_

- [x] 4. Implement registry, Keychain, source-secret migration, and redaction boundaries
  - [x] 4.1 Define `RegistryStore`, `CredentialStore`, `Clock`, `Redactor`, and secure-input protocols; implement `JSONRegistryStore` for non-secret identity, endpoint history, last-confirmed status, policy/capability metadata, and opaque Keychain references only.
    - Reject or migrate legacy persisted secret fields and never serialize bearer/session tokens, PINs, source credentials, Music/Snapcast credentials, authorization headers, raw response bodies, or cleartext manifests.
    - _Requirements: 1.7-1.8, 4.7-4.9, 9.1-9.3, 11.11; Design: §§3.1, 11, 14_

  - [x] 4.2 Implement `KeychainCredentialStore` with bundle-scoped generic-password items and account derivation that includes Portal/service/credential kind and, for source secrets, `PortalID + sourceID + SourceSecretField`.
    - Support bearer tokens, remote sessions, source fields, Music Assistant credentials, and supported Snapcast credentials with typed read/write/delete errors and no cleartext fallback.
    - _Requirements: 4.7-4.9, 6.10, 8.10, 9.1-9.3, 9.9; Design: §11_

  - [x] 4.3 Implement structured and free-form redaction plus transient secure-input lifecycle helpers.
    - Redact authorization headers, URLs, PINs, passwords, source fields, access tokens, nested JSON, ADB output, and sentinel values before errors, logs, diagnostics, exports, analytics, snapshots, or release evidence; clear transient input after save, cancel, or failure.
    - _Requirements: 4.2, 5.6, 6.9-6.11, 9.4-9.6; Design: §§6.2, 8.4, 11, 13_

  - [x] 4.4 Implement the private source wire DTO, sanitized source snapshot, `SourceSecretKey`, field-status model, and explicit per-Portal/per-source legacy migration/replacement operation.
    - Strip `immichKey`, `smbUser`, `smbPass`, `davUser`, and `davPass` before state/UI/registry/log/export use; write a migrated value directly to the exact Keychain item from active-operation memory; report configured-but-reentry-required when migration fails.
    - _Requirements: 6.9-6.11, 6.16, 9.1-9.5; Design: §8.4_

  - [x] 4.5 Add deterministic fake implementations for the clock, registry, Keychain, redactor, DNS/transport ports, Bonjour, ADB/process, Music Assistant, Snapcast, and evidence stores.
    - Fakes must record only sanitized request metadata and must support credential-access-order assertions, redirect assertions, no-download assertions, and fixture replay.
    - _Requirements: 1.1-1.8, 3.1-3.10, 4.1-4.10, 6.1-6.16, 8.1-8.10, 9.1-9.9; Design: §15.1_

  - [x] 4.6 Implement registry reconciliation and removal/offline retention.
    - Merge authenticated serial/stable identity records and authenticated endpoint fallbacks, retain valid credential references and alternate endpoints, prefer the newest authenticated address, preserve last contact/status after outages, and delete associated credential references and bulk membership on removal.
    - _Requirements: 1.7-1.8, 4.9; Design: §§3.1, 5.3-5.4, 14_

  - [x]* 4.7 Add the property test for registry reconciliation and offline retention.
    - **Property 5: Registry reconciliation and offline retention**
    - Generate authenticated discovery/manual/provisioning records and outages; verify one merged entry, credential preservation, newest authenticated endpoint selection, and retained retry/edit state.
    - **Validates: Requirements 1.7-1.8; Design: §18 Property 5**

  - [x]* 4.8 Add the property test for sensitive-value persistence and diagnostic fallback.
    - **Property 22: Sensitive values have no persistence or diagnostic fallback**
    - Inject Portal, source, Music Assistant, Snapcast, PIN, and artifact sentinels; verify Keychain/active-memory-only handling, masking, redaction, and no registry/UserDefaults/URL/clipboard/shared-file fallback after Keychain failure.
    - **Validates: Requirements 9.1-9.4, 9.6, 9.9; Design: §18 Property 22**

  - [x]* 4.9 Add secure-persistence unit tests for Keychain isolation, registry serialization, per-Portal/per-source account derivation, migration failure, removal cleanup, nested redaction, and transient secret clearing.
    - _Requirements: 4.7-4.9, 6.9-6.16, 9.1-9.9; Design: §§8.4, 11, 14_

- [x] 5. Checkpoint - Run persistence, Keychain, source-secret, redaction, and reconciliation tests before implementing Fleet routes.
  - Ensure the registry contains only non-secret metadata/references and that every source migration path is per Portal and per source.
  - _Requirements: 1.7-1.8, 4.7-4.9, 6.9-6.16, 9.1-9.9; Design: §§3.1, 8.4, 11, 14-16_

- [x] 6. Implement the closed Fleet route surface, no-follow transport, and credential-matrix session coordinator
  - [x] 6.1 Implement `FleetRoute`, `ApprovedAction`, `CredentialRequirement`, `RemoteOperationApproval`, `RouteCredentialPlan`, and `OperationPlanner` for the exact credential matrix.
    - Represent only `/info`, `/remote/pair`, approved `/remote/settings`, approved `/remote/sources`, `/screensaver`, `/calendar`, and `/action` `identify`/`reaffirm`; require a matching Portal ID, route, method, and operation ID approval for every remote-session settings/source plan; make `/apps`, `/config`, `/fs/*`, `/logcat`, `/install`, `/update`, `/dev`, `/diag`, arbitrary `/remote/*`, reboot, shell, and future routes unrepresentable; route stale, imported, or future excluded intents through an `OperationExclusionGate` that explains locally, emits no request, leaves the device unchanged, and returns continuation.
    - _Requirements: 4.1-4.2, 4.4, 4.10, 5.4, 5.6, 11.9-11.10; Design: §§3.3, 6.1, 17_

  - [x] 6.2 Implement `HTTPTransport` and connection execution with redirect following disabled.
    - Re-run `ConnectionAdmission` on initial connection and every reconnect before Keychain access or socket creation; reject every `3xx`, never construct a `Location` request, never forward a credential, and enforce the ten-second status deadline.
    - _Requirements: 5.2, 9.7-9.8, 11.2-11.7; Design: §§4.3-4.4, 6.2_

  - [x] 6.3 Implement `FleetHTTPClient` and response classification over the injected transport.
    - Build typed requests only from validated endpoints and route plans, put bearer/session credentials only in `Authorization`, put the PIN only in the `/remote/pair` JSON body, distinguish DNS/transport/timeout/401/403/404/405/409/5xx/redirect/schema errors, and preserve confirmed state on failure.
    - _Requirements: 1.5, 4.1-4.7, 5.1-5.7, 6.7-6.8, 9.6-9.8; Design: §6.2_

  - [x] 6.4 Implement `PortalSessionCoordinator` for bearer verification, exact-once PIN redemption, remote-session limits, per-Portal credential choice, reauthentication, and removal cleanup.
    - Verify bearer tokens with `/info`; permit a Remote Session Credential only for approved `/remote/settings` and `/remote/sources`; discard blank/wrong/expired/redeemed PIN input; never promote a session to verified identity/health/bearer state; preserve valid references while suppressing operations after `401` or revocation.
    - _Requirements: 1.4-1.5, 4.1-4.9, 5.2, 9.1-9.2; Design: §§3.3-3.4, 6.3_

  - [x]* 6.5 Add the property test for the exact route credential matrix.
    - **Property 7: The route credential matrix is exact**
    - Generate every approved/excluded operation, method, action, credential kind, exact/mismatched `RemoteOperationApproval`, and pairing PIN case; verify bearer/session permissions, no-credential pairing, bearer-only routes, approval scoping to the intended Portal and operation, and zero requests for excluded routes.
    - **Validates: Requirements 4.1-4.4, 5.4, 5.6, 11.9-11.10; Design: §18 Property 7**

  - [x]* 6.6 Add the property test for pairing and reauthentication fail-closed behavior.
    - **Property 8: Pairing and reauthentication fail closed**
    - Generate blank/wrong/expired/redeemed PINs, revocations, `401`s, and removals; verify transient discard, identity/reference preservation, mutation suppression, and no credential-kind substitution.
    - **Validates: Requirements 4.5-4.9; Design: §18 Property 8**

  - [x]* 6.7 Add the property test for DNS ordering, reconnect revalidation, scoped trust warnings, and redirect refusal.
    - **Property 2: DNS, reconnect, trust scope, and redirect safety**
    - Generate hostname resolution sequences, changed endpoints/zones/service kinds, cached acknowledgements, reconnects, and `3xx` responses; verify DNS/LAN checks precede Keychain/socket access, new scopes require warnings, and no redirect request is emitted.
    - **Validates: Requirements 9.7-9.8, 11.2, 11.6-11.7; Design: §18 Property 2**

  - [x]* 6.8 Add Fleet protocol fixture tests for exact methods/paths/bodies/headers, manual PIN body presence, status deadlines, redirect cancellation, response categories, and absence of excluded route serialization.
    - _Requirements: 4.1-4.7, 5.1-5.7, 9.6-9.8, 11.6, 11.9-11.10; Design: §§6.1-6.3, 15.2_

- [x] 7. Implement mDNS discovery, manual-IP onboarding, and authenticated reconciliation
  - [x] 7.1 Implement `BonjourBrowser` over `NWBrowser`/Bonjour for `_immortal-remote._tcp.` on active LAN interfaces.
    - Emit untrusted add/change/remove events with service name, resolved host/address, port, interface, source, resolution errors, and cancellation/refresh state; never carry credentials in discovery metadata.
    - _Requirements: 1.1-1.2, 11.1; Design: §5.2_

  - [x] 7.2 Implement discovery candidate normalization and resolved-address admission.
    - Parse service metadata, resolve hostnames, apply shared LAN policy after resolution, derive the Portal trust scope, and reject no-LAN/public candidates before any probe or credential read.
    - _Requirements: 1.1-1.3, 1.6, 9.7, 11.2-11.5; Design: §§4.1-4.5, 5.2_

  - [x] 7.3 Implement `DiscoveryCoordinator` refresh/probe/reconcile flow.
    - Browse and refresh without deleting managed entries; send `/info` only when a supplied Verified Bearer Credential exists and send no `/info` request when it is absent; promote only verified identity, preserve remote-session-only assurance, merge duplicates, prefer authenticated address updates, and retain offline entries after mDNS loss.
    - _Requirements: 1.1-1.8, 2.1, 4.4-4.5, 5.1-5.2; Design: §§3.1, 3.4, 5.2-5.4_

  - [x] 7.4 Implement Manual-IP onboarding and endpoint editing using `host[:port]`/bracketed IPv6 parsing, the same DNS/LAN/trust checks, `.manual` source, authenticated `/info` verification, and direct mDNS-independent `/remote/pair` redemption.
    - Retain `8723` defaulting and IPv6 zones, mark the entry manually added, associate a resulting session only with the intended Portal, and leave existing identity/endpoint state unchanged when the probe fails.
    - _Requirements: 1.3-1.5, 1.8, 4.3-4.4, 11.5; Design: §§4.2-4.5, 5.3, 6.3_

  - [x]* 7.5 Add the property test for discovery promotion.
    - **Property 3: Discovery promotes only authenticated identity**
    - Generate mDNS/manual candidates, LAN failures, failed/malformed probes, remote sessions, and valid bearer `/info` responses; verify only the final case promotes verified identity/health.
    - **Validates: Requirements 1.1, 1.2, 1.5, 4.5; Design: §18 Property 3**

  - [x]* 7.6 Add the property test for mDNS-independent manual PIN pairing limits.
    - **Property 4: Manual PIN pairing is limited and mDNS-independent**
    - Generate LAN-valid manual endpoints and one-time PIN outcomes; verify exactly-once `/remote/pair`, intended-Portal association, remote-session-only route scope, and no identity/health promotion.
    - **Validates: Requirements 1.4, 4.3-4.4; Design: §18 Property 4**

  - [x]* 7.7 Add Bonjour/manual discovery fixture tests for refresh cancellation, duplicate records, delayed/lost mDNS, public resolution, DHCP changes, authenticated endpoint preference, manual pairing, and offline retention.
    - _Requirements: 1.1-1.8; Design: §§4.1-4.5, 5.1-5.4, 15.2_

- [x] 8. Implement separate Fleet Agent Enablement/Recovery and Full USB Provisioning
  - [x] 8.1 Define `ADBDeviceSnapshot`, `ProvisioningMode`, `EnablementRecoveryPlan`, `FullUSBProvisioningPlan`, `LocalArtifact`, `ProvisioningStepID`, `ProvisioningEvent`, `AgentManifest`, and typed sanitized failures.
    - Make enablement/recovery valid only for an already installed compatible Immortal app with no artifact; make full provisioning require an operator-selected local artifact and report setup/install separately from the final enablement/recovery phase.
    - _Requirements: 3.1-3.3, 3.8-3.9; Design: §§7.1, 7.4_

  - [x] 8.2 Implement the restricted finite-enum `ADBCommand` runner and process boundary.
    - Permit only device enumeration/inspection, provision-file push, Immortal relaunch, agent-manifest read, verified-artifact install, and established setup steps; use a selected/local executable, fixed environment, sanitized output, no shell string, no `sh -c`, no arbitrary path, no remote shell, no package manager, and no generic command method.
    - _Requirements: 3.1-3.3, 3.8-3.9, 11.8-11.9; Design: §7.3_

  - [x] 8.3 Implement `LocalArtifactVerifier` before any install command.
    - Verify readable regular-file access, expected Immortal package identity, accepted signature/certificate policy, exact SHA-256 digest, Android API 28/API 29 compatibility, supported arm64 ABI, and applicable Portal model compatibility; expose each check and block on any failure.
    - _Requirements: 3.3-3.4, 3.10; Design: §7.2_

  - [x] 8.4 Implement the per-operation sandbox/temp workspace and no-download boundary.
    - Restrict handoff files and transcripts to a permission-limited temporary workspace, use security-scoped local artifact/ADB selections, delete workspace data on completion/failure/cancellation where permitted, expose no downloader or artifact URL, and assert that platform-tools/APKs/packages/release artifacts/setup dependencies are never downloaded.
    - _Requirements: 3.5, 3.9-3.10, 9.1, 11.1, 11.8; Design: §§7.2-7.3, 11, 16-17_

  - [x] 8.5 Implement `ProvisioningCoordinator` with mode-specific step gates, retries, cancellation, generation-specific setup steps, `provision.json` handoff, `agent.json` recovery, immediate Keychain storage, LAN revalidation, bearer `/info` verification, and registry commit.
    - Preserve the existing registry on unauthorized/disconnected/unsupported/failed/timed-out flows; identify the failed step with sanitized diagnostics; mark online only after a recovered bearer, admitted LAN endpoint, and successful `/info`; never change Android provisioning scripts.
    - _Requirements: 3.1-3.10, 4.5, 4.7, 9.2, 11.8; Design: §§7.1-7.4, 14_

  - [x]* 8.6 Add the property test for separate provisioning modes and complete artifact verification.
    - **Property 9: Provisioning modes and artifact verification remain separate**
    - Generate enablement/full plans and artifact checks; verify enablement has no install/artifact step, full provisioning requires local artifact, all identity/signature/digest/API/ABI/model checks pass before install, and no download/arbitrary-command step exists.
    - **Validates: Requirements 3.2-3.5, 3.10; Design: §18 Property 9**

  - [x]* 8.7 Add the property test for verification-gated online state.
    - **Property 10: Provisioning cannot create online state before verification**
    - Generate ADB/artifact/setup/manifest failures, timeouts, retries, cancellation, LAN failures, and `/info` results; verify named sanitized failure/retry state and no online management before final bearer verification.
    - **Validates: Requirements 3.6-3.9; Design: §18 Property 10**

  - [x]* 8.8 Add provisioning fixture tests for authorized/unauthorized ADB, disconnects, finite-command rejection, every artifact verification dimension, first-generation installer behavior, temp-workspace cleanup, manifest recovery, retry/cancellation, bearer verification, sanitized output, and no-download behavior.
    - _Requirements: 3.1-3.10, 9.1, 11.8; Design: §§7.2-7.4, 15.2_

- [x] 9. Checkpoint - Run Fleet, discovery, and both provisioning-mode tests before adding settings and bulk mutation planning.
  - Ensure manual PIN pairing remains remote-session-limited, `/info` remains bearer-only, enablement never installs an artifact, full provisioning never downloads inputs, and no Portal is online before bearer verification.
  - _Requirements: 1.1-1.8, 3.1-3.10, 4.1-4.9, 5.1-5.2, 11.1-11.8; Design: §§5-7, 16-17_

- [ ] 10. Implement forward-compatible Settings Registry decoding and approved endpoint adapters
  - [x] 10.1 Implement additive `SettingsRegistrySchema`, `SettingsDomainSchema`, `SettingsControlSchema`, `DecodedControlType`, `JSONValue`, enum options, constraints, visibility, and unknown metadata decoding.
    - Recognize current domains (`screensaver`, `calendar`, `immortal`, `mqtt`, `quickbar`, `fleet`, `chime`, `digitalclock`, `welcome`, `sunrise`) while preserving unknown/future domains, controls, and types as safe read-only compatibility rows.
    - _Requirements: 6.1, 6.3-6.5, 6.14, 11.10; Design: §8.1_

  - [x] 10.2 Implement explicit `SettingsPolicyClassification` and default-deny policy lookup.
    - Require a policy entry before editing; keep unknown, endpoint-bearing, credential-bearing, secret, and unknown-type controls read-only until route, value handling, redaction, and bulk behavior are approved; always classify `maUsername` as sensitive/credential-bearing.
    - _Requirements: 6.2, 6.3, 6.8, 6.14-6.15, 11.10; Design: §8.2_

  - [x] 10.3 Implement `SettingsCoordinator` draft validation, domain-batch apply, applied-key handling, and authoritative read-back.
    - Honor server type/title/section/help/value/default/options/min/max/step/wrap/readOnly/visibility metadata; reject wrong type/range/enum/unclassified/unknown controls locally; submit only approved fields; replace confirmed state with the returned schema without optimistic invalid retries.
    - _Requirements: 6.1-6.8, 6.13-6.14; Design: §§8.1-8.3_

  - [x] 10.4 Implement `SourceAdapter` and `SourceSecretCoordinator` over `/remote/sources` with legacy stripping, explicit migration/replacement, blank-preserve field presence, configured-state metadata, and authoritative source read-back.
    - Use the per-Portal/per-source Keychain boundary; omit blank/omitted credentials, preserve device and Keychain values, block unsupported partial edits without a request, and never reuse a failed legacy cleartext value.
    - _Requirements: 6.9-6.12, 6.16, 9.1-9.5; Design: §8.4_

  - [x] 10.5 Implement separate `ScreensaverAdapter` and `CalendarAdapter` over their documented bearer-only routes.
    - Preserve field-presence/partial-update semantics, applied-field reporting, read-back, last-confirmed values, capability absence, and route credential restrictions; do not substitute `/config` or a generic settings write.
    - _Requirements: 4.1-4.2, 5.4, 6.7-6.8, 6.12-6.14; Design: §§3.3, 6.1, 8.3-8.4_

  - [x] 10.6 Implement the policy-approved Portal multi-room settings bridge for `multiRoomEnabled`, `snapcastHost`, `maPort`, `maUsername`, and masked `maPassword`.
    - Route values through Settings Registry validation and Keychain; omit blank secret fields; report Music resynchronization only after settings acknowledgement and service read-back.
    - _Requirements: 6.2, 6.15, 8.10, 9.2-9.3; Design: §§8.2-8.3, 10.2_

  - [ ]* 10.7 Add the property test for forward-compatible settings decoding and default-deny policy.
    - **Property 11: Settings decoding is forward-compatible and default-deny**
    - Generate domains, controls, type strings, additive metadata, endpoint/credential-bearing fields, and unknown values; verify preservation, safe read-only rendering, metadata honoring, and explicit classification before edit.
    - **Validates: Requirements 6.1-6.5, 6.14, 11.10; Design: §18 Property 11**

  - [ ]* 10.8 Add the property test for settings validation and authoritative read-back.
    - **Property 12: Settings apply uses policy and authoritative read-back**
    - Generate drafts and server outcomes; verify local rejection of invalid/unclassified values, approved-field-only submission, applied-key reporting, returned-schema replacement, and no optimistic retry.
    - **Validates: Requirements 6.6-6.8; Design: §18 Property 12**

  - [ ]* 10.9 Add the property test for legacy source-secret redaction.
    - **Property 13: Legacy source secrets never enter state or UI**
    - Generate every legacy source credential field and migration outcome; verify stripping before UI/state/log/export/registry/evidence and configured-but-reentry-required status after failed migration.
    - **Validates: Requirements 6.9, 6.16, 9.1-9.5; Design: §18 Property 13**

  - [ ]* 10.10 Add the property test for per-Portal/per-source Keychain and blank preservation.
    - **Property 14: Source credentials use per-Portal/per-source Keychain and preserve blanks**
    - Generate Portal/source/field combinations and blank/replacement/unsupported edits; verify exact Keychain isolation, omitted blank fields, preserved device/Keychain values, and zero unsupported requests.
    - **Validates: Requirements 6.10-6.12, 9.1; Design: §18 Property 14**

  - [ ]* 10.11 Add the property test for confirmed state and endpoint action errors.
    - **Property 15: Confirmed state and action errors are never overwritten optimistically**
    - Generate status, settings, source, screensaver, calendar, and approved-action responses; verify applied/read-back fields, last-confirmed rejected values, categorized errors, and no false success.
    - **Validates: Requirements 5.1, 5.5-5.7, 6.7-6.8, 6.13; Design: §18 Property 15**

  - [ ]* 10.12 Add schema and adapter fixture tests for every current domain/control type, unknown domains/controls/types, visibility/sections/defaults/constraints, secret/`hasValue`, `maUsername`, source migration, blank fields, unsupported partial edits, screensaver/calendar route restrictions, and applied read-back.
    - _Requirements: 4.1-4.2, 5.4-5.7, 6.1-6.16, 8.10, 9.1-9.5; Design: §§8.1-8.4, 15.2_

- [ ] 11. Implement target-specific bulk preflight, confirmation, dispatch, and reconciliation
  - [x] 11.1 Implement individual-operation planning and target-specific bulk preflight only after `PortalSessionCoordinator`, `SettingsCoordinator`, and approved source/calendar/screensaver adapters are available and before any dispatch path.
    - For one selected Portal, build a fresh eligible operation from current status/schema/capability/policy/credential state; for multiple targets, build a summary with count, operation, fields, credential scope, connection/auth state, capability state, policy classification, schema constraints, omitted/reduced fields, and sensitive-value impact; mark ineligible targets without substituting credentials or policy.
    - _Requirements: 7.1-7.3, 7.6, 7.8; Design: §9.1_

  - [x] 11.2 Implement explicit confirmation for divergent schemas, reduced per-target operations, and sensitive bulk changes.
    - Name target count, operation, fields, and affected sensitive domain; dispatch zero requests when confirmation is absent or cancelled.
    - _Requirements: 7.2-7.3, 7.6, 7.8; Design: §9.1_

  - [x] 11.3 Implement `BulkOperationEngine` with bounded independent fan-out, per-Portal route plans/Keychain reads, cancellation boundaries, terminal per-target events, authoritative read-back, and truthful aggregation.
    - Continue eligible targets after offline, unauthenticated, incompatible, unclassified, timeout, partial, or rejected results; report success/partial/failure/skipped/cancelled counts and never copy one Portal's result to another.
    - _Requirements: 7.3-7.7, 10.5; Design: §9.2_

  - [ ]* 11.4 Add the property test for target-specific bulk preflight.
    - **Property 16: Bulk preflight is explicit and target-specific**
    - Generate divergent schemas, credentials, capabilities, policy states, constraints, and sensitive fields; verify exact summaries, valid/reduced plans, and confirmation before dispatch.
    - **Validates: Requirements 7.2-7.3, 7.6, 7.8; Design: §18 Property 16**

  - [ ]* 11.5 Add the property test for independent bulk fan-out and aggregation.
    - **Property 17: Bulk fan-out is independent and truthfully aggregated**
    - Generate mixed successful/offline/unauthenticated/incompatible/unclassified/rejected/timed-out/cancelled targets; verify independent dispatch, continued eligible work, terminal read-back per target, and correct aggregate result.
    - **Validates: Requirements 7.4-7.7; Design: §18 Property 17**

  - [ ]* 11.6 Add bulk fixture tests for individual operations, fresh preflight, reduced plans, sensitive confirmation, bounded concurrency, cancellation, stale schema, partial apply, per-target read-back, and aggregate counts.
    - _Requirements: 7.1-7.8; Design: §9_

- [ ] 12. Implement release evidence storage and the gate evaluator
  - [x] 12.1 Implement `GateID`, `GateStatus`, `ReleaseEvidenceRecord`, `ReleaseGateReport`, and `ReleaseEvidenceStore` for sanitized evidence IDs, test outcomes, model/service claims, and unresolved deviations only.
    - Support Security, LAN, Provisioning, Model Matrix, Portal TV, and named service/operation Music Mutation gate identities without storing credentials, raw protocol bodies, ADB manifests, or artifact secrets.
    - _Requirements: 12.1-12.8; Design: §13_

  - [x] 12.2 Implement `ReleaseEvidenceCoordinator` and the gate evaluator.
    - Require passing Security, LAN, Provisioning, and Model Matrix gates for corresponding v1 claims; require Portal TV evidence for a Portal TV claim; require typed contract, sanitized fixtures, Mutation Evidence, service read-back, and a named Music Mutation gate for any enabled mutation; withhold only affected claims when evidence is missing/failed.
    - _Requirements: 8.4-8.5, 12.1-12.8; Design: §§10.3, 13.1_

  - [x] 12.3 Implement machine-readable release reports and CI/task projections for `missing`, `pending`, `passed`, `failed`, and `withheld` states.
    - Ensure a runtime operation cannot become enabled merely because a route or capability is discovered; only explicit evidence and a passed scoped gate can project support.
    - _Requirements: 11.10, 12.1-12.8; Design: §§13.1, 16-17_

  - [ ]* 12.4 Add the property test for release claim withholding.
    - **Property 24: Missing release evidence withholds affected claims**
    - Generate all combinations of mandatory and conditional gate statuses; verify evidence retention, publishable/withheld claim separation, scoped operation/model withholding, and no fallback when a gate is missing or failed.
    - **Validates: Requirements 12.2-12.8; Design: §18 Property 24**

  - [ ]* 12.5 Add evidence-store/evaluator unit tests for sanitized persistence, mandatory gate combinations, model/provisioning/Portal TV scoping, conditional Music Mutation gates, and machine-readable reports.
    - _Requirements: 8.4-8.5, 11.10, 12.1-12.8; Design: §§13.1, 15.2_

- [ ] 13. Implement read-only Music Assistant/Snapcast topology and conditional service-specific mutations
  - [x] 13.1 Define independent Music Assistant/Snapcast configuration, service endpoint, credential references, authentication/connection states, player/client/group/stream models, and topology snapshots.
    - Default Music Assistant to port `8095` and Snapcast control to `1705`; use shared DNS/LAN/reconnect/trust policy and separate Keychain identities; preserve stable service IDs independently from display names.
    - _Requirements: 8.1-8.2, 8.7-8.8, 9.1, 9.7-9.8; Design: §§4, 10-11_

  - [x] 13.2 Implement the read-only Music Assistant WebSocket adapter.
    - Support `/ws`, hello/framing, optional `auth/login` and `auth`, `players/all`, typed topology/transport reads, timeouts, cancellation, and authentication-state separation; a blank optional credential may remain connected-unauthenticated, while rejected credentials suppress mutation.
    - Do not add a generic group-mutation method or speculative request path.
    - _Requirements: 8.1-8.3, 8.6-8.8; Design: §10.1_

  - [x] 13.3 Implement the read-only Snapcast newline-delimited JSON-RPC adapter.
    - Support typed `Server.GetStatus`, streams/groups/clients/hosts, notifications, framing, reconnects, and topology reads; preserve IDs and do not expose `call(method: String, params: JSONValue)` or a generic mutation path.
    - _Requirements: 8.1-8.3, 8.5-8.8; Design: §10.2_

  - [x] 13.4 Implement service authentication-state handling, Portal-to-player/client reconciliation, and ambiguity/offline reporting.
    - Preserve server identifiers and membership despite equal display names, distinguish connected-unauthenticated/authentication/network failures, and report ambiguous mappings or partial service state without silently selecting a member.
    - _Requirements: 8.2, 8.7-8.9; Design: §§10.1-10.2_

  - [x] 13.5 Implement default-deny `MusicCapabilityResolver` and UI/operation projections for group mutations.
    - Keep create, rename, add-member, remove-member, and dissolve unavailable by default; unknown versions, missing evidence, ambiguous mappings, and failed gates remain read-only and emit no request; never mutate through Portal preferences or cross-service fallbacks.
    - _Requirements: 8.3, 8.5-8.6, 8.9, 12.7-12.8; Design: §10.3_

  - [x] 13.6 Implement only explicitly enabled service-specific Music mutation branches.
    - **Conditional, but mandatory when enabled:** for each named Music Assistant or Snapcast operation, implement a typed adapter for one documented Versioned Service Contract, sanitized request/response fixtures, Mutation Evidence ingestion, service-specific read-back, and the corresponding named Release Gate; do not implement a generic mutation API or an unverified operation.
    - _Requirements: 8.4-8.6, 8.9, 12.7-12.8; Design: §§10.3-10.4, 13.1_

  - [ ]* 13.7 Add the property test for the Portal multi-room settings bridge.
    - **Property 21: Portal multi-room settings use policy, Keychain, and read-back ordering**
    - Generate `multiRoomEnabled`, `snapcastHost`, `maPort`, `maUsername`, and masked `maPassword` drafts plus Music Assistant/Snapcast acknowledgement and read-back outcomes; verify only policy-approved fields can apply, credentials use Keychain, blank secret fields preserve existing values, and resynchronization appears only after acknowledgement and current service/status read-back.
    - **Validates: Requirements 8.10, 9.2; Design: §18 Property 21**

  - [ ]* 13.8 Add the property test for service identity and authentication states.
    - **Property 18: Music topology preserves service identity and authentication states**
    - Generate equal names, IDs, memberships, online/offline states, optional credentials, rejected credentials, and ambiguous mappings; verify ID preservation and distinct unauthenticated/authentication/network states.
    - **Validates: Requirements 8.1-8.2, 8.7-8.8; Design: §18 Property 18**

  - [ ]* 13.9 Add the property test for complete mutation evidence.
    - **Property 19: Music mutations require complete service-specific evidence**
    - Generate service/version/operation contracts, fixtures, evidence, read-back, and gate states; verify default-disabled behavior, enabled-only named adapters, no request on missing evidence, and read-back-confirmed success.
    - **Validates: Requirements 8.3-8.5, 12.7; Design: §18 Property 19**

  - [ ]* 13.10 Add the property test for no speculative or cross-service mutation fallback.
    - **Property 20: Music mutation planning has no speculative or cross-service fallback**
    - Generate unknown contracts and workflows naming both services; verify no generic/speculative/Portal-preference mutation, separate service results, and no cross-service atomicity claim.
    - **Validates: Requirements 8.6, 8.9; Design: §18 Property 20**

  - [ ]* 13.11 Add Music Assistant/Snapcast fixture tests for read-only handshake/auth/topology, identifier preservation, ambiguous mappings, authentication failure separation, default-disabled group operations, and conditional typed mutation contract/read-back when enabled.
    - _Requirements: 8.1-8.10, 12.7-12.8; Design: §§10.1-10.4, 15.2_

- [ ] 14. Implement the native SwiftUI/AppKit desktop workflow and commands
  - [x] 14.1 Implement the `@MainActor PortalManagerStore`, navigation state, immutable non-secret snapshots, command state, coordinator event routing, and explicit selected-Portal semantics.
    - Keep network, Keychain, ADB, and service work off the main actor; never silently target the last-active or another Portal.
    - _Requirements: 5.1-5.2, 7.1, 10.1-10.6; Design: §§2.1, 12.2_

  - [x] 14.2 Implement `NavigationSplitView`/sidebar/detail overview UI for discovery candidates, Portal Registry entries, assurance/credential badges, model/API/capability summaries, last refresh/contact, Music hosts, and sanitized errors/recovery actions.
    - Show remote-session-limited state separately from bearer identity/health and show unsupported models/capabilities explicitly.
    - _Requirements: 1.1-1.8, 2.1-2.6, 4.4-4.8, 5.1-5.7, 10.1-10.2, 10.6; Design: §§3.4, 5, 12.1_

  - [x] 14.3 Implement schema-driven settings, source, calendar, and screensaver views.
    - Render server metadata and unknown controls read-only, preserve policy/read-only/secret/blank semantics, show applied/omitted/read-back state, and never expose raw preference keys, cleartext legacy secrets, or excluded routes.
    - _Requirements: 5.4, 5.6, 6.1-6.16, 9.3-9.6, 11.9-11.10; Design: §§6.1, 8, 11-12_

  - [x] 14.4 Implement provisioning/recovery, Bulk Operation, read-only Music Groups, conditional named mutation, and release-evidence detail views.
    - Show ADB/artifact prerequisites, identity/signature/digest/API/ABI/model verification, separate provisioning steps, target-specific preflight, per-target read-back, topology identifiers, disabled mutation reasons, evidence statuses, and safe cancellation.
    - _Requirements: 3.1-3.10, 7.1-7.8, 8.1-8.10, 12.1-12.8; Design: §§7, 9-10, 13, 12.1_

  - [x] 14.5 Implement `AppDelegate`, AppKit `Commands`/`NSMenu`, key equivalents, `NSOpenPanel`, security-scoped bookmarks, and typed command enablement for discovery refresh, Manual-IP onboarding, navigation/selection, status refresh, identify/reaffirm, apply/retry, provisioning, Music refresh, and Bulk Operation confirmation/cancellation.
    - Keep unavailable commands discoverable but disabled or explanatory; never add commands for excluded routes, cloud/relay access, raw files/logs, shell, install/update escape hatches, or unverified music mutations.
    - _Requirements: 5.4-5.6, 10.3-10.4, 11.9; Design: §§1, 12.3, 17_

  - [x] 14.6 Add reusable progress, error, confirmation, accessibility/VoiceOver, and Portal TV no-touch presentation components.
    - Preserve final per-target/per-step results after cancellation, expose safe cancellation boundaries, and make discovery, authentication, approved management, provisioning, and native UI flows operate without touchscreen or D-pad input on the Portal.
    - _Requirements: 2.4, 3.8-3.9, 7.2-7.7, 10.5-10.6, 12.6; Design: §§7.4, 9.2, 12.1-12.3_

  - [ ]* 14.7 Add the property test for explicit command targeting and local data-plane plans.
    - **Property 23: Commands and data-plane plans remain explicitly targeted and local**
    - Generate selection, credential/capability, operation, and active-operation states; verify explicit target-or-disabled behavior and LAN/USB-only plans without cloud, public, shell, package-manager, or arbitrary-server actions.
    - **Validates: Requirements 10.4, 11.1, 11.8; Design: §18 Property 23**

  - [ ]* 14.8 Add UI automation tests for split-view/sidebar selection, assurance badges, Manual-IP pairing state, unknown read-only controls, source-secret masking, disabled prerequisites, command/key-equivalent behavior, confirmation copy, progress/cancellation, accessibility labels, release evidence, and Portal TV fixture workflows.
    - _Requirements: 1.3-1.5, 2.4-2.6, 5.1-5.7, 6.1-6.16, 7.2-7.8, 10.1-10.6, 12.1-12.8; Design: §§8, 12-13, 15.2_

- [ ] 15. Add integrated smoke checks and mandatory release-validation gates
  - [~] 15.1 Add automated macOS integration/smoke checks for Xcode build/test separation, startup/resume without cloud registration, Keychain-only restoration, admitted LAN/public rejection, DNS-before-credential ordering, every-reconnect revalidation, no-follow redirects, closed route/UI allowlists, no-download provisioning, and unchanged Android/Gradle boundaries.
    - Use loopback/fake Fleet, Bonjour, Music Assistant, Snapcast, Keychain, registry, ADB, artifact, and evidence boundaries; do not turn external services or manual acceptance into unbounded tests.
    - _Requirements: 1.1-1.8, 3.1-3.10, 4.1-4.10, 5.1-5.7, 8.1-8.10, 9.1-9.9, 11.1-11.11; Design: §§4-7, 10-11, 15-17_

  - [~] 15.2 Add **mandatory Security Release Validation** and emit a passing/failing `security` gate evidence record.
    - Automate credential-matrix route tests, Keychain-only Portal/source/Music/Snapcast storage, `maUsername` sensitivity, per-Portal/per-source isolation and migration, legacy-source redaction, PIN handling, closed route/action allowlists, unknown-settings read-only behavior, secret sink audits, and redirect credential non-forwarding.
    - _Requirements: 4.1-4.10, 5.4-5.6, 6.1-6.16, 9.1-9.9, 12.2, 12.8; Design: §§6, 8, 11, 13.1, 15.2_

  - [~] 15.3 Add **mandatory LAN Release Validation** and emit a passing/failing `lan` gate evidence record.
    - Automate IPv4 private/link-local, IPv6 loopback/unique-local/link-local, bracketed IPv6 with/without zones, malformed/ambiguous endpoint, DNS public-address, no-resolution, reconnect revalidation, no-follow redirect, and service-kind/resolved-endpoint Trust Warning Scope cases for Portal, Music Assistant, and Snapcast.
    - _Requirements: 1.3, 8.1, 9.7-9.8, 11.2-11.7, 12.3; Design: §§4.1-4.5, 13.1, 15.2_

  - [~] 15.4 Add **mandatory Provisioning Release Validation** and emit a passing/failing `provisioning` gate evidence record.
    - Automate both Fleet Agent Enablement/Recovery without APK installation and Full USB Provisioning with a local verified artifact; cover finite-enum ADB, identity/signature/digest/API/ABI/model checks, sandbox/temp cleanup, `provision.json`/`agent.json`, bearer `/info`, retries/failures, operator-provided tools, sanitized output, and proof that no platform-tools/APK/package/release download occurs.
    - _Requirements: 3.1-3.10, 4.5, 4.7, 9.1-9.2, 11.8, 12.4, 12.8; Design: §§7, 11, 13.1, 15.2_

  - [~] 15.5 Add **mandatory Model Matrix Validation** and emit evidence for every claimed Compatible Portal family and applicable Android API level.
    - Implement profile-driven automated validation for 2018 Portal, Portal+, first-generation Portal+, Portal Go, Portal Mini, Portal (gen-2), and Portal TV across API 28/API 29 where applicable, including arm64, model/capability classification, DHCP changes, mDNS loss, provisioning/recovery paths, and the exact claim withheld or published for each profile.
    - _Requirements: 2.1-2.6, 3.1-3.10, 11.11, 12.5, 12.8; Design: §§3.2, 7, 13.1, 15.2_

  - [~] 15.6 Add **mandatory Portal TV Validation** and emit a passing/failing `portalTV` gate evidence record.
    - Automate discovery, bearer verification, approved settings/actions, Fleet Agent Enablement/Recovery, Full USB Provisioning, Native UI, command, cancellation, and read-back workflows using a Portal TV profile without touchscreen or D-pad input on the Portal.
    - _Requirements: 2.4, 3.1-3.10, 5.1-5.7, 6.1-6.16, 10.1-10.6, 12.6, 12.8; Design: §§3.2, 7, 12-13, 15.2_

  - [~] 15.7 Add **conditional mandatory Music Mutation Release Validation** for every enabled named mutation.
    - If a release enables a Music Assistant or Snapcast mutation, automate the exact Versioned Service Contract, sanitized fixtures, Mutation Evidence, service-specific request/response and read-back, rejection/partial handling, scoped `musicMutation` gate, and no-cross-service-atomicity assertion; if no mutation is enabled, automate the default-disabled read-only topology claim and absence of mutation requests.
    - _Requirements: 8.3-8.10, 12.7-12.8; Design: §§10.3-10.4, 13.1, 15.2_

- [~] 16. Final checkpoint - Run the complete required validation set and produce the release report.
  - Ensure all mandatory Security, LAN, Provisioning, Model Matrix, and claimed Portal TV gates pass; ensure conditional Music Mutation validation passes whenever enabled; withhold unsupported claims; and verify v1 exclusions, no-download behavior, and the Android non-change boundary remain intact.
  - _Requirements: 8.3-8.10, 9.1-9.9, 11.1-11.11, 12.1-12.8; Design: §§10-14, 16-17_

## Notes

- Tasks marked `*` are optional property, unit, fixture, integration, or UI tests for the Fast Task MVP. Tasks 15.2-15.7 are mandatory release-validation tasks and intentionally have no optional marker. Task 13.6 and task 15.7 are conditional but mandatory whenever a named Music Assistant/Snapcast mutation is enabled.
- The design contains 24 correctness properties in §18. Each property is represented by its own property-test subtask and should run at least 100 generated cases with deterministic seeds, injected fakes, and the tag `Feature: macos-portal-manager, Property N: ...`.
- Bulk preflight is deliberately downstream of the credential/session and settings/source/calendar/screensaver coordinators. No dispatch task may bypass the target-specific preflight or confirmation gates.
- Music Assistant and Snapcast topology is read-only by default. No generic mutation method, speculative probe, Portal-preference fallback, or cross-service atomic operation may be added. Only a named, typed, versioned contract with fixtures, Mutation Evidence, read-back, and a passed scoped gate can enable one operation.
- LAN admission is shared by Portal, discovery, manual endpoints, provisioning verification, Music Assistant, and Snapcast. DNS resolution and LAN validation precede every credential read/socket attempt, including reconnects; redirect acknowledgements never bypass policy.
- v1 exclusions remain absent from route enums, planners, UI commands, bulk models, and release claims: raw files/logs, arbitrary endpoints, `/apps`, `/config`, `/fs/*`, `/logcat`, `/install`, `/update`, `/dev`, `/diag`, reboot, shell, developer mode, cloud/relay/public-IP access, broad server administration, downloaded tools/packages/artifacts, and unverified Music Group mutation.
- The Android non-change boundary is strict: do not modify `app/`, Android/Kotlin Fleet Agent routes, authentication, Settings Registry definitions/semantics, `settings.gradle.kts`, Android CI, provisioning scripts, or Portal runtime behavior. The macOS project consumes those contracts only.
- Checkpoint entries are represented in the dependency graph's `checkpoints` metadata. The `waves` array contains only executable leaf task IDs, with every leaf appearing exactly once.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.1"] },
    { "id": 2, "tasks": ["1.4", "2.2", "2.4"] },
    { "id": 3, "tasks": ["2.3"] },
    { "id": 4, "tasks": ["2.5", "2.6", "2.7"] },
    { "id": 5, "tasks": ["4.1"] },
    { "id": 6, "tasks": ["4.2", "4.3", "4.5", "4.6"] },
    { "id": 7, "tasks": ["4.4", "4.7", "4.8", "4.9"] },
    { "id": 8, "tasks": ["6.1"] },
    { "id": 9, "tasks": ["6.2"] },
    { "id": 10, "tasks": ["6.3"] },
    { "id": 11, "tasks": ["6.4"] },
    { "id": 12, "tasks": ["6.5", "6.6", "6.7", "6.8"] },
    { "id": 13, "tasks": ["7.1"] },
    { "id": 14, "tasks": ["7.2"] },
    { "id": 15, "tasks": ["7.3"] },
    { "id": 16, "tasks": ["7.4"] },
    { "id": 17, "tasks": ["7.5", "7.6", "7.7"] },
    { "id": 18, "tasks": ["8.1"] },
    { "id": 19, "tasks": ["8.2", "8.3"] },
    { "id": 20, "tasks": ["8.4"] },
    { "id": 21, "tasks": ["8.5"] },
    { "id": 22, "tasks": ["8.6", "8.7", "8.8"] },
    { "id": 23, "tasks": ["10.1"] },
    { "id": 24, "tasks": ["10.2"] },
    { "id": 25, "tasks": ["10.3"] },
    { "id": 26, "tasks": ["10.4", "10.5", "10.6"] },
    { "id": 27, "tasks": ["10.7", "10.8", "10.9", "10.10", "10.11", "10.12"] },
    { "id": 28, "tasks": ["11.1"] },
    { "id": 29, "tasks": ["11.2"] },
    { "id": 30, "tasks": ["11.3"] },
    { "id": 31, "tasks": ["11.4", "11.5", "11.6"] },
    { "id": 32, "tasks": ["12.1"] },
    { "id": 33, "tasks": ["12.2"] },
    { "id": 34, "tasks": ["12.3", "12.4", "12.5"] },
    { "id": 35, "tasks": ["13.1"] },
    { "id": 36, "tasks": ["13.2", "13.3"] },
    { "id": 37, "tasks": ["13.4"] },
    { "id": 38, "tasks": ["13.5"] },
    { "id": 39, "tasks": ["13.6"] },
    { "id": 40, "tasks": ["13.7", "13.8", "13.9", "13.10", "13.11"] },
    { "id": 41, "tasks": ["14.1"] },
    { "id": 42, "tasks": ["14.2", "14.3", "14.4", "14.5"] },
    { "id": 43, "tasks": ["14.6"] },
    { "id": 44, "tasks": ["14.7", "14.8"] },
    { "id": 45, "tasks": ["15.1"] },
    { "id": 46, "tasks": ["15.2", "15.3", "15.4", "15.5", "15.6", "15.7"] }
  ],
  "checkpoints": [
    { "id": "CP-1", "task": "3", "afterWave": 4 },
    { "id": "CP-2", "task": "5", "afterWave": 7 },
    { "id": "CP-3", "task": "9", "afterWave": 22 },
    { "id": "CP-4", "task": "16", "afterWave": 46 }
  ]
}
```
