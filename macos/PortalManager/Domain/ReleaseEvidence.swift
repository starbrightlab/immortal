/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The lifecycle of one release gate's evidence. `missing` is distinct from
/// `pending` so a forgotten gate can never read as work in progress.
enum GateStatus: String, Codable, Sendable, Equatable, Hashable {
    case missing
    case pending
    case passed
    case failed
    case withheld
}

/// Sanitized evidence for one gate. Evidence identifiers and test names are
/// opaque strings; no credential, raw protocol body, ADB manifest, or
/// artifact secret is representable in this record.
struct ReleaseEvidenceRecord: Codable, Sendable, Equatable, Hashable {
    var gateID: GateID
    var candidateVersion: String
    var evidenceIDs: [String]
    var testResults: [String]
    var supportedClaims: [String]
    var unresolvedDeviations: [String]
    var status: GateStatus
    var recordedAt: Date

    init(
        gateID: GateID,
        candidateVersion: String,
        evidenceIDs: [String] = [],
        testResults: [String] = [],
        supportedClaims: [String] = [],
        unresolvedDeviations: [String] = [],
        status: GateStatus,
        recordedAt: Date
    ) {
        self.gateID = gateID
        self.candidateVersion = candidateVersion
        self.evidenceIDs = evidenceIDs
        self.testResults = testResults
        self.supportedClaims = supportedClaims
        self.unresolvedDeviations = unresolvedDeviations
        self.status = status
        self.recordedAt = recordedAt
    }
}

/// The machine-readable projection consumed by CI/task runners.
struct ReleaseGateReport: Codable, Sendable, Equatable, Hashable {
    var candidateVersion: String
    var records: [ReleaseEvidenceRecord]
    var publishableClaims: [String]
    var withheldClaims: [String]

    init(
        candidateVersion: String,
        records: [ReleaseEvidenceRecord],
        publishableClaims: [String],
        withheldClaims: [String]
    ) {
        self.candidateVersion = candidateVersion
        self.records = records
        self.publishableClaims = publishableClaims
        self.withheldClaims = withheldClaims
    }

    /// Stable per-gate status map for tooling.
    var statusByGate: [String: String] {
        Dictionary(
            records.map { (Self.key(for: $0.gateID), $0.status.rawValue) },
            uniquingKeysWith: { current, _ in current }
        )
    }

    static func key(for gateID: GateID) -> String {
        switch gateID {
        case .security:
            return "security"
        case .lan:
            return "lan"
        case .provisioning:
            return "provisioning"
        case .packaging:
            return "packaging"
        case .modelMatrix:
            return "modelMatrix"
        case .portalTV:
            return "portalTV"
        case .musicMutation(let service, let operation, _):
            return "musicMutation.\(service).\(operation)"
        }
    }
}
