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

private struct RawSyncFailure: Error, CustomStringConvertible {
    let description: String
}

private struct RecordedCredentialRead: Equatable {
    let namespace: String
    let identifier: String
}

private final class CredentialStoreFake: CredentialStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [CredentialReference: Data]
    private var reads: [RecordedCredentialRead] = []

    init(seed: [CredentialReference: String]) {
        values = seed.mapValues { Data($0.utf8) }
    }

    var recordedReads: [RecordedCredentialRead] {
        lock.lock()
        defer { lock.unlock() }
        return reads
    }

    func read(_ reference: CredentialReference) async throws -> Data? {
        lock.lock()
        reads.append(
            RecordedCredentialRead(
                namespace: reference.namespace,
                identifier: reference.identifier
            )
        )
        lock.unlock()
        return values[reference]
    }

    func write(_ value: Data, for reference: CredentialReference) async throws {
        throw ManagerError.keychain(.writeFailed)
    }

    func delete(_ reference: CredentialReference) async throws {
        throw ManagerError.keychain(.deleteFailed)
    }
}

private struct RecordedFleetRequest: Equatable {
    let portalID: PortalID
    let route: FleetRoute
    let method: HTTPMethod
    let hasBody: Bool
    let bodyFieldNames: Set<String>
    let expectedAppliedKeys: [String]
}

private final class FleetClientFake: CredentialSyncFleetClient, @unchecked Sendable {
    private enum Outcome {
        case response(FleetHTTPClientResponse)
        case failure(Error)
    }

    private let lock = NSLock()
    private var outcomes: [Outcome]
    private var requests: [RecordedFleetRequest] = []

    init(
        responses: [FleetHTTPResponse] = [],
        failures: [Error] = [],
        repeatLastResponse: Bool = false
    ) {
        outcomes = responses.map { response in
            let payload = try! JSONDecoder().decode(
                JSONValue.self,
                from: response.body ?? Data()
            )
            return Outcome.response(
                FleetHTTPClientResponse(
                    response: response,
                    classification: .success,
                    payload: payload
                )
            )
        }
            + failures.map(Outcome.failure)

        if repeatLastResponse, let last = outcomes.last {
            outcomes.append(last)
        }
    }

    var recordedRequests: [RecordedFleetRequest] {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }

    func execute(_ request: FleetHTTPClientRequest) async throws -> FleetHTTPClientResponse {
        lock.lock()
        requests.append(Self.metadata(for: request))
        let outcome = outcomes.isEmpty
            ? .failure(RawSyncFailure(description: "raw RAW-SOURCE-SECRET-SENTINEL"))
            : outcomes.removeFirst()
        lock.unlock()

        switch outcome {
        case .response(let response):
            return response
        case .failure(let error):
            throw error
        }
    }

    private static func metadata(
        for request: FleetHTTPClientRequest
    ) -> RecordedFleetRequest {
        var fieldNames: Set<String> = []
        if case .json(let body) = request.body,
           let object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] {
            fieldNames = Set(object.keys)
        }

        return RecordedFleetRequest(
            portalID: request.portalID,
            route: request.routePlan.route,
            method: request.routePlan.method,
            hasBody: request.body.data != nil,
            bodyFieldNames: fieldNames,
            expectedAppliedKeys: request.expectedAppliedKeys
        )
    }
}

final class FleetCredentialSyncTransportTests: XCTestCase {
    private let sourcePortal = PortalID(
        rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
    )
    private let firstTargetPortal = PortalID(
        rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
    )
    private let secondTargetPortal = PortalID(
        rawValue: UUID(uuidString: "66666666-7777-8888-9999-000000000000")!
    )

    func testInvalidTargetAndIncompletePairFailClosedBeforeNetworkOrSourceRead() async throws {
        let store = CredentialStoreFake(seed: [:])
        let client = FleetClientFake()
        let transport = makeTransport(
            client: client,
            credentialStore: store,
            targets: [try targetContext(firstTargetPortal, supportsSources: false)]
        )
        let source = try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "screensaver",
            fields: [.immichKey]
        )

        await assertSanitizedRejection {
            try await transport.send(
                source,
                fields: [.immichKey],
                to: firstTargetPortal
            )
        }
        XCTAssertTrue(store.recordedReads.isEmpty)
        XCTAssertTrue(client.recordedRequests.isEmpty)

