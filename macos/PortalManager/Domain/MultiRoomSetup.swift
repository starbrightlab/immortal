/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

struct MultiRoomPortalCandidate: Equatable, Sendable {
    let portalID: PortalID
    let name: String
    let address: String?
}

struct MultiRoomClientCandidate: Equatable, Sendable {
    let clientID: String
    let name: String
    let address: String?
}

enum MultiRoomSetupPhase: Equatable, Sendable {
    case idle
    case checking
    case ready(MultiRoomSetupPlan)
    case applying(finished: Int, total: Int)
    case completed(attempted: Int, succeeded: Int)
    case failed(String)
}

/// A deterministic matching proposal. Equal names or addresses stay ambiguous
/// instead of silently joining the wrong room.
struct MultiRoomSetupPlan: Equatable, Sendable {
    let matches: [PortalID: String]
    let ambiguousPortalIDs: Set<PortalID>
    let unmatchedPortalIDs: Set<PortalID>
    let unmatchedClientIDs: Set<String>

    init(
        portals: [MultiRoomPortalCandidate],
        clients: [MultiRoomClientCandidate]
    ) {
        var matches: [PortalID: String] = [:]
        var ambiguous: Set<PortalID> = []
        var usedClients = Set<String>()

        func normalized(_ value: String?) -> String? {
            guard let value else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            return trimmed.isEmpty ? nil : trimmed
        }

        for portal in portals {
            let addressMatches = clients.filter {
                normalized($0.address) != nil && normalized($0.address) == normalized(portal.address)
            }
            let nameMatches = clients.filter {
                normalized($0.name) != nil && normalized($0.name) == normalized(portal.name)
            }
            let candidates = addressMatches.isEmpty ? nameMatches : addressMatches

            if candidates.count == 1, let client = candidates.first,
               !usedClients.contains(client.clientID) {
                matches[portal.portalID] = client.clientID
                usedClients.insert(client.clientID)
            } else if candidates.count > 1 {
                ambiguous.insert(portal.portalID)
            }
        }

        self.matches = matches
        self.ambiguousPortalIDs = ambiguous
        self.unmatchedPortalIDs = Set(portals.map(\.portalID))
            .subtracting(matches.keys)
            .subtracting(ambiguous)
        self.unmatchedClientIDs = Set(clients.map(\.clientID)).subtracting(usedClients)
    }

    var hasActionableMatches: Bool { !matches.isEmpty }
}

extension SettingsPolicyLookup {
    /// Exact approvals used only by the explicit multi-room setup flow. The
    /// general Settings editor remains default-deny.
    static let guidedMultiRoomSetup = SettingsPolicyLookup(entries: [
        SettingsPolicyEntry(
            domainID: "immortal",
            controlKey: "multiRoomEnabled",
            classification: .approvedEditable(
                route: .remoteSettings,
                bulk: .allowed,
                evidence: "multi-room-setup-enable"
            ),
            fieldPresence: .required
        ),
        SettingsPolicyEntry(
            domainID: "immortal",
            controlKey: "snapcastHost",
            classification: .approvedEditable(
                route: .remoteSettings,
                bulk: .allowed,
                evidence: "multi-room-setup-server"
            ),
            fieldPresence: .required
        ),
    ])
}
