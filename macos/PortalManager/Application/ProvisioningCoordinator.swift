/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Bounded retry settings for transient local ADB, handoff, and verification
/// steps. A value of one disables retries without changing the typed failure
/// returned to the caller.
struct ProvisioningRetryConfiguration: Sendable, Equatable {
    let maximumAttempts: Int
    let delay: Duration

    init(
        maximumAttempts: Int = 2,
        delay: Duration = .milliseconds(250)
    ) {
        self.maximumAttempts = max(1, maximumAttempts)
        self.delay = delay
    }
}

/// Sanitized lifecycle sink for the USB provisioning operation. The domain
/// event type deliberately contains no path, raw ADB output, manifest JSON, or
/// bearer value.
protocol ProvisioningCoordinatorEventSink: Sendable {
    func record(_ event: ProvisioningEvent) async
}

typealias ProvisioningEventSink = ProvisioningCoordinatorEventSink

struct NoopProvisioningCoordinatorEventSink: ProvisioningCoordinatorEventSink, Sendable {
    func record(_ event: ProvisioningEvent) async {}
}

typealias NoopProvisioningEventSink = NoopProvisioningCoordinatorEventSink

/// The successful non-secret result of a provisioning operation. The recovered
/// bearer is intentionally absent; it exists only in the Keychain and in the
/// active transport read performed by the typed Fleet client.
struct ProvisioningResult: Sendable, Equatable {
    let portalID: PortalID
    let mode: ProvisioningMode
    let preflightSnapshot: ADBDeviceSnapshot
    let manifest: AgentManifest
    let admittedEndpoint: LANEndpoint
    let artifactVerification: ArtifactVerificationSummary?
    let registryEntry: PortalRegistryEntry

    var entry: PortalRegistryEntry { registryEntry }
    var endpoint: LANEndpoint { admittedEndpoint }
}

