/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Combine
import AppKit
import Foundation
import SwiftUI

/// Detail sections inside the split view.
enum DetailSection: String, CaseIterable, Identifiable, Sendable {
    case overview
    case settings
    case music
    case bulk
    case provisioning
    case evidence

    var id: Self { self }

    var title: String {
        switch self {
        case .overview: return "Overview"
        case .settings: return "Settings"
        case .music: return "Music"
        case .bulk: return "Actions"
        case .provisioning: return "Provisioning"
        case .evidence: return "Release"
        }
    }

    var systemImage: String {
        switch self {
        case .overview: return "square.grid.2x2"
        case .settings: return "slider.horizontal.3"
        case .music: return "hifispeaker.2"
        case .bulk: return "arrow.triangle.branch"
        case .provisioning: return "cable.connector"
        case .evidence: return "checkmark.seal"
        }
    }
}

/// Sidebar destinations.
enum SidebarDestination: String, CaseIterable, Identifiable, Sendable {
    case dashboard
    case portals
    case music
    case casting
    case credentials
    case bulk
    case services
    case provisioning
    case release

    var id: Self { self }

    var title: String {
        switch self {
        case .dashboard: return "Dashboard"
        case .portals: return "Portals"
        case .music: return "Music"
        case .casting: return "Cast"
        case .credentials: return "Credentials"
        case .bulk: return "Fleet Actions"
        case .services: return "Services"
        case .provisioning: return "Setup"
        case .release: return "Health"
        }
    }

    var systemImage: String {
        switch self {
        case .dashboard: return "gauge.with.dots.needle.67percent"
        case .portals: return "rectangle.stack"
        case .music: return "hifispeaker.2"
        case .casting: return "airplayvideo"
        case .credentials: return "key.horizontal.fill"
        case .bulk: return "arrow.triangle.branch"
        case .services: return "gearshape.2"
        case .provisioning: return "cable.connector"
        case .release: return "checkmark.seal"
        }
    }

    var detailSection: DetailSection {
        switch self {
        case .dashboard, .portals: return .overview
        case .music: return .music
        case .casting, .credentials, .services: return .overview
        case .bulk: return .bulk
        case .provisioning: return .provisioning
        case .release: return .evidence
        }
    }
}

struct SceneNavigationState: Equatable, Sendable {
    var selection: SidebarDestination? = .dashboard
    var detailSection: DetailSection = .overview
}

struct CommandState: Equatable, Sendable {
    var canRefreshDiscovery = true
    var canOpenManualEndpoint = true
    var canRefreshStatus = false
    var canIdentify = false
    var canReaffirm = false
    var canApply = false
    var canRetry = false
    var canRefreshMusic = false
    var canCancel = false
}

enum PortalManagerIntent: Equatable, Sendable {
    case navigate(SidebarDestination)
    case refreshDiscovery
    case openManualEndpoint
    case refreshStatus
    case identify
    case reaffirm
    case apply
    case retry
    case refreshMusic
    case scanCasting
    case connectCasting(CastingTargetID)
    case disconnectCasting(CastingTargetID)
    case playCasting(CastingPlaybackRequest, CastingTargetID)
    case stopCasting(CastingTargetID)
    case refreshApps
    case refreshAppProfiles
    case applyAppProfile(AppProfileAction)
    case retryAppProfile(FleetAppProfile)
    case stageAppSync(String)
    case toggleAppSyncTarget(PortalID)
    case confirmAppSync
    case cancelAppSync
    case portalMedia(MediaTransportAction)
    case portalVolume(VolumeDirection)
    case selectProvisioningADB(URL)
    case selectProvisioningArtifact(URL)
    case startProvisioning(ProvisioningMode)
    case cancelProvisioning
    case copyReleaseReport
    case cancel
}

struct ProvisioningWorkflowState: Equatable, Sendable {
    var isRunning = false
    var progress = ProvisioningProgress(
        mode: .fleetAgentEnablementRecovery,
        step: .preflight,
        completedStepCount: 0,
        totalStepCount: 0
    )
    var currentStep: ProvisioningStepID?
    var statusMessage: String?

    static func idle() -> Self { Self() }
}

private struct ClosureProvisioningEventSink: ProvisioningCoordinatorEventSink {
    let handler: @Sendable (ProvisioningEvent) async -> Void

    func record(_ event: ProvisioningEvent) async {
        await handler(event)
    }
}

private extension ProvisioningEvent {
    var progressValue: ProvisioningProgress? {
        if case .progress(let progress) = self { return progress }
        return nil
    }
}

struct CastingPlaybackCommand: Equatable, Sendable {
    let request: CastingPlaybackRequest
    let targetID: CastingTargetID

    init(_ request: CastingPlaybackRequest, _ targetID: CastingTargetID) {
        self.request = request
        self.targetID = targetID
    }
}

enum ProvisioningTargetError: Error, Equatable {
    case registeredPortalRequired
    case ambiguousSerial
}

// MARK: - Store

/// The main-actor-facing application store. It owns sanitized, non-secret UI
/// state only; network, Keychain, ADB, and protocol work stay behind injected
/// dependency ports and their coordinators (which run on their own executors).
@MainActor
final class PortalManagerStore: ObservableObject {
    let dependencies: DependencyContainer

    private(set) lazy var backgroundServiceController = BackgroundServiceController()

    @Published var navigation = SceneNavigationState()
    @Published private(set) var commandState = CommandState()
    @Published private(set) var statusMessage = "Ready"
    @Published private(set) var lastIntent: PortalManagerIntent?

    /// Sanitized registry entries for the sidebar and overview.
    @Published private(set) var entries: [PortalRegistryEntry] = []
    /// Discovery candidates currently visible from mDNS.
    @Published private(set) var discoveryCandidates: [BonjourService] = []
    @Published private(set) var discoveryRunning = false

    @Published var selectedPortalID: PortalID? {
        didSet { recomputeCommands() }
    }
    @Published private(set) var selectedSnapshot: PortalSessionSnapshot?
    @Published var showManualOnboarding = false

    // Music.
    @Published private(set) var musicAssistantSnapshot: MusicTopologySnapshot?
    @Published private(set) var snapcastSnapshot: SnapcastTopologySnapshot?
    @Published private(set) var musicRefreshing = false
    @Published var maHostInput = ""
    @Published var snapcastHostInput = ""
    @Published private(set) var multiRoomSetupPhase: MultiRoomSetupPhase = .idle
    @Published var multiRoomSelectedGroupID = ""
    @Published var multiRoomSelectedPortalIDs: Set<PortalID> = []

    // Native Room Link.
    @Published var nativeRoomSelectedSourceID: PortalID?
    @Published var nativeRoomSelectedReceiverIDs: Set<PortalID> = []
    @Published private(set) var nativeRoomReport: NativeRoomApplyReport?
    @Published private(set) var nativeRoomIsRunning = false
    @Published private(set) var nativeRoomReviewMessage: String?
    private var nativeRoomPlan: NativeRoomPlan?

    // Casting.
    @Published private(set) var castingTargets: [CastingTarget] = []
    @Published private(set) var castingStates: [CastingTargetID: CastingConnectionState] = [:]
    @Published private(set) var castingScanning = false
    @Published private(set) var castingPlayback: [CastingTargetID: CastingPlaybackSnapshot] = [:]

    // Apps and Portal controls.
    @Published private(set) var selectedPortalApps: [PortalAppSummary] = []
    @Published private(set) var appsRefreshing = false
    @Published private(set) var fleetSyncRunning = false
    @Published private(set) var fleetSyncProgress = SyncProgress(finished: 0, total: 0)
    @Published private(set) var fleetSyncResults: [PortalID: FleetTargetOutcome] = [:]
    @Published private(set) var pendingAppSyncPackage: String?
    @Published private(set) var pendingAppSyncTargets: Set<PortalID> = []
    @Published private(set) var selectedPortalProfiles: [FleetAppProfile] = []
    @Published private(set) var profilesRefreshing = false
    @Published private(set) var profileResults: [PortalID: FleetTargetOutcome] = [:]
    @Published var profilePackageInput = ""
    @Published var profileApkURLInput = ""

    // Credential sharing.
    @Published var credentialSyncSelection = CredentialSyncSelection()
    @Published private(set) var credentialSyncResults: [CredentialSyncResult] = []
    @Published private(set) var credentialSyncRunning = false

