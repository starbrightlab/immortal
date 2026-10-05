/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The local, stable identifier used by the Portal Registry.
struct PortalID: Hashable, Codable, Sendable {
    let rawValue: UUID

    init(rawValue: UUID = UUID()) {
        self.rawValue = rawValue
    }
}

/// The version reported by the installed Immortal application.
///
/// Version metadata is non-secret and deliberately keeps both the numeric and
/// display forms returned by the Fleet Agent.
struct AppVersion: Codable, Sendable, Equatable, Hashable {
    var versionCode: Int64?
    var versionName: String?

    init(versionCode: Int64? = nil, versionName: String? = nil) {
        self.versionCode = versionCode
        self.versionName = versionName
    }
}

/// Identity data accepted only after a bearer-authenticated `/info` response.
struct PortalIdentity: Codable, Sendable, Equatable, Hashable {
    var portalID: PortalID
    var serial: String?
    var name: String
    var model: String
    var device: String?
    /// The exact model string reported by the Portal, retained for diagnostics.
    var rawModel: String
    var androidAPILevel: Int?
    var immortalVersion: AppVersion?

    init(
        portalID: PortalID,
        serial: String? = nil,
        name: String,
        model: String,
        device: String? = nil,
        rawModel: String? = nil,
        androidAPILevel: Int? = nil,
        immortalVersion: AppVersion? = nil
    ) {
        self.portalID = portalID
        self.serial = serial
        self.name = name
        self.model = model
        self.device = device
        self.rawModel = rawModel ?? model
        self.androidAPILevel = androidAPILevel
        self.immortalVersion = immortalVersion
    }
}

/// The address representation retained for a resolved or staged LAN endpoint.
enum AddressFamily: String, Codable, Sendable, Equatable, Hashable {
    case ipv4
    case ipv6
    case hostname
}

enum EndpointSource: Codable, Sendable, Equatable, Hashable {
    case mdns(serviceName: String)
    case manual
    case provisioning
    case authenticatedRefresh
}

/// A Portal endpoint. It contains connection metadata only; credentials are
/// represented separately by opaque Keychain references.
struct LANEndpoint: Codable, Sendable, Equatable, Hashable {
    static let defaultPortalAgentPort: UInt16 = 8723

    var hostOrAddress: String
    var port: UInt16
    var addressFamily: AddressFamily
    var interfaceZone: String?
    var source: EndpointSource
    var lastAuthenticatedAt: Date?

    init(
        hostOrAddress: String,
        port: UInt16 = LANEndpoint.defaultPortalAgentPort,
        addressFamily: AddressFamily,
        interfaceZone: String? = nil,
        source: EndpointSource,
        lastAuthenticatedAt: Date? = nil
    ) {
        self.hostOrAddress = hostOrAddress
        self.port = port
        self.addressFamily = addressFamily
        self.interfaceZone = interfaceZone
        self.source = source
        self.lastAuthenticatedAt = lastAuthenticatedAt
    }
}

/// Credential kinds are labels for Keychain entries, never the credential
/// values themselves.
enum CredentialKind: String, Codable, Sendable, Equatable, Hashable {
    case verifiedBearer
    case remoteSession
    case musicAssistant
    case snapcast
    case source
}

/// Local services whose credentials are kept separate from Portal credentials.
/// The service name is a non-secret account namespace, never a host or endpoint.
enum CredentialService: String, Codable, Sendable, Equatable, Hashable {
    case musicAssistant
    case snapcast
}

/// Source fields that may arrive as legacy cleartext values from a Portal.
/// Each field receives its own Portal/source-scoped Keychain item.
enum SourceSecretField: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case immichKey
    case smbUser
    case smbPass
    case davUser
    case davPass
}

/// An opaque reference to a Keychain item. No token, password, PIN, or other
/// authorizing value is represented by this type.
struct CredentialReference: Codable, Sendable, Equatable, Hashable {
    let namespace: String
    let identifier: String

    init(namespace: String, identifier: String) {
        self.namespace = namespace
        self.identifier = identifier
    }
}

enum PortalReachability: String, Codable, Sendable, Equatable, Hashable {
    case unknown
    case reachable
    case unreachable
}

/// The last confirmed, non-secret status returned by the Portal.
struct PortalStatus: Codable, Sendable, Equatable, Hashable {
    var reachability: PortalReachability
    /// Raw, non-secret presence state when supplied by `/info`.
    var presence: String?
    /// Raw, non-secret screen/presence state when supplied by `/info`.
    var screenState: String?
    var lastUpdatedAt: Date?
    var responseTimeMilliseconds: Int?

    init(
        reachability: PortalReachability = .unknown,
        presence: String? = nil,
        screenState: String? = nil,
        lastUpdatedAt: Date? = nil,
        responseTimeMilliseconds: Int? = nil
    ) {
        self.reachability = reachability
        self.presence = presence
        self.screenState = screenState
        self.lastUpdatedAt = lastUpdatedAt
        self.responseTimeMilliseconds = responseTimeMilliseconds
    }

    var isReachable: Bool {
        reachability == .reachable
    }
}

/// The non-secret local registry record for one managed Portal.
struct PortalRegistryEntry: Codable, Sendable, Equatable, Hashable {
    var id: PortalID
    var identity: PortalIdentity?
    var endpoint: LANEndpoint?
    var discoveredEndpoints: [LANEndpoint]
    var capabilities: PortalCapabilities?
    var credentialReferences: [CredentialReference]
    var lastSuccessfulContact: Date?
    var lastConfirmedStatus: PortalStatus?
    var connectionState: ConnectionState
    var policyMetadata: PortalPolicyMetadata

    init(
        id: PortalID,
        connectionState: ConnectionState,
        identity: PortalIdentity? = nil,
        endpoint: LANEndpoint? = nil,
        discoveredEndpoints: [LANEndpoint] = [],
        capabilities: PortalCapabilities? = nil,
        credentialReferences: [CredentialReference] = [],
        lastSuccessfulContact: Date? = nil,
        lastConfirmedStatus: PortalStatus? = nil,
        policyMetadata: PortalPolicyMetadata = PortalPolicyMetadata()
    ) {
        self.id = id
        self.identity = identity
        self.endpoint = endpoint
        self.discoveredEndpoints = discoveredEndpoints
        self.capabilities = capabilities
        self.credentialReferences = credentialReferences
        self.lastSuccessfulContact = lastSuccessfulContact
        self.lastConfirmedStatus = lastConfirmedStatus
        self.connectionState = connectionState
        self.policyMetadata = policyMetadata
    }
}
