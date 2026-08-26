/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Stable schema failures exposed by the Fleet response boundary. These cases
/// deliberately carry no response body, headers, or server-provided text.
enum FleetSchemaFailure: String, Codable, Sendable, Equatable, Hashable {
    case emptyBody
    case invalidJSON
    case topLevelNotObject
    case missingPairingToken
    case invalidAppliedKeys

    var sanitizedMessage: String {
        switch self {
        case .emptyBody:
            return "The Fleet Agent returned an empty response."
        case .invalidJSON:
            return "The Fleet Agent returned invalid JSON."
        case .topLevelNotObject:
            return "The Fleet Agent returned an invalid response shape."
        case .missingPairingToken:
            return "The Fleet Agent pairing response did not contain a session token."
        case .invalidAppliedKeys:
            return "The Fleet Agent returned an invalid applied-key set."
        }
    }
}

/// Classification of an admitted Fleet HTTP response. HTTP status failures
/// are named individually where the manager has distinct recovery semantics;
/// all other client/server statuses retain their numeric status without any
/// untrusted response detail.
enum FleetResponseClassification: Codable, Sendable, Equatable, Hashable {
    case success
    case partialApply(appliedKeys: [String], omittedKeys: [String])
    case applicationFailure
    case unauthorized
    case forbidden
    case notFound
    case methodNotAllowed
    case conflict
    case clientFailure(statusCode: Int)
    case serverFailure(statusCode: Int)
    case redirectRejected
    case schemaFailure(FleetSchemaFailure)
    case unexpectedStatus(statusCode: Int)

    var isSuccessful: Bool {
        switch self {
        case .success, .partialApply:
            return true
        case .applicationFailure, .unauthorized, .forbidden, .notFound,
             .methodNotAllowed, .conflict, .clientFailure, .serverFailure,
             .redirectRejected, .schemaFailure, .unexpectedStatus:
            return false
        }
    }

    /// Converts a non-success classification into the existing sanitized
    /// manager error boundary. The selected credential kind is intentionally
    /// not included here; the session coordinator adds that context from its
    /// already validated request plan when handling reauthentication.
    var managerError: ManagerError? {
        switch self {
        case .success, .partialApply:
            return nil
        case .applicationFailure:
            return .validation(
                field: "Fleet response",
                reason: "The Fleet Agent rejected the approved operation."
            )
        case .unauthorized:
            return .authentication(.unauthorized)
        case .forbidden:
            return .http(status: 403, code: nil, detail: nil)
        case .notFound:
            return .http(status: 404, code: nil, detail: nil)
        case .methodNotAllowed:
            return .http(status: 405, code: nil, detail: nil)
        case .conflict:
            return .http(status: 409, code: nil, detail: nil)
        case .clientFailure(let statusCode):
            return .http(status: statusCode, code: nil, detail: nil)
        case .serverFailure(let statusCode):
            return .http(status: statusCode, code: nil, detail: nil)
        case .redirectRejected:
            return .redirectRejected
        case .schemaFailure(let failure):
            return .validation(
                field: "Fleet response",
                reason: failure.sanitizedMessage
            )
        case .unexpectedStatus(let statusCode):
            return .http(status: statusCode, code: nil, detail: nil)
        }
    }
}

/// The result of validating and classifying one HTTP response. A JSON object
/// is retained only as active operation data for the typed adapter that asked
/// for it; it is never a registry, log, or UI persistence type.
struct FleetResponseAnalysis: Sendable, Equatable {
    let classification: FleetResponseClassification
    let payload: JSONValue?
}

/// Pure response classifier for the closed Fleet route surface.
struct FleetResponseClassifier: Sendable {
    init() {}

