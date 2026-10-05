/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import XCTest
@testable import PortalManager

final class ProvisioningCoordinatorTests: XCTestCase {
    private let serial = "ADB-SERIAL-1"
    private let portalAddress = "192.168.1.40"
    private let bearer = Data("SECRET-BEARER".utf8)

    private var executable: LocalExecutableReference {
        LocalExecutableReference(
            securityScopedURL: URL(fileURLWithPath: "/private/tmp/adb-selected-token"),
            displayName: "adb"
        )
    }

    private var artifact: LocalArtifact {
        LocalArtifact(
            securityScopedURL: URL(fileURLWithPath: "/private/tmp/Immortal-release-token.apk"),
            displayName: "Immortal-release.apk",
            expectedPackageIdentity: "com.immortal.launcher",
            expectedSignaturePolicy: .approvedPackageSignature
        )
    }

    private var recoverySnapshot: ADBDeviceSnapshot {
        ADBDeviceSnapshot(
            authorization: .authorized,
            connection: .connected,
            serial: serial,
            model: "Meta Portal Go",
            apiLevel: 29,
            installedImmortal: true,
            immortalCompatible: true,
            fleetAgent: .disabled,
            deviceCompatibility: .compatible,
            installedPackageIdentity: "com.immortal.launcher"
        )
    }

    private func fullSnapshot(
        model: String = "Meta Portal Go",
        apiLevel: Int = 29
    ) -> ADBDeviceSnapshot {
        ADBDeviceSnapshot(
            authorization: .authorized,
            connection: .connected,
            serial: serial,
            model: model,
            apiLevel: apiLevel,
            installedImmortal: false,
            immortalCompatible: false,
            fleetAgent: .unknown,
            deviceCompatibility: .compatible
        )
    }

    func testRecoveryUsesOnlyHandoffAndNeverArtifactOrSetup() async throws {
        let stack = try await makeStack(snapshot: recoverySnapshot)
        defer { stack.cleanup() }
        let portalID = PortalID(rawValue: UUID())
        let plan = EnablementRecoveryPlan(
            deviceSerial: serial,
            adbExecutable: executable,
            friendlyName: "Living Room"
        )

        let result = try await stack.coordinator.run(
            plan,
            for: portalID,
            endpoint: endpointRequest()
        )

        XCTAssertEqual(result.mode, .fleetAgentEnablementRecovery)
        XCTAssertEqual(result.manifest.name, "Living Room")
        XCTAssertEqual(result.manifest.port, 8723)
        XCTAssertNil(result.artifactVerification)
        XCTAssertEqual(stack.artifactVerifier.verificationCount, 0)
        XCTAssertEqual(stack.adb.setupSteps, [])
        XCTAssertEqual(stack.adb.installCount, 0)
        XCTAssertEqual(stack.adb.handoffName, "Living Room")
        XCTAssertEqual(stack.adb.handoffEnabled, true)
        XCTAssertEqual(stack.adb.commandKinds.filter { $0 == .pushProvisionFile }.count, 1)
        XCTAssertEqual(stack.adb.commandKinds.filter { $0 == .relaunchImmortal }.count, 1)
        XCTAssertEqual(stack.adb.commandKinds.filter { $0 == .readAgentManifest }.count, 1)

        if case .online = result.registryEntry.connectionState {
            // The registry is online only after the admitted bearer /info check.
        } else {
            XCTFail("A successfully verified Portal must be online.")
        }

        let eventSteps = stack.eventSink.events.compactMap { event -> String? in
            guard case let .stepStarted(mode, step) = event else { return nil }
            return "\(mode.rawValue):\(step.rawValue)"
        }
        XCTAssertEqual(
            eventSteps,
            [
                "fleetAgentEnablementRecovery:preflight",
                "fleetAgentEnablementRecovery:writeProvisionFile",
                "fleetAgentEnablementRecovery:relaunchImmortal",
                "fleetAgentEnablementRecovery:readAgentManifest",
                "fleetAgentEnablementRecovery:bearerVerification",
                "fleetAgentEnablementRecovery:complete"
            ]
        )
        XCTAssertTrue(
            stack.recorder.occurredInOrder([
                .dnsResolve,
                .trustWarningLookup,
                .credentialRead,
                .credentialWrite,
                .trustWarningLookup,
                .credentialRead,
                .transportSend,
                .registryLoad,
                .registrySave
            ])
        )

        let encodedEvents = try stack.eventSink.events.map {
            String(decoding: try JSONEncoder().encode($0), as: UTF8.self)
        }
        XCTAssertTrue(encodedEvents.allSatisfy { !$0.contains("SECRET-BEARER") })
        XCTAssertTrue(encodedEvents.contains { $0.contains("artifact") == false })
    }

