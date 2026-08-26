# macOS Portal Manager Design

## 1. Design authority, scope, and repository alignment

The Portal Manager is a separate native macOS SwiftUI/AppKit Xcode project under `macos/`. It is a consumer of the existing Immortal Android contracts; it is not a replacement or extension of the Android application. Version one is a LAN appliance manager with a local USB/ADB onboarding path.

The macOS target MUST NOT modify:

- Android/Kotlin runtime code, including `FleetRoutes`, `RemoteRoutes`, `RemotePairing`, `SettingsRegistry`, or the Portal-side Music Assistant/Snapcast clients;
- Fleet Agent authentication, route names, HTTP methods, response semantics, or provisioning handoff files;
- `provisioning/provision.sh`, `provisioning/provision.ps1`, `provisioning/config.env`, or Portal behavior;
- the Android package/application identity, settings persistence, or side-effect implementations.

The macOS application owns only local orchestration, policy, presentation, secure credential storage, and typed adapters around those existing contracts. No UI component constructs a URL, chooses a credential, invokes a process, or sends an arbitrary protocol request.

### 1.1 Current repository contracts consumed by the design

The design is synchronized to the repository behavior rather than to a hypothetical generic Fleet API:

- `FleetRoutes.handle` routes `/remote/*` before the direct Fleet bearer gate. Every direct route, including `GET /info`, `/screensaver`, `/calendar`, and `POST /action`, requires the Fleet bearer in `Authorization: Bearer <token>`. Direct routes such as `/apps`, `/config`, `/fs/*`, `/logcat`, `/install`, `/update`, `/dev`, and `/diag` exist in Android but are excluded from the macOS product.
- `RemoteRoutes` exposes `/remote/pair` as a LAN-guarded POST that accepts a PIN body without an existing credential. Its authenticated routes accept either the Fleet bearer or a paired session, but the macOS route planner admits only `/remote/settings` and `/remote/sources` from that surface. `/remote/ui`, input/media/preset/roster/device routes, album lookup, and every other `/remote/*` route remain excluded.
- `RemotePairing` generates an in-memory six-digit PIN with a five-minute lifetime, clears it on successful redemption, and persists the returned session token on Android. The macOS app sends a PIN once, discards its local input on every outcome, and stores the returned Remote Session Credential only in the Mac Keychain. The Android persistence mechanism is not changed.
- `SettingsRegistry` and `SettingsDomain` build a schema from visible `SettingSpec` controls and apply partial domain batches through existing setters. `BoolSpec`, `IntSpec`, `EnumSpec`, `StringSpec`, derived values, and navigation-only rows are represented by typed macOS schema models. The Android schema hides cleartext for `StringSpec(secret = true)` by returning an empty value plus `hasValue`; the macOS policy additionally treats `maUsername` as sensitive even when the Android schema does not mark it secret.
- `SettingSpec` rejects malformed scalar values and out-of-range non-wrapping integers, while existing enum coercion and device-side clamping remain Android behavior. The macOS client validates the returned schema and policy before dispatch and uses the returned schema/applied set as authoritative read-back; it does not change Android validation semantics.
- `MaControl` uses a local Music Assistant WebSocket, optional `auth/login` and `auth` messages, `players/all`, and `players/cmd/*`; the configured/default Music Assistant port is `8095`. Authentication is optional for an unauthenticated local server, but a supplied invalid credential is an authentication failure.
- `SnapcastControlClient` uses the local Snapcast control socket, normally port `1705`, sends `Server.GetStatus`, and reads streams, groups, clients, and topology notifications. The repository has no stable group-mutation client. The macOS product therefore exposes topology inspection only unless a future named mutation satisfies the explicit contract/evidence/release gate below.
- `provisioning/provision.sh --fleet` demonstrates the Fleet Agent Enablement/Recovery boundary: write `provision.json` under the Immortal external files handoff directory, relaunch Immortal, poll/read `agent.json`, and recover the token and metadata. The script's general `resolve_adb`, APK/release downloads, optional app installs, Shizuku, Alexa, and broad device-tweak behavior is not an acceptable macOS command surface.
- Full USB Provisioning is a separate local operation that installs/configures Immortal from a verified local artifact and then reports Fleet Agent Enablement/Recovery as a distinct phase. The macOS app does not invoke the provisioning shell script as an opaque shell command; it uses a typed, allowlisted local backend for only the established steps required by this product.

### 1.2 Version-one data-plane boundaries

Allowed data-plane connections are:

1. Bonjour/mDNS browsing for `_immortal-remote._tcp.` on active local interfaces;
2. HTTP to a resolved Portal Fleet Agent endpoint admitted by the shared LAN policy;
3. a local Music Assistant WebSocket and local Snapcast JSON-RPC control connection admitted by the same policy;
4. an operator-attached USB/ADB connection using a selected local executable and, for Full USB Provisioning, a selected local artifact.

The application has no cloud account, relay, public-IP management, remote shell, package-manager operation, broad server-administration surface, arbitrary Fleet route, or automatic artifact/tool downloader. A local artifact may be selected from disk or attached local media; obtaining it from an external service is outside the application.

## 2. Architecture

The project uses Swift concurrency and a unidirectional, actor-isolated architecture:

```text
SwiftUI views + AppKit application shell
                    |
                    v
@MainActor PortalManagerStore / NavigationState / CommandState
                    |
                    v
Application coordinators and policy gates
  Discovery       PortalSession       Settings/Source
  Provisioning    BulkOperation       Music
  ReleaseEvidence / GateProjection
                    |
                    v
Pure domain models, parsers, planners, reducers, redactors
                    |
                    v
Infrastructure ports and actors
  DNS/LAN       Bonjour       Fleet HTTP       Keychain/Registry
  ADB runner    Artifact verifier             MA WebSocket
  Snapcast RPC  Redactor      Evidence store
                    |
                    v
Validated LAN endpoints or operator-attached USB/ADB
```

### 2.1 Layers and responsibilities

**Domain** contains value types and deterministic logic: endpoint parsing, IPv4/IPv6/LAN classification, trust-warning scope, route and credential planning, settings decoding/policy, source field presence, capability classification, state reducers, bulk plans, Music mutation eligibility, redaction, and release-gate evaluation. Domain code imports neither SwiftUI nor AppKit.

**Application** contains use cases and orchestration: discovery, onboarding, bearer verification, PIN pairing, credential lifecycle, settings/source operations, provisioning, bulk fan-out, Music topology, and release evidence. Coordinators depend on protocols and injected clocks/transports/stores; they do not depend directly on `URLSession`, `Process`, Keychain APIs, or AppKit.

**Infrastructure** implements Bonjour, DNS, HTTP, WebSocket, Snapcast JSON-RPC, Keychain, registry persistence, ADB/process execution, artifact inspection, redaction sinks, and evidence storage. Infrastructure adapters are actors or otherwise isolated from the main actor and have deterministic fakes.

**UI** projects immutable, non-secret snapshots into SwiftUI views and dispatches typed intents. It has no route strings, Android preference keys, shell commands, arbitrary JSON-RPC method names, or credential values.

**AppKit shell** owns `AppDelegate`, application commands, menu/key-equivalent registration, `NSOpenPanel`, security-scoped local-file access, alerts, lifecycle, and cancellation presentation. AppKit also owns the local-artifact/ADB executable selection flow; the selected values are passed to application protocols, not embedded in views.

**Release evidence** is a read-only subsystem. It records sanitized evidence IDs, test results, claims, and deviations and projects gate status into the UI. It never broadens the runtime route allowlist merely because a server advertises a new route or a service responds to an exploratory request.

### 2.2 Suggested project layout

