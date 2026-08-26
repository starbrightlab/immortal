/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import XCTest
@testable import PortalManager

final class NativeRoomCoordinatorTests: XCTestCase {
    private let sourceID = PortalID(
        rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
    )
    private let receiverID = PortalID(
        rawValue: UUID(uuidString: "66666666-7777-8888-9999-000000000000")!
    )

    func testPolicyApprovesOnlyTheTwoImmortalRoomLinkControls() throws {
        let controls = [
            SettingsControlSchema(
                domainID: "immortal",
                key: NativeRoomPlan.modeKey,
                type: .enumValue,
                options: [
                    EnumOption(value: "off", label: "Off"),
                    EnumOption(value: "broadcast", label: "Broadcast"),
                    EnumOption(value: "receive", label: "Receive"),
                ]
            ),
            SettingsControlSchema(
                domainID: "immortal",
                key: NativeRoomPlan.peerHostKey,
                type: .string
            ),
            SettingsControlSchema(
                domainID: "immortal",
                key: "snapcastHost",
                type: .string
            ),
            SettingsControlSchema(
                domainID: "screensaver",
                key: NativeRoomPlan.modeKey,
                type: .enumValue,
                options: [EnumOption(value: "off", label: "Off")]
            ),
        ]

        XCTAssertTrue(SettingsPolicyLookup.nativeRoomLink.canEdit(controls[0]))
        XCTAssertTrue(SettingsPolicyLookup.nativeRoomLink.canEdit(controls[1]))
        XCTAssertFalse(SettingsPolicyLookup.nativeRoomLink.canEdit(controls[2]))
        XCTAssertFalse(SettingsPolicyLookup.nativeRoomLink.canEdit(controls[3]))

        let result = SettingsCoordinator.validateDraft(
            SettingsDomainDraft(
                domainID: "immortal",
                values: [
                    NativeRoomPlan.modeKey: .string("receive"),
                    NativeRoomPlan.peerHostKey: .string("192.168.1.20"),
                    "snapcastHost": .string("192.168.1.99"),
                ]
            ),
            against: SettingsRegistrySchema(domains: [
                SettingsDomainSchema(id: "immortal", controls: controls)
            ]),
            policy: .nativeRoomLink
        )

        XCTAssertEqual(result.acceptedKeys, [
            NativeRoomPlan.modeKey,
            NativeRoomPlan.peerHostKey,
        ])
        XCTAssertEqual(result.rejectedKeys, ["snapcastHost"])
    }

    func testPlanValidationRejectsIncompleteOrOverlappingSelections() async {
        let source = participant(sourceID, address: "192.168.1.20")
        let receiver = participant(receiverID, address: "192.168.1.21")
        let cases: [(NativeRoomPlanError, NativeRoomParticipant, [NativeRoomParticipant])] = [
            (.missingReceivers, source, []),
            (.receiverIsSource(sourceID), source, [
                participant(sourceID, address: "192.168.1.22"),
                receiver,
            ]),
            (.duplicateReceiver(receiverID), source, [receiver, receiver]),
            (.invalidReceiverEndpoint, source, [
                participant(receiverID, address: ""),
            ]),
            (.publicAddressRejected, participant(sourceID, address: "8.8.8.8"), [receiver]),
            (.publicAddressRejected, source, [
                participant(receiverID, address: "203.0.113.10"),
            ]),
        ]

        for (expectedError, candidateSource, candidateReceivers) in cases {
            do {
                _ = try NativeRoomPlan(source: candidateSource, receivers: candidateReceivers)
                XCTFail("Expected \(expectedError)")
            } catch let error as NativeRoomPlanError {
                XCTAssertEqual(error, expectedError)
            } catch {
                XCTFail("Unexpected error \(error)")
            }
        }

        let coordinator = NativeRoomCoordinator(
            dispatcher: RecordingNativeRoomDispatcher(outcomes: [:])
        )
        do {
            _ = try await coordinator.prepare(sources: [], receivers: [receiver])
            XCTFail("Expected a missing source rejection.")
        } catch let error as NativeRoomPlanError {
            XCTAssertEqual(error, .missingSource)
        } catch {
            XCTFail("Unexpected error \(error)")
        }
        do {
            _ = try await coordinator.prepare(
                sources: [source, participant(sourceID, address: "192.168.1.23")],
                receivers: [receiver]
            )
            XCTFail("Expected duplicate source rejection.")
        } catch let error as NativeRoomPlanError {
            XCTAssertEqual(error, .duplicateSource)
        } catch {
            XCTFail("Unexpected error \(error)")
        }

        XCTAssertThrowsError(
            try NativeRoomPlan(
                source: participant(sourceID, address: "source.local"),
                receivers: [receiver]
            )
        ) { error in
            XCTAssertEqual(error as? NativeRoomPlanError, .invalidSourceEndpoint)
        }
    }

