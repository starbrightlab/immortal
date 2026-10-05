/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A fully authenticated observation used to reconcile a candidate into the
/// local registry. The observation is created only after a bearer-authenticated
/// `/info` response; discovery metadata and failed probes are not represented by
/// this type.
struct AuthenticatedPortalRecord: Codable, Sendable, Equatable, Hashable {
    var identity: PortalIdentity
    var endpoint: LANEndpoint?
    var capabilities: PortalCapabilities?
    var status: PortalStatus
    var policyMetadata: PortalPolicyMetadata
    var credentialReferences: [CredentialReference]
    var verifiedAt: Date

    init(
        identity: PortalIdentity,
        endpoint: LANEndpoint? = nil,
        capabilities: PortalCapabilities? = nil,
        status: PortalStatus = PortalStatus(reachability: .reachable),
        policyMetadata: PortalPolicyMetadata = PortalPolicyMetadata(),
        credentialReferences: [CredentialReference] = [],
        verifiedAt: Date
    ) {
        self.identity = identity
        self.endpoint = endpoint
        self.capabilities = capabilities
        self.status = status
        self.policyMetadata = policyMetadata
        self.credentialReferences = credentialReferences
        self.verifiedAt = verifiedAt
    }

    /// Alias for callers whose protocol terminology uses "authenticated" for
    /// the verification timestamp.
    init(
        identity: PortalIdentity,
        endpoint: LANEndpoint? = nil,
        capabilities: PortalCapabilities? = nil,
        status: PortalStatus = PortalStatus(reachability: .reachable),
        policyMetadata: PortalPolicyMetadata = PortalPolicyMetadata(),
        credentialReferences: [CredentialReference] = [],
        authenticatedAt: Date
    ) {
        self.init(
            identity: identity,
            endpoint: endpoint,
            capabilities: capabilities,
            status: status,
            policyMetadata: policyMetadata,
            credentialReferences: credentialReferences,
            verifiedAt: authenticatedAt
        )
    }
}

/// The result of reconciling one authenticated record. `mergedPortalIDs`
/// identifies duplicate local records absorbed into the canonical entry so
/// transient bulk selections can follow the surviving PortalID.
struct PortalRegistryReconciliation: Sendable, Equatable {
    let entry: PortalRegistryEntry
    let mergedPortalIDs: Set<PortalID>
    let didCreateEntry: Bool

    var portalID: PortalID { entry.id }
}

/// The non-secret work produced by removing a Portal from the registry. The
/// persistence coordinator uses the references to clean up Keychain items;
/// the values themselves are never included here.
struct PortalRegistryRemoval: Sendable, Equatable {
    let portalID: PortalID
    let credentialReferences: [CredentialReference]
    let wasMemberOfBulkOperation: Bool
}

/// In-memory registry aggregate for deterministic reconciliation and lifecycle
/// decisions. Bulk membership is deliberately transient UI/application state;
/// only the non-secret entry snapshot is persisted by `RegistryStore`.
struct PortalRegistry: Sendable, Equatable {
    private(set) var entries: [PortalRegistryEntry]
    private(set) var bulkOperationMembership: Set<PortalID>

    init(
        snapshot: RegistrySnapshot = RegistrySnapshot(),
        bulkOperationMembership: Set<PortalID> = []
    ) {
        entries = snapshot.entries
        let knownPortalIDs = Set(entries.map(\.id))
        self.bulkOperationMembership = bulkOperationMembership.intersection(knownPortalIDs)
    }

    var snapshot: RegistrySnapshot {
        RegistrySnapshot(entries: entries)
    }

    func entry(for portalID: PortalID) -> PortalRegistryEntry? {
        entries.first { $0.id == portalID }
    }

    /// Replaces the transient selection with managed Portal IDs only. This
    /// keeps stale selections from targeting a removed or never-registered
    /// Portal.
    mutating func setBulkOperationMembership(_ portalIDs: Set<PortalID>) {
        let knownPortalIDs = Set(entries.map(\.id))
        bulkOperationMembership = portalIDs.intersection(knownPortalIDs)
    }

    mutating func addToBulkOperation(_ portalID: PortalID) {
        guard entries.contains(where: { $0.id == portalID }) else { return }
        bulkOperationMembership.insert(portalID)
    }

