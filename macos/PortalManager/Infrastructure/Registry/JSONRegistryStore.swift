/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Safe, stable failures for local registry persistence.
///
/// Underlying file-system errors, paths, response bodies, and decoded values are
/// intentionally not retained in these errors. The registry is non-secret, but
/// its failure surface must still be safe to show in the UI and diagnostics.
enum RegistryStoreError: Error, Codable, Equatable, LocalizedError, Sendable {
    case invalidLocation
    case readFailed
    case writeFailed
    case invalidData
    case unsupportedSchema
    case containsSensitiveData
    case invalidEntry
    case encodingFailed

    var errorDescription: String? {
        switch self {
        case .invalidLocation:
            return "The Portal Registry location is invalid."
        case .readFailed:
            return "The Portal Registry could not be read."
        case .writeFailed:
            return "The Portal Registry could not be saved."
        case .invalidData:
            return "The Portal Registry contains invalid data."
        case .unsupportedSchema:
            return "The Portal Registry format is not supported."
        case .containsSensitiveData:
            return "The Portal Registry contains a value that cannot be persisted."
        case .invalidEntry:
            return "The Portal Registry contains an invalid entry."
        case .encodingFailed:
            return "The Portal Registry could not be encoded."
        }
    }
}

/// JSON-backed persistence for non-secret Portal Registry metadata.
///
/// The on-disk representation is a private allowlist DTO rather than a direct
/// encoding of application state. This prevents a future field on
/// `PortalRegistryEntry`, an error detail, or a raw protocol payload from
/// silently becoming a persisted secret. Credential values are never accepted
/// by this type; only opaque `CredentialReference` values are copied.
actor JSONRegistryStore: RegistryStore {
    static let currentSchemaVersion = 1

    private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// Convenience label for callers that use `url` for local persistence.
    init(url: URL) {
        self.init(fileURL: url)
    }

    func load() async throws -> RegistrySnapshot {
        guard fileURL.isFileURL else {
            throw RegistryStoreError.invalidLocation
        }

        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return RegistrySnapshot()
        }

        let data: Data
        do {
            data = try Data(contentsOf: fileURL, options: [.mappedIfSafe])
        } catch {
            throw RegistryStoreError.readFailed
        }

        do {
            try RegistrySecretFieldScanner.rejectSensitiveKeys(in: data)
        } catch let error as RegistryStoreError {
            throw error
        } catch {
            throw RegistryStoreError.invalidData
        }

        let persistedFile: PersistedRegistryFile
        do {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            persistedFile = try decoder.decode(PersistedRegistryFile.self, from: data)
        } catch let error as RegistryStoreError {
            throw error
        } catch {
            throw RegistryStoreError.invalidData
        }

        guard persistedFile.schemaVersion == Self.currentSchemaVersion else {
            throw RegistryStoreError.unsupportedSchema
        }

        do {
            return try persistedFile.snapshot()
        } catch let error as RegistryStoreError {
            throw error
        } catch {
            throw RegistryStoreError.invalidEntry
        }
    }

    func save(_ snapshot: RegistrySnapshot) async throws {
        guard fileURL.isFileURL else {
            throw RegistryStoreError.invalidLocation
        }

        let persistedFile: PersistedRegistryFile
        do {
            persistedFile = try PersistedRegistryFile(snapshot: snapshot)
        } catch let error as RegistryStoreError {
            throw error
        } catch {
            throw RegistryStoreError.invalidEntry
        }

        let data: Data
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            data = try encoder.encode(persistedFile)
        } catch {
            throw RegistryStoreError.encodingFailed
        }

        // Validate the actual bytes as a final defense against an accidentally
        // expanded DTO or a future field added to one of its nested values.
        do {
            try RegistrySecretFieldScanner.rejectSensitiveKeys(in: data)
        } catch let error as RegistryStoreError {
            throw error
        } catch {
            throw RegistryStoreError.invalidData
        }

        let parentURL = fileURL.deletingLastPathComponent()
        do {
            try FileManager.default.createDirectory(
                at: parentURL,
                withIntermediateDirectories: true,
                attributes: nil
            )
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            throw RegistryStoreError.writeFailed
        }
    }
}

// MARK: - Private persistence DTOs

private struct PersistedRegistryFile: Codable, Sendable {
    let schemaVersion: Int
    let entries: [PersistedPortalEntry]

    init(snapshot: RegistrySnapshot) throws {
        schemaVersion = JSONRegistryStore.currentSchemaVersion
        entries = try snapshot.entries.map(PersistedPortalEntry.init)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        // Version 1 originally had no explicit version field. Treating a
        // missing version as v1 permits a safe non-secret migration while
        // still rejecting all future versions.
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
        entries = try container.decodeIfPresent([PersistedPortalEntry].self, forKey: .entries) ?? []
    }