```text
macos/
  PortalManager.xcodeproj
  PortalManager/
    App/
      PortalManagerApp.swift
      AppDelegate.swift
      AppCommands.swift
      DependencyContainer.swift
      SceneNavigation.swift
    Domain/
      Portal.swift
      PortalRegistry.swift
      PortalIdentity.swift
      PortalCapabilities.swift
      Compatibility.swift
      ConnectionState.swift
      CredentialPolicy.swift
      FleetRoute.swift
      FleetOperation.swift
      LANPolicy.swift
      TrustWarningScope.swift
      SettingsSchema.swift
      SettingsPolicy.swift
      SourceMutation.swift
      Provisioning.swift
      MusicTopology.swift
      MusicMutationCapability.swift
      ReleaseEvidence.swift
      ManagerError.swift
    Application/
      PortalManagerStore.swift
      DiscoveryCoordinator.swift
      PortalSessionCoordinator.swift
      FleetOperationCoordinator.swift
      ProvisioningCoordinator.swift
      SettingsCoordinator.swift
      SourceSecretCoordinator.swift
      BulkOperationEngine.swift
      MusicCoordinator.swift
      ReleaseEvidenceCoordinator.swift
    Infrastructure/
      Discovery/BonjourBrowser.swift
      Discovery/DNSResolver.swift
      Fleet/FleetHTTPClient.swift
      Fleet/HTTPTransport.swift
      Fleet/FleetResponseClassifier.swift
      LAN/LANPolicy.swift
      LAN/TrustWarningStore.swift
      Credentials/CredentialStore.swift
      Credentials/KeychainCredentialStore.swift
      Registry/RegistryStore.swift
      Registry/JSONRegistryStore.swift
      Provisioning/ADBRunner.swift
      Provisioning/ArtifactVerifier.swift
      Provisioning/ProvisioningBackend.swift
      Provisioning/TempWorkspace.swift
      Music/MusicAssistantWebSocketClient.swift
      Music/SnapcastJSONRPCClient.swift
      Music/TypedMutationAdapters.swift
      Evidence/ReleaseEvidenceStore.swift
      Support/Clock.swift
      Support/Redactor.swift
      Support/Logger.swift
    UI/
      RootSplitView.swift
      PortalSidebar.swift
      PortalDetailView.swift
      OverviewView.swift
      SettingsView.swift
      SettingsControlView.swift
      SourcesView.swift
      ScreensaverView.swift
      CalendarView.swift
      ProvisioningView.swift
      MusicGroupsView.swift
      BulkOperationView.swift
      ReleaseEvidenceView.swift
      Components/
    Resources/
      Assets.xcassets
      Localizable.xcstrings
  PortalManagerTests/
    Fixtures/{Fleet,Pairing,Sources,Settings,Provisioning,MusicAssistant,Snapcast,ReleaseEvidence}
    Domain/
    Application/
    Infrastructure/
  PortalManagerUITests/
```

No Android or Gradle source is added to this project.

## 3. Domain model and assurance states

### 3.1 Portal identity and registry

A local `PortalID` is the stable registry key. A serial or other authenticated hardware identifier is a reconciliation key, not the registry key itself. A service name, display name, hostname, or IP address is never identity by itself.

```swift
struct PortalID: Hashable, Codable, Sendable {
    let rawValue: UUID
}

struct PortalIdentity: Codable, Sendable, Equatable {
    var portalID: PortalID
    var serial: String?
    var name: String
    var model: String
    var device: String?
    var rawModel: String
    var androidAPILevel: Int?
    var immortalVersion: AppVersion?
}

struct LANEndpoint: Codable, Sendable, Equatable {
    var hostOrAddress: String
    var port: UInt16
    var addressFamily: AddressFamily
    var interfaceZone: String?
    var source: EndpointSource
    var lastAuthenticatedAt: Date?
}

enum EndpointSource: Codable, Sendable {
    case mdns(serviceName: String)
    case manual
    case provisioning
    case authenticatedRefresh
}

struct PortalRegistryEntry: Codable, Sendable, Equatable {
    var id: PortalID
    var identity: PortalIdentity?
    var endpoint: LANEndpoint?
    var discoveredEndpoints: [LANEndpoint]
    var capabilities: PortalCapabilities?
    var credentialReferences: [CredentialReference]
    var lastSuccessfulContact: Date?
    var lastConfirmedStatus: PortalStatus?
    var connectionState: ConnectionState
    var policyMetadata: PortalPolicyMetadata
}
```

`JSONRegistryStore` persists only non-secret identity, endpoint history, capability/policy metadata, last confirmed state, UI-safe error history, and opaque Keychain references. It never persists bearer tokens, remote sessions, PINs, source credentials, Music credentials, Snapcast credentials, authorization headers, raw response bodies, raw `agent.json`, or cleartext diagnostic/process output.

Identity is updated only after a valid bearer-authenticated `/info` response, or after a recovered manifest is followed by LAN admission and successful bearer `/info`. An mDNS record, manual endpoint, remote session, or unverified manifest remains untrusted.

### 3.2 Model, API, capability, and compatibility assessment

```swift
enum PortalModelFamily: String, Codable, Sendable {
    case portal2018
    case portalPlus
    case portalPlusFirstGeneration
    case portalGo
    case portalMini
    case portalGen2
    case portalTV
    case unknown
}

struct PortalCapabilities: Codable, Sendable, Equatable {
    var modelFamily: PortalModelFamily
    var androidAPILevel: Int?
    var fleetInfo: Bool
    var settingsRegistry: Bool
    var sources: Bool
    var screensaver: Bool
    var calendar: Bool
    var identify: Bool
    var reaffirm: Bool
    var rawAdvertisedCapabilities: [String: JSONValue]
}

enum CompatibilityAssessment: Codable, Sendable, Equatable {
    case compatible
    case warning(reason: CompatibilityReason)
    case operationUnavailable(operation: String, reason: String)
}
```

The classifier preserves every relevant `/info` field: `name`, `model`, `device`, `apiLevel`, application version, IP/address, port, presence/screen data when supplied, and advertised capabilities. It maps the supported 2018 Portal, Portal+, first-generation Portal+, Portal Go, Portal Mini, Portal (gen-2), and Portal TV families while retaining the raw model string.

Eligibility is calculated independently from model labels. API 28/API 29, endpoint presence, and advertised capabilities are separate inputs. An unknown model family is not itself a compatibility warning. A warning is projected only when the detected API level is unsupported or the selected operation lacks a required capability. A known or unknown model with no detected incompatibility therefore remains usable for the capabilities actually verified. Missing `/remote/settings`, `/remote/sources`, `/calendar`, or `/screensaver` disables only the affected operation and never causes a raw `/config` substitute.

Portal TV uses the same Fleet API configuration workflow and native macOS controls; no touchscreen or D-pad input on the Portal is required. Portal TV support claims remain subject to Portal TV Validation.

### 3.3 Connection and assurance state

```swift
enum ConnectionState: Codable, Sendable, Equatable {
    case discovered(candidate: DiscoveryReference)
    case resolving(input: String)
    case lanValidated(endpoint: LANEndpoint, trustScope: TrustWarningScope)
    case pairingRequired(endpoint: LANEndpoint)
    case remoteSessionPaired(lastPairedAt: Date)
    case remoteSessionReady(lastReadAt: Date?)
    case bearerVerificationRequired(reason: String)
    case bearerAuthenticated(identity: PortalIdentity, verifiedAt: Date)
    case online(lastRefresh: Date, latencyMs: Int)
    case provisioning(mode: ProvisioningMode, step: ProvisioningStepID)
    case offline(lastContact: Date?, reason: String)
    case reauthenticationRequired(kind: CredentialKind, reason: String)
    case unsupported(reason: String)
    case error(ManagerError)
}
```

Required transitions are assurance-preserving:

- `discovered -> resolving -> lanValidated`; failed resolution never reads a credential or opens a socket.
- `lanValidated -> pairingRequired -> remoteSessionPaired`; this path does not require mDNS and does not establish identity or health.
- `lanValidated -> bearerVerificationRequired -> bearerAuthenticated -> online`; `/info` success is mandatory for identity, health, and bearer-only operation availability.
- `remoteSessionPaired -> remoteSessionReady` only after an approved remote settings/source read; the state remains remote-session limited.
- A credential-specific `401` or revocation becomes `reauthenticationRequired` for that credential kind; valid unrelated references and non-secret identity metadata remain.
- Transport loss becomes `offline` with last contact and retry/edit actions; it does not delete the registry entry.
- Provisioning cannot transition to `online` until the recovered bearer, admitted endpoint, and `/info` verification all succeed.

The UI never labels a remote session as verified identity, verified health, bearer authentication, or full Portal management.

## 4. LAN policy, endpoint parsing, DNS ordering, and trust warnings

