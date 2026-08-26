/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Identifies an infrastructure boundary that can be injected into the application shell.
enum DependencyPortKind: String, CaseIterable, Codable, Sendable {
    case dns
    case bonjour
    case fleetHTTP
    case keychain
    case trustWarnings
    case registry
    case adb
    case artifactVerification
    case provisioningWorkspace
    case provisioningDownloads
    case musicAssistant
    case snapcast
    case clock
    case redaction
    case evidence
}

enum DependencyPortError: Error, Equatable, LocalizedError, Sendable {
    case unavailable(DependencyPortKind)

    var errorDescription: String? {
        switch self {
        case .unavailable(let kind):
            return "The \(kind.rawValue) dependency is not configured."
        }
    }
}

/// A service endpoint passed between application coordinators and infrastructure.
/// URL construction and LAN admission remain infrastructure/application concerns.
struct ServiceEndpoint: Codable, Equatable, Hashable, Sendable {
    enum Service: String, Codable, Sendable {
        case portal
        case musicAssistant
        case snapcast
    }

    enum Transport: String, Codable, Sendable {
        case http
        case webSocket
        case tcp
    }

    let host: String
    let port: UInt16
    let service: Service
    let transport: Transport

    init(host: String, port: UInt16, service: Service, transport: Transport) {
        self.host = host
        self.port = port
        self.service = service
        self.transport = transport
    }
}

struct DNSResolutionRequest: Equatable, Sendable {
    let hostname: String
}

struct ResolvedAddress: Codable, Equatable, Hashable, Sendable {
    let address: String
    let interfaceZone: String?

    init(address: String, interfaceZone: String? = nil) {
        self.address = address
        self.interfaceZone = interfaceZone
    }
}

struct DNSResolutionResult: Equatable, Sendable {
    let addresses: [ResolvedAddress]
}

/// A non-secret acknowledgement record keyed only by the normalized local
/// service endpoint and protocol. Credential bytes never cross this boundary.
struct TrustWarningAcknowledgement: Codable, Equatable, Hashable, Sendable {
    let scope: TrustWarningScope
    let acknowledgedAt: Date
}

protocol TrustWarningStore: Sendable {
    func acknowledgement(
        for scope: TrustWarningScope
    ) async throws -> TrustWarningAcknowledgement?

    func acknowledge(
        _ scope: TrustWarningScope,
        at date: Date
    ) async throws
}

extension TrustWarningStore {
    func isAcknowledged(_ scope: TrustWarningScope) async throws -> Bool {
        try await acknowledgement(for: scope.normalized) != nil
    }
}

protocol DNSResolver: Sendable {
    func resolve(_ request: DNSResolutionRequest) async throws -> DNSResolutionResult
}

/// A sanitized resolution failure reported by the Bonjour adapter. Network
/// framework error descriptions are intentionally not carried across the
/// discovery boundary because they may contain endpoint or process details.
enum BonjourResolutionError: String, Codable, Equatable, Hashable, Sendable {
    case endpointUnavailable
    case invalidEndpoint
    case timedOut
    case connectionFailed

    var sanitizedMessage: String {
        switch self {
        case .endpointUnavailable:
            return "The Bonjour service endpoint could not be resolved."
        case .invalidEndpoint:
            return "The Bonjour service returned an unsupported endpoint."
        case .timedOut:
            return "Bonjour service resolution timed out."
        case .connectionFailed:
            return "The Bonjour service address could not be resolved."
        }
    }
}

/// Lifecycle state emitted by a Bonjour browser. State events are separate
/// from service observations so cancellation and refresh never look like an
/// identity or removal decision to a discovery coordinator.
enum BonjourBrowserState: String, Codable, Equatable, Hashable, Sendable {
    case starting
    case waiting
    case browsing
    case refreshing
    case cancelling
    case cancelled
    case failed
}

