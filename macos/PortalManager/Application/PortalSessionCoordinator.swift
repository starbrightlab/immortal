/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Sanitized lifecycle events emitted by the session coordinator. Events carry
/// only local identifiers, typed route/credential metadata, and stable error
/// categories; credential values and protocol payloads never cross this port.
enum PortalSessionEvent: Sendable, Equatable {
    case bearerVerificationStarted(portalID: PortalID)
    case bearerVerificationSucceeded(portalID: PortalID)
    case pairingStarted(portalID: PortalID)
    case pairingSucceeded(portalID: PortalID)
    case pairingFailed(portalID: PortalID, reason: PairingReason)
    case operationStarted(
        portalID: PortalID,
        route: FleetRoute,
        credential: CredentialKind
    )
    case operationSucceeded(
        portalID: PortalID,
        route: FleetRoute,
        credential: CredentialKind
    )
    case credentialSuppressed(
        portalID: PortalID,
        credential: CredentialKind,
        category: ManagerErrorCategory
    )
    case reauthenticationSucceeded(portalID: PortalID)
    case removed(portalID: PortalID)
}

/// Event boundary used by the application shell and deterministic tests.
protocol PortalSessionEventSink: Sendable {
    func record(_ event: PortalSessionEvent) async
}

struct NoopPortalSessionEventSink: PortalSessionEventSink, Sendable {
    func record(_ event: PortalSessionEvent) async {}
}

/// A non-secret view of one Portal's session state. Credential values are never
/// represented; availability is derived only from opaque registry references.
struct PortalSessionSnapshot: Sendable, Equatable {
    let portalID: PortalID
    let availableCredentialKinds: Set<CredentialKind>
    let selectedCredential: CredentialKind?
    let suppressedCredentialKinds: Set<CredentialKind>
    let connectionState: ConnectionState?

    var availableCredentials: Set<CredentialKind> {
        availableCredentialKinds
    }

    var activeCredential: CredentialKind? {
        selectedCredential
    }
}

/// A typed operation request consumed by `PortalSessionCoordinator`. The route,
/// method, credential kind, and optional remote approval are all closed values;
/// arbitrary Fleet paths and credentials cannot be represented here.
struct PortalSessionOperation: Sendable, Equatable {
    let portalID: PortalID
    let admissionRequest: ConnectionAdmissionRequest
    let route: FleetRoute
    let method: HTTPMethod
    let selectedCredential: CredentialKind?
    let operationID: String?
    let remoteApproval: RemoteOperationApproval?
    let body: FleetRequestBody
    let expectedAppliedKeys: [String]

    init(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        route: FleetRoute,
        method: HTTPMethod,
        credential: CredentialKind? = nil,
        operationID: String? = nil,
        remoteApproval: RemoteOperationApproval? = nil,
        body: FleetRequestBody = .none,
        expectedAppliedKeys: [String] = []
    ) {
        self.portalID = portalID
        self.admissionRequest = admissionRequest
        self.route = route
        self.method = method
        selectedCredential = credential
        self.operationID = operationID
        self.remoteApproval = remoteApproval
        self.body = body
        self.expectedAppliedKeys = Array(Set(expectedAppliedKeys)).sorted()
    }

    var credential: CredentialKind? {
        selectedCredential
    }
}

typealias PortalFleetOperation = PortalSessionOperation

/// The result of a successful pairing operation. The Keychain reference is
/// intentionally returned instead of the session token itself.
struct PortalSessionPairingResult: Sendable, Equatable {
    let portalID: PortalID
    let credentialReference: CredentialReference
    let pairedAt: Date
    let connectionState: ConnectionState?
    let replacedExistingSession: Bool
}

