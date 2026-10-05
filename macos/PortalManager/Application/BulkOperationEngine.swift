/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Inputs the engine needs for one bulk target. Everything is non-secret;
/// credentials are resolved per target at dispatch time through the session
/// coordinator and Keychain boundary.
struct BulkOperationTarget: Sendable, Equatable, Hashable {
    let portalID: PortalID
    let admissionRequest: ConnectionAdmissionRequest

    init(portalID: PortalID, admissionRequest: ConnectionAdmissionRequest) {
        self.portalID = portalID
        self.admissionRequest = admissionRequest
    }
}

/// Builds target-specific preflight summaries (design §9). The builder never
/// substitutes a credential or a guessed policy for an ineligible target, and
/// it never copies one Portal's accepted fields to another.
struct BulkPreflightBuilder: Sendable {
    func build(
        operation: BulkOperationKind,
        draftValues: [String: JSONValue],
        targets: [BulkOperationTarget],
        entries: [PortalID: PortalRegistryEntry],
        sessions: [PortalID: PortalSessionSnapshot],
        schemas: [PortalID: SettingsRegistrySchema]
    ) -> BulkPreflightSummary {
        let preflights = targets.map { target -> BulkTargetPreflight in
            let entry = entries[target.portalID]
            let session = sessions[target.portalID]
            let schema = schemas[target.portalID]

            var reasons: [String] = []
            let displayName = entry?.identity?.name ?? "Portal"

            // Connection/auth state gates. Eligible assurance states are the
            // ones that can carry the approved operations; everything else is
            // named explicitly instead of guessed.
            if entry == nil {
                reasons.append("The Portal is not in the managed registry.")
            }
            if let connectionState = session?.connectionState {
                switch connectionState {
                case .bearerAuthenticated, .online, .remoteSessionPaired, .remoteSessionReady:
                    break
                case .offline:
                    reasons.append("The Portal is offline.")
                case .reauthenticationRequired:
                    reasons.append("The selected credential requires reauthentication.")
                default:
                    reasons.append("The Portal has not completed authentication.")
                }
            } else {
                reasons.append("No credential state is available for this Portal.")
            }

            // Credential scope for the requested operation.
            if case .approvedAction = operation,
               session?.availableCredentialKinds.contains(.verifiedBearer) != true {
                reasons.append("A verified bearer credential is required for this operation.")
            }

            // Capability gates evaluated from that target's own `/info`.
            var capabilityAvailable = true
            switch operation {
            case .settingsApply:
                capabilityAvailable = entry?.capabilities?.settingsRegistry == true
            case .approvedAction(let action):
                switch action {
                case .identify:
                    capabilityAvailable = entry?.capabilities?.identify == true
                case .reaffirm:
                    capabilityAvailable = entry?.capabilities?.reaffirm == true
                }
            }
            if !capabilityAvailable {
                reasons.append("The Portal does not advertise this capability.")
            }

            // Per-target policy classification and schema reduction.
            var policyClassified = true
            var constraints: [String] = []
            var omittedFields: [String] = []
            var reducedValues: [String: JSONValue] = [:]

            if case .settingsApply(let domainID) = operation {
                if let schema, let domain = schema.domains.first(where: { $0.id == domainID }) {
                    let validation = SettingsCoordinator.validateDraft(
                        SettingsDomainDraft(domainID: domainID, values: draftValues),
                        against: schema,
                        policy: .default
                    )
                    reducedValues = validation.acceptedValues
                    omittedFields = validation.omittedKeys + validation.unsupportedKeys
                    let rejected = validation.rejectedKeys
                    if rejected.count == validation.fields.count && !draftValues.isEmpty {
                        // Every field was refused locally for this target; the
                        // reduced plan would silently become a no-op.
                        reasons.append(
                            "None of the selected fields are valid for this Portal."
                        )
                    }
                    policyClassified = validation.fields.allSatisfy { field in
                        field.outcome != .unsupported && field.outcome != .rejected
                    }
                    for control in domain.controls where !control.readOnly {
                        constraints.append(
                            "\(control.key): \(Self.constraintDescription(control))"
                        )
                    }
                } else if schema == nil {
                    reasons.append("No confirmed settings schema exists for this Portal.")
                    policyClassified = false
                } else {
                    reasons.append(
                        "The confirmed schema does not contain the '\(domainID)' domain."
                    )
                    policyClassified = false
                }
            }

            return BulkTargetPreflight(
                portalID: target.portalID,
                displayName: displayName,
                connectionState: session?.connectionState,
                credentialScope: session?.selectedCredential
                    ?? Self.fallbackCredentialScope(session?.availableCredentialKinds ?? []),
                capabilityAvailable: capabilityAvailable,
                policyClassified: policyClassified,
                schemaConstraints: constraints.sorted(),
                omittedFields: omittedFields.sorted(),
                reducedValues: reducedValues,
                affectsSensitiveValue: operation.affectedSensitiveDomain != nil,
                ineligibilityReasons: reasons.sorted()
            )
        }

        return BulkPreflightSummary(operation: operation, targets: preflights)
    }
}