/// Untrusted mDNS metadata. This value deliberately contains no credential,
/// authorization header, PIN, or Keychain reference. `DiscoveryCandidate` is
/// an alias below so application code can use the domain terminology from the
/// design while existing port users retain the original name.
struct BonjourService: Codable, Equatable, Hashable, Sendable {
    let instanceName: String
    /// Kept for compatibility with the initial port contract. For a resolved
    /// result it is the resolved host/address; unresolved failures use an
    /// empty string and expose the typed error below.
    let hostname: String
    let resolvedHostOrAddress: String?
    let port: UInt16
    let interfaceName: String?
    let source: EndpointSource
    let resolutionError: BonjourResolutionError?

    init(
        instanceName: String,
        hostname: String,
        port: UInt16,
        interfaceName: String? = nil
    ) {
        self.init(
            serviceName: instanceName,
            resolvedHostOrAddress: hostname.isEmpty ? nil : hostname,
            port: port,
            interfaceName: interfaceName,
            source: .mdns(serviceName: instanceName),
            resolutionError: nil
        )
    }

    init(
        serviceName: String,
        resolvedHostOrAddress: String? = nil,
        port: UInt16 = 0,
        interfaceName: String? = nil,
        source: EndpointSource? = nil,
        resolutionError: BonjourResolutionError? = nil
    ) {
        self.instanceName = serviceName
        self.hostname = resolvedHostOrAddress ?? ""
        self.resolvedHostOrAddress = resolvedHostOrAddress
        self.port = port
        self.interfaceName = interfaceName
        self.source = source ?? .mdns(serviceName: serviceName)
        self.resolutionError = resolutionError
    }

    var serviceName: String { instanceName }

    /// The resolved value only. An unresolved candidate must not be mistaken
    /// for a LAN-admitted endpoint by a later coordinator.
    var hostOrAddress: String? { resolvedHostOrAddress }

    var isResolved: Bool {
        resolvedHostOrAddress != nil && resolutionError == nil && port != 0
    }
}

typealias DiscoveryCandidate = BonjourService

enum BonjourEvent: Equatable, Sendable {
    case found(BonjourService)
    case updated(BonjourService)
    case removed(BonjourService)
    /// Legacy source-compatible removal form for deterministic clients that
    /// only retained the mDNS instance name. The factory keeps the historical
    /// `.removed(instanceName:)` construction spelling without overloading
    /// the associated-value pattern used by the resolved-service case.
    case removedInstance(instanceName: String)

    static func removed(instanceName: String) -> BonjourEvent {
        .removedInstance(instanceName: instanceName)
    }
    case resolutionFailed(BonjourService)
    case state(BonjourBrowserState)
    /// A stable, sanitized browser-level failure. Resolution failures carry
    /// their service metadata in `resolutionFailed` instead.
    case failed(message: String)
}

protocol BonjourBrowser: Sendable {
    func events() -> AsyncStream<BonjourEvent>
    func start() async throws
    func refresh() async throws
    func stop() async
}

/// Requests are intentionally marker-typed. Route and payload selection belongs in an
/// application coordinator, never in SwiftUI views or AppKit commands.
protocol FleetHTTPRequest: Sendable {}

struct FleetHTTPResponse: Sendable {
    let statusCode: Int
    let headers: [String: String]
    let body: Data?

    init(statusCode: Int, headers: [String: String] = [:], body: Data? = nil) {
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
    }
}

protocol FleetHTTPTransport: Sendable {
    func send(_ request: any FleetHTTPRequest) async throws -> FleetHTTPResponse
}

/// The only persistence boundary for credentials and other sensitive values.
protocol CredentialStore: Sendable {
    func read(_ reference: CredentialReference) async throws -> Data?
    func write(_ value: Data, for reference: CredentialReference) async throws
    func delete(_ reference: CredentialReference) async throws
}

/// The non-secret snapshot persisted by a registry store.
///
/// Registry entries contain identity, endpoint, status, capability/policy metadata,
/// and opaque Keychain references only. Sensitive values are deliberately not
/// representable in this snapshot type.
struct RegistrySnapshot: Codable, Equatable, Sendable {
    let entries: [PortalRegistryEntry]

    init(entries: [PortalRegistryEntry] = []) {
        self.entries = entries
    }
}