    func snapshot() throws -> RegistrySnapshot {
        RegistrySnapshot(entries: try entries.map { try $0.domainEntry() })
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case entries
    }
}

private struct PersistedPortalEntry: Codable, Sendable {
    let id: PortalID
    let identity: PortalIdentity?
    let endpoint: LANEndpoint?
    let discoveredEndpoints: [LANEndpoint]
    let capabilities: PersistedPortalCapabilities?
    let credentialReferences: [CredentialReference]
    let lastSuccessfulContact: Date?
    let lastConfirmedStatus: PortalStatus?
    let connectionState: PersistedConnectionState
    let policyMetadata: PersistedPortalPolicyMetadata

    init(_ entry: PortalRegistryEntry) throws {
        id = entry.id
        identity = entry.identity
        endpoint = entry.endpoint
        discoveredEndpoints = entry.discoveredEndpoints
        capabilities = try entry.capabilities.map(PersistedPortalCapabilities.init)
        credentialReferences = try entry.credentialReferences.map {
            try RegistrySecretFieldScanner.validateOpaqueReference($0)
            return $0
        }
        lastSuccessfulContact = entry.lastSuccessfulContact
        lastConfirmedStatus = entry.lastConfirmedStatus
        connectionState = try PersistedConnectionState(entry.connectionState)
        policyMetadata = try PersistedPortalPolicyMetadata(entry.policyMetadata)
    }

    func domainEntry() throws -> PortalRegistryEntry {
        if let identity, identity.portalID != id {
            throw RegistryStoreError.invalidEntry
        }

        let references = try credentialReferences.map {
            try RegistrySecretFieldScanner.validateOpaqueReference($0)
            return $0
        }

        return PortalRegistryEntry(
            id: id,
            connectionState: connectionState.domainState,
            identity: identity,
            endpoint: endpoint,
            discoveredEndpoints: discoveredEndpoints,
            capabilities: try capabilities?.domainCapabilities(),
            credentialReferences: references,
            lastSuccessfulContact: lastSuccessfulContact,
            lastConfirmedStatus: lastConfirmedStatus,
            policyMetadata: try policyMetadata.domainMetadata()
        )
    }
}

private struct PersistedPortalCapabilities: Codable, Sendable {
    let modelFamily: PortalModelFamily
    let androidAPILevel: Int?
    let fleetInfo: Bool
    let settingsRegistry: Bool
    let sources: Bool
    let screensaver: Bool
    let calendar: Bool
    let identify: Bool
    let reaffirm: Bool
    let rawAdvertisedCapabilities: [String: JSONValue]

    init(_ capabilities: PortalCapabilities) throws {
        modelFamily = capabilities.modelFamily
        androidAPILevel = capabilities.androidAPILevel
        fleetInfo = capabilities.fleetInfo
        settingsRegistry = capabilities.settingsRegistry
        sources = capabilities.sources
        screensaver = capabilities.screensaver
        calendar = capabilities.calendar
        identify = capabilities.identify
        reaffirm = capabilities.reaffirm
        rawAdvertisedCapabilities = try capabilities.rawAdvertisedCapabilities.mapValues {
            try RegistrySecretFieldScanner.sanitizeCapabilityValue($0)
        }
    }

    func domainCapabilities() throws -> PortalCapabilities {
        PortalCapabilities(
            modelFamily: modelFamily,
            androidAPILevel: androidAPILevel,
            fleetInfo: fleetInfo,
            settingsRegistry: settingsRegistry,
            sources: sources,
            screensaver: screensaver,
            calendar: calendar,
            identify: identify,
            reaffirm: reaffirm,
            rawAdvertisedCapabilities: rawAdvertisedCapabilities
        )
    }
}

private struct PersistedPortalPolicyMetadata: Codable, Sendable {
    let compatibility: CompatibilityAssessment
    let operationWarnings: [String: String]
    let notes: [String]

    init(_ metadata: PortalPolicyMetadata) throws {
        compatibility = try RegistrySecretFieldScanner.sanitizeCompatibility(metadata.compatibility)
        operationWarnings = try metadata.operationWarnings.mapValues {
            try RegistrySecretFieldScanner.sanitizeText($0)
        }
        notes = try metadata.notes.map(RegistrySecretFieldScanner.sanitizeText)
    }

    func domainMetadata() throws -> PortalPolicyMetadata {
        PortalPolicyMetadata(
            compatibility: compatibility,
            operationWarnings: operationWarnings,
            notes: notes
        )
    }
}

