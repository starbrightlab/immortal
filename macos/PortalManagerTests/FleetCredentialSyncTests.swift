/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#if !CREDENTIAL_SYNC_TYPECHECK_STANDALONE
@testable import PortalManager
#endif

import Foundation
import XCTest

private final class RecordingCredentialTransport: CredentialSyncTransport, @unchecked Sendable {
    enum Failure: Error {
        case raw
    }

    private let failurePortalID: PortalID?
    private let lock = NSLock()
    private var _requests: [(source: SharedCredentialSource, portalID: PortalID)] = []

    var requests: [(source: SharedCredentialSource, portalID: PortalID)] {
        lock.lock()
        defer { lock.unlock() }
        return _requests
    }

    init(failurePortalID: PortalID? = nil) {
        self.failurePortalID = failurePortalID
    }

    func send(
        _ source: SharedCredentialSource,
        fields: Set<ShareableCredentialField>,
        to portalID: PortalID
    ) async throws {
        lock.lock()
        _requests.append((source, portalID))
        lock.unlock()
        if portalID == failurePortalID {
            throw Failure.raw
        }
    }
}

final class FleetCredentialSyncTests: XCTestCase {
    private let sourcePortal = PortalID()
    private let firstDestination = PortalID()
    private let secondDestination = PortalID()

    func testInvalidAllowlistAndSourceAreRejectedBeforeTransport() async throws {
        XCTAssertThrowsError(try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "  ",
            fields: [.immichKey]
        ))
        XCTAssertThrowsError(try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "immich",
            fields: []
        ))
    }

    func testIneligibleTargetsBlockDispatchBeforeAnyRequest() async throws {
        let eligible = CredentialSyncTarget(
            portalID: firstDestination,
            connectionState: .online(lastRefresh: Date(), latencyMs: 12),
            credentialReferences: [.portalCredential(portalID: firstDestination, kind: .verifiedBearer)]
        )
        let offline = CredentialSyncTarget(
            portalID: secondDestination,
            connectionState: .offline(lastContact: nil, reason: "timeout"),
            credentialReferences: [.portalCredential(portalID: secondDestination, kind: .verifiedBearer)]
        )
        var transport = RecordingCredentialTransport()
        let coordinator = FleetCredentialSyncCoordinator(transport: transport)
        let source = try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "immich",
            fields: [.immichKey]
        )

        let report = await coordinator.sync(source, targets: [eligible, offline])

        XCTAssertTrue(transport.requests.isEmpty)
        XCTAssertEqual(report.results.map(\.outcome), [.rejected, .rejected])
        XCTAssertFalse(report.isFullySuccessful)
    }

    func testSuccessfulFanOutReportsEachTargetAndNeverLeaksRawErrors() async throws {
        let source = try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "immich",
            fields: [.davUser, .davPass]
        )
        func target(_ id: PortalID) -> CredentialSyncTarget {
            CredentialSyncTarget(
                portalID: id,
                connectionState: .bearerAuthenticated(
                    identity: PortalIdentity(
                        portalID: id,
                        name: "Portal",
                        model: "Portal"
                    ),
                    verifiedAt: Date()
                ),
                credentialReferences: [.portalCredential(portalID: id, kind: .verifiedBearer)]
            )
        }
        var transport = RecordingCredentialTransport(failurePortalID: secondDestination)
        let coordinator = FleetCredentialSyncCoordinator(transport: transport)

        let report = await coordinator.sync(source, targets: [target(firstDestination), target(secondDestination)])

        XCTAssertEqual(transport.requests.count, 2)
        XCTAssertEqual(Set(transport.requests.map(\.portalID)), [firstDestination, secondDestination])
        XCTAssertTrue(transport.requests.allSatisfy { $0.source.sourceID == "immich" })
        XCTAssertEqual(report.results.first?.outcome, .succeeded)
        XCTAssertEqual(report.results.last?.outcome, .rejected)
        XCTAssertEqual(report.successfulCount, 1)
        XCTAssertFalse(report.isFullySuccessful)
    }
}
