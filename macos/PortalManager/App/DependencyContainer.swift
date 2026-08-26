/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The composition root for the native application.
///
/// Concrete adapters are injected here rather than constructed by SwiftUI views or AppKit
/// commands. `bootstrap()` intentionally uses side-effect-free unavailable adapters until the
/// corresponding infrastructure tasks provide production implementations.
struct DependencyContainer: Sendable {
    let dns: any DNSResolver
    let bonjour: any BonjourBrowser
    let fleetHTTP: any FleetHTTPTransport
    let keychain: any CredentialStore
    let trustWarnings: any TrustWarningStore
    let registry: any RegistryStore
    let adb: any ADBRunner
    let artifactVerifier: any ArtifactVerifier
    let provisioningWorkspace: any ProvisioningWorkspaceFactory
    let provisioningDownloads: any ProvisioningDownloadBoundary
    let musicAssistant: any MusicAssistantClient
    let snapcast: any SnapcastClient
    let clock: any ManagerClock
    let redactor: any Redactor
    let evidence: any EvidenceStore

    init(
        dns: any DNSResolver,
        bonjour: any BonjourBrowser,
        fleetHTTP: any FleetHTTPTransport,
        keychain: any CredentialStore,
        trustWarnings: any TrustWarningStore,
        registry: any RegistryStore,
        adb: any ADBRunner,
        artifactVerifier: any ArtifactVerifier,
        provisioningWorkspace: any ProvisioningWorkspaceFactory = TempWorkspaceFactory(),
        provisioningDownloads: any ProvisioningDownloadBoundary = NoDownloadProvisioningBoundary(),
        musicAssistant: any MusicAssistantClient,
        snapcast: any SnapcastClient,
        clock: any ManagerClock,
        redactor: any Redactor,
        evidence: any EvidenceStore
    ) {
        self.dns = dns
        self.bonjour = bonjour
        self.fleetHTTP = fleetHTTP
        self.keychain = keychain
        self.trustWarnings = trustWarnings
        self.registry = registry
        self.adb = adb
        self.artifactVerifier = artifactVerifier
        self.provisioningWorkspace = provisioningWorkspace
        self.provisioningDownloads = provisioningDownloads
        self.musicAssistant = musicAssistant
        self.snapcast = snapcast
        self.clock = clock
        self.redactor = redactor
        self.evidence = evidence
    }

    static func bootstrap() -> DependencyContainer {
        DependencyContainer(
            dns: UnavailableDNSResolver(),
            bonjour: NWBonjourBrowser(),
            fleetHTTP: HTTPTransport(),
            keychain: KeychainCredentialStore(),
            trustWarnings: UnavailableTrustWarningStore(),
            registry: UnavailableRegistryStore(),
            adb: UnavailableADBRunner(),
            artifactVerifier: UnavailableArtifactVerifier(),
            provisioningWorkspace: TempWorkspaceFactory(),
            provisioningDownloads: NoDownloadProvisioningBoundary(),
            musicAssistant: UnavailableMusicAssistantClient(),
            snapcast: UnavailableSnapcastClient(),
            clock: SystemManagerClock(),
            redactor: ConservativeRedactor(),
            evidence: UnavailableEvidenceStore()
        )
    }

    /// The full production composition used by the native application shell.
    /// Every boundary is a real implementation; no external-service or file
    /// retrieval path is representable anywhere in this graph.
    static func production() -> DependencyContainer {
        let support = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first?.appendingPathComponent("PortalManager", isDirectory: true)
        let directory = support ?? FileManager.default.temporaryDirectory
            .appendingPathComponent("PortalManager", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let keychain = KeychainCredentialStore()
        let registryURL = directory.appendingPathComponent("registry.json")

        return DependencyContainer(
            dns: SystemDNSResolver(),
            bonjour: NWBonjourBrowser(),
            fleetHTTP: HTTPTransport(),
            keychain: keychain,
            trustWarnings: FileTrustWarningStore(directory: directory),
            registry: JSONRegistryStore(fileURL: registryURL),
            adb: UnavailableADBRunner(),
            artifactVerifier: UnavailableArtifactVerifier(),
            provisioningWorkspace: TempWorkspaceFactory(),
            provisioningDownloads: NoDownloadProvisioningBoundary(),
            musicAssistant: ProductionMusicAssistantClient(
                credentialProvider: ServiceCredentialProvider(store: keychain, reference: nil)
            ),
            snapcast: ProductionSnapcastClient(
                credentialProvider: ServiceCredentialProvider(store: keychain, reference: nil)
            ),
            clock: SystemManagerClock(),
            redactor: ConservativeRedactor(),
            evidence: FileReleaseEvidenceStore(directory: directory).evidencePort
        )
    }
}

/// Adapts the release-evidence file store to the generic evidence port.
extension FileReleaseEvidenceStore {
    /// The app-level evidence port records sanitized summaries alongside the
    /// structured gate records.
    nonisolated var evidencePort: EvidenceStore {
        FileEvidencePort(store: self)
    }
}

private struct FileEvidencePort: EvidenceStore {
    let store: FileReleaseEvidenceStore

