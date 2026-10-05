/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import XCTest
@testable import PortalManager

/// Validation for the bulk operation, release-evidence, and music capability
/// layers added by tasks 11-13. The tests are deterministic: injected fakes
/// only, no network, no Keychain, no process execution.
final class EngineLayerTests: XCTestCase {
    // MARK: - Fixtures

    private let portalA = PortalID(rawValue: UUID(uuidString: "11111111-1111-1111-1111-111111111111")!)
    private let portalB = PortalID(rawValue: UUID(uuidString: "22222222-2222-2222-2222-222222222222")!)

    private struct StubReleasePackagingVerifier: ReleasePackagingVerifier {
        let result: Result<Void, ReleasePackagingFailure>

        func verify(_ candidate: ReleaseCandidate) async throws {
            switch result {
            case .success:
                return
            case .failure(let failure):
                throw failure
            }
        }
    }

    private final class ScriptedReleaseProcessRunner: ReleasePackagingProcessRunner, @unchecked Sendable {
        private(set) var recordedExecutables: [String] = []
        private var outcomes: [Int32]

        init(exitCodes: [Int32]) {
            outcomes = exitCodes
        }

        func run(executablePath: String, arguments: [String]) async throws -> Int32 {
            recordedExecutables.append((executablePath as NSString).lastPathComponent)
            return outcomes.isEmpty ? 0 : outcomes.removeFirst()
        }
    }

    private func makeEndpoint(portalID: PortalID) -> LANEndpoint {
        LANEndpoint(
            hostOrAddress: portalID == self.portalA ? "192.168.1.20" : "192.168.1.21",
            addressFamily: .ipv4,
            source: .manual
        )
    }

    @MainActor
    private func makeEngine(
        registryStore: FakeRegistryStore = FakeRegistryStore()
    ) async -> (engine: BulkOperationEngine, transport: FakeFleetHTTPTransport, registry: PortalRegistryCoordinator) {
        let transport = FakeFleetHTTPTransport()
        let credentialStore = FakeCredentialStore()
        let registryCoordinator = PortalRegistryCoordinator(
            registryStore: registryStore,
            credentialStore: credentialStore
        )
        let admission = ConnectionAdmission(
            dnsResolver: FakeDNSResolver(),
            trustWarningStore: FakeTrustWarningStore()
        )
        let sessionCoordinator = PortalSessionCoordinator(
            fleetClient: FleetHTTPClient(
                transport: transport,
                admission: admission,
                credentialStore: credentialStore
            ),
            registryCoordinator: registryCoordinator,
            credentialStore: credentialStore
        )
        let settingsCoordinator = SettingsCoordinator(sessionCoordinator: sessionCoordinator)
        let engine = BulkOperationEngine(
            dependencies: .init(
                registryCoordinator: registryCoordinator,
                sessionCoordinator: sessionCoordinator,
                settingsCoordinator: settingsCoordinator,
                clock: FakeManagerClock()
            )
        )
        return (engine, transport, registryCoordinator)
    }

    private func makeSummary(targets: [BulkTargetPreflight], operation: BulkOperationKind = .approvedAction(.identify))
        -> BulkPreflightSummary {
        BulkPreflightSummary(operation: operation, targets: targets)
    }

    private func preflight(
        id: PortalID,
        eligible: Bool,
        reducedValues: [String: JSONValue] = [:]
    ) -> BulkTargetPreflight {
        BulkTargetPreflight(
            portalID: id,
            displayName: "Portal",
            connectionState: eligible ? .online(lastRefresh: Date(), latencyMs: 5) : nil,
            credentialScope: eligible ? .verifiedBearer : nil,
            capabilityAvailable: eligible,
            policyClassified: true,
            schemaConstraints: [],
            omittedFields: [],
            reducedValues: reducedValues,
            affectsSensitiveValue: false,
            ineligibilityReasons: eligible ? [] : ["The Portal has not completed authentication."]
        )
    }

    // MARK: Bulk confirmation gate (Property 16 support)