    // Bulk workflow.
    enum BulkPhase: Equatable {
        case idle
        case planning
        case awaitingConfirmation(BulkPreflightSummary)
        case running(summary: BulkPreflightSummary, finished: Int, total: Int)
        case completed(BulkOperationReport)
        case blocked(reason: String)
    }
    @Published private(set) var bulkPhase: BulkPhase = .idle
    @Published private(set) var bulkResults: [BulkTargetResult] = []

    // Release evidence.
    @Published private(set) var evidenceReport: ReleaseGateReport?

    // USB provisioning.
    @Published private(set) var adbExecutableSelection: LocalExecutableReference?
    @Published private(set) var provisioningArtifact: LocalArtifact?
    @Published private(set) var provisioningState = ProvisioningWorkflowState.idle()
    @Published var provisioningDeviceSerialInput = ""
    @Published var provisioningFriendlyNameInput = ""
    @Published var provisioningPortalEndpointInput = ""
    private var provisioningTask: Task<Void, Never>?
    private var retainedSecurityScopedURLs: Set<URL> = []

    private lazy var registryCoordinator = PortalRegistryCoordinator(
        registryStore: dependencies.registry,
        credentialStore: dependencies.keychain
    )

    private lazy var sessionCoordinator = PortalSessionCoordinator(
        fleetClient: FleetHTTPClient(
            transport: dependencies.fleetHTTP,
            admission: ConnectionAdmission(
                dnsResolver: dependencies.dns,
                trustWarningStore: dependencies.trustWarnings
            ),
            credentialStore: dependencies.keychain
        ),
        registryCoordinator: registryCoordinator,
        credentialStore: dependencies.keychain,
        clock: dependencies.clock
    )

    private lazy var settingsCoordinator = SettingsCoordinator(
        sessionCoordinator: sessionCoordinator,
        policy: .default
    )

    private lazy var bulkEngine = BulkOperationEngine(
        dependencies: .init(
            registryCoordinator: registryCoordinator,
            sessionCoordinator: sessionCoordinator,
            settingsCoordinator: settingsCoordinator,
            clock: dependencies.clock
        )
    )

    private lazy var musicCoordinator = MusicServiceCoordinator(
        admission: ConnectionAdmission(
            dnsResolver: dependencies.dns,
            trustWarningStore: dependencies.trustWarnings
        ),
        clock: dependencies.clock
    )

    private lazy var multiRoomSettingsCoordinator = SettingsCoordinator(
        sessionCoordinator: sessionCoordinator,
        policy: .guidedMultiRoomSetup
    )

    private lazy var nativeRoomSettingsCoordinator = SettingsCoordinator(
        sessionCoordinator: sessionCoordinator,
        policy: .nativeRoomLink
    )

    private lazy var nativeRoomCoordinator = NativeRoomCoordinator(
        settingsCoordinator: nativeRoomSettingsCoordinator,
        sessionCoordinator: sessionCoordinator
    )

    private lazy var credentialFleetClient = FleetHTTPClient(
        transport: dependencies.fleetHTTP,
        admission: ConnectionAdmission(
            dnsResolver: dependencies.dns,
            trustWarningStore: dependencies.trustWarnings
        ),
        credentialStore: dependencies.keychain
    )

    private lazy var castingCoordinator = CastingCoordinator(
        discoverer: CastingTargetDiscovererAdapter(scanner: NetworkCastingServiceScanner()),
        controller: MultiKindCastingController()
    )

    private var snapcastConnection: AdmittedConnection?

    private lazy var evidenceCoordinator = ReleaseEvidenceCoordinator(
        store: FileReleaseEvidenceStore(),
        clock: dependencies.clock
    )

    private var pendingBulkConfirmation: BulkConfirmation?
    private var pendingBulkTargets: [BulkOperationTarget] = []

    /// Nonisolated so SwiftUI view initializers can construct the store as a
    /// default argument. Every stored property is initialized inline; the
    /// MainActor isolation applies to all instance methods.
    nonisolated init(dependencies: DependencyContainer = .production()) {
        self.dependencies = dependencies
    }

    // MARK: Lifecycle

    func bootstrap() {
        Task { await loadRegistry() }
        Task { await refreshEvidence() }
    }

    private func loadRegistry() async {
        do {
            _ = try await registryCoordinator.load()
            let loaded = try await registryCoordinator.entries()
            withAnimation(.spring(response: 0.5, dampingFraction: 0.9)) {
                entries = loaded.sorted {
                    ($0.identity?.name ?? "") < ($1.identity?.name ?? "")
                }
            }
            statusMessage = "\(loaded.count) Portal\(loaded.count == 1 ? "" : "s") in your fleet"
            recomputeCommands()
        } catch {
            statusMessage = "Registry unavailable on this machine."
        }
    }

    // MARK: Intents

    func dispatch(_ intent: PortalManagerIntent) {
        lastIntent = intent

        switch intent {
        case .navigate(let destination):
            navigate(to: destination)
        case .refreshDiscovery:
            refreshDiscovery()
        case .openManualEndpoint:
            showManualOnboarding = true
        case .refreshStatus:
            Task { await refreshSelectedStatus() }
        case .identify:
            Task { await runApprovedAction(.identify) }
        case .reaffirm:
            Task { await runApprovedAction(.reaffirm) }
        case .apply:
            statusMessage = "Settings apply runs from the Settings tab."
        case .retry:
            statusMessage = "Retry re-runs the failed step."
        case .refreshMusic:
            Task { await refreshMusic() }
        case .scanCasting:
            Task { await refreshCasting() }
        case .connectCasting(let id):
            Task { await connectCasting(id) }
        case .disconnectCasting(let id):
            Task { await disconnectCasting(id) }
        case .playCasting(let request, let id):
            Task { await playCasting(CastingPlaybackCommand(request, id)) }
        case .stopCasting(let id):
            Task { await stopCasting(id) }
        case .refreshApps:
            Task { await refreshSelectedApps() }
        case .refreshAppProfiles:
            Task { await refreshSelectedProfiles() }
        case .applyAppProfile(let action):
            Task { await applyAppProfile(action) }
        case .retryAppProfile(let profile):
            Task { await retryAppProfile(profile) }
        case .stageAppSync(let packageName):
            guard !fleetSyncRunning else { return }
            pendingAppSyncPackage = packageName
            pendingAppSyncTargets = Set(eligibleTargets().map(\.portalID))

        case .toggleAppSyncTarget(let portalID):
            guard pendingAppSyncPackage != nil, !fleetSyncRunning else { return }
            if pendingAppSyncTargets.contains(portalID) {
                pendingAppSyncTargets.remove(portalID)
            } else {
                pendingAppSyncTargets.insert(portalID)
            }

        case .confirmAppSync:
            guard let packageName = pendingAppSyncPackage, !fleetSyncRunning else { return }
            Task { await installAppAcrossFleet(packageName, targetIDs: pendingAppSyncTargets) }

        case .cancelAppSync:
            pendingAppSyncPackage = nil
            pendingAppSyncTargets = []
        case .portalMedia(let action):
            Task { await sendPortalMedia(action) }
        case .portalVolume(let direction):
            Task { await sendPortalVolume(direction) }
        case .selectProvisioningADB(let url):
            selectProvisioningADB(url)
        case .selectProvisioningArtifact(let url):
            selectProvisioningArtifact(url)
        case .startProvisioning(let mode):
            provisioningTask = Task { [weak self] in
                await self?.startProvisioning(mode)
            }
        case .cancelProvisioning:
            cancelProvisioning()
        case .copyReleaseReport:
            Task { await copyReleaseReport() }
        case .cancel:
            Task { await cancelActiveOperation() }
        }
    }

    // MARK: Provisioning

    func canStartProvisioning(_ mode: ProvisioningMode) -> Bool {
        guard !provisioningState.isRunning,
              let adbExecutableSelection,
              adbExecutableSelection.isSafeSelection,
              (try? Self.provisioningTargetID(
                  for: mode,
                  serial: provisioningDeviceSerialInput,
                  entries: entries
              )) != nil,
              !provisioningDeviceSerialInput
                  .trimmingCharacters(in: .whitespacesAndNewlines)
                  .isEmpty,
              !provisioningPortalEndpointInput
                  .trimmingCharacters(in: .whitespacesAndNewlines)
                  .isEmpty else {
            return false
        }

        if mode == .fullUSBProvisioning {
            return provisioningArtifact?.isSafeSelection == true
        }
        return true
    }