/// Coordinates credential assurance and Portal-scoped Fleet operations. The
/// actor serializes pairing/replacement decisions, suppression state, and
/// per-Portal credential selection while leaving admission, transport, and
/// credential reads to their injected typed boundaries.
actor PortalSessionCoordinator {
    private let fleetClient: FleetHTTPClient
    private let registryCoordinator: PortalRegistryCoordinator
    private let credentialStore: any CredentialStore
    private let secureInputStore: any SecureInputStore
    private let clock: any ManagerClock
    private let planner: OperationPlanner
    private let eventSink: any PortalSessionEventSink

    private var selectedCredentials: [PortalID: CredentialKind] = [:]
    private var suppressedCredentials: [PortalID: Set<CredentialKind>] = [:]
    private var pairingInFlight: Set<PortalID> = []

    init(
        fleetClient: FleetHTTPClient,
        registryCoordinator: PortalRegistryCoordinator,
        credentialStore: any CredentialStore,
        secureInputStore: any SecureInputStore = TransientSecureInputStore(),
        clock: any ManagerClock = SystemManagerClock(),
        planner: OperationPlanner = OperationPlanner(),
        eventSink: any PortalSessionEventSink = NoopPortalSessionEventSink()
    ) {
        self.fleetClient = fleetClient
        self.registryCoordinator = registryCoordinator
        self.credentialStore = credentialStore
        self.secureInputStore = secureInputStore
        self.clock = clock
        self.planner = planner
        self.eventSink = eventSink
    }

    /// Returns only non-secret credential availability and assurance state for
    /// one Portal. Selection is stored per Portal rather than globally.
    func snapshot(for portalID: PortalID) async throws -> PortalSessionSnapshot {
        let entry = try await registryEntry(for: portalID)
        let available = entry.map(availableCredentialKinds(in:)) ?? []
        let persistedSuppression = entry.map(persistedSuppressedKinds(in:)) ?? []
        let suppressed = (suppressedCredentials[portalID] ?? []).union(persistedSuppression)
        return PortalSessionSnapshot(
            portalID: portalID,
            availableCredentialKinds: available,
            selectedCredential: selectedCredentials[portalID],
            suppressedCredentialKinds: suppressed,
            connectionState: entry?.connectionState
        )
    }

    /// Selects one Portal credential without exposing its value. Selection is
    /// validated against that Portal's opaque references and suppression state.
    func selectCredential(
        _ credential: CredentialKind,
        for portalID: PortalID
    ) async throws {
        guard credential == .verifiedBearer || credential == .remoteSession else {
            throw ManagerError.authentication(.invalidCredential)
        }
        guard let entry = try await registryEntry(for: portalID) else {
            throw ManagerError.authentication(.missingCredential)
        }
        let reference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: credential
        )
        guard entry.credentialReferences.contains(reference) else {
            throw ManagerError.authentication(.missingCredential)
        }
        guard !isSuppressed(credential, portalID: portalID, entry: entry) else {
            throw ManagerError.authentication(.revokedCredential)
        }
        selectedCredentials[portalID] = credential
    }

    /// Labelled compatibility spelling for application call sites.
    func selectCredential(
        for portalID: PortalID,
        credential: CredentialKind
    ) async throws {
        try await selectCredential(credential, for: portalID)
    }

    /// Verifies the exact Portal-scoped bearer reference with an authenticated
    /// `/info` request. No request is constructed when the caller omits the
    /// reference or supplies a reference for another Portal/credential kind.
    /// A caller that has already completed an admission attempt may provide
    /// that admitted endpoint so manual onboarding records the exact `.manual`
    /// target rather than trusting an unvalidated address reported by `/info`.
    @discardableResult
    func verifyBearer(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        credentialReference: CredentialReference?,
        authenticatedEndpoint: LANEndpoint? = nil
    ) async throws -> PortalInfoClassification {
        let reference = try exactReference(
            credentialReference,
            portalID: portalID,
            kind: .verifiedBearer
        )
        await eventSink.record(.bearerVerificationStarted(portalID: portalID))

        do {
            let classification = try await performBearerVerification(
                portalID: portalID,
                admissionRequest: admissionRequest,
                credentialReference: reference,
                client: fleetClient
            )
            let verifiedAt = clock.now
            var endpoint: LANEndpoint?
            if var authenticatedEndpoint {
                authenticatedEndpoint.lastAuthenticatedAt = verifiedAt
                endpoint = authenticatedEndpoint
            } else if var reportedEndpoint = classification.endpoint {
                reportedEndpoint.lastAuthenticatedAt = verifiedAt
                endpoint = reportedEndpoint
            } else {
                var fallbackEndpoint = admissionRequest.endpoint
                fallbackEndpoint.source = .authenticatedRefresh
                fallbackEndpoint.lastAuthenticatedAt = verifiedAt
                endpoint = fallbackEndpoint
            }
            let record = AuthenticatedPortalRecord(
                identity: classification.identity,
                endpoint: endpoint,
                capabilities: classification.capabilities,
                status: classification.status,
                policyMetadata: classification.policyMetadata,
                credentialReferences: [reference],
                verifiedAt: verifiedAt
            )
            _ = try await registryCoordinator.reconcile(record)
            suppressedCredentials[portalID, default: []].remove(.verifiedBearer)
            selectedCredentials[portalID] = .verifiedBearer
            await eventSink.record(.bearerVerificationSucceeded(portalID: portalID))
            return classification
        } catch {
            if isCredentialFailure(error) {
                await suppress(
                    portalID: portalID,
                    credential: .verifiedBearer,
                    error: error
                )
            }
            throw error
        }
    }

    /// Convenience overload for callers that already have a Portal endpoint.
    @discardableResult
    func verifyBearer(
        portalID: PortalID,
        endpoint: LANEndpoint,
        credentialReference: CredentialReference?
    ) async throws -> PortalInfoClassification {
        try await verifyBearer(
            portalID: portalID,
            admissionRequest: ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            ),
            credentialReference: credentialReference
        )
    }

    /// Creates an operation-local secure input, then clears it on every exit.
    @discardableResult
    func pair(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        pin: String,
        allowReplacement: Bool = false
    ) async throws -> PortalSessionPairingResult {
        let input = secureInputStore.makeReference(from: pin)
        defer { secureInputStore.clear(input) }
        return try await redeemPairing(
            portalID: portalID,
            admissionRequest: admissionRequest,
            input: input,
            allowReplacement: allowReplacement
        )
    }

    /// Redeems one active-operation secure PIN input exactly once. The input is
    /// cleared before this method returns, including cancellation and failure.
    @discardableResult
    func pair(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        pin: SecureInputRef,
        allowReplacement: Bool = false
    ) async throws -> PortalSessionPairingResult {
        defer { secureInputStore.clear(pin) }
        return try await redeemPairing(
            portalID: portalID,
            admissionRequest: admissionRequest,
            input: pin,
            allowReplacement: allowReplacement
        )
    }

    /// Convenience overload for callers that already have a Portal endpoint.
    @discardableResult
    func pair(
        portalID: PortalID,
        endpoint: LANEndpoint,
        pin: String,
        allowReplacement: Bool = false
    ) async throws -> PortalSessionPairingResult {
        try await pair(
            portalID: portalID,
            admissionRequest: ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            ),
            pin: pin,
            allowReplacement: allowReplacement
        )
    }

    /// Convenience secure-input overload that retains the same active-operation
    /// clearing guarantees as the admission-request form.
    @discardableResult
    func pair(
        portalID: PortalID,
        endpoint: LANEndpoint,
        pin: SecureInputRef,
        allowReplacement: Bool = false
    ) async throws -> PortalSessionPairingResult {
        try await pair(
            portalID: portalID,
            admissionRequest: ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            ),
            pin: pin,
            allowReplacement: allowReplacement
        )
    }

    /// Explicit replacement spelling for a suppressed/revoked remote session.
    @discardableResult
    func replaceRemoteSession(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        pin: SecureInputRef
    ) async throws -> PortalSessionPairingResult {
        try await pair(
            portalID: portalID,
            admissionRequest: admissionRequest,
            pin: pin,
            allowReplacement: true
        )
    }

    /// Executes one planner-approved operation using the credential selected for
    /// this Portal. A remote session is never substituted into a bearer-only
    /// route, and an explicitly selected suppressed credential is never silently
    /// replaced by another kind.
    func execute(
        _ operation: PortalSessionOperation
    ) async throws -> FleetHTTPClientResponse {
        let selectedCredential: CredentialKind
        if let suppliedCredential = operation.selectedCredential {
            selectedCredential = suppliedCredential
        } else {
            selectedCredential = try defaultCredential(for: operation)
        }
        let plan: RouteCredentialPlan
        do {
            plan = try planner.plan(
                portalID: operation.portalID,
                route: operation.route,
                method: operation.method,
                credential: selectedCredential,
                remoteApproval: operation.remoteApproval,
                operationID: operation.operationID
            )
        } catch let error as OperationPlanningError {
            throw error.managerError
        }
        guard let credential = plan.credential else {
            // The only no-credential operation is pairing, which has its own
            // exact-once entry point and typed PIN body.
            throw ManagerError.validation(
                field: "Fleet operation",
                reason: "The operation requires the Portal session coordinator."
            )
        }

        guard let entry = try await registryEntry(for: operation.portalID) else {
            throw ManagerError.authentication(.missingCredential)
        }
        let reference = CredentialReference.portalCredential(
            portalID: operation.portalID,
            kind: credential
        )
        guard entry.credentialReferences.contains(reference) else {
            throw ManagerError.authentication(.missingCredential)
        }
        guard !isSuppressed(
            credential,
            portalID: operation.portalID,
            entry: entry
        ) else {
            throw ManagerError.authentication(.revokedCredential)
        }

        let request = FleetHTTPClientRequest(
            portalID: operation.portalID,
            admissionRequest: operation.admissionRequest,
            routePlan: plan,
            credentialReference: reference,
            body: operation.body,
            expectedAppliedKeys: operation.expectedAppliedKeys
        )
        await eventSink.record(
            .operationStarted(
                portalID: operation.portalID,
                route: operation.route,
                credential: credential
            )
        )

        do {
            let response = try await fleetClient.execute(request)
            if credential == .remoteSession {
                _ = try await registryCoordinator.recordRemoteSessionRead(
                    for: operation.portalID,
                    readAt: clock.now
                )
                suppressedCredentials[operation.portalID, default: []]
                    .remove(.remoteSession)
            }
            selectedCredentials[operation.portalID] = credential
            await eventSink.record(
                .operationSucceeded(
                    portalID: operation.portalID,
                    route: operation.route,
                    credential: credential
                )
            )
            return response
        } catch {
            if isCredentialFailure(error) {
                await suppress(
                    portalID: operation.portalID,
                    credential: credential,
                    error: error
                )
            }
            throw error
        }
    }

    /// Convenience operation entry point that keeps route/credential choice out
    /// of UI callers while retaining a typed body for approved POST operations.
    func execute(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        route: FleetRoute,
        method: HTTPMethod,
        credential: CredentialKind? = nil,
        operationID: String? = nil,
        remoteApproval: RemoteOperationApproval? = nil,
        body: FleetRequestBody = .none,
        expectedAppliedKeys: [String] = []
    ) async throws -> FleetHTTPClientResponse {
        try await execute(
            PortalSessionOperation(
                portalID: portalID,
                admissionRequest: admissionRequest,
                route: route,
                method: method,
                credential: credential,
                operationID: operationID,
                remoteApproval: remoteApproval,
                body: body,
                expectedAppliedKeys: expectedAppliedKeys
            )
        )
    }

    /// Verifies a replacement bearer entirely against a temporary in-memory
    /// credential store. The persistent Keychain item is written only after the
    /// new token has successfully authenticated `/info`.
    @discardableResult
    func reauthenticateBearer(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        token: SecureInputRef
    ) async throws -> PortalInfoClassification {
        defer { secureInputStore.clear(token) }
        return try await reauthenticateBearerValue(
            portalID: portalID,
            admissionRequest: admissionRequest,
            token: token
        )
    }

    @discardableResult
    private func reauthenticateBearerValue(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        token: SecureInputRef
    ) async throws -> PortalInfoClassification {
        var replacement = try token.withData { data -> Data in
            guard !data.isEmpty,
                  let value = String(data: data, encoding: .utf8),
                  !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw ManagerError.authentication(.invalidCredential)
            }
            return Data(data)
        }
        defer { wipe(&replacement) }

        let reference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let temporaryStore = OperationCredentialStore(value: replacement)
        let verificationClient = fleetClient.withCredentialStore(temporaryStore)

        do {
            let classification = try await performBearerVerification(
                portalID: portalID,
                admissionRequest: admissionRequest,
                credentialReference: reference,
                client: verificationClient
            )
            let previousValue = try await readExistingCredential(reference)
            try await writeCredential(replacement, for: reference)

            do {
                let verifiedAt = clock.now
                var endpoint = classification.endpoint
                if endpoint == nil {
                    var authenticatedEndpoint = admissionRequest.endpoint
                    authenticatedEndpoint.source = .authenticatedRefresh
                    authenticatedEndpoint.lastAuthenticatedAt = verifiedAt
                    endpoint = authenticatedEndpoint
                }
                _ = try await registryCoordinator.reconcile(
                    AuthenticatedPortalRecord(
                        identity: classification.identity,
                        endpoint: endpoint,
                        capabilities: classification.capabilities,
                        status: classification.status,
                        policyMetadata: classification.policyMetadata,
                        credentialReferences: [reference],
                        verifiedAt: verifiedAt
                    )
                )
            } catch {
                try await rollbackCredential(
                    previousValue,
                    for: reference
                )
                throw error
            }

            suppressedCredentials[portalID, default: []].remove(.verifiedBearer)
            selectedCredentials[portalID] = .verifiedBearer
            await eventSink.record(.reauthenticationSucceeded(portalID: portalID))
            return classification
        } catch {
            if isCredentialFailure(error) {
                await suppress(
                    portalID: portalID,
                    credential: .verifiedBearer,
                    error: error
                )
            }
            throw error
        }
    }

    @discardableResult
    func reauthenticateBearer(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        token: String
    ) async throws -> PortalInfoClassification {
        let input = secureInputStore.makeReference(from: token)
        defer { secureInputStore.clear(input) }
        return try await reauthenticateBearerValue(
            portalID: portalID,
            admissionRequest: admissionRequest,
            token: input
        )
    }

    @discardableResult
    func reauthenticateBearer(
        portalID: PortalID,
        endpoint: LANEndpoint,
        token: String
    ) async throws -> PortalInfoClassification {
        try await reauthenticateBearer(
            portalID: portalID,
            admissionRequest: ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            ),
            token: token
        )
    }

    @discardableResult
    func reauthenticateBearer(
        portalID: PortalID,
        endpoint: LANEndpoint,
        token: SecureInputRef
    ) async throws -> PortalInfoClassification {
        try await reauthenticateBearer(
            portalID: portalID,
            admissionRequest: ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            ),
            token: token
        )
    }

    /// Deletes the Portal's unshared opaque credentials through the registry
    /// boundary and clears only local coordinator state after persistence has
    /// succeeded. Registry/keychain failures therefore remain retryable.
    @discardableResult
    func remove(
        portalID: PortalID
    ) async throws -> PortalRegistryRemoval? {
        let removal = try await registryCoordinator.remove(portalID)
        selectedCredentials.removeValue(forKey: portalID)
        suppressedCredentials.removeValue(forKey: portalID)
        pairingInFlight.remove(portalID)
        if removal != nil {
            await eventSink.record(.removed(portalID: portalID))
        }
        return removal
    }

    private func redeemPairing(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        input: SecureInputRef,
        allowReplacement: Bool
    ) async throws -> PortalSessionPairingResult {
        let pairingPIN: PairingPIN
        do {
            pairingPIN = try input.withData { data in
                try PairingPIN(String(decoding: data, as: UTF8.self))
            }
        } catch {
            throw error
        }

        guard !pairingInFlight.contains(portalID) else {
            throw ManagerError.pairing(.alreadyRedeemed)
        }
        pairingInFlight.insert(portalID)
        defer { pairingInFlight.remove(portalID) }

        guard let entry = try await registryEntry(for: portalID) else {
            throw ManagerError.validation(
                field: "Portal registry",
                reason: "The Portal must be registered before pairing."
            )
        }

        let reference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let hasExistingSession = entry.credentialReferences.contains(reference)
        let existingSessionIsSuppressed = isSuppressed(
            .remoteSession,
            portalID: portalID,
            entry: entry
        )
        guard !hasExistingSession || (allowReplacement && existingSessionIsSuppressed) else {
            throw ManagerError.pairing(.alreadyRedeemed)
        }

        await eventSink.record(.pairingStarted(portalID: portalID))
        let response: FleetHTTPClientResponse
        do {
            response = try await fleetClient.pair(
                portalID: portalID,
                admissionRequest: admissionRequest,
                pin: pairingPIN
            )
        } catch {
            let mapped = mapPairingError(error)
            if case let ManagerError.pairing(reason) = mapped {
                await eventSink.record(
                    .pairingFailed(portalID: portalID, reason: reason)
                )
            }
            throw mapped
        }

        let token = try pairingToken(from: response.payload)
        var tokenData = Data(token.utf8)
        defer { wipe(&tokenData) }

        let pairedAt = clock.now
        let previousValue: Data?
        if hasExistingSession {
            previousValue = try await readExistingCredential(reference)
        } else {
            // Initial pairing has no credential input. Avoid reading a
            // Keychain item for a reference that does not yet exist.
            previousValue = nil
        }
        try await writeCredential(tokenData, for: reference)
        do {
            guard let updatedEntry = try await registryCoordinator.recordRemoteSession(
                for: portalID,
                credentialReference: reference,
                pairedAt: pairedAt
            ) else {
                throw ManagerError.validation(
                    field: "Portal registry",
                    reason: "The Portal registry entry could not be updated."
                )
            }
            suppressedCredentials[portalID, default: []].remove(.remoteSession)
            selectedCredentials[portalID] = .remoteSession
            await eventSink.record(.pairingSucceeded(portalID: portalID))
            return PortalSessionPairingResult(
                portalID: portalID,
                credentialReference: reference,
                pairedAt: pairedAt,
                connectionState: updatedEntry.connectionState,
                replacedExistingSession: hasExistingSession
            )
        } catch {
            try await rollbackCredential(previousValue, for: reference)
            throw error
        }
    }

    private func performBearerVerification(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        credentialReference: CredentialReference,
        client: FleetHTTPClient
    ) async throws -> PortalInfoClassification {
        let plan = try planner.plan(
            portalID: portalID,
            route: .info,
            method: .get,
            credential: .verifiedBearer
        )
        let request = FleetHTTPClientRequest(
            portalID: portalID,
            admissionRequest: admissionRequest,
            routePlan: plan,
            credentialReference: credentialReference
        )
        let response = try await client.execute(request)
        let info = try decodeInfo(from: response.payload)
        guard !info.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !info.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ManagerError.validation(
                field: "Portal identity",
                reason: "The authenticated Portal identity is incomplete."
            )
        }
        return PortalInfoClassifier.classify(
            info,
            portalID: portalID,
            endpointSource: .authenticatedRefresh
        )
    }

    private func decodeInfo(from payload: JSONValue) throws -> AuthenticatedPortalInfo {
        do {
            let data = try JSONEncoder().encode(payload)
            return try JSONDecoder().decode(AuthenticatedPortalInfo.self, from: data)
        } catch {
            throw ManagerError.validation(
                field: "Fleet response",
                reason: "The authenticated Portal information was not valid."
            )
        }
    }

    private func pairingToken(from payload: JSONValue) throws -> String {
        guard case let .object(fields) = payload,
              case let .string(token) = fields["token"],
              !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ManagerError.pairing(.requestFailed)
        }
        return token
    }

    private func defaultCredential(
        for operation: PortalSessionOperation
    ) throws -> CredentialKind {
        switch operation.route.credentialRequirement {
        case .verifiedBearer:
            return .verifiedBearer
        case .approvedRemoteSession, .verifiedBearerOrApprovedRemoteSession:
            if let selected = selectedCredentials[operation.portalID] {
                return selected
            }
            if operation.remoteApproval != nil {
                return .remoteSession
            }
            return .verifiedBearer
        case .none:
            throw ManagerError.validation(
                field: "Fleet operation",
                reason: "The operation does not accept a Portal credential."
            )
        }
    }

    private func exactReference(
        _ supplied: CredentialReference?,
        portalID: PortalID,
        kind: CredentialKind
    ) throws -> CredentialReference {
        guard let supplied else {
            throw ManagerError.authentication(.missingCredential)
        }
        let expected = CredentialReference.portalCredential(
            portalID: portalID,
            kind: kind
        )
        guard supplied == expected else {
            throw ManagerError.authentication(.invalidCredential)
        }
        return expected
    }

    private func registryEntry(
        for portalID: PortalID
    ) async throws -> PortalRegistryEntry? {
        try await registryCoordinator.entries().first { $0.id == portalID }
    }

    private func availableCredentialKinds(
        in entry: PortalRegistryEntry
    ) -> Set<CredentialKind> {
        var kinds: Set<CredentialKind> = []
        for kind in [CredentialKind.verifiedBearer, .remoteSession] {
            let reference = CredentialReference.portalCredential(
                portalID: entry.id,
                kind: kind
            )
            if entry.credentialReferences.contains(reference) {
                kinds.insert(kind)
            }
        }
        return kinds
    }

    private func persistedSuppressedKinds(
        in entry: PortalRegistryEntry
    ) -> Set<CredentialKind> {
        guard case let .reauthenticationRequired(kind, _) = entry.connectionState else {
            return []
        }
        return [kind]
    }

    private func isSuppressed(
        _ credential: CredentialKind,
        portalID: PortalID,
        entry: PortalRegistryEntry
    ) -> Bool {
        suppressedCredentials[portalID]?.contains(credential) == true
            || persistedSuppressedKinds(in: entry).contains(credential)
    }

    private func suppress(
        portalID: PortalID,
        credential: CredentialKind,
        error: Error
    ) async {
        suppressedCredentials[portalID, default: []].insert(credential)
        let category = (error as? ManagerError)?.category ?? .authentication
        _ = try? await registryCoordinator.markReauthenticationRequired(
            for: portalID,
            kind: credential,
            reason: "The Portal credential requires authentication again."
        )
        await eventSink.record(
            .credentialSuppressed(
                portalID: portalID,
                credential: credential,
                category: category
            )
        )
    }

    private func isCredentialFailure(_ error: Error) -> Bool {
        guard let managerError = error as? ManagerError else { return false }
        guard case let .authentication(reason) = managerError else { return false }
        return reason == .unauthorized || reason == .revokedCredential
    }

    private func mapPairingError(_ error: Error) -> Error {
        if error is CancellationError {
            return error
        }
        guard let managerError = error as? ManagerError else {
            return ManagerError.pairing(.requestFailed)
        }
        switch managerError {
        case .authentication(.unauthorized):
            return ManagerError.pairing(.rejected)
        case let .http(status, _, _):
            switch status {
            case 409:
                return ManagerError.pairing(.alreadyRedeemed)
            case 410:
                return ManagerError.pairing(.expiredPIN)
            case 400...499:
                return ManagerError.pairing(.rejected)
            default:
                return managerError
            }
        case .validation:
            return ManagerError.pairing(.requestFailed)
        case .pairing:
            return managerError
        default:
            return managerError
        }
    }

    private func readExistingCredential(
        _ reference: CredentialReference
    ) async throws -> Data? {
        do {
            return try await credentialStore.read(reference)
        } catch let error as ManagerError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ManagerError.keychain(.readFailed)
        }
    }

    private func writeCredential(
        _ value: Data,
        for reference: CredentialReference
    ) async throws {
        do {
            try await credentialStore.write(value, for: reference)
        } catch let error as ManagerError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ManagerError.keychain(.writeFailed)
        }
    }

    private func rollbackCredential(
        _ previousValue: Data?,
        for reference: CredentialReference
    ) async throws {
        do {
            if let previousValue {
                try await credentialStore.write(previousValue, for: reference)
            } else {
                try await credentialStore.delete(reference)
            }
        } catch let error as ManagerError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ManagerError.keychain(.writeFailed)
        }
    }

    private func wipe(_ data: inout Data) {
        guard !data.isEmpty else { return }
        data.resetBytes(in: 0..<data.count)
        data.removeAll(keepingCapacity: false)
    }
}

/// A credential store that exists only for the duration of replacement-token
/// verification. It is never handed to registry persistence or UI state.
private actor OperationCredentialStore: CredentialStore {
    private var value: Data

    init(value: Data) {
        self.value = Data(value)
    }

    deinit {
        if !value.isEmpty {
            value.resetBytes(in: 0..<value.count)
            value.removeAll(keepingCapacity: false)
        }
    }

    func read(_ reference: CredentialReference) async throws -> Data? {
        Data(value)
    }

    func write(_ value: Data, for reference: CredentialReference) async throws {
        self.value.resetBytes(in: 0..<self.value.count)
        self.value.removeAll(keepingCapacity: false)
        self.value = Data(value)
    }

    func delete(_ reference: CredentialReference) async throws {
        value.resetBytes(in: 0..<value.count)
        value.removeAll(keepingCapacity: false)
    }
}