    func testBulkDispatchIsBlockedWithoutExplicitConfirmation() async throws {
        let context = await makeEngine()
        let summary = makeSummary(targets: [preflight(id: portalA, eligible: true)])
        let targets = [BulkOperationTarget(portalID: portalA, admissionRequest: .selfPortal)]

        let report = try await context.engine.run(
            summary: summary,
            confirmation: nil,
            targets: targets
        )

        XCTAssertTrue(report.results.isEmpty, "no request may be emitted without confirmation")
        XCTAssertEqual(context.transport.requestCount, 0)
    }

    func testStaleConfirmationBlocksDispatchAndEmitsNoRequest() async throws {
        let context = await makeEngine()
        let original = makeSummary(targets: [preflight(id: portalA, eligible: true)])
        let mutated = makeSummary(
            targets: [preflight(id: portalA, eligible: true), preflight(id: portalB, eligible: true)]
        )
        let confirmation = BulkConfirmation.acknowledged(mutated, at: Date())

        let report = try await context.engine.run(
            summary: original,
            confirmation: confirmation,
            targets: []
        )

        XCTAssertTrue(report.results.isEmpty)
        XCTAssertEqual(context.transport.requestCount, 0)
    }

    func testMatchingConfirmationAllowsTheGate() {
        let gate = BulkConfirmationGate()
        let summary = makeSummary(targets: [preflight(id: portalA, eligible: true)])

        XCTAssertEqual(gate.evaluate(summary: summary, confirmation: nil), .blockedMissingConfirmation)

        let stale = makeSummary(targets: [])
        XCTAssertEqual(
            gate.evaluate(summary: summary, confirmation: BulkConfirmation.acknowledged(stale, at: Date())),
            .blockedStaleConfirmation
        )

        XCTAssertEqual(
            gate.evaluate(summary: summary, confirmation: BulkConfirmation.acknowledged(summary, at: Date())),
            .dispatchAllowed
        )
    }

    // MARK: Target-specific preflight (Property 16)

    @MainActor
    func testPreflightMarksUnknownTargetsIneligibleWithoutGuessingCredentials() async throws {
        let context = await makeEngine()
        let targets = [
            BulkOperationTarget(portalID: portalA, admissionRequest: .selfPortal),
            BulkOperationTarget(portalID: portalB, admissionRequest: .selfPortal),
        ]

        let summary = try await context.engine.preflight(
            operation: .approvedAction(.identify),
            draftValues: [:],
            targets: targets
        )

        XCTAssertTrue(summary.targets.allSatisfy { !$0.isEligible })
        XCTAssertTrue(summary.targets.allSatisfy { $0.credentialScope == nil })
        XCTAssertFalse(summary.affectsSensitiveValue)
        XCTAssertNil(summary.sensitiveDomainName)
        XCTAssertEqual(summary.eligibleCount, 0)
        XCTAssertEqual(summary.ineligibleCount, 2)
    }

    func testSensitiveOperationsSurfaceAffectedDomainInConfirmationCopy() {
        let operation = BulkOperationKind.settingsApply(domainID: KnownSettingsDomain.immortal.rawValue)
        let summary = makeSummary(
            targets: [preflight(id: portalA, eligible: true)],
            operation: operation
        )

        XCTAssertEqual(operation.affectedSensitiveDomain, KnownSettingsDomain.immortal.rawValue)
        XCTAssertEqual(summary.sensitiveDomainName, KnownSettingsDomain.immortal.rawValue)
        XCTAssertTrue(summary.affectsSensitiveValue)
        XCTAssertTrue(summary.confirmationDetail.contains {
            $0.lowercased().contains("sensitive value")
        })
        XCTAssertTrue(summary.confirmationHeadline.contains("settings.apply.immortal"))
    }

    // MARK: Truthful aggregation (Property 17 support)