    /// Stages a newly admitted manual endpoint for a pairing candidate. Existing
    /// confirmed entries are intentionally left untouched; endpoint editing is
    /// committed only after the requested authenticated operation succeeds.
    @discardableResult
    mutating func stageManualEndpoint(
        for portalID: PortalID,
        endpoint: LANEndpoint
    ) -> PortalRegistryEntry {
        var manualEndpoint = endpoint
        manualEndpoint.source = .manual

        guard let index = entries.firstIndex(where: { $0.id == portalID }) else {
            let entry = PortalRegistryEntry(
                id: portalID,
                connectionState: .pairingRequired(endpoint: manualEndpoint),
                endpoint: manualEndpoint
            )
            entries.append(entry)
            return entry
        }

        var entry = entries[index]
        if entry.identity == nil,
           entry.lastSuccessfulContact == nil,
           entry.lastConfirmedStatus == nil,
           entry.credentialReferences.isEmpty {
            entry.endpoint = manualEndpoint
            entry.discoveredEndpoints = orderedEndpointHistory(
                endpointHistory(for: entry) + [manualEndpoint],
                active: manualEndpoint
            )
            entry.connectionState = .pairingRequired(endpoint: manualEndpoint)
            entries[index] = entry
        }
        return entry
    }

    /// Commits a manual endpoint after a successful bearer verification or PIN
    /// pairing. The endpoint becomes active while prior endpoints, identity,
    /// confirmed status, and credential references remain in the entry.
    @discardableResult
    mutating func recordManualEndpoint(
        for portalID: PortalID,
        endpoint: LANEndpoint,
        authenticatedAt: Date
    ) -> PortalRegistryEntry? {
        guard let index = entries.firstIndex(where: { $0.id == portalID }) else {
            return nil
        }

        var manualEndpoint = endpoint
        manualEndpoint.source = .manual
        manualEndpoint.lastAuthenticatedAt = authenticatedAt

        var entry = entries[index]
        let allEndpoints = endpointHistory(for: entry) + [manualEndpoint]
        entry.endpoint = manualEndpoint
        entry.discoveredEndpoints = orderedEndpointHistory(
            allEndpoints,
            active: manualEndpoint
        )
        entries[index] = entry
        return entry
    }

    mutating func removeFromBulkOperation(_ portalID: PortalID) {
        bulkOperationMembership.remove(portalID)
    }

    /// Reconciles an authenticated `/info` record into one canonical entry.
    /// Matching uses the stable PortalID/serial identity first and an
    /// authenticated host/port endpoint only as a fallback. All endpoint
    /// history and valid opaque credential references are retained.
    mutating func reconcile(
        _ record: AuthenticatedPortalRecord
    ) -> PortalRegistryReconciliation {
        let matchingIndices = entries.indices.filter { index in
            matches(entries[index], record: record)
        }

        let canonicalID: PortalID
        if matchingIndices.contains(where: { entries[$0].id == record.identity.portalID }) {
            canonicalID = record.identity.portalID
        } else if let firstMatch = matchingIndices.first {
            canonicalID = entries[firstMatch].id
        } else {
            canonicalID = record.identity.portalID
        }

        let matchedIDs = Set(matchingIndices.map { entries[$0].id })
        let didCreateEntry = matchingIndices.isEmpty
        let previouslySelected = !bulkOperationMembership.isDisjoint(with: matchedIDs)

        let baseEntry: PortalRegistryEntry
        if let firstMatch = matchingIndices.first {
            baseEntry = matchingIndices.dropFirst().reduce(entries[firstMatch]) { partial, index in
                mergeExistingEntries(partial, entries[index])
            }
        } else {
            baseEntry = PortalRegistryEntry(
                id: canonicalID,
                connectionState: .bearerVerificationRequired(
                    reason: "Authenticated Portal state is being recorded."
                )
            )
        }

        let baseEvidenceDate = evidenceDate(for: baseEntry)
        let incomingIsCurrent = record.verifiedAt >= baseEvidenceDate
        var reconciled = baseEntry
        reconciled.id = canonicalID

        let allEndpoints = endpointHistory(
            existing: baseEntry,
            incoming: record.endpoint,
            verifiedAt: record.verifiedAt
        )
        reconciled.endpoint = activeEndpoint(
            from: allEndpoints,
            preferred: incomingIsCurrent ? record.endpoint : baseEntry.endpoint,
            preservePreferred: !incomingIsCurrent
        )
        reconciled.discoveredEndpoints = orderedEndpointHistory(
            allEndpoints,
            active: reconciled.endpoint
        )

        let incomingReferences = validCredentialReferences(record.credentialReferences)
        reconciled.credentialReferences = validCredentialReferences(
            baseEntry.credentialReferences + incomingReferences
        )

        if incomingIsCurrent || reconciled.identity == nil {
            reconciled.identity = mergedIdentity(
                existing: reconciled.identity,
                incoming: record.identity,
                canonicalID: canonicalID
            )
            reconciled.capabilities = record.capabilities ?? reconciled.capabilities
            reconciled.policyMetadata = record.policyMetadata
            reconciled.lastConfirmedStatus = record.status
            reconciled.lastSuccessfulContact = latestDate(
                baseEntry.lastSuccessfulContact,
                record.verifiedAt
            )
            reconciled.connectionState = .online(
                lastRefresh: record.verifiedAt,
                latencyMs: record.status.responseTimeMilliseconds ?? 0
            )
        } else {
            if var identity = reconciled.identity {
                identity.portalID = canonicalID
                reconciled.identity = identity
            }
            reconciled.lastSuccessfulContact = latestDate(
                baseEntry.lastSuccessfulContact,
                record.verifiedAt
            )
        }

        // An authenticated observation is a successful contact even when the
        // server omitted a status timestamp. Never let an older failed probe
        // overwrite this state; failed probes use markOffline instead.
        if incomingIsCurrent, reconciled.lastSuccessfulContact == nil {
            reconciled.lastSuccessfulContact = record.verifiedAt
        }

        entries.removeAll { matchedIDs.contains($0.id) }
        entries.append(reconciled)

        bulkOperationMembership.subtract(matchedIDs)
        if previouslySelected {
            bulkOperationMembership.insert(canonicalID)
        }

        return PortalRegistryReconciliation(
            entry: reconciled,
            mergedPortalIDs: matchedIDs,
            didCreateEntry: didCreateEntry
        )
    }

    /// Retains all confirmed metadata and the last successful contact while
    /// exposing a retry/editable offline state. This method cannot replace an
    /// endpoint or identity because it accepts no new authenticated data.
    @discardableResult
    mutating func markOffline(
        for portalID: PortalID,
        reason: String
    ) -> PortalRegistryEntry? {
        guard let index = entries.firstIndex(where: { $0.id == portalID }) else {
            return nil
        }

        let entry = entries[index]
        let lastContact = entry.lastSuccessfulContact
            ?? entry.lastConfirmedStatus?.lastUpdatedAt
            ?? entry.endpoint?.lastAuthenticatedAt
        entries[index].connectionState = .offline(
            lastContact: lastContact,
            reason: safeOfflineReason(reason)
        )
        return entries[index]
    }

    /// Marks a known entry offline from a failed candidate probe. The candidate
    /// may use a different temporary PortalID, so identity and endpoint fallback
    /// matching are supported, but no candidate metadata is copied into state.
    @discardableResult
    mutating func markOffline(
        matching identity: PortalIdentity?,
        endpoint: LANEndpoint?,
        reason: String
    ) -> PortalRegistryEntry? {
        guard identity != nil || endpoint != nil,
              let index = entries.firstIndex(where: { entry in
                  matches(entry, identity: identity, endpoint: endpoint)
              }) else {
            return nil
        }

        return markOffline(for: entries[index].id, reason: reason)
    }

    /// Removes one canonical entry and its transient bulk membership. Credential
    /// references are returned to the application coordinator, which deletes
    /// only Keychain items that are not still referenced by another entry.
    mutating func remove(_ portalID: PortalID) -> PortalRegistryRemoval? {
        let matchingEntries = entries.filter { $0.id == portalID }
        guard !matchingEntries.isEmpty else { return nil }

        let references = validCredentialReferences(
            matchingEntries.flatMap(\.credentialReferences)
        )
        let wasMember = bulkOperationMembership.remove(portalID) != nil
        entries.removeAll { $0.id == portalID }

        return PortalRegistryRemoval(
            portalID: portalID,
            credentialReferences: references,
            wasMemberOfBulkOperation: wasMember
        )
    }

    /// Records a newly paired session after its opaque Keychain value has been
    /// persisted. Existing bearer identity/health state is deliberately kept;
    /// a remote session never becomes bearer assurance.
    @discardableResult
    mutating func recordRemoteSession(
        for portalID: PortalID,
        credentialReference: CredentialReference,
        pairedAt: Date
    ) -> PortalRegistryEntry? {
        guard let index = entries.firstIndex(where: { $0.id == portalID }) else {
            return nil
        }

        var entry = entries[index]
        entry.credentialReferences = validCredentialReferences(
            entry.credentialReferences + [credentialReference]
        )

        switch entry.connectionState {
        case .online, .bearerAuthenticated:
            // A session adds an approved remote capability but does not weaken
            // or replace already-confirmed bearer identity/health state.
            break
        case .reauthenticationRequired(let kind, _)
            where kind == .verifiedBearer:
            // The bearer still needs replacement even though the session was
            // paired successfully; preserve that assurance state.
            break
        default:
            entry.connectionState = .remoteSessionPaired(lastPairedAt: pairedAt)
        }

        entries[index] = entry
        return entry
    }

    /// Records a successful approved remote-session operation without creating
    /// identity or bearer health. Confirmed bearer state, when present, remains
    /// untouched.
    @discardableResult
    mutating func recordRemoteSessionRead(
        for portalID: PortalID,
        readAt: Date
    ) -> PortalRegistryEntry? {
        guard let index = entries.firstIndex(where: { $0.id == portalID }) else {
            return nil
        }

        var entry = entries[index]
        switch entry.connectionState {
        case .online, .bearerAuthenticated:
            break
        case .reauthenticationRequired(let kind, _)
            where kind == .verifiedBearer:
            break
        default:
            entry.connectionState = .remoteSessionReady(lastReadAt: readAt)
        }
        entries[index] = entry
        return entry
    }

    /// Marks only the affected credential kind as needing replacement while
    /// preserving identity, endpoint history, confirmed status, and all opaque
    /// credential references.
    @discardableResult
    mutating func markReauthenticationRequired(
        for portalID: PortalID,
        kind: CredentialKind,
        reason: String
    ) -> PortalRegistryEntry? {
        guard let index = entries.firstIndex(where: { $0.id == portalID }) else {
            return nil
        }
        entries[index].connectionState = .reauthenticationRequired(
            kind: kind,
            reason: safeOfflineReason(reason)
        )
        return entries[index]
    }
}

private extension PortalRegistry {
    struct EndpointKey: Hashable {
        let host: String
        let port: UInt16
        let zone: String?

        init(_ endpoint: LANEndpoint) {
            host = Self.normalizeHost(endpoint.hostOrAddress)
            port = endpoint.port
            zone = endpoint.interfaceZone?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
        }

        private static func normalizeHost(_ value: String) -> String {
            var normalized = value
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            if normalized.hasPrefix("[") && normalized.hasSuffix("]") {
                normalized.removeFirst()
                normalized.removeLast()
            }
            if normalized.hasSuffix(".") {
                normalized.removeLast()
            }
            return normalized
        }
    }

    func matches(
        _ entry: PortalRegistryEntry,
        record: AuthenticatedPortalRecord
    ) -> Bool {
        if entry.id == record.identity.portalID {
            return true
        }
        if let existingIdentity = entry.identity,
           sameAuthenticatedIdentity(existingIdentity, record.identity) {
            return true
        }
        guard let incomingEndpoint = record.endpoint else { return false }
        return endpointHistory(for: entry).contains { EndpointKey($0) == EndpointKey(incomingEndpoint) }
    }

    func matches(
        _ entry: PortalRegistryEntry,
        identity: PortalIdentity?,
        endpoint: LANEndpoint?
    ) -> Bool {
        if let identity {
            if entry.id == identity.portalID {
                return true
            }
            if let existingIdentity = entry.identity,
               sameAuthenticatedIdentity(existingIdentity, identity) {
                return true
            }
        }
        guard let endpoint else { return false }
        return endpointHistory(for: entry).contains { EndpointKey($0) == EndpointKey(endpoint) }
    }

    func sameAuthenticatedIdentity(
        _ lhs: PortalIdentity,
        _ rhs: PortalIdentity
    ) -> Bool {
        if let leftSerial = normalizedIdentityPart(lhs.serial),
           let rightSerial = normalizedIdentityPart(rhs.serial) {
            return leftSerial == rightSerial
        }

        guard let leftStableKey = stableIdentityKey(lhs),
              let rightStableKey = stableIdentityKey(rhs) else {
            return false
        }
        return leftStableKey == rightStableKey
    }

    /// `device` is used only as a fallback when it is a concrete, non-generic
    /// hardware identifier. A display name or model alone is never an identity
    /// key, preventing two similarly named Portals from being merged.
    func stableIdentityKey(_ identity: PortalIdentity) -> String? {
        guard let device = normalizedIdentityPart(identity.device),
              !["unknown", "device", "portal"].contains(device) else {
            return nil
        }
        let model = normalizedIdentityPart(identity.rawModel)
            ?? normalizedIdentityPart(identity.model)
            ?? "unknown"
        return "device:\(device)|model:\(model)"
    }

    func mergedIdentity(
        existing: PortalIdentity?,
        incoming: PortalIdentity,
        canonicalID: PortalID
    ) -> PortalIdentity {
        var merged = incoming
        if let existing {
            if merged.serial == nil { merged.serial = existing.serial }
            if merged.device == nil { merged.device = existing.device }
            if merged.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.name = existing.name
            }
            if merged.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.model = existing.model
            }
            if merged.rawModel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.rawModel = existing.rawModel
            }
            if merged.androidAPILevel == nil {
                merged.androidAPILevel = existing.androidAPILevel
            }
            if merged.immortalVersion == nil {
                merged.immortalVersion = existing.immortalVersion
            }
        }
        merged.portalID = canonicalID
        return merged
    }

    func normalizedIdentityPart(_ value: String?) -> String? {
        guard let value else { return nil }
        let normalized = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        return normalized.isEmpty ? nil : normalized
    }

    func mergeExistingEntries(
        _ lhs: PortalRegistryEntry,
        _ rhs: PortalRegistryEntry
    ) -> PortalRegistryEntry {
        let lhsIsCurrent = evidenceDate(for: lhs) >= evidenceDate(for: rhs)
        let current = lhsIsCurrent ? lhs : rhs
        let other = lhsIsCurrent ? rhs : lhs
        var merged = current

        if merged.identity == nil {
            merged.identity = other.identity
        }
        merged.capabilities = merged.capabilities ?? other.capabilities
        merged.policyMetadata = current.policyMetadata
        merged.lastSuccessfulContact = latestDate(
            lhs.lastSuccessfulContact,
            rhs.lastSuccessfulContact
        )
        if merged.lastConfirmedStatus == nil {
            merged.lastConfirmedStatus = other.lastConfirmedStatus
        }
        merged.credentialReferences = validCredentialReferences(
            lhs.credentialReferences + rhs.credentialReferences
        )

        let endpoints = endpointHistory(for: lhs) + endpointHistory(for: rhs)
        merged.endpoint = activeEndpoint(from: endpoints, preferred: current.endpoint)
        merged.discoveredEndpoints = orderedEndpointHistory(
            endpoints,
            active: merged.endpoint
        )
        return merged
    }

    func endpointHistory(
        existing: PortalRegistryEntry,
        incoming: LANEndpoint?,
        verifiedAt: Date
    ) -> [LANEndpoint] {
        var endpoints = endpointHistory(for: existing)
        if var incoming {
            incoming.lastAuthenticatedAt = latestDate(
                incoming.lastAuthenticatedAt,
                verifiedAt
            )
            endpoints.append(incoming)
        }
        return endpoints
    }

    func endpointHistory(for entry: PortalRegistryEntry) -> [LANEndpoint] {
        var endpoints = entry.discoveredEndpoints
        if let endpoint = entry.endpoint {
            endpoints.append(endpoint)
        }
        return endpoints
    }

    func activeEndpoint(
        from endpoints: [LANEndpoint],
        preferred: LANEndpoint?,
        preservePreferred: Bool = false
    ) -> LANEndpoint? {
        let unique = uniqueEndpoints(endpoints)
        if preservePreferred,
           let preferred,
           let preferredEndpoint = endpoint(from: unique, matching: preferred),
           preferredEndpoint.lastAuthenticatedAt == nil {
            return preferredEndpoint
        }
        if let newestAuthenticated = unique
            .filter({ $0.lastAuthenticatedAt != nil })
            .max(by: { ($0.lastAuthenticatedAt ?? .distantPast) < ($1.lastAuthenticatedAt ?? .distantPast) }) {
            return newestAuthenticated
        }
        if let preferred {
            return endpoint(from: unique, matching: preferred) ?? preferred
        }
        return unique.first
    }

    func orderedEndpointHistory(
        _ endpoints: [LANEndpoint],
        active: LANEndpoint?
    ) -> [LANEndpoint] {
        let unique = uniqueEndpoints(endpoints)
        let activeKey = active.map(EndpointKey.init)
        return unique.sorted { lhs, rhs in
            let lhsIsActive = activeKey == EndpointKey(lhs)
            let rhsIsActive = activeKey == EndpointKey(rhs)
            if lhsIsActive != rhsIsActive {
                return lhsIsActive
            }

            let lhsDate = lhs.lastAuthenticatedAt ?? .distantPast
            let rhsDate = rhs.lastAuthenticatedAt ?? .distantPast
            if lhsDate != rhsDate {
                return lhsDate > rhsDate
            }
            return EndpointKey(lhs).description < EndpointKey(rhs).description
        }
    }

    func uniqueEndpoints(_ endpoints: [LANEndpoint]) -> [LANEndpoint] {
        var result: [LANEndpoint] = []
        var indicesByKey: [EndpointKey: Int] = [:]

        for endpoint in endpoints {
            let key = EndpointKey(endpoint)
            if let index = indicesByKey[key] {
                let current = result[index]
                let currentDate = current.lastAuthenticatedAt ?? .distantPast
                let incomingDate = endpoint.lastAuthenticatedAt ?? .distantPast
                if incomingDate > currentDate {
                    result[index] = endpoint
                }
            } else {
                indicesByKey[key] = result.count
                result.append(endpoint)
            }
        }
        return result
    }

    func endpoint(
        from endpoints: [LANEndpoint],
        matching target: LANEndpoint
    ) -> LANEndpoint? {
        endpoints.first { EndpointKey($0) == EndpointKey(target) }
    }

    func evidenceDate(for entry: PortalRegistryEntry) -> Date {
        var dates: [Date] = []
        if let date = entry.lastSuccessfulContact {
            dates.append(date)
        }
        if let date = entry.lastConfirmedStatus?.lastUpdatedAt {
            dates.append(date)
        }
        dates.append(contentsOf: endpointHistory(for: entry).compactMap(\.lastAuthenticatedAt))

        switch entry.connectionState {
        case .bearerAuthenticated(_, let verifiedAt):
            dates.append(verifiedAt)
        case .online(let lastRefresh, _):
            dates.append(lastRefresh)
        case .remoteSessionPaired(let lastPairedAt):
            dates.append(lastPairedAt)
        case .remoteSessionReady(let lastReadAt):
            if let lastReadAt { dates.append(lastReadAt) }
        case .offline(let lastContact, _):
            if let lastContact { dates.append(lastContact) }
        case .discovered, .resolving, .lanValidated, .pairingRequired,
             .bearerVerificationRequired, .provisioning, .reauthenticationRequired,
             .unsupported, .error:
            break
        }
        return dates.max() ?? .distantPast
    }

    func latestDate(_ lhs: Date?, _ rhs: Date?) -> Date? {
        switch (lhs, rhs) {
        case let (left?, right?): return max(left, right)
        case let (left?, nil): return left
        case let (nil, right?): return right
        case (nil, nil): return nil
        }
    }

    func validCredentialReferences(
        _ references: [CredentialReference]
    ) -> [CredentialReference] {
        var seen = Set<CredentialReference>()
        return references.filter { reference in
            guard !reference.namespace.isEmpty,
                  !reference.identifier.isEmpty,
                  !reference.namespace.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
                  !reference.identifier.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
                  seen.insert(reference).inserted else {
                return false
            }
            return true
        }
    }

    func safeOfflineReason(_ reason: String) -> String {
        let trimmed = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              !trimmed.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
            return "The Portal could not be reached."
        }
        return trimmed
    }
}

private extension PortalRegistry.EndpointKey {
    var description: String {
        "\(host):\(port):\(zone ?? "")"
    }
}
