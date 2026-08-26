/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Persistence boundary for sanitized release evidence. Records carry opaque
/// identifiers, test names, claims, and deviations only.
protocol ReleaseEvidenceStore: Sendable {
    func save(_ record: ReleaseEvidenceRecord) async throws
    func records(candidateVersion: String) async throws -> [ReleaseEvidenceRecord]
}

/// In-memory store used by the app session and deterministic tests.
actor InMemoryReleaseEvidenceStore: ReleaseEvidenceStore {
    private var recordsByID: [String: [ReleaseEvidenceRecord]] = [:]

    init() {}

    func save(_ record: ReleaseEvidenceRecord) async throws {
        var existing = recordsByID[record.candidateVersion] ?? []
        existing.removeAll { Self.key(for: $0) == Self.key(for: record) }
        existing.append(record)
        recordsByID[record.candidateVersion] = existing
    }

    func records(candidateVersion: String) async throws -> [ReleaseEvidenceRecord] {
        return (recordsByID[candidateVersion] ?? [])
            .sorted { $0.recordedAt < $1.recordedAt }
    }

    private static func key(for record: ReleaseEvidenceRecord) -> String {
        ReleaseGateReport.key(for: record.gateID)
    }
}

/// The v1 support claims a candidate can make. Each claim maps to the gate
/// that must pass before it may be published.
enum ReleaseClaim: String, Codable, Sendable, CaseIterable {
    case coreFleetManagement = "claim.core-fleet-management"
    case usbEnablementRecovery = "claim.usb-enablement-recovery"
    case fullUSBProvisioning = "claim.full-usb-provisioning"
    case readOnlyMusicTopology = "claim.read-only-music-topology"

    var title: String {
        switch self {
        case .coreFleetManagement:
            return "Core Fleet management over LAN"
        case .usbEnablementRecovery:
            return "Fleet Agent Enablement/Recovery over USB"
        case .fullUSBProvisioning:
            return "Full USB Provisioning with a local artifact"
        case .readOnlyMusicTopology:
            return "Read-only Music Assistant/Snapcast topology"
        }
    }
}

/// Evaluates gate evidence and produces the machine-readable report
/// (design §13.1). A missing or failed gate withholds exactly the affected
/// claims; it never enables a fallback or broadens the runtime allowlist.
struct ReleaseGateEvaluator: Sendable {
    let mandatoryGates: [GateID] = [
        .security, .lan, .provisioning, .packaging, .modelMatrix,
    ]

    init() {}

    func status(
        of gateID: GateID,
        in records: [ReleaseEvidenceRecord]
    ) -> GateStatus {
        guard let record = records.first(where: { $0.gateID == gateID }) else {
            return .missing
        }
        return record.status
    }

    /// Builds the full report for a candidate. Portal TV claims require the
    /// `portalTV` gate; each named music mutation requires its own scoped
    /// passed gate before it can appear as a supported operation.
    func evaluate(
        candidateVersion: String,
        records: [ReleaseEvidenceRecord],
        claimsPortalTVSupport: Bool,
        enabledMusicMutations: [GateID] = []
    ) -> ReleaseGateReport {
        var withheld: [String] = []

        // Mandatory v1 gates.
        for gateID in mandatoryGates {
            let status = status(of: gateID, in: records)
            switch status {
            case .passed:
                continue
            case .missing:
                withheld.append(
                    "\(Self.gateName(gateID)) evidence is missing; affected claims are withheld."
                )
            case .pending:
                withheld.append(
                    "\(Self.gateName(gateID)) validation is pending; affected claims are withheld."
                )
            case .failed:
                withheld.append(
                    "\(Self.gateName(gateID)) validation failed; affected claims are withheld."
                )
            case .withheld:
                withheld.append(
                    "\(Self.gateName(gateID)) is explicitly withheld."
                )
            }
        }

        // Conditional Portal TV claim.
        if claimsPortalTVSupport {
            switch status(of: .portalTV, in: records) {
            case .passed:
                break
            case .missing:
                withheld.append("Portal TV Validation evidence is missing; the Portal TV claim is withheld.")
            default:
                withheld.append("Portal TV Validation has not passed; the Portal TV claim is withheld.")
            }
        }

        // Conditional Music Mutation gates.
        for gateID in enabledMusicMutations {
            if case .musicMutation(let service, let operation, _) = gateID {
                if status(of: gateID, in: records) != .passed {
                    withheld.append(
                        "The \(service) '\(operation)' mutation lacks a passed gate and remains unavailable."
                    )
                }
            }
        }

        let publishable = withheld.isEmpty
            ? ReleaseClaim.allCases.map(\.title)
            : []

        return ReleaseGateReport(
            candidateVersion: candidateVersion,
            records: records.sorted { $0.recordedAt < $1.recordedAt },
            publishableClaims: publishable,
            withheldClaims: withheld
        )
    }

