/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A validated six-digit PIN used only to construct the typed pairing body.
/// The value is never part of a route, URL, credential reference, or
/// response/error description.
struct PairingPIN: Sendable, Equatable, Hashable {
    private let digits: String

    init(_ value: String) throws {
        let candidate = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !candidate.isEmpty else {
            throw ManagerError.pairing(.blankPIN)
        }
        guard candidate.count == 6,
              candidate.unicodeScalars.allSatisfy({ scalar in
                  scalar.value >= 48 && scalar.value <= 57
              }) else {
            throw ManagerError.pairing(.invalidPIN)
        }
        digits = candidate
    }

    init(rawValue value: String) throws {
        try self.init(value)
    }

    var rawValue: String { digits }

    var bodyData: Data {
        // A six-digit ASCII value is always valid JSON after encoding. The
        // fallback is unreachable but keeps this value property total.
        let body = ["pin": digits]
        return (try? JSONSerialization.data(withJSONObject: body))
            ?? Data("{}".utf8)
    }
}

/// The only body forms the Fleet client can hand to the transport. Pairing is
/// intentionally a separate case so a PIN cannot accidentally become a bearer
/// header, query value, or body for another approved route.
enum FleetRequestBody: Sendable, Equatable {
    case none
    case json(Data)
    case pairingPIN(PairingPIN)

    var data: Data? {
        switch self {
        case .none:
            return nil
        case .json(let data):
            return data
        case .pairingPIN(let pin):
            return pin.bodyData
        }
    }
}

/// A typed request assembled by an application coordinator. The endpoint is
/// still represented by a `ConnectionAdmissionRequest`; `FleetHTTPClient`
/// passes it to `HTTPConnectionExecutor`, which resolves and admits it before
/// reading the credential or invoking the injected transport.
struct FleetHTTPClientRequest: Sendable, Equatable {
    let portalID: PortalID
    let admissionRequest: ConnectionAdmissionRequest
    let routePlan: RouteCredentialPlan
    let credentialReference: CredentialReference?
    let body: FleetRequestBody
    let expectedAppliedKeys: [String]

    init(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        routePlan: RouteCredentialPlan,
        credentialReference: CredentialReference? = nil,
        body: FleetRequestBody = .none,
        expectedAppliedKeys: [String] = []
    ) {
        self.portalID = portalID
        self.admissionRequest = admissionRequest
        self.routePlan = routePlan
        self.credentialReference = credentialReference
        self.body = body
        self.expectedAppliedKeys = Array(Set(expectedAppliedKeys)).sorted()
    }

    /// Constructs the one approved no-credential pairing request through the
    /// closed planner rather than allowing a caller to invent its route plan.
    static func pairing(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        pin: PairingPIN
    ) throws -> FleetHTTPClientRequest {
        let plan = try OperationPlanner().plan(
            portalID: portalID,
            route: .remotePair,
            method: .post
        )
        return FleetHTTPClientRequest(
            portalID: portalID,
            admissionRequest: admissionRequest,
            routePlan: plan,
            body: .pairingPIN(pin)
        )
    }
}

typealias FleetClientRequest = FleetHTTPClientRequest

/// A response that has passed status and JSON-shape classification. The raw
/// response and JSON payload remain active-operation values for a typed adapter;
/// no client method persists them or projects them into UI state.
struct FleetHTTPClientResponse: Sendable {
    let response: FleetHTTPResponse
    let classification: FleetResponseClassification
    let payload: JSONValue

    var statusCode: Int { response.statusCode }
    var body: Data? { response.body }
}

typealias FleetClientResponse = FleetHTTPClientResponse

/// Executes only planner-approved Fleet requests over the injected connection
/// executor and classifies their responses. This type owns no registry or
/// confirmed Portal state, so a failed request cannot overwrite a last-known
/// value or claim an unconfirmed mutation.
struct FleetHTTPClient: Sendable {
    private let connectionExecutor: HTTPConnectionExecutor
    private let planner: OperationPlanner
    private let classifier: FleetResponseClassifier

    init(
        transport: any FleetHTTPTransport,
        admission: ConnectionAdmission,
        credentialStore: any CredentialStore,
        planner: OperationPlanner = OperationPlanner(),
        classifier: FleetResponseClassifier = FleetResponseClassifier()
    ) {
        self.connectionExecutor = HTTPConnectionExecutor(
            admission: admission,
            credentialStore: credentialStore,
            transport: transport
        )
        self.planner = planner
        self.classifier = classifier
    }

    init(
        connectionExecutor: HTTPConnectionExecutor,
        planner: OperationPlanner = OperationPlanner(),
        classifier: FleetResponseClassifier = FleetResponseClassifier()
    ) {
        self.connectionExecutor = connectionExecutor
        self.planner = planner
        self.classifier = classifier
    }

    /// Returns a client with the same admission/transport/planner policy but a
    /// caller-supplied credential store. This is used by reauthentication to
    /// verify a replacement token in operation memory before Keychain commit.
    func withCredentialStore(
        _ credentialStore: any CredentialStore
    ) -> FleetHTTPClient {
        FleetHTTPClient(
            connectionExecutor: connectionExecutor.withCredentialStore(credentialStore),
            planner: planner,
            classifier: classifier
        )
    }

    /// Compatibility spelling for staged credential verification call sites.
    func usingCredentialStore(
        _ credentialStore: any CredentialStore
    ) -> FleetHTTPClient {
        withCredentialStore(credentialStore)
    }