    func selectProvisioningADB(_ url: URL) {
        guard !provisioningState.isRunning else { return }
        let reference = LocalExecutableReference(url: url)
        guard reference.isSafeSelection, FileManager.default.isExecutableFile(atPath: url.path) else {
            provisioningState.statusMessage = "Choose a readable ADB executable."
            return
        }

        if let previousURL = adbExecutableSelection?.securityScopedURL {
            releaseSecurityScope(previousURL)
        }
        retainSecurityScope(url)
        adbExecutableSelection = reference
        provisioningState.statusMessage = "ADB executable selected."
    }

    func selectProvisioningArtifact(_ url: URL) {
        guard !provisioningState.isRunning else { return }
        let artifact = LocalArtifact(
            url: url,
            expectedPackageIdentity: "com.immortal.launcher",
            expectedSignaturePolicy: .certificateSHA256(
                LocalArtifactVerifier.Configuration.productionSigningCertificateSHA256
            )
        )
        var isDirectory: ObjCBool = false
        guard artifact.isSafeSelection,
              FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory),
              !isDirectory.boolValue,
              FileManager.default.isReadableFile(atPath: url.path) else {
            provisioningState.statusMessage = "Choose a local Immortal APK."
            return
        }

        releaseArtifactSelection()
        retainSecurityScope(url)
        provisioningArtifact = artifact
        provisioningState.statusMessage = "Local artifact selected for verification."
    }

    func clearProvisioningArtifact() {
        guard !provisioningState.isRunning else { return }
        releaseArtifactSelection()
        provisioningState.statusMessage = "Local artifact cleared."
    }

    func cancelProvisioning() {
        guard provisioningState.isRunning else { return }
        provisioningTask?.cancel()
        provisioningState.statusMessage = "Cancelling provisioning..."
    }

    private func startProvisioning(_ mode: ProvisioningMode) async {
        guard canStartProvisioning(mode), let adbExecutableSelection else { return }
        guard let admissionRequest = try? ConnectionAdmissionRequest(
            rawEndpoint: provisioningPortalEndpointInput.trimmingCharacters(in: .whitespacesAndNewlines),
            serviceKind: .portal,
            protocolName: "http",
            defaultPort: LANEndpoint.defaultPortalAgentPort,
            source: .provisioning
        ) else {
            provisioningState.statusMessage = "Enter a private Portal address."
            return
        }

        let friendlyName = provisioningFriendlyNameInput
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let portalID: PortalID
        do {
            portalID = try Self.provisioningTargetID(
                for: mode,
                serial: provisioningDeviceSerialInput,
                entries: entries
            )
        } catch let error as ProvisioningTargetError {
            provisioningState.statusMessage = error == .registeredPortalRequired
                ? "Recovery requires the USB serial to match a registered Portal."
                : "The entered USB serial matches more than one Portal."
            return
        } catch {
            provisioningState.statusMessage = "The provisioning target could not be resolved."
            return
        }
        let runner = ProcessADBRunner(executable: adbExecutableSelection)
        let artifactVerifier = LocalArtifactVerifier(
            configuration: .immortalProduction
        )
        let sink = ClosureProvisioningEventSink { [weak self] event in
            await self?.handleProvisioningEvent(event)
        }
        let coordinator = ProvisioningCoordinator(
            adb: runner,
            artifactVerifier: artifactVerifier,
            workspaceFactory: dependencies.provisioningWorkspace,
            connectionAdmission: ConnectionAdmission(
                dnsResolver: dependencies.dns,
                trustWarningStore: dependencies.trustWarnings
            ),
            fleetTransport: dependencies.fleetHTTP,
            credentialStore: dependencies.keychain,
            registryCoordinator: registryCoordinator,
            clock: dependencies.clock,
            eventSink: sink
        )

        provisioningState = ProvisioningWorkflowState(
            isRunning: true,
            progress: ProvisioningProgress(
                mode: mode,
                step: .preflight,
                completedStepCount: 0,
                totalStepCount: mode.expectedSteps.count
            ),
            currentStep: .preflight,
            statusMessage: "Starting provisioning..."
        )

        do {
            let result: ProvisioningResult
            switch mode {
            case .fleetAgentEnablementRecovery:
                result = try await coordinator.provision(
                    EnablementRecoveryPlan(
                        deviceSerial: provisioningDeviceSerialInput,
                        adbExecutable: adbExecutableSelection,
                        friendlyName: friendlyName.isEmpty ? nil : friendlyName
                    ),
                    portalID: portalID,
                    endpoint: admissionRequest
                )
            case .fullUSBProvisioning:
                guard let provisioningArtifact else { return }
                result = try await coordinator.provision(
                    FullUSBProvisioningPlan(
                        deviceSerial: provisioningDeviceSerialInput,
                        adbExecutable: adbExecutableSelection,
                        localArtifact: provisioningArtifact,
                        friendlyName: friendlyName.isEmpty ? nil : friendlyName
                    ),
                    portalID: portalID,
                    endpoint: admissionRequest
                )
            }

            withAnimation(.spring(response: 0.45, dampingFraction: 0.9)) {
                upsert(entry: result.entry)
                selectedPortalID = result.portalID
            }
            provisioningState.statusMessage = "Portal verified and saved."
        } catch is CancellationError {
            provisioningState.statusMessage = "Provisioning cancelled."
        } catch let failure as ProvisioningFailure {
            provisioningState.statusMessage = failure.sanitizedMessage
        } catch {
            provisioningState.statusMessage = "The provisioning operation was rejected."
        }

        finishProvisioning()
    }

    private func handleProvisioningEvent(_ event: ProvisioningEvent) async {
        if let progress = event.progressValue {
            provisioningState.progress = progress
        }
        if let step = event.step {
            provisioningState.currentStep = step
        }

        switch event {
        case .preflightCompleted:
            provisioningState.statusMessage = "USB device preflight passed."
        case .artifactVerificationCompleted:
            provisioningState.statusMessage = "Artifact verification passed."
        case .agentManifestRecovered:
            provisioningState.statusMessage = "Fleet Agent manifest recovered; verifying over LAN."
        case .completed:
            break
        case .cancelled:
            provisioningState.statusMessage = "Provisioning cancelled."
        case .failed(let failure):
            provisioningState.statusMessage = failure.sanitizedMessage
        case .started, .stepStarted, .stepCompleted, .progress:
            break
        }
    }

    private func finishProvisioning() {
        provisioningTask = nil
        provisioningState.isRunning = false
        provisioningState.currentStep = nil
    }

    private func retainSecurityScope(_ url: URL) {
        if url.startAccessingSecurityScopedResource() {
            retainedSecurityScopedURLs.insert(url)
        }
    }

    private func releaseArtifactSelection() {
        if let url = provisioningArtifact?.securityScopedURL {
            releaseSecurityScope(url)
        }
        provisioningArtifact = nil
    }

    private func releaseProvisioningSelections() {
        if let url = adbExecutableSelection?.securityScopedURL {
            releaseSecurityScope(url)
        }
        adbExecutableSelection = nil
        releaseArtifactSelection()
    }

    private func releaseSecurityScope(_ url: URL) {
        guard retainedSecurityScopedURLs.remove(url) != nil else { return }
        url.stopAccessingSecurityScopedResource()
    }

    nonisolated static func provisioningTargetID(
        for mode: ProvisioningMode,
        serial: String,
        entries: [PortalRegistryEntry]
    ) throws -> PortalID {
        let normalizedSerial = serial.trimmingCharacters(in: .whitespacesAndNewlines)
        let matchingEntries = entries.filter { entry in
            entry.identity?.serial?.caseInsensitiveCompare(normalizedSerial) == .orderedSame
        }

        switch mode {
        case .fleetAgentEnablementRecovery:
            guard matchingEntries.count == 1, let match = matchingEntries.first else {
                throw ProvisioningTargetError.registeredPortalRequired
            }
            return match.id
        case .fullUSBProvisioning:
            guard matchingEntries.count <= 1 else {
                throw ProvisioningTargetError.ambiguousSerial
            }
            return matchingEntries.first?.id ?? PortalID()
        }
    }

    func navigate(to destination: SidebarDestination) {
        withAnimation(.spring(response: 0.45, dampingFraction: 0.9)) {
            navigation.selection = destination
            navigation.detailSection =
                destination == .portals ? .overview : destination.detailSection
        }
        recomputeCommands()
    }

    func select(_ portalID: PortalID?) {
        selectedPortalID = portalID
        if portalID != nil, navigation.selection != .portals {
            navigation.selection = .portals
        }
        guard let portalID else {
            selectedSnapshot = nil
            return
        }
        Task {
            let snapshot = try? await sessionCoordinator.snapshot(for: portalID)
            withAnimation(.easeOut(duration: 0.2)) {
                selectedSnapshot = snapshot
            }
            recomputeCommands()
        }
    }

    // MARK: Discovery

    func refreshDiscovery() {
        guard !discoveryRunning else { return }
        discoveryRunning = true
        statusMessage = "Searching the local network…"

        Task {
            do {
                try await dependencies.bonjour.start()
            } catch {
                discoveryRunning = false
                statusMessage = "Discovery could not start on this network."
                recomputeCommands()
                return
            }

            // A separate timeout result guarantees that the scan ends even
            // when mDNS stays quiet or the browser stream never finishes.
            let candidates = await Self.collectResolvedCandidates(
                from: dependencies.bonjour,
                limit: 24,
                window: .seconds(4)
            )
            await dependencies.bonjour.stop()

            withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) {
                discoveryCandidates = candidates
            }

            discoveryRunning = false
            statusMessage = candidates.isEmpty
                ? "No Portals advertised yet — add one by IP address."
                : "Found \(candidates.count) candidate\(candidates.count == 1 ? "" : "s")"
            recomputeCommands()
        }
    }

    private nonisolated static func collectResolvedCandidates(
        from browser: any BonjourBrowser,
        limit: Int,
        window: Duration
    ) async -> [BonjourService] {
        await withTaskGroup(of: [BonjourService].self) { group in
            group.addTask {
                var candidates: [BonjourService] = []
                for await event in browser.events() {
                    switch event {
                    case .found(let service), .updated(let service):
                        if service.isResolved,
                           !candidates.contains(where: { $0.instanceName == service.instanceName }) {
                            candidates.append(service)
                        }
                    case .removedInstance(let name):
                        candidates.removeAll { $0.instanceName == name }
                    default:
                        break
                    }
                    if candidates.count >= limit { break }
                }
                return candidates
            }
            group.addTask {
                try? await Task.sleep(for: window)
                return []
            }
            let first = await group.next() ?? []
            group.cancelAll()
            return first
        }
    }
    // MARK: Status & approved actions

    private func refreshSelectedStatus() async {
        guard let portalID = selectedPortalID,
              let entry = entries.first(where: { $0.id == portalID }),
              let endpoint = entry.endpoint else {
            statusMessage = "Select an authenticated Portal first."
            return
        }

        do {
            let request = ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            )
            _ = try await sessionCoordinator.verifyBearer(
                portalID: portalID,
                admissionRequest: request,
                credentialReference: CredentialReference.portalCredential(
                    portalID: portalID,
                    kind: .verifiedBearer
                )
            )
            if let refreshed = try await registryCoordinator.entries().first(where: { $0.id == portalID }) {
                upsert(entry: refreshed)
            }
            statusMessage = "Status verified."
        } catch {
            statusMessage = "Verification failed — check the connection."
        }
    }

    private func runApprovedAction(_ action: ApprovedAction) async {
        guard let portalID = selectedPortalID,
              let entry = entries.first(where: { $0.id == portalID }),
              let endpoint = entry.endpoint else {
            statusMessage = "Select an authenticated Portal first."
            return
        }

        do {
            let request = ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            )
            _ = try await sessionCoordinator.execute(
                portalID: portalID,
                admissionRequest: request,
                route: .action(action),
                method: .post,
                credential: .verifiedBearer
            )
            statusMessage = "\(action.rawValue.capitalized) sent."
        } catch {
            statusMessage = "The action was rejected by the Portal."
        }
    }

    // MARK: Apps & Portal controls

    private func request(for entry: PortalRegistryEntry) -> ConnectionAdmissionRequest? {
        guard let endpoint = entry.endpoint else { return nil }
        return try? ConnectionAdmissionRequest(
            rawEndpoint: endpoint.hostOrAddress,
            serviceKind: .portal,
            protocolName: "http",
            defaultPort: endpoint.port,
            source: endpoint.source
        )
    }

    private func executePortalRoute(
        _ route: FleetRoute,
        method: HTTPMethod = .get,
        body: FleetRequestBody = .none,
        expectedAppliedKeys: [String] = []
    ) async throws -> FleetHTTPClientResponse {
        guard let portalID = selectedPortalID,
              let entry = entries.first(where: { $0.id == portalID }),
              let admissionRequest = request(for: entry) else {
            throw ManagerError.validation(field: "Portal", reason: "Select an authenticated Portal first.")
        }
        return try await sessionCoordinator.execute(
            portalID: portalID,
            admissionRequest: admissionRequest,
            route: route,
            method: method,
            body: body,
            expectedAppliedKeys: expectedAppliedKeys
        )
    }

    private func refreshSelectedApps() async {
        guard selectedPortalID != nil else {
            statusMessage = "Select a Portal to see installed apps."
            return
        }
        appsRefreshing = true
        defer { appsRefreshing = false }
        do {
            let response = try await executePortalRoute(.apps)
            selectedPortalApps = PortalAppSummary.decode(from: response.payload)
            statusMessage = "Installed apps refreshed."
        } catch {
            selectedPortalApps = []
            statusMessage = "Could not read this Portal's app list."
        }
    }

    private func refreshSelectedProfiles() async {
        guard let portalID = selectedPortalID else {
            statusMessage = "Select a Portal to see desired app state."
            return
        }
        profilesRefreshing = true
        defer { profilesRefreshing = false }
        do {
            let response = try await executePortalRoute(.appProfiles)
            selectedPortalProfiles = FleetAppProfileSnapshot.parse(response.payload).profiles
            statusMessage = "Desired app state refreshed."
            profileResults.removeValue(forKey: portalID)
        } catch {
            selectedPortalProfiles = []
            profileResults[portalID] = .failure("Unavailable")
            statusMessage = "Could not read this Portal's desired app state."
        }
    }

    private func applyAppProfile(_ action: AppProfileAction) async {
        guard let portalID = selectedPortalID else {
            statusMessage = "Select an authenticated Portal first."
            return
        }
        let packageName = profilePackageInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !packageName.isEmpty, !profilesRefreshing else { return }

        var body: [String: Any] = ["packageName": packageName, "action": action.rawValue]
        let requestedAPKURL = profileApkURLInput.trimmingCharacters(in: .whitespacesAndNewlines)
        if case .install = action, !requestedAPKURL.isEmpty, let apkURL = URL(string: requestedAPKURL),
           apkURL.scheme?.isEmpty == false, apkURL.host()?.isEmpty == false {
            body["apkUrl"] = apkURL.absoluteString
        }
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }

        await submitAppProfile(body: data, portalID: portalID, actionTitle: "Applied")
    }

    private func retryAppProfile(_ profile: FleetAppProfile) async {
        guard let portalID = selectedPortalID else {
            statusMessage = "Select an authenticated Portal first."
            return
        }
        let body: [String: Any] = [
            "packageName": profile.packageName,
            "action": profile.action,
            "retry": true,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }

        await submitAppProfile(body: data, portalID: portalID, actionTitle: "Retried")
    }

    private func submitAppProfile(
        body data: Data,
        portalID: PortalID,
        actionTitle: String
    ) async {
        profilesRefreshing = true
        defer { profilesRefreshing = false }
        do {
            let response = try await executePortalRoute(
                .appProfiles,
                method: .post,
                body: .json(data)
            )
            if case .object(let payload) = response.payload,
               case .object(let profile)? = payload["profile"],
               case .string(let state)? = profile["state"] {
                if state == FleetAppProfile.installedState {
                    profileResults[portalID] = .success(actionTitle)
                } else {
                    profileResults[portalID] = .failure(state)
                }
            } else {
                profileResults[portalID] = .failure("Rejected")
            }
            statusMessage = "Desired app state updated."
        } catch {
            profileResults[portalID] = .failure("Rejected")
            statusMessage = "Could not update desired app state."
        }
        await refreshSelectedProfiles()
    }

    private func installAppAcrossFleet(
        _ packageName: String,
        targetIDs: Set<PortalID>
    ) async {
        guard !fleetSyncRunning else { return }
        pendingAppSyncPackage = nil
        let targets = eligibleTargets().filter { targetIDs.contains($0.portalID) }
        fleetSyncRunning = true
        fleetSyncProgress = SyncProgress(finished: 0, total: targets.count)
        fleetSyncResults = [:]
        statusMessage = "Syncing \(packageName)…"
        defer { fleetSyncRunning = false }

        for target in targets {
            do {
                let data = try JSONSerialization.data(withJSONObject: ["packageName": packageName])
                let response = try await sessionCoordinator.execute(
                    portalID: target.portalID,
                    admissionRequest: target.admissionRequest,
                    route: .install,
                    method: .post,
                    body: .json(data)
                )
                if let result = FleetInstallResponse.parse(response.payload) {
                    switch result {
                    case .installed:
                        fleetSyncResults[target.portalID] = .success("Installed")
                    case .busy, .pending:
                        fleetSyncResults[target.portalID] = .failure("Pending retry")
                    case .failed(let code):
                        fleetSyncResults[target.portalID] = .failure(code)
                    }
                } else {
                    fleetSyncResults[target.portalID] = .failure("Rejected")
                }
            } catch {
                fleetSyncResults[target.portalID] = .failure("Rejected")
            }
            fleetSyncProgress.finished += 1
        }

        let failures = fleetSyncResults.values.filter {
            if case .failure = $0 { return true }
            return false
        }.count
        statusMessage = failures == 0
            ? "\(packageName) synced to \(targets.count) selected Portal\(targets.count == 1 ? "" : "s")."
            : "\(packageName) finished with \(failures) failure(s)."
        await refreshSelectedApps()
    }

    var eligibleTargetCount: Int {
        eligibleTargets().count
    }

    var appSyncTargetOptions: [AppSyncTargetOption] {
        entries.compactMap { entry in
            guard request(for: entry) != nil else { return nil }
            var isEligible = false
            switch entry.connectionState {
            case .online, .bearerAuthenticated:
                isEligible = !entry.credentialReferences.isEmpty
            default:
                break
            }
            return AppSyncTargetOption(
                portalID: entry.id,
                name: entry.identity?.name ?? "Portal",
                stateTitle: entry.connectionState.displayTitle,
                isEligible: isEligible
            )
        }
    }

    private func eligibleTargets() -> [BulkOperationTarget] {
        entries.filter { entry in
            if case .online = entry.connectionState { return !entry.credentialReferences.isEmpty }
            if case .bearerAuthenticated = entry.connectionState { return !entry.credentialReferences.isEmpty }
            return false
        }.compactMap { entry in
            request(for: entry).map {
                BulkOperationTarget(portalID: entry.id, admissionRequest: $0)
            }
        }
    }

    // MARK: Credential sharing

    var credentialSyncOptions: [CredentialSyncPortalOption] {
        entries.map { entry in
            let authenticated = isAuthenticated(entry)
            let hasSourceCredentials = entry.credentialReferences.contains {
                $0.namespace == "source"
            }
            let hasPortalBearer = entry.credentialReferences.contains {
                $0.namespace == "portal" && $0.identifier.hasSuffix("/verifiedBearer")
            }
            return CredentialSyncPortalOption(
                portalID: entry.id,
                name: entry.identity?.name ?? "Portal",
                isEligibleSource: authenticated && hasSourceCredentials,
                isEligibleTarget: authenticated && hasPortalBearer && entry.capabilities?.sources == true
            )
        }
    }

    func shareCredentials() async {
        guard !credentialSyncRunning else { return }
        guard let sourceID = selectedCredentialSource() else {
            statusMessage = "Choose a Portal that already has the source configured."
            return
        }

        let selectedTargets = entries.filter { credentialSyncSelection.targetIDs.contains($0.id) }
        do {
            let contexts = try selectedTargets.map(CredentialSyncTargetContext.init(entry:))
            let transport = FleetCredentialSyncTransport(
                fleetClient: credentialFleetClient,
                credentialStore: dependencies.keychain,
                targets: contexts
            )
            let coordinator = FleetCredentialSyncCoordinator(transport: transport)
            let source = try SharedCredentialSource(
                portalID: sourceID.portalID,
                sourceID: credentialSyncSelection.sourceID,
                fields: credentialSyncSelection.fields
            )

            credentialSyncRunning = true
            credentialSyncResults = []
            defer { credentialSyncRunning = false }
            let report = await coordinator.sync(source, targets: credentialTargets(from: selectedTargets))
            credentialSyncResults = report.results
            statusMessage = report.isFullySuccessful
                ? "Credentials shared with every selected Portal."
                : "Sharing finished — review each destination's result."
        } catch {
            credentialSyncResults = []
            statusMessage = "Credential sharing was blocked before any request."
        }
    }

    private func selectedCredentialSource() -> CredentialSyncPortalOption? {
        guard let id = credentialSyncSelection.sourcePortalID else { return nil }
        return credentialSyncOptions.first { $0.portalID == id && $0.isEligibleSource }
    }

    private func credentialTargets(
        from entries: [PortalRegistryEntry]
    ) -> [CredentialSyncTarget] {
        entries.map { entry in
            CredentialSyncTarget(
                portalID: entry.id,
                connectionState: entry.connectionState,
                credentialReferences: entry.credentialReferences
            )
        }
    }

    private func isAuthenticated(_ entry: PortalRegistryEntry) -> Bool {
        switch entry.connectionState {
        case .online, .bearerAuthenticated:
            return true
        default:
            return false
        }
    }

    // MARK: Casting

    func refreshCasting() async {
        guard !castingScanning else { return }
        castingScanning = true
        statusMessage = "Looking for nearby receivers…"

        do {
            let result = try await castingCoordinator.discover(maximumDuration: 4)
            var states: [CastingTargetID: CastingConnectionState] = [:]
            var playbackStates: [CastingTargetID: CastingPlaybackSnapshot] = [:]
            for target in result.targets {
                if let record = await castingCoordinator.target(for: target.id) {
                    states[target.id] = record.connectionState
                    if let playback = await castingCoordinator.playback(for: target.id) {
                        playbackStates[target.id] = playback
                    }
                } else {
                    states[target.id] = .disconnected
                }
            }
            withAnimation(.spring(response: 0.45, dampingFraction: 0.88)) {
                castingTargets = result.targets
                castingStates = states
                castingPlayback = playbackStates
            }
            statusMessage = result.timedOut
                ? "No receiver answered in time."
                : result.targets.isEmpty
                    ? "No AirPlay or Chromecast receivers found."
                    : "Found \(result.targets.count) receiver\(result.targets.count == 1 ? "" : "s")."
        } catch {
            statusMessage = "Receiver search could not start on this network."
        }
        castingScanning = false
    }

    func connectCasting(_ id: CastingTargetID) async {
        guard let target = castingTargets.first(where: { $0.id == id }) else { return }
        castingStates[id] = .connecting
        do {
            let state = try await castingCoordinator.connect(id)
            castingStates[id] = state
            _ = target
            statusMessage = "\(target.name) is ready."
        } catch {
            castingStates[id] = await castingCoordinator.target(for: id)?.connectionState ?? .failed(.transport)
            statusMessage = "Could not connect to \(target.name)."
        }
    }

    func disconnectCasting(_ id: CastingTargetID) async {
        guard let target = castingTargets.first(where: { $0.id == id }) else { return }
        castingStates[id] = .disconnecting
        do {
            let state = try await castingCoordinator.disconnect(id)
            castingStates[id] = state
            castingPlayback[id] = nil
            statusMessage = "\(target.name) stopped."
        } catch {
            castingStates[id] = await castingCoordinator.target(for: id)?.connectionState ?? .failed(.transport)
            statusMessage = "\(target.name) did not stop cleanly."
        }
    }

    func playCasting(_ command: CastingPlaybackCommand) async {
        let request = command.request
        let id = command.targetID
        guard let target = castingTargets.first(where: { $0.id == id }) else { return }
        castingPlayback[id] = CastingPlaybackSnapshot(state: .preparing, title: request.title)
        do {
            let snapshot = try await castingCoordinator.play(request, on: id)
            castingPlayback[id] = snapshot
            statusMessage = "Playing on \(target.name)."
        } catch {
            castingPlayback[id] = await castingCoordinator.playback(for: id)
                ?? CastingPlaybackSnapshot(state: .failed(.transport), title: request.title)
            statusMessage = "Could not start playback on \(target.name)."
        }
    }

    func stopCasting(_ id: CastingTargetID) async {
        guard let target = castingTargets.first(where: { $0.id == id }) else { return }
        castingPlayback[id] = CastingPlaybackSnapshot(
            state: .stopping,
            title: castingPlayback[id]?.title
        )
        do {
            let snapshot = try await castingCoordinator.stop(on: id)
            castingPlayback[id] = snapshot
            statusMessage = "Playback stopped on \(target.name)."
        } catch {
            castingPlayback[id] = await castingCoordinator.playback(for: id)
                ?? CastingPlaybackSnapshot(state: .failed(.transport))
            statusMessage = "Could not stop playback on \(target.name)."
        }
    }

    private func sendPortalMedia(_ action: MediaTransportAction) async {
        do {
            let data = try JSONSerialization.data(withJSONObject: ["action": action.rawValue])
            _ = try await executePortalRoute(.remoteMedia, method: .post, body: .json(data))
            statusMessage = "Media command sent."
        } catch {
            statusMessage = action.requiresActiveSession ? "Nothing is playing on this Portal." : "The media command was rejected."
        }
    }

    private func sendPortalVolume(_ direction: VolumeDirection) async {
        do {
            let data = try JSONSerialization.data(withJSONObject: ["dir": direction.rawValue])
            _ = try await executePortalRoute(.remoteVolume, method: .post, body: .json(data))
            statusMessage = "Volume updated."
        } catch {
            statusMessage = "The volume command was rejected."
        }
    }

    // MARK: Music

    func refreshMusic() {
        let maHost = maHostInput.trimmingCharacters(in: .whitespaces)
        let snapHost = snapcastHostInput.trimmingCharacters(in: .whitespaces)
        guard !maHost.isEmpty || !snapHost.isEmpty else {
            statusMessage = "Enter a host to inspect."
            return
        }

        musicRefreshing = true
        statusMessage = "Reading topology…"

        Task {
            var maResult: MusicTopologySnapshot?
            var snapResult: SnapcastTopologySnapshot?

            if !maHost.isEmpty {
                maResult = await readMusicAssistant(hostOrAddress: maHost)
            }
            if !snapHost.isEmpty {
                snapResult = await readSnapcast(hostOrAddress: snapHost)
            }

            withAnimation(.spring(response: 0.5, dampingFraction: 0.88)) {
                if let maResult { musicAssistantSnapshot = maResult }
                if let snapResult { snapcastSnapshot = snapResult }
            }
            musicRefreshing = false
            statusMessage = "Topology updated."
            recomputeCommands()
        }
    }

    /// Admits and reads one Music Assistant endpoint through typed adapters.
    private func readMusicAssistant(hostOrAddress: String) async -> MusicTopologySnapshot? {
        do {
            let config = MusicServiceConfiguration.musicAssistant(hostOrAddress: hostOrAddress)
            let admitted = try await musicCoordinator.admittedRequest(config)
            let provider = ServiceCredentialProvider(store: dependencies.keychain, reference: nil)
            let adapter = ReadOnlyMusicAssistantAdapter(credentialProvider: provider)
            return try await adapter.topology(
                hostOrAddress: admitted.endpoint.hostOrAddress,
                port: admitted.endpoint.port,
                interfaceZone: admitted.endpoint.interfaceZone
            )
        } catch {
            statusMessage = "Music Assistant unreachable on this network."
            return nil
        }
    }

    private func readSnapcast(hostOrAddress: String) async -> SnapcastTopologySnapshot? {
        do {
            let config = MusicServiceConfiguration.snapcast(hostOrAddress: hostOrAddress)
            snapcastConnection = try await musicCoordinator.admittedRequest(config)
            let provider = ServiceCredentialProvider(store: dependencies.keychain, reference: nil)
            let adapter = ReadOnlySnapcastAdapter(credentialProvider: provider)
            guard let admitted = snapcastConnection else { return nil }
            return try await adapter.topology(
                hostOrAddress: admitted.endpoint.hostOrAddress,
                port: admitted.endpoint.port,
                interfaceZone: admitted.endpoint.interfaceZone
            )
        } catch {
            statusMessage = "Snapcast unreachable on this network."
            return nil
        }
    }

    private func mutateSnapcast(
        _ operation: (ReadOnlySnapcastAdapter, AdmittedConnection) async throws -> Void
    ) async {
        do {
            if snapcastConnection == nil {
                _ = await readSnapcast(hostOrAddress: snapcastHostInput.trimmingCharacters(in: .whitespaces))
            }
            guard let connection = snapcastConnection else {
                statusMessage = "Connect to Snapcast first."
                return
            }
            try await operation(ReadOnlySnapcastAdapter(credentialProvider: ServiceCredentialProvider(store: dependencies.keychain, reference: nil)), connection)
            await refreshMusic()
        } catch {
            statusMessage = "Snapcast rejected the change."
        }
    }

    func setSnapcastVolume(clientID: String, percent: Int) async {
        await mutateSnapcast { adapter, connection in
            try await adapter.setClientVolume(
                hostOrAddress: connection.endpoint.hostOrAddress,
                port: connection.endpoint.port,
                interfaceZone: connection.endpoint.interfaceZone,
                clientID: clientID,
                percent: percent
            )
        }
    }

    func setSnapcastGroup(groupID: String, clients: [String]) async {
        await mutateSnapcast { adapter, connection in
            try await adapter.setGroupClients(
                hostOrAddress: connection.endpoint.hostOrAddress,
                port: connection.endpoint.port,
                interfaceZone: connection.endpoint.interfaceZone,
                groupID: groupID,
                clientIDs: clients
            )
        }
    }

    func renameSnapcastGroup(groupID: String, name: String) async {
        await mutateSnapcast { adapter, connection in
            try await adapter.setGroupName(
                hostOrAddress: connection.endpoint.hostOrAddress,
                port: connection.endpoint.port,
                interfaceZone: connection.endpoint.interfaceZone,
                groupID: groupID,
                name: name
            )
        }
    }

    func setSnapcastStream(groupID: String, streamID: String) async {
        await mutateSnapcast { adapter, connection in
            try await adapter.setStream(
                hostOrAddress: connection.endpoint.hostOrAddress,
                port: connection.endpoint.port,
                interfaceZone: connection.endpoint.interfaceZone,
                groupID: groupID,
                streamID: streamID
            )
        }
    }

    // MARK: Guided multi-room setup

    var multiRoomSetupPortalRows: [MultiRoomSetupPortalRow] {
        guard case .ready(let plan) = multiRoomSetupPhase else { return [] }
        return entries
            .sorted { ($0.identity?.name ?? "") < ($1.identity?.name ?? "") }
            .map { entry in
                MultiRoomSetupPortalRow(
                    portalID: entry.id,
                    name: entry.identity?.name ?? "Portal",
                    matchedClientID: plan.matches[entry.id],
                    isAmbiguous: plan.ambiguousPortalIDs.contains(entry.id)
                )
            }
    }

    func findMultiRoomServer() async {
        var host = snapcastHostInput.trimmingCharacters(in: .whitespacesAndNewlines)
        if host.isEmpty {
            if let configuredHost = await configuredSnapcastHost() {
                host = configuredHost
                snapcastHostInput = configuredHost
            } else {
                multiRoomSetupPhase = .failed("No managed Portal knows the audio server yet. Enter its IP address.")
                return
            }
        }

        multiRoomSetupPhase = .checking
        guard let snapshot = await readSnapcast(hostOrAddress: host), !snapshot.clients.isEmpty else {
            multiRoomSetupPhase = .failed("No audio rooms were found at that address.")
            return
        }

        let portalCandidates = entries.map { entry in
            MultiRoomPortalCandidate(
                portalID: entry.id,
                name: entry.identity?.name ?? "Portal",
                address: entry.endpoint?.hostOrAddress
            )
        }
        let clientCandidates = snapshot.clients.map { client in
            MultiRoomClientCandidate(
                clientID: client.clientID,
                name: client.name,
                address: client.address
            )
        }
        let plan = MultiRoomSetupPlan(portals: portalCandidates, clients: clientCandidates)
        guard plan.hasActionableMatches else {
            multiRoomSetupPhase = .failed("Audio devices were found, but none matched a managed Portal.")
            return
        }

        if multiRoomSelectedGroupID.isEmpty || !snapshot.groups.contains(where: { $0.groupID == multiRoomSelectedGroupID }) {
            multiRoomSelectedGroupID = snapshot.groups.max(by: { $0.clientIDs.count < $1.clientIDs.count })?.groupID ?? ""
        }
        multiRoomSelectedPortalIDs.formIntersection(Set(plan.matches.keys))
        multiRoomSetupPhase = .ready(plan)
        statusMessage = "Review the suggested rooms, then apply the setup."
    }

    // MARK: Native Room Link

    var nativeRoomPortalRows: [NativeRoomPortalRow] {
        entries
            .filter { isAuthenticated($0) && $0.endpoint != nil }
            .sorted { ($0.identity?.name ?? "") < ($1.identity?.name ?? "") }
            .map {
                NativeRoomPortalRow(
                    portalID: $0.id,
                    name: $0.identity?.name ?? "Portal"
                )
            }
    }

    var nativeRoomHasReviewedSelection: Bool {
        guard let plan = nativeRoomPlan else { return false }
        return plan.source.portalID == nativeRoomSelectedSourceID
            && plan.receivers.map(\.portalID) == orderedSelectedReceivers
    }

    private var orderedSelectedReceivers: [PortalID] {
        nativeRoomPortalRows
            .map(\.portalID)
            .filter { nativeRoomSelectedReceiverIDs.contains($0) && $0 != nativeRoomSelectedSourceID }
    }

    func prepareNativeRoom() async {
        let source = nativeRoomParticipants.first { $0.portalID == nativeRoomSelectedSourceID }
        let receivers = nativeRoomParticipants.filter {
            nativeRoomSelectedReceiverIDs.contains($0.portalID)
        }

        do {
            nativeRoomPlan = try await nativeRoomCoordinator.prepare(
                source: source,
                receivers: receivers
            )
            nativeRoomReviewMessage = nil
            statusMessage = "Review the source and receiving rooms, then set up."
        } catch {
            nativeRoomPlan = nil
            nativeRoomReviewMessage = sanitizedNativeRoomMessage(error)
            statusMessage = "Room Link setup could not be checked."
        }
    }

    func applyNativeRoom() async {
        guard !nativeRoomIsRunning else { return }
        await prepareNativeRoom()
        guard let plan = nativeRoomPlan, nativeRoomHasReviewedSelection else { return }

        nativeRoomIsRunning = true
        statusMessage = "Setting up rooms..."
        defer { nativeRoomIsRunning = false }

        let report = await nativeRoomCoordinator.apply(plan: plan)
        nativeRoomReport = report
        statusMessage = report.isFullySuccessful
            ? "Room Link settings were applied."
            : "Room Link setup needs attention — review each room."
    }

    func stopNativeRooms() async {
        guard !nativeRoomIsRunning else { return }
        nativeRoomIsRunning = true
        statusMessage = "Stopping Room Link..."
        defer { nativeRoomIsRunning = false }

        let participants = currentOrReviewedNativeRoomParticipants()
        let results = await nativeRoomCoordinator.stopAll(participants: participants)
        let failures = results.filter { !$0.isSuccess }
        nativeRoomReport = results.isEmpty ? nil : NativeRoomApplyReport(
            sourceResult: results[0],
            receiverResults: Array(results.dropFirst())
        )
        statusMessage = failures.isEmpty
            ? "All selected rooms stopped."
            : "Stop completed with failures — review each room."
    }

    private var nativeRoomParticipants: [NativeRoomParticipant] {
        entries.compactMap { entry in
            guard isAuthenticated(entry), let endpoint = entry.endpoint else { return nil }
            return NativeRoomParticipant(
                portalID: entry.id,
                name: entry.identity?.name ?? "Portal",
                endpointHostOrAddress: endpoint.hostOrAddress,
                addressFamily: endpoint.addressFamily
            )
        }
    }

    private func currentOrReviewedNativeRoomParticipants() -> [NativeRoomParticipant] {
        if let plan = nativeRoomPlan, nativeRoomHasReviewedSelection {
            return [plan.source] + plan.receivers
        }
        let source = nativeRoomParticipants.filter { $0.portalID == nativeRoomSelectedSourceID }
        let receivers = nativeRoomParticipants.filter {
            nativeRoomSelectedReceiverIDs.contains($0.portalID)
        }
        return source + receivers
    }

    private func sanitizedNativeRoomMessage(_ error: Error) -> String {
        switch error {
        case let planError as NativeRoomPlanError:
            return planError.sanitizedMessage
        case let managerError as ManagerError:
            return managerError.sanitizedMessage
        default:
            return "The selection is not ready to connect."
        }
    }

    /// Reuses a server address already configured on a managed Portal so the
    /// common multi-room flow needs no manual endpoint entry.
    private func configuredSnapcastHost() async -> String? {
        let candidates = entries.filter { isAuthenticated($0) }.compactMap { entry in
            request(for: entry).map { (entry.id, $0) }
        }.prefix(4)

        for (portalID, admissionRequest) in candidates {
            guard let schema = try? await multiRoomSettingsCoordinator.refresh(
                portalID: portalID,
                admissionRequest: admissionRequest
            ),
                let control = schema.domains.first(where: { $0.id == "immortal" })?
                    .controls.first(where: { $0.key == "snapcastHost" }),
                case .string(let host) = control.value,
                !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                continue
            }
            return host.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return nil
    }

    func applyMultiRoomSetup() async {
        guard case .ready(let plan) = multiRoomSetupPhase else {
            multiRoomSetupPhase = .failed("Find the audio server before applying setup.")
            return
        }
        guard !multiRoomSelectedGroupID.isEmpty else {
            multiRoomSetupPhase = .failed("Choose a room group for the selected Portals.")
            return
        }

        let selectedEntries = entries.filter { multiRoomSelectedPortalIDs.contains($0.id) }
        guard !selectedEntries.isEmpty else {
            multiRoomSetupPhase = .failed("Select at least one matched Portal.")
            return
        }

        let total = selectedEntries.count
        multiRoomSetupPhase = .applying(finished: 0, total: total)
        var succeeded = 0
        var appliedClientIDs: [String] = []

        for (index, entry) in selectedEntries.enumerated() {
            defer { multiRoomSetupPhase = .applying(finished: index + 1, total: total) }
            guard let portalID = plan.matches[entry.id],
                  let admissionRequest = request(for: entry) else { continue }

            do {
                _ = try await multiRoomSettingsCoordinator.apply(
                    portalID: entry.id,
                    admissionRequest: admissionRequest,
                    drafts: [
                        SettingsDomainDraft(
                            domainID: "immortal",
                            values: [
                                "multiRoomEnabled": .bool(true),
                                "snapcastHost": .string(snapcastHostInput.trimmingCharacters(in: .whitespaces)),
                            ]
                        )
                    ]
                )
                succeeded += 1
                appliedClientIDs.append(portalID)
            } catch {
                // Per-target failures are reported in the aggregate result and
                // never block configuration of the remaining Portals.
            }
        }

        if !appliedClientIDs.isEmpty {
            await setSnapcastGroup(groupID: multiRoomSelectedGroupID, clients: appliedClientIDs)
        }

        multiRoomSetupPhase = .completed(attempted: total, succeeded: succeeded)
        statusMessage = succeeded == total
            ? "Multi-room setup is complete."
            : "Multi-room setup finished with \(total - succeeded) issue(s)."
    }

    // MARK: Bulk operations

    /// Stages the target set used by the next plan/confirm cycle.
    func stageBulk(targets: [BulkOperationTarget]) {
        pendingBulkTargets = targets
    }

    func planBulk(
        operation: BulkOperationKind,
        values: [String: JSONValue],
        targets: [BulkOperationTarget]
    ) {
        bulkPhase = .planning
        statusMessage = "Planning per-target operations…"

        Task {
            do {
                let summary = try await bulkEngine.preflight(
                    operation: operation,
                    draftValues: values,
                    targets: targets
                )
                pendingBulkConfirmation = BulkConfirmation.acknowledged(summary, at: Date())
                pendingBulkTargets = targets
                withAnimation(.spring(response: 0.45, dampingFraction: 0.88)) {
                    bulkPhase = .awaitingConfirmation(summary)
                }
                statusMessage = summary.confirmationHeadline
            } catch {
                bulkPhase = .blocked(reason: "Planning failed before any request was sent.")
                statusMessage = "Bulk planning failed."
            }
        }
    }

    func confirmBulk() {
        guard case .awaitingConfirmation(let summary) = bulkPhase,
              let confirmation = pendingBulkConfirmation else { return }

        let targets = pendingBulkTargets
        bulkPhase = .running(summary: summary, finished: 0, total: targets.count)
        statusMessage = "Dispatching…"

        Task {
            let report = try? await bulkEngine.run(
                summary: summary,
                confirmation: confirmation,
                targets: targets
            ) { [weak self] event in
                guard let self, case .targetFinished(let result) = event else { return }
                self.bulkResults.append(result)
                if case .running(let s, let finished, let total) = self.bulkPhase {
                    self.bulkPhase = .running(summary: s, finished: finished + 1, total: total)
                }
            }

            withAnimation(.spring(response: 0.5, dampingFraction: 0.9)) {
                if let report {
                    bulkResults = report.results
                    bulkPhase = .completed(report)
                    statusMessage = report.isFullySuccessful
                        ? "All targets confirmed."
                        : "Completed with failures — review per-target results."
                } else {
                    bulkPhase = .blocked(reason: "The run was interrupted.")
                }
            }
            recomputeCommands()
        }
    }

    /// Discards staged planning without dispatching anything.
    func cancelBulkPlanning() {
        pendingBulkConfirmation = nil
        pendingBulkTargets = []
        bulkPhase = .idle
        statusMessage = "Bulk planning cancelled — no request was sent."
    }

    func cancelActiveOperation() async {
        await bulkEngine.cancel()
        statusMessage = "Cancellation requested."
    }

    // MARK: Evidence

    private func refreshEvidence() async {
        guard let report = try? await evidenceCoordinator.report(
            candidateVersion: "1.0.0",
            claimsPortalTVSupport: false,
            enabledMusicMutations: []
        ) else { return }
        evidenceReport = report
    }

    private func copyReleaseReport() async {
        guard let report = evidenceReport else {
            statusMessage = "The release report is not ready yet."
            return
        }

        do {
            let data = try await evidenceCoordinator.reportData(
                candidateVersion: report.candidateVersion,
                claimsPortalTVSupport: false,
                enabledMusicMutations: []
            )
            let text = String(decoding: data, as: UTF8.self)
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            guard pasteboard.setString(text, forType: .string) else {
                throw ManagerError.validation(field: "report", reason: "clipboard unavailable")
            }
            statusMessage = "Sanitized release report copied."
        } catch {
            statusMessage = "The release report could not be copied."
        }
    }

    // MARK: Helpers

    private func upsert(entry: PortalRegistryEntry) {
        if let index = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[index] = entry
        } else {
            entries.append(entry)
        }
        recomputeCommands()
    }

    private func recomputeCommands() {
        let selected = selectedPortalID.flatMap { id in entries.first { $0.id == id } }
        let hasCredential = !(selected?.credentialReferences.isEmpty ?? true)

        commandState = CommandState(
            canRefreshDiscovery: !discoveryRunning,
            canOpenManualEndpoint: true,
            canRefreshStatus: selected != nil && hasCredential,
            canIdentify: selected != nil && hasCredential && selected?.capabilities?.identify == true,
            canReaffirm: selected != nil && hasCredential && selected?.capabilities?.reaffirm == true,
            canApply: selected != nil && hasCredential,
            canRetry: false,
            canRefreshMusic: !musicRefreshing,
            canCancel: isBusy
        )
    }

    private var isBusy: Bool {
        if case .running = bulkPhase { return true }
        return discoveryRunning || musicRefreshing || appsRefreshing || fleetSyncRunning
    }
}

