/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

enum LANPolicyReason: String, Codable, Sendable, Equatable, Hashable {
    case malformedHost
    case unresolved
    case noPermittedAddress
    case publicAddress
    case unsupportedAddressFamily
    case missingInterfaceZone
    case invalidPort
}

enum ResolutionReason: String, Codable, Sendable, Equatable, Hashable {
    case invalidHost
    case noAddresses
    case timedOut
    case failed
}

enum TransportReason: String, Codable, Sendable, Equatable, Hashable {
    case connectionFailed
    case timedOut
    case connectionClosed
    case networkUnavailable
}

enum AuthenticationReason: String, Codable, Sendable, Equatable, Hashable {
    case missingCredential
    case invalidCredential
    case revokedCredential
    case credentialUnavailable
    case unauthorized
}

enum PairingReason: String, Codable, Sendable, Equatable, Hashable {
    case blankPIN
    case invalidPIN
    case expiredPIN
    case alreadyRedeemed
    case rejected
    case requestFailed
}

enum KeychainReason: String, Codable, Sendable, Equatable, Hashable {
    case unavailable
    case accessDenied
    case invalidReference
    case missing
    case readFailed
    case writeFailed
    case deleteFailed
}

enum ArtifactVerificationFailure: String, Codable, Sendable, Equatable, Hashable {
    case notReadable
    case notRegularFile
    case packageIdentityMismatch
    case signatureRejected
    case digestUnavailable
    case digestMismatch
    case apiIncompatible
    case abiIncompatible
    case modelIncompatible
}

enum GateID: Codable, Sendable, Equatable, Hashable {
    case security
    case lan
    case provisioning
    case packaging
    case modelMatrix
    case portalTV
    case musicMutation(service: String, operation: String, contract: String)
}

enum ManagerErrorCategory: String, Codable, Sendable, Equatable, Hashable {
    case lanPolicy
    case resolution
    case discovery
    case transport
    case redirect
    case http
    case authentication
    case pairing
    case capability
    case settingsPolicy
    case validation
    case keychain
    case artifactVerification
    case provisioning
    case protocolError
    case excludedOperation
    case releaseGate
    case cancelled
}

enum RecoveryAction: String, Codable, Sendable, Equatable, Hashable {
    case retry
    case editEndpoint
    case refreshDiscovery
    case pairAgain
    case reauthenticate
    case reviewCapability
    case reviewInput
    case selectLocalArtifact
    case retryProvisioning
    case reviewReleaseEvidence
    case acknowledgeExclusion
    case none
}

enum RetryPolicy: String, Codable, Sendable, Equatable, Hashable {
    case immediate
    case afterCorrection
    case notRecommended
    case never
}

/// Typed, serializable manager failures. Any associated text supplied by a
/// caller must already be sanitized; the computed presentation below never
/// includes raw detail, headers, credentials, or process output.
enum ManagerError: Error, Codable, Sendable, Equatable, Hashable {
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
    case `protocol`(service: String, reason: String)
    case excludedOperation(String)
    case releaseGate(GateID, reason: String)
    case cancelled

    var category: ManagerErrorCategory {
        switch self {
        case .lanPolicy: return .lanPolicy
        case .resolution: return .resolution
        case .discovery: return .discovery
        case .transport: return .transport
        case .redirectRejected: return .redirect
        case .http: return .http
        case .authentication: return .authentication
        case .pairing: return .pairing
        case .capabilityUnavailable: return .capability
        case .settingsPolicy: return .settingsPolicy
        case .validation, .unsupportedPartialEdit: return .validation
        case .keychain: return .keychain
        case .artifactVerification: return .artifactVerification
        case .provisioning: return .provisioning
        case .`protocol`: return .protocolError
        case .excludedOperation: return .excludedOperation
        case .releaseGate: return .releaseGate
        case .cancelled: return .cancelled
        }
    }

    var recoveryAction: RecoveryAction {
        switch self {
        case .lanPolicy, .resolution, .redirectRejected:
            return .editEndpoint
        case .discovery:
            return .refreshDiscovery
        case .transport:
            return .retry
        case .http:
            return .retry
        case .authentication, .keychain:
            return .reauthenticate
        case .pairing:
            return .pairAgain
        case .capabilityUnavailable:
            return .reviewCapability
        case .settingsPolicy, .validation, .unsupportedPartialEdit:
            return .reviewInput
        case .artifactVerification:
            return .selectLocalArtifact
        case .provisioning:
            return .retryProvisioning
        case .`protocol`:
            return .retry
        case .excludedOperation:
            return .acknowledgeExclusion
        case .releaseGate:
            return .reviewReleaseEvidence
        case .cancelled:
            return .none
        }
    }

    var retryPolicy: RetryPolicy {
        switch self {
        case .lanPolicy, .redirectRejected, .authentication, .keychain,
             .capabilityUnavailable, .settingsPolicy, .validation,
             .unsupportedPartialEdit, .artifactVerification, .excludedOperation,
             .releaseGate, .cancelled:
            return .afterCorrection
        case .resolution, .transport, .discovery, .http, .pairing, .provisioning, .`protocol`:
            return .immediate
        }
    }

    /// A stable, non-secret description suitable for UI and diagnostics.
    var sanitizedMessage: String {
        switch self {
        case .lanPolicy:
            return "Version-one management is LAN-only; the destination is not an admitted LAN address."
        case .resolution(.noAddresses):
            return "No LAN address was resolved; version-one management is LAN-only."
        case .resolution:
            return "The endpoint could not be resolved."
        case .discovery: return "Portal discovery failed."
        case .transport: return "The local service could not be reached."
        case .redirectRejected: return "The request was redirected and was rejected."
        case let .http(status, _, _): return "The Fleet Agent returned HTTP status \(status)."
        case .authentication: return "The credential requires authentication again."
        case .pairing: return "Portal pairing did not complete."
        case .capabilityUnavailable: return "The selected operation is unavailable on this Portal."
        case .settingsPolicy: return "The setting is not approved for this operation."
        case .validation: return "The supplied value failed validation."
        case .unsupportedPartialEdit: return "The partial edit is not supported by the Portal contract."
        case .keychain: return "The required Keychain credential is unavailable."
        case .artifactVerification: return "The local artifact failed verification."
        case .provisioning: return "USB provisioning did not complete."
        case .`protocol`: return "The local service returned an invalid protocol result."
        case .excludedOperation: return "The requested operation is outside version-one scope."
        case .releaseGate: return "The required release evidence is not available."
        case .cancelled: return "The operation was cancelled."
        }
    }
}

extension ManagerError: LocalizedError {
    var errorDescription: String? { sanitizedMessage }
}