    func analyze(
        _ response: FleetHTTPResponse,
        route: FleetRoute,
        expectedAppliedKeys: [String] = []
    ) -> FleetResponseAnalysis {
        switch response.statusCode {
        case 200...299:
            return analyzeSuccess(
                response,
                route: route,
                expectedAppliedKeys: expectedAppliedKeys
            )
        case 300...399:
            return FleetResponseAnalysis(
                classification: .redirectRejected,
                payload: nil
            )
        case 401:
            return FleetResponseAnalysis(
                classification: .unauthorized,
                payload: nil
            )
        case 403:
            return FleetResponseAnalysis(
                classification: .forbidden,
                payload: nil
            )
        case 404:
            return FleetResponseAnalysis(
                classification: .notFound,
                payload: nil
            )
        case 405:
            return FleetResponseAnalysis(
                classification: .methodNotAllowed,
                payload: nil
            )
        case 409:
            return FleetResponseAnalysis(
                classification: .conflict,
                payload: nil
            )
        case 400...499:
            return FleetResponseAnalysis(
                classification: .clientFailure(statusCode: response.statusCode),
                payload: nil
            )
        case 500...599:
            return FleetResponseAnalysis(
                classification: .serverFailure(statusCode: response.statusCode),
                payload: nil
            )
        default:
            return FleetResponseAnalysis(
                classification: .unexpectedStatus(statusCode: response.statusCode),
                payload: nil
            )
        }
    }

    private func analyzeSuccess(
        _ response: FleetHTTPResponse,
        route: FleetRoute,
        expectedAppliedKeys: [String]
    ) -> FleetResponseAnalysis {
        guard let body = response.body, !body.isEmpty else {
            return schemaFailure(.emptyBody)
        }

        let payload: JSONValue
        do {
            payload = try JSONDecoder().decode(JSONValue.self, from: body)
        } catch {
            return schemaFailure(.invalidJSON)
        }

        guard case let .object(object) = payload else {
            return schemaFailure(.topLevelNotObject)
        }

        if case .bool(false) = object["ok"] {
            return FleetResponseAnalysis(
                classification: .applicationFailure,
                payload: payload
            )
        }

        if route == .remotePair {
            guard case let .string(token) = object["token"],
                  !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return schemaFailure(.missingPairingToken)
            }
        }

        let appliedResult = appliedKeys(
            in: object,
            expected: expectedAppliedKeys
        )
        switch appliedResult {
        case .invalid:
            return schemaFailure(.invalidAppliedKeys)
        case .none:
            return FleetResponseAnalysis(
                classification: .success,
                payload: payload
            )
        case let .partial(applied, omitted):
            return FleetResponseAnalysis(
                classification: .partialApply(
                    appliedKeys: applied,
                    omittedKeys: omitted
                ),
                payload: payload
            )
        case .complete:
            return FleetResponseAnalysis(
                classification: .success,
                payload: payload
            )
        }
    }

    private enum AppliedKeysResult {
        case none
        case invalid
        case complete
        case partial(applied: [String], omitted: [String])
    }

    private func appliedKeys(
        in object: [String: JSONValue],
        expected: [String]
    ) -> AppliedKeysResult {
        let normalizedExpected = Array(Set(expected)).sorted()
        guard !normalizedExpected.isEmpty else { return .none }

        guard let appliedValue = object["applied"] else {
            return .invalid
        }
        guard case let .array(values) = appliedValue else {
            return .invalid
        }

        var applied = Set<String>()
        for value in values {
            guard case let .string(key) = value,
                  !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return .invalid
            }
            applied.insert(key)
        }

        let appliedKeys = applied.sorted()
        let omittedKeys = normalizedExpected.filter { !applied.contains($0) }
        if omittedKeys.isEmpty {
            return .complete
        }
        return .partial(applied: appliedKeys, omitted: omittedKeys)
    }

    private func schemaFailure(_ failure: FleetSchemaFailure) -> FleetResponseAnalysis {
        FleetResponseAnalysis(
            classification: .schemaFailure(failure),
            payload: nil
        )
    }
}