// MARK: - Product control models

enum MediaTransportAction: String, CaseIterable, Sendable {
    case playpause
    case next
    case previous = "previous"

    var requiresActiveSession: Bool { true }
}

enum VolumeDirection: String, Sendable {
    case up
    case down
    case mute
}

struct SyncProgress: Equatable, Sendable {
    var finished: Int
    var total: Int
}

enum FleetTargetOutcome: Equatable, Sendable {
    case success(String)
    case failure(String)

    var title: String {
        switch self {
        case .success(let message): return message
        case .failure(let message): return message
        }
    }
}

struct AppSyncTargetOption: Identifiable, Equatable, Sendable {
    let portalID: PortalID
    let name: String
    let stateTitle: String
    let isEligible: Bool

    var id: PortalID { portalID }
}

enum FleetInstallResultState: Equatable, Sendable {
    case installed
    case pending
    case busy
    case failed(String)
}

enum FleetInstallResponse {
    static func parse(_ payload: JSONValue?) -> FleetInstallResultState? {
        guard case let .object(object) = payload,
              case let .string(result) = object["result"] else {
            return nil
        }
        switch result {
        case "installed":
            return .installed
        case "busy":
            return .busy
        case "timeout", "paused", "failed":
            return .failed(result)
        default:
            return nil
        }
    }
}