extension BulkPreflightBuilder {
    /// Deterministic scope fallback: bearer first, then session. Credential
    /// kinds are labels, never comparable values.
    static func fallbackCredentialScope(
        _ available: Set<CredentialKind>
    ) -> CredentialKind? {
        if available.contains(.verifiedBearer) { return .verifiedBearer }
        if available.contains(.remoteSession) { return .remoteSession }
        return nil
    }

    static func constraintDescription(_ control: SettingsControlSchema) -> String {
        var parts: [String] = []
        if let min = control.min { parts.append("min \(min)") }
        if let max = control.max { parts.append("max \(max)") }
        if let step = control.step { parts.append("step \(step)") }
        if control.wrap == true { parts.append("wraps") }
        return parts.isEmpty ? control.type.rawValue ?? "value" : parts.joined(separator: ", ")
    }
}

// MARK: - Confirmation gate

/// Pure confirmation gate. Dispatch with an absent or mismatched confirmation
/// emits zero requests; there is no implicit "confirm all" path.
struct BulkConfirmationGate: Sendable {
    enum Decision: Equatable, Sendable {
        case dispatchAllowed
        case blockedMissingConfirmation
        case blockedStaleConfirmation
    }

    func evaluate(
        summary: BulkPreflightSummary,
        confirmation: BulkConfirmation?
    ) -> Decision {
        guard let confirmation else { return .blockedMissingConfirmation }
        return confirmation.summary == summary
            ? .dispatchAllowed
            : .blockedStaleConfirmation
    }
}

// MARK: - Engine

/// Serializes fan-out result storage without blocking an asynchronous worker.
private actor BulkResultBuffer {
    private var results: [BulkTargetResult?]

    init(count: Int) {
        results = [BulkTargetResult?](repeating: nil, count: count)
    }

    func store(_ result: BulkTargetResult, at offset: Int) {
        results[offset] = result
    }

    var completedResults: [BulkTargetResult] {
        results.compactMap(\.self)
    }
}