    func testFullProvisioningVerifiesArtifactBeforeInstallAndReportsRecoverySeparately() async throws {
        let summary = passingArtifactSummary(digestCharacter: "b")
        let stack = try await makeStack(
            snapshot: fullSnapshot(),
            artifactResults: [summary]
        )
        defer { stack.cleanup() }
        let portalID = PortalID(rawValue: UUID())
        let plan = FullUSBProvisioningPlan(
            deviceSerial: serial,
            adbExecutable: executable,
            localArtifact: artifact,
            friendlyName: "Living Room"
        )

        let result = try await stack.coordinator.run(
            plan,
            for: portalID,
            endpoint: endpointRequest()
        )

        XCTAssertEqual(result.mode, .fullUSBProvisioning)
        XCTAssertEqual(result.artifactVerification, summary)
        XCTAssertEqual(stack.artifactVerifier.verificationCount, 1)
        XCTAssertEqual(
            stack.adb.setupSteps,
            [.enableDeveloperSettings, .hideStatusBar, .allowHiddenAPI]
        )
        XCTAssertEqual(stack.adb.installCount, 1)

        let events = stack.eventSink.events
        guard let artifactEventIndex = events.firstIndex(where: {
            if case .artifactVerificationCompleted = $0 { return true }
            return false
        }),
        let installationStartIndex = events.firstIndex(where: {
            if case .stepStarted(_, .installation) = $0 { return true }
            return false
        }) else {
            return XCTFail("Full provisioning must report artifact verification and installation.")
        }
        XCTAssertLessThan(artifactEventIndex, installationStartIndex)

        let recoveryModes = events.compactMap { event -> ProvisioningMode? in
            guard case let .stepStarted(mode, step) = event,
                  step.phase == .enablementRecovery else { return nil }
            return mode
        }
        XCTAssertEqual(
            recoveryModes,
            Array(repeating: .fleetAgentEnablementRecovery, count: 3)
        )

        guard let artifactEvent = events.compactMap({ event -> ProvisioningEvent? in
            if case .artifactVerificationCompleted = event { return event }
            return nil
        }).first else {
            return XCTFail("The artifact preview event was not emitted.")
        }
        if case let .artifactVerificationCompleted(mode, artifactName, digest, eventSummary) = artifactEvent {
            XCTAssertEqual(mode, .fullUSBProvisioning)
            XCTAssertEqual(artifactName, "Immortal-release.apk")
            XCTAssertEqual(digest, summary.sha256Digest)
            XCTAssertEqual(eventSummary, summary)
        } else {
            XCTFail("Unexpected event shape.")
        }
    }

    func testFirstGenerationAPI28UsesOnlyEstablishedOverlaySetupSteps() async throws {
        let snapshot = fullSnapshot(
            model: "Meta Portal+ first generation",
            apiLevel: 28
        )
        let stack = try await makeStack(
            snapshot: snapshot,
            artifactResults: [passingArtifactSummary()]
        )
        defer { stack.cleanup() }
        let plan = FullUSBProvisioningPlan(
            deviceSerial: serial,
            adbExecutable: executable,
            localArtifact: artifact,
            friendlyName: "First Generation"
        )

        _ = try await stack.coordinator.run(
            plan,
            for: PortalID(rawValue: UUID()),
            endpoint: endpointRequest()
        )

        XCTAssertEqual(
            stack.adb.setupSteps,
            [
                .enableDeveloperSettings,
                .hideStatusBar,
                .allowHiddenAPI,
                .disableInstallerOverlay,
                .disableSettingsOverlay
            ]
        )
        XCTAssertEqual(stack.adb.installCount, 1)
    }

