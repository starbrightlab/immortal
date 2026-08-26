/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import Network

// MARK: - Production DNS resolution

/// Resolves hostnames through the system resolver. Literal addresses short-
/// circuit through the injected boundary so LAN validation still runs.
struct SystemDNSResolver: DNSResolver {
    init() {}

    func resolve(_ request: DNSResolutionRequest) async throws -> DNSResolutionResult {
        // Literal addresses never hit DNS.
        if let literal = Self.literalAddress(request.hostname) {
            return DNSResolutionResult(addresses: [literal])
        }

        return try await withCheckedThrowingContinuation { continuation in
            // The POSIX resolver enumerates both AAAA and A records; the
            // permitted-address selector re-validates ordering afterwards.
            let box = ContinuationOnce(continuation)
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let addresses = try Self.resolvePOSIX(request.hostname)
                    box.succeed(DNSResolutionResult(addresses: addresses))
                } catch {
                    box.fail(error)
                }
            }
        }
    }

    private static func literalAddress(_ value: String) -> ResolvedAddress? {
        let trimmed = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        if trimmed.contains(":") {
            // Bracketed or bare IPv6 literal; strip any zone suffix for the
            // address itself and keep the zone separately.
            var address = trimmed
            var zone: String?
            if let percentIndex = address.firstIndex(of: "%") {
                zone = String(address[address.index(after: percentIndex)...])
                address = String(address[..<percentIndex])
            }
            var candidate = in6_addr()
            let result = address.withCString { pointer in
                inet_pton(AF_INET6, pointer, &candidate)
            }
            if result == 1 {
                return ResolvedAddress(address: address, interfaceZone: zone)
            }
            return nil
        }
        var candidate = in_addr()
        let result = trimmed.withCString { pointer in
            inet_pton(AF_INET, pointer, &candidate)
        }
        if result == 1 {
            return ResolvedAddress(address: trimmed)
        }
        return nil
    }

    /// POSIX getaddrinfo resolution returning both AAAA and A records.
    private static func resolvePOSIX(_ hostname: String) throws -> [ResolvedAddress] {
        var hints = addrinfo()
        hints.ai_family = AF_UNSPEC
        hints.ai_flags = AI_ADDRCONFIG

        var info: UnsafeMutablePointer<addrinfo>?
        let status = getaddrinfo(hostname, nil, &hints, &info)
        guard status == 0, let first = info else {
            throw ManagerError.resolution(status == EAI_NONAME ? .invalidHost : .failed)
        }
        defer { freeaddrinfo(info) }

        var addresses: [ResolvedAddress] = []
        var current: UnsafeMutablePointer<addrinfo>? = first
        while let item = current {
            defer { current = item.pointee.ai_next }
            guard let socketAddress = item.pointee.ai_addr else { continue }

            switch socketAddress.pointee.sa_family {
            case sa_family_t(AF_INET6):
                var addr6 = sockaddr_in6()
                withUnsafeBytes(of: socketAddress.pointee) { raw in
                    addr6 = raw.load(as: sockaddr_in6.self)
                }
                var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
                var in6 = addr6.sin6_addr
                inet_ntop(AF_INET6, &in6, &buffer, socklen_t(INET6_ADDRSTRLEN))
                let text = String(cString: buffer)
                let scopeID = UInt32(addr6.sin6_scope_id)
                var zone: String?
                if scopeID != 0 {
                    var nameBuffer = [CChar](repeating: 0, count: Int(IFNAMSIZ))
                    if_indextoname(scopeID, &nameBuffer)
                    let name = String(cString: nameBuffer)
                    zone = name.isEmpty ? String(scopeID) : name
                }
                addresses.append(ResolvedAddress(address: text, interfaceZone: zone))

            case sa_family_t(AF_INET):
                var addr4 = sockaddr_in()
                withUnsafeBytes(of: socketAddress.pointee) { raw in
                    addr4 = raw.load(as: sockaddr_in.self)
                }
                var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                var in4 = addr4.sin_addr
                inet_ntop(AF_INET, &in4, &buffer, socklen_t(INET_ADDRSTRLEN))
                addresses.append(ResolvedAddress(address: String(cString: buffer)))

            default:
                break
            }
        }

        guard !addresses.isEmpty else {
            throw ManagerError.resolution(.noAddresses)
        }
        return addresses
    }
}

