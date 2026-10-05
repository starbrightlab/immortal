/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The exact credential fields that can be shared between managed Portals.
/// The closed set prevents an arbitrary settings key from becoming a secret
/// copy operation.
enum ShareableCredentialField: String, Codable, Sendable, CaseIterable, Hashable {
    case immichKey
    case smbUser
    case smbPass
    case davUser
    case davPass
}

/// A source-scoped, opaque credential reference. It identifies a Keychain item
/// but never contains or logs the credential bytes.
struct SharedCredentialSource: Codable, Sendable, Equatable, Hashable {
    let portalID: PortalID
    let sourceID: String
    let fields: Set<ShareableCredentialField>

    init(portalID: PortalID, sourceID: String, fields: Set<ShareableCredentialField>) throws {
        let trimmedSource = sourceID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedSource.isEmpty,
              !sourceID.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              !fields.isEmpty else {
            throw FleetCredentialSyncError.invalidRequest
        }

        self.portalID = portalID
        self.sourceID = trimmedSource
        self.fields = fields
    }

    func reference(for field: ShareableCredentialField) -> CredentialReference {
        .sourceCredential(
            portalID: portalID,
            sourceID: sourceID,
            field: SourceSecretField(rawValue: field.rawValue) ?? .immichKey
        )
    }
}

/// A validated destination and its non-secret admission snapshot.
struct CredentialSyncTarget: Codable, Sendable, Equatable, Hashable {
    let portalID: PortalID
    let connectionState: ConnectionState
    let credentialReferences: [CredentialReference]

    var isEligible: Bool {
        guard !credentialReferences.isEmpty else { return false }
        switch connectionState {
        case .online, .bearerAuthenticated:
            return true
        default:
            return false
        }
    }
}

enum FleetCredentialSyncError: Error, Equatable, LocalizedError, Sendable {
    case invalidRequest
    case ineligibleTarget(PortalID)
    case sourceUnavailable
    case destinationRejected

    var errorDescription: String? {
        switch self {
        case .invalidRequest:
            return "The credential sync request is incomplete."
        case .ineligibleTarget:
            return "One selected Portal is not ready to receive credentials."
        case .sourceUnavailable:
            return "The source credentials are not available right now."
        case .destinationRejected:
            return "The destination did not accept the credential update."
        }
    }
}

enum CredentialSyncOutcome: Equatable, Sendable {
    case succeeded
    case offline
    case rejected
    case cancelled
}

struct CredentialSyncResult: Equatable, Sendable {
    let portalID: PortalID
    let outcome: CredentialSyncOutcome
}

struct FleetCredentialSyncReport: Equatable, Sendable {
    let results: [CredentialSyncResult]

    var successfulCount: Int {
        results.filter { $0.outcome == .succeeded }.count
    }

    /// Never claims fleet-wide success when any target failed or was skipped.
    var isFullySuccessful: Bool {
        !results.isEmpty && results.allSatisfy { $0.outcome == .succeeded }
    }
}
