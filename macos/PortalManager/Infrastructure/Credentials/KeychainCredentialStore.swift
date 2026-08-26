/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import Security

/// The production credential persistence boundary for the Portal Manager.
///
/// Generic-password items are scoped to the application bundle identifier through
/// `kSecAttrService`. The account contains only validated, non-secret ownership
/// metadata; credential bytes are supplied only as the Keychain item data.
struct KeychainCredentialStore: CredentialStore, Sendable {
    static let defaultBundleIdentifier = "com.starbrightlab.portalmanager"

    private let bundleService: String

    /// Uses the application bundle identifier as the Keychain service. An explicit
    /// identifier is injectable for deterministic infrastructure tests and never
    /// changes where credential bytes are stored outside the Keychain.
    init(bundleIdentifier: String? = Bundle.main.bundleIdentifier) {
        bundleService = bundleIdentifier ?? Self.defaultBundleIdentifier
    }

    /// The bundle-scoped service used for generic-password items.
    var serviceIdentifier: String {
        bundleService
    }

    func read(_ reference: CredentialReference) async throws -> Data? {
        let account = try Self.accountIdentifier(for: reference)
        let query = try baseQuery(account: account)
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        switch status {
        case errSecSuccess:
            guard let data = result as? Data else {
                throw ManagerError.keychain(.readFailed)
            }
            return data
        case errSecItemNotFound:
            return nil
        default:
            throw Self.operationError(status: status, operation: .read)
        }
    }

    func write(_ value: Data, for reference: CredentialReference) async throws {
        let account = try Self.accountIdentifier(for: reference)
        let query = try baseQuery(account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: value
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return
        }

        guard updateStatus == errSecItemNotFound else {
            throw Self.operationError(status: updateStatus, operation: .write)
        }

        var addQuery = query
        addQuery[kSecValueData as String] = value
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        if addStatus == errSecSuccess {
            return
        }

        // Another writer may have created the item between the update and add.
        // Retry the same scoped update, but never fall back to another storage
        // location or expose the value in an error.
        if addStatus == errSecDuplicateItem {
            let retryStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if retryStatus == errSecSuccess {
                return
            }
            throw Self.operationError(status: retryStatus, operation: .write)
        }

        throw Self.operationError(status: addStatus, operation: .write)
    }

    func delete(_ reference: CredentialReference) async throws {
        let account = try Self.accountIdentifier(for: reference)
        let query = try baseQuery(account: account)
        let status = SecItemDelete(query as CFDictionary)

        switch status {
        case errSecSuccess, errSecItemNotFound:
            // Deletion is idempotent so removal cleanup cannot be blocked by a
            // credential that has already been removed.
            return
        default:
            throw Self.operationError(status: status, operation: .delete)
        }
    }

