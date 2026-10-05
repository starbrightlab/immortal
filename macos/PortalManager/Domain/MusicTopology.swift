/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

// MARK: - Service identity and endpoints

/// Stable service-level configuration. Defaults follow the repository's
/// Portal-side clients: Music Assistant `8095` `/ws`, Snapcast control `1705`.
struct MusicServiceConfiguration: Codable, Sendable, Equatable, Hashable {
    static let defaultMusicAssistantPort: UInt16 = 8095
    static let defaultSnapcastPort: UInt16 = 1705

    let serviceKind: ServiceKind
    let hostOrAddress: String
    var port: UInt16
    let credentialReference: CredentialReference?

    init(
        serviceKind: ServiceKind,
        hostOrAddress: String,
        port: UInt16? = nil,
        credentialReference: CredentialReference? = nil
    ) {
        self.serviceKind = serviceKind
        self.hostOrAddress = hostOrAddress
        self.port = port ?? (serviceKind == .musicAssistant
            ? Self.defaultMusicAssistantPort
            : Self.defaultSnapcastPort)
        self.credentialReference = credentialReference
    }

    static func musicAssistant(
        hostOrAddress: String,
        port: UInt16? = nil,
        credentialReference: CredentialReference? = nil
    ) -> Self {
        Self(
            serviceKind: .musicAssistant,
            hostOrAddress: hostOrAddress,
            port: port ?? defaultMusicAssistantPort,
            credentialReference: credentialReference
        )
    }

    static func snapcast(
        hostOrAddress: String,
        port: UInt16? = nil,
        credentialReference: CredentialReference? = nil
    ) -> Self {
        Self(
            serviceKind: .snapcast,
            hostOrAddress: hostOrAddress,
            port: port ?? defaultSnapcastPort,
            credentialReference: credentialReference
        )
    }
}

/// Connection/authentication state for a music service. Authentication
/// failure is deliberately distinct from network failure so an invalid
/// supplied credential never reads as "server offline".
enum MusicServiceConnectionState: Codable, Sendable, Equatable, Hashable {
    case disconnected
    case connecting
    case connectedUnauthenticated
    case authenticated
    case authenticationFailed(reason: String)
    case networkFailed(reason: String)
    case timedOut

    var isAuthenticated: Bool {
        if case .authenticated = self { return true }
        return false
    }
}

// MARK: - Topology models

/// A Music Assistant player. Server identifiers are preserved independently
/// from display names; equal names never identify two players.
struct MAPlayer: Codable, Sendable, Equatable, Hashable, Identifiable {
    let playerID: String
    let name: String
    let online: Bool
    let groupID: String?
    let currentMediaTitle: String?

    var id: String { playerID }
}

/// A Music Assistant provider (metadata, streaming, etc.) entry.
struct MAProvider: Codable, Sendable, Equatable, Hashable, Identifiable {
    let providerID: String
    let providerType: String
    let name: String

    var id: String { providerID }
}

/// A Music Assistant group. Membership is by stable player IDs only.
struct MAGroup: Codable, Sendable, Equatable, Hashable, Identifiable {
    let groupID: String
    let name: String
    let memberPlayerIDs: [String]

    var id: String { groupID }
}

/// Read-only Music Assistant topology snapshot.
struct MusicTopologySnapshot: Codable, Sendable, Equatable, Hashable {
    let serviceKind: ServiceKind
    let connectionState: MusicServiceConnectionState
    let players: [MAPlayer]
    let providers: [MAProvider]
    let groups: [MAGroup]
    let serverVersion: String?
    let readAt: Date
}

/// A Snapcast stream (Server.GetStatus → server.streams[]).
struct SnapcastStream: Codable, Sendable, Equatable, Hashable, Identifiable {
    let streamID: String
    let status: String?

    var id: String { streamID }
}

/// A Snapcast client. Identifiers are preserved even across equal names.
struct SnapcastClient_: Codable, Sendable, Equatable, Hashable, Identifiable {
    let clientID: String
    let name: String
    let connected: Bool
    let groupID: String
    let streamID: String?
    let address: String?

    var id: String { clientID }

    init(
        clientID: String,
        name: String,
        connected: Bool,
        groupID: String,
        streamID: String?,
        address: String? = nil
    ) {
        self.clientID = clientID
        self.name = name
        self.connected = connected
        self.groupID = groupID
        self.streamID = streamID
        self.address = address
    }
}