    func testFailedArtifactVerificationDoesNotInstallOrChangeRegistry() async throws {
        let portalID = PortalID(rawValue: UUID())
        let reference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let existing = existingEntry(portalID: portalID, reference: reference)
        let initialSnapshot = RegistrySnapshot(entries: [existing])
        let failedSummary = ArtifactVerificationSummary(
            readableRegularFile: .passed,
            packageIdentity: .failed(.packageIdentityMismatch),
            signature: .passed,
            sha256Digest: String(repeating: "c", count: 64),
            apiCompatibility: .passed,
            abiCompatibility: .passed,
            targetModelCompatibility: .passed
        )
        let stack = try await makeStack(
            portalID: portalID,
            snapshot: fullSnapshot(),
            artifactResults: [failedSummary],
            initialRegistrySnapshot: initialSnapshot
        )
        defer { stack.cleanup() }
        let plan = FullUSBProvisioningPlan(
            deviceSerial: serial,
            adbExecutable: executable,
            localArtifact: artifact,
            friendlyName: "Living Room"
        )

        do {
            _ = try await stack.coordinator.run(
                plan,
                for: portalID,
                endpoint: endpointRequest()
            )
            XCTFail("A failed artifact check must block provisioning.")
        } catch let failure as ProvisioningFailure {
            XCTAssertEqual(failure.step, .artifactVerification)
            XCTAssertEqual(failure.code, .artifactVerificationFailed)
            XCTAssertEqual(failure.sanitizedMessage, "The local artifact failed verification.")
        }

        XCTAssertEqual(stack.adb.installCount, 0)
        XCTAssertEqual(stack.adb.setupSteps, [])
        XCTAssertEqual(stack.registryStore.snapshot, initialSnapshot)
        XCTAssertEqual(stack.registryStore.saveCount, 0)
        XCTAssertTrue(stack.credentialStore.operations.isEmpty)
        XCTAssertTrue(
            stack.eventSink.events.contains {
                if case .failed(let failure) = $0 {
                    return failure.code == .artifactVerificationFailed
                }
                return false
            }
        )
    }

    func testRetryRetainsOriginalCredentialForRollbackAndPreservesRegistry() async throws {
        let portalID = PortalID(rawValue: UUID())
        let reference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let initialSnapshot = RegistrySnapshot(
            entries: [existingEntry(portalID: portalID, reference: reference)]
        )
        let stack = try await makeStack(
            portalID: portalID,
            snapshot: recoverySnapshot,
            infoResponses: [
                FleetHTTPResponse(statusCode: 500),
                FleetHTTPResponse(statusCode: 500)
            ],
            initialRegistrySnapshot: initialSnapshot,
            seededCredential: Data("OLD-BEARER".utf8),
            resolutionCount: 2,
            retryConfiguration: ProvisioningRetryConfiguration(
                maximumAttempts: 2,
                delay: .milliseconds(1)
            )
        )
        defer { stack.cleanup() }
        let plan = EnablementRecoveryPlan(
            deviceSerial: serial,
            adbExecutable: executable,
            friendlyName: "Living Room"
        )

        do {
            _ = try await stack.coordinator.run(
                plan,
                for: portalID,
                endpoint: endpointRequest()
            )
            XCTFail("Two failed verification attempts must fail the operation.")
        } catch let failure as ProvisioningFailure {
            XCTAssertEqual(failure.step, .bearerVerification)
            XCTAssertEqual(failure.code, .bearerVerificationFailed)
            XCTAssertFalse(failure.sanitizedMessage.contains("OLD-BEARER"))
        }

        XCTAssertEqual(stack.transport.requestCount, 2)
        XCTAssertEqual(stack.clock.sleepDurations.count, 1)
        XCTAssertEqual(
            stack.credentialStore.operations.map(\.operation),
            [.read, .write, .read, .write, .read, .write]
        )
        XCTAssertEqual(stack.registryStore.snapshot, initialSnapshot)
        XCTAssertEqual(stack.registryStore.saveCount, 0)
    }

