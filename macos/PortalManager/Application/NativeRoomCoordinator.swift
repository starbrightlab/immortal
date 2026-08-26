/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The exact policy surface for Native Room Link. No other Immortal control is
/// approved for this feature, even if the returned Settings Registry offers it.
extension SettingsPolicyLookup {
    static let nativeRoomLink = SettingsPolicyLookup(entries: [
        SettingsPolicyEntry(
            domainID: NativeRoomPlan.domainID,
            controlKey: NativeRoomPlan.modeKey,
            classification: .approvedEditable(
                route: .remoteSettings,
                bulk: .allowed,
                evidence: "native-room-link-mode"
            ),
            fieldPresence: .required
        ),
        SettingsPolicyEntry(
            domainID: NativeRoomPlan.domainID,
            controlKey: NativeRoomPlan.peerHostKey,
            classification: .approvedEditable(
                route: .remoteSettings,
                bulk: .allowed,
                evidence: "native-room-link-peer-host"
            ),
            fieldPresence: .required
        ),
    ])
}

protocol NativeRoomSettingsDispatching: Sendable {
    func apply(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        draft: SettingsDomainDraft,
        schema: SettingsRegistrySchema?
    ) async throws -> SettingsApplyResult
}

private struct LiveNativeRoomSettingsDispatcher: NativeRoomSettingsDispatching {
    let coordinator: SettingsCoordinator

    func apply(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        draft: SettingsDomainDraft,
        schema: SettingsRegistrySchema?
    ) async throws -> SettingsApplyResult {
        try await coordinator.apply(
            portalID: portalID,
            admissionRequest: admissionRequest,
            drafts: [draft],
            schema: schema
        )
    }
}

private struct LiveNativeRoomSchemaReader {
    let coordinator: SettingsCoordinator

    func read(for participant: NativeRoomParticipant) async throws -> SettingsRegistrySchema? {
        let admission = try ConnectionAdmissionRequest(
            rawEndpoint: participant.endpointHostOrAddress,
            serviceKind: .portal,
            protocolName: "http",
            defaultPort: LANEndpoint.defaultPortalAgentPort,
            source: .authenticatedRefresh
        )
        return try await coordinator.refresh(
            portalID: participant.portalID,
            admissionRequest: admission
        )
    }
}

private struct LiveNativeRoomInfoReader {
    let coordinator: PortalSessionCoordinator

    func read(
        for participant: NativeRoomParticipant
    ) async throws -> [String: JSONValue]? {
        let admission = try ConnectionAdmissionRequest(
            rawEndpoint: participant.endpointHostOrAddress,
            serviceKind: .portal,
            protocolName: "http",
            defaultPort: LANEndpoint.defaultPortalAgentPort,
            source: .authenticatedRefresh
        )
        let response = try await coordinator.execute(
            portalID: participant.portalID,
            admissionRequest: admission,
            route: .info,
            method: .get
        )
        guard case let .object(fields) = response.payload,
              case let .object(roomLink) = fields["roomLink"] else {
            return nil
        }
        return roomLink
    }
}

struct NativeRoomDispatchContext: Sendable {
    let portalID: PortalID
    let name: String
    let admissionRequest: ConnectionAdmissionRequest
    let draft: SettingsDomainDraft
    let schema: SettingsRegistrySchema?
}