    func testAggregateCountsAreTruthfulAndNeverClaimFleetSuccessFromPartialResults() {
        let results: [BulkTargetResult] = [
            .init(portalID: portalA, outcome: .success),
            .init(portalID: portalB, outcome: .failure, error: ManagerError.authentication(.revokedCredential)),
            .init(portalID: PortalID(), outcome: .partial, appliedKeys: ["a"]),
            .init(portalID: PortalID(), outcome: .skipped),
            .init(portalID: PortalID(), outcome: .cancelled),
        ]
        let report = BulkOperationReport(operation: .approvedAction(.identify), results: results)

        XCTAssertEqual(report.successCount, 1)
        XCTAssertEqual(report.partialCount, 1)
        XCTAssertEqual(report.failureCount, 1)
        XCTAssertEqual(report.skippedCount, 1)
        XCTAssertEqual(report.cancelledCount, 1)
        XCTAssertFalse(report.isFullySuccessful)
        XCTAssertTrue(report.isPartiallyFailed)
    }

    func testFullSuccessRequiresEveryTargetToSucceed() {
        let single = BulkOperationReport(
            operation: .approvedAction(.reaffirm),
            results: [.init(portalID: portalA, outcome: .success)]
        )
        XCTAssertTrue(single.isFullySuccessful)

        let empty = BulkOperationReport(operation: .approvedAction(.reaffirm), results: [])
        XCTAssertFalse(empty.isFullySuccessful, "an empty run never claims fleet-wide success")
    }

    // MARK: Cancellation boundary

    func testCancellationSkipsNotYetStartedTargets() async throws {
        let context = await makeEngine()
        await context.engine.cancel()

        let targets = [
            BulkOperationTarget(portalID: portalA, admissionRequest: .selfPortal),
            BulkOperationTarget(portalID: portalB, admissionRequest: .selfPortal),
        ]
        let summary = makeSummary(targets: [preflight(id: portalA, eligible: true), preflight(id: portalB, eligible: true)])
        let confirmation = BulkConfirmation.acknowledged(summary, at: Date())

        let report = try await context.engine.run(
            summary: summary,
            confirmation: confirmation,
            targets: targets
        )

        XCTAssertEqual(report.cancelledCount, 2)
        XCTAssertEqual(context.transport.requestCount, 0)
    }

    // MARK: Release evidence withholding (Property 24)

    func testMissingMandatoryGateWithholdsClaims() {
        let evaluator = ReleaseGateEvaluator()
        let report = evaluator.evaluate(
            candidateVersion: "1.0.0",
            records: [],
            claimsPortalTVSupport: false
        )

        XCTAssertTrue(report.publishableClaims.isEmpty, "missing gates must publish nothing")
        XCTAssertEqual(report.withheldClaims.count, evaluator.mandatoryGates.count)
        for gate in evaluator.mandatoryGates {
            XCTAssertEqual(evaluator.status(of: gate, in: []), .missing)
        }
    }

    func testEveryGateCombinationSeparatesPublishableFromWithheld() {
        let evaluator = ReleaseGateEvaluator()
        let statuses: [GateStatus] = [.missing, .pending, .passed, .failed, .withheld]

        for securityStatus in statuses {
            var records: [ReleaseEvidenceRecord] = []
            if securityStatus != .missing {
                records.append(Self.record(gate: .security, status: securityStatus))
            }
            let report = evaluator.evaluate(
                candidateVersion: "1.0.0",
                records: records,
                claimsPortalTVSupport: false
            )

            if securityStatus != .passed {
                XCTAssertFalse(
                    report.publishableClaims.isEmpty == false && report.withheldClaims.isEmpty,
                    "non-passing security gate must withhold claims"
                )
                XCTAssertTrue(report.withheldClaims.contains {
                    $0.lowercased().contains("security")
                })
            }
        }
    }

