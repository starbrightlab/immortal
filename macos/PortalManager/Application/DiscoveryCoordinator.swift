/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A non-secret projection of one Bonjour candidate. Candidate metadata remains
/// separate from the Portal Registry until a bearer-authenticated `/info`
/// response has produced a verified identity.
struct DiscoveryCandidateSnapshot: Sendable, Equatable, Hashable {
    let candidate: DiscoveryCandidate
    let admittedEndpoint: LANEndpoint?
    let portalID: PortalID?
    let connectionState: ConnectionState
    let isPresent: Bool

    var endpoint: LANEndpoint? {
        admittedEndpoint
    }

    var state: ConnectionState {
        connectionState
    }
}

/// Sanitized lifecycle events emitted by discovery. No event carries a
/// credential value, authorization header, PIN, raw response, or untrusted
/// transport error description.
enum DiscoveryCoordinatorEvent: Sendable, Equatable {
    case browserState(BonjourBrowserState)
    case candidateObserved(serviceName: String, source: EndpointSource)
    case candidateAdmitted(serviceName: String, endpoint: LANEndpoint)
    case trustWarningRequired(serviceName: String, scope: TrustWarningScope)
    case probeSkipped(serviceName: String)
    case probeSucceeded(portalID: PortalID)
    case candidateFailed(serviceName: String, category: ManagerErrorCategory)
    case portalOffline(portalID: PortalID, lastContact: Date?, reason: String)
    case browserFailed
}

protocol DiscoveryCoordinatorEventSink: Sendable {
    func record(_ event: DiscoveryCoordinatorEvent) async
}

struct NoopDiscoveryCoordinatorEventSink: DiscoveryCoordinatorEventSink, Sendable {
    func record(_ event: DiscoveryCoordinatorEvent) async {}
}

