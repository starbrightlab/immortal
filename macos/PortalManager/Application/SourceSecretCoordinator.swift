/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Handles the sensitive half of the `/remote/sources` contract.
///
/// The wire DTOs below are private to this coordinator. A source response is
/// decoded, partitioned, and projected to `SanitizedSourceSnapshot` before it
/// can be returned to application state. Legacy values are never returned by
/// this type, persisted in the registry, or passed to a logger/export sink.
struct SourceSecretCoordinator: Sendable {
    private let credentialStore: any CredentialStore
    private let secureInputStore: any SecureInputStore

    init(
        credentialStore: any CredentialStore,
        secureInputStore: any SecureInputStore = TransientSecureInputStore()
    ) {
        self.credentialStore = credentialStore
        self.secureInputStore = secureInputStore
    }

    /// Decodes a `/remote/sources` response and returns only non-secret fields
    /// plus configured-state metadata. `sourceID` is supplied by the caller so
    /// the same response shape can be used for distinct Portal source records.
    func sanitizedSnapshot(
        from response: Data,
        sourceID: String,
        keychainConfiguredFields: Set<SourceSecretField> = []
    ) throws -> SanitizedSourceSnapshot {
        let wire = try SourceResponseWireDTO.decode(from: response)
        return wire.sanitizedSnapshot(
            sourceID: sourceID,
            keychainConfiguredFields: keychainConfiguredFields
        )
    }

    /// Short alias for application adapters that call this boundary a sanitizer.
    func sanitize(
        _ response: Data,
        sourceID: String,
        keychainConfiguredFields: Set<SourceSecretField> = []
    ) throws -> SanitizedSourceSnapshot {
        try sanitizedSnapshot(
            from: response,
            sourceID: sourceID,
            keychainConfiguredFields: keychainConfiguredFields
        )
    }

    /// Migrates every nonblank legacy secret in one source response. Each field
    /// gets its own operation-local secure buffer and its own exact
    /// `PortalID/sourceID/field` Keychain item. A failed write is represented as
    /// `configuredButReentryRequired`; it never causes the cleartext response to
    /// be returned or reused for a later request.
    func migrateLegacySecrets(
        from response: Data,
        portalID: PortalID,
        sourceID: String
    ) async throws -> SourceSecretMigrationResult {
        let wire = try SourceResponseWireDTO.decode(from: response)
        var snapshot = wire.sanitizedSnapshot(sourceID: sourceID)
        var writes: [SourceSecretWriteResult] = []

        for field in SourceSecretField.allCases {
            guard let rawValue = wire.rawValue(for: field),
                  SourceSecretFieldPolicy.isConfigured(rawValue) else {
                continue
            }

            let key = SourceSecretKey(
                portalID: portalID,
                sourceID: sourceID,
                field: field
            )
            guard let legacyValue = wire.legacyValue(for: field) else {
                let result = SourceSecretWriteResult(
                    key: key,
                    status: .configuredButReentryRequired,
                    error: .validation(
                        field: field.rawValue,
                        reason: "The legacy source credential requires re-entry."
                    )
                )
                writes.append(result)
                snapshot = snapshot.updating(status: result.status, for: field)
                continue
            }

            let input = secureInputStore.makeReference(from: legacyValue)
            let result = await migrateLegacySecret(input, for: key)
            writes.append(result)
            snapshot = snapshot.updating(status: result.status, for: field)
        }

        return SourceSecretMigrationResult(snapshot: snapshot, writes: writes)
    }

    /// Migrates one legacy field from active-operation memory into its exact
    /// Keychain item. The secure input is cleared on success and failure.
    func migrateLegacySecret(
        _ input: SecureInputRef,
        for key: SourceSecretKey
    ) async -> SourceSecretWriteResult {
        await persist(input, for: key)
    }

    /// Label-order variant useful to coordinators that keep the field key first.
    func migrateLegacySecret(
        for key: SourceSecretKey,
        input: SecureInputRef
    ) async -> SourceSecretWriteResult {
        await migrateLegacySecret(input, for: key)
    }

    /// Replaces one existing source secret from active-operation memory. A blank
    /// replacement is rejected locally, so callers use `.preserve` for blank or
    /// omitted fields and never overwrite a nonblank Portal/Keychain value.
    func replaceSecret(
        _ input: SecureInputRef,
        for key: SourceSecretKey
    ) async -> SourceSecretWriteResult {
        await persist(input, for: key)
    }

