/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Combine
import Foundation
import SwiftUI
import XCTest
@testable import PortalManager

final class ScriptedBackgroundServiceLauncher: BackgroundServiceProcessLauncher, @unchecked Sendable {
    private let lock = NSLock()
    private var launchResults: [Result<BackgroundServiceProcessIdentity, Error>] = []
    private var terminationResults: [Result<Void, Error>] = []
    private var launchedPorts: [UInt16] = []
    private var terminatedIDs: [Int32] = []
    private var nextProcessID: Int32 = 500

    init(
        launchResults: [Result<BackgroundServiceProcessIdentity, Error>] = [],
        terminationResults: [Result<Void, Error>] = []
    ) {
        self.launchResults = launchResults
        self.terminationResults = terminationResults
    }

    func launch(
        executableURL: URL,
        port: UInt16
    ) async throws -> BackgroundServiceProcessIdentity {
        lock.lock()
        launchedPorts.append(port)
        let result = launchResults.isEmpty
            ? Result<BackgroundServiceProcessIdentity, Error>.success(makeIdentity())
            : launchResults.removeFirst()
        lock.unlock()

        return try result.get()
    }

    func terminate(
        processID: Int32,
        timeout: Duration
    ) async throws {
        lock.lock()
        terminatedIDs.append(processID)
        let result = terminationResults.isEmpty
            ? Result<Void, Error>.success(())
            : terminationResults.removeFirst()
        lock.unlock()

        try result.get()
    }

    func launchCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return launchedPorts.count
    }

    func terminationCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return terminatedIDs.count
    }

    func recordedPorts() -> [UInt16] {
        lock.lock()
        defer { lock.unlock() }
        return launchedPorts
    }

    func recordedTerminatedIDs() -> [Int32] {
        lock.lock()
        defer { lock.unlock() }
        return terminatedIDs
    }

    private func makeIdentity() -> BackgroundServiceProcessIdentity {
        let processID = nextProcessID
        nextProcessID += 1
        return BackgroundServiceProcessIdentity(
            processID: processID,
            ownership: .launched,
            serviceVersion: BackgroundServiceController.supportedServiceVersion,
            startedAt: Date(timeIntervalSince1970: 10),
            executablePath: "/bundled/portal-manager-background",
        )
    }
}

final class ScriptedBackgroundServiceHealthChecker: BackgroundServiceHealthChecker, @unchecked Sendable {
    enum Outcome {
        case compatible(Int32)
        case incompatible(Int32)
        case failure(Error)
    }

    private let lock = NSLock()
    private var outcomes: [Outcome]
    private var checkedPorts: [UInt16] = []
    private var recordedSnapshots: [BackgroundServiceHealthSnapshot] = []

    init(outcomes: [Outcome]) {
        self.outcomes = outcomes
    }

    func check(
        port: UInt16,
        timeout: Duration
    ) async throws -> BackgroundServiceHealthSnapshot {
        lock.lock()
        checkedPorts.append(port)
        let outcome = outcomes.isEmpty
            ? Outcome.failure(BackgroundServiceError.healthCheckFailed)
            : outcomes.removeFirst()
        lock.unlock()

        switch outcome {
        case .compatible(let processID):
            return makeSnapshot(processID: processID, version: "1")
        case .incompatible(let processID):
            return makeSnapshot(processID: processID, version: "different")
        case .failure(let error):
            throw error
        }
    }

    func checkCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return checkedPorts.count
    }

    func snapshots() -> [BackgroundServiceHealthSnapshot] {
        lock.lock()
        defer { lock.unlock() }
        return recordedSnapshots
    }

    private func makeSnapshot(processID: Int32, version: String) -> BackgroundServiceHealthSnapshot {
        let snapshot = BackgroundServiceHealthSnapshot(
            processID: processID,
            version: version,
            startedAtUnixMs: 1_720_000_000_000
        )

        lock.lock()
        recordedSnapshots.append(snapshot)
        lock.unlock()

        return snapshot
    }
}

private final class LifecycleStateRecorder {
    private let lock = NSLock()
    private var values: [BackgroundServiceLifecycleState] = []
    private var cancellation: AnyCancellable?

    func observe(_ publisher: Published<BackgroundServiceLifecycleState>.Publisher) {
        cancellation = publisher.sink { [weak self] value in
            guard let self else { return }
            self.lock.lock()
            self.values.append(value)
            self.lock.unlock()
        }
    }

    func recordedValues() -> [BackgroundServiceLifecycleState] {
        lock.lock()
        defer { lock.unlock() }
        return values
    }
}