    static func gateName(_ gateID: GateID) -> String {
        switch gateID {
        case .security: return "Security Release Validation"
        case .lan: return "LAN Release Validation"
        case .provisioning: return "Provisioning Release Validation"
        case .packaging: return "Signed and Notarized Packaging"
        case .modelMatrix: return "Model Matrix Validation"
        case .portalTV: return "Portal TV Validation"
        case .musicMutation(let service, let operation, _):
            return "Music Mutation (\(service) / \(operation))"
        }
    }
}

/// Coordinates recording and evaluating release evidence for the app.
actor ReleaseEvidenceCoordinator {
    private let store: any ReleaseEvidenceStore
    private let evaluator = ReleaseGateEvaluator()
    private let clock: any ManagerClock
    private let packagingVerifier: any ReleasePackagingVerifier

    init(
        store: any ReleaseEvidenceStore,
        clock: any ManagerClock,
        packagingVerifier: any ReleasePackagingVerifier = SystemReleasePackagingVerifier()
    ) {
        self.store = store
        self.clock = clock
        self.packagingVerifier = packagingVerifier
    }

    func record(
        _ record: ReleaseEvidenceRecord,
        candidateVersion: String = "1.0.0"
    ) async throws {
        try await store.save(record)
    }

    /// Verifies a signed candidate and records only the sanitized packaging
    /// outcome. The verifier owns codesign/stapler failures, so raw process
    /// output cannot enter persisted evidence.
    func recordPackagingGate(
        appPath: String,
        notarizationTicketPath: String,
        candidateVersion: String = "1.0.0"
    ) async throws {
        let candidate = ReleaseCandidate(
            version: candidateVersion,
            appPath: appPath,
            notarizationTicketPath: notarizationTicketPath
        )
        try await packagingVerifier.verify(candidate)

        try await store.save(
            ReleaseEvidenceRecord(
                gateID: .packaging,
                candidateVersion: candidateVersion,
                evidenceIDs: ["codesign.strict", "notarization.ticket"],
                testResults: ["codesign.verify.strict", "stapler.validate.app"],
                supportedClaims: [],
                unresolvedDeviations: [],
                status: .passed,
                recordedAt: clock.now
            )
        )
    }

    func report(
        candidateVersion: String,
        claimsPortalTVSupport: Bool,
        enabledMusicMutations: [GateID]
    ) async throws -> ReleaseGateReport {
        let records = try await store.records(candidateVersion: candidateVersion)
        return evaluator.evaluate(
            candidateVersion: candidateVersion,
            records: records,
            claimsPortalTVSupport: claimsPortalTVSupport,
            enabledMusicMutations: enabledMusicMutations
        )
    }

    /// Emits the evaluator's stable machine-readable projection for CI,
    /// release automation, and operator review. Evidence records remain typed
    /// and sanitized; raw protocol, path, process, or credential text cannot
    /// enter this report.
    func reportData(
        candidateVersion: String,
        claimsPortalTVSupport: Bool,
        enabledMusicMutations: [GateID]
    ) async throws -> Data {
        let report = try await report(
            candidateVersion: candidateVersion,
            claimsPortalTVSupport: claimsPortalTVSupport,
            enabledMusicMutations: enabledMusicMutations
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(report)
    }

    /// Convenience used by the release-evidence detail view.
    func statuses(candidateVersion: String) async throws -> [(gate: GateID, status: GateStatus)] {
        let records = try await store.records(candidateVersion: candidateVersion)
        var allGates: [GateID] = evaluator.mandatoryGates + [.portalTV]
        var seen = Set(allGates)
        for record in records where !seen.contains(record.gateID) {
            allGates.append(record.gateID)
            seen.insert(record.gateID)
        }
        return allGates.map { ($0, evaluator.status(of: $0, in: records)) }
    }
}