    func testPassedCoreGatesPublishCoreClaimsWithoutPortalTV() {
        let evaluator = ReleaseGateEvaluator()
        let records = evaluator.mandatoryGates.map {
            Self.record(gate: $0, status: .passed)
        }

        let withoutTV = evaluator.evaluate(
            candidateVersion: "1.0.0",
            records: records,
            claimsPortalTVSupport: false
        )
        XCTAssertEqual(withoutTV.publishableClaims.count, ReleaseClaim.allCases.count)
        XCTAssertTrue(withoutTV.withheldClaims.isEmpty)
        XCTAssertEqual(withoutTV.statusByGate["security"], GateStatus.passed.rawValue)

        let withTVMissing = evaluator.evaluate(
            candidateVersion: "1.0.0",
            records: records,
            claimsPortalTVSupport: true
        )
        XCTAssertTrue(withTVMissing.publishableClaims.isEmpty, "claiming TV requires the TV gate")
        XCTAssertTrue(withTVMissing.withheldClaims.contains { $0.contains("Portal TV") })

        var withTVPassed = records
        withTVPassed.append(Self.record(gate: .portalTV, status: .passed))
        let tvOK = evaluator.evaluate(
            candidateVersion: "1.0.0",
            records: withTVPassed,
            claimsPortalTVSupport: true
        )
        XCTAssertTrue(tvOK.publishableClaims.count == ReleaseClaim.allCases.count)
        XCTAssertTrue(tvOK.withheldClaims.isEmpty)
    }

    func testFailedGateWithholdsOnlyAffectedClaimsButNeverEnablesFallback() {
        let evaluator = ReleaseGateEvaluator()
        let records = [
            Self.record(gate: .security, status: .passed),
            Self.record(gate: .lan, status: .failed),
        ]

        let report = evaluator.evaluate(
            candidateVersion: "1.0.0",
            records: records,
            claimsPortalTVSupport: false
        )

        XCTAssertTrue(report.publishableClaims.isEmpty)
        XCTAssertTrue(report.withheldClaims.contains { $0.lowercased().contains("lan") })
        XCTAssertEqual(evaluator.status(of: .lan, in: records), .failed)
    }