    func testTypedPlansUseExactImmortalDomainPayloads() throws {
        let plan = try NativeRoomPlan(
            source: participant(sourceID, address: "192.168.1.20"),
            receivers: [participant(receiverID, address: "192.168.1.21")]
        )

        XCTAssertEqual(plan.sourceSettings(), SettingsDomainDraft(
            domainID: "immortal",
            values: [
                "intercomMode": .string("broadcast"),
            ]
        ))
        XCTAssertEqual(plan.receiverSettings(), SettingsDomainDraft(
            domainID: "immortal",
            values: [
                "intercomMode": .string("receive"),
                "intercomPeerHost": .string("192.168.1.20"),
            ]
        ))
        XCTAssertEqual(NativeRoomPlan.stopSettings(), SettingsDomainDraft(
            domainID: "immortal",
            values: [
                "intercomMode": .string("off"),
            ]
        ))
    }

    func testApplyDispatchesIndependentlyAndReportsPerTargetResults() async throws {
        let dispatcher = RecordingNativeRoomDispatcher(outcomes: [
            sourceID: ManagerError.http(status: 503, code: nil, detail: "raw upstream detail"),
            receiverID: ManagerError.cancelled,
        ])
        let coordinator = NativeRoomCoordinator(dispatcher: dispatcher)
        let plan = try NativeRoomPlan(
            source: participant(sourceID, address: "192.168.1.20"),
            receivers: [participant(receiverID, address: "192.168.1.21")]
        )

        let report = await coordinator.apply(plan: plan)

        XCTAssertFalse(report.isFullySuccessful)
        XCTAssertNotNil(report.receiverResults.first?.error)
        XCTAssertEqual(report.sourceResult.error?.sanitizedMessage, "The Fleet Agent returned HTTP status 503.")
        XCTAssertEqual(report.receiverResults.first?.error, ManagerError.cancelled)
        XCTAssertFalse(report.receiverResults.first?.isSuccess ?? true)
        XCTAssertEqual(dispatcher.requests.count, 2)
        XCTAssertTrue(dispatcher.requests.contains { request in
            request.portalID == sourceID && request.draft == plan.sourceSettings()
        })
        XCTAssertTrue(dispatcher.requests.contains { request in
            request.portalID == receiverID && request.draft == plan.receiverSettings()
        })
    }

    func testApplyConfirmsPortalRuntimeAndRejectsReportedStartupFailure() async throws {
        let dispatcher = RecordingNativeRoomDispatcher(outcomes: [:])
        let coordinator = NativeRoomCoordinator(
            dispatcher: dispatcher,
            infoReader: { participant in
                if participant.portalID == self.sourceID {
                    return [
                        "mode": .string("broadcast"),
                        "state": .string("error"),
                    ]
                }
                return [
                    "mode": .string("receive"),
                    "state": .string("receiving"),
                ]
            }
        )
        let plan = try NativeRoomPlan(
            source: participant(sourceID, address: "192.168.1.20"),
            receivers: [participant(receiverID, address: "192.168.1.21")]
        )

        let report = await coordinator.apply(plan: plan)

        XCTAssertFalse(report.isFullySuccessful)
        XCTAssertNotNil(report.sourceResult.error)
        XCTAssertNil(report.receiverResults.first?.error)
    }