/// Persistence boundary for the non-secret Portal Registry.
protocol RegistryStore: Sendable {
    func load() async throws -> RegistrySnapshot
    func save(_ snapshot: RegistrySnapshot) async throws
}

/// ADB requests and results are typed by the provisioning layer. The shell never accepts a
/// shell command string or invokes a process directly.
protocol ADBRequest: Sendable {}
protocol ADBResult: Sendable {}

protocol ADBRunner: Sendable {
    func execute(_ request: any ADBRequest) async throws -> any ADBResult
}

protocol ArtifactVerificationRequest: Sendable {}
protocol ArtifactVerificationResult: Sendable {}

protocol ArtifactVerifier: Sendable {
    func verify(_ request: any ArtifactVerificationRequest) async throws -> any ArtifactVerificationResult
}

struct MusicAssistantConfiguration: Equatable, Sendable {
    let endpoint: ServiceEndpoint
    let credential: CredentialReference?
}

protocol MusicAssistantTopology: Sendable {}

protocol MusicAssistantClient: Sendable {
    func connect(_ configuration: MusicAssistantConfiguration) async throws
    func topology() async throws -> any MusicAssistantTopology
    func disconnect() async
}

struct SnapcastConfiguration: Equatable, Sendable {
    let endpoint: ServiceEndpoint
    let credential: CredentialReference?
}

protocol SnapcastTopology: Sendable {}

protocol SnapcastClient: Sendable {
    func connect(_ configuration: SnapcastConfiguration) async throws
    func topology() async throws -> any SnapcastTopology
    func disconnect() async

    func setClientVolume(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        clientID: String,
        percent: Int
    ) async throws

    func setGroupClients(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        clientIDs: [String]
    ) async throws

    func setGroupName(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        name: String
    ) async throws

    func setStream(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        streamID: String
    ) async throws
}

/// Application clock boundary used by coordinators and deterministic tests.
///
/// This is intentionally distinct from the standard-library clock protocols: the
/// manager needs a wall-clock `Date` for persisted timestamps and an injectable
/// sleep operation for retry/deadline workflows.
protocol ManagerClock: Sendable {
    var now: Date { get }
    func sleep(for duration: Duration) async throws
}

struct RedactedText: Equatable, Sendable {
    let value: String
}

/// Redaction boundary for operator-facing text and raw process bytes.
/// Implementations must return text that is safe for UI, logs, diagnostics,
/// exports, and evidence. The Data overload keeps byte decoding/redaction at
/// the boundary instead of making every process adapter reimplement it.
protocol Redactor: Sendable {
    func redact(_ input: String) -> RedactedText
    func redact(_ input: Data) -> RedactedText
}

/// A transient, non-persistable reference to an active secure input value.
///
/// Implementations own the input buffer and must clear it after the active
/// operation completes, is cancelled, or fails. The protocol intentionally has
/// no `Codable` conformance or conversion to a registry value.
protocol SecureInput: Sendable {
    var isEmpty: Bool { get }

    func withData<Result: Sendable>(
        _ body: @Sendable (Data) throws -> Result
    ) rethrows -> Result

    func clear()
}

/// The opaque reference form used by active operations such as credential
/// replacement. It is an existential alias rather than a serializable value.
typealias SecureInputRef = any SecureInput

/// Lifecycle boundary for creating and clearing operation-local secure inputs.
/// A concrete implementation belongs to the active-operation infrastructure,
/// not to the registry or persistent view state.
protocol SecureInputStore: Sendable {
    func makeReference(from value: String) -> SecureInputRef
    func clear(_ input: SecureInputRef)
}

enum EvidenceOutcome: String, Codable, Sendable {
    case passed
    case failed
    case pending
}

/// Evidence is deliberately limited to sanitized identifiers and summaries.
struct EvidenceRecord: Codable, Equatable, Sendable {
    let identifier: String
    let outcome: EvidenceOutcome
    let summary: String
}

protocol EvidenceStore: Sendable {
    func append(_ record: EvidenceRecord) async throws
    func records() async throws -> [EvidenceRecord]
}