/// Connection-state representation that preserves useful non-secret state but
/// intentionally drops raw inputs, diagnostic details, and process/protocol
/// output. Those values are re-created as stable prompts on load.
private enum PersistedConnectionState: Codable, Sendable {
    case discovered(candidate: DiscoveryReference)
    case resolving
    case lanValidated(endpoint: LANEndpoint, trustScope: TrustWarningScope)
    case pairingRequired(endpoint: LANEndpoint)
    case remoteSessionPaired(lastPairedAt: Date)
    case remoteSessionReady(lastReadAt: Date?)
    case bearerVerificationRequired
    case bearerAuthenticated(identity: PortalIdentity, verifiedAt: Date)
    case online(lastRefresh: Date, latencyMs: Int)
    case provisioning(mode: ProvisioningMode, step: ProvisioningStepID)
    case offline(lastContact: Date?, reason: String)
    case reauthenticationRequired(kind: CredentialKind)
    case unsupported(reason: String)
    case error

    init(_ state: ConnectionState) throws {
        switch state {
        case .discovered(let candidate):
            self = .discovered(candidate: candidate)
        case .resolving:
            // The entered host is transient input and is not persisted.
            self = .resolving
        case .lanValidated(let endpoint, let trustScope):
            self = .lanValidated(endpoint: endpoint, trustScope: trustScope)
        case .pairingRequired(let endpoint):
            self = .pairingRequired(endpoint: endpoint)
        case .remoteSessionPaired(let lastPairedAt):
            self = .remoteSessionPaired(lastPairedAt: lastPairedAt)
        case .remoteSessionReady(let lastReadAt):
            self = .remoteSessionReady(lastReadAt: lastReadAt)
        case .bearerVerificationRequired:
            self = .bearerVerificationRequired
        case .bearerAuthenticated(let identity, let verifiedAt):
            self = .bearerAuthenticated(identity: identity, verifiedAt: verifiedAt)
        case .online(let lastRefresh, let latencyMs):
            self = .online(lastRefresh: lastRefresh, latencyMs: latencyMs)
        case .provisioning(let mode, let step):
            self = .provisioning(mode: mode, step: step)
        case .offline(let lastContact, let reason):
            self = .offline(
                lastContact: lastContact,
                reason: try RegistrySecretFieldScanner.sanitizeText(reason)
            )
        case .reauthenticationRequired(let kind, _):
            // Reauthentication reasons may contain transport diagnostics; the
            // credential kind is enough to restore the safe state.
            self = .reauthenticationRequired(kind: kind)
        case .unsupported(let reason):
            self = .unsupported(reason: try RegistrySecretFieldScanner.sanitizeText(reason))
        case .error:
            // ManagerError can carry HTTP detail, field text, or protocol
            // diagnostics. Persist only the fact that a refresh is required.
            self = .error
        }
    }

    var domainState: ConnectionState {
        switch self {
        case .discovered(let candidate):
            return .discovered(candidate: candidate)
        case .resolving:
            return .resolving(input: "Restored state")
        case .lanValidated(let endpoint, let trustScope):
            return .lanValidated(endpoint: endpoint, trustScope: trustScope)
        case .pairingRequired(let endpoint):
            return .pairingRequired(endpoint: endpoint)
        case .remoteSessionPaired(let lastPairedAt):
            return .remoteSessionPaired(lastPairedAt: lastPairedAt)
        case .remoteSessionReady(let lastReadAt):
            return .remoteSessionReady(lastReadAt: lastReadAt)
        case .bearerVerificationRequired:
            return .bearerVerificationRequired(reason: "Bearer verification is required.")
        case .bearerAuthenticated(let identity, let verifiedAt):
            return .bearerAuthenticated(identity: identity, verifiedAt: verifiedAt)
        case .online(let lastRefresh, let latencyMs):
            return .online(lastRefresh: lastRefresh, latencyMs: latencyMs)
        case .provisioning(let mode, let step):
            return .provisioning(mode: mode, step: step)
        case .offline(let lastContact, let reason):
            return .offline(lastContact: lastContact, reason: reason)
        case .reauthenticationRequired(let kind):
            return .reauthenticationRequired(
                kind: kind,
                reason: "The credential requires authentication again."
            )
        case .unsupported(let reason):
            return .unsupported(reason: reason)
        case .error:
            return .offline(
                lastContact: nil,
                reason: "A previous error requires a fresh status check."
            )
        }
    }
}

// MARK: - Persistence safety checks