### 4.1 Shared LAN policy

`LANPolicy` is a pure validator used for Portal HTTP, Music Assistant, Snapcast, discovery candidates, manual endpoints, reconnects, and provisioning verification. It accepts:

- IPv4 loopback, `10/8`, `172.16/12`, `192.168/16`, and `169.254/16`;
- IPv6 loopback `::1`, unique-local `fc00::/7`, and link-local `fe80::/10`;
- a valid link-local interface zone when one is supplied.

It rejects malformed hosts, unresolved destinations, public addresses, unsupported address families, and any DNS result set with no permitted address. A hostname is never considered LAN merely because it looks local.

### 4.2 Manual endpoint parser

`ManualEndpointParser` accepts `host`, `host:port`, `[IPv6]`, and `[IPv6]:port`. An omitted Portal Agent port defaults to `8723`. Percent-encoded or native link-local zones such as `[fe80::1%25en0]:8723` are decoded for policy evaluation and retained for connection. An explicit IPv6 host-and-port input must be bracketed; an ambiguous unbracketed IPv6 string with an appended port is rejected. The parser marks a successfully admitted manual endpoint as `.manual` and does not require an mDNS record.

### 4.3 Mandatory credentialed connection ordering

Every Portal, Music Assistant, Snapcast, pairing, and reconnect attempt follows this sequence:

```text
parse endpoint
  -> resolve hostname/literal and preserve IPv6 interface zone
  -> select a permitted resolved address
  -> apply LANPolicy
  -> derive normalized TrustWarningScope
  -> obtain/display non-secret trust acknowledgement if needed
  -> read the required Keychain credential, if any
  -> construct a typed request/protocol handshake
  -> open the connection
  -> send credentials only in the established protocol location
```

A public or unresolved result fails before Keychain access and before socket creation. A trust acknowledgement never bypasses LAN validation. Reconnects resolve again, select again, reapply policy again, and repeat trust-scope evaluation before reading a credential or opening a socket.

`DNSResolver` and `ResolvedAddressSelector` are injected ports. A hostname with multiple addresses may use only an address that passes policy. The selected endpoint is attempt-scoped and is not trusted on the next reconnect without revalidation.

### 4.4 Redirect policy

`HTTPTransport` disables automatic redirect following. Any `3xx` response or redirect target is classified as `redirectRejected`; no request is constructed for the `Location` target and no credential is forwarded. Redirect targets are not an alternate admission path. WebSocket and Snapcast clients have no redirect fallback.

### 4.5 Trust Warning Scope

```swift
struct TrustWarningScope: Codable, Hashable, Sendable {
    var serviceKind: ServiceKind       // portal, musicAssistant, snapcast
    var protocolName: String           // http, ws, tcp-json-rpc
    var resolvedHostOrAddress: String
    var port: UInt16
    var interfaceZone: String?
}
```

The first credentialed connection for an exact service/protocol/resolved endpoint/port/zone scope shows a local-network trust warning. The acknowledgement stores only the non-secret scope and timestamp. A changed service kind, protocol, address, port, or zone requires a new acknowledgement. The acknowledgement never bypasses DNS, LAN, reconnect, route, or Keychain policy.

## 5. Discovery, manual onboarding, and registry reconciliation

`BonjourBrowser` wraps `NWBrowser`/Bonjour for `_immortal-remote._tcp.` on active local interfaces and emits service name, resolved host/address, port, interface, add/change/remove, and resolution-error events. The event is an untrusted `DiscoveryCandidate`; mDNS metadata is not identity and service metadata is never identity.

`DiscoveryCoordinator` applies DNS and LAN policy after resolution. It sends `/info` only when a supplied Verified Bearer Credential is available. It sends no `/info` request without that credential. A valid bearer-authenticated `/info` response is the only discovery promotion path to an identity-verified Portal. A discovered candidate with no bearer can remain a pairing candidate, but it cannot display verified identity/health.

Manual onboarding uses the same parser, resolver, policy, trust scope, and reconciliation logic and marks the source `.manual`. A LAN-valid manual endpoint can redeem a PIN directly, even when mDNS is unavailable or absent.

Reconciliation order is:

1. authenticated serial/hardware identity;
2. another authenticated stable identity tuple from `/info` or verified provisioning;
3. authenticated host and port as a temporary fallback.

Duplicate mDNS/manual/provisioning records merge into one registry entry. The merge retains valid credential references, alternate endpoints, last confirmed state, and the newest endpoint that completed an authenticated probe. A failed probe cannot replace a working endpoint or erase identity. When an established Portal becomes unreachable, its entry remains available with `offline`, last successful contact, sanitized reason, and retry/edit actions.

## 6. Exact Fleet route and credential planning

### 6.1 Closed routes and operations

The macOS domain model cannot represent an arbitrary Fleet path, arbitrary action name, generic `/remote/*` call, or generic JSON body:

```swift
enum FleetRoute: Codable, Sendable, Equatable {
    case info
    case remotePair
    case remoteSettings
    case remoteSources
    case screensaver
    case calendar
    case action(ApprovedAction)
}

enum ApprovedAction: String, Codable, Sendable {
    case identify
    case reaffirm
}

enum CredentialKind: String, Codable, Sendable {
    case verifiedBearer
    case remoteSession
}
```

The operation planner has no representation for `/apps`, `/config`, `/fs/*`, `/logcat`, `/install`, `/update`, `/dev`, `/diag`, `/remote/ui`, remote input/media/preset/roster/device routes, arbitrary `/remote/*`, reboot, shell, developer mode, or broad server administration. An excluded intent is handled by the local exclusion gate described in §11; it never becomes a transport request.

### 6.2 Credential matrix

| Fleet operation | Verified Bearer Credential | Remote Session Credential | Plan behavior |
|---|---:|---:|---|
| `POST /remote/pair` | Not required | Not required | Send a validated six-digit PIN in the JSON body after LAN admission; do not read an existing credential. |
| `GET /info` | Required | Not permitted | A valid response verifies Portal identity and health. |
| `GET`/`POST /remote/settings` | Permitted | Permitted only with explicit exact-operation approval | Session use is approved separately for the exact route, method, domain/action, and target Portal. |
| `GET`/`POST /remote/sources` | Permitted | Permitted only with explicit exact-operation approval | The source adapter applies redaction and field-presence rules on every response/request. |
| `GET`/`POST /screensaver` | Required | Not permitted | A session is rejected locally; there is no fallback. |
| `GET`/`POST /calendar` | Required | Not permitted | A session is rejected locally; there is no fallback. |
| `POST /action` for `identify`/`reaffirm` | Required | Not permitted | Only the two named actions are representable. |
| Any other direct Fleet or `/remote/*` operation | Not available | Not available | Return a local scope error and emit no request. |

A remote-session plan carries an explicit approval record rather than a Boolean `authenticated` flag:

```swift
struct RemoteOperationApproval: Sendable, Equatable {
    var portalID: PortalID
    var route: FleetRoute
    var method: HTTPMethod
    var operationID: String
    var approvedAt: Date
}

struct RouteCredentialPlan: Sendable, Equatable {
    var method: HTTPMethod
    var route: FleetRoute
    var credential: CredentialKind?
    var remoteApproval: RemoteOperationApproval?
}
```

The planner rejects a remote session for `/info`, `/screensaver`, `/calendar`, `/action`, and excluded routes before Keychain access or transport. It also rejects a session for settings/sources when the exact route/method/operation approval is absent or belongs to another Portal.

### 6.3 Request construction and response classification

Bearer and session credentials are sent only as `Authorization: Bearer <value>`. A PIN is sent only in the typed JSON body of `/remote/pair`. No secret enters URL queries, service names, discovery records, redirect targets, request descriptions, analytics, logs, exports, or error strings.

`FleetHTTPClient` distinguishes DNS/resolution, LAN policy, timeout, transport, redirect, `401`, `403`, `404`, `405`, `409`, `5xx`, malformed schema, and partial-apply responses. A `401` marks only the affected credential kind for reauthentication and suppresses operations requiring it. The client never changes a server error into an optimistic success.

A selected Portal status refresh has a ten-second deadline. An identity/health refresh uses a fresh bearer `/info` request and reports latency or a categorized connection error.