    func testRegistryCommitFailureRollsBackReplacementCredential() async throws {
        let portalID = PortalID(rawValue: UUID())
        let reference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let initialSnapshot = RegistrySnapshot(
            entries: [existingEntry(portalID: portalID, reference: reference)]
        )
        let stack = try await makeStack(
            portalID: portalID,
            snapshot: recoverySnapshot,
            initialRegistrySnapshot: initialSnapshot,
            seededCredential: Data("OLD-BEARER".utf8)
        )
        defer { stack.cleanup() }
        stack.registryStore.setSaveFailure(true)
        let plan = EnablementRecoveryPlan(
            deviceSerial: serial,
            adbExecutable: executable,
            friendlyName: "Living Room"
        )

        do {
            _ = try await stack.coordinator.run(
                plan,
                for: portalID,
                endpoint: endpointRequest()
            )
            XCTFail("A registry save failure must fail provisioning.")
        } catch let failure as ProvisioningFailure {
            XCTAssertEqual(failure.step, .complete)
            XCTAssertEqual(failure.code, .registryCommitFailed)
        }

        XCTAssertEqual(stack.registryStore.snapshot, initialSnapshot)
        XCTAssertEqual(
            stack.credentialStore.operations.map(\.operation),
            [.read, .write, .read, .write]
        )
        XCTAssertTrue(
            stack.eventSink.events.contains {
                if case .failed(let failure) = $0 {
                    return failure.code == .registryCommitFailed
                }
                return false
            }
        )
    }

    func testUnauthorizedPreflightPreservesRegistryAndReportsSanitizedFailure() async throws {
        let portalID = PortalID(rawValue: UUID())
        let reference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let initialSnapshot = RegistrySnapshot(
            entries: [existingEntry(portalID: portalID, reference: reference)]
        )
        let unauthorized = ADBDeviceSnapshot(
            authorization: .unauthorized,
            connection: .connected,
            serial: serial,
            model: "Meta Portal Go",
            apiLevel: 29,
            installedImmortal: true,
            immortalCompatible: true,
            deviceCompatibility: .compatible
        )
        let stack = try await makeStack(
            snapshot: unauthorized,
            initialRegistrySnapshot: initialSnapshot
        )
        defer { stack.cleanup() }
        let plan = EnablementRecoveryPlan(
            deviceSerial: serial,
            adbExecutable: executable,
            friendlyName: "Living Room"
        )

        do {
            _ = try await stack.coordinator.run(
                plan,
                for: portalID,
                endpoint: endpointRequest()
            )
            XCTFail("Unauthorized ADB must block recovery.")
        } catch let failure as ProvisioningFailure {
            XCTAssertEqual(failure.step, .preflight)
            XCTAssertEqual(failure.code, .deviceUnauthorized)
            XCTAssertFalse(failure.sanitizedMessage.contains("ADB-SERIAL-1"))
            XCTAssertFalse(failure.sanitizedMessage.contains("SECRET-BEARER"))
        }

        XCTAssertEqual(stack.registryStore.snapshot, initialSnapshot)
        XCTAssertEqual(stack.registryStore.loadCount, 0)
        XCTAssertEqual(stack.registryStore.saveCount, 0)
        XCTAssertTrue(stack.credentialStore.operations.isEmpty)
    }

    func testCooperativeCancellationCleansWorkspaceAndEmitsCancelledStep() async throws {
        let stack = try await makeStack(
            snapshot: recoverySnapshot,
            blockFirstADBCommand: true
        )
        defer { stack.cleanup() }
        let plan = EnablementRecoveryPlan(
            deviceSerial: serial,
            adbExecutable: executable,
            friendlyName: "Living Room"
        )
        let task = Task {
            try await stack.coordinator.run(
                plan,
                for: PortalID(rawValue: UUID()),
                endpoint: endpointRequest()
            )
        }

        for _ in 0..<1_000 where !stack.adb.firstCommandStarted {
            await Task.yield()
        }
        task.cancel()

        do {
            _ = try await task.value
            XCTFail("Cancellation must stop the operation.")
        } catch is CancellationError {
            // Expected cooperative cancellation.
        }

        XCTAssertTrue(
            stack.eventSink.events.contains {
                if case .cancelled(_, .preflight) = $0 { return true }
                return false
            }
        )
        XCTAssertEqual(stack.registryStore.saveCount, 0)
        XCTAssertTrue(stack.workspaceBaseContents.isEmpty)
    }

