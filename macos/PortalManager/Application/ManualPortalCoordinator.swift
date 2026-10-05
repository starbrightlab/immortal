/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The non-secret result of parsing, resolving, LAN-validating, and
/// trust-checking one operator-supplied Portal endpoint. The request handed to
/// a later session operation uses the admitted literal endpoint so the initial
/// DNS/LAN/trust sequence cannot be bypassed, while the admission boundary is
/// still repeated before transport.
struct ManualPortalAdmission: Sendable, Equatable, Hashable {
    let portalID: PortalID
    let request: ConnectionAdmissionRequest
    let connection: AdmittedConnection
    let hadExistingEntry: Bool

    var endpoint: LANEndpoint {
        connection.endpoint
    }

    var trustScope: TrustWarningScope {
        connection.trustScope
    }
}

/// A typed result for a successful manual bearer verification. The registry
/// entry is returned only after `/info` succeeded and the admitted manual
/// endpoint was committed.
struct ManualPortalBearerResult: Sendable, Equatable, Hashable {
    let admission: ManualPortalAdmission
    let classification: PortalInfoClassification
    let entry: PortalRegistryEntry
}

/// A typed result for a successful manual PIN pairing. The session result keeps
/// the credential opaque; the registry entry exposes only remote-session
/// assurance and non-secret endpoint metadata.
struct ManualPortalPairingResult: Sendable, Equatable {
    let admission: ManualPortalAdmission
    let pairing: PortalSessionPairingResult
    let entry: PortalRegistryEntry
}

/// Sanitized lifecycle events for manual onboarding and endpoint editing. No
/// event carries the raw input, PIN, bearer, session token, response body, or
/// transport error text.
enum ManualPortalEvent: Sendable, Equatable {
    case endpointAdmitted(
        portalID: PortalID,
        endpoint: LANEndpoint,
        existingEntry: Bool
    )
    case trustWarningRequired(
        portalID: PortalID?,
        scope: TrustWarningScope
    )
    case bearerVerificationSucceeded(portalID: PortalID)
    case pairingSucceeded(portalID: PortalID)
    case operationFailed(
        portalID: PortalID?,
        category: ManagerErrorCategory
    )
}

protocol ManualPortalEventSink: Sendable {
    func record(_ event: ManualPortalEvent) async
}

struct NoopManualPortalEventSink: ManualPortalEventSink, Sendable {
    func record(_ event: ManualPortalEvent) async {}
}