### 6.4 Pairing and reauthentication

The pairing flow is:

1. validate and resolve the discovered/manual endpoint through LAN policy;
2. show the local-network warning if the exact scope has not been acknowledged;
3. accept a masked six-digit PIN in an operation-local secure buffer;
4. submit exactly one `POST /remote/pair` request with the PIN body;
5. clear the PIN on success, failure, cancellation, or timeout;
6. write the returned session to the intended Portal's Keychain reference;
7. enter `remoteSessionPaired`, never `bearerAuthenticated`.

Wrong, blank, expired, or already-redeemed PINs produce a pairing error and leave existing credential references unchanged. A bearer entered by the operator or recovered from provisioning is verified with `/info` before the Portal becomes bearer-authenticated or direct operations are enabled. A remote settings/source read can confirm session usability but cannot upgrade assurance.

Removing a Portal deletes its bearer/session references and all per-Portal source Keychain items, removes it from managed-operation selections, and leaves unrelated Portal/service credentials intact.

## 7. USB/ADB provisioning

Provisioning is the only subsystem allowed to use USB/ADB. It is not a fallback Fleet HTTP transport and cannot silently change a network operation into a USB operation.

### 7.1 Two separate plans

```swift
enum ProvisioningMode: Codable, Sendable {
    case fleetAgentEnablementRecovery
    case fullUSBProvisioning
}

struct EnablementRecoveryPlan: Sendable {
    var deviceSerial: String
    var adbExecutable: LocalExecutableReference
    var friendlyName: String?
}

struct FullUSBProvisioningPlan: Sendable {
    var deviceSerial: String
    var adbExecutable: LocalExecutableReference
    var localArtifact: LocalArtifact
    var friendlyName: String?
}
```

**Fleet Agent Enablement/Recovery** is valid only when inspection proves an already installed compatible Immortal application. It uses the local ADB handoff to write `provision.json`, relaunch Immortal, wait for/read `agent.json`, recover the manifest, and verify the recovered bearer. It never installs an APK, runs Full USB Provisioning setup, downloads anything, or executes an unallowlisted command.

**Full USB Provisioning** requires a selected local artifact and operator/local ADB executable. It runs the established setup and Immortal installation steps as a separate operation, including generation-specific behavior required by the verified local profile, and then reports Fleet Agent Enablement/Recovery as a distinct final phase. It does not run the repository's broad `provision.sh` entry point because that entry point can download platform-tools/APKs and perform unrelated optional work.

Before either flow, the app inspects ADB authorization, serial, model, API level, installed Immortal state, and current Fleet Agent state. Unauthorized, disconnected, unavailable, or unsupported devices retain the existing Portal Registry and expose a retry path without a success claim.

### 7.2 Typed ADB boundary

```swift
enum ADBCommand: Sendable {
    case enumerateDevices
    case inspect(device: String, field: DeviceField)
    case pushProvisionFile(device: String, localURL: URL)
    case relaunchImmortal(device: String)
    case readAgentManifest(device: String)
    case installVerifiedArtifact(device: String, localURL: URL)
    case applyEstablishedSetup(device: String, step: SetupStep)
}
```

`ADBRunner` accepts only this finite command set. It never accepts a shell string, invokes `sh -c`, runs a user-provided remote shell, changes ADB transport to Wi-Fi, downloads a tool/package, invokes a package manager, or exposes arbitrary paths. It uses a fixed allowlisted environment and only the selected executable, selected artifact, generated `provision.json`, per-operation temporary workspace, and expected `agent.json` handoff.

The macOS app performs the equivalent of the required local handoff explicitly:

1. detect and inspect the USB device;
2. for Full USB Provisioning only, verify the local artifact;
3. for Full USB Provisioning only, install and apply the established local setup;
4. write `provision.json` before relaunching Immortal;
5. relaunch Immortal and poll/read `agent.json` after relaunch;
6. parse the manifest and immediately write the token to the Portal Keychain;
7. retain only non-secret name/port from the manifest plus serial/model from the inspected device; retain the endpoint address only after LAN admission;
8. resolve and revalidate the address through LAN policy;
9. verify the recovered bearer with `/info`;
10. persist registry metadata and mark the Portal online only after verification.

Every step is emitted as a typed progress event. Omitted or reordered handoff steps fail the operation.

### 7.3 Artifact verification

```swift
struct LocalArtifact: Sendable {
    var securityScopedURL: URL
    var displayName: String
    var expectedPackageIdentity: String
    var expectedSignaturePolicy: SignaturePolicy
}

struct ArtifactVerificationResult: Sendable, Equatable {
    var readableRegularFile: Bool
    var packageIdentity: ArtifactCheck
    var signature: ArtifactCheck
    var sha256Digest: String?
    var apiCompatibility: ArtifactCheck
    var abiCompatibility: ArtifactCheck
    var targetModelCompatibility: ArtifactCheck
    var passed: Bool
}
```

Verification happens before any install command and checks readable regular-file access, expected package identity (`com.immortal.launcher` for the manager artifact), accepted signature policy, SHA-256 digest, target API compatibility including API 28/API 29, supported ABI including arm64, and applicable model-family compatibility. A failed check blocks installation. The UI shows artifact name, digest, and each check result before installation begins.

The selected ADB/platform-tools executable and artifact are local security-scoped selections. No download interface, URL resolver, release API client, or automatic platform-tools bootstrap exists in the app. Provisioning tests must prove that no network artifact/package/tool request can be emitted.

### 7.4 Manifest recovery and failure handling

The raw manifest is treated as untrusted sensitive input. The adapter projects only the manifest's non-secret name and port plus serial/model from the preflight ADB snapshot and the address from the admitted endpoint for registry state, and writes the bearer token directly to the per-Portal Keychain item. Raw manifest text and token-bearing process output are cleared with the temporary workspace on completion, failure, or cancellation where the OS permits.

An enablement, recovery, artifact, installation, setup, or verification failure/timeout identifies the failed step, records sanitized diagnostics, preserves the last confirmed Portal state, and requires a successful bearer `/info` before LAN management is enabled. Cancellation is cooperative; a non-cancellable current ADB step is allowed to finish safely, after which no later step is started.

## 8. Settings Registry and source-secret handling

### 8.1 Forward-compatible schema model

The wire decoder is additive and untrusted:

```swift
enum DecodedControlType: Sendable, Equatable {
    case bool
    case int
    case enumValue
    case string
    case info
    case unknown(rawValue: String?)
}

struct SettingsControlSchema: Sendable, Equatable {
    var domainID: String
    var key: String
    var rawType: String?
    var type: DecodedControlType
    var title: String?
    var section: String?
    var help: String?
    var value: JSONValue?
    var defaultValue: JSONValue?
    var options: [EnumOption]?
    var min: Int?
    var max: Int?
    var step: Int?
    var wrap: Bool?
    var asText: Bool?
    var readOnly: Bool
    var secret: Bool
    var hasValue: Bool?
    var visible: Bool
    var additiveMetadata: [String: JSONValue]
}
```

Every returned Settings Domain and every returned visible control is represented using its returned identifier and metadata. Unknown/future domains, controls, and type strings remain safe read-only rows when decodable. Unknown types never receive an invented editor or a submit path. The current identifiers `screensaver`, `calendar`, `immortal`, `mqtt`, `quickbar`, `fleet`, `chime`, `digitalclock`, `welcome`, and `sunrise` are recognized for display, but recognition never grants edit permission.

The renderer honors title, section, help, current value, default, enum options, integer bounds/step/wrap, `readOnly`, and visibility. The server's `secret`/`hasValue` metadata is preserved for configured-state presentation, but is not the sole policy decision.

### 8.2 Explicit default-deny policy

```swift
enum SettingsPolicyClassification: Sendable, Equatable {
    case approvedEditable(route: FleetRoute, bulk: BulkPolicy, evidence: String)
    case approvedReadOnly(reason: String)
    case endpointBearingPendingApproval(reason: String)
    case credentialBearingPendingApproval(reason: String)
    case excluded(reason: String)
    case unknown
}

struct SettingsPolicyEntry: Sendable, Equatable {
    var domainID: String
    var controlKey: String?
    var classification: SettingsPolicyClassification
    var sensitive: Bool
    var fieldPresence: FieldPresencePolicy
}
```