    private func endpointRequest() -> ConnectionAdmissionRequest {
        ConnectionAdmissionRequest(
            endpoint: LANEndpoint(
                hostOrAddress: "portal.local",
                port: LANEndpoint.defaultPortalAgentPort,
                addressFamily: .hostname,
                source: .manual
            ),
            serviceKind: .portal,
            protocolName: "http"
        )
    }

    private func passingArtifactSummary(digestCharacter: Character = "a") -> ArtifactVerificationSummary {
        ArtifactVerificationSummary(
            readableRegularFile: .passed,
            packageIdentity: .passed,
            signature: .passed,
            sha256Digest: String(repeating: digestCharacter, count: 64),
            apiCompatibility: .passed,
            abiCompatibility: .passed,
            targetModelCompatibility: .passed
        )
    }

    private func existingEntry(
        portalID: PortalID,
        reference: CredentialReference
    ) -> PortalRegistryEntry {
        let date = Date(timeIntervalSince1970: 100)
        let identity = PortalIdentity(
            portalID: portalID,
            serial: serial,
            name: "Existing Portal",
            model: "Meta Portal Go",
            rawModel: "Meta Portal Go",
            androidAPILevel: 29
        )
        let endpoint = LANEndpoint(
            hostOrAddress: portalAddress,
            port: 8723,
            addressFamily: .ipv4,
            source: .provisioning,
            lastAuthenticatedAt: date
        )
        return PortalRegistryEntry(
            id: portalID,
            connectionState: .online(lastRefresh: date, latencyMs: 10),
            identity: identity,
            endpoint: endpoint,
            discoveredEndpoints: [endpoint],
            credentialReferences: [reference],
            lastSuccessfulContact: date,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: date
            )
        )
    }

    private func makeStack(
        portalID: PortalID? = nil,
        snapshot: ADBDeviceSnapshot,
        infoResponses: [FleetHTTPResponse]? = nil,
        artifactResults: [ArtifactVerificationSummary] = [],
        initialRegistrySnapshot: RegistrySnapshot = RegistrySnapshot(),
        seededCredential: Data? = nil,
        resolutionCount: Int = 1,
        retryConfiguration: ProvisioningRetryConfiguration = ProvisioningRetryConfiguration(
            maximumAttempts: 1,
            delay: .milliseconds(1)
        ),
        blockFirstADBCommand: Bool = false
    ) async throws -> CoordinatorTestStack {
        let credentialPortalID = portalID ?? PortalID(rawValue: UUID())
        let recorder = FakeDependencyEventRecorder()
        let adb = CoordinatorADBSpy(
            snapshot: snapshot,
            manifestName: "Living Room",
            manifestPort: 8723,
            bearerToken: bearer,
            blockFirstCommand: blockFirstADBCommand
        )
        let artifactVerifier = FakeArtifactVerifier(results: artifactResults)
        let registryStore = FakeRegistryStore(
            initialSnapshot: initialRegistrySnapshot,
            recorder: recorder
        )
        let credentialReference = CredentialReference.portalCredential(
            portalID: credentialPortalID,
            kind: .verifiedBearer
        )
        let credentialStore = FakeCredentialStore(
            seededValues: seededCredential.map {
                [credentialReference: $0]
            } ?? [:],
            recorder: recorder
        )
        let info = try makeInfoResponse(model: snapshot.model ?? "Meta Portal Go")
        let transport = FakeFleetHTTPTransport(
            responses: infoResponses ?? [info],
            recorder: recorder
        )
        let resolver = FakeDNSResolver(
            responses: Array(
                repeating: DNSResolutionResult(
                    addresses: [ResolvedAddress(address: portalAddress)]
                ),
                count: max(1, resolutionCount)
            ),
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: portalAddress,
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 0)
        )
        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let clock = FakeManagerClock(recorder: recorder)
        let eventSink = RecordingProvisioningEventSink()
        let baseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent(
                "ProvisioningCoordinatorTests-\(UUID().uuidString)",
                isDirectory: true
            )
        try FileManager.default.createDirectory(
            at: baseDirectory,
            withIntermediateDirectories: true
        )
        let workspaceFactory = TempWorkspaceFactory(baseDirectory: baseDirectory)
        let registryCoordinator = PortalRegistryCoordinator(
            registryStore: registryStore,
            credentialStore: credentialStore
        )
        let coordinator = ProvisioningCoordinator(
            adb: adb,
            artifactVerifier: artifactVerifier,
            workspaceFactory: workspaceFactory,
            connectionAdmission: admission,
            fleetTransport: transport,
            credentialStore: credentialStore,
            registryCoordinator: registryCoordinator,
            clock: clock,
            retryConfiguration: retryConfiguration,
            downloadBoundary: NoDownloadProvisioningBoundary(),
            eventSink: eventSink
        )
        return CoordinatorTestStack(
            coordinator: coordinator,
            adb: adb,
            artifactVerifier: artifactVerifier,
            registryStore: registryStore,
            credentialStore: credentialStore,
            transport: transport,
            clock: clock,
            eventSink: eventSink,
            recorder: recorder,
            baseDirectory: baseDirectory
        )
    }

    private func makeInfoResponse(model: String) throws -> FleetHTTPResponse {
        let info = AuthenticatedPortalInfo(
            name: "Living Room",
            model: model,
            device: "Meta Portal",
            apiLevel: 29,
            app: AppVersion(versionCode: 42, versionName: "1.2.3"),
            ip: portalAddress,
            port: 8723,
            capabilities: [
                "remoteSettings": .bool(true),
                "remoteSources": .bool(true),
                "screensaver": .bool(true),
                "calendar": .bool(true),
                "action": .bool(true)
            ],
            endpointPresence: PortalEndpointPresence(
                remoteSettings: true,
                remoteSources: true,
                calendar: true,
                screensaver: true,
                action: true
            )
        )
        return FleetHTTPResponse(
            statusCode: 200,
            body: try JSONEncoder().encode(info)
        )
    }
}

