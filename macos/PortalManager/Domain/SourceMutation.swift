/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The non-secret state of one source credential field.
///
/// The state deliberately does not carry the legacy value. A source response
/// containing a value is represented as migration-required until an explicit
/// operation writes that value to the exact Portal/source Keychain item.
enum SourceSecretStatus: Sendable, Equatable, Hashable {
    case notConfigured
    case configuredInKeychain
    case legacyConfiguredMigrationRequired
    case configuredButReentryRequired
}

/// Compatibility spelling for callers that describe this as a field status.
typealias SourceFieldStatus = SourceSecretStatus

/// The exact ownership key for a source secret. A source secret is never
/// addressable by a display name, endpoint, or a global service account.
struct SourceSecretKey: Hashable, Codable, Sendable {
    let portalID: PortalID
    let sourceID: String
    let field: SourceSecretField

    init(
        portalID: PortalID,
        sourceID: String,
        field: SourceSecretField
    ) {
        self.portalID = portalID
        self.sourceID = sourceID
        self.field = field
    }

    /// The opaque Keychain reference for this exact Portal/source/field.
    var credentialReference: CredentialReference {
        .sourceCredential(
            portalID: portalID,
            sourceID: sourceID,
            field: field
        )
    }

    /// Rejects values that could not be represented by the scoped Keychain
    /// account format. The source identifier itself is never logged or returned
    /// as an error detail.
    var isValid: Bool {
        let trimmed = sourceID.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty
            && !sourceID.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
    }
}

/// The only source edit forms accepted by the source-secret operation.
enum SourceSecretEdit: Sendable {
    /// Blank or omitted input preserves both the Portal value and Keychain item.
    case preserve
    /// A deliberate replacement held in an operation-local secure buffer.
    case replace(SecureInputRef)
}

/// Sanitized source state safe for application/UI snapshots.
///
/// `nonSecretFields` is filtered again at construction time so a future caller
/// cannot accidentally place a known or credential-shaped field into a source
/// snapshot. This type intentionally has no Codable conformance: it is not a
/// registry or other persistence DTO.
struct SanitizedSourceSnapshot: Sendable, Equatable, Hashable {
    let sourceID: String
    let nonSecretFields: [String: JSONValue]
    let secretStatus: [SourceSecretField: SourceSecretStatus]

    init(
        sourceID: String,
        nonSecretFields: [String: JSONValue] = [:],
        secretStatus: [SourceSecretField: SourceSecretStatus] = [:]
    ) {
        self.sourceID = sourceID
        self.nonSecretFields = SourceSecretFieldPolicy.sanitizedNonSecretFields(
            nonSecretFields
        )

        var completeStatus = SourceSecretField.allCases.reduce(
            into: [SourceSecretField: SourceSecretStatus]()
        ) { result, field in
            result[field] = .notConfigured
        }
        for (field, status) in secretStatus {
            completeStatus[field] = status
        }
        self.secretStatus = completeStatus
    }

    func status(for field: SourceSecretField) -> SourceSecretStatus {
        secretStatus[field] ?? .notConfigured
    }

    func updating(
        status: SourceSecretStatus,
        for field: SourceSecretField
    ) -> SanitizedSourceSnapshot {
        var updatedStatuses = secretStatus
        updatedStatuses[field] = status
        return SanitizedSourceSnapshot(
            sourceID: sourceID,
            nonSecretFields: nonSecretFields,
            secretStatus: updatedStatuses
        )
    }
}

/// The result of one explicit migration or replacement. It contains only the
/// scoped opaque key, status, and sanitized failure category.
struct SourceSecretWriteResult: Sendable, Equatable, Hashable {
    let key: SourceSecretKey
    let status: SourceSecretStatus
    let error: ManagerError?

    init(
        key: SourceSecretKey,
        status: SourceSecretStatus,
        error: ManagerError? = nil
    ) {
        self.key = key
        self.status = status
        self.error = error
    }

    var didPersist: Bool {
        status == .configuredInKeychain
    }
}

/// The sanitized result of migrating all legacy source fields in one response.
struct SourceSecretMigrationResult: Sendable, Equatable, Hashable {
    let snapshot: SanitizedSourceSnapshot
    let writes: [SourceSecretWriteResult]

    func status(for field: SourceSecretField) -> SourceSecretStatus {
        snapshot.status(for: field)
    }
}

/// Centralized classification used by the private wire DTO and by sanitized
/// snapshots. Known legacy fields are exact; future credential-shaped fields
/// are removed rather than being treated as ordinary source metadata.
enum SourceSecretFieldPolicy {
    static func knownField(for key: String) -> SourceSecretField? {
        SourceSecretField(rawValue: key)
    }

    static func isSensitiveKey(_ key: String) -> Bool {
        let normalized = normalize(key)
        if SourceSecretField.allCases.contains(where: { normalize($0.rawValue) == normalized }) {
            return true
        }

        return normalized.contains("password")
            || normalized.contains("passwd")
            || normalized.contains("credential")
            || normalized.contains("accesstoken")
            || normalized.contains("refreshtoken")
            || normalized.contains("sessiontoken")
            || normalized.contains("secret")
            || normalized.contains("apikey")
            || normalized.hasSuffix("token")
            || normalized.hasSuffix("username")
            || normalized.hasSuffix("user")
            || normalized.hasSuffix("pass")
            || normalized.hasSuffix("key")
    }

    static func isConfigured(_ value: JSONValue?) -> Bool {
        guard let value else { return false }
        switch value {
        case .null:
            return false
        case let .string(string):
            return !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .bool, .number, .array, .object:
            // A malformed non-string secret is still sensitive and must never
            // be copied into a non-secret projection.
            return true
        }
    }

    static func sanitizedNonSecretFields(
        _ fields: [String: JSONValue]
    ) -> [String: JSONValue] {
        fields.reduce(into: [String: JSONValue]()) { result, field in
            guard !isSensitiveKey(field.key) else { return }
            result[field.key] = field.value
        }
    }

    private static func normalize(_ key: String) -> String {
        key.unicodeScalars
            .filter { CharacterSet.alphanumerics.contains($0) }
            .map(String.init)
            .joined()
            .lowercased()
    }
}