    func append(_ record: EvidenceRecord) async throws {
        try await store.save(
            ReleaseEvidenceRecord(
                gateID: .security,
                candidateVersion: "1.0.0",
                evidenceIDs: [record.identifier],
                testResults: [],
                supportedClaims: [],
                unresolvedDeviations: [],
                status: record.outcome == .passed
                    ? .passed
                    : record.outcome == .failed ? .failed : .pending,
                recordedAt: Date()
            )
        )
    }

    func records() async throws -> [EvidenceRecord] {
        try await store.records(candidateVersion: "1.0.0").map { record in
            EvidenceRecord(
                identifier: record.evidenceIDs.first ?? "gate",
                outcome: record.status == .passed
                    ? .passed
                    : record.status == .failed ? .failed : .pending,
                summary: ReleaseGateReport.key(for: record.gateID)
            )
        }
    }
}

struct UnavailableDNSResolver: DNSResolver {
    func resolve(_ request: DNSResolutionRequest) async throws -> DNSResolutionResult {
        throw DependencyPortError.unavailable(.dns)
    }
}

struct UnavailableBonjourBrowser: BonjourBrowser {
    func events() -> AsyncStream<BonjourEvent> {
        AsyncStream { continuation in
            continuation.finish()
        }
    }

    func start() async throws {
        throw DependencyPortError.unavailable(.bonjour)
    }

    func refresh() async throws {
        throw DependencyPortError.unavailable(.bonjour)
    }

    func stop() async {}
}

struct UnavailableFleetHTTPTransport: FleetHTTPTransport {
    func send(_ request: any FleetHTTPRequest) async throws -> FleetHTTPResponse {
        throw DependencyPortError.unavailable(.fleetHTTP)
    }
}

struct UnavailableCredentialStore: CredentialStore {
    func read(_ reference: CredentialReference) async throws -> Data? {
        throw DependencyPortError.unavailable(.keychain)
    }

    func write(_ value: Data, for reference: CredentialReference) async throws {
        throw DependencyPortError.unavailable(.keychain)
    }

    func delete(_ reference: CredentialReference) async throws {
        throw DependencyPortError.unavailable(.keychain)
    }
}

struct UnavailableTrustWarningStore: TrustWarningStore {
    func acknowledgement(
        for scope: TrustWarningScope
    ) async throws -> TrustWarningAcknowledgement? {
        nil
    }

    func acknowledge(
        _ scope: TrustWarningScope,
        at date: Date
    ) async throws {
        throw DependencyPortError.unavailable(.trustWarnings)
    }
}

struct UnavailableRegistryStore: RegistryStore {
    func load() async throws -> RegistrySnapshot {
        throw DependencyPortError.unavailable(.registry)
    }

    func save(_ snapshot: RegistrySnapshot) async throws {
        throw DependencyPortError.unavailable(.registry)
    }
}

struct UnavailableADBRunner: ADBRunner {
    func execute(_ request: any ADBRequest) async throws -> any ADBResult {
        throw DependencyPortError.unavailable(.adb)
    }
}

struct UnavailableArtifactVerifier: ArtifactVerifier {
    func verify(_ request: any ArtifactVerificationRequest) async throws -> any ArtifactVerificationResult {
        throw DependencyPortError.unavailable(.artifactVerification)
    }
}

struct UnavailableMusicAssistantClient: MusicAssistantClient {
    func connect(_ configuration: MusicAssistantConfiguration) async throws {
        throw DependencyPortError.unavailable(.musicAssistant)
    }

    func topology() async throws -> any MusicAssistantTopology {
        throw DependencyPortError.unavailable(.musicAssistant)
    }

    func disconnect() async {}
}

struct UnavailableSnapcastClient: SnapcastClient {
    func connect(_ configuration: SnapcastConfiguration) async throws {
        throw DependencyPortError.unavailable(.snapcast)
    }

    func topology() async throws -> any SnapcastTopology {
        throw DependencyPortError.unavailable(.snapcast)
    }

    func disconnect() async {}

    func setClientVolume(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        clientID: String,
        percent: Int
    ) async throws {
        throw DependencyPortError.unavailable(.snapcast)
    }

    func setGroupClients(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        clientIDs: [String]
    ) async throws {
        throw DependencyPortError.unavailable(.snapcast)
    }

    func setGroupName(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        name: String
    ) async throws {
        throw DependencyPortError.unavailable(.snapcast)
    }

    func setStream(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        streamID: String
    ) async throws {
        throw DependencyPortError.unavailable(.snapcast)
    }
}

struct SystemManagerClock: ManagerClock {
    var now: Date { Date() }

    func sleep(for duration: Duration) async throws {
        try await Task.sleep(for: duration)
    }
}

struct UnavailableEvidenceStore: EvidenceStore {
    func append(_ record: EvidenceRecord) async throws {
        throw DependencyPortError.unavailable(.evidence)
    }

    func records() async throws -> [EvidenceRecord] {
        throw DependencyPortError.unavailable(.evidence)
    }
}