/// Ensures a checked continuation resumes exactly once across racing paths.
final class ContinuationOnce<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var finished = false
    private let continuation: CheckedContinuation<T, Error>

    init(_ continuation: CheckedContinuation<T, Error>) {
        self.continuation = continuation
    }

    func succeed(_ value: T) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        lock.unlock()
        continuation.resume(returning: value)
    }

    func fail(_ error: Error) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        lock.unlock()
        continuation.resume(throwing: error)
    }
}

// MARK: - File-backed trust acknowledgements

/// Persists non-secret trust-warning scopes as JSON under Application Support.
/// Scopes are keyed only by service kind, protocol, normalized resolved
/// address, and port — never credentials.
struct FileTrustWarningStore: TrustWarningStore {
    private let fileURL: URL
    private let lock = NSLock()

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first?.appendingPathComponent("PortalManager", isDirectory: true)
        let resolved = base ?? FileManager.default.temporaryDirectory
            .appendingPathComponent("PortalManager", isDirectory: true)
        try? FileManager.default.createDirectory(at: resolved, withIntermediateDirectories: true)
        self.fileURL = resolved.appendingPathComponent("trust-warnings.json")
    }

    private struct File: Codable {
        var acknowledgements: [TrustWarningAcknowledgement] = []
    }

    private func loadFile() -> File {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode(File.self, from: data) else {
            return File()
        }
        return decoded
    }

    private func saveFile(_ file: File) {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? JSONEncoder().encode(file) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtection])
    }

    func acknowledgement(
        for scope: TrustWarningScope
    ) async throws -> TrustWarningAcknowledgement? {
        loadFile().acknowledgements.first { $0.scope.normalized == scope.normalized }
    }

    func acknowledge(_ scope: TrustWarningScope, at date: Date) async throws {
        var file = loadFile()
        file.acknowledgements.removeAll { $0.scope.normalized == scope.normalized }
        file.acknowledgements.append(
            TrustWarningAcknowledgement(scope: scope.normalized, acknowledgedAt: date)
        )
        saveFile(file)
    }
}

// MARK: - File-backed release evidence

actor FileReleaseEvidenceStore: ReleaseEvidenceStore {
    private let fileURL: URL

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first?.appendingPathComponent("PortalManager", isDirectory: true)
        let resolved = base ?? FileManager.default.temporaryDirectory
            .appendingPathComponent("PortalManager", isDirectory: true)
        try? FileManager.default.createDirectory(at: resolved, withIntermediateDirectories: true)
        self.fileURL = resolved.appendingPathComponent("release-evidence.json")
    }

    private struct File: Codable {
        var records: [ReleaseEvidenceRecord] = []
    }

    func save(_ record: ReleaseEvidenceRecord) async throws {
        var file = (try? Data(contentsOf: fileURL)).flatMap {
            try? JSONDecoder().decode(File.self, from: $0)
        } ?? File()
        let key = ReleaseGateReport.key(for: record.gateID)
        file.records.removeAll { ReleaseGateReport.key(for: $0.gateID) == key }
        file.records.append(record)
        if let data = try? JSONEncoder().encode(file) {
            try? data.write(to: fileURL, options: [.atomic, .completeFileProtection])
        }
    }

    func records(candidateVersion: String) async throws -> [ReleaseEvidenceRecord] {
        guard let data = try? Data(contentsOf: fileURL),
              let file = try? JSONDecoder().decode(File.self, from: data) else {
            return []
        }
        return file.records
            .filter { $0.candidateVersion == candidateVersion }
            .sorted { $0.recordedAt < $1.recordedAt }
    }
}

// MARK: - Production music service clients

    /// Stateful read-only Music Assistant client bound to one admitted endpoint.
final class ProductionMusicAssistantClient: MusicAssistantClient, @unchecked Sendable {
    private let credentialProvider: ServiceCredentialProvider
    private var credentialReference: CredentialReference?
    private var admittedHostOrAddress: String?
    private var admittedPort: UInt16 = MusicServiceConfiguration.defaultMusicAssistantPort
    private var admittedZone: String?