    func execute(
        _ request: FleetHTTPClientRequest
    ) async throws -> FleetHTTPClientResponse {
        try validate(request)

        let connectionRequest = FleetConnectionRequest(
            admissionRequest: request.admissionRequest,
            routePlan: request.routePlan,
            credentialReference: request.credentialReference,
            body: request.body.data
        )

        do {
            let response = try await connectionExecutor.execute(connectionRequest)
            return try classify(response, for: request)
        } catch let error as ManagerError {
            // Includes DNS/LAN admission, Keychain, transport timeout, and
            // no-follow redirect errors already sanitized by lower layers.
            throw error
        } catch let error as ConnectionAdmissionError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // Do not expose injected transport details, URLs, or response text.
            throw ManagerError.transport(.connectionFailed)
        }
    }

    /// Repeats the complete DNS/LAN/trust/credential sequence. The executor
    /// deliberately does not reuse an admitted address on reconnect.
    func reconnect(
        _ request: FleetHTTPClientRequest
    ) async throws -> FleetHTTPClientResponse {
        try validate(request)

        let connectionRequest = FleetConnectionRequest(
            admissionRequest: request.admissionRequest,
            routePlan: request.routePlan,
            credentialReference: request.credentialReference,
            body: request.body.data
        )

        do {
            let response = try await connectionExecutor.reconnect(connectionRequest)
            return try classify(response, for: request)
        } catch let error as ManagerError {
            throw error
        } catch let error as ConnectionAdmissionError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ManagerError.transport(.connectionFailed)
        }
    }

    /// Convenience entry point for the explicit manual/USB-independent pairing
    /// request. Exact-once redemption and credential persistence belong to the
    /// session coordinator; this method performs one transport execution only.
    func pair(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        pin: PairingPIN
    ) async throws -> FleetHTTPClientResponse {
        let request = try FleetHTTPClientRequest.pairing(
            portalID: portalID,
            admissionRequest: admissionRequest,
            pin: pin
        )
        return try await execute(request)
    }

    private func validate(_ request: FleetHTTPClientRequest) throws {
        let normalizedPlan: RouteCredentialPlan
        do {
            normalizedPlan = try planner.plan(
                portalID: request.portalID,
                route: request.routePlan.route,
                method: request.routePlan.method,
                credential: request.routePlan.credential,
                remoteApproval: request.routePlan.remoteApproval,
                operationID: request.routePlan.operationID
            )
        } catch let error as OperationPlanningError {
            throw error.managerError
        } catch {
            throw ManagerError.validation(
                field: "Fleet operation",
                reason: "The Fleet operation could not be validated."
            )
        }

        guard normalizedPlan == request.routePlan else {
            throw ManagerError.validation(
                field: "Fleet operation",
                reason: "The Fleet route plan is not validated."
            )
        }

        switch request.routePlan.credential {
        case nil:
            guard request.credentialReference == nil else {
                throw ManagerError.authentication(.invalidCredential)
            }
        case .some:
            guard request.credentialReference != nil else {
                throw ManagerError.authentication(.missingCredential)
            }
        }

        switch request.body {
        case .none:
            guard request.routePlan.route != .remotePair else {
                throw ManagerError.validation(
                    field: "Fleet request body",
                    reason: "The pairing route requires a validated PIN body."
                )
            }

        case .pairingPIN:
            guard request.routePlan.route == .remotePair,
                  request.routePlan.method == .post,
                  request.routePlan.credential == nil,
                  request.credentialReference == nil,
                  request.routePlan.remoteApproval == nil else {
                throw ManagerError.validation(
                    field: "Fleet request body",
                    reason: "A PIN body is approved only for the no-credential pairing route."
                )
            }

        case .json(let data):
            guard request.routePlan.route != .remotePair,
                  request.routePlan.method == .post,
                  !data.isEmpty else {
                throw ManagerError.validation(
                    field: "Fleet request body",
                    reason: "A JSON body is not approved for this Fleet operation."
                )
            }
            guard isJSONObject(data) else {
                throw ManagerError.validation(
                    field: "Fleet request body",
                    reason: "The Fleet request body must be a JSON object."
                )
            }
        }

        if !request.expectedAppliedKeys.isEmpty {
            guard request.routePlan.method == .post,
                  request.routePlan.route != .info,
                  request.routePlan.route != .remotePair else {
                throw ManagerError.validation(
                    field: "Fleet applied fields",
                    reason: "Applied-field expectations are not approved for this route."
                )
            }
        }
    }

    private func classify(
        _ response: FleetHTTPResponse,
        for request: FleetHTTPClientRequest
    ) throws -> FleetHTTPClientResponse {
        let analysis = classifier.analyze(
            response,
            route: request.routePlan.route,
            expectedAppliedKeys: request.expectedAppliedKeys
        )

        guard analysis.classification.isSuccessful,
              let payload = analysis.payload else {
            throw analysis.classification.managerError
                ?? ManagerError.validation(
                    field: "Fleet response",
                    reason: "The Fleet Agent response was not accepted."
                )
        }

        return FleetHTTPClientResponse(
            response: response,
            classification: analysis.classification,
            payload: payload
        )
    }

    private func isJSONObject(_ data: Data) -> Bool {
        guard let value = try? JSONDecoder().decode(JSONValue.self, from: data) else {
            return false
        }
        if case .object = value {
            return true
        }
        return false
    }
}