private enum RegistrySecretFieldScanner {
    private static let forbiddenKeyNames: Set<String> = Set([
        "accessToken",
        "agentJson",
        "agentManifest",
        "authorization",
        "authorizationHeader",
        "authorizationHeaders",
        "bearer",
        "bearerToken",
        "body",
        "cleartext",
        "credential",
        "credentials",
        "davPass",
        "davUser",
        "diagnostic",
        "diagnostics",
        "headers",
        "immichKey",
        "manifest",
        "maAccessToken",
        "maPassword",
        "maUsername",
        "pairingPin",
        "password",
        "passwd",
        "pin",
        "processOutput",
        "rawAgentJson",
        "rawBody",
        "rawResponse",
        "remoteSession",
        "remoteSessionToken",
        "responseBody",
        "secret",
        "sessionToken",
        "smbPass",
        "smbUser",
        "stdout",
        "stderr",
        "token",
        "transcript",
        "username",
        "values"
    ].map { normalizedKey($0) })

    private static let suspiciousTextFragments = [
        "authorization:",
        "bearer ",
        "access_token",
        "refresh_token",
        "pairing pin",
        "immichkey",
        "smbpass",
        "smbuser",
        "davpass",
        "davuser",
        "ma_password",
        "ma_username",
        "password=",
        "passwd=",
        "token=",
        "pin=",
        "rawresponse",
        "rawbody",
        "agent.json"
    ]

    static func rejectSensitiveKeys(in data: Data) throws {
        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            throw RegistryStoreError.invalidData
        }
        try rejectSensitiveKeys(in: object)
    }

    static func validateOpaqueReference(_ reference: CredentialReference) throws {
        guard !reference.namespace.isEmpty,
              !reference.identifier.isEmpty,
              !looksLikeSensitiveText(reference.namespace),
              !looksLikeSensitiveText(reference.identifier) else {
            throw RegistryStoreError.containsSensitiveData
        }
    }

    static func sanitizeText(_ value: String) throws -> String {
        guard !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              !looksLikeSensitiveText(value) else {
            throw RegistryStoreError.containsSensitiveData
        }
        return value
    }

    static func sanitizeCompatibility(
        _ value: CompatibilityAssessment
    ) throws -> CompatibilityAssessment {
        switch value {
        case .compatible:
            return .compatible
        case .warning(let reason):
            return .warning(reason: try sanitizeText(reason))
        case .operationUnavailable(let operation, let reason):
            return .operationUnavailable(
                operation: try sanitizeText(operation),
                reason: try sanitizeText(reason)
            )
        }
    }

    static func sanitizeCapabilityValue(_ value: JSONValue) throws -> JSONValue {
        switch value {
        case .null, .bool:
            return value
        case .number(let number):
            guard number.isFinite else {
                throw RegistryStoreError.invalidEntry
            }
            return .number(number)
        case .string(let string):
            return .string(try sanitizeText(string))
        case .array(let values):
            return .array(try values.map(sanitizeCapabilityValue))
        case .object(let values):
            var sanitized: [String: JSONValue] = [:]
            for (key, nestedValue) in values {
                guard !isForbiddenKey(key) else {
                    throw RegistryStoreError.containsSensitiveData
                }
                sanitized[key] = try sanitizeCapabilityValue(nestedValue)
            }
            return .object(sanitized)
        }
    }

    private static func rejectSensitiveKeys(in value: Any) throws {
        if let dictionary = value as? [String: Any] {
            for (key, nestedValue) in dictionary {
                guard !isForbiddenKey(key) else {
                    throw RegistryStoreError.containsSensitiveData
                }
                try rejectSensitiveKeys(in: nestedValue)
            }
        } else if let array = value as? [Any] {
            for nestedValue in array {
                try rejectSensitiveKeys(in: nestedValue)
            }
        }
    }

    private static func isForbiddenKey(_ key: String) -> Bool {
        let normalized = normalizedKey(key)
        if forbiddenKeyNames.contains(normalized) {
            return true
        }

        return normalized.contains("password")
            || normalized.contains("accesstoken")
            || normalized.contains("authorization")
            || normalized.contains("pairingpin")
            || normalized.contains("sessiontoken")
            || normalized.contains("agentmanifest")
            || normalized.contains("processoutput")
            || normalized.contains("diagnostic")
    }

    private static func normalizedKey(_ key: String) -> String {
        key.unicodeScalars
            .filter { CharacterSet.alphanumerics.contains($0) }
            .map(String.init)
            .joined()
            .lowercased()
    }

    private static func looksLikeSensitiveText(_ value: String) -> Bool {
        let normalized = value.lowercased()
        return suspiciousTextFragments.contains { normalized.contains($0) }
    }
}