/// Coordinates manual Portal-IP onboarding and endpoint editing without making
/// Bonjour a prerequisite. It deliberately delegates admission, bearer
/// verification, PIN redemption, Keychain access, and registry persistence to
/// the existing typed boundaries.
actor ManualPortalCoordinator {
    private static let portalProtocol = "http"

    private let connectionAdmission: ConnectionAdmission
    private let sessionCoordinator: PortalSessionCoordinator
    private let registryCoordinator: PortalRegistryCoordinator
    private let clock: any ManagerClock
    private let eventSink: any ManualPortalEventSink

    init(
        connectionAdmission: ConnectionAdmission,
        sessionCoordinator: PortalSessionCoordinator,
        registryCoordinator: PortalRegistryCoordinator,
        clock: any ManagerClock = SystemManagerClock(),
        eventSink: any ManualPortalEventSink = NoopManualPortalEventSink()
    ) {
        self.connectionAdmission = connectionAdmission
        self.sessionCoordinator = sessionCoordinator
        self.registryCoordinator = registryCoordinator
        self.clock = clock
        self.eventSink = eventSink
    }

    /// Convenience composition-root initializer. The session coordinator is
    /// still the only owner of credential selection, pairing exact-once state,
    /// and Keychain writes.
    init(
        connectionAdmission: ConnectionAdmission,
        fleetClient: FleetHTTPClient,
        registryCoordinator: PortalRegistryCoordinator,
        credentialStore: any CredentialStore,
        secureInputStore: any SecureInputStore = TransientSecureInputStore(),
        clock: any ManagerClock = SystemManagerClock(),
        planner: OperationPlanner = OperationPlanner(),
        eventSink: any ManualPortalEventSink = NoopManualPortalEventSink()
    ) {
        self.init(
            connectionAdmission: connectionAdmission,
            sessionCoordinator: PortalSessionCoordinator(
                fleetClient: fleetClient,
                registryCoordinator: registryCoordinator,
                credentialStore: credentialStore,
                secureInputStore: secureInputStore,
                clock: clock,
                planner: planner
            ),
            registryCoordinator: registryCoordinator,
            clock: clock,
            eventSink: eventSink
        )
    }

    /// Parses and admits a manual endpoint. This performs no probe and reads no
    /// credential. An omitted port defaults to 8723; hostnames are resolved,
    /// permitted LAN addresses are selected, and the exact trust scope is
    /// checked before a result is returned.
    func admit(
        rawEndpoint: String,
        portalID: PortalID? = nil
    ) async throws -> ManualPortalAdmission {
        let request: ConnectionAdmissionRequest
        do {
            request = try ConnectionAdmissionRequest(
                rawEndpoint: rawEndpoint,
                serviceKind: .portal,
                protocolName: Self.portalProtocol,
                defaultPort: LANEndpoint.defaultPortalAgentPort,
                source: .manual
            )
        } catch {
            await recordFailure(error, portalID: portalID)
            throw error
        }

        let admitted: AdmittedConnection
        do {
            admitted = try await connectionAdmission.admit(request)
        } catch let error as ConnectionAdmissionError {
            await recordFailure(error, portalID: portalID)
            throw error
        } catch {
            await recordFailure(error, portalID: portalID)
            throw sanitized(error)
        }

        let entries: [PortalRegistryEntry]
        do {
            entries = try await registryCoordinator.entries()
        } catch {
            let failure = sanitized(error)
            await recordFailure(failure, portalID: portalID)
            throw failure
        }

        let selectedPortalID = portalID
            ?? matchingPortalID(for: admitted.endpoint, in: entries)
            ?? PortalID()
        let hadExistingEntry = entries.contains { $0.id == selectedPortalID }
        let result = ManualPortalAdmission(
            portalID: selectedPortalID,
            request: request,
            connection: admitted,
            hadExistingEntry: hadExistingEntry
        )
        await eventSink.record(
            .endpointAdmitted(
                portalID: selectedPortalID,
                endpoint: admitted.endpoint,
                existingEntry: hadExistingEntry
            )
        )
        return result
    }

    /// Verifies a supplied exact Portal-scoped bearer credential at a manual
    /// endpoint. `/info` is the only identity promotion path. A failed probe
    /// never calls `recordManualEndpoint`, so an existing endpoint, identity,
    /// status, and credential-reference set remain intact.
    @discardableResult
    func verifyBearer(
        rawEndpoint: String,
        for portalID: PortalID,
        credentialReference: CredentialReference
    ) async throws -> ManualPortalBearerResult {
        let admission = try await admit(
            rawEndpoint: rawEndpoint,
            portalID: portalID
        )
        let expected = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        guard credentialReference == expected else {
            let error = ManagerError.authentication(.invalidCredential)
            await recordFailure(error, portalID: portalID)
            throw error
        }

        do {
            let classification = try await sessionCoordinator.verifyBearer(
                portalID: portalID,
                admissionRequest: admittedRequest(for: admission),
                credentialReference: credentialReference,
                authenticatedEndpoint: admission.endpoint
            )
            let verifiedAt = clock.now
            guard let entry = try await registryCoordinator.recordManualEndpoint(
                for: portalID,
                endpoint: admission.endpoint,
                authenticatedAt: verifiedAt
            ) else {
                throw ManagerError.validation(
                    field: "Portal registry",
                    reason: "The manually verified Portal could not be recorded."
                )
            }
            await eventSink.record(
                .bearerVerificationSucceeded(portalID: portalID)
            )
            return ManualPortalBearerResult(
                admission: admission,
                classification: classification,
                entry: entry
            )
        } catch {
            let failure = sanitized(error)
            await recordFailure(failure, portalID: portalID)
            throw failure
        }
    }

    /// Redeems one PIN directly against a validated manual endpoint. No
    /// Bonjour browser or mDNS record is consulted. A new candidate is staged
    /// only after admission succeeds; an existing confirmed entry is not
    /// mutated until the pairing request has succeeded.
    @discardableResult
    func pair(
        rawEndpoint: String,
        for portalID: PortalID? = nil,
        pin: String,
        allowReplacement: Bool = false
    ) async throws -> ManualPortalPairingResult {
        do {
            // Validate before staging a new candidate so blank/malformed input
            // cannot create a registry record or reach transport.
            _ = try PairingPIN(pin)
        } catch {
            await recordFailure(error, portalID: portalID)
            throw error
        }

        let admission = try await admit(
            rawEndpoint: rawEndpoint,
            portalID: portalID
        )

        do {
            if !admission.hadExistingEntry {
                _ = try await registryCoordinator.stageManualEndpoint(
                    for: admission.portalID,
                    endpoint: admission.endpoint
                )
            }

            let pairing = try await sessionCoordinator.pair(
                portalID: admission.portalID,
                admissionRequest: admittedRequest(for: admission),
                pin: pin,
                allowReplacement: allowReplacement
            )
            guard let entry = try await registryCoordinator.recordManualEndpoint(
                for: admission.portalID,
                endpoint: admission.endpoint,
                authenticatedAt: pairing.pairedAt
            ) else {
                throw ManagerError.validation(
                    field: "Portal registry",
                    reason: "The paired Portal could not be recorded."
                )
            }
            await eventSink.record(
                .pairingSucceeded(portalID: admission.portalID)
            )
            return ManualPortalPairingResult(
                admission: admission,
                pairing: pairing,
                entry: entry
            )
        } catch {
            let failure = sanitized(error)
            await recordFailure(failure, portalID: admission.portalID)
            throw failure
        }
    }

    /// Endpoint-editing spelling for a successful bearer-backed update.
    @discardableResult
    func editEndpoint(
        _ rawEndpoint: String,
        for portalID: PortalID,
        credentialReference: CredentialReference
    ) async throws -> ManualPortalBearerResult {
        try await verifyBearer(
            rawEndpoint: rawEndpoint,
            for: portalID,
            credentialReference: credentialReference
        )
    }

    /// Endpoint-editing spelling for a successful direct PIN pairing update.
    @discardableResult
    func editEndpoint(
        _ rawEndpoint: String,
        for portalID: PortalID,
        pin: String,
        allowReplacement: Bool = false
    ) async throws -> ManualPortalPairingResult {
        try await pair(
            rawEndpoint: rawEndpoint,
            for: portalID,
            pin: pin,
            allowReplacement: allowReplacement
        )
    }

    /// Lets the UI acknowledge the exact non-secret scope surfaced by an
    /// admission failure before retrying the same typed operation.
    func acknowledgeTrust(
        for scope: TrustWarningScope,
        at date: Date
    ) async throws {
        try await connectionAdmission.acknowledgeTrust(for: scope, at: date)
    }

    private func admittedRequest(
        for admission: ManualPortalAdmission
    ) -> ConnectionAdmissionRequest {
        ConnectionAdmissionRequest(
            endpoint: admission.endpoint,
            serviceKind: .portal,
            protocolName: Self.portalProtocol
        )
    }

    private func matchingPortalID(
        for endpoint: LANEndpoint,
        in entries: [PortalRegistryEntry]
    ) -> PortalID? {
        entries.first { entry in
            endpointHistory(for: entry).contains {
                sameEndpoint($0, endpoint)
            }
        }?.id
    }

    private func endpointHistory(
        for entry: PortalRegistryEntry
    ) -> [LANEndpoint] {
        entry.discoveredEndpoints + (entry.endpoint.map { [$0] } ?? [])
    }

    private func sameEndpoint(
        _ lhs: LANEndpoint,
        _ rhs: LANEndpoint
    ) -> Bool {
        normalizeHost(lhs.hostOrAddress) == normalizeHost(rhs.hostOrAddress)
            && lhs.port == rhs.port
            && normalizeZone(lhs.interfaceZone) == normalizeZone(rhs.interfaceZone)
    }

    private func normalizeHost(_ host: String) -> String {
        var value = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if value.hasPrefix("[") && value.hasSuffix("]") {
            value.removeFirst()
            value.removeLast()
        }
        if value.hasSuffix(".") {
            value.removeLast()
        }
        return value
    }

    private func normalizeZone(_ zone: String?) -> String? {
        guard let zone else { return nil }
        let value = zone.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return value.isEmpty ? nil : value
    }

    private func recordFailure(
        _ error: Error,
        portalID: PortalID?
    ) async {
        if let admissionError = error as? ConnectionAdmissionError,
           case let .trustWarningRequired(scope) = admissionError {
            await eventSink.record(
                .trustWarningRequired(portalID: portalID, scope: scope)
            )
        }
        await eventSink.record(
            .operationFailed(
                portalID: portalID,
                category: category(for: error)
            )
        )
    }

    private func category(for error: Error) -> ManagerErrorCategory {
        if let managerError = error as? ManagerError {
            return managerError.category
        }
        if error is ManualEndpointParserError || error is ConnectionAdmissionError {
            return .lanPolicy
        }
        if error is CancellationError {
            return .cancelled
        }
        return .validation
    }

    private func sanitized(_ error: Error) -> Error {
        if error is CancellationError {
            return ManagerError.cancelled
        }
        if error is ManualEndpointParserError || error is ConnectionAdmissionError {
            return error
        }
        if let managerError = error as? ManagerError {
            return managerError
        }
        return ManagerError.discovery(
            "The manual Portal operation could not be completed."
        )
    }
}

/// Compatibility name for application composition roots that use the full
/// onboarding terminology from the product design.
typealias ManualPortalOnboardingCoordinator = ManualPortalCoordinator