/// Coordinates the two deliberately separate local USB flows. The actor owns
/// sequencing and cancellation; every side effect remains behind the existing
/// typed ADB, artifact, LAN, Fleet, Keychain, workspace, and registry ports.
actor ProvisioningCoordinator {
    private struct ProvisionHandoff: Codable, Sendable, Equatable {
        let name: String?
        let enabled: Bool

        init(name: String?, enabled: Bool = true) {
            self.name = name
            self.enabled = enabled
        }
    }

    private struct VerifiedPortal: Sendable {
        let manifest: AgentManifest
        let endpoint: LANEndpoint
        let record: AuthenticatedPortalRecord
    }

    private final class CredentialRollbackState: @unchecked Sendable {
        var previousValue: Data?
        var didCapturePreviousValue = false
        var didWriteReplacement = false

        func clear() {
            if var previousValue {
                wipe(&previousValue)
                self.previousValue = nil
            }
            didCapturePreviousValue = false
            didWriteReplacement = false
        }
    }

    private let adb: any ADBRunner
    private let artifactVerifier: any ArtifactVerifier
    private let workspaceFactory: any ProvisioningWorkspaceFactory
    private let downloadBoundary: any ProvisioningDownloadBoundary
    private let connectionAdmission: ConnectionAdmission
    private let fleetClient: FleetHTTPClient
    private let credentialStore: any CredentialStore
    private let registryCoordinator: PortalRegistryCoordinator
    private let clock: any ManagerClock
    private let planner: OperationPlanner
    private let retryConfiguration: ProvisioningRetryConfiguration
    private let eventSink: any ProvisioningCoordinatorEventSink

    private var currentStep: ProvisioningStepID = .preflight

    init(
        adb: any ADBRunner,
        artifactVerifier: any ArtifactVerifier,
        workspaceFactory: any ProvisioningWorkspaceFactory,
        connectionAdmission: ConnectionAdmission,
        fleetClient: FleetHTTPClient,
        credentialStore: any CredentialStore,
        registryCoordinator: PortalRegistryCoordinator,
        clock: any ManagerClock = SystemManagerClock(),
        planner: OperationPlanner = OperationPlanner(),
        retryConfiguration: ProvisioningRetryConfiguration = ProvisioningRetryConfiguration(),
        downloadBoundary: any ProvisioningDownloadBoundary = NoDownloadProvisioningBoundary(),
        eventSink: any ProvisioningCoordinatorEventSink = NoopProvisioningCoordinatorEventSink()
    ) {
        self.adb = adb
        self.artifactVerifier = artifactVerifier
        self.workspaceFactory = workspaceFactory
        self.downloadBoundary = downloadBoundary
        self.connectionAdmission = connectionAdmission
        self.fleetClient = fleetClient
        self.credentialStore = credentialStore
        self.registryCoordinator = registryCoordinator
        self.clock = clock
        self.planner = planner
        self.retryConfiguration = retryConfiguration
        self.eventSink = eventSink
    }

    /// Composition-root convenience initializer. The Fleet client must be
    /// built with the same admission and credential policy supplied here.
    init(
        adb: any ADBRunner,
        artifactVerifier: any ArtifactVerifier,
        workspaceFactory: any ProvisioningWorkspaceFactory,
        connectionAdmission: ConnectionAdmission,
        fleetTransport: any FleetHTTPTransport,
        credentialStore: any CredentialStore,
        registryCoordinator: PortalRegistryCoordinator,
        clock: any ManagerClock = SystemManagerClock(),
        planner: OperationPlanner = OperationPlanner(),
        retryConfiguration: ProvisioningRetryConfiguration = ProvisioningRetryConfiguration(),
        downloadBoundary: any ProvisioningDownloadBoundary = NoDownloadProvisioningBoundary(),
        eventSink: any ProvisioningCoordinatorEventSink = NoopProvisioningCoordinatorEventSink()
    ) {
        self.init(
            adb: adb,
            artifactVerifier: artifactVerifier,
            workspaceFactory: workspaceFactory,
            connectionAdmission: connectionAdmission,
            fleetClient: FleetHTTPClient(
                transport: fleetTransport,
                admission: connectionAdmission,
                credentialStore: credentialStore,
                planner: planner
            ),
            credentialStore: credentialStore,
            registryCoordinator: registryCoordinator,
            clock: clock,
            planner: planner,
            retryConfiguration: retryConfiguration,
            downloadBoundary: downloadBoundary,
            eventSink: eventSink
        )
    }

    /// Runs Fleet Agent Enablement/Recovery. The type itself has no artifact,
    /// installation, or setup input, so those operations cannot be reached by
    /// this overload.
    @discardableResult
    func run(
        _ plan: EnablementRecoveryPlan,
        for portalID: PortalID,
        endpoint: ConnectionAdmissionRequest
    ) async throws -> ProvisioningResult {
        try await run(
            mode: .fleetAgentEnablementRecovery,
            deviceSerial: plan.deviceSerial,
            adbExecutable: plan.adbExecutable,
            artifact: nil,
            friendlyName: plan.friendlyName,
            plannedSnapshot: plan.preflightSnapshot,
            portalID: portalID,
            endpoint: endpoint,
            expectedSHA256Digest: nil
        )
    }

    /// Runs Full USB Provisioning with a required local artifact. The artifact
    /// digest is optional because the verifier always records the computed
    /// SHA-256; when supplied it is checked for exact equality as well.
    @discardableResult
    func run(
        _ plan: FullUSBProvisioningPlan,
        for portalID: PortalID,
        endpoint: ConnectionAdmissionRequest,
        expectedSHA256Digest: String? = nil
    ) async throws -> ProvisioningResult {
        try await run(
            mode: .fullUSBProvisioning,
            deviceSerial: plan.deviceSerial,
            adbExecutable: plan.adbExecutable,
            artifact: plan.localArtifact,
            friendlyName: plan.friendlyName,
            plannedSnapshot: plan.preflightSnapshot,
            portalID: portalID,
            endpoint: endpoint,
            expectedSHA256Digest: expectedSHA256Digest
        )
    }

    /// Labelled aliases for application call sites that prefer the explicit
    /// provisioning verb over the lifecycle-oriented `run` spelling.
    @discardableResult
    func provision(
        _ plan: EnablementRecoveryPlan,
        portalID: PortalID,
        endpoint: ConnectionAdmissionRequest
    ) async throws -> ProvisioningResult {
        try await run(plan, for: portalID, endpoint: endpoint)
    }

    @discardableResult
    func provision(
        _ plan: FullUSBProvisioningPlan,
        portalID: PortalID,
        endpoint: ConnectionAdmissionRequest,
        expectedSHA256Digest: String? = nil
    ) async throws -> ProvisioningResult {
        try await run(
            plan,
            for: portalID,
            endpoint: endpoint,
            expectedSHA256Digest: expectedSHA256Digest
        )
    }

    private func run(
        mode: ProvisioningMode,
        deviceSerial: String,
        adbExecutable: LocalExecutableReference,
        artifact: LocalArtifact?,
        friendlyName: String?,
        plannedSnapshot: ADBDeviceSnapshot?,
        portalID: PortalID,
        endpoint: ConnectionAdmissionRequest,
        expectedSHA256Digest: String?
    ) async throws -> ProvisioningResult {
        _ = plannedSnapshot
        currentStep = .preflight
        await eventSink.record(
            .started(mode: mode, deviceSerial: deviceSerial)
        )

        let credentialState = CredentialRollbackState()
        let credentialReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )

        do {
            try validatePlan(
                mode: mode,
                deviceSerial: deviceSerial,
                adbExecutable: adbExecutable,
                artifact: artifact,
                endpoint: endpoint
            )

            let result = try await workspaceFactory.withWorkspace { workspace in
                try await self.executeInWorkspace(
                    mode: mode,
                    deviceSerial: deviceSerial,
                    adbExecutable: adbExecutable,
                    artifact: artifact,
                    friendlyName: friendlyName,
                    endpoint: endpoint,
                    portalID: portalID,
                    expectedSHA256Digest: expectedSHA256Digest,
                    credentialReference: credentialReference,
                    credentialState: credentialState,
                    workspace: workspace
                )
            }

            credentialState.clear()
            await eventSink.record(.completed(mode: mode))
            return result
        } catch is CancellationError {
            if let rollbackFailure = await rollbackIfNeeded(
                credentialState,
                reference: credentialReference
            ) {
                await eventSink.record(.failed(rollbackFailure))
                throw rollbackFailure
            }
            await eventSink.record(
                .cancelled(mode: mode, step: currentStep)
            )
            throw CancellationError()
        } catch let failure as ProvisioningFailure {
            let finalFailure: ProvisioningFailure
            if let rollbackFailure = await rollbackIfNeeded(
                credentialState,
                reference: credentialReference
            ) {
                finalFailure = rollbackFailure
            } else {
                finalFailure = failure
            }
            await eventSink.record(.failed(finalFailure))
            throw finalFailure
        } catch {
            let failure = ProvisioningFailure(
                step: currentStep,
                code: defaultFailureCode(for: currentStep)
            )
            let finalFailure: ProvisioningFailure
            if let rollbackFailure = await rollbackIfNeeded(
                credentialState,
                reference: credentialReference
            ) {
                finalFailure = rollbackFailure
            } else {
                finalFailure = failure
            }
            await eventSink.record(.failed(finalFailure))
            throw finalFailure
        }
    }

    private func validatePlan(
        mode: ProvisioningMode,
        deviceSerial: String,
        adbExecutable: LocalExecutableReference,
        artifact: LocalArtifact?,
        endpoint: ConnectionAdmissionRequest
    ) throws {
        guard !deviceSerial.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              adbExecutable.isSafeSelection,
              endpoint.serviceKind == .portal,
              endpoint.protocolName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "http",
              !downloadBoundary.allowsNetworkAcquisition else {
            throw ProvisioningFailure(step: .preflight, code: .invalidPlan)
        }

        switch mode {
        case .fleetAgentEnablementRecovery:
            guard artifact == nil else {
                throw ProvisioningFailure(step: .preflight, code: .artifactNotAllowed)
            }
        case .fullUSBProvisioning:
            guard let artifact else {
                throw ProvisioningFailure(step: .artifactVerification, code: .artifactRequired)
            }
            guard artifact.isSafeSelection else {
                throw ProvisioningFailure(step: .artifactVerification, code: .artifactInvalid)
            }
        }
    }

    private func executeInWorkspace(
        mode: ProvisioningMode,
        deviceSerial: String,
        adbExecutable: LocalExecutableReference,
        artifact: LocalArtifact?,
        friendlyName: String?,
        endpoint: ConnectionAdmissionRequest,
        portalID: PortalID,
        expectedSHA256Digest: String?,
        credentialReference: CredentialReference,
        credentialState: CredentialRollbackState,
        workspace: any ProvisioningWorkspace
    ) async throws -> ProvisioningResult {
        let totalStepCount = mode.expectedSteps.count
        var completedStepCount = 0

        currentStep = .preflight
        let snapshot: ADBDeviceSnapshot = try await runStep(
            mode: mode,
            step: .preflight,
            completedStepCount: completedStepCount,
            totalStepCount: totalStepCount,
            fallbackCode: .deviceUnavailable
        ) {
            try await self.collectPreflight(
                mode: mode,
                artifact: artifact,
                deviceSerial: deviceSerial,
                workspace: workspace,
                adbExecutable: adbExecutable
            )
        }
        await eventSink.record(.preflightCompleted(snapshot: snapshot))
        completedStepCount += 1

        var artifactSummary: ArtifactVerificationSummary?
        if mode == .fullUSBProvisioning {
            guard let artifact else {
                throw ProvisioningFailure(
                    step: .artifactVerification,
                    code: .artifactRequired
                )
            }
            currentStep = .artifactVerification
            let summary: ArtifactVerificationSummary = try await runStep(
                mode: mode,
                step: .artifactVerification,
                completedStepCount: completedStepCount,
                totalStepCount: totalStepCount,
                fallbackCode: .artifactVerificationFailed
            ) {
                let request = LocalArtifactVerificationRequest(
                    artifact: artifact,
                    snapshot: snapshot,
                    expectedSHA256Digest: expectedSHA256Digest
                )
                do {
                    let result = try await self.artifactVerifier.verify(request)
                    guard let summary = result as? ArtifactVerificationSummary,
                          summary.passed else {
                        throw ProvisioningFailure(
                            step: .artifactVerification,
                            code: .artifactVerificationFailed
                        )
                    }
                    return summary
                } catch is CancellationError {
                    throw CancellationError()
                } catch let failure as ProvisioningFailure {
                    throw failure
                } catch {
                    throw ProvisioningFailure(
                        step: .artifactVerification,
                        code: .artifactVerificationFailed
                    )
                }
            }
            artifactSummary = summary
            await eventSink.record(
                .artifactVerificationCompleted(
                    mode: mode,
                    artifactName: artifact.displayName,
                    digest: summary.sha256Digest,
                    summary: summary
                )
            )
            completedStepCount += 1

            currentStep = .deviceSetup
            _ = try await runStep(
                mode: mode,
                step: .deviceSetup,
                completedStepCount: completedStepCount,
                totalStepCount: totalStepCount,
                fallbackCode: .setupFailed
            ) {
                for setupStep in Self.establishedSetupSteps(for: snapshot) {
                    _ = try await self.executeADB(
                        ADBCommand.applyEstablishedSetup(
                            device: deviceSerial,
                            step: setupStep
                        ),
                        expected: ADBCommandAcknowledgement.self,
                        step: .deviceSetup,
                        fallbackCode: .setupFailed,
                        workspace: workspace,
                        executable: adbExecutable
                    )
                }
            }
            completedStepCount += 1

            currentStep = .installation
            _ = try await runStep(
                mode: mode,
                step: .installation,
                completedStepCount: completedStepCount,
                totalStepCount: totalStepCount,
                fallbackCode: .installationFailed
            ) {
                _ = try await self.executeADB(
                    ADBCommand.installVerifiedArtifact(
                        device: deviceSerial,
                        localURL: artifact.securityScopedURL
                    ),
                    expected: ADBCommandAcknowledgement.self,
                    step: .installation,
                    fallbackCode: .installationFailed,
                    workspace: workspace,
                    executable: adbExecutable
                )
            }
            completedStepCount += 1
        }

        let recoveryMode = ProvisioningMode.fleetAgentEnablementRecovery
        currentStep = .writeProvisionFile
        let handoffURL = try workspace.url(for: .provisionJSON)
        _ = try await runStep(
            mode: recoveryMode,
            step: .writeProvisionFile,
            completedStepCount: completedStepCount,
            totalStepCount: totalStepCount,
            fallbackCode: .writeProvisionFileFailed
        ) {
            let handoff = ProvisionHandoff(name: friendlyName)
            do {
                try workspace.write(
                    try JSONEncoder().encode(handoff),
                    to: .provisionJSON
                )
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                throw ProvisioningFailure(
                    step: .writeProvisionFile,
                    code: .writeProvisionFileFailed
                )
            }

            _ = try await self.executeADB(
                ADBCommand.pushProvisionFile(
                    device: deviceSerial,
                    localURL: handoffURL
                ),
                expected: ADBCommandAcknowledgement.self,
                step: .writeProvisionFile,
                fallbackCode: .writeProvisionFileFailed,
                workspace: workspace,
                executable: adbExecutable
            )
        }
        completedStepCount += 1

        currentStep = .relaunchImmortal
        _ = try await runStep(
            mode: recoveryMode,
            step: .relaunchImmortal,
            completedStepCount: completedStepCount,
            totalStepCount: totalStepCount,
            fallbackCode: .relaunchFailed
        ) {
            _ = try await self.executeADB(
                ADBCommand.relaunchImmortal(device: deviceSerial),
                expected: ADBCommandAcknowledgement.self,
                step: .relaunchImmortal,
                fallbackCode: .relaunchFailed,
                workspace: workspace,
                executable: adbExecutable
            )
        }
        completedStepCount += 1

        currentStep = .readAgentManifest
        let recovered: ADBManifestReadResult = try await runStep(
            mode: recoveryMode,
            step: .readAgentManifest,
            completedStepCount: completedStepCount,
            totalStepCount: totalStepCount,
            fallbackCode: .readAgentManifestFailed
        ) {
            let result: ADBManifestReadResult = try await self.executeADB(
                ADBCommand.readAgentManifest(device: deviceSerial),
                expected: ADBManifestReadResult.self,
                step: .readAgentManifest,
                fallbackCode: .readAgentManifestFailed,
                workspace: workspace,
                executable: adbExecutable
            )
            guard result.manifest.isSafeProjection else {
                throw ProvisioningFailure(
                    step: .readAgentManifest,
                    code: .invalidAgentManifest
                )
            }
            return result
        }
        let projectedManifest = try AgentManifest(
            name: recovered.manifest.name,
            port: recovered.manifest.port,
            snapshot: snapshot
        )
        await eventSink.record(
            .agentManifestRecovered(manifest: projectedManifest)
        )
        completedStepCount += 1

        currentStep = .bearerVerification
        let verified: VerifiedPortal = try await runStep(
            mode: recoveryMode,
            step: .bearerVerification,
            completedStepCount: completedStepCount,
            totalStepCount: totalStepCount,
            fallbackCode: .bearerVerificationFailed
        ) {
            try await self.admitStoreAndVerify(
                recovered: recovered,
                snapshot: snapshot,
                endpoint: endpoint,
                portalID: portalID,
                credentialReference: credentialReference,
                credentialState: credentialState
            )
        }
        completedStepCount += 1

        currentStep = .complete
        let entry: PortalRegistryEntry = try await runStep(
            mode: recoveryMode,
            step: .complete,
            completedStepCount: completedStepCount,
            totalStepCount: totalStepCount,
            fallbackCode: .registryCommitFailed
        ) {
            do {
                let reconciliation = try await self.registryCoordinator.reconcile(
                    verified.record
                )
                // Once the non-secret authenticated record is durably saved,
                // the replacement credential is the committed value and must
                // not be removed by a cancellation arriving at this boundary.
                credentialState.didWriteReplacement = false
                return reconciliation.entry
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                throw ProvisioningFailure(
                    step: .complete,
                    code: .registryCommitFailed
                )
            }
        }

        return ProvisioningResult(
            portalID: portalID,
            mode: mode,
            preflightSnapshot: snapshot,
            manifest: verified.manifest,
            admittedEndpoint: verified.endpoint,
            artifactVerification: artifactSummary,
            registryEntry: entry
        )
    }

    private func collectPreflight(
        mode: ProvisioningMode,
        artifact: LocalArtifact?,
        deviceSerial: String,
        workspace: any ProvisioningWorkspace,
        adbExecutable: LocalExecutableReference
    ) async throws -> ADBDeviceSnapshot {
        let enumeration: ADBDeviceEnumerationResult = try await executeADB(
            .enumerateDevices,
            expected: ADBDeviceEnumerationResult.self,
            step: .preflight,
            fallbackCode: .deviceUnavailable,
            workspace: workspace,
            executable: adbExecutable
        )
        guard let descriptor = enumeration.devices.first(where: { $0.serial == deviceSerial }) else {
            throw ProvisioningFailure(step: .preflight, code: .deviceDisconnected)
        }
        guard descriptor.authorization == .authorized else {
            throw ProvisioningFailure(
                step: .preflight,
                code: descriptor.authorization == .unauthorized
                    ? .deviceUnauthorized
                    : .deviceUnavailable
            )
        }
        guard descriptor.connection == .connected else {
            throw ProvisioningFailure(
                step: .preflight,
                code: descriptor.connection == .disconnected
                    ? .deviceDisconnected
                    : .deviceUnavailable
            )
        }

        let fields: [DeviceField] = [
            .authorization,
            .connection,
            .serial,
            .model,
            .apiLevel,
            .installedImmortal,
            .immortalVersion,
            .fleetAgent
        ]
        var authorization = descriptor.authorization
        var connection = descriptor.connection
        var serial: String? = descriptor.serial
        var model: String? = descriptor.model
        var apiLevel: Int?
        var installation: ImmortalInstallationState = .unknown
        var version: AppVersion?
        var fleetAgent: FleetAgentState = .unknown

        for field in fields {
            let result: ADBInspectionResult = try await executeADB(
                ADBCommand.inspect(device: deviceSerial, field: field),
                expected: ADBInspectionResult.self,
                step: .preflight,
                fallbackCode: .deviceUnavailable,
                workspace: workspace,
                executable: adbExecutable
            )
            guard result.device == deviceSerial, result.field == field else {
                throw ProvisioningFailure(step: .preflight, code: .deviceUnavailable)
            }
            switch result.value {
            case .authorization(let value): authorization = value
            case .connection(let value): connection = value
            case .serial(let value): serial = value ?? serial
            case .model(let value): model = value ?? model
            case .apiLevel(let value): apiLevel = value
            case .installedImmortal(let value): installation = value
            case .immortalVersion(let value): version = value
            case .fleetAgent(let value): fleetAgent = value
            }
        }

        let installed = installation != .notInstalled
        let compatible = installation == .installedCompatible
        let deviceCompatibility: ADBDeviceCompatibility = {
            guard let apiLevel, [28, 29].contains(apiLevel),
                  let model, !model.isEmpty else {
                return .incompatible
            }
            return .compatible
        }()
        let snapshot = ADBDeviceSnapshot(
            authorization: authorization,
            connection: connection,
            serial: serial,
            model: model,
            apiLevel: apiLevel,
            installedImmortal: installed,
            immortalCompatible: compatible,
            fleetAgent: fleetAgent,
            deviceCompatibility: deviceCompatibility,
            immortalVersion: version,
            installedPackageIdentity: installed
                ? LocalArtifactVerifier.immortalPackageIdentity
                : nil
        )

        guard snapshot.authorization == .authorized else {
            throw ProvisioningFailure(
                step: .preflight,
                code: snapshot.authorization == .unauthorized
                    ? .deviceUnauthorized
                    : .deviceUnavailable
            )
        }
        guard snapshot.connection == .connected else {
            throw ProvisioningFailure(
                step: .preflight,
                code: snapshot.connection == .disconnected
                    ? .deviceDisconnected
                    : .deviceUnavailable
            )
        }
        let validation = mode.validate(snapshot: snapshot, artifact: artifact)
        guard validation.isAllowed else {
            throw validation.failure
                ?? ProvisioningFailure(step: .preflight, code: .invalidPlan)
        }
        return snapshot
    }

    private func admitStoreAndVerify(
        recovered: ADBManifestReadResult,
        snapshot: ADBDeviceSnapshot,
        endpoint: ConnectionAdmissionRequest,
        portalID: PortalID,
        credentialReference: CredentialReference,
        credentialState: CredentialRollbackState
    ) async throws -> VerifiedPortal {
        let endpointRequest = provisioningEndpointRequest(
            endpoint,
            port: recovered.manifest.port
        )
        let admitted: AdmittedConnection
        do {
            // Resolve and apply LAN policy before reading or writing the
            // replacement credential. The Fleet client repeats this admission
            // with the selected literal endpoint before it reads the token.
            admitted = try await connectionAdmission.admit(endpointRequest)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ProvisioningFailure(
                step: .bearerVerification,
                code: .lanAdmissionFailed
            )
        }

        if !credentialState.didCapturePreviousValue {
            do {
                credentialState.previousValue = try await credentialStore.read(
                    credentialReference
                )
                credentialState.didCapturePreviousValue = true
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                throw ProvisioningFailure(
                    step: .bearerVerification,
                    code: .keychainWriteFailed
                )
            }
        }

        var token = Data(recovered.bearerToken)
        defer { wipe(&token) }
        guard !token.isEmpty else {
            throw ProvisioningFailure(
                step: .bearerVerification,
                code: .invalidAgentManifest
            )
        }
        do {
            try await credentialStore.write(token, for: credentialReference)
            credentialState.didWriteReplacement = true
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ProvisioningFailure(
                step: .bearerVerification,
                code: .keychainWriteFailed
            )
        }

        let admittedRequest = ConnectionAdmissionRequest(
            endpoint: admitted.endpoint,
            serviceKind: .portal,
            protocolName: "http"
        )
        let classification: PortalInfoClassification
        do {
            let routePlan = try planner.plan(
                portalID: portalID,
                route: .info,
                method: .get,
                credential: .verifiedBearer
            )
            let request = FleetHTTPClientRequest(
                portalID: portalID,
                admissionRequest: admittedRequest,
                routePlan: routePlan,
                credentialReference: credentialReference
            )
            let response = try await fleetClient.execute(request)
            let data = try JSONEncoder().encode(response.payload)
            let info = try JSONDecoder().decode(
                AuthenticatedPortalInfo.self,
                from: data
            )
            guard !info.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !info.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw ProvisioningFailure(
                    step: .bearerVerification,
                    code: .bearerVerificationFailed
                )
            }
            classification = PortalInfoClassifier.classify(
                info,
                portalID: portalID,
                endpointSource: .provisioning
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch let failure as ProvisioningFailure {
            throw failure
        } catch let error as ConnectionAdmissionError {
            _ = error
            throw ProvisioningFailure(
                step: .bearerVerification,
                code: .lanAdmissionFailed
            )
        } catch let error as ManagerError {
            switch error {
            case .lanPolicy, .resolution:
                throw ProvisioningFailure(
                    step: .bearerVerification,
                    code: .lanAdmissionFailed
                )
            case .transport(.timedOut):
                throw ProvisioningFailure(
                    step: .bearerVerification,
                    code: .timedOut
                )
            case .keychain:
                throw ProvisioningFailure(
                    step: .bearerVerification,
                    code: .keychainWriteFailed
                )
            default:
                throw ProvisioningFailure(
                    step: .bearerVerification,
                    code: .bearerVerificationFailed
                )
            }
        } catch {
            throw ProvisioningFailure(
                step: .bearerVerification,
                code: .bearerVerificationFailed
            )
        }

        let verifiedAt = clock.now
        var finalEndpoint = admitted.endpoint
        finalEndpoint.source = .provisioning
        finalEndpoint.lastAuthenticatedAt = verifiedAt
        let finalManifest = try AgentManifest(
            name: recovered.manifest.name,
            port: recovered.manifest.port,
            snapshot: snapshot,
            admittedEndpoint: finalEndpoint
        )

        var identity = classification.identity
        identity.portalID = portalID
        identity.serial = snapshot.serial
        identity.name = finalManifest.name
        identity.model = snapshot.model ?? classification.info.model
        identity.rawModel = snapshot.model ?? classification.info.model
        identity.androidAPILevel = snapshot.apiLevel ?? classification.info.apiLevel
        identity.immortalVersion = snapshot.immortalVersion ?? classification.info.app

        var status = classification.status
        status.reachability = .reachable
        status.lastUpdatedAt = verifiedAt
        let record = AuthenticatedPortalRecord(
            identity: identity,
            endpoint: finalEndpoint,
            capabilities: classification.capabilities,
            status: status,
            policyMetadata: classification.policyMetadata,
            credentialReferences: [credentialReference],
            verifiedAt: verifiedAt
        )
        return VerifiedPortal(
            manifest: finalManifest,
            endpoint: finalEndpoint,
            record: record
        )
    }

    private func provisioningEndpointRequest(
        _ request: ConnectionAdmissionRequest,
        port: UInt16
    ) -> ConnectionAdmissionRequest {
        var endpoint = request.endpoint
        endpoint.port = port
        endpoint.source = .provisioning
        return ConnectionAdmissionRequest(
            endpoint: endpoint,
            serviceKind: .portal,
            protocolName: "http"
        )
    }

    private func executeADB<Result: ADBResult>(
        _ command: ADBCommand,
        expected: Result.Type,
        step: ProvisioningStepID,
        fallbackCode: ProvisioningFailureCode,
        workspace: any ProvisioningWorkspace,
        executable: LocalExecutableReference
    ) async throws -> Result {
        _ = executable
        do {
            let result = try await adb.execute(command)
            if let acknowledgement = result as? ADBCommandAcknowledgement {
                do {
                    try workspace.appendTranscript(acknowledgement.output)
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    throw ProvisioningFailure(step: step, code: fallbackCode)
                }
            }
            guard let typedResult = result as? Result else {
                throw ProvisioningFailure(step: step, code: fallbackCode)
            }
            return typedResult
        } catch is CancellationError {
            throw CancellationError()
        } catch let failure as ProvisioningFailure {
            throw failure
        } catch let error as ADBRunnerError {
            throw ProvisioningFailure(
                step: step,
                code: mapADBFailure(error, fallback: fallbackCode)
            )
        } catch {
            throw ProvisioningFailure(step: step, code: fallbackCode)
        }
    }

    private func runStep<Result>(
        mode: ProvisioningMode,
        step: ProvisioningStepID,
        completedStepCount: Int,
        totalStepCount: Int,
        fallbackCode: ProvisioningFailureCode,
        operation: () async throws -> Result
    ) async throws -> Result {
        currentStep = step
        var attempt = 1
        while true {
            try Task.checkCancellation()
            await eventSink.record(
                .stepStarted(mode: mode, step: step)
            )
            await eventSink.record(
                .progress(
                    ProvisioningProgress(
                        mode: mode,
                        step: step,
                        completedStepCount: completedStepCount,
                        totalStepCount: totalStepCount
                    )
                )
            )

            do {
                let result = try await operation()
                await eventSink.record(
                    .stepCompleted(mode: mode, step: step)
                )
                await eventSink.record(
                    .progress(
                        ProvisioningProgress(
                            mode: mode,
                            step: step,
                            completedStepCount: completedStepCount + 1,
                            totalStepCount: totalStepCount
                        )
                    )
                )
                return result
            } catch is CancellationError {
                throw CancellationError()
            } catch let failure as ProvisioningFailure {
                guard failure.retryPolicy == .immediate,
                      attempt < retryConfiguration.maximumAttempts else {
                    throw failure
                }
                attempt += 1
                do {
                    try await clock.sleep(for: retryConfiguration.delay)
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    throw failure
                }
            } catch {
                let failure = ProvisioningFailure(step: step, code: fallbackCode)
                guard failure.retryPolicy == .immediate,
                      attempt < retryConfiguration.maximumAttempts else {
                    throw failure
                }
                attempt += 1
                do {
                    try await clock.sleep(for: retryConfiguration.delay)
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    throw failure
                }
            }
        }
    }

    private func rollbackIfNeeded(
        _ state: CredentialRollbackState,
        reference: CredentialReference
    ) async -> ProvisioningFailure? {
        guard state.didWriteReplacement else {
            state.clear()
            return nil
        }

        do {
            if let previousValue = state.previousValue {
                try await credentialStore.write(previousValue, for: reference)
            } else {
                try await credentialStore.delete(reference)
            }
            state.clear()
            return nil
        } catch is CancellationError {
            // A rollback is still attempted as an idempotent Keychain action;
            // the typed failure is safer than leaving an untracked credential.
            return ProvisioningFailure(
                step: .complete,
                code: .keychainWriteFailed
            )
        } catch {
            return ProvisioningFailure(
                step: .complete,
                code: .keychainWriteFailed
            )
        }
    }

    private static func establishedSetupSteps(
        for snapshot: ADBDeviceSnapshot
    ) -> [SetupStep] {
        var steps: [SetupStep] = [
            .enableDeveloperSettings,
            .hideStatusBar,
            .allowHiddenAPI
        ]

        // The existing setup contract applies the installer/settings overlay
        // fix only to the typed Android 9 first-generation Portal+ profile.
        // No model guess or arbitrary device command can opt into this path.
        let family = snapshot.model.map {
            PortalInfoClassifier.modelFamily(for: $0)
        }
        if snapshot.apiLevel == 28,
           family == .portalPlusFirstGeneration {
            steps.append(.disableInstallerOverlay)
            steps.append(.disableSettingsOverlay)
        }
        return steps
    }

    private func mapADBFailure(
        _ error: ADBRunnerError,
        fallback: ProvisioningFailureCode
    ) -> ProvisioningFailureCode {
        switch error.code {
        case .timedOut:
            return .timedOut
        case .invalidAgentManifest:
            return .invalidAgentManifest
        case .invalidExecutable, .invalidDevice:
            return fallback == .deviceUnavailable ? .deviceUnavailable : fallback
        case .invalidRequest, .invalidLocalFile, .processLaunchFailed,
             .processFailed, .malformedOutput:
            return fallback
        }
    }

    private func defaultFailureCode(
        for step: ProvisioningStepID
    ) -> ProvisioningFailureCode {
        switch step {
        case .preflight: return .deviceUnavailable
        case .artifactVerification: return .artifactVerificationFailed
        case .deviceSetup: return .setupFailed
        case .installation: return .installationFailed
        case .writeProvisionFile: return .writeProvisionFileFailed
        case .relaunchImmortal: return .relaunchFailed
        case .readAgentManifest: return .readAgentManifestFailed
        case .bearerVerification: return .bearerVerificationFailed
        case .complete: return .registryCommitFailed
        }
    }
}

private func wipe(_ data: inout Data) {
    guard !data.isEmpty else { return }
    data.resetBytes(in: 0..<data.count)
    data.removeAll(keepingCapacity: false)
}