final class BackgroundServiceControllerTests: XCTestCase {
    private let fixedExecutable = URL(fileURLWithPath: "/bundled/portal-manager-background")

    func testBundledExecutableResolverRejectsMissingAndOutsideBundlePaths() {
        XCTAssertNil(BackgroundServiceController.bundledExecutableURL(bundle: Bundle(for: Self.self)))

        let outsideBundle = URL(fileURLWithPath: "/tmp/portal-manager-background")
        let bundleURL = BackgroundServiceController.bundledExecutableURL(
            candidate: outsideBundle,
            bundle: Bundle(for: Self.self)
        )
        XCTAssertNil(bundleURL)
    }

    @MainActor
    func testStartTransitionsToRunningAndRecordsSuccessfulHealthContract() async throws {
        let clock = FakeManagerClock(now: Date(timeIntervalSince1970: 100))
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .failure(URLError(.cannotConnectToHost)),
            .compatible(500),
        ])
        let recorder = LifecycleStateRecorder()
        let controller = makeController(
            launcher: launcher,
            checker: checker,
            clock: clock
        )
        recorder.observe(controller.$lifecycleState)

        await controller.start()

        XCTAssertEqual(recorder.recordedValues(), [.stopped, .starting, .running])
        XCTAssertEqual(launcher.launchCount(), 1)
        XCTAssertEqual(launcher.recordedPorts(), [1789])
        XCTAssertEqual(checker.checkCount(), 2)
        XCTAssertEqual(checker.snapshots().map(\.processID), [500])
        XCTAssertEqual(controller.processIdentity?.ownership, .launched)
        XCTAssertEqual(controller.processIdentity?.serviceVersion, "1")
        XCTAssertEqual(controller.processIdentity?.startedAt, Date(timeIntervalSince1970: 1_720_000_000))
        XCTAssertEqual(controller.processIdentity?.executablePath, fixedExecutable.path)
        XCTAssertEqual(controller.lastHealthCheckAt, Date(timeIntervalSince1970: 100))
    }

    @MainActor
    func testStartAdoptsHealthyCompatibleServiceWithoutLaunching() async throws {
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [.compatible(733)])
        let controller = makeController(
            executableURL: nil,
            launcher: launcher,
            checker: checker
        )

        await controller.start()

        XCTAssertEqual(controller.lifecycleState, .running)
        XCTAssertEqual(controller.processIdentity, BackgroundServiceProcessIdentity(
            processID: 733,
            ownership: .adopted,
            serviceVersion: "1",
            startedAt: Date(timeIntervalSince1970: 1_720_000_000),
            executablePath: nil
        ))
        XCTAssertEqual(launcher.launchCount(), 0)
        XCTAssertEqual(launcher.terminationCount(), 0)
        XCTAssertEqual(checker.checkCount(), 1)
    }

    @MainActor
    func testIncompatibleServiceFallsBackToLaunchingCompatibleHelper() async throws {
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .incompatible(910),
            .compatible(500),
        ])
        let controller = makeController(launcher: launcher, checker: checker)

        await controller.start()

        XCTAssertEqual(controller.lifecycleState, .running)
        XCTAssertEqual(controller.processIdentity?.processID, 500)
        XCTAssertEqual(controller.processIdentity?.ownership, .launched)
        XCTAssertEqual(launcher.launchCount(), 1)
        XCTAssertEqual(launcher.recordedPorts(), [1789])
        XCTAssertEqual(checker.checkCount(), 2)
    }

    @MainActor
    func testStopTransitionsThroughStoppingAndClearsProcessIdentity() async throws {
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [.compatible(500)])
        let recorder = LifecycleStateRecorder()
        let controller = makeController(launcher: launcher, checker: checker)
        recorder.observe(controller.$lifecycleState)

        await controller.start()
        await controller.stop()

        XCTAssertEqual(
            recorder.recordedValues(),
            [
                .stopped, .starting, .running, .stopping, .stopped,
            ]
        )
        XCTAssertNil(controller.processIdentity)
        XCTAssertEqual(launcher.terminationCount(), 1)
    }

    @MainActor
    func testStopTerminatesAdoptedProcessID() async throws {
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [.compatible(733)])
        let controller = makeController(
            executableURL: nil,
            launcher: launcher,
            checker: checker
        )

        await controller.start()
        await controller.stop()

        XCTAssertEqual(controller.lifecycleState, .stopped)
        XCTAssertNil(controller.processIdentity)
        XCTAssertEqual(launcher.recordedTerminatedIDs(), [733])
        XCTAssertEqual(launcher.terminationCount(), 1)
    }

    @MainActor
    func testRestartStopsThenStartsAgain() async throws {
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .failure(URLError(.cannotConnectToHost)),
            .compatible(500),
            .failure(URLError(.cannotConnectToHost)),
            .compatible(501),
        ])
        let recorder = LifecycleStateRecorder()
        let controller = makeController(launcher: launcher, checker: checker)
        recorder.observe(controller.$lifecycleState)

        await controller.start()
        XCTAssertTrue(
            controller.processIdentity != nil && controller.lifecycleState == .running,
            "state=\(controller.lifecycleState) identity=\(String(describing: controller.processIdentity))"
        )
        await controller.restart()

        XCTAssertEqual(
            recorder.recordedValues(),
            [
                .stopped, .starting, .running,
                .stopping, .stopped, .starting, .running,
            ]
        )
        XCTAssertEqual(launcher.launchCount(), 2)
        XCTAssertEqual(launcher.terminationCount(), 1)
    }

    @MainActor
    func testInvalidExecutableFailsBeforeAnyProcessIsCreated() async throws {
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [])
        let missing = URL(fileURLWithPath: "/definitely-not-present/portal-manager-background")
        let controller = makeController(
            executableURL: missing,
            launcher: launcher,
            checker: checker
        )

        await controller.start()

        XCTAssertEqual(launcher.launchCount(), 0)
        XCTAssertEqual(checker.checkCount(), 1)
        XCTAssertEqual(
            controller.lifecycleState,
            .failed("The background service program is missing.")
        )
    }

    @MainActor
    func testStartupTimeoutTerminatesHelperAndReportsReadinessFailure() async throws {
        let clock = FakeManagerClock(now: Date(timeIntervalSince1970: 200))
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .failure(URLError(.cannotConnectToHost)),
        ])
        let controller = makeController(
            launcher: launcher,
            checker: checker,
            clock: clock
        )

        await controller.start()

        XCTAssertEqual(checker.checkCount(), 51)
        XCTAssertEqual(launcher.terminationCount(), 1)
        XCTAssertNil(controller.processIdentity)
        XCTAssertEqual(
            controller.lifecycleState,
            .failed("The background service did not become ready.")
        )
    }

    @MainActor
    func testRawLaunchFailuresAreSanitizedWithoutPhantomCleanup() async throws {
        let rawMessage = "secret launch transcript /private/raw/path"
        let launcher = ScriptedBackgroundServiceLauncher(launchResults: [
            .failure(NSError(domain: "test.raw", code: 17, userInfo: [
                NSLocalizedDescriptionKey: rawMessage,
            ])),
        ])
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .failure(URLError(.cannotConnectToHost)),
        ])
        let controller = makeController(launcher: launcher, checker: checker)

        await controller.start()

        XCTAssertEqual(launcher.terminationCount(), 0)
        XCTAssertNil(controller.processIdentity)
        XCTAssertEqual(
            controller.lifecycleState,
            .failed("The background service could not start.")
        )
    }

    @MainActor
    func testFailedShutdownKeepsSanitizedFailureAndBlocksImmediateRestartStart() async throws {
        let launcher = ScriptedBackgroundServiceLauncher(terminationResults: [
            .failure(BackgroundServiceError.stopFailed),
        ])
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .failure(URLError(.cannotConnectToHost)),
            .compatible(500),
        ])
        let controller = makeController(launcher: launcher, checker: checker)

        await controller.start()
        await controller.stop()

        XCTAssertEqual(
            controller.lifecycleState,
            .failed("The background service did not close cleanly.")
        )

        await controller.start()

        XCTAssertEqual(launcher.launchCount(), 1)
    }

    @MainActor
    func testHealthRefreshAcceptsTheTrackedCompatibleService() async throws {
        let clock = FakeManagerClock(now: Date(timeIntervalSince1970: 100))
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .failure(URLError(.cannotConnectToHost)),
            .compatible(500),
            .compatible(500),
        ])
        let controller = makeController(
            launcher: launcher,
            checker: checker,
            clock: clock
        )

        await controller.start()
        guard let startedAt = controller.lastHealthCheckAt else {
            return XCTFail("Expected startup to record a health-check timestamp.")
        }
        clock.advance(by: .milliseconds(200))
        await controller.refreshHealth()

        XCTAssertEqual(controller.lifecycleState, .running)
        XCTAssertEqual(controller.processIdentity?.processID, 500)
        XCTAssertEqual(checker.checkCount(), 3)
        XCTAssertEqual(controller.lastHealthCheckAt, Date(timeIntervalSince1970: 100.2))
        XCTAssertGreaterThan(controller.lastHealthCheckAt!, startedAt)
        XCTAssertEqual(launcher.terminationCount(), 0)
    }

    @MainActor
    func testStaleHealthTerminatesOwnershipAndReportsSanitizedFailure() async throws {
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .failure(URLError(.cannotConnectToHost)),
            .compatible(500),
            .compatible(501),
        ])
        let controller = makeController(launcher: launcher, checker: checker)

        await controller.start()
        await controller.refreshHealth()

        XCTAssertNil(controller.processIdentity)
        XCTAssertNil(controller.lastHealthCheckAt)
        XCTAssertEqual(
            controller.lifecycleState,
            .failed("The background service is not responding.")
        )
        XCTAssertEqual(launcher.recordedTerminatedIDs(), [500])
    }

    @MainActor
    func testRawRefreshFailureReconcilesWithoutExposingTransportDetail() async throws {
        let rawMessage = "secret loopback transcript /private/raw/path"
        let launcher = ScriptedBackgroundServiceLauncher()
        let checker = ScriptedBackgroundServiceHealthChecker(outcomes: [
            .failure(URLError(.cannotConnectToHost)),
            .compatible(500),
            .failure(NSError(domain: "test.raw", code: 42, userInfo: [
                NSLocalizedDescriptionKey: rawMessage,
            ])),
        ])
        let controller = makeController(launcher: launcher, checker: checker)

        await controller.start()
        await controller.refreshHealth()

        XCTAssertNil(controller.processIdentity)
        XCTAssertEqual(
            controller.lifecycleState,
            .failed("The background service is not responding.")
        )
        XCTAssertEqual(launcher.recordedTerminatedIDs(), [500])
    }

    @MainActor
    func testViewCanBeConstructedAndRenderedWithoutAProcess() {
        let binding = Binding<BackgroundServiceViewState>(
            get: {
                BackgroundServiceViewState(
                    isEnabled: true,
                    lifecycleState: .running,
                    lastHealthCheckAt: Date(timeIntervalSince1970: 300)
                )
            },
            set: { _ in }
        )
        var startCount = 0
        var stopCount = 0
        var restartCount = 0

        let view = BackgroundServiceView(
            state: binding,
            onStart: { startCount += 1 },
            onStop: { stopCount += 1 },
            onRestart: { restartCount += 1 }
        )
        let rendered = UIHostIgnoringContainer(content: view.body)

        XCTAssertNotNil(rendered)
        XCTAssertEqual(startCount, 0)
        XCTAssertEqual(stopCount, 0)
        XCTAssertEqual(restartCount, 0)
    }

    @MainActor
    func testViewRendersAdoptedAndLaunchedServiceDetails() {
        let adoptedState = BackgroundServiceViewState(
            isEnabled: true,
            lifecycleState: .running,
            lastHealthCheckAt: Date(timeIntervalSince1970: 300),
            ownership: .adopted,
            serviceVersion: "1",
            serviceStartedAt: Date(timeIntervalSince1970: 1_720_000_000)
        )
        let launchedState = BackgroundServiceViewState(
            isEnabled: true,
            lifecycleState: .running,
            lastHealthCheckAt: Date(timeIntervalSince1970: 300),
            ownership: .launched,
            serviceVersion: "1",
            serviceStartedAt: Date(timeIntervalSince1970: 1_720_000_000)
        )

        let states = [adoptedState, launchedState]
        for state in states {
            var state = state
            let view = BackgroundServiceView(
                state: Binding<BackgroundServiceViewState>(
                    get: { state },
                    set: { state = $0 }
                ),
                onStart: {},
                onStop: {},
                onRestart: {}
            )
            let rendered = UIHostIgnoringContainer(content: view.body)

            XCTAssertNotNil(rendered)
        }
    }

    @MainActor
    private func makeController(
        executableURL: URL? = URL(fileURLWithPath: "/bundled/portal-manager-background"),
        launcher: ScriptedBackgroundServiceLauncher,
        checker: ScriptedBackgroundServiceHealthChecker,
        clock: any ManagerClock = FakeManagerClock(),
        port: UInt16 = 1789
    ) -> BackgroundServiceController {
        let validPaths = Set([fixedExecutable.path])
        return BackgroundServiceController(
            executableURL: executableURL,
            launcher: launcher,
            healthChecker: checker,
            clock: clock,
            port: port,
            startupTimeout: .seconds(5),
            healthPollInterval: .milliseconds(100),
            shutdownTimeout: .seconds(3),
            isValidExecutable: { validPaths.contains($0.path) }
        )
    }
}

/// Keeps the opaque SwiftUI body expression valid without adding AppKit to a
/// unit-test target.
private struct UIHostIgnoringContainer<Content: View>: View {
    let content: Content

    var body: some View {
        content
    }
}
