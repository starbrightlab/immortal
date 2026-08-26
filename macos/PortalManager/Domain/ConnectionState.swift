/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Untrusted discovery metadata retained only as a candidate reference.
struct DiscoveryReference: Codable, Sendable, Equatable, Hashable {
    var serviceName: String
    var hostOrAddress: String
    var port: UInt16
    var interfaceName: String?

    init(
        serviceName: String,
        hostOrAddress: String,
        port: UInt16,
        interfaceName: String? = nil
    ) {
        self.serviceName = serviceName
        self.hostOrAddress = hostOrAddress
        self.port = port
        self.interfaceName = interfaceName
    }
}

enum ServiceKind: String, Codable, Sendable, Equatable, Hashable {
    case portal
    case musicAssistant
    case snapcast
}

/// The exact non-secret scope for a local-network trust acknowledgement.
struct TrustWarningScope: Codable, Sendable, Equatable, Hashable {
    var serviceKind: ServiceKind
    var protocolName: String
    var resolvedHostOrAddress: String
    var port: UInt16
    var interfaceZone: String?

    init(
        serviceKind: ServiceKind,
        protocolName: String,
        resolvedHostOrAddress: String,
        port: UInt16,
        interfaceZone: String? = nil
    ) {
        self.serviceKind = serviceKind
        self.protocolName = Self.normalizeProtocol(protocolName)
        self.resolvedHostOrAddress = Self.normalizeHost(resolvedHostOrAddress)
        self.port = port
        self.interfaceZone = Self.normalizeZone(interfaceZone)
    }

    /// Rebuilds a scope from its stable, non-secret key components. Admission
    /// uses this value before querying or writing the acknowledgement store.
    var normalized: TrustWarningScope {
        TrustWarningScope(
            serviceKind: serviceKind,
            protocolName: protocolName,
            resolvedHostOrAddress: resolvedHostOrAddress,
            port: port,
            interfaceZone: interfaceZone
        )
    }

    private static func normalizeProtocol(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func normalizeHost(_ value: String) -> String {
        var normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if normalized.hasSuffix(".") {
            normalized.removeLast()
        }
        return normalized
    }

    private static func normalizeZone(_ value: String?) -> String? {
        guard let value else { return nil }
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized.isEmpty ? nil : normalized
    }
}

enum ProvisioningMode: String, Codable, Sendable, Equatable, Hashable {
    case fleetAgentEnablementRecovery
    case fullUSBProvisioning
}

/// Identifies a currently executing provisioning phase without carrying
/// process output, paths, credentials, or other sensitive data.
enum ProvisioningStepID: String, Codable, Sendable, Equatable, Hashable {
    case preflight
    case artifactVerification
    case deviceSetup
    case installation
    case writeProvisionFile
    case relaunchImmortal
    case readAgentManifest
    case bearerVerification
    case complete
}

/// Assurance-preserving connection state for a registry entry.
///
/// Associated strings are sanitized operator-facing input/reason text. No
/// credential value or raw protocol/process output belongs in this enum.
enum ConnectionState: Codable, Sendable, Equatable, Hashable {
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
