/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import XCTest
@testable import PortalManager

final class SettingsCoordinatorTests: XCTestCase {
    func testInvalidDraftFieldsAreRejectedWithoutRequests() async throws {
        let approvedInterval = approvedPolicy(
            controls: [("screensaver", "intervalSec")]
        )
        let approvedEnum = approvedPolicy(
            controls: [("screensaver", "fit")]
        )
        let approvedShuffle = approvedPolicy(
            controls: [("screensaver", "shuffle")]
        )
        let approvedSecret = approvedPolicy(
            controls: [("mqtt", "password")]
        )

        let cases: [(String, SettingsRegistrySchema, SettingsPolicyLookup, SettingsDomainDraft, SettingsFieldOutcome)] = [
            (
                "wrong type",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "intervalSec",
                        type: .int,
                        min: 5,
                        max: 600,
                        step: 5
                    )]
                ),
                approvedInterval,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["intervalSec": .string("45")]
                ),
                .rejected
            ),
            (
                "range",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "intervalSec",
                        type: .int,
                        min: 5,
                        max: 600,
                        step: 5
                    )]
                ),
                approvedInterval,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["intervalSec": .number(0)]
                ),
                .rejected
            ),
            (
                "step",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "intervalSec",
                        type: .int,
                        min: 5,
                        max: 600,
                        step: 5
                    )]
                ),
                approvedInterval,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["intervalSec": .number(46)]
                ),
                .rejected
            ),
            (
                "malformed range",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "intervalSec",
                        type: .int,
                        min: 600,
                        max: 5,
                        step: 5
                    )]
                ),
                approvedInterval,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["intervalSec": .number(45)]
                ),
                .rejected
            ),
            (
                "malformed step",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "intervalSec",
                        type: .int,
                        min: 5,
                        max: 600,
                        step: 0
                    )]
                ),
                approvedInterval,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["intervalSec": .number(45)]
                ),
                .rejected
            ),
            (
                "enum membership",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "fit",
                        type: .enumValue,
                        options: [
                            EnumOption(value: "cover", label: "Cover"),
                            EnumOption(value: "contain", label: "Contain")
                        ]
                    )]
                ),
                approvedEnum,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["fit": .string("stretch")]
                ),
                .rejected
            ),
            (
                "malformed enum options",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "fit",
                        type: .enumValue,
                        options: []
                    )]
                ),
                approvedEnum,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["fit": .string("cover")]
                ),
                .rejected
            ),
            (
                "hidden",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "shuffle",
                        type: .bool,
                        visible: false
                    )]
                ),
                approvedShuffle,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["shuffle": .bool(true)]
                ),
                .rejected
            ),
            (
                "read only",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "shuffle",
                        type: .bool,
                        readOnly: true
                    )]
                ),
                approvedShuffle,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["shuffle": .bool(true)]
                ),
                .rejected
            ),
            (
                "unknown type",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "intervalSec",
                        rawType: "future-number",
                        type: .unknown(rawValue: "future-number")
                    )]
                ),
                approvedInterval,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["intervalSec": .number(45)]
                ),
                .rejected
            ),
            (
                "unknown control",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "futureControl",
                        type: .string
                    )]
                ),
                .default,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["futureControl": .string("value")]
                ),
                .rejected
            ),
            (
                "secret",
                schema(
                    domainID: "mqtt",
                    controls: [SettingsControlSchema(
                        domainID: "mqtt",
                        key: "password",
                        type: .string,
                        secret: true,
                        hasValue: true
                    )]
                ),
                approvedSecret,
                SettingsDomainDraft(
                    domainID: "mqtt",
                    values: ["password": .string("entered-secret")]
                ),
                .rejected
            ),
            (
                "policy",
                schema(
                    domainID: "screensaver",
                    controls: [SettingsControlSchema(
                        domainID: "screensaver",
                        key: "enabled",
                        type: .bool
                    )]
                ),
                .default,
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["enabled": .bool(true)]
                ),
                .rejected
            )
        ]

        for (label, candidateSchema, policy, draft, expectedOutcome) in cases {
            let context = try await makeContext(policy: policy)
            let result = try await context.coordinator.apply(
                portalID: portalID,
                admissionRequest: try admissionRequest(),
                drafts: [draft],
                schema: candidateSchema
            )

            XCTAssertEqual(
                context.transport.requestCount,
                0,
                "The \(label) draft must not reach the typed Fleet transport."
            )
            XCTAssertEqual(result.domains.count, 1, label)
            XCTAssertEqual(result.domains[0].fields.count, 1, label)
            XCTAssertEqual(result.domains[0].fields[0].outcome, expectedOutcome, label)
        }
    }

    func testBlankSecretIsOmittedWithoutARequest() async throws {
        let candidateSchema = schema(
            domainID: "mqtt",
            controls: [SettingsControlSchema(
                domainID: "mqtt",
                key: "password",
                type: .string,
                secret: true,
                hasValue: true
            )]
        )
        let context = try await makeContext(
            policy: approvedPolicy(controls: [("mqtt", "password")])
        )

        let result = try await context.coordinator.apply(
            portalID: portalID,
            admissionRequest: try admissionRequest(),
            drafts: [SettingsDomainDraft(
                domainID: "mqtt",
                values: ["password": .string("  ")]
            )],
            schema: candidateSchema
        )

        XCTAssertEqual(context.transport.requestCount, 0)
        XCTAssertEqual(result.domains[0].omittedKeys, ["password"])
        XCTAssertNil(result.domains[0].error)
    }

    func testMixedDraftSendsOnlyApprovedValuesAndPreservesRejectedFields() async throws {
        let candidateSchema = schema(
            domainID: "screensaver",
            controls: [
                SettingsControlSchema(
                    domainID: "screensaver",
                    key: "intervalSec",
                    type: .int,
                    value: .number(30),
                    min: 5,
                    max: 600,
                    step: 5
                ),
                SettingsControlSchema(
                    domainID: "screensaver",
                    key: "shuffle",
                    type: .bool,
                    value: .bool(false)
                )
            ]
        )
        let context = try await makeContext(
            policy: approvedPolicy(controls: [("screensaver", "intervalSec")]),
            responses: [
                postResponse(applied: ["intervalSec"]),
                getResponse(candidateSchema)
            ]
        )

        let result = try await context.coordinator.apply(
            portalID: portalID,
            admissionRequest: try admissionRequest(),
            draft: SettingsDomainDraft(
                domainID: "screensaver",
                values: [
                    "intervalSec": .number(45),
                    "shuffle": .bool(true)
                ]
            ),
            schema: candidateSchema
        )

        let domain = try XCTUnwrap(result.domains.first)
        XCTAssertEqual(domain.appliedKeys, ["intervalSec"])
        XCTAssertEqual(domain.rejectedKeys, ["shuffle"])
        XCTAssertEqual(context.transport.requestCount, 2)
        let requestBody = try decodeJSON(context.transport.requests[0].body)
        let expectedBody: JSONValue = .object([
            "domain": .string("screensaver"),
            "values": .object(["intervalSec": .number(45)])
        ])
        XCTAssertEqual(requestBody, expectedBody)
    }

    func testDraftsAreGroupedByDomainAndUseExactRemoteSettingsBody() async throws {
        let candidateSchema = SettingsRegistrySchema(domains: [
            SettingsDomainSchema(
                id: "screensaver",
                controls: [
                    SettingsControlSchema(
                        domainID: "screensaver",
                        key: "intervalSec",
                        type: .int,
                        value: .number(30),
                        min: 5,
                        max: 600,
                        step: 5
                    ),
                    SettingsControlSchema(
                        domainID: "screensaver",
                        key: "shuffle",
                        type: .bool,
                        value: .bool(false)
                    )
                ]
            ),
            SettingsDomainSchema(
                id: "immortal",
                controls: [SettingsControlSchema(
                    domainID: "immortal",
                    key: "weatherUnit",
                    type: .string,
                    value: .string("celsius")
                )]
            )
        ])
        let context = try await makeContext(
            policy: approvedPolicy(controls: [
                ("screensaver", "intervalSec"),
                ("screensaver", "shuffle"),
                ("immortal", "weatherUnit")
            ]),
            responses: [
                postResponse(applied: ["weatherUnit"]),
                getResponse(candidateSchema),
                postResponse(applied: ["intervalSec", "shuffle"]),
                getResponse(candidateSchema)
            ]
        )

        let result = try await context.coordinator.apply(
            portalID: portalID,
            admissionRequest: try admissionRequest(),
            drafts: [
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["intervalSec": .number(45)]
                ),
                SettingsDomainDraft(
                    domainID: "screensaver",
                    values: ["shuffle": .bool(true)]
                ),
                SettingsDomainDraft(
                    domainID: "immortal",
                    values: ["weatherUnit": .string("fahrenheit")]
                )
            ],
            schema: candidateSchema
        )

        XCTAssertEqual(result.domains.map { $0.domainID }, ["immortal", "screensaver"])
        XCTAssertEqual(context.transport.requestCount, 4)
        let postBodies = context.transport.requests
            .filter { $0.method == HTTPMethod.post }
            .map { $0.body }
        XCTAssertEqual(postBodies.count, 2)
        let firstExpectedBody: JSONValue = .object([
            "domain": .string("immortal"),
            "values": .object(["weatherUnit": .string("fahrenheit")])
        ])
        let secondExpectedBody: JSONValue = .object([
            "domain": .string("screensaver"),
            "values": .object([
                "intervalSec": .number(45),
                "shuffle": .bool(true)
            ])
        ])
        XCTAssertEqual(try decodeJSON(postBodies[0]), firstExpectedBody)
        XCTAssertEqual(try decodeJSON(postBodies[1]), secondExpectedBody)
    }

    func testPartialAppliedResponsePreservesOmittedKeysBeforeReadBack() async throws {
        let candidateSchema = schema(
            domainID: "screensaver",
            controls: [
                SettingsControlSchema(
                    domainID: "screensaver",
                    key: "intervalSec",
                    type: .int,
                    value: .number(30),
                    min: 5,
                    max: 600,
                    step: 5
                ),
                SettingsControlSchema(
                    domainID: "screensaver",
                    key: "shuffle",
                    type: .bool,
                    value: .bool(false)
                )
            ]
        )
        let context = try await makeContext(
            policy: approvedPolicy(controls: [
                ("screensaver", "intervalSec"),
                ("screensaver", "shuffle")
            ]),
            responses: [
                postResponse(applied: ["intervalSec"]),
                getResponse(candidateSchema)
            ]
        )

        let result = try await context.coordinator.apply(
            portalID: portalID,
            admissionRequest: try admissionRequest(),
            draft: SettingsDomainDraft(
                domainID: "screensaver",
                values: [
                    "intervalSec": .number(45),
                    "shuffle": .bool(true)
                ]
            ),
            schema: candidateSchema
        )

        let domain = try XCTUnwrap(result.domains.first)
        XCTAssertEqual(domain.appliedKeys, ["intervalSec"])
        XCTAssertEqual(domain.omittedKeys, ["shuffle"])
        XCTAssertEqual(domain.failedKeys, [])
        XCTAssertTrue(domain.isPartial)
        XCTAssertEqual(domain.confirmedDomain, candidateSchema.domains[0])
    }

    func testAuthoritativeReadBackReplacesConfirmedSchema() async throws {
        let initialSchema = schema(
            domainID: "screensaver",
            controls: [SettingsControlSchema(
                domainID: "screensaver",
                key: "intervalSec",
                type: .int,
                value: .number(45),
                min: 5,
                max: 600,
                step: 5
            )]
        )
        let authoritativeSchema = schema(
            domainID: "screensaver",
            controls: [SettingsControlSchema(
                domainID: "screensaver",
                key: "intervalSec",
                type: .int,
                title: "Server-confirmed interval",
                value: .number(55),
                min: 5,
                max: 600,
                step: 5
            )]
        )
        let context = try await makeContext(
            policy: approvedPolicy(controls: [("screensaver", "intervalSec")]),
            responses: [
                postResponse(applied: ["intervalSec"]),
                getResponse(authoritativeSchema)
            ]
        )
        await context.coordinator.setConfirmedSchema(initialSchema, for: portalID)

        let result = try await context.coordinator.apply(
            portalID: portalID,
            admissionRequest: try admissionRequest(),
            draft: SettingsDomainDraft(
                domainID: "screensaver",
                values: ["intervalSec": .number(60)]
            ),
            schema: initialSchema
        )

        XCTAssertEqual(result.confirmedSchema, authoritativeSchema)
        let storedConfirmed = await context.coordinator.confirmedSchema(for: portalID)
        XCTAssertEqual(storedConfirmed, authoritativeSchema)
        let confirmedControl = try XCTUnwrap(
            result.domains[0].confirmedDomain?.controls.first
        )
        let confirmedValue = try XCTUnwrap(confirmedControl.currentValue)
        XCTAssertEqual(confirmedValue, JSONValue.number(55))
        XCTAssertEqual(confirmedControl.title, "Server-confirmed interval")
    }

    func testReadBackFailureDoesNotProjectOptimisticState() async throws {
        let initialSchema = schema(
            domainID: "screensaver",
            controls: [SettingsControlSchema(
                domainID: "screensaver",
                key: "intervalSec",
                type: .int,
                value: .number(45),
                min: 5,
                max: 600,
                step: 5
            )]
        )
        let context = try await makeContext(
            policy: approvedPolicy(controls: [("screensaver", "intervalSec")]),
            responses: [postResponse(applied: ["intervalSec"])]
        )
        await context.coordinator.setConfirmedSchema(initialSchema, for: portalID)

        let result = try await context.coordinator.apply(
            portalID: portalID,
            admissionRequest: try admissionRequest(),
            draft: SettingsDomainDraft(
                domainID: "screensaver",
                values: ["intervalSec": .number(60)]
            ),
            schema: initialSchema
        )

        XCTAssertEqual(context.transport.requestCount, 2)
        XCTAssertEqual(result.confirmedSchema, initialSchema)
        let storedConfirmed = await context.coordinator.confirmedSchema(for: portalID)
        XCTAssertEqual(storedConfirmed, initialSchema)
        let confirmedValue = try XCTUnwrap(
            result.domains[0].confirmedDomain?.controls.first?.currentValue
        )
        XCTAssertEqual(confirmedValue, JSONValue.number(45))
        XCTAssertEqual(result.domains[0].appliedKeys, ["intervalSec"])
        XCTAssertEqual(result.domains[0].failedKeys, [])
        XCTAssertNotNil(result.domains[0].error)
    }

    private let portalID = PortalID(
        rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
    )

    private func admissionRequest() throws -> ConnectionAdmissionRequest {
        ConnectionAdmissionRequest(
            endpoint: try LANPolicy.validate(
                hostOrAddress: "192.168.1.20",
                port: 8723,
                source: .manual
            ),
            serviceKind: .portal,
            protocolName: "http"
        )
    }

    private func approvedPolicy(
        controls: [(String, String)],
        fieldPresence: FieldPresencePolicy = .required
    ) -> SettingsPolicyLookup {
        SettingsPolicyLookup(
            entries: controls.map { domainID, key in
                SettingsPolicyEntry(
                    domainID: domainID,
                    controlKey: key,
                    classification: .approvedEditable(
                        route: .remoteSettings,
                        bulk: .allowed,
                        evidence: "settings-coordinator-test"
                    ),
                    fieldPresence: fieldPresence
                )
            }
        )
    }

    private func schema(
        domainID: String,
        controls: [SettingsControlSchema]
    ) -> SettingsRegistrySchema {
        SettingsRegistrySchema(
            domains: [SettingsDomainSchema(id: domainID, controls: controls)]
        )
    }

    private func postResponse(applied: [String]) -> FleetHTTPResponse {
        let payload = JSONValue.object([
            "ok": .bool(true),
            "applied": .array(applied.map { .string($0) })
        ])
        return FleetHTTPResponse(
            statusCode: 200,
            body: try! JSONEncoder().encode(payload)
        )
    }

    private func getResponse(_ schema: SettingsRegistrySchema) -> FleetHTTPResponse {
        let inner = try! JSONDecoder().decode(
            JSONValue.self,
            from: JSONEncoder().encode(schema)
        )
        let payload = JSONValue.object([
            "ok": .bool(true),
            "settings": inner
        ])
        return FleetHTTPResponse(
            statusCode: 200,
            body: try! JSONEncoder().encode(payload)
        )
    }

    private func decodeJSON(_ data: Data?) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: try XCTUnwrap(data))
    }

    private func makeContext(
        policy: SettingsPolicyLookup,
        responses: [FleetHTTPResponse] = []
    ) async throws -> SettingsTestContext {
        let transport = SettingsRecordingTransport(responses: responses)
        let credentialReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let endpoint = try admissionRequest().endpoint
        let entry = PortalRegistryEntry(
            id: portalID,
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
                resolvedHostOrAddress: "192.168.1.20",
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
        return SettingsTestContext(
            coordinator: SettingsCoordinator(
                sessionCoordinator: sessionCoordinator,
                policy: policy
            ),
            transport: transport
        )
    }
}

private struct SettingsTestContext {
    let coordinator: SettingsCoordinator
    let transport: SettingsRecordingTransport
}

private struct SettingsRecordedRequest: Sendable {
    let method: HTTPMethod
    let route: FleetRoute
    let body: Data?
}

private final class SettingsRecordingTransport: FleetHTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var responses: [FleetHTTPResponse]
    private var recordedRequests: [SettingsRecordedRequest] = []

    init(responses: [FleetHTTPResponse]) {
        self.responses = responses
    }

    var requestCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return recordedRequests.count
    }

    var requests: [SettingsRecordedRequest] {
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
            SettingsRecordedRequest(
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
