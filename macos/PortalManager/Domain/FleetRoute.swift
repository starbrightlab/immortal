/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The only HTTP methods the approved Fleet surface can construct.
///
/// A raw method string is intentionally not representable here. Route-specific
/// method validation is performed by `OperationPlanner` before a plan can be
/// handed to a transport adapter.
enum HTTPMethod: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case get = "GET"
    case post = "POST"
}

/// The credential assurance required by an approved Fleet route.
///
/// `approvedRemoteSession` is not sufficient by itself to create a plan: an
/// exact `RemoteOperationApproval` must also bind the selected Portal, route,
/// method, and operation identifier.
enum CredentialRequirement: String, Codable, Sendable, Equatable, Hashable {
    case none
    case verifiedBearer
    case approvedRemoteSession
    case verifiedBearerOrApprovedRemoteSession

    /// Compatibility aliases keep call sites descriptive without introducing
    /// additional credential kinds or an open-ended requirement value.
    static var bearer: Self { .verifiedBearer }
    static var remoteSession: Self { .approvedRemoteSession }
    static var bearerOrRemoteSession: Self { .verifiedBearerOrApprovedRemoteSession }

    func permits(
        _ credential: CredentialKind,
        hasExactRemoteApproval: Bool = false
    ) -> Bool {
        switch self {
        case .none:
            return false
        case .verifiedBearer:
            return credential == .verifiedBearer
        case .approvedRemoteSession:
            return credential == .remoteSession && hasExactRemoteApproval
        case .verifiedBearerOrApprovedRemoteSession:
            return credential == .verifiedBearer
                || (credential == .remoteSession && hasExactRemoteApproval)
        }
    }
}

/// Closed representation of the Fleet routes admitted by the macOS product.
///
/// There is deliberately no string path initializer, wildcard route, generic
/// remote operation, arbitrary body, or arbitrary action case. Excluded and
/// future imported intents are handled by `OperationExclusionGate` instead of
/// being converted into this type.
enum FleetRoute: Codable, Sendable, Equatable, Hashable {
    case info
    case apps
    case appProfiles
    case install
    case update
    case remotePair
    case remoteSettings
    case remoteSources
    case remoteApps
    case remoteMedia
    case remoteVolume
    case remoteLaunch
    case screensaver
    case calendar
    case action(ApprovedAction)

    var path: String {
        switch self {
        case .info:
            return "/info"
        case .apps:
            return "/apps"
        case .appProfiles:
            return "/apps/profile"
        case .install:
            return "/install"
        case .update:
            return "/update"
        case .remotePair:
            return "/remote/pair"
        case .remoteSettings:
            return "/remote/settings"
        case .remoteSources:
            return "/remote/sources"
        case .remoteApps:
            return "/remote/apps"
        case .remoteMedia:
            return "/remote/media"
        case .remoteVolume:
            return "/remote/volume"
        case .remoteLaunch:
            return "/remote/launch"
        case .screensaver:
            return "/screensaver"
        case .calendar:
            return "/calendar"
        case .action:
            return "/action"
        }
    }

    var allowedMethods: Set<HTTPMethod> {
        switch self {
        case .info, .apps:
            return [.get]
        case .appProfiles:
            return [.get, .post]
        case .install, .update:
            return [.post]
        case .remotePair:
            return [.post]
        case .remoteSettings, .remoteSources, .remoteApps,
             .remoteMedia, .remoteVolume, .remoteLaunch,
             .screensaver, .calendar:
            return [.get, .post]
        case .action:
            return [.post]
        }
    }

    var credentialRequirement: CredentialRequirement {
        switch self {
        case .remotePair:
            return .none
        case .info, .apps, .screensaver, .calendar, .action:
            return .verifiedBearer
        case .install, .update, .appProfiles,
             .remoteSettings, .remoteSources, .remoteApps,
             .remoteMedia, .remoteVolume, .remoteLaunch:
            return .verifiedBearerOrApprovedRemoteSession
        }
    }