    /// Derives the Keychain account from an opaque reference after validating its
    /// scope. This method intentionally does not accept display names, endpoints,
    /// credential values, or arbitrary account strings.
    static func accountIdentifier(for reference: CredentialReference) throws -> String {
        let components = reference.identifier.split(separator: "/", omittingEmptySubsequences: false)

        switch reference.namespace {
        case CredentialReferenceNamespace.portal:
            guard components.count == 2,
                  let portalID = normalizedUUID(components[0]),
                  let kind = CredentialKind(rawValue: String(components[1])),
                  kind == .verifiedBearer || kind == .remoteSession else {
                throw ManagerError.keychain(.invalidReference)
            }
            return "portal/\(portalID)/\(kind.rawValue)"

        case CredentialReferenceNamespace.service:
            guard components.count == 2 || components.count == 3 else {
                throw ManagerError.keychain(.invalidReference)
            }

            let serviceIndex = components.count == 2 ? 0 : 1
            let kindIndex = components.count == 2 ? 1 : 2
            if components.count == 3 && normalizedUUID(components[0]) == nil {
                throw ManagerError.keychain(.invalidReference)
            }

            guard let service = CredentialService(rawValue: String(components[serviceIndex])),
                  let kind = CredentialKind(rawValue: String(components[kindIndex])),
                  isValid(service: service, kind: kind) else {
                throw ManagerError.keychain(.invalidReference)
            }

            if components.count == 2 {
                return "service/\(service.rawValue)/\(kind.rawValue)"
            }
            guard let portalID = normalizedUUID(components[0]) else {
                throw ManagerError.keychain(.invalidReference)
            }
            return "service/\(portalID)/\(service.rawValue)/\(kind.rawValue)"

        case CredentialReferenceNamespace.source:
            guard components.count == 4,
                  let portalID = normalizedUUID(components[0]),
                  components[1] == Substring(CredentialKind.source.rawValue),
                  let field = SourceSecretField(rawValue: String(components[3])),
                  let sourceID = String(components[2]).removingPercentEncoding,
                  !sourceID.isEmpty,
                  !sourceID.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
                throw ManagerError.keychain(.invalidReference)
            }

            let encodedSourceID = String(components[2])
            guard !encodedSourceID.isEmpty else {
                throw ManagerError.keychain(.invalidReference)
            }
            return "source/\(portalID)/\(CredentialKind.source.rawValue)/\(encodedSourceID)/\(field.rawValue)"

        default:
            throw ManagerError.keychain(.invalidReference)
        }
    }

    private func baseQuery(account: String) throws -> [String: Any] {
        guard !bundleService.isEmpty,
              !bundleService.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
            throw ManagerError.keychain(.unavailable)
        }

        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: bundleService,
            kSecAttrAccount as String: account
        ]
    }

    private enum Operation {
        case read
        case write
        case delete
    }

    private static func operationError(status: OSStatus, operation: Operation) -> ManagerError {
        switch status {
        case errSecNotAvailable, errSecNoSuchKeychain:
            return .keychain(.unavailable)
        case errSecAuthFailed, errSecInteractionNotAllowed, errSecUserCanceled, errSecMissingEntitlement:
            return .keychain(.accessDenied)
        default:
            switch operation {
            case .read:
                return .keychain(.readFailed)
            case .write:
                return .keychain(.writeFailed)
            case .delete:
                return .keychain(.deleteFailed)
            }
        }
    }

    private static func normalizedUUID(_ value: Substring) -> String? {
        guard let uuid = UUID(uuidString: String(value)) else { return nil }
        return uuid.uuidString.lowercased()
    }

    private static func isValid(service: CredentialService, kind: CredentialKind) -> Bool {
        switch (service, kind) {
        case (.musicAssistant, .musicAssistant), (.snapcast, .snapcast):
            return true
        default:
            return false
        }
    }
}

private enum CredentialReferenceNamespace {
    static let portal = "portal"
    static let service = "service"
    static let source = "source"
}

private func encodeSourceReferenceComponent(_ value: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
}

/// Typed constructors for references accepted by `KeychainCredentialStore`.
/// The resulting values remain opaque and contain no credential bytes.
extension CredentialReference {
    static func portalCredential(portalID: PortalID, kind: CredentialKind) -> Self {
        Self(
            namespace: CredentialReferenceNamespace.portal,
            identifier: "\(portalID.rawValue.uuidString.lowercased())/\(kind.rawValue)"
        )
    }

    static func serviceCredential(
        service: CredentialService,
        kind: CredentialKind,
        portalID: PortalID? = nil
    ) -> Self {
        let scope = portalID.map { "\($0.rawValue.uuidString.lowercased())/" } ?? ""
        return Self(
            namespace: CredentialReferenceNamespace.service,
            identifier: "\(scope)\(service.rawValue)/\(kind.rawValue)"
        )
    }

    static func sourceCredential(
        portalID: PortalID,
        sourceID: String,
        field: SourceSecretField
    ) -> Self {
        Self(
            namespace: CredentialReferenceNamespace.source,
            identifier: "\(portalID.rawValue.uuidString.lowercased())/\(CredentialKind.source.rawValue)/\(encodeSourceReferenceComponent(sourceID))/\(field.rawValue)"
        )
    }
}