Every editable current domain/control has an explicit policy entry. Unknown, endpoint-bearing, credential-bearing, secret, and future controls remain read-only until the policy explicitly approves value handling, route, redaction, and bulk behavior. The returned schema still controls visibility, `readOnly`, type, constraints, and configured-state behavior after policy approval.

`maUsername` is always classified as sensitive and credential-bearing, even if `secret` is false. Its value is represented only by a Keychain reference/configured-state flag, never by registry JSON, UserDefaults, persistent view state, or a published cleartext draft.

If an on-device navigation editor has no generic control in the schema, the manager shows the approved available controls only. It never invents a desktop editor or writes a raw preference key.

### 8.3 Apply and authoritative read-back

`SettingsCoordinator` validates type, visibility, read-only state, policy classification, integer range/step/wrap, and enum membership before constructing a domain batch. It submits only explicitly approved controls through `/remote/settings`, using the exact route credential plan and remote-session approval if applicable.

The response's applied-key set is shown. Applied, omitted, rejected, unsupported, and conflict fields are reported separately, and the returned domain schema replaces the local confirmed snapshot. The manager never optimistically marks an unconfirmed value as applied and never retries an invalid value.

### 8.4 Sanitized source model and field presence

`GET /remote/sources` may contain legacy cleartext `immichKey`, `smbUser`, `smbPass`, `davUser`, and `davPass` values. The wire DTO is private to the active adapter:

```swift
enum SourceSecretField: String, Codable, Sendable {
    case immichKey, smbUser, smbPass, davUser, davPass
}

struct SourceSecretKey: Hashable, Codable, Sendable {
    var portalID: PortalID
    var sourceID: String
    var field: SourceSecretField
}

enum SourceSecretStatus: Sendable, Equatable {
    case notConfigured
    case configuredInKeychain
    case legacyConfiguredMigrationRequired
    case configuredButReentryRequired
}

struct SanitizedSourceSnapshot: Sendable, Equatable {
    var sourceID: String
    var nonSecretFields: [String: JSONValue]
    var secretStatus: [SourceSecretField: SourceSecretStatus]
}
```

Before any result reaches the store, view model, registry, logger, diagnostics, export, analytics, or release evidence, every cleartext source value is removed. Only non-secret fields and configured-state/migration status remain. If an explicit migration to the exact `PortalID + sourceID + field` Keychain item fails, the UI shows configured-but-reentry-required and the legacy value cannot be reused.

Source replacement is masked and operation-local:

```swift
enum SourceSecretEdit: Sendable {
    case preserve                 // blank or omitted; omit from request
    case replace(SecureInputRef)  // deliberate nonblank replacement
}
```

Blank or omitted source credential fields are omitted from the request and preserve both the Portal value and Keychain item. No blank value clears or overwrites a nonblank credential. If a source edit cannot be represented by the documented partial-update/field-presence semantics, the coordinator returns `unsupportedPartialEdit`, sends no request, and preserves the last confirmed snapshot. Approved `/screensaver` and `/calendar` edits use the same field-presence discipline and remain bearer-only.

## 9. Individual and bulk operations

An individual operation uses the selected Portal's fresh schema, capability assessment, policy classification, connection state, and exact route credential plan. Missing or stale prerequisites block the operation rather than guessing a credential, policy, field, or target.

`BulkOperationEngine` performs target-specific preflight before confirmation. The summary includes target count, selected operation, fields, credential scope, connection state, capability state, policy classification, incompatible fields, reduced per-target plans, and whether a Sensitive Value is affected. A divergent-schema reduced operation requires explicit confirmation. A sensitive operation displays target count and affected domain in this normal bulk confirmation; after that confirmation it may dispatch without an additional explicit sensitive-value confirmation.

After confirmation, eligible targets dispatch independently with bounded concurrency and per-target Keychain reads. Offline, unauthenticated, incompatible, unclassified, timed-out, and rejected targets do not prevent other eligible targets from running. Each target receives progress, a terminal result, and authoritative read-back. The aggregate reports success, partial failure, failure, skipped, and cancelled counts. Full success is claimed only when every eligible target has confirmed success; one response is never copied to another target.

Cancellation prevents not-yet-started targets from dispatching. A currently executing cancellable operation is cancelled; a non-cancellable protocol step is allowed to finish safely and its final result remains visible.

## 10. Music Assistant and Snapcast

Music Assistant and Snapcast are independent local services. Both use DNS/LAN/reconnect/trust-warning policy and separate Keychain references. Music Assistant defaults to `8095` and `/ws`; Snapcast control defaults to `1705` and newline-delimited JSON-RPC.

### 10.1 Read-only topology and authentication

The Music Assistant adapter supports the existing hello/auth flow, optional `auth/login` and `auth`, `players/all`, and typed transport commands already represented by the repository. A `MusicTopologySnapshot` enumerates players, providers, Music Groups, membership, online state, current media, and stable identifiers; it preserves player IDs, provider IDs, display names, and group references. A blank optional credential can produce connected-unauthenticated read state. A supplied credential rejection is an authentication failure, not a network failure, and suppresses mutation.

The Snapcast adapter supports typed `Server.GetStatus`, stream/group/client/host topology, notifications, and re-read after updates. It preserves server/client/group/stream identifiers. Equal display names do not identify a member. Ambiguous Portal-to-player/client mapping remains explicitly ambiguous, and offline or partially accepted results show confirmed service topology rather than requested topology.

### 10.2 Mutation default-deny and release gate

The v1 surface disables Music Assistant and Snapcast group mutations by default, including create, rename, add-member, remove-member, and dissolve. The application has no generic `call(method: String, params: JSONValue)` mutation interface.

A named mutation becomes available only when all of the following are present for the named service/version/operation:

1. a typed adapter implementing an explicit Versioned Service Contract;
2. sanitized request/response fixtures for the deployed version;
3. Mutation Evidence from a capability response or controlled service result;
4. service-specific read-back verification;
5. a passed operation-specific Music Mutation Release Gate.

Unknown versions, incomplete evidence, ambiguous members, missing gates, and failed read-back remain read-only and emit no speculative request. A mutation success claim requires the requested IDs, names, and membership to be confirmed by service read-back.

Music Assistant and Snapcast are never one atomic cross-service transaction. A workflow that names both creates separate service-specific plans, confirmations/results, and read-backs with no rollback or atomicity claim.

Portal multi-room fields `multiRoomEnabled`, `snapcastHost`, `maPort`, `maUsername`, and masked `maPassword` are subject to explicit Settings Policy Classification. Music Assistant/Snapcast credentials always use Keychain. Service-resynchronization status is shown only after the relevant settings/service operation is acknowledged and the current service/status state is read back.

## 11. Sensitive values, Keychain, and redaction

`CredentialStore` is the only persistence interface for sensitive values:

```swift
protocol CredentialStore: Sendable {
    func read(_ reference: CredentialReference) throws -> Data?
    func write(_ value: Data, for reference: CredentialReference) throws
    func delete(_ reference: CredentialReference) throws
}
```

The production implementation uses bundle-scoped macOS Keychain generic-password items. Account identity includes owning Portal/service, source ID where applicable, and credential kind, but not display name, IP address, or secret. It supports Portal bearer/session values, source fields, Music Assistant credentials, and supported Snapcast credentials.

The following are prohibited cleartext sinks: registry JSON, UserDefaults, SceneStorage, Core Data, plist/cache, clipboard previews, URLs, mDNS metadata, crash payloads, analytics, exports, published `@Published` snapshots, navigation state, release evidence, logs, signposts, error descriptions, `URLRequest.debugDescription`, and ADB transcripts.

A `SecureField` may hold a transient masked input while an operator enters a replacement. The buffer is not bound to published state, is handed directly to the active coordinator, and is cleared after save, cancellation, or failure. Legacy source values never reach the view. Redaction removes known values, authorization headers, PIN patterns, passwords, source fields, usernames, access tokens, and nested sensitive JSON from structured/text diagnostics.

A Keychain read failure enters reauthentication/keychain-error state. There is no fallback to registry JSON, UserDefaults, URL parameters, clipboard, shared files, a prior log, or a stale cleartext value.