private struct CoordinatorTestStack {
    let coordinator: ProvisioningCoordinator
    let adb: CoordinatorADBSpy
    let artifactVerifier: FakeArtifactVerifier
    let registryStore: FakeRegistryStore
    let credentialStore: FakeCredentialStore
    let transport: FakeFleetHTTPTransport
    let clock: FakeManagerClock
    let eventSink: RecordingProvisioningEventSink
    let recorder: FakeDependencyEventRecorder
    let baseDirectory: URL

    var workspaceBaseContents: [URL] {
        (try? FileManager.default.contentsOfDirectory(
            at: baseDirectory,
            includingPropertiesForKeys: nil
        )) ?? []
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: baseDirectory)
    }
}

private final class RecordingProvisioningEventSink: ProvisioningCoordinatorEventSink, @unchecked Sendable {
    private let lock = NSLock()
    private var recordedEvents: [ProvisioningEvent] = []

    func record(_ event: ProvisioningEvent) async {
        lock.lock()
        recordedEvents.append(event)
        lock.unlock()
    }

    var events: [ProvisioningEvent] {
        lock.lock()
        defer { lock.unlock() }
        return recordedEvents
    }
}

private final class CoordinatorADBSpy: ADBRunner, @unchecked Sendable {
    private let lock = NSLock()
    private let snapshot: ADBDeviceSnapshot
    private let manifest: AgentManifest
    private let bearerToken: Data
    private var failuresRemaining: [ADBCommandKind: Int] = [:]
    private var recordedCommandKinds: [ADBCommandKind] = []
    private var recordedSetupSteps: [SetupStep] = []
    private var receivedHandoffName: String?
    private var receivedHandoffEnabled: Bool?
    private let blockFirstCommand: Bool
    private var didBlockFirstCommand = false
    private var firstCommandDidStart = false

    init(
        snapshot: ADBDeviceSnapshot,
        manifestName: String,
        manifestPort: UInt16,
        bearerToken: Data,
        blockFirstCommand: Bool = false
    ) {
        self.snapshot = snapshot
        self.manifest = AgentManifest(
            name: manifestName,
            port: manifestPort,
            serial: snapshot.serial,
            model: snapshot.model
        )
        self.bearerToken = Data(bearerToken)
        self.blockFirstCommand = blockFirstCommand
    }

