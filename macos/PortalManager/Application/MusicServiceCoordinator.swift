/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Read-only Music Assistant/Snapcast topology coordination (design §10.1).
///
/// The coordinator performs LAN admission through the shared `ConnectionAdmission`
/// boundary before any socket work, separates authentication failure from
/// network failure, preserves stable service identifiers, and never exposes a
/// group-mutation method.
actor MusicServiceCoordinator {
    private let admission: ConnectionAdmission
    private let clock: any ManagerClock

    init(admission: ConnectionAdmission, clock: any ManagerClock) {
        self.admission = admission
        self.clock = clock
    }

    /// Builds an admitted connection request for one service after parsing.
    /// DNS resolution and LAN validation happen inside `admission.admit`.
    func admittedRequest(
        _ configuration: MusicServiceConfiguration
    ) async throws -> AdmittedConnection {
        let transport: ServiceEndpoint.Transport = configuration.serviceKind == .musicAssistant
            ? .webSocket
            : .tcp
        let request = try ConnectionAdmissionRequest(
            rawEndpoint: configuration.hostOrAddress,
            serviceKind: configuration.serviceKind,
            protocolName: transport == .webSocket ? "ws" : "tcp",
            defaultPort: configuration.port,
            source: .manual
        )
        return try await admission.admit(request)
    }

    // MARK: Reconciliation

    /// Reconciles one Portal against Music Assistant players by stable ID first
    /// and display name second. Equal names produce an explicitly ambiguous
    /// mapping instead of a silent first-match selection.
    nonisolated static func reconcilePortal(
        _ portalID: PortalID,
        displayName: String?,
        against snapshot: MusicServiceSnapshot
    ) -> PortalMemberMapping {
        switch snapshot {
        case .musicAssistant(let topology):
            var byID: [String] = []
            if let displayName {
                byID = topology.players.filter { $0.name == displayName }.map(\.playerID)
            }
            let offlineCandidates = topology.players.filter { !$0.online }.map(\.playerID)
            if byID.count == 1 {
                return PortalMemberMapping(
                    portalID: portalID,
                    matchedPlayerID: byID[0],
                    matchedClientID: nil,
                    ambiguousCandidateIDs: [],
                    offline: false
                )
            }
            if byID.count > 1 {
                return PortalMemberMapping(
                    portalID: portalID,
                    matchedPlayerID: nil,
                    matchedClientID: nil,
                    ambiguousCandidateIDs: byID.sorted(),
                    offline: false
                )
            }
            return PortalMemberMapping(
                portalID: portalID,
                matchedPlayerID: nil,
                matchedClientID: nil,
                ambiguousCandidateIDs: [],
                offline: !offlineCandidates.isEmpty && topology.players.count == offlineCandidates.count
            )

        case .snapcast(let topology):
            var byID: [String] = []
            if let displayName {
                byID = topology.clients.filter { $0.name == displayName }.map(\.clientID)
            }
            let disconnected = topology.clients.filter { !$0.connected }.map(\.clientID)
            if byID.count == 1 {
                return PortalMemberMapping(
                    portalID: portalID,
                    matchedPlayerID: nil,
                    matchedClientID: byID[0],
                    ambiguousCandidateIDs: [],
                    offline: false
                )
            }
            if byID.count > 1 {
                return PortalMemberMapping(
                    portalID: portalID,
                    matchedPlayerID: nil,
                    matchedClientID: nil,
                    ambiguousCandidateIDs: byID.sorted(),
                    offline: false
                )
            }
            return PortalMemberMapping(
                portalID: portalID,
                matchedPlayerID: nil,
                matchedClientID: nil,
                ambiguousCandidateIDs: [],
                offline: !disconnected.isEmpty && topology.clients.count == disconnected.count
            )
        }
    }
}

// MARK: - Default-deny capability resolution

/// Resolves UI/operation projections for group mutations (design §10.2).
///
/// v1 ships zero enabled mutations. Every requested operation resolves to an
/// explicit denial naming the missing condition; no speculative request path
/// exists because this resolver cannot construct one.
struct MusicCapabilityResolver: Sendable {
    struct ServiceEvidence: Sendable, Equatable {
        var service: String
        var deployedVersion: String?
        var hasTypedContract: Bool
        var contractVersion: String?
        var hasSanitizedFixtures: Bool
        var hasMutationEvidence: Bool
        var hasReadBackVerification: Bool
        var gateStatus: GateStatus

        static func readOnly(_ service: String, version: String?) -> Self {
            Self(
                service: service,
                deployedVersion: version,
                hasTypedContract: false,
                contractVersion: nil,
                hasSanitizedFixtures: false,
                hasMutationEvidence: false,
                hasReadBackVerification: false,
                gateStatus: .missing
            )
        }
    }

    init() {}

    func decision(
        for operation: MusicGroupMutationKind,
        evidence: ServiceEvidence
    ) -> MusicMutationDecision {
        // Ordered, explicit denial reasons. The first unsatisfied condition
        // wins so the UI can explain exactly what is missing.
        if evidence.gateStatus == .failed || evidence.gateStatus == .withheld {
            return .denied(
                operation,
                reason: .gateNotPassed,
                detail: "The release gate for this mutation did not pass.",
                service: evidence.service
            )
        }
        guard evidence.hasTypedContract else {
            return .denied(
                operation,
                reason: .missingContract,
                detail: "No typed Versioned Service Contract exists for this service version.",
                service: evidence.service
            )
        }
        guard let contractVersion = evidence.contractVersion,
              let deployed = evidence.deployedVersion,
              contractVersion == deployed else {
            return .denied(
                operation,
                reason: .unknownServiceVersion,
                detail: "The deployed service version does not match a documented contract.",
                service: evidence.service
            )
        }
        guard evidence.hasSanitizedFixtures else {
            return .denied(
                operation,
                reason: .missingFixtures,
                detail: "Sanitized request/response fixtures are required before enabling.",
                service: evidence.service
            )
        }
        guard evidence.hasMutationEvidence else {
            return .denied(
                operation,
                reason: .missingMutationEvidence,
                detail: "Capability Mutation Evidence is required before enabling.",
                service: evidence.service
            )
        }
        guard evidence.hasReadBackVerification else {
            return .denied(
                operation,
                reason: .missingMutationEvidence,
                detail: "Service-specific read-back verification is required before enabling.",
                service: evidence.service
            )
        }
        guard evidence.gateStatus == .passed else {
            return .denied(
                operation,
                reason: .gateNotPassed,
                detail: "The scoped Music Mutation Release Gate has not passed yet.",
                service: evidence.service
            )
        }

        // v1 contains no enabled mutation evidence bundle; reaching this point
        // still requires a named gate record, which the product does not ship.
        // Keeping the final branch unreachable-by-construction documents the
        // default-deny invariant without adding a generic mutation API.
        return .denied(
            operation,
            reason: .disabledByDefault,
            detail: "Group mutations are unavailable in version one.",
            service: evidence.service
        )
    }

    /// Projections for every named mutation, used by the Music detail view.
    func projections(
        for service: String,
        evidence: ServiceEvidence
    ) -> [MusicGroupMutationKind: MusicMutationDecision] {
        Dictionary(
            uniqueKeysWithValues: MusicGroupMutationKind.allCases.map { kind in
                (kind, decision(for: kind, evidence: evidence))
            }
        )
    }
}