## 12. Native UI, command state, exclusions, and cancellation

`PortalManagerApp` presents a native `NavigationSplitView`:

- **Sidebar:** Portal Registry entries, discovery candidates, assurance/connection badges, credential scope, compatibility status, service hosts, and selection state.
- **Detail:** Overview/status, schema-driven settings, sources/calendar/screensaver, approved actions, provisioning, Music topology, bulk results, and sanitized errors.
- **Progress/inspector:** Per-target progress/read-back, artifact verification, provisioning steps, cancellation state, and release evidence.

When a Portal is selected, the detail view shows connection state, authenticated `/info` identity, model/raw model, Android API level, Immortal version, agent address/port, reachability, last refresh/response time, supplied presence/screen state, capability summary, credential assurance/scope, and available operations before mutation controls. A remote-session entry visibly distinguishes limited settings/source access from verified identity/health.

AppKit commands and keyboard equivalents cover discovery refresh, manual-IP onboarding, sidebar navigation, Portal selection, status refresh, identify, reaffirm, apply/retry, provisioning start/cancel, Music refresh, and Bulk Operation confirmation/cancellation. Commands remain discoverable but are disabled or explain prerequisites when no Portal is selected, a capability/credential/policy is missing, or ADB/artifact verification is incomplete. No command silently targets another Portal.

Excluded operations are absent from the normal UI, command menu, bulk model, and internal route enum. If an excluded intent arrives through a stale workflow, imported state, or future UI path, `OperationExclusionGate` shows the local explanation, records no secret or raw request, emits no excluded request, leaves the device unchanged, and returns a continuation outcome so the surrounding workflow can proceed without that request. This satisfies the required local explanation without making excluded controls part of the product surface.

While a Bulk Operation or USB flow is active, the UI shows progress and safe cancellation state and preserves the final per-target/provisioning result. Connection, authentication, compatibility, validation, capability, and server errors map to sanitized detail plus a recovery action appropriate to the category.

Compatibility warnings are not used as a generic unknown-model warning. The UI warns only for actual unsupported API or missing capability for the selected operation. Unknown model-family recognition alone is shown as raw diagnostic metadata without blocking or warning when no incompatibility is detected.

## 13. Error model and recovery boundaries

```swift
enum ManagerError: Error, Codable, Sendable, Equatable {
    case lanPolicy(LANPolicyReason)
    case resolution(ResolutionReason)
    case discovery(String)
    case transport(TransportReason)
    case redirectRejected
    case http(status: Int, code: String?, detail: String?)
    case authentication(AuthenticationReason)
    case pairing(PairingReason)
    case capabilityUnavailable(operation: String, reason: String)
    case settingsPolicy(field: String, reason: String)
    case validation(field: String, reason: String)
    case unsupportedPartialEdit(sourceID: String, reason: String)
    case keychain(KeychainReason)
    case artifactVerification(ArtifactVerificationFailure)
    case provisioning(step: ProvisioningStepID, reason: String)
    case protocol(service: String, reason: String)
    case excludedOperation(String)
    case releaseGate(GateID, reason: String)
    case cancelled
}
```

Errors expose a sanitized message, category, recovery action, and retry policy. Raw response bodies, headers, PINs, source values, credential bytes, untrusted ADB output, and process paths are never passed directly to the UI or logger. Failed/partial operations retain last confirmed state. Retry starts with fresh DNS/LAN/trust/credential checks; it never reuses an unsafe prior connection assumption.

## 14. Release evidence and mandatory gates

Release validation is represented by typed sanitized evidence rather than an informal checklist:

```swift
enum GateID: Codable, Hashable, Sendable {
    case security
    case lan
    case provisioning
    case modelMatrix
    case portalTV
    case musicMutation(service: String, operation: String, contract: String)
}

enum GateStatus: String, Codable, Sendable {
    case missing
    case pending
    case passed
    case failed
    case withheld
}

struct ReleaseEvidenceRecord: Codable, Sendable {
    var gateID: GateID
    var candidateVersion: String
    var evidenceIDs: [String]
    var testResults: [String]
    var supportedClaims: [String]
    var unresolvedDeviations: [String]
    var status: GateStatus
}

struct ReleaseGateReport: Codable, Sendable {
    var candidateVersion: String
    var records: [ReleaseEvidenceRecord]
    var publishableClaims: [String]
    var withheldClaims: [String]
}
```

The mandatory v1 gates are:

- **Security Release Validation:** exact route/credential matrix, explicit remote-session approval, Keychain-only storage, `maUsername` handling, source redaction/migration, closed route/action set, unknown settings safety, and redirect rejection;
- **LAN Release Validation:** IPv4/IPv6/zone parsing, DNS-before-credential/socket ordering, public-address rejection, reconnect revalidation, redirect refusal, and trust-warning scope for Portal/MA/Snapcast;
- **Provisioning Release Validation:** both Enablement/Recovery and Full USB Provisioning, local ADB/artifact only, all artifact checks, manifest recovery, bearer `/info`, retries/cancellation, sanitized output, and no-download proof;
- **Model Matrix Validation:** every claimed supported model family, API level, ABI, DHCP/mDNS-loss behavior, and recorded claim status;
- **Portal TV Validation:** discovery, bearer verification, approved configuration/actions, both USB modes, and native UI with no touchscreen/D-pad input on the Portal.

A future Music mutation additionally requires its named Versioned Service Contract, sanitized fixtures, Mutation Evidence, service-specific read-back, and passed operation-specific gate. A missing or failed gate withholds only the affected model, provisioning, Portal TV, or mutation claim; it never enables a fallback or broadens the runtime allowlist.

## 15. Persistence and lifecycle

At launch/resume, the app loads the non-secret registry, restores only opaque credential references, refreshes local discovery, and revalidates a persisted endpoint only when connecting. It does not register a cloud account or contact a relay. A persisted endpoint never authorizes a connection without fresh resolution and LAN policy.

Persistence is split into:

- `JSONRegistryStore`: identity, endpoint metadata/history, last confirmed status, capability/policy metadata, and Keychain references;
- `KeychainCredentialStore`: all sensitive values;
- transient operation memory: PINs, decrypted credential bytes, source migration buffers, artifact handles, process handles, and secure input references;
- `ReleaseEvidenceStore`: sanitized evidence IDs, results, claims, and deviations only.

Registry writes occur after authenticated identity updates, verified provisioning, or authoritative read-back. Failed/partial operations preserve last confirmed state. DHCP changes are staged as candidate endpoints until the new address completes the applicable route/credential checks.

## 16. Testing and validation strategy

### 16.1 Injectable seams

The following are protocols/actors with deterministic fakes:

- DNS resolver and address selector, including IPv6 zones and ordering;
- Bonjour browser and discovery events;
- HTTP transport capturing method/path/headers/body, deadline, and redirect callbacks;
- Credential, trust-warning, registry, clock, logger, and redactor stores;
- ADB runner, artifact verifier, provisioning backend, temporary workspace, process factory, and security-scoped file access;
- Music Assistant WebSocket and Snapcast transports;
- typed Music mutation adapters and release evidence/gate store.

Fakes record sanitized request metadata only. An access-order fake asserts DNS/LAN policy occurs before Keychain/socket. A redirect fake asserts there is no second request. An ADB fake rejects commands outside `ADBCommand` and verifies no downloader/arbitrary shell is invoked.

### 16.2 Sanitized fixtures

Fixtures contain sentinel values that must not appear in state, logs, exports, or snapshots:

```text
PortalManagerTests/Fixtures/
  Fleet/{info-portal-go.json, info-portal-tv.json, info-unknown-model.json,
        settings-current-domains.json, settings-unknown-domain.json,
        settings-unknown-type.json, settings-secret-has-value.json,
        action-identify-error.json}
  Pairing/{pair-success.json, pair-bad-pin.json, pair-expired.json}
  Sources/{sources-legacy-cleartext.json, sources-mixed-blank-fields.json,
           sources-partial-unsupported.json, source-migration-failure.json}
  Provisioning/{adb-authorized.txt, adb-unauthorized.txt, enablement-only.txt,
                full-provisioning.txt, artifact-identity-failure.json,
                artifact-signature-failure.json, artifact-digest-failure.json,
                artifact-api-abi-failure.json, agent-manifest.json}
  MusicAssistant/{hello.json, auth-success.json, auth-failure.json,
                  players-all-grouped.json, mutation-contract-unknown.json,
                  mutation-contract-supported.json}
  Snapcast/{server-status-grouped.json, server-status-ambiguous.json,
            topology-update.lines}
  ReleaseEvidence/{security-pass.json, lan-pass.json,
                   provisioning-partial.json, model-matrix-partial.json,
                   portal-tv-pass.json, music-mutation-missing-evidence.json}
```