    /// String convenience that creates and clears an operation-local secure
    /// input; the caller never needs to pass a cleartext value to Keychain APIs.
    func replaceSecret(
        _ value: String,
        for key: SourceSecretKey
    ) async -> SourceSecretWriteResult {
        let input = secureInputStore.makeReference(from: value)
        return await replaceSecret(input, for: key)
    }

    /// Explicit replacement convenience for migration adapters that already
    /// hold a decoded legacy value only inside the active operation.
    func migrateLegacySecret(
        _ value: String,
        for key: SourceSecretKey
    ) async -> SourceSecretWriteResult {
        let input = secureInputStore.makeReference(from: value)
        return await migrateLegacySecret(input, for: key)
    }

    /// Applies the field-presence rule used by source edits. Preserve emits no
    /// Keychain write and replace performs exactly one scoped write.
    func apply(
        _ edit: SourceSecretEdit,
        for key: SourceSecretKey
    ) async -> SourceSecretWriteResult? {
        switch edit {
        case .preserve:
            return nil
        case .replace(let input):
            return await replaceSecret(input, for: key)
        }
    }

    private func persist(
        _ input: SecureInputRef,
        for key: SourceSecretKey
    ) async -> SourceSecretWriteResult {
        var value = Data()
        defer {
            wipe(&value)
            secureInputStore.clear(input)
        }

        guard key.isValid else {
            return SourceSecretWriteResult(
                key: key,
                status: .configuredButReentryRequired,
                error: .keychain(.invalidReference)
            )
        }

        do {
            value = try input.withData { data in
                guard !data.isEmpty else {
                    throw ManagerError.validation(
                        field: key.field.rawValue,
                        reason: "A blank source credential preserves the existing value."
                    )
                }
                return Data(data)
            }

            try await credentialStore.write(value, for: key.credentialReference)
            return SourceSecretWriteResult(
                key: key,
                status: .configuredInKeychain
            )
        } catch is CancellationError {
            return SourceSecretWriteResult(
                key: key,
                status: .configuredButReentryRequired,
                error: .cancelled
            )
        } catch let error as ManagerError {
            return SourceSecretWriteResult(
                key: key,
                status: .configuredButReentryRequired,
                error: sanitizedWriteError(error)
            )
        } catch {
            return SourceSecretWriteResult(
                key: key,
                status: .configuredButReentryRequired,
                error: .keychain(.writeFailed)
            )
        }
    }

    private func sanitizedWriteError(_ error: ManagerError) -> ManagerError {
        guard case .keychain = error else {
            return .keychain(.writeFailed)
        }
        return error
    }

    private func wipe(_ value: inout Data) {
        guard !value.isEmpty else { return }
        value.resetBytes(in: 0..<value.count)
        value.removeAll(keepingCapacity: false)
    }
}

// MARK: - Private source wire DTOs

private struct SourceResponseWireDTO: Decodable {
    let sources: SourceWireDTO

    static func decode(from data: Data) throws -> SourceWireDTO {
        let decoder = JSONDecoder()

        if let envelope = try? decoder.decode(Self.self, from: data) {
            return envelope.sources
        }
        if let direct = try? decoder.decode(SourceWireDTO.self, from: data) {
            return direct
        }

        throw ManagerError.validation(
            field: "sources",
            reason: "The source response could not be decoded."
        )
    }
}

/// Private allowlist boundary for a source response. The raw dictionary exists
/// only while the active source operation partitions it into safe metadata and
/// migration-required field statuses.
private struct SourceWireDTO: Decodable {
    private let fields: [String: JSONValue]

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        fields = try container.decode([String: JSONValue].self)
    }

    func sanitizedSnapshot(
        sourceID: String,
        keychainConfiguredFields: Set<SourceSecretField> = []
    ) -> SanitizedSourceSnapshot {
        var secretStatus: [SourceSecretField: SourceSecretStatus] = [:]
        for field in SourceSecretField.allCases {
            if keychainConfiguredFields.contains(field) {
                secretStatus[field] = .configuredInKeychain
            } else if SourceSecretFieldPolicy.isConfigured(fields[field.rawValue]) {
                secretStatus[field] = .legacyConfiguredMigrationRequired
            } else {
                secretStatus[field] = .notConfigured
            }
        }

        return SanitizedSourceSnapshot(
            sourceID: sourceID,
            nonSecretFields: SourceSecretFieldPolicy.sanitizedNonSecretFields(fields),
            secretStatus: secretStatus
        )
    }

    func rawValue(for field: SourceSecretField) -> JSONValue? {
        fields[field.rawValue]
    }

    func legacyValue(for field: SourceSecretField) -> String? {
        guard case let .string(value) = fields[field.rawValue] else {
            return nil
        }
        return value
    }
}