/// A Snapcast group with membership by stable client IDs.
struct SnapcastGroupInfo: Codable, Sendable, Equatable, Hashable, Identifiable {
    let groupID: String
    let name: String?
    let streamID: String?
    let clientIDs: [String]

    var id: String { groupID }
}

/// Read-only Snapcast topology snapshot from typed `Server.GetStatus`.
struct SnapcastTopologySnapshot: Codable, Sendable, Equatable, Hashable {
    let serviceKind: ServiceKind
    let connectionState: MusicServiceConnectionState
    let serverName: String?
    let serverVersion: String?
    let streams: [SnapcastStream]
    let groups: [SnapcastGroupInfo]
    let clients: [SnapcastClient_]
    let hosts: [String]
    let readAt: Date
}

/// Either service's snapshot, for UI projection.
enum MusicServiceSnapshot: Sendable, Equatable, Hashable {
    case musicAssistant(MusicTopologySnapshot)
    case snapcast(SnapcastTopologySnapshot)

    var connectionState: MusicServiceConnectionState {
        switch self {
        case .musicAssistant(let snapshot):
            return snapshot.connectionState
        case .snapcast(let snapshot):
            return snapshot.connectionState
        }
    }

    var serviceKind: ServiceKind {
        switch self {
        case .musicAssistant: return .musicAssistant
        case .snapcast: return .snapcast
        }
    }

    /// Total member count across both model shapes.
    var memberCount: Int {
        switch self {
        case .musicAssistant(let snapshot):
            return snapshot.players.count
        case .snapcast(let snapshot):
            return snapshot.clients.count
        }
    }

    var groupCount: Int {
        switch self {
        case .musicAssistant(let snapshot):
            return snapshot.groups.count
        case .snapcast(let snapshot):
            return snapshot.groups.count
        }
    }
}

// MARK: - Portal-to-service reconciliation

/// The result of reconciling one Portal against service topology. Ambiguity
/// is preserved rather than silently resolved to the first equal-name member.
struct PortalMemberMapping: Codable, Sendable, Equatable, Hashable {
    let portalID: PortalID
    let matchedPlayerID: String?
    let matchedClientID: String?
    let ambiguousCandidateIDs: [String]
    let offline: Bool

    var isAmbiguous: Bool { ambiguousCandidateIDs.count > 1 }
    var isResolved: Bool { !isAmbiguous && (matchedPlayerID != nil || matchedClientID != nil) }
}

// MARK: - Default-deny mutation surface

/// The named mutation operations that remain unavailable unless every
/// release-gate condition in design §10.2 is present for a specific
/// versioned service contract. There is deliberately no generic mutation
/// path: these are labels used by the capability resolver and UI projections.
enum MusicGroupMutationKind: String, Codable, Sendable, CaseIterable {
    case create
    case rename
    case addMember = "add-member"
    case removeMember = "remove-member"
    case dissolve
}

/// Why a mutation is currently unavailable. All reasons are sanitized.
struct MusicMutationDenial: Codable, Sendable, Equatable, Hashable {
    enum Reason: String, Codable, Sendable {
        case disabledByDefault
        case unknownServiceVersion
        case missingContract
        case missingFixtures
        case missingMutationEvidence
        case gateNotPassed
        case authenticationFailed
        case ambiguousMapping
        case serviceOffline
    }

    let reason: Reason
    let detail: String
}

/// The resolver's decision for one requested mutation. `allowed` requires a
/// complete evidence chain; v1 has no enabled mutations, so the production
/// resolver denies everything with explicit reasons and emits no request.
struct MusicMutationDecision: Sendable, Equatable, Hashable {
    let operation: MusicGroupMutationKind
    let allowed: Bool
    let denial: MusicMutationDenial?
    let gateID: GateID

    static func denied(
        _ operation: MusicGroupMutationKind,
        reason: MusicMutationDenial.Reason,
        detail: String,
        service: String
    ) -> Self {
        Self(
            operation: operation,
            allowed: false,
            denial: MusicMutationDenial(reason: reason, detail: detail),
            gateID: .musicMutation(
                service: service,
                operation: operation.rawValue,
                contract: "unversioned"
            )
        )
    }
}