    init(credentialProvider: ServiceCredentialProvider) {
        self.credentialProvider = credentialProvider
    }

    /// Binds an admitted connection and the exact credential reference after
    /// LAN admission, keeping Keychain reads inside the active operation.
    func bind(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        reference: CredentialReference?
    ) {
        admittedHostOrAddress = hostOrAddress
        admittedPort = port
        admittedZone = interfaceZone
        credentialReference = reference
    }

    func connect(_ configuration: MusicAssistantConfiguration) async throws {
        // The typed call path binds through `bind(...)` after admission; this
        // port entry point stays symmetric with the injected contract.
    }

    func topology() async throws -> any MusicAssistantTopology {
        guard let host = admittedHostOrAddress else {
            throw DependencyPortError.unavailable(.musicAssistant)
        }
        let provider = ServiceCredentialProvider(
            store: credentialProvider.store,
            reference: credentialReference ?? credentialProvider.reference
        )
        return try await ReadOnlyMusicAssistantAdapter(credentialProvider: provider)
            .topology(
                hostOrAddress: host,
                port: admittedPort,
                interfaceZone: admittedZone
            )
    }

    func disconnect() async {
        admittedHostOrAddress = nil
        credentialReference = nil
    }
}

/// Stateful read-only Snapcast client bound to one admitted endpoint.
final class ProductionSnapcastClient: SnapcastClient, @unchecked Sendable {
    private let credentialProvider: ServiceCredentialProvider
    private var credentialReference: CredentialReference?
    private var admittedHostOrAddress: String?
    private var admittedPort: UInt16 = MusicServiceConfiguration.defaultSnapcastPort
    private var admittedZone: String?

    init(credentialProvider: ServiceCredentialProvider) {
        self.credentialProvider = credentialProvider
    }

    /// Binds an admitted connection after LAN admission.
    func bind(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        reference: CredentialReference?
    ) {
        admittedHostOrAddress = hostOrAddress
        admittedPort = port
        admittedZone = interfaceZone
        credentialReference = reference
    }

    func connect(_ configuration: SnapcastConfiguration) async throws {}

    func topology() async throws -> any SnapcastTopology {
        guard let host = admittedHostOrAddress else {
            throw DependencyPortError.unavailable(.snapcast)
        }
        let provider = ServiceCredentialProvider(
            store: credentialProvider.store,
            reference: credentialReference ?? credentialProvider.reference
        )
        return try await ReadOnlySnapcastAdapter(credentialProvider: provider)
            .topology(
                hostOrAddress: host,
                port: admittedPort,
                interfaceZone: admittedZone
            )
    }

    func disconnect() async {
        admittedHostOrAddress = nil
        credentialReference = nil
    }

    func setClientVolume(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        clientID: String,
        percent: Int
    ) async throws {
        let provider = ServiceCredentialProvider(
            store: credentialProvider.store,
            reference: credentialReference ?? credentialProvider.reference
        )
        try await ReadOnlySnapcastAdapter(credentialProvider: provider).setClientVolume(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            clientID: clientID,
            percent: percent
        )
    }

    func setGroupClients(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        clientIDs: [String]
    ) async throws {
        let provider = ServiceCredentialProvider(
            store: credentialProvider.store,
            reference: credentialReference ?? credentialProvider.reference
        )
        try await ReadOnlySnapcastAdapter(credentialProvider: provider).setGroupClients(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            groupID: groupID,
            clientIDs: clientIDs
        )
    }

    func setGroupName(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        name: String
    ) async throws {
        let provider = ServiceCredentialProvider(
            store: credentialProvider.store,
            reference: credentialReference ?? credentialProvider.reference
        )
        try await ReadOnlySnapcastAdapter(credentialProvider: provider).setGroupName(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            groupID: groupID,
            name: name
        )
    }

    func setStream(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        streamID: String
    ) async throws {
        let provider = ServiceCredentialProvider(
            store: credentialProvider.store,
            reference: credentialReference ?? credentialProvider.reference
        )
        try await ReadOnlySnapcastAdapter(credentialProvider: provider).setStream(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            groupID: groupID,
            streamID: streamID
        )
    }
}
