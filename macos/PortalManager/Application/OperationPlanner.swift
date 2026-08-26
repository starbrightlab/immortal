/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Fail-closed reasons returned before a route plan can reach Keychain or
/// transport code. The cases do not carry raw routes, request bodies, or
/// credential values.
enum OperationPlanningError: Error, Codable, Sendable, Equatable, Hashable {
    case methodNotAllowed
    case credentialRequired
    case credentialNotPermitted
    case remoteSessionApprovalRequired
    case remoteSessionApprovalMismatch
    case invalidRemoteOperationID
    case unexpectedRemoteApproval

    var sanitizedMessage: String {
        switch self {
        case .methodNotAllowed:
            return "The HTTP method is not approved for this Fleet route."
        case .credentialRequired:
            return "The approved Fleet route requires a credential."
        case .credentialNotPermitted:
            return "The selected credential is not permitted for this Fleet route."
        case .remoteSessionApprovalRequired:
            return "An exact remote-session operation approval is required."
        case .remoteSessionApprovalMismatch:
            return "The remote-session approval does not match the requested Portal operation."
        case .invalidRemoteOperationID:
            return "The remote-session operation identifier is invalid."
        case .unexpectedRemoteApproval:
            return "The remote-session approval is not applicable to this Fleet route."
        }
    }

    /// Maps planning failures into the existing sanitized manager error
    /// boundary without exposing route strings or credential values.
    var managerError: ManagerError {
        .validation(field: "Fleet operation", reason: sanitizedMessage)
    }
}

extension OperationPlanningError: LocalizedError {
    var errorDescription: String? { sanitizedMessage }
}

/// Pure, state-free operation planner for the closed Fleet route surface.
///
/// This type intentionally has no transport, Keychain, registry, or UI
/// dependency. A successful plan only proves that the requested combination is
/// representable and policy-approved; the eventual coordinator still performs
/// LAN admission and obtains the selected credential through the existing
/// infrastructure boundary.
struct OperationPlanner: Sendable {
    init() {}

    func plan(
        portalID: PortalID,
        route: FleetRoute,
        method: HTTPMethod,
        credential: CredentialKind? = nil,
        remoteApproval: RemoteOperationApproval? = nil,
        operationID: String? = nil
    ) throws -> RouteCredentialPlan {
        guard route.allowedMethods.contains(method) else {
            throw OperationPlanningError.methodNotAllowed
        }

        if let operationID,
           operationID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw OperationPlanningError.invalidRemoteOperationID
        }

        switch route {
        case .remotePair:
            guard credential == nil else {
                throw OperationPlanningError.credentialNotPermitted
            }
            guard remoteApproval == nil, operationID == nil else {
                throw OperationPlanningError.unexpectedRemoteApproval
            }
            return RouteCredentialPlan(
                method: method,
                route: route,
                credential: nil
            )

        case .info, .apps, .screensaver, .calendar, .action:
            guard remoteApproval == nil, operationID == nil else {
                throw OperationPlanningError.unexpectedRemoteApproval
            }
            guard let credential else {
                throw OperationPlanningError.credentialRequired
            }
            guard credential == .verifiedBearer else {
                throw OperationPlanningError.credentialNotPermitted
            }
            return RouteCredentialPlan(
                method: method,
                route: route,
                credential: .verifiedBearer
            )

        case .install, .update, .appProfiles,
             .remoteSettings, .remoteSources, .remoteApps,
             .remoteMedia, .remoteVolume, .remoteLaunch:
            if remoteApproval != nil, credential != .remoteSession {
                throw OperationPlanningError.unexpectedRemoteApproval
            }

            guard let credential else {
                throw OperationPlanningError.credentialRequired
            }

            switch credential {
            case .verifiedBearer:
                // A bearer is directly permitted for both approved remote
                // routes. Session approval is neither needed nor retained.
                return RouteCredentialPlan(
                    method: method,
                    route: route,
                    credential: .verifiedBearer
                )

            case .remoteSession:
                guard let remoteApproval else {
                    throw OperationPlanningError.remoteSessionApprovalRequired
                }
                let requestedOperationID = operationID ?? remoteApproval.operationID
                guard !requestedOperationID
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .isEmpty else {
                    throw OperationPlanningError.invalidRemoteOperationID
                }
                guard remoteApproval.isStructurallyValid,
                      remoteApproval.matches(
                          portalID: portalID,
                          route: route,
                          method: method,
                          operationID: requestedOperationID
                      ) else {
                    throw OperationPlanningError.remoteSessionApprovalMismatch
                }
                return RouteCredentialPlan(
                    method: method,
                    route: route,
                    credential: .remoteSession,
                    remoteApproval: remoteApproval
                )

            default:
                // Service and source credential labels are never Portal Fleet
                // authorization credentials.
                throw OperationPlanningError.credentialNotPermitted
            }
        }
    }

    /// Convenience spelling for call sites that name the approval explicitly.
    func plan(
        portalID: PortalID,
        route: FleetRoute,
        method: HTTPMethod,
        credential: CredentialKind? = nil,
        operationID: String?,
        approval: RemoteOperationApproval
    ) throws -> RouteCredentialPlan {
        try plan(
            portalID: portalID,
            route: route,
            method: method,
            credential: credential,
            remoteApproval: approval,
            operationID: operationID
        )
    }

    /// Convenience overload for a caller whose operation identifier is carried
    /// by the exact approval record itself.
    func plan(
        portalID: PortalID,
        route: FleetRoute,
        method: HTTPMethod,
        credential: CredentialKind? = nil,
        approval: RemoteOperationApproval
    ) throws -> RouteCredentialPlan {
        try plan(
            portalID: portalID,
            route: route,
            method: method,
            credential: credential,
            remoteApproval: approval,
            operationID: approval.operationID
        )
    }
}