    func testStopAllSendsStopPayloadToEveryParticipantIndependently() async throws {
        let thirdID = PortalID(
            rawValue: UUID(uuidString: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")!
        )
        let participants = [
            participant(sourceID, address: "192.168.1.20"),
            participant(receiverID, address: "192.168.1.21"),
            participant(thirdID, address: "192.168.1.22"),
        ]
        let dispatcher = RecordingNativeRoomDispatcher(outcomes: [
            receiverID: ManagerError.transport(.connectionFailed),
        ])
        let coordinator = NativeRoomCoordinator(dispatcher: dispatcher)

        let results = await coordinator.stopAll(participants: participants)

        XCTAssertEqual(results.count, 3)
        XCTAssertEqual(results.map(\.portalID).sorted { $0.rawValue.uuidString < $1.rawValue.uuidString }, [
            sourceID,
            receiverID,
            thirdID,
        ])
        XCTAssertEqual(results.filter(\.isSuccess).count, 2)
        XCTAssertEqual(Set(dispatcher.requests.map(\.draft)), [NativeRoomPlan.stopSettings()])
        XCTAssertEqual(Set(dispatcher.requests.map(\.portalID)), Set(participants.map(\.portalID)))
        XCTAssertEqual(Set(dispatcher.requests.map(\.route)), [.remoteSettings])
    }

    func testLiveStopUsesExactRemoteSettingsRouteAndBody() async throws {
        let schema = nativeRoomSchema()
        let transport = RecordingFleetTransport(responses: [
            try getResponse(schema),
            try postResponse(applied: [
                NativeRoomPlan.modeKey,
            ]),
            try getResponse(schema),
        ])
        let context = try await makeLiveContext(transport: transport)
        let coordinator = NativeRoomCoordinator(settingsCoordinator: context.coordinator)
        let participant = self.participant(
            sourceID,
            address: "192.168.1.30"
        )

        let results = await coordinator.stopAll(participants: [participant])

        XCTAssertEqual(results.count, 1)
        XCTAssertNil(results.first?.error)
        XCTAssertEqual(transport.requests.count, 3)
        XCTAssertEqual(transport.requests.map(\.method), [.get, .post, .get])
        XCTAssertEqual(transport.requests.map(\.route), Array(repeating: .remoteSettings, count: 3))

        let body = try XCTUnwrap(transport.requests[1].body)
        let payload = try JSONDecoder().decode(JSONValue.self, from: body)
        XCTAssertEqual(payload, JSONValue.object([
            "domain": .string("immortal"),
            "values": .object([
                "intercomMode": .string("off"),
            ]),
        ]))
    }

    private func participant(
        _ portalID: PortalID,
        address: String
    ) -> NativeRoomParticipant {
        NativeRoomParticipant(
            portalID: portalID,
            name: "Portal \(portalID.rawValue.uuidString.prefix(2))",
            endpointHostOrAddress: address,
            addressFamily: address.hasSuffix(".local") ? .hostname : .ipv4
        )
    }

    private func nativeRoomSchema() -> SettingsRegistrySchema {
        SettingsRegistrySchema(domains: [
            SettingsDomainSchema(id: "immortal", controls: [
                SettingsControlSchema(
                    domainID: "immortal",
                    key: NativeRoomPlan.modeKey,
                    type: .enumValue,
                    options: [
                        EnumOption(value: "off", label: "Off"),
                        EnumOption(value: "broadcast", label: "Broadcast"),
                        EnumOption(value: "receive", label: "Receive"),
                    ]
                ),
                SettingsControlSchema(
                    domainID: "immortal",
                    key: NativeRoomPlan.peerHostKey,
                    type: .string
                ),
            ])
        ])
    }

    private func getResponse(_ schema: SettingsRegistrySchema) throws -> FleetHTTPResponse {
        let encodedSchema = try JSONEncoder().encode(schema)
        let inner = try JSONDecoder().decode(JSONValue.self, from: encodedSchema)
        let payload = JSONValue.object([
            "ok": .bool(true),
            "settings": inner,
        ])
        return FleetHTTPResponse(
            statusCode: 200,
            body: try JSONEncoder().encode(payload)
        )
    }

    private func postResponse(applied keys: [String]) throws -> FleetHTTPResponse {
        let payload = JSONValue.object([
            "ok": .bool(true),
            "applied": .array(keys.map { .string($0) }),
        ])
        return FleetHTTPResponse(
            statusCode: 200,
            body: try JSONEncoder().encode(payload)
        )
    }

    private func makeLiveContext(
        transport: RecordingFleetTransport
    ) async throws -> LiveTestContext {
        let credentialReference = CredentialReference.portalCredential(
            portalID: sourceID,
            kind: .verifiedBearer
        )
        let endpoint = try LANPolicy.validate(
            hostOrAddress: "192.168.1.30",
            port: 8723,
            source: .manual
        )
        _ = ConnectionAdmissionRequest(
            endpoint: endpoint,
            serviceKind: .portal,
            protocolName: "http"
        )
        let entry = PortalRegistryEntry(
            id: sourceID,
            connectionState: .online(
                lastRefresh: Date(timeIntervalSince1970: 1),
                latencyMs: 1
            ),
            endpoint: endpoint,
            credentialReferences: [credentialReference]
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [credentialReference: Data("bearer".utf8)]
        )
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [entry])
        )
        let trustStore = FakeTrustWarningStore()
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: "192.168.1.30",
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 1)
        )
        let fleetClient = FleetHTTPClient(
            transport: transport,
            admission: ConnectionAdmission(
                dnsResolver: FakeDNSResolver(),
                trustWarningStore: trustStore
            ),
            credentialStore: credentialStore
        )
        let registryCoordinator = PortalRegistryCoordinator(
            registryStore: registryStore,
            credentialStore: credentialStore
        )
        let sessionCoordinator = PortalSessionCoordinator(
            fleetClient: fleetClient,
            registryCoordinator: registryCoordinator,
            credentialStore: credentialStore
        )
        let coordinator = SettingsCoordinator(
            sessionCoordinator: sessionCoordinator,
            policy: .nativeRoomLink
        )
        return LiveTestContext(coordinator: coordinator)
    }
}

