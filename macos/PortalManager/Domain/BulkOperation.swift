/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The closed set of individually confirmed multi-Portal operations.
///
/// Excluded routes and unverified service mutations are deliberately not
/// representable here; a bulk operation can only be an approved Settings
/// Registry apply or a bearer-only approved action.
enum BulkOperationKind: Codable, Sendable, Equatable, Hashable {
    case settingsApply(domainID: String)
    case approvedAction(ApprovedAction)

    var operationLabel: String {
        switch self {
        case .settingsApply(let domainID):
            return "settings.apply.\(domainID)"
        case .approvedAction(let action):
            return "action.\(action.rawValue)"
        }
    }

    /// The sensitive-value domain affected by this operation, if any.
    var affectedSensitiveDomain: String? {
        switch self {
        case .settingsApply(let domainID):
            return SensitiveBulkField.domain(for: domainID)
        case .approvedAction:
            return nil
        }
    }
}

/// Fields whose bulk change is a Sensitive Value change under requirement 7.8.
enum SensitiveBulkField: String, Codable, Sendable, CaseIterable {
    case maUsername
    case maPassword

    static func domain(for domainID: String) -> String? {
        // The multi-room bridge is currently the only credential-bearing
        // approved settings surface; keep this mapping narrow on purpose.
        domainID == KnownSettingsDomain.immortal.rawValue ? KnownSettingsDomain.immortal.rawValue : nil
    }
}

/// One target's fresh eligibility state produced during preflight.
struct BulkTargetPreflight: Sendable, Equatable, Hashable {
    let portalID: PortalID
    let displayName: String
    let connectionState: ConnectionState?
    let credentialScope: CredentialKind?
    let capabilityAvailable: Bool
    let policyClassified: Bool
    let schemaConstraints: [String]
    let omittedFields: [String]
    let reducedValues: [String: JSONValue]
    let affectsSensitiveValue: Bool
    let ineligibilityReasons: [String]

    var isEligible: Bool { ineligibilityReasons.isEmpty }

    /// A reduced per-target plan: only values that survived that target's own
    /// schema constraints. Never a copy of another Portal's plan.
    var dispatchValues: [String: JSONValue] { reducedValues }
}

/// The explicit summary shown before any request can be dispatched.
struct BulkPreflightSummary: Sendable, Equatable, Hashable {
    let operation: BulkOperationKind
    let targets: [BulkTargetPreflight]
    let eligibleCount: Int
    let ineligibleCount: Int
    let divergentSchemasDetected: Bool
    let affectsSensitiveValue: Bool
    let sensitiveDomainName: String?

    init(operation: BulkOperationKind, targets: [BulkTargetPreflight]) {
        self.operation = operation
        self.targets = targets
        let eligible = targets.filter(\.isEligible)
        self.eligibleCount = eligible.count
        self.ineligibleCount = targets.count - eligible.count
        self.divergentSchemasDetected = targets.contains { !$0.omittedFields.isEmpty }
        // A sensitive change is one that affects any target OR is inherent to
        // the selected operation itself.
        self.affectsSensitiveValue = targets.contains(where: \.affectsSensitiveValue)
            || operation.affectedSensitiveDomain != nil
        self.sensitiveDomainName = operation.affectedSensitiveDomain
    }

    var requiresConfirmation: Bool { true }

    var confirmationHeadline: String {
        let count = eligibleCount
        let noun = count == 1 ? "Portal" : "Portals"
        var headline = "Apply \(operation.operationLabel) to \(count) \(noun)"
        if divergentSchemasDetected {
            headline += " (reduced per-target fields)"
        }
        return headline
    }

    var confirmationDetail: [String] {
        var detail = [
            "Eligible targets: \(eligibleCount)",
            "Ineligible targets: \(ineligibleCount)",
        ]
        if divergentSchemasDetected {
            detail.append("Divergent schemas detected; per-target fields will be reduced.")
        }
        if affectsSensitiveValue, let sensitiveDomainName {
            detail.append(
                "This operation changes a Sensitive Value in the '\(sensitiveDomainName)' domain."
            )
        }
        return detail
    }
}

/// The operator's explicit decision for a preflight summary. Dispatch with no
/// confirmation emits zero requests.
struct BulkConfirmation: Sendable, Equatable, Hashable {
    let summary: BulkPreflightSummary
    let confirmedAt: Date

    static func acknowledged(_ summary: BulkPreflightSummary, at date: Date) -> Self {
        Self(summary: summary, confirmedAt: date)
    }
}

/// Terminal outcome for one bulk target.
enum BulkTargetOutcomeKind: String, Codable, Sendable, Equatable, Hashable {
    case success
    case partial
    case failure
    case skipped
    case cancelled
}

/// Per-target terminal result with authoritative read-back status. Results are
/// never copied between targets; each carries its own applied/read-back keys.
struct BulkTargetResult: Sendable, Equatable, Hashable {
    let portalID: PortalID
    let outcome: BulkTargetOutcomeKind
    let appliedKeys: [String]
    let readBackConfirmedKeys: [String]
    let error: ManagerError?

    init(
        portalID: PortalID,
        outcome: BulkTargetOutcomeKind,
        appliedKeys: [String] = [],
        readBackConfirmedKeys: [String] = [],
        error: ManagerError? = nil
    ) {
        self.portalID = portalID
        self.outcome = outcome
        self.appliedKeys = appliedKeys
        self.readBackConfirmedKeys = readBackConfirmedKeys
        self.error = error
    }
}

/// Truthful aggregate over per-target results.
struct BulkOperationReport: Sendable, Equatable, Hashable {
    let operation: BulkOperationKind
    let results: [BulkTargetResult]

    init(operation: BulkOperationKind, results: [BulkTargetResult]) {
        self.operation = operation
        self.results = results
    }

    var successCount: Int { count(.success) }
    var partialCount: Int { count(.partial) }
    var failureCount: Int { count(.failure) }
    var skippedCount: Int { count(.skipped) }
    var cancelledCount: Int { count(.cancelled) }

    private func count(_ kind: BulkTargetOutcomeKind) -> Int {
        results.lazy.filter { $0.outcome == kind }.count
    }

    /// Fleet-wide success is claimed only when every dispatched target ended
    /// in confirmed success and nothing was skipped or cancelled.
    var isFullySuccessful: Bool {
        !results.isEmpty && results.allSatisfy { $0.outcome == .success }
    }

    var isPartiallyFailed: Bool {
        (partialCount + failureCount) > 0
    }
}