### 16.3 Test layers

- **Pure unit tests:** endpoint parsing, LAN ranges/zones, DNS selection, trust scope, route planning, remote approval, allowlists, state transitions, model/capability/warning decisions, settings decoding/policy, source field presence, artifact verification, bulk planning, Music evidence decisions, redaction, and release-gate aggregation.
- **Property tests:** every property in §17 runs at least 100 generated cases with deterministic seeds and fake transports. Tests are tagged `Feature: macos-portal-manager, Property N: <property text>`. They do not make repeated live LAN, USB, or service calls.
- **Protocol fixture tests:** exact Fleet paths/methods/headers/bodies, `/remote/pair`, source/settings read/apply, bearer/session restrictions, 3xx refusal, MA framing/auth/player reads, Snapcast JSON-RPC framing/topology, redaction, and absence of unsupported mutation requests.
- **UI tests:** split view/navigation, selected assurance state, actual-compatibility warnings, unknown read-only controls, secure source editors, disabled prerequisites, excluded-operation explanation, bulk confirmation/progress/cancellation, artifact preview, evidence status, menus/key equivalents, accessibility, and Portal TV fixture flows.
- **Integration tests:** controlled local Fleet/MA/Snapcast/Bonjour/Keychain boundaries, representative timing/deadline behavior, ADB authorization, artifact picker/security scope, and actual service protocol behavior.
- **Hardware/release validation:** both provisioning modes, generation-specific setup, agent recovery, API 28/API 29, arm64, DHCP changes, mDNS loss, all claimed model families, and Portal TV no-touch validation. These produce release evidence.
- **Smoke checks:** separate Xcode build/test, startup/resume without cloud registration, Keychain-only restoration, public endpoint rejection, no redirect follow, closed UI/route exclusions, and no-download provisioning behavior.

Build/test commands remain separate from Gradle:

```text
xcodebuild -project macos/PortalManager.xcodeproj \
  -scheme PortalManager \
  -destination 'platform=macOS' build

xcodebuild test -project macos/PortalManager.xcodeproj \
  -scheme PortalManager \
  -destination 'platform=macOS'
```

## 17. Correctness Properties

*A property is a behavior that must hold for all valid executions of the manager. Properties target pure domain/application logic and injected boundaries; live Bonjour, hardware, UI layout, and release evidence collection are validated by the example, integration, smoke, and hardware tests above. The properties were reflected for redundancy before being written: source-redaction and general secret handling are separated by boundary, endpoint concerns are consolidated, and bulk planning is kept distinct from execution.*

### Property 1: LAN endpoint admission and connection ordering

For any Portal, Music Assistant, Snapcast, discovery, manual, provisioning, or reconnect endpoint input, the manager SHALL parse the permitted syntax, default an omitted Portal port to `8723`, preserve valid IPv4/IPv6/interface-zone information, select only a resolved loopback/private/link-local address, reject malformed/unresolved/public results, and perform no Keychain read or socket attempt before LAN admission.

**Validates: Requirements 1.3, 1.6, 9.8, 11.2, 11.3, 11.4, 11.5**

### Property 2: Trust scope and redirect credential safety

For any hostname resolution sequence, reconnect, trust acknowledgement, service kind, endpoint, or HTTP response, the manager SHALL reapply LAN policy before every credential read/socket attempt, derive trust scope from service kind plus normalized protocol/endpoint/port/zone, require a new acknowledgement when that scope changes, and reject every redirect without a follow-up credentialed request.

**Validates: Requirements 9.7, 9.8, 11.2, 11.6, 11.7**

### Property 3: Discovery promotes only bearer-authenticated identity

For any mDNS or manual candidate, credential availability, and `/info` outcome, the manager SHALL send `/info` only with a supplied Verified Bearer Credential, send no `/info` request when that credential is absent, and create verified identity/health only after the bearer-authenticated response succeeds; discovery metadata and a remote session alone SHALL never promote assurance.

**Validates: Requirements 1.1, 1.2, 1.5, 4.5**

### Property 4: Manual PIN pairing is mDNS-independent and scope-limited

For any LAN-validated manual endpoint and any PIN outcome, the manager SHALL permit `/remote/pair` without an mDNS record, submit an accepted PIN exactly once, associate the returned session only with the intended Portal, and expose that session only for explicitly approved `/remote/settings` and `/remote/sources` operations without promoting identity, health, or bearer authentication.

**Validates: Requirements 1.4, 4.3, 4.4**

### Property 5: Registry reconciliation and offline retention

For any sequence of authenticated discovery, manual, provisioning, duplicate, and reachability records, the manager SHALL merge duplicate identity or authenticated endpoint records into one entry, preserve valid credential references and last confirmed state, prefer the newest authenticated endpoint, and retain retry/editable offline state after an outage.

**Validates: Requirements 1.7, 1.8**

### Property 6: Model, API, capability, and warning decisions are independent

For any authenticated `/info` payload and selected operation, the manager SHALL preserve raw identity fields, recognize every supported model family, evaluate API level and endpoint capability independently of the model label, warn only for detected unsupported API or missing required capability, and leave an unknown model unwarned when no actual incompatibility is detected.

**Validates: Requirements 2.1, 2.2, 2.3, 2.5, 2.6**

### Property 7: The route credential matrix and closed allowlist are exact

For any Fleet operation intent, route, method, action, credential kind, and remote-session approval, the planner SHALL accept a Verified Bearer Credential exactly where the matrix permits it, accept a Remote Session Credential only for an explicitly approved exact `/remote/settings` or `/remote/sources` operation, accept no existing credential for `/remote/pair` with a PIN body, reject sessions for `/info`, `/screensaver`, `/calendar`, and `/action`, and emit no request for every excluded or unknown route/action.

**Validates: Requirements 4.1, 4.2, 4.4, 5.4, 11.9, 11.10**

### Property 8: Credential lifecycle fails closed

For any blank, wrong, expired, redeemed, revoked, `401`, or unavailable Keychain credential outcome, the manager SHALL discard transient PIN/input data or enter credential-specific reauthentication, preserve non-secret identity and still-valid unrelated references, suppress mutations requiring the affected credential, and never substitute a different credential kind or cleartext fallback.

**Validates: Requirements 4.6, 4.7, 9.9**

### Property 9: Provisioning modes and artifact gates remain separate

For any provisioning request, Fleet Agent Enablement/Recovery SHALL contain only inspection plus the allowlisted `provision.json` write/relaunch/`agent.json` handoff for an already installed compatible Immortal application, while Full USB Provisioning SHALL require a local artifact and contain setup/install before a separately reported enablement phase; installation SHALL be permitted only when file, package identity, signature, SHA-256, API, ABI, and applicable model checks all pass.

**Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.10**

### Property 10: Provisioning cannot create online state before verification

For any ADB, artifact, setup, installation, enablement, manifest, LAN, and `/info` event sequence, an unauthorized, disconnected, unsupported, failed, or timed-out step SHALL leave the Portal non-manageable with a named sanitized failure and retry path, and the Portal SHALL become online only after a recovered bearer, LAN-valid endpoint, and successful bearer `/info` verification.

**Validates: Requirements 3.6, 3.7, 3.8, 3.9**

### Property 11: Settings decoding is forward-compatible and default-deny

For any returned Settings Domain, visible control, type string, schema metadata, and policy entry, the manager SHALL preserve returned identifiers/metadata, render unknown domains/controls/types as safe read-only values, honor known metadata, recognize the current domain identifiers, and require explicit classification before editing endpoint-bearing, credential-bearing, unknown, future, or otherwise unapproved controls.

**Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 6.14, 11.10**

### Property 12: Settings apply uses policy and authoritative read-back

For any settings draft and schema, the manager SHALL reject wrong types, invalid ranges, invalid enum values, read-only controls, unknown types, invisible controls, and unclassified controls locally, submit only approved valid fields, report the server's applied-key set, and replace confirmed local state with the returned domain schema without invalid retries or optimistic values.

**Validates: Requirements 6.7, 6.8**

### Property 13: Legacy source values never enter unsafe state or output

For any `/remote/sources` response containing any legacy source credential or future credential-bearing field, the manager SHALL strip cleartext values before published state, UI, logs, diagnostics, exports, analytics, registry, or release evidence, retain only non-secret fields/configured-state metadata, and use configured-but-reentry-required when explicit Keychain migration fails.

**Validates: Requirements 6.9, 6.16, 9.1, 9.3, 9.5**

### Property 14: Source migration and blank edits preserve per-Portal state

For any Portal, source, credential field, migration/replacement outcome, and source draft, an explicit replacement SHALL write only the corresponding per-Portal/per-source Keychain item, a blank or omitted credential SHALL be omitted from the request and preserve device and Keychain values, and an unsupported partial edit SHALL send no request and preserve confirmed state.

**Validates: Requirements 6.10, 6.11, 6.12, 9.1**

### Property 15: Confirmed state and approved-action errors are never optimistic

For any authenticated status, settings, source, screensaver, calendar, or approved identify/reaffirm request and any success, partial, unsupported, conflict, or server-error response, the manager SHALL retain the last confirmed value/result for rejected fields/actions, expose a categorized outcome, show returned applied fields, and never report unconfirmed success.

**Validates: Requirements 5.1, 5.5, 5.7, 6.7, 6.13**

### Property 16: Bulk preflight is explicit and target-specific

For any selected Portal set and approved operation, preflight SHALL report exact target count, operation, fields, credential scope, capability, policy classification, sensitivity, and per-target eligibility; divergent schemas SHALL produce target-specific valid/reduced plans and require the normal explicit bulk confirmation before dispatch, without adding a second sensitive-value confirmation.

**Validates: Requirements 7.2, 7.3, 7.6, 7.8**

### Property 17: Bulk fan-out is independent and truthfully aggregated

For any target set containing successful, offline, unauthenticated, incompatible, unclassified, rejected, timed-out, or cancelled targets, the engine SHALL dispatch eligible targets independently, continue after individual failures, retain one terminal read-back per target, and report full success only when every eligible target confirms success.

**Validates: Requirements 7.4, 7.5, 7.7**

### Property 18: Music topology preserves identifiers and authentication states

For any Music Assistant or Snapcast topology, configured/default port, mapping, and authentication outcome, the manager SHALL preserve service identifiers and membership despite equal display names, distinguish connected-unauthenticated from authentication failure and transport failure, and mark ambiguous/offline mappings without silently selecting a member or inventing requested topology.

**Validates: Requirements 8.1, 8.2, 8.7, 8.8**

### Property 19: Named Music mutations require complete evidence

For any Music Assistant/Snapcast service, deployed version, named operation, typed adapter, contract, fixture set, Mutation Evidence, read-back result, and gate status, mutation SHALL remain disabled by default and SHALL enable only when all operation-specific evidence and a passed release gate are present; missing evidence SHALL produce read-only topology and no mutation request.

**Validates: Requirements 8.3, 8.4, 8.5, 12.7**

### Property 20: Music workflows never use speculative or cross-service fallback

For any unknown service contract or workflow naming both Music Assistant and Snapcast, the manager SHALL emit no speculative/generic mutation call and no Portal-preference fallback, SHALL plan separate service-specific operations/results/read-backs, and SHALL never claim cross-service atomicity.

**Validates: Requirements 8.6, 8.9**

### Property 21: Portal multi-room settings use policy, Keychain, and read-back ordering

For any Portal multi-room draft and Music Assistant/Snapcast acknowledgement/read-back outcome, the manager SHALL permit only policy-approved `multiRoomEnabled`, `snapcastHost`, `maPort`, `maUsername`, and masked `maPassword` fields, store credentials through Keychain, and show service-resynchronization status only after the relevant operation is acknowledged and current service/status state is read back.

**Validates: Requirements 8.10, 9.2**

### Property 22: Sensitive values have no persistence or diagnostic fallback

For any Portal, source, Music Assistant, Snapcast, PIN, or other Sensitive Value and any save/cancel/failure path, the manager SHALL persist the value only in the appropriate Keychain item or active operation memory, mask transient entry, redact structured/text diagnostics and clipboard/URL previews, clear temporary state after the operation, and perform no cleartext fallback on Keychain failure.

**Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.6, 9.9**

### Property 23: Commands and data-plane plans remain explicitly targeted and local

For any command, selection, prerequisite state, credential/capability state, and operation intent, the manager SHALL target only the explicitly selected eligible Portal or remain disabled with an explanation, and every emitted plan SHALL use an admitted LAN endpoint or restricted USB/ADB backend rather than cloud, relay, public, shell, package-manager, arbitrary server, or downloaded-tool access.

**Validates: Requirements 10.4, 11.1, 11.8**

### Property 24: Missing release evidence withholds affected claims

For any release candidate and combination of Security, LAN, Provisioning, Model Matrix, Portal TV, and conditional Music Mutation gate statuses, the evaluator SHALL retain named evidence/test/deviation records, publish only claims whose required gates have passing evidence, and withhold the affected operation/model/provisioning claim when a required gate is missing, pending, failed, or withheld.

**Validates: Requirements 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8**

## 18. Requirements traceability

| Requirement | Design coverage | Primary validation |
|---|---|---|
| 1. Discovery and registration | §§3.1, 3.3, 4, 5; Properties 1–5 | mDNS/manual fixtures, bearer-only identity probes, direct manual PIN pairing, LAN parser, merge/offline tests |
| 2. Model and capability classification | §3.2; Property 6 | `/info` fixtures, actual-compatibility warning tests, model matrix, Portal TV validation |
| 3. Enablement/recovery and Full USB Provisioning | §7; Properties 9–10 | ADB/process fixtures, local artifact checks, manifest/`/info` verification, Provisioning Gate, no-download smoke |
| 4. Credential matrix and pairing | §§3.3, 6; Properties 3–4, 7–8 | exact route fixtures, explicit remote approval tests, PIN cases, bearer verification, Security Gate |
| 5. Status and approved management | §§3.3, 6, 12–13; Property 15 | status/error fixtures, closed planner tests, excluded workflow UI test, native scope checks |
| 6. Settings and source safety | §8; Properties 11–15, 21–22 | schema/source fixtures, default-deny policy, secret migration, blank preservation, partial-update and redaction tests |
| 7. Individual and bulk operations | §9, §12; Properties 16–17, 23 | target-specific preflight, sensitive confirmation, independent fan-out, read-back and cancellation tests |
| 8. Music topology and gated controls | §10; Properties 18–21 | MA WebSocket/Snapcast fixtures, auth-state tests, typed evidence adapters, conditional mutation Gate |
| 9. Secret protection | §4.5, §6.3, §8.4, §11; Properties 2, 8, 13–14, 21–22 | Keychain isolation, redaction/sink audit, redirect, trust-scope, legacy-source fixtures |
| 10. Native desktop workflow | §12; Property 23 | SwiftUI/AppKit UI tests, command registration, prerequisite states, accessibility, cancellation, Portal TV flows |
| 11. LAN-only scope and exclusions | §§1.2, 4, 6, 7.2, 12; Properties 1–2, 7, 20, 23 | address/reconnect/redirect tests, route/UI static review, no-download/no-cloud smoke |
| 12. Release gates | §14; Property 24 | sanitized gate reports, CI/task evidence, model/provisioning/Portal TV evidence, conditional Music Gate |

The following remain intentionally example, integration, edge-case, smoke, or hardware validation rather than universal properties: actual Bonjour enumeration, ten-second live timing, ADB authorization and generation-specific setup, artifact picker sequencing, native layout and VoiceOver presentation, menu registration, Portal TV hardware operation, model-matrix evidence collection, startup/resume behavior, and the final Xcode/Gradle separation check.