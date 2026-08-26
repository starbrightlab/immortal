/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Persistence/application boundary for the local Portal Registry. The
/// coordinator serializes registry mutations, saves only non-secret snapshots,
/// and delegates sensitive cleanup to the injected Keychain boundary.
actor PortalRegistryCoordinator {
    private let registryStore: any RegistryStore
    private let credentialStore: any CredentialStore
    private var registry: PortalRegistry?
    private let initialBulkOperationMembership: Set<PortalID>

    init(
        registryStore: any RegistryStore,
        credentialStore: any CredentialStore,
        bulkOperationMembership: Set<PortalID> = []
    ) {
        self.registryStore = registryStore
        self.credentialStore = credentialStore
        self.initialBulkOperationMembership = bulkOperationMembership
    }

    /// Loads the non-secret registry. Loading is explicit so callers can show a
    /// persistence error without accidentally starting a network operation.
    func load() async throws -> RegistrySnapshot {
        let snapshot = try await registryStore.load()
        registry = PortalRegistry(
            snapshot: snapshot,
            bulkOperationMembership: initialBulkOperationMembership
        )
        return snapshot
    }

    func snapshot() async throws -> RegistrySnapshot {
        try await ensureLoaded()
        return registry?.snapshot ?? RegistrySnapshot()
    }

    func entries() async throws -> [PortalRegistryEntry] {
        try await ensureLoaded()
        return registry?.entries ?? []
    }

    func bulkOperationMembership() async throws -> Set<PortalID> {
        try await ensureLoaded()
        return registry?.bulkOperationMembership ?? []
    }

    /// Updates only transient bulk selection; it is intentionally not persisted
    /// as registry metadata and is filtered to currently managed entries.
    func setBulkOperationMembership(_ portalIDs: Set<PortalID>) async throws {
        try await ensureLoaded()
        registry?.setBulkOperationMembership(portalIDs)
    }

    func addToBulkOperation(_ portalID: PortalID) async throws {
        try await ensureLoaded()
        registry?.addToBulkOperation(portalID)
    }

    /// Creates the non-secret pairing candidate required by direct manual PIN
    /// onboarding. A confirmed existing entry is never overwritten here.
    @discardableResult
    func stageManualEndpoint(
        for portalID: PortalID,
        endpoint: LANEndpoint
    ) async throws -> PortalRegistryEntry {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        let entry = updated.stageManualEndpoint(
            for: portalID,
            endpoint: endpoint
        )
        try await registryStore.save(updated.snapshot)
        registry = updated
        return entry
    }

    /// Commits an admitted manual endpoint only after the caller has completed
    /// bearer `/info` verification or PIN pairing successfully. Existing
    /// confirmed identity/status/credential metadata is otherwise retained.
    @discardableResult
    func recordManualEndpoint(
        for portalID: PortalID,
        endpoint: LANEndpoint,
        authenticatedAt: Date
    ) async throws -> PortalRegistryEntry? {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        guard let entry = updated.recordManualEndpoint(
            for: portalID,
            endpoint: endpoint,
            authenticatedAt: authenticatedAt
        ) else {
            return nil
        }
        try await registryStore.save(updated.snapshot)
        registry = updated
        return entry
    }

    func reconcile(
        _ record: AuthenticatedPortalRecord
    ) async throws -> PortalRegistryReconciliation {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        let result = updated.reconcile(record)
        try await registryStore.save(updated.snapshot)
        registry = updated
        return result
    }

    /// Records a failed reachability attempt without copying candidate data into
    /// the registry. The existing identity, endpoint history, credentials, and
    /// last-confirmed status remain intact and the entry becomes retryable.
    @discardableResult
    func markOffline(
        for portalID: PortalID,
        reason: String
    ) async throws -> PortalRegistryEntry? {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        guard let entry = updated.markOffline(for: portalID, reason: reason) else {
            return nil
        }
        try await registryStore.save(updated.snapshot)
        registry = updated
        return entry
    }

    /// Candidate-oriented offline path for discovery/manual probes whose
    /// temporary PortalID differs from the canonical registry ID.
    @discardableResult
    func markOffline(
        matching identity: PortalIdentity?,
        endpoint: LANEndpoint?,
        reason: String
    ) async throws -> PortalRegistryEntry? {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        guard let entry = updated.markOffline(
            matching: identity,
            endpoint: endpoint,
            reason: reason
        ) else {
            return nil
        }
        try await registryStore.save(updated.snapshot)
        registry = updated
        return entry
    }

    /// Removes a Portal only after its no-longer-shared Keychain references have
    /// been deleted. A failed Keychain cleanup or registry save leaves the
    /// in-memory registry entry available for a retry.
    @discardableResult
    func remove(_ portalID: PortalID) async throws -> PortalRegistryRemoval? {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        guard let removal = updated.remove(portalID) else {
            return nil
        }

        let referencesStillInUse = Set(
            updated.entries.flatMap(\.credentialReferences)
        )
        let referencesToDelete = removal.credentialReferences.filter {
            !referencesStillInUse.contains($0)
        }

        var firstCleanupError: Error?
        for reference in referencesToDelete {
            do {
                try await credentialStore.delete(reference)
            } catch {
                // Attempt every item so one stale Keychain entry does not leave
                // other removable credentials behind. The first error is
                // rethrown after all cleanup attempts have been made.
                if firstCleanupError == nil {
                    firstCleanupError = error
                }
            }
        }
        if let firstCleanupError {
            throw firstCleanupError
        }

        try await registryStore.save(updated.snapshot)
        registry = updated
        return removal
    }

    /// Records a remote-session reference only after the caller has completed
    /// the Keychain write. A failed registry save leaves the prior aggregate
    /// untouched so the caller can roll back the new Keychain value.
    @discardableResult
    func recordRemoteSession(
        for portalID: PortalID,
        credentialReference: CredentialReference,
        pairedAt: Date
    ) async throws -> PortalRegistryEntry? {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        guard let entry = updated.recordRemoteSession(
            for: portalID,
            credentialReference: credentialReference,
            pairedAt: pairedAt
        ) else {
            return nil
        }
        try await registryStore.save(updated.snapshot)
        registry = updated
        return entry
    }

    /// Records a successful approved session operation without promoting the
    /// session to bearer identity or health.
    @discardableResult
    func recordRemoteSessionRead(
        for portalID: PortalID,
        readAt: Date
    ) async throws -> PortalRegistryEntry? {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        guard let entry = updated.recordRemoteSessionRead(
            for: portalID,
            readAt: readAt
        ) else {
            return nil
        }
        try await registryStore.save(updated.snapshot)
        registry = updated
        return entry
    }

    /// Retains all non-secret Portal metadata while marking one credential kind
    /// as requiring replacement after unauthorized/revoked use.
    @discardableResult
    func markReauthenticationRequired(
        for portalID: PortalID,
        kind: CredentialKind,
        reason: String
    ) async throws -> PortalRegistryEntry? {
        try await ensureLoaded()
        var updated = registry ?? PortalRegistry()
        guard let entry = updated.markReauthenticationRequired(
            for: portalID,
            kind: kind,
            reason: reason
        ) else {
            return nil
        }
        try await registryStore.save(updated.snapshot)
        registry = updated
        return entry
    }

    private func ensureLoaded() async throws {
        guard registry == nil else { return }
        _ = try await load()
    }
}
