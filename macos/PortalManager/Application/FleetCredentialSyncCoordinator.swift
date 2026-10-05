/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Transport reads only through scoped references. Implementations must keep
/// credential bytes adapter-private and reduce failures to closed outcomes.
protocol CredentialSyncTransport: Sendable {
    func send(
        _ source: SharedCredentialSource,
        fields: Set<ShareableCredentialField>,
        to portalID: PortalID
    ) async throws
}

actor FleetCredentialSyncCoordinator {
    private let transport: any CredentialSyncTransport

    init(transport: any CredentialSyncTransport) {
        self.transport = transport
    }

    /// Validates every target before dispatching anything, then reports each
    /// destination independently. Raw transport errors never cross this actor.
    func sync(
        _ source: SharedCredentialSource,
        targets: [CredentialSyncTarget]
    ) async -> FleetCredentialSyncReport {
        guard !targets.isEmpty else {
            return FleetCredentialSyncReport(results: [])
        }

        if targets.contains(where: { !$0.isEligible }) {
            return FleetCredentialSyncReport(
                results: targets.map { result($0.portalID, .rejected) }
            )
        }

        var resultsByPortal: [PortalID: CredentialSyncResult] = [:]
        for target in targets {
            do {
                try await transport.send(source, fields: source.fields, to: target.portalID)
                resultsByPortal[target.portalID] = result(target.portalID, .succeeded)
            } catch is CancellationError {
                resultsByPortal[target.portalID] = result(target.portalID, .cancelled)
            } catch {
                resultsByPortal[target.portalID] = result(target.portalID, .rejected)
            }
        }

        return FleetCredentialSyncReport(
            results: targets.map { resultsByPortal[$0.portalID] ?? result($0.portalID, .rejected) }
        )
    }

    private func result(
        _ portalID: PortalID,
        _ outcome: CredentialSyncOutcome
    ) -> CredentialSyncResult {
        CredentialSyncResult(portalID: portalID, outcome: outcome)
    }
}