/// Dispatches one explicitly confirmed operation independently to each target
/// with bounded concurrency (design §9.2). Per-target failures never stop
/// eligible targets; results are aggregated truthfully and never copied.
actor BulkOperationEngine {
    struct Dependencies: Sendable {
        let registryCoordinator: PortalRegistryCoordinator
        let sessionCoordinator: PortalSessionCoordinator
        let settingsCoordinator: SettingsCoordinator
        let clock: any ManagerClock

        init(
            registryCoordinator: PortalRegistryCoordinator,
            sessionCoordinator: PortalSessionCoordinator,
            settingsCoordinator: SettingsCoordinator,
            clock: any ManagerClock
        ) {
            self.registryCoordinator = registryCoordinator
            self.sessionCoordinator = sessionCoordinator
            self.settingsCoordinator = settingsCoordinator
            self.clock = clock
        }
    }

    /// Progress event for UI projection.
    enum Event: Sendable, Equatable {
        case started(totalTargets: Int)
        case targetStarted(PortalID)
        case targetFinished(BulkTargetResult)
        case finished(BulkOperationReport)
        case blockedDispatch(reason: String)
    }

    private let dependencies: Dependencies
    private let preflightBuilder = BulkPreflightBuilder()
    private let confirmationGate = BulkConfirmationGate()
    private let maxConcurrentTargets: Int

    private var running = false
    private var cancelled = false

    init(dependencies: Dependencies, maxConcurrentTargets: Int = 4) {
        precondition(maxConcurrentTargets >= 1, "bounded fan-out requires at least one lane")
        self.dependencies = dependencies
        self.maxConcurrentTargets = maxConcurrentTargets
    }

    // MARK: Preflight (task 11.1)

    /// Builds the fresh, target-specific summary. Callers must present this to
    /// the operator before `run`.
    func preflight(
        operation: BulkOperationKind,
        draftValues: [String: JSONValue] = [:],
        targets: [BulkOperationTarget]
    ) async throws -> BulkPreflightSummary {
        guard !targets.isEmpty else {
            throw ManagerError.validation(
                field: "Bulk operation",
                reason: "Select at least one Portal before planning a bulk operation."
            )
        }

        var entries: [PortalID: PortalRegistryEntry] = [:]
        for entry in try await dependencies.registryCoordinator.entries() {
            entries[entry.id] = entry
        }

        var sessions: [PortalID: PortalSessionSnapshot] = [:]
        for target in targets {
            if let snapshot = try? await dependencies.sessionCoordinator
                .snapshot(for: target.portalID) {
                sessions[target.portalID] = snapshot
            }
        }

        var schemas: [PortalID: SettingsRegistrySchema] = [:]
        if case .settingsApply = operation {
            for target in targets {
                schemas[target.portalID] = await dependencies.settingsCoordinator
                    .confirmedSchema(for: target.portalID)
            }
        }

        return preflightBuilder.build(
            operation: operation,
            draftValues: draftValues,
            targets: targets,
            entries: entries,
            sessions: sessions,
            schemas: schemas
        )
    }

    // MARK: Confirmation (task 11.2)

    func evaluate(
        summary: BulkPreflightSummary,
        confirmation: BulkConfirmation?
    ) -> BulkConfirmationGate.Decision {
        confirmationGate.evaluate(summary: summary, confirmation: confirmation)
    }

    /// Requests cancellation. Targets that have not started are skipped; a
    /// currently executing protocol step finishes safely and stays visible.
    func cancel() {
        cancelled = true
    }

    var isCancelled: Bool { cancelled }

    // MARK: Dispatch (task 11.3)

    /// Runs the confirmed operation with bounded independent fan-out.
    func run(
        summary: BulkPreflightSummary,
        confirmation: BulkConfirmation?,
        targets: [BulkOperationTarget],
        progress: (@Sendable @MainActor (Event) -> Void)? = nil
    ) async throws -> BulkOperationReport {
        guard !running else {
            throw ManagerError.validation(
                field: "Bulk operation",
                reason: "A bulk operation is already running."
            )
        }
        running = true
        // `cancelled` is intentionally not reset here: a cancellation requested
        // before the run must still skip every not-yet-started target. The
        // flag re-arms after the report is built.
        defer { running = false }

        switch evaluate(summary: summary, confirmation: confirmation) {
        case .dispatchAllowed:
            break
        case .blockedMissingConfirmation:
            await notify(progress, .blockedDispatch(
                reason: "Bulk dispatch requires explicit confirmation. No request was sent."
            ))
            return BulkOperationReport(operation: summary.operation, results: [])
        case .blockedStaleConfirmation:
            await notify(progress, .blockedDispatch(
                reason: "The confirmation does not match the current preflight; re-run planning."
            ))
            return BulkOperationReport(operation: summary.operation, results: [])
        }

        await notify(progress, .started(totalTargets: targets.count))

        let reducedByPortal = Dictionary(
            uniqueKeysWithValues: summary.targets.map { ($0.portalID, $0.dispatchValues) }
        )

        // Bounded independent fan-out over lanes. The actor buffer keeps result
        // order stable without blocking asynchronous workers with NSLock.
        let buffer = BulkResultBuffer(count: targets.count)

        try await withThrowingTaskGroup(of: Void.self) { group in
            var iterator = targets.enumerated().makeIterator()

            // Local closure capturing self weakly is unnecessary; the engine is
            // an actor and addTask closures hop onto it for dispatchOne only.
            func addNext() {
                guard let (offset, target) = iterator.next() else { return }
                group.addTask { [dependencies] in
                    let engineCancelled = await self.isCancelled
                    if Task.isCancelled || engineCancelled {
                        let result = BulkTargetResult(
                            portalID: target.portalID,
                            outcome: .cancelled
                        )
                        await buffer.store(result, at: offset)
                        await self.notify(progress, .targetFinished(result))
                        return
                    }
                    await self.notify(progress, .targetStarted(target.portalID))
                    let result = await self.dispatchOne(
                        operation: summary.operation,
                        values: reducedByPortal[target.portalID] ?? [:],
                        target: target,
                        dependencies: dependencies
                    )
                    await buffer.store(result, at: offset)
                    await self.notify(progress, .targetFinished(result))
                }
            }

            for _ in 0..<min(maxConcurrentTargets, targets.count) {
                addNext()
            }

            while try await group.next() != nil {
                addNext()
            }
        }

        let results = await buffer.completedResults
        let report = BulkOperationReport(operation: summary.operation, results: results)
        cancelled = false
        await notify(progress, .finished(report))
        return report
    }

    // MARK: Per-target dispatch

    /// One target, one fresh route plan, one Keychain read. Results are built
    /// only from that target's own response/read-back.
    private nonisolated func dispatchOne(
        operation: BulkOperationKind,
        values: [String: JSONValue],
        target: BulkOperationTarget,
        dependencies: Dependencies
    ) async -> BulkTargetResult {
        do {
            switch operation {
            case .settingsApply(let domainID):
                let result = try await dependencies.settingsCoordinator.apply(
                    portalID: target.portalID,
                    admissionRequest: target.admissionRequest,
                    drafts: [SettingsDomainDraft(domainID: domainID, values: values)]
                )
                return Self.settingsResult(target.portalID, from: result)

            case .approvedAction(let action):
                _ = try await dependencies.sessionCoordinator.execute(
                    portalID: target.portalID,
                    admissionRequest: target.admissionRequest,
                    route: .action(action),
                    method: .post,
                    credential: .verifiedBearer
                )
                return BulkTargetResult(
                    portalID: target.portalID,
                    outcome: .success,
                    appliedKeys: [action.rawValue],
                    readBackConfirmedKeys: [action.rawValue]
                )
            }
        } catch is CancellationError {
            return BulkTargetResult(portalID: target.portalID, outcome: .cancelled)
        } catch let error as ManagerError {
            return BulkTargetResult(
                portalID: target.portalID,
                outcome: .failure,
                error: error
            )
        } catch {
            return BulkTargetResult(
                portalID: target.portalID,
                outcome: .failure,
                error: ManagerError.validation(
                    field: "Bulk operation",
                    reason: "The target operation failed before completion."
                )
            )
        }
    }

    private static func settingsResult(
        _ portalID: PortalID,
        from result: SettingsApplyResult
    ) -> BulkTargetResult {
        let appliedKeys = result.domains.flatMap(\.appliedKeys).sorted()
        let readBackKeys = Set(result.domains.flatMap { domain in
            (domain.confirmedDomain?.controls ?? []).compactMap(\.key)
        }).sorted()

        let outcome: BulkTargetOutcomeKind
        if result.isSuccessful {
            outcome = .success
        } else if appliedKeys.isEmpty {
            outcome = .failure
        } else {
            outcome = .partial
        }

        return BulkTargetResult(
            portalID: portalID,
            outcome: outcome,
            appliedKeys: appliedKeys,
            readBackConfirmedKeys: readBackKeys,
            error: result.errors.first
        )
    }

    private nonisolated func notify(
        _ progress: (@Sendable @MainActor (Event) -> Void)?,
        _ event: Event
    ) async {
        guard let progress else { return }
        await MainActor.run { progress(event) }
    }
}