    var supportsRemoteSession: Bool {
        switch self {
        case .install, .update, .appProfiles,
             .remoteSettings, .remoteSources, .remoteApps,
             .remoteMedia, .remoteVolume, .remoteLaunch:
            return true
        case .apps, .info, .remotePair, .screensaver, .calendar, .action:
            return false
        }
    }

    /// A non-secret operation label for diagnostics and route-level policy.
    /// Settings/source session approvals carry their more specific operation ID
    /// separately; this value is never serialized into a request URL or body.
    var operationID: String {
        switch self {
        case .info:
            return "info"
        case .apps:
            return "apps"
        case .appProfiles:
            return "app.profiles"
        case .install:
            return "install"
        case .update:
            return "update"
        case .remotePair:
            return "remote.pair"
        case .remoteSettings:
            return "remote.settings"
        case .remoteSources:
            return "remote.sources"
        case .remoteApps:
            return "remote.apps"
        case .remoteMedia:
            return "remote.media"
        case .remoteVolume:
            return "remote.volume"
        case .remoteLaunch:
            return "remote.launch"
        case .screensaver:
            return "screensaver"
        case .calendar:
            return "calendar"
        case .action(let action):
            return action.rawValue
        }
    }
}

/// The only action values that can be sent to `POST /action`.
enum ApprovedAction: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case identify
    case reaffirm
}

/// A scoped product approval for a remote-session settings/source operation.
///
/// The operation ID is intentionally an opaque, non-secret policy identifier,
/// not a route or request body. `OperationPlanner` requires it to match the
/// operation requested by the caller exactly and rejects empty identifiers.
struct RemoteOperationApproval: Codable, Sendable, Equatable, Hashable {
    var portalID: PortalID
    var route: FleetRoute
    var method: HTTPMethod
    var operationID: String
    var approvedAt: Date

    init(
        portalID: PortalID,
        route: FleetRoute,
        method: HTTPMethod,
        operationID: String,
        approvedAt: Date
    ) {
        self.portalID = portalID
        self.route = route
        self.method = method
        self.operationID = operationID
        self.approvedAt = approvedAt
    }

    var hasNonEmptyOperationID: Bool {
        !operationID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func matches(
        portalID requestedPortalID: PortalID,
        route requestedRoute: FleetRoute,
        method requestedMethod: HTTPMethod,
        operationID requestedOperationID: String
    ) -> Bool {
        self.portalID == requestedPortalID
            && self.route == requestedRoute
            && self.method == requestedMethod
            && self.operationID == requestedOperationID
            && hasNonEmptyOperationID
    }

    /// `true` only when the approval is structurally usable for the session
    /// scope. The planner still performs the caller/approval exact-match check.
    var isStructurallyValid: Bool {
        route.supportsRemoteSession
            && route.allowedMethods.contains(method)
            && hasNonEmptyOperationID
    }
}

/// A fully validated route/credential selection ready for a typed Fleet
/// transport adapter. It contains no credential bytes and no arbitrary body.
struct RouteCredentialPlan: Codable, Sendable, Equatable, Hashable {
    var method: HTTPMethod
    var route: FleetRoute
    var credential: CredentialKind?
    var remoteApproval: RemoteOperationApproval?

    init(
        method: HTTPMethod,
        route: FleetRoute,
        credential: CredentialKind?,
        remoteApproval: RemoteOperationApproval? = nil
    ) {
        self.method = method
        self.route = route
        self.credential = credential
        self.remoteApproval = remoteApproval
    }

    /// The route-level matrix requirement. The selected `credential` and
    /// `remoteApproval` are the result of planning, not proof of authentication.
    var credentialRequirement: CredentialRequirement {
        route.credentialRequirement
    }

    var selectedCredential: CredentialKind? {
        credential
    }

    var operationID: String? {
        remoteApproval?.operationID
    }
}