enum AppProfileAction: String, CaseIterable, Sendable {
    case install
    case remove
}

struct FleetAppProfile: Identifiable, Equatable, Sendable {
    let packageName: String
    let action: String
    let state: String
    let attempts: Int
    let lastAttemptAtMs: Int?
    let hasApkURL: Bool

    static let installedState = "installed"
    static let failedState = "failed"

    var id: String { packageName }

    var stateTitle: String {
        switch state {
        case "pending": return "Pending"
        case Self.installedState: return "Applied"
        case Self.failedState: return "Failed"
        default: return state.isEmpty ? "Unknown" : state
        }
    }
}

struct FleetAppProfileSnapshot: Equatable, Sendable {
    let profiles: [FleetAppProfile]

    static func parse(_ payload: JSONValue?) -> Self {
        guard case .object(let object) = payload,
              case .array(let rawProfiles)? = object["profiles"] else {
            return Self(profiles: [])
        }

        return Self(
            profiles: rawProfiles.compactMap { raw in
                guard case .object(let profile) = raw,
                      case .string(let packageName)? = profile["packageName"],
                      case .string(let action)? = profile["action"],
                      case .string(let state)? = profile["state"] else {
                    return nil
                }

                var attempts = 0
                if case .number(let value) = profile["attempts"] { attempts = Int(value) }
                var lastAttemptAtMs: Int?
                if case .number(let value) = profile["lastAttemptAtMs"] {
                    lastAttemptAtMs = Int(value)
                }
                var hasApkURL = false
                if case .bool(let value) = profile["hasApkUrl"] { hasApkURL = value }

                return FleetAppProfile(
                    packageName: packageName,
                    action: action,
                    state: state,
                    attempts: attempts,
                    lastAttemptAtMs: lastAttemptAtMs,
                    hasApkURL: hasApkURL
                )
            }.sorted { $0.packageName.localizedCaseInsensitiveCompare($1.packageName) == .orderedAscending }
        )
    }
}