/// Coordinates Bonjour lifecycle, resolved-address admission, optional bearer
/// `/info` probes, and authenticated registry reconciliation.
///
/// The actor deliberately keeps unverified candidates in transient state. A
/// candidate can be displayed and used as a pairing target after LAN admission,
/// but it cannot create identity, health, or authenticated endpoint state until
/// the exact Portal-scoped Verified Bearer Credential has completed `/info`.
actor DiscoveryCoordinator {
    private struct CandidateKey: Hashable, Sendable {
        let serviceName: String
        let interfaceName: String?

        init(_ candidate: DiscoveryCandidate) {
            serviceName = candidate.serviceName
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            let interface = candidate.interfaceName?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            interfaceName = interface?.isEmpty == true ? nil : interface
        }
    }

    private struct CandidateRecord: Sendable {
        var candidate: DiscoveryCandidate
        var admittedEndpoint: LANEndpoint?
        var portalID: PortalID?
        var connectionState: ConnectionState
        var isPresent: Bool

        var snapshot: DiscoveryCandidateSnapshot {
            DiscoveryCandidateSnapshot(
                candidate: candidate,
                admittedEndpoint: admittedEndpoint,
                portalID: portalID,
                connectionState: connectionState,
                isPresent: isPresent
            )
        }
    }

    private struct SuppliedBearer: Sendable {
        let portalID: PortalID
        let reference: CredentialReference
    }

    private let browser: any BonjourBrowser
    private let candidateAdmission: DiscoveryCandidateNormalizer
    private let fleetClient: FleetHTTPClient
    private let registryCoordinator: PortalRegistryCoordinator
    private let clock: any ManagerClock
    private let planner: OperationPlanner
    private let eventSink: any DiscoveryCoordinatorEventSink

    private var eventTask: Task<Void, Never>?
    private var candidateRecords: [CandidateKey: CandidateRecord] = [:]
    private var generations: [CandidateKey: UInt64] = [:]

    init(
        browser: any BonjourBrowser,
        candidateAdmission: DiscoveryCandidateNormalizer,
        fleetClient: FleetHTTPClient,
        registryCoordinator: PortalRegistryCoordinator,
        clock: any ManagerClock = SystemManagerClock(),
        planner: OperationPlanner = OperationPlanner(),
        eventSink: any DiscoveryCoordinatorEventSink = NoopDiscoveryCoordinatorEventSink()
    ) {
        self.browser = browser
        self.candidateAdmission = candidateAdmission
        self.fleetClient = fleetClient
        self.registryCoordinator = registryCoordinator
        self.clock = clock
        self.planner = planner
        self.eventSink = eventSink
    }

    /// Convenience composition-root initializer that reuses the shared
    /// connection admission policy without exposing a second discovery path.
    init(
        browser: any BonjourBrowser,
        connectionAdmission: ConnectionAdmission,
        fleetClient: FleetHTTPClient,
        registryCoordinator: PortalRegistryCoordinator,
        clock: any ManagerClock = SystemManagerClock(),
        planner: OperationPlanner = OperationPlanner(),
        eventSink: any DiscoveryCoordinatorEventSink = NoopDiscoveryCoordinatorEventSink()
    ) {
        self.init(
            browser: browser,
            candidateAdmission: DiscoveryCandidateNormalizer(
                connectionAdmission: connectionAdmission
            ),
            fleetClient: fleetClient,
            registryCoordinator: registryCoordinator,
            clock: clock,
            planner: planner,
            eventSink: eventSink
        )
    }

    /// Starts Bonjour observation. Starting the browser never removes or
    /// rewrites managed registry entries.
    func start() async throws {
        startEventConsumptionIfNeeded()
        do {
            try await browser.start()
            await Task.yield()
        } catch is CancellationError {
            eventTask?.cancel()
            eventTask = nil
            throw CancellationError()
        } catch {
            eventTask?.cancel()
            eventTask = nil
            await eventSink.record(.browserFailed)
            throw sanitizedDiscoveryError(error)
        }
    }

    /// Recreates/refreshes Bonjour observation through the injected browser.
    /// Existing managed entries remain untouched when no new event arrives.
    func refresh() async throws {
        startEventConsumptionIfNeeded()
        do {
            try await browser.refresh()
            await Task.yield()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            await eventSink.record(.browserFailed)
            throw sanitizedDiscoveryError(error)
        }
    }

    func stop() async {
        eventTask?.cancel()
        eventTask = nil
        await browser.stop()
    }

    /// Returns transient candidate state. Registry entries are not synthesized
    /// from this list, so discovery metadata cannot become identity by itself.
    func candidates() -> [DiscoveryCandidateSnapshot] {
        candidateRecords.values
            .map(\.snapshot)
            .sorted { lhs, rhs in
                let left = CandidateKey(lhs.candidate)
                let right = CandidateKey(rhs.candidate)
                if left.serviceName != right.serviceName {
                    return left.serviceName < right.serviceName
                }
                return (left.interfaceName ?? "") < (right.interfaceName ?? "")
            }
    }

    /// Compatibility spelling for callers that describe the transient list as
    /// discovery candidates rather than snapshots.
    func candidateSnapshots() -> [DiscoveryCandidateSnapshot] {
        candidates()
    }

    /// Provides the current non-secret registry projection for UI/application
    /// callers without allowing discovery to delete or reset managed entries.
    func managedEntries() async throws -> [PortalRegistryEntry] {
        try await registryCoordinator.entries()
    }

    /// Explicitly probes one candidate with an exact Portal-scoped bearer
    /// reference. This is useful for an operator-supplied credential and keeps
    /// manual/discovery callers on the same admission and reconciliation path.
    @discardableResult
    func probe(
        _ candidate: DiscoveryCandidate,
        portalID: PortalID,
        credentialReference: CredentialReference
    ) async throws -> DiscoveryCandidateSnapshot {
        let expected = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        guard credentialReference == expected else {
            throw ManagerError.authentication(.invalidCredential)
        }

        let key = CandidateKey(candidate)
        let generation = nextGeneration(for: key)
        let prior = candidateRecords[key]
        seedRecord(
            for: key,
            candidate: candidate,
            prior: prior,
            isPresent: true
        )

        let result = await process(
            candidate,
            key: key,
            generation: generation,
            suppliedBearer: SuppliedBearer(
                portalID: portalID,
                reference: credentialReference
            )
        )
        switch result {
        case .success(let snapshot):
            return snapshot
        case .failure(let error):
            throw error
        }
    }

    /// Labelled compatibility spelling for explicit candidate probes.
    @discardableResult
    func probe(
        candidate: DiscoveryCandidate,
        for portalID: PortalID,
        credentialReference: CredentialReference
    ) async throws -> DiscoveryCandidateSnapshot {
        try await probe(
            candidate,
            portalID: portalID,
            credentialReference: credentialReference
        )
    }

    private func startEventConsumptionIfNeeded() {
        guard eventTask == nil else { return }
        let browser = self.browser
        eventTask = Task { [weak self, browser] in
            guard let self else { return }
            for await event in browser.events() {
                await self.consume(event)
            }
        }
    }

    private func consume(_ event: BonjourEvent) async {
        switch event {
        case .found(let candidate), .updated(let candidate):
            await handle(candidate: candidate)
        case .removed(let candidate):
            await handleRemoval(candidate)
        case .removedInstance(instanceName: let instanceName):
            await handleRemoval(instanceName: instanceName)
        case .resolutionFailed(let candidate):
            await handleResolutionFailure(candidate)
        case .state(let state):
            await eventSink.record(.browserState(state))
        case .failed:
            // Browser-provided text is intentionally discarded. A browser
            // failure does not prove that every managed Portal is offline.
            await eventSink.record(.browserFailed)
        }
    }

    private func handle(candidate: DiscoveryCandidate) async {
        let key = CandidateKey(candidate)
        let generation = nextGeneration(for: key)
        let prior = candidateRecords[key]
        seedRecord(
            for: key,
            candidate: candidate,
            prior: prior,
            isPresent: true
        )
        await eventSink.record(
            .candidateObserved(
                serviceName: normalizedServiceName(candidate),
                source: candidate.source
            )
        )
        _ = await process(
            candidate,
            key: key,
            generation: generation,
            suppliedBearer: nil
        )
    }

    private func process(
        _ candidate: DiscoveryCandidate,
        key: CandidateKey,
        generation: UInt64,
        suppliedBearer: SuppliedBearer?
    ) async -> Result<DiscoveryCandidateSnapshot, ManagerError> {
        let prior = candidateRecords[key]

        let admitted: AdmittedDiscoveryCandidate
        do {
            admitted = try await candidateAdmission.admit(candidate)
        } catch let error as ConnectionAdmissionError {
            guard isCurrent(generation, for: key) else {
                return .failure(.cancelled)
            }
            let endpoint = endpoint(from: error, source: candidate.source)
            let state: ConnectionState
            if let endpoint, let scope = trustScope(from: error) {
                state = .lanValidated(
                    endpoint: endpoint,
                    trustScope: scope
                )
            } else {
                state = prior?.connectionState
                    ?? .error(.discovery("The discovery trust check could not be completed."))
            }
            updateRecord(
                key: key,
                candidate: candidate,
                admittedEndpoint: endpoint ?? prior?.admittedEndpoint,
                portalID: prior?.portalID,
                connectionState: state,
                isPresent: true
            )
            if let scope = trustScope(from: error) {
                await eventSink.record(
                    .trustWarningRequired(
                        serviceName: normalizedServiceName(candidate),
                        scope: scope
                    )
                )
            }
            return .failure(.discovery("A local-network trust acknowledgement is required."))
        } catch {
            let managerError = sanitizedDiscoveryError(error)
            guard isCurrent(generation, for: key) else {
                return .failure(.cancelled)
            }
            await recordAdmissionFailure(
                managerError,
                candidate: candidate,
                key: key,
                prior: prior
            )
            return .failure(managerError)
        }

        guard isCurrent(generation, for: key) else {
            return .failure(.cancelled)
        }

        await eventSink.record(
            .candidateAdmitted(
                serviceName: admitted.serviceName,
                endpoint: admitted.endpoint
            )
        )

        let entries: [PortalRegistryEntry]
        do {
            entries = try await registryCoordinator.entries()
        } catch {
            let managerError = sanitizedDiscoveryError(error)
            await recordAdmissionFailure(
                managerError,
                candidate: candidate,
                key: key,
                prior: prior,
                admittedEndpoint: admitted.endpoint
            )
            return .failure(managerError)
        }

        let matchingEntry = matchingEntry(
            for: admitted.endpoint,
            in: entries
        )
        let selection: SuppliedBearer?
        if let suppliedBearer {
            selection = suppliedBearer
        } else {
            selection = automaticBearerSelection(for: matchingEntry)
        }

        guard let selection else {
            let state = stateWithoutProbe(
                for: matchingEntry,
                endpoint: admitted.endpoint,
                prior: prior
            )
            updateRecord(
                key: key,
                candidate: candidate,
                admittedEndpoint: admitted.endpoint,
                portalID: matchingEntry?.id ?? prior?.portalID,
                connectionState: state,
                isPresent: true
            )
            await eventSink.record(
                .probeSkipped(serviceName: admitted.serviceName)
            )
            return .success(candidateSnapshot(for: key))
        }

        let expectedReference = CredentialReference.portalCredential(
            portalID: selection.portalID,
            kind: .verifiedBearer
        )
        guard selection.reference == expectedReference else {
            let managerError = ManagerError.authentication(.invalidCredential)
            await recordProbeFailure(
                managerError,
                candidate: candidate,
                key: key,
                generation: generation,
                portalID: selection.portalID,
                admittedEndpoint: admitted.endpoint,
                prior: prior
            )
            return .failure(managerError)
        }

        guard !isBearerSuppressed(matchingEntry, suppliedBearer: suppliedBearer) else {
            let managerError = ManagerError.authentication(.revokedCredential)
            await recordProbeFailure(
                managerError,
                candidate: candidate,
                key: key,
                generation: generation,
                portalID: selection.portalID,
                admittedEndpoint: admitted.endpoint,
                prior: prior
            )
            return .failure(managerError)
        }

        updateRecord(
            key: key,
            candidate: candidate,
            admittedEndpoint: admitted.endpoint,
            portalID: selection.portalID,
            connectionState: .lanValidated(
                endpoint: admitted.endpoint,
                trustScope: admitted.trustScope
            ),
            isPresent: true
        )

        do {
            let classification = try await performBearerProbe(
                portalID: selection.portalID,
                endpoint: admitted.endpoint,
                credentialReference: selection.reference
            )
            guard isCurrent(generation, for: key) else {
                return .failure(.cancelled)
            }

            let verifiedAt = clock.now
            let authenticatedEndpoint = authenticatedEndpoint(
                from: classification.endpoint,
                admitted: admitted.endpoint,
                verifiedAt: verifiedAt
            )
            var status = classification.status
            status.reachability = .reachable
            status.lastUpdatedAt = status.lastUpdatedAt ?? verifiedAt
            let reconciliation = try await registryCoordinator.reconcile(
                AuthenticatedPortalRecord(
                    identity: classification.identity,
                    endpoint: authenticatedEndpoint,
                    capabilities: classification.capabilities,
                    status: status,
                    policyMetadata: classification.policyMetadata,
                    credentialReferences: [selection.reference],
                    verifiedAt: verifiedAt
                )
            )
            updateMergedCandidateReferences(
                mergedPortalIDs: reconciliation.mergedPortalIDs,
                canonicalPortalID: reconciliation.portalID
            )
            updateRecord(
                key: key,
                candidate: candidate,
                admittedEndpoint: reconciliation.entry.endpoint,
                portalID: reconciliation.portalID,
                connectionState: reconciliation.entry.connectionState,
                isPresent: true
            )
            await eventSink.record(
                .probeSucceeded(portalID: reconciliation.portalID)
            )
            return .success(candidateSnapshot(for: key))
        } catch is CancellationError {
            let managerError = ManagerError.cancelled
            await recordProbeFailure(
                managerError,
                candidate: candidate,
                key: key,
                generation: generation,
                portalID: selection.portalID,
                admittedEndpoint: admitted.endpoint,
                prior: prior
            )
            return .failure(managerError)
        } catch {
            let managerError = sanitizedDiscoveryError(error)
            await recordProbeFailure(
                managerError,
                candidate: candidate,
                key: key,
                generation: generation,
                portalID: selection.portalID,
                admittedEndpoint: admitted.endpoint,
                prior: prior
            )
            return .failure(managerError)
        }
    }

    private func performBearerProbe(
        portalID: PortalID,
        endpoint: LANEndpoint,
        credentialReference: CredentialReference
    ) async throws -> PortalInfoClassification {
        let plan = try planner.plan(
            portalID: portalID,
            route: .info,
            method: .get,
            credential: .verifiedBearer
        )
        let request = FleetHTTPClientRequest(
            portalID: portalID,
            admissionRequest: ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            ),
            routePlan: plan,
            credentialReference: credentialReference
        )
        let response = try await fleetClient.execute(request)
        let info: AuthenticatedPortalInfo
        do {
            let data = try JSONEncoder().encode(response.payload)
            info = try JSONDecoder().decode(AuthenticatedPortalInfo.self, from: data)
        } catch {
            throw ManagerError.validation(
                field: "Fleet response",
                reason: "The authenticated Portal information was not valid."
            )
        }
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

    private func automaticBearerSelection(
        for entry: PortalRegistryEntry?
    ) -> SuppliedBearer? {
        guard let entry else { return nil }
        let reference = CredentialReference.portalCredential(
            portalID: entry.id,
            kind: .verifiedBearer
        )
        guard entry.credentialReferences.contains(reference) else {
            return nil
        }
        return SuppliedBearer(portalID: entry.id, reference: reference)
    }

    private func stateWithoutProbe(
        for entry: PortalRegistryEntry?,
        endpoint: LANEndpoint,
        prior: CandidateRecord?
    ) -> ConnectionState {
        guard let entry else {
            return .pairingRequired(endpoint: endpoint)
        }

        // A paired remote session is deliberately retained as its own assurance
        // state. Discovery never turns it into identity or health.
        switch entry.connectionState {
        case .remoteSessionPaired, .remoteSessionReady:
            return entry.connectionState
        case .offline, .reauthenticationRequired:
            return entry.connectionState
        case .online, .bearerAuthenticated:
            if entry.identity != nil {
                return .bearerVerificationRequired(
                    reason: "A Verified Bearer Credential is required to refresh Portal identity."
                )
            }
            return entry.connectionState
        default:
            return prior?.connectionState ?? entry.connectionState
        }
    }

    private func isBearerSuppressed(
        _ entry: PortalRegistryEntry?,
        suppliedBearer: SuppliedBearer?
    ) -> Bool {
        guard suppliedBearer == nil,
              let entry,
              case let .reauthenticationRequired(kind, _) = entry.connectionState else {
            return false
        }
        return kind == .verifiedBearer
    }

    private func matchingEntry(
        for endpoint: LANEndpoint,
        in entries: [PortalRegistryEntry]
    ) -> PortalRegistryEntry? {
        entries.first { entry in
            endpointHistory(for: entry).contains {
                sameEndpoint($0, endpoint)
            }
        }
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

    private func authenticatedEndpoint(
        from reported: LANEndpoint?,
        admitted: LANEndpoint,
        verifiedAt: Date
    ) -> LANEndpoint {
        if let reported,
           let validated = try? LANPolicy.validate(
               hostOrAddress: reported.hostOrAddress,
               interfaceZone: reported.interfaceZone ?? admitted.interfaceZone,
               port: reported.port,
               source: .authenticatedRefresh,
               lastAuthenticatedAt: verifiedAt
           ) {
            return validated
        }

        var endpoint = admitted
        endpoint.source = .authenticatedRefresh
        endpoint.lastAuthenticatedAt = verifiedAt
        return endpoint
    }

    private func handleRemoval(_ candidate: DiscoveryCandidate) async {
        let key = CandidateKey(candidate)
        await markRemoved(key: key, candidate: candidate)
    }

    private func handleRemoval(instanceName: String) async {
        let normalized = instanceName
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let matchingKeys = candidateRecords.keys.filter {
            $0.serviceName == normalized
        }
        for key in matchingKeys {
            guard let record = candidateRecords[key] else { continue }
            await markRemoved(key: key, candidate: record.candidate)
        }
    }

    private func markRemoved(
        key: CandidateKey,
        candidate: DiscoveryCandidate
    ) async {
        guard var record = candidateRecords[key] else { return }
        record.candidate = candidate
        record.isPresent = false

        let portalID = record.portalID
        let offlineEntry: PortalRegistryEntry?
        if let portalID {
            offlineEntry = try? await registryCoordinator.markOffline(
                for: portalID,
                reason: "The Portal is no longer available through Bonjour."
            )
        } else if let endpoint = record.admittedEndpoint {
            offlineEntry = try? await registryCoordinator.markOffline(
                matching: nil,
                endpoint: endpoint,
                reason: "The Portal is no longer available through Bonjour."
            )
        } else {
            offlineEntry = nil
        }

        if let offlineEntry {
            record.portalID = offlineEntry.id
            record.connectionState = offlineEntry.connectionState
            await eventSink.record(
                .portalOffline(
                    portalID: offlineEntry.id,
                    lastContact: offlineEntry.lastSuccessfulContact,
                    reason: "The Portal is no longer available through Bonjour."
                )
            )
        }
        candidateRecords[key] = record
    }

    private func handleResolutionFailure(_ candidate: DiscoveryCandidate) async {
        let key = CandidateKey(candidate)
        guard let record = candidateRecords[key] else {
            await eventSink.record(
                .candidateFailed(
                    serviceName: normalizedServiceName(candidate),
                    category: .resolution
                )
            )
            return
        }
        let error = ManagerError.resolution(.failed)
        await recordAdmissionFailure(
            error,
            candidate: candidate,
            key: key,
            prior: record,
            admittedEndpoint: record.admittedEndpoint
        )
    }

    private func recordAdmissionFailure(
        _ error: ManagerError,
        candidate: DiscoveryCandidate,
        key: CandidateKey,
        prior: CandidateRecord?,
        admittedEndpoint: LANEndpoint? = nil
    ) async {
        var state = prior?.connectionState
            ?? .error(error)
        var portalID = prior?.portalID
        if let existingPortalID = portalID,
           let entry = try? await registryCoordinator.markOffline(
               for: existingPortalID,
               reason: offlineReason(for: error)
           ) {
            state = entry.connectionState
            portalID = entry.id
            await eventSink.record(
                .portalOffline(
                    portalID: entry.id,
                    lastContact: entry.lastSuccessfulContact,
                    reason: offlineReason(for: error)
                )
            )
        }
        updateRecord(
            key: key,
            candidate: candidate,
            admittedEndpoint: admittedEndpoint ?? prior?.admittedEndpoint,
            portalID: portalID,
            connectionState: state,
            isPresent: true
        )
        await eventSink.record(
            .candidateFailed(
                serviceName: normalizedServiceName(candidate),
                category: error.category
            )
        )
    }

    private func recordProbeFailure(
        _ error: ManagerError,
        candidate: DiscoveryCandidate,
        key: CandidateKey,
        generation: UInt64,
        portalID: PortalID,
        admittedEndpoint: LANEndpoint,
        prior: CandidateRecord?
    ) async {
        guard isCurrent(generation, for: key) else { return }

        var state = prior?.connectionState ?? .error(error)
        var canonicalPortalID = portalID
        let updatedEntry: PortalRegistryEntry?
        if isCredentialFailure(error) {
            updatedEntry = try? await registryCoordinator.markReauthenticationRequired(
                for: portalID,
                kind: .verifiedBearer,
                reason: "The Portal credential requires authentication again."
            )
        } else if error != .cancelled {
            updatedEntry = try? await registryCoordinator.markOffline(
                for: portalID,
                reason: offlineReason(for: error)
            )
        } else {
            updatedEntry = nil
        }

        if let updatedEntry {
            state = updatedEntry.connectionState
            canonicalPortalID = updatedEntry.id
            if case .offline = state {
                await eventSink.record(
                    .portalOffline(
                        portalID: updatedEntry.id,
                        lastContact: updatedEntry.lastSuccessfulContact,
                        reason: offlineReason(for: error)
                    )
                )
            }
        }

        updateRecord(
            key: key,
            candidate: candidate,
            admittedEndpoint: admittedEndpoint,
            portalID: canonicalPortalID,
            connectionState: state,
            isPresent: true
        )
        await eventSink.record(
            .candidateFailed(
                serviceName: normalizedServiceName(candidate),
                category: error.category
            )
        )
    }

    private func isCredentialFailure(_ error: ManagerError) -> Bool {
        switch error {
        case .authentication(.unauthorized), .authentication(.revokedCredential), .keychain:
            return true
        default:
            return false
        }
    }

    private func offlineReason(for error: ManagerError) -> String {
        switch error {
        case .resolution, .lanPolicy:
            return "The Portal discovery address could not be reached."
        case .transport, .http, .redirectRejected:
            return "The Portal could not be reached during discovery."
        case .validation, .discovery, .protocol:
            return "The Portal response could not be confirmed."
        default:
            return "The Portal could not be reached during discovery."
        }
    }

    private func seedRecord(
        for key: CandidateKey,
        candidate: DiscoveryCandidate,
        prior: CandidateRecord?,
        isPresent: Bool
    ) {
        candidateRecords[key] = CandidateRecord(
            candidate: candidate,
            admittedEndpoint: prior?.admittedEndpoint,
            portalID: prior?.portalID,
            connectionState: prior?.connectionState
                ?? .discovered(candidate: DiscoveryReference(
                    serviceName: normalizedServiceName(candidate),
                    hostOrAddress: candidate.resolvedHostOrAddress ?? "",
                    port: candidate.port,
                    interfaceName: candidate.interfaceName
                )),
            isPresent: isPresent
        )
    }

    private func updateRecord(
        key: CandidateKey,
        candidate: DiscoveryCandidate,
        admittedEndpoint: LANEndpoint?,
        portalID: PortalID?,
        connectionState: ConnectionState,
        isPresent: Bool
    ) {
        candidateRecords[key] = CandidateRecord(
            candidate: candidate,
            admittedEndpoint: admittedEndpoint,
            portalID: portalID,
            connectionState: connectionState,
            isPresent: isPresent
        )
    }

    private func candidateSnapshot(
        for key: CandidateKey
    ) -> DiscoveryCandidateSnapshot {
        candidateRecords[key]?.snapshot
            ?? DiscoveryCandidateSnapshot(
                candidate: BonjourService(
                    serviceName: "Unknown Portal",
                    resolutionError: .invalidEndpoint
                ),
                admittedEndpoint: nil,
                portalID: nil,
                connectionState: .error(.discovery("The discovery candidate was unavailable.")),
                isPresent: false
            )
    }

    private func updateMergedCandidateReferences(
        mergedPortalIDs: Set<PortalID>,
        canonicalPortalID: PortalID
    ) {
        guard !mergedPortalIDs.isEmpty else { return }
        for key in candidateRecords.keys {
            guard var record = candidateRecords[key],
                  let portalID = record.portalID,
                  mergedPortalIDs.contains(portalID) else {
                continue
            }
            record.portalID = canonicalPortalID
            candidateRecords[key] = record
        }
    }

    private func nextGeneration(for key: CandidateKey) -> UInt64 {
        let next = (generations[key] ?? 0) &+ 1
        generations[key] = next
        return next
    }

    private func isCurrent(
        _ generation: UInt64,
        for key: CandidateKey
    ) -> Bool {
        generations[key] == generation
    }

    private func normalizedServiceName(_ candidate: DiscoveryCandidate) -> String {
        let name = candidate.serviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "Unknown Portal" : name
    }

    private func trustScope(from error: ConnectionAdmissionError) -> TrustWarningScope? {
        if case let .trustWarningRequired(scope) = error {
            return scope
        }
        return nil
    }

    private func endpoint(
        from error: ConnectionAdmissionError,
        source: EndpointSource
    ) -> LANEndpoint? {
        guard let scope = trustScope(from: error) else { return nil }
        let family: AddressFamily = scope.resolvedHostOrAddress.contains(":")
            ? .ipv6
            : .ipv4
        return LANEndpoint(
            hostOrAddress: scope.resolvedHostOrAddress,
            port: scope.port,
            addressFamily: family,
            interfaceZone: scope.interfaceZone,
            source: source
        )
    }

    private func sanitizedDiscoveryError(_ error: Error) -> ManagerError {
        if error is CancellationError {
            return .cancelled
        }
        if let managerError = error as? ManagerError {
            if case .discovery = managerError {
                return .discovery("The Portal discovery operation failed.")
            }
            return managerError
        }
        if error is ConnectionAdmissionError {
            return .discovery("The Portal discovery admission did not complete.")
        }
        return .discovery("The Portal discovery operation failed.")
    }
}