/// Categories used when stale/imported/future workflow state reaches the
/// application boundary. None of these categories can be converted to a
/// `FleetRoute` or a transport request.
enum ExcludedOperationKind: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case apps
    case config
    case fileSystem
    case logcat
    case install
    case update
    case developerMode
    case diagnostics
    case remoteUI
    case remoteInput
    case remoteMedia
    case remotePreset
    case remoteRoster
    case remoteDevice
    case reboot
    case shell
    case broadServerAdministration
    case arbitraryRoute
    case futureRoute
    case importedIntent

    var localExplanation: String {
        switch self {
        case .apps:
            return "App listing is outside version-one Portal Manager scope."
        case .config:
            return "Raw configuration writes are outside version-one Portal Manager scope."
        case .fileSystem:
            return "Raw file access is outside version-one Portal Manager scope."
        case .logcat:
            return "Logcat access is outside version-one Portal Manager scope."
        case .install, .update:
            return "Ad hoc installation and update controls are outside version-one scope."
        case .developerMode:
            return "Developer-mode control is outside version-one Portal Manager scope."
        case .diagnostics:
            return "Raw diagnostics access is outside version-one Portal Manager scope."
        case .remoteUI, .remoteInput, .remoteMedia, .remotePreset,
             .remoteRoster, .remoteDevice:
            return "This remote control route is outside version-one Portal Manager scope."
        case .reboot:
            return "Reboot control is outside version-one Portal Manager scope."
        case .shell:
            return "Shell access is outside version-one Portal Manager scope."
        case .broadServerAdministration:
            return "Broad server administration is outside version-one Portal Manager scope."
        case .arbitraryRoute:
            return "Arbitrary Fleet routes are outside version-one Portal Manager scope."
        case .futureRoute:
            return "Future Fleet routes require explicit product approval before use."
        case .importedIntent:
            return "The imported Fleet intent is not an approved version-one operation."
        }
    }
}

/// An untrusted workflow intent that must be explained and discarded at the
/// exclusion boundary. A raw route string is accepted only for classification;
/// it is intentionally not retained and cannot become a request path.
struct ExcludedOperationIntent: Codable, Sendable, Equatable, Hashable {
    let kind: ExcludedOperationKind

    init(kind: ExcludedOperationKind) {
        self.kind = kind
    }

    init(route: String, operationID: String? = nil) {
        // Imported strings are deliberately not echoed or retained. They are
        // never passed to a URL builder, logger, or transport.
        _ = route
        _ = operationID
        self.kind = .importedIntent
    }

    init(importedRoute: String, operationID: String? = nil) {
        self.init(route: importedRoute, operationID: operationID)
    }

    init(futureRoute: String, operationID: String? = nil) {
        _ = futureRoute
        _ = operationID
        self.kind = .futureRoute
    }

    static func imported(
        route: String,
        operationID: String? = nil
    ) -> Self {
        Self(route: route, operationID: operationID)
    }

    static func future(
        route: String,
        operationID: String? = nil
    ) -> Self {
        Self(futureRoute: route, operationID: operationID)
    }
}

enum OperationExclusionOutcome: String, Codable, Sendable, Equatable, Hashable {
    case continueWorkflow
}

/// The local result of handling an excluded intent. The explicit invariants
/// make it straightforward for UI/application callers and tests to prove that
/// no request was emitted and the managed device was not changed.
struct OperationExclusionResult: Codable, Sendable, Equatable, Hashable {
    let explanation: String
    let outcome: OperationExclusionOutcome
    let requestEmitted: Bool
    let deviceChanged: Bool

    var shouldContinue: Bool {
        outcome == .continueWorkflow
    }

    var continuation: OperationExclusionOutcome {
        outcome
    }
}

typealias OperationExclusionDecision = OperationExclusionResult

/// Pure local exclusion gate. It has no transport or persistence dependency,
/// so evaluating an excluded intent cannot send a request or mutate a Portal.
struct OperationExclusionGate: Sendable {
    init() {}

    func evaluate(_ intent: ExcludedOperationIntent) -> OperationExclusionResult {
        OperationExclusionResult(
            explanation: "\(intent.kind.localExplanation) No request was sent and the Portal was left unchanged.",
            outcome: .continueWorkflow,
            requestEmitted: false,
            deviceChanged: false
        )
    }

    func handle(_ intent: ExcludedOperationIntent) -> OperationExclusionResult {
        evaluate(intent)
    }

    func evaluate(
        route: String,
        operationID: String? = nil
    ) -> OperationExclusionResult {
        evaluate(ExcludedOperationIntent(route: route, operationID: operationID))
    }
}