    func failNext(_ kind: ADBCommandKind, count: Int = 1) {
        lock.lock()
        failuresRemaining[kind, default: 0] += max(0, count)
        lock.unlock()
    }

    var commandKinds: [ADBCommandKind] {
        lock.lock()
        defer { lock.unlock() }
        return recordedCommandKinds
    }

    var setupSteps: [SetupStep] {
        lock.lock()
        defer { lock.unlock() }
        return recordedSetupSteps
    }

    var installCount: Int {
        commandKinds.filter { $0 == .installVerifiedArtifact }.count
    }

    var handoffName: String? {
        lock.lock()
        defer { lock.unlock() }
        return receivedHandoffName
    }

    var handoffEnabled: Bool? {
        lock.lock()
        defer { lock.unlock() }
        return receivedHandoffEnabled
    }

    var firstCommandStarted: Bool {
        lock.lock()
        defer { lock.unlock() }
        return firstCommandDidStart
    }

    func execute(_ request: any ADBRequest) async throws -> any ADBResult {
        guard let command = request as? ADBCommand else {
            throw FakePortError.configuredFailure(.adb)
        }

        let shouldBlock: Bool
        let shouldFail: Bool
        lock.lock()
        firstCommandDidStart = true
        recordedCommandKinds.append(command.kind)
        shouldBlock = blockFirstCommand && !didBlockFirstCommand
        if shouldBlock {
            didBlockFirstCommand = true
        }
        let remaining = failuresRemaining[command.kind, default: 0]
        shouldFail = remaining > 0
        if shouldFail {
            failuresRemaining[command.kind] = remaining - 1
        }
        if case let .applyEstablishedSetup(_, step) = command {
            recordedSetupSteps.append(step)
        }
        lock.unlock()

        if shouldBlock {
            try await Task.sleep(nanoseconds: 5_000_000_000)
        }
        try Task.checkCancellation()
        if shouldFail {
            throw FakePortError.configuredFailure(.adb)
        }

        switch command {
        case .enumerateDevices:
            return ADBDeviceEnumerationResult(
                devices: [
                    ADBDeviceDescriptor(
                        serial: snapshot.serial ?? "",
                        authorization: snapshot.authorization,
                        connection: snapshot.connection,
                        model: snapshot.model
                    )
                ]
            )
        case .inspect(let device, let field):
            return ADBInspectionResult(
                device: device,
                field: field,
                value: inspectionValue(for: field)
            )
        case .pushProvisionFile(_, let localURL):
            recordHandoff(at: localURL)
            return acknowledgement(for: command.kind)
        case .relaunchImmortal, .installVerifiedArtifact, .applyEstablishedSetup:
            return acknowledgement(for: command.kind)
        case .readAgentManifest:
            return ADBManifestReadResult(
                manifest: manifest,
                bearerToken: Data(bearerToken)
            )
        }
    }

    private func inspectionValue(for field: DeviceField) -> ADBInspectionValue {
        switch field {
        case .authorization:
            return .authorization(snapshot.authorization)
        case .connection:
            return .connection(snapshot.connection)
        case .serial:
            return .serial(snapshot.serial)
        case .model:
            return .model(snapshot.model)
        case .apiLevel:
            return .apiLevel(snapshot.apiLevel)
        case .installedImmortal:
            return .installedImmortal(snapshot.installedImmortalState)
        case .immortalVersion:
            return .immortalVersion(snapshot.immortalVersion)
        case .fleetAgent:
            return .fleetAgent(snapshot.fleetAgent)
        }
    }

    private func acknowledgement(for kind: ADBCommandKind) -> ADBCommandAcknowledgement {
        ADBCommandAcknowledgement(
            kind: kind,
            output: SanitizedADBProcessOutput(stdout: "", stderr: "")
        )
    }

    private func recordHandoff(at url: URL) {
        guard let data = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: data),
              let dictionary = object as? [String: Any] else {
            return
        }
        lock.lock()
        receivedHandoffName = dictionary["name"] as? String
        receivedHandoffEnabled = dictionary["enabled"] as? Bool
        lock.unlock()
    }
}