private extension ConnectionState {
    var displayTitle: String {
        switch self {
        case .online: return "Ready"
        case .bearerAuthenticated: return "Verified"
        case .remoteSessionPaired, .remoteSessionReady: return "Session"
        case .offline: return "Offline"
        case .discovered: return "Discovered"
        case .pairingRequired: return "Pairing"
        case .provisioning: return "Provisioning"
        case .reauthenticationRequired: return "Re-auth"
        case .unsupported: return "Unsupported"
        case .error: return "Error"
        default: return "Idle"
        }
    }
}

struct PortalAppSummary: Identifiable, Equatable, Sendable {
    let packageName: String
    let name: String
    let installedVersionCode: Int?
    let catalogVersionCode: Int?

    var id: String { packageName }

    var updateAvailable: Bool {
        guard let catalog = catalogVersionCode, let installed = installedVersionCode else {
            return false
        }
        return catalog > installed
    }

    var stateTitle: String {
        if installedVersionCode == nil { return "Not installed" }
        if updateAvailable { return "Update available" }
        return "Installed"
    }

    static func decode(from payload: JSONValue) -> [PortalAppSummary] {
        guard case .object(let object) = payload,
              case .array(let rawApps)? = object["apps"] else { return [] }

        return rawApps.compactMap { raw in
            guard case .object(let app) = raw,
                  case .string(let packageName) = app["packageName"] else { return nil }

            func integer(_ key: String) -> Int? {
                guard case .number(let value) = app[key] else { return nil }
                return Int(value)
            }
            let name: String
            if case .string(let value) = app["name"] { name = value } else { name = packageName }

            return PortalAppSummary(
                packageName: packageName,
                name: name,
                installedVersionCode: integer("installed"),
                catalogVersionCode: integer("versionCode") ?? integer("catalogVersion")
            )
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
}