        let partialPair = try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "screensaver",
            fields: [.smbUser]
        )
        await assertSanitizedRejection {
            try await transport.send(
                partialPair,
                fields: [.smbUser],
                to: firstTargetPortal
            )
        }
        XCTAssertTrue(store.recordedReads.isEmpty)
        XCTAssertEqual(client.recordedRequests.count, 0)
    }

    func testMissingScopedSourceStopsBeforeDestinationReadAndDoesNotExposeValue() async throws {
        let store = CredentialStoreFake(seed: [
            .sourceCredential(portalID: sourcePortal, sourceID: "screensaver", field: .davPass):
                "DAV-PASS-SENTINEL"
        ])
        let client = FleetClientFake()
        let transport = makeTransport(client: client, credentialStore: store)
        let source = try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "screensaver",
            fields: [.davUser, .davPass]
        )

        do {
            try await transport.send(source, fields: [.davUser, .davPass], to: firstTargetPortal)
            XCTFail("A missing source field must stop the operation.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .keychain(.missing))
            XCTAssertFalse(String(describing: error).contains("SENTINEL"))
        }

        XCTAssertEqual(store.recordedReads.count, 1)
        XCTAssertTrue(client.recordedRequests.isEmpty)
    }

    func testSuccessfulShareUsesScopedReferencesAndMinimalTypedPayload() async throws {
        let sourceReference = CredentialReference.sourceCredential(
            portalID: sourcePortal,
            sourceID: "screensaver",
            field: .immichKey
        )
        let store = CredentialStoreFake(seed: [sourceReference: "IMMICH-KEY-SENTINEL"])
        let destinationSources = """
        {
          "ok": true,
          "sources": {
            "source": "default",
            "immichUrl": "http://photos.local",
            "immichKey": "OLD-DESTINATION-VALUE",
            "smbPass": "SHOULD-NOT-BE-COPIED"
          }
        }
        """.data(using: .utf8)!
        let appliedResponse = """
        {"ok":true,"applied":["immich"]}
        """.data(using: .utf8)!
        let client = FleetClientFake(
            responses: [
                FleetHTTPResponse(statusCode: 200, body: destinationSources),
                FleetHTTPResponse(statusCode: 200, body: appliedResponse)
            ]
        )
        let transport = makeTransport(client: client, credentialStore: store)
        let source = try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "screensaver",
            fields: [.immichKey]
        )

        try await transport.send(source, fields: [.immichKey], to: firstTargetPortal)

        XCTAssertEqual(store.recordedReads.count, 1)
        XCTAssertTrue(store.recordedReads[0].identifier.contains(sourcePortal.rawValue.uuidString.lowercased()))
        XCTAssertTrue(store.recordedReads[0].identifier.hasSuffix("/immichKey"))

        let fleetRequests = client.recordedRequests
        XCTAssertEqual(fleetRequests.count, 2)
        XCTAssertEqual(fleetRequests[0].route, .remoteSources)
        XCTAssertEqual(fleetRequests[0].method, .get)
        XCTAssertFalse(fleetRequests[0].hasBody)
        XCTAssertEqual(fleetRequests[1].route, .remoteSources)
        XCTAssertEqual(fleetRequests[1].method, .post)
        XCTAssertEqual(fleetRequests[1].bodyFieldNames, ["immichUrl", "immichKey"])
        XCTAssertEqual(fleetRequests[1].expectedAppliedKeys, ["immich"])
    }

    func testCoordinatorReportsEachTargetAndNeverExposesRawInjectedFailure() async throws {
        let store = CredentialStoreFake(seed: [
            .sourceCredential(portalID: sourcePortal, sourceID: "screensaver", field: .immichKey):
                "IMMICH-KEY-SENTINEL"
        ])
        let getResponse = FleetHTTPResponse(
            statusCode: 200,
            body: Data(#"{"ok":true,"sources":{"immichUrl":"http://photos.local"}}"#.utf8)
        )
        let postResponse = FleetHTTPResponse(
            statusCode: 200,
            body: Data(#"{"ok":true,"applied":["immich"]}"#.utf8)
        )
        let client = FleetClientFake(
            responses: [getResponse, postResponse],
            failures: [RawSyncFailure(description: "RAW-SOURCE-SECRET-SENTINEL")],
        )
        let coordinator = FleetCredentialSyncCoordinator(
            transport: makeTransport(client: client, credentialStore: store)
        )
        let source = try SharedCredentialSource(
            portalID: sourcePortal,
            sourceID: "screensaver",
            fields: [.immichKey]
        )
        let report = await coordinator.sync(
            source,
            targets: [target(firstTargetPortal), target(secondTargetPortal)]
        )

        XCTAssertEqual(
            report.results.map(\.outcome),
            [.succeeded, .rejected],
            "requests=\(client.recordedRequests) report=\(report)"
        )
        XCTAssertFalse(report.isFullySuccessful)
        XCTAssertFalse(String(describing: report).contains("SENTINEL"))
    }

    // MARK: - Fixtures

    private func endpoint(_ address: String) throws -> LANEndpoint {
        try LANPolicy.validate(
            hostOrAddress: address,
            port: 8723,
            source: .manual
        )
    }

    private func targetContext(
        _ portalID: PortalID,
        supportsSources: Bool = true
    ) throws -> CredentialSyncTargetContext {
        let bearer = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        return try CredentialSyncTargetContext(
            portalID: portalID,
            admissionRequest: ConnectionAdmissionRequest(
                endpoint: endpoint(portalID == self.firstTargetPortal ? "192.168.1.20" : "192.168.1.21"),
                serviceKind: .portal,
                protocolName: "http"
            ),
            authorizedReferences: [bearer],
            supportsSources: supportsSources
        )
    }

    private func target(_ portalID: PortalID) -> CredentialSyncTarget {
        let bearer = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        return CredentialSyncTarget(
            portalID: portalID,
            connectionState: .online(lastRefresh: Date(timeIntervalSince1970: 100), latencyMs: 4),
            credentialReferences: [bearer]
        )
    }

    private func makeTransport(
        client: FleetClientFake,
        credentialStore: CredentialStoreFake,
        targets: [CredentialSyncTargetContext]? = nil
    ) -> FleetCredentialSyncTransport {
        FleetCredentialSyncTransport(
            fleetClient: client,
            credentialStore: credentialStore,
            targets: targets ?? [
                try! targetContext(firstTargetPortal),
                try! targetContext(secondTargetPortal)
            ]
        )
    }

    private func assertSanitizedRejection(
        _ operation: () async throws -> Void
    ) async {
        do {
            try await operation()
            XCTFail("The invalid request must be rejected.")
        } catch {
            XCTAssertFalse(String(describing: error).contains("RAW"))
            XCTAssertFalse(String(describing: error).contains("SENTINEL"))
        }
    }
}