private struct RecordingNativeRoomRequest: Sendable {
    let portalID: PortalID
    let route: FleetRoute
    let draft: SettingsDomainDraft
}

private final class RecordingNativeRoomDispatcher: NativeRoomSettingsDispatching, @unchecked Sendable {
    private let lock = NSLock()
    private var outcomes: [PortalID: Error]
    private var recordedRequests: [RecordingNativeRoomRequest] = []

    init(outcomes: [PortalID: Error]) {
        self.outcomes = outcomes
    }

    var requests: [RecordingNativeRoomRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recordedRequests
    }

    func apply(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        draft: SettingsDomainDraft,
        schema: SettingsRegistrySchema?
    ) async throws -> SettingsApplyResult {
        lock.lock()
        recordedRequests.append(
            RecordingNativeRoomRequest(
                portalID: portalID,
                route: admissionRequest.endpoint.port == 8723 ? .remoteSettings : .info,
                draft: draft
            )
        )
        let failure = outcomes[portalID]
        lock.unlock()

        if let failure { throw failure }
        return SettingsApplyResult(portalID: portalID, domains: [], confirmedSchema: schema)
    }
}

private struct RecordedFleetRequest: Sendable {
    let method: HTTPMethod
    let route: FleetRoute
    let body: Data?
}

private final class RecordingFleetTransport: FleetHTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var responses: [FleetHTTPResponse]
    private var recordedRequests: [RecordedFleetRequest] = []

    init(responses: [FleetHTTPResponse]) {
        self.responses = responses
    }

    var requests: [RecordedFleetRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recordedRequests
    }

    func send(_ request: any FleetHTTPRequest) async throws -> FleetHTTPResponse {
        guard let request = request as? HTTPTransportRequest else {
            throw ManagerError.validation(
                field: "Fleet request",
                reason: "The test transport received an untyped request."
            )
        }
        lock.lock()
        recordedRequests.append(
            RecordedFleetRequest(
                method: request.routePlan.method,
                route: request.routePlan.route,
                body: request.body.map { Data(bytes: $0) }
            )
        )
        guard !responses.isEmpty else {
            lock.unlock()
            throw ManagerError.transport(.connectionFailed)
        }
        let response = responses.removeFirst()
        lock.unlock()
        return response
    }
}

private struct LiveTestContext {
    let coordinator: SettingsCoordinator
}