actor NativeRoomCoordinator {
    private let dispatcher: any NativeRoomSettingsDispatching
    private let liveSchemaReader: LiveNativeRoomSchemaReader?
    private let customSchemaReader: (@Sendable (NativeRoomParticipant) async throws -> SettingsRegistrySchema)?
    private let infoReader: (@Sendable (NativeRoomParticipant) async throws -> [String: JSONValue]?)?

    init(
        settingsCoordinator: SettingsCoordinator,
        schemaReader: (@Sendable (NativeRoomParticipant) async throws -> SettingsRegistrySchema)? = nil
    ) {
        self.dispatcher = LiveNativeRoomSettingsDispatcher(coordinator: settingsCoordinator)
        self.liveSchemaReader = LiveNativeRoomSchemaReader(coordinator: settingsCoordinator)
        self.customSchemaReader = schemaReader
        self.infoReader = nil
    }

    init(
        settingsCoordinator: SettingsCoordinator,
        sessionCoordinator: PortalSessionCoordinator,
        schemaReader: (@Sendable (NativeRoomParticipant) async throws -> SettingsRegistrySchema)? = nil
    ) {
        self.dispatcher = LiveNativeRoomSettingsDispatcher(coordinator: settingsCoordinator)
        self.liveSchemaReader = LiveNativeRoomSchemaReader(coordinator: settingsCoordinator)
        self.customSchemaReader = schemaReader
        let reader = LiveNativeRoomInfoReader(coordinator: sessionCoordinator)
        self.infoReader = { participant in try await reader.read(for: participant) }
    }

    init(
        dispatcher: any NativeRoomSettingsDispatching,
        infoReader: (@Sendable (NativeRoomParticipant) async throws -> [String: JSONValue]?)? = nil
    ) {
        self.dispatcher = dispatcher
        self.liveSchemaReader = nil
        self.customSchemaReader = nil
        self.infoReader = infoReader
    }

    /// Validates a reviewed selection without sending anything.
    func prepare(
        sources: [NativeRoomParticipant],
        receivers: [NativeRoomParticipant]
    ) throws -> NativeRoomPlan {
        guard !sources.isEmpty else { throw NativeRoomPlanError.missingSource }
        guard sources.count == 1, let source = sources.first else {
            throw NativeRoomPlanError.duplicateSource
        }
        return try NativeRoomPlan(source: source, receivers: receivers)
    }

    /// Single-candidate spelling for application callers that have already
    /// resolved one source row from the registry.
    func prepare(
        source: NativeRoomParticipant?,
        receivers: [NativeRoomParticipant]
    ) throws -> NativeRoomPlan {
        guard let source else { throw NativeRoomPlanError.missingSource }
        return try NativeRoomPlan(source: source, receivers: receivers)
    }

    /// Applies each reviewed target independently. A source failure does not
    /// prevent receiver dispatch, and receiver failures do not undo earlier work.
    func apply(plan: NativeRoomPlan) async -> NativeRoomApplyReport {
        let sourceResult = await dispatch(
            participant: plan.source,
            draft: plan.sourceSettings(),
            operationName: "source"
        )
        let receiverResults = await withTaskGroup(
            of: NativeRoomTargetResult.self
        ) { group in
            for receiver in plan.receivers {
                group.addTask {
                    await self.dispatch(
                        participant: receiver,
                        draft: plan.receiverSettings(),
                        operationName: "receiver"
                    )
                }
            }

            var results: [NativeRoomTargetResult] = []
            for await result in group {
                results.append(result)
            }
            return results.sorted { $0.portalID.rawValue.uuidString < $1.portalID.rawValue.uuidString }
        }

        return NativeRoomApplyReport(
            sourceResult: sourceResult,
            receiverResults: receiverResults
        )
    }

    /// Stops every supplied participant independently. The caller supplies all
    /// participants so stop remains truthful even after a partially failed connect.
    func stopAll(participants: [NativeRoomParticipant]) async -> [NativeRoomTargetResult] {
        await withTaskGroup(of: NativeRoomTargetResult.self) { group in
            for participant in participants {
                group.addTask {
                    await self.dispatch(
                        participant: participant,
                        draft: NativeRoomPlan.stopSettings(),
                        operationName: "stop"
                    )
                }
            }

            var results: [NativeRoomTargetResult] = []
            for await result in group {
                results.append(result)
            }
            return results.sorted { $0.portalID.rawValue.uuidString < $1.portalID.rawValue.uuidString }
        }
    }

    private func dispatch(
        participant: NativeRoomParticipant,
        draft: SettingsDomainDraft,
        operationName: String
    ) async -> NativeRoomTargetResult {
        do {
            let schema = try await readSchema(participant)
            let admission = try ConnectionAdmissionRequest(
                rawEndpoint: participant.endpointHostOrAddress,
                serviceKind: .portal,
                protocolName: "http",
                defaultPort: LANEndpoint.defaultPortalAgentPort,
                source: .authenticatedRefresh
            )
            _ = try await dispatcher.apply(
                portalID: participant.portalID,
                admissionRequest: admission,
                draft: draft,
                schema: schema
            )
            if let runtimeError = await confirmRuntime(
                participant: participant,
                expectedMode: expectedRuntimeMode(for: draft)
            ) {
                return NativeRoomTargetResult(
                    portalID: participant.portalID,
                    name: participant.name,
                    error: runtimeError
                )
            }
            return NativeRoomTargetResult(portalID: participant.portalID, name: participant.name, error: nil)
        } catch let error as ManagerError {
            return NativeRoomTargetResult(portalID: participant.portalID, name: participant.name, error: error)
        } catch is CancellationError {
            return NativeRoomTargetResult(portalID: participant.portalID, name: participant.name, error: .cancelled)
        } catch let error as NativeRoomPlanError {
            // Stable sanitized copy; raw provider detail is intentionally dropped.
            _ = error.sanitizedMessage
            return NativeRoomTargetResult(
                portalID: participant.portalID,
                name: participant.name,
                error: .validation(
                    field: "Native Room Link",
                    reason: "The \(operationName) request was rejected before dispatch."
                )
            )
        } catch {
            return NativeRoomTargetResult(
                portalID: participant.portalID,
                name: participant.name,
                error: .validation(
                    field: "Native Room Link",
                    reason: "The \(operationName) request did not complete."
                )
            )
        }
    }

    private func readSchema(
        _ participant: NativeRoomParticipant
    ) async throws -> SettingsRegistrySchema? {
        if let customSchemaReader {
            return try await customSchemaReader(participant)
        }
        guard let liveSchemaReader else { return nil }
        return try await liveSchemaReader.read(for: participant)
    }

    private func expectedRuntimeMode(for draft: SettingsDomainDraft) -> String {
        switch draft.values[NativeRoomPlan.modeKey] {
        case .string(NativeRoomIntercomMode.broadcast.rawValue):
            return NativeRoomIntercomMode.broadcast.rawValue
        case .string(NativeRoomIntercomMode.receive.rawValue):
            return NativeRoomIntercomMode.receive.rawValue
        default:
            return NativeRoomIntercomMode.off.rawValue
        }
    }

    /// Older Portal builds do not advertise runtime state; their absence stays
    /// compatible. A newer build that reports an error or a stale mode fails the
    /// operation instead of letting accepted settings masquerade as live audio.
    private func confirmRuntime(
        participant: NativeRoomParticipant,
        expectedMode: String
    ) async -> ManagerError? {
        guard let reader = infoReader else { return nil }

        var reportedState: String?
        for attempt in 0..<8 {
            do {
                guard let roomLink = try await reader(participant),
                      case let .string(mode) = roomLink["mode"] else {
                    return nil
                }
                reportedState = {
                    if case let .string(state) = roomLink["state"] { return state }
                    return nil
                }()

                if mode != expectedMode {
                    return .validation(
                        field: "Room Link",
                        reason: "The Portal did not apply the requested room role."
                    )
                }
                if reportedState == "error" {
                    return .validation(
                        field: "Room Link",
                        reason: "The Portal could not start Room Link. Check its notification for permission or connection help."
                    )
                }
                if mode == NativeRoomIntercomMode.off.rawValue,
                   reportedState == "off" || reportedState == nil {
                    return nil
                }
                if reportedState == "broadcasting" || reportedState == "receiving" {
                    return nil
                }
            } catch is CancellationError {
                return .cancelled
            } catch {
                // A temporary readiness read failure should not undo a confirmed
                // settings write. Continue polling until the bounded window ends.
            }

            guard attempt < 7 else { break }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }

        return .validation(
            field: "Room Link",
            reason: "The Portal did not report that Room Link became ready."
        )
    }
}