    func testPackagingCoordinatorRecordsSanitizedEvidenceOnlyAfterSuccessfulVerification() async throws {
        let store = InMemoryReleaseEvidenceStore()
        let verifier = StubReleasePackagingVerifier(result: .success(()))
        let coordinator = ReleaseEvidenceCoordinator(
            store: store,
            clock: FakeManagerClock(),
            packagingVerifier: verifier
        )

        try await coordinator.recordPackagingGate(
            appPath: "/tmp/PortalManager.app",
            notarizationTicketPath: "/tmp/notarization.json"
        )

        let records = try await store.records(candidateVersion: "1.0.0")
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.gateID, .packaging)
        XCTAssertEqual(records.first?.status, .passed)
        XCTAssertEqual(records.first?.evidenceIDs, ["codesign.strict", "notarization.ticket"])
    }

    func testPackagingCoordinatorDoesNotRecordEvidenceWhenVerificationFails() async {
        let store = InMemoryReleaseEvidenceStore()
        let coordinator = ReleaseEvidenceCoordinator(
            store: store,
            clock: FakeManagerClock(),
            packagingVerifier: StubReleasePackagingVerifier(
                result: .failure(ReleasePackagingFailure.ticketMissingOrInvalid)
            )
        )

        do {
            try await coordinator.recordPackagingGate(
                appPath: "/tmp/PortalManager.app",
                notarizationTicketPath: "/tmp/notarization.json"
            )
            XCTFail("Verification failure must propagate.")
        } catch {
            XCTAssertEqual(
                error as? ReleasePackagingFailure,
                .ticketMissingOrInvalid
            )
        }

        let records = try? await store.records(candidateVersion: "1.0.0")
        XCTAssertEqual(records, [])
    }

    func testReleaseReportDataIsMachineReadableAndTruthfulAboutMissingGates() async throws {
        let store = InMemoryReleaseEvidenceStore()
        let coordinator = ReleaseEvidenceCoordinator(
            store: store,
            clock: FakeManagerClock(),
            packagingVerifier: StubReleasePackagingVerifier(result: .success(()))
        )

        do {
            let data = try await coordinator.reportData(
                candidateVersion: "1.0.0",
                claimsPortalTVSupport: false,
                enabledMusicMutations: []
            )
            let report = try Self.decoder.decode(ReleaseGateReport.self, from: data)
            XCTAssertTrue(report.publishableClaims.isEmpty)
            XCTAssertEqual(report.withheldClaims.count, ReleaseGateEvaluator().mandatoryGates.count)
        } catch {
            XCTFail("A missing-gate report must still export safely: \(error)")
        }

        for gateID in ReleaseGateEvaluator().mandatoryGates {
            try await coordinator.record(Self.record(gate: gateID, status: .passed))
        }

        let data = try await coordinator.reportData(
            candidateVersion: "1.0.0",
            claimsPortalTVSupport: false,
            enabledMusicMutations: []
        )
        let report = try Self.decoder.decode(ReleaseGateReport.self, from: data)
        XCTAssertEqual(report.publishableClaims.count, ReleaseClaim.allCases.count)
        XCTAssertTrue(report.withheldClaims.isEmpty)
        XCTAssertEqual(report.statusByGate["packaging"], GateStatus.passed.rawValue)
    }

    func testPackagingGateIsMandatoryForEveryReleaseClaim() {
        let evaluator = ReleaseGateEvaluator()
        XCTAssertTrue(evaluator.mandatoryGates.contains(.packaging))

        let report = evaluator.evaluate(
            candidateVersion: "1.0.0",
            records: [],
            claimsPortalTVSupport: false
        )
        XCTAssertEqual(evaluator.status(of: .packaging, in: []), .missing)
        XCTAssertTrue(report.withheldClaims.contains { $0.contains("Signed and Notarized Packaging") })
    }

    func testSystemPackagingVerifierRejectsTheWrongBundleBeforeAnyProcess() async throws {
        let appURL = try Self.makeFixtureApp(bundleIdentifier: "com.example.wrong")
        defer { try? FileManager.default.removeItem(at: appURL) }
        let runner = ScriptedReleaseProcessRunner(exitCodes: [])
        let verifier = SystemReleasePackagingVerifier(processRunner: runner)

        do {
            try await verifier.verify(Self.candidate(at: appURL))
        } catch let failure as ReleasePackagingFailure {
            XCTAssertEqual(failure, .bundleIdentifierMismatch)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertTrue(runner.recordedExecutables.isEmpty)
    }

    func testSystemPackagingVerifierRunsIdentitySignatureGatekeeperAndStapler() async throws {
        let appURL = try Self.makeFixtureApp(bundleIdentifier: "com.starbrightlab.portalmanager")
        defer { try? FileManager.default.removeItem(at: appURL) }
        let runner = ScriptedReleaseProcessRunner(exitCodes: [0, 0, 0])
        let verifier = SystemReleasePackagingVerifier(processRunner: runner)

        try await verifier.verify(Self.candidate(at: appURL))

        XCTAssertEqual(runner.recordedExecutables, ["codesign", "spctl", "stapler"])
    }

    func testSystemPackagingVerifierFailsClosedWhenGatekeeperRejectsCandidate() async throws {
        let appURL = try Self.makeFixtureApp(bundleIdentifier: "com.starbrightlab.portalmanager")
        defer { try? FileManager.default.removeItem(at: appURL) }
        let runner = ScriptedReleaseProcessRunner(exitCodes: [0, 1, 0])
        let verifier = SystemReleasePackagingVerifier(processRunner: runner)

        do {
            try await verifier.verify(Self.candidate(at: appURL))
            XCTFail("A Gatekeeper rejection must fail the release gate.")
        } catch let failure as ReleasePackagingFailure {
            XCTAssertEqual(failure, .signatureMissing)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(runner.recordedExecutables, ["codesign", "spctl"])
    }

    func testPassedPackagingGateUnblocksCoreClaimsWhenOtherGatesPass() {
        let evaluator = ReleaseGateEvaluator()
        let records = evaluator.mandatoryGates.map {
            Self.record(gate: $0, status: .passed)
        }

        let report = evaluator.evaluate(
            candidateVersion: "1.0.0",
            records: records,
            claimsPortalTVSupport: false
        )

        XCTAssertEqual(report.publishableClaims.count, ReleaseClaim.allCases.count)
        XCTAssertTrue(report.withheldClaims.isEmpty)
        XCTAssertEqual(report.statusByGate["packaging"], GateStatus.passed.rawValue)
    }

    // MARK: Music mutation default-deny (Properties 19/20 surface)

    func testEveryNamedMutationIsDeniedByDefaultWithAnExplicitReason() {
        let resolver = MusicCapabilityResolver()
        let evidence = MusicCapabilityResolver.ServiceEvidence.readOnly("snapcast", version: "0.27")

        for kind in MusicGroupMutationKind.allCases {
            let decision = resolver.decision(for: kind, evidence: evidence)
            XCTAssertFalse(decision.allowed)
            XCTAssertNotNil(decision.denial)
            if case .musicMutation(let service, let operation, _) = decision.gateID {
                XCTAssertEqual(service, "snapcast")
                XCTAssertEqual(operation, kind.rawValue)
            } else {
                XCTFail("the scoped music mutation gate identity is required")
            }
        }
    }

    func testIncompleteEvidenceChainsDenyAtTheFirstMissingCondition() {
        let resolver = MusicCapabilityResolver()

        func decision(_ mutate: (inout MusicCapabilityResolver.ServiceEvidence) -> Void) -> MusicMutationDecision {
            var evidence = MusicCapabilityResolver.ServiceEvidence.readOnly("music-assistant", version: "1.6")
            mutate(&evidence)
            return resolver.decision(for: .rename, evidence: evidence)
        }

        XCTAssertEqual(decision({ $0.hasTypedContract = true }).denial?.reason, .unknownServiceVersion)

        XCTAssertEqual(decision({
            $0.hasTypedContract = true
            $0.contractVersion = "1.6"
        }).denial?.reason, .missingFixtures)

        XCTAssertEqual(decision({
            $0.hasTypedContract = true
            $0.contractVersion = "1.6"
            $0.hasSanitizedFixtures = true
        }).denial?.reason, .missingMutationEvidence)

        XCTAssertEqual(decision({
            $0.hasTypedContract = true
            $0.contractVersion = "1.6"
            $0.hasSanitizedFixtures = true
            $0.hasMutationEvidence = true
            $0.hasReadBackVerification = true
        }).denial?.reason, .gateNotPassed)

        // Even a passed gate record cannot enable v1 mutations: no evidence
        // bundle ships, so the final branch stays disabled by construction.
        let enabledLooking = decision({
            $0.hasTypedContract = true
            $0.contractVersion = "1.6"
            $0.hasSanitizedFixtures = true
            $0.hasMutationEvidence = true
            $0.hasReadBackVerification = true
            $0.gateStatus = .passed
        })
        XCTAssertFalse(enabledLooking.allowed)
        XCTAssertEqual(enabledLooking.denial?.reason, .disabledByDefault)
    }

    func testFailedGateDeniesBeforeAnyOtherCondition() {
        let resolver = MusicCapabilityResolver()
        var evidence = MusicCapabilityResolver.ServiceEvidence.readOnly("snapcast", version: nil)
        evidence.gateStatus = .failed
        evidence.hasTypedContract = true

        let decision = resolver.decision(for: .dissolve, evidence: evidence)
        XCTAssertEqual(decision.denial?.reason, .gateNotPassed)
        XCTAssertFalse(decision.allowed)
    }

    // MARK: Reconciliation identifiers (Property 18 support)

    func testEqualDisplayNamesProduceAmbiguousMappingNotSilentFirstMatch() {
        let snapshot = MusicServiceSnapshot.musicAssistant(MusicTopologySnapshot(
            serviceKind: .musicAssistant,
            connectionState: .connectedUnauthenticated,
            players: [
                MAPlayer(playerID: "p1", name: "Kitchen", online: true, groupID: nil, currentMediaTitle: nil),
                MAPlayer(playerID: "p2", name: "Kitchen", online: true, groupID: nil, currentMediaTitle: nil),
                MAPlayer(playerID: "p3", name: "Studio", online: false, groupID: nil, currentMediaTitle: nil),
            ],
            providers: [],
            groups: [],
            serverVersion: "1.6",
            readAt: Date()
        ))

        let mapping = MusicServiceCoordinator.reconcilePortal(
            portalA,
            displayName: "Kitchen",
            against: snapshot
        )

        XCTAssertTrue(mapping.isAmbiguous)
        XCTAssertEqual(mapping.ambiguousCandidateIDs, ["p1", "p2"])
        XCTAssertNil(mapping.matchedPlayerID)
    }

    func testUniqueNameResolvesByIDAndPreservesIdentity() {
        let snapshot = MusicServiceSnapshot.snapcast(SnapcastTopologySnapshot(
            serviceKind: .snapcast,
            connectionState: .authenticated,
            serverName: "audio",
            serverVersion: "0.27",
            streams: [],
            groups: [],
            clients: [
                SnapcastClient_(clientID: "c9", name: "Office", connected: true, groupID: "g1", streamID: "s1"),
            ],
            hosts: ["audio"],
            readAt: Date()
        ))

        let mapping = MusicServiceCoordinator.reconcilePortal(
            portalB,
            displayName: "Office",
            against: snapshot
        )

        XCTAssertTrue(mapping.isResolved)
        XCTAssertEqual(mapping.matchedClientID, "c9")
        XCTAssertFalse(mapping.offline)
    }

    // MARK: Helpers

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()

    private static func candidate(at appURL: URL) -> ReleaseCandidate {
        ReleaseCandidate(
            version: "1.0.0",
            appPath: appURL.path,
            notarizationTicketPath: appURL
                .appendingPathComponent("Contents")
                .appendingPathComponent("notarization.json")
                .path
        )
    }

    private static func makeFixtureApp(bundleIdentifier: String) throws -> URL {
        let unique = UUID().uuidString
        let appURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("PortalManager-release-\(unique).app", isDirectory: true)
        let contentsURL = appURL.appendingPathComponent("Contents", isDirectory: true)
        let macosURL = contentsURL.appendingPathComponent("MacOS", isDirectory: true)
        try FileManager.default.createDirectory(at: macosURL, withIntermediateDirectories: true)

        let info = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>CFBundleIdentifier</key><string>\(bundleIdentifier)</string>
          <key>CFBundleExecutable</key><string>PortalManager</string>
        </dict>
        </plist>
        """
        try Data(info.utf8).write(to: contentsURL.appendingPathComponent("Info.plist"))
        try Data("{\"status\":\"valid\"}".utf8).write(
            to: contentsURL.appendingPathComponent("notarization.json")
        )
        let executableURL = macosURL.appendingPathComponent("PortalManager")
        try Data("#!/bin/sh\n".utf8).write(to: executableURL)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o755],
            ofItemAtPath: executableURL.path
        )
        return appURL
    }

    private static func record(gate: GateID, status: GateStatus) -> ReleaseEvidenceRecord {
        ReleaseEvidenceRecord(
            gateID: gate,
            candidateVersion: "1.0.0",
            evidenceIDs: ["evidence.\(ReleaseGateReport.key(for: gate))"],
            testResults: ["test.\(ReleaseGateReport.key(for: gate))"],
            supportedClaims: status == .passed ? ["claim"] : [],
            unresolvedDeviations: [],
            status: status,
            recordedAt: Date()
        )
    }
}

private extension ConnectionAdmissionRequest {
    /// A loopback target for planning-only paths; real dispatch re-admits.
    static var selfPortal: ConnectionAdmissionRequest {
        (try? ConnectionAdmissionRequest(
            rawEndpoint: "127.0.0.1",
            serviceKind: .portal,
            protocolName: "http"
        )) ?? ConnectionAdmissionRequest(
            endpoint: LANEndpoint(
                hostOrAddress: "127.0.0.1",
                addressFamily: .ipv4,
                source: .manual
            ),
            serviceKind: .portal,
            protocolName: "http"
        )
    }
}
