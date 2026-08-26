/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

#if !CASTING_TYPECHECK_STANDALONE
@testable import PortalManager
#endif

import Foundation
import XCTest

private struct CastingTargetFixture {
    static func airplay(
        id: String = "airplay-living-room",
        name: String = "Living Room"
    ) throws -> CastingTarget {
        try CastingTarget(
            id: CastingTargetID(rawValue: id)!,
            name: name,
            kind: .airplay,
            hostOrAddress: "living-room.local",
            port: 700
        )
    }

    static func duplicateName(
        id: String,
        host: String,
        name: String = "Living Room"
    ) throws -> CastingTarget {
        try CastingTarget(
            id: CastingTargetID(uncheckedRawValue: id),
            name: name,
            kind: .airplay,
            hostOrAddress: host,
            port: 700
        )
    }

    static func chromecast(
        id: String = "chromecast-den",
        name: String = "Den Display",
        address: String = "192.168.1.24"
    ) throws -> CastingTarget {
        try CastingTarget(
            id: CastingTargetID(rawValue: id)!,
            name: name,
            kind: .chromecast,
            hostOrAddress: address,
            port: 8009
        )
    }
}

private final class DeterministicCastingClock: CastingClock, @unchecked Sendable {
    private let lock = NSLock()
    private var currentDate: Date
    private(set) var sleepDeadlines: [Date] = []

    init(now: Date = Date(timeIntervalSince1970: 800)) {
        currentDate = now
    }

    var now: Date {
        lock.lock()
        defer { lock.unlock() }
        return currentDate
    }

    func advance(by interval: TimeInterval) {
        lock.lock()
        defer { lock.unlock() }
        currentDate = currentDate.addingTimeInterval(interval)
    }

    func sleep(until deadline: Date) async throws {
        lock.lock()
        sleepDeadlines.append(deadline)
        currentDate = deadline
        lock.unlock()

        if deadline.timeIntervalSinceNow >= 0 {
            await Task.yield()
        }
    }
}

private final class BoundedCastingDiscoverer: CastingTargetDiscoverer, @unchecked Sendable {
    private let lock = NSLock()
    private var targets: [CastingTarget]
    private let error: Error?
    private(set) var deadlines: [Date] = []

    init(targets: [CastingTarget] = [], error: Error? = nil) {
        self.targets = targets
        self.error = error
    }

    func discover(deadline: Date) async throws -> [CastingTarget] {
        lock.lock()
        deadlines.append(deadline)
        lock.unlock()

        if let error { throw error }
        return targets
    }

    func replaceTargets(_ newTargets: [CastingTarget]) {
        lock.lock()
        defer { lock.unlock() }
        targets = newTargets
    }
}

private final class RecordingCastingController: CastingTargetController, @unchecked Sendable {
    private let connectError: Error?
    private let disconnectError: Error?
    private let playError: Error?
    private let stopError: Error?
    private let lock = NSLock()
    private var _connectedIDs: [CastingTargetID] = []
    private var _disconnectedIDs: [CastingTargetID] = []
    private var _playedRequests: [(CastingTargetID, CastingPlaybackRequest)] = []
    private var _stoppedIDs: [CastingTargetID] = []

    init(
        connectError: Error? = nil,
        disconnectError: Error? = nil,
        playError: Error? = nil,
        stopError: Error? = nil
    ) {
        self.connectError = connectError
        self.disconnectError = disconnectError
        self.playError = playError
        self.stopError = stopError
    }

    var connectedIDs: [CastingTargetID] {
        lock.lock()
        defer { lock.unlock() }
        return _connectedIDs
    }

    var disconnectedIDs: [CastingTargetID] {
        lock.lock()
        defer { lock.unlock() }
        return _disconnectedIDs
    }

    var playedRequests: [(CastingTargetID, CastingPlaybackRequest)] {
        lock.lock()
        defer { lock.unlock() }
        return _playedRequests
    }

    var stoppedIDs: [CastingTargetID] {
        lock.lock()
        defer { lock.unlock() }
        return _stoppedIDs
    }

    func connect(to target: CastingTarget) async throws {
        lock.lock()
        _connectedIDs.append(target.id)
        lock.unlock()
        if let connectError { throw connectError }
    }

    func disconnect(from target: CastingTarget) async throws {
        lock.lock()
        _disconnectedIDs.append(target.id)
        lock.unlock()
        if let disconnectError { throw disconnectError }
    }

    func play(
        _ request: CastingPlaybackRequest,
        on target: CastingTarget
    ) async throws -> CastingPlaybackSnapshot {
        lock.lock()
        _playedRequests.append((target.id, request))
        lock.unlock()
        if let playError { throw playError }
        return CastingPlaybackSnapshot(state: .playing, title: request.title)
    }

    func stop(on target: CastingTarget) async throws -> CastingPlaybackSnapshot {
        lock.lock()
        _stoppedIDs.append(target.id)
        lock.unlock()
        if let stopError { throw stopError }
        return CastingPlaybackSnapshot(state: .stopped)
    }
}

final class CastingCoordinatorTests: XCTestCase {
    func testDiscoveryIsBoundedToFourSecondsAndCallsDiscovererOnce() async throws {
        let clock = DeterministicCastingClock()
        let discoverer = BoundedCastingDiscoverer(
            targets: [try CastingTargetFixture.airplay()]
        )
        let coordinator = CastingCoordinator(
            discoverer: discoverer,
            controller: RecordingCastingController(),
            clock: clock
        )

        let result = try await coordinator.discover(maximumDuration: 30)

        XCTAssertEqual(discoverer.deadlines.count, 1)
        XCTAssertEqual(result.deadline.timeIntervalSince(result.startedAt), 4, accuracy: 0.001)
        XCTAssertFalse(result.timedOut)
        XCTAssertEqual(result.targets.map(\.name), ["Living Room"])
    }

    func testDiscoverySuppressesDuplicateStableIDsBeforeStateUpsert() async throws {
        let coordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [
                try CastingTargetFixture.duplicateName(
                    id: "airplay-living-room",
                    host: "192.168.1.20"
                ),
                try CastingTargetFixture.duplicateName(
                    id: "airplay-living-room",
                    host: "192.168.1.21"
                ),
            ]),
            controller: RecordingCastingController(),
            clock: DeterministicCastingClock()
        )

        let result = try await coordinator.discover(maximumDuration: 4)

        XCTAssertEqual(result.targets.count, 1)
        XCTAssertEqual(result.targets.first?.hostOrAddress, "192.168.1.20")
        let record = await coordinator.target(for: CastingTargetID(uncheckedRawValue: "airplay-living-room"))
        XCTAssertEqual(record?.connectionState, .disconnected)
    }

    func testDiscoveryReturnsTimeoutWhenProviderExceedsDeadline() async throws {
        struct UnboundedDiscoverer: CastingTargetDiscoverer {
            func discover(deadline: Date) async throws -> [CastingTarget] {
                while !Task.isCancelled {
                    await Task.yield()
                }
                throw CancellationError()
            }
        }
        let clock = DeterministicCastingClock()
        let coordinator = CastingCoordinator(
            discoverer: UnboundedDiscoverer(),
            controller: RecordingCastingController(),
            clock: clock
        )

        let result = try await coordinator.discover(maximumDuration: 30)

        XCTAssertTrue(result.timedOut)
        XCTAssertEqual(result.failureReason, .timeout)
        XCTAssertEqual(result.deadline.timeIntervalSince(result.startedAt), 4, accuracy: 0.001)
    }

    func testDuplicateStableIDsAreSuppressedWithoutResettingConnectedState() async throws {
        let controller = RecordingCastingController()
        let initial = try CastingTargetFixture.airplay()
        let discoverer = BoundedCastingDiscoverer(targets: [initial])
        let coordinator = CastingCoordinator(
            discoverer: discoverer,
            controller: controller,
            clock: DeterministicCastingClock()
        )
        _ = try await coordinator.discover(maximumDuration: 4)
        _ = try await coordinator.connect(CastingTargetID(uncheckedRawValue: "airplay-living-room"))

        let first = try CastingTargetFixture.airplay(name: "Living Room")
        let duplicate = try CastingTargetFixture.airplay(name: "Living Room Renamed")
        let unrelated = try CastingTargetFixture.chromecast()
        discoverer.replaceTargets([duplicate, unrelated])
        let result = try await coordinator.discover(maximumDuration: 4)

        XCTAssertEqual(controller.connectedIDs.count, 1)
        XCTAssertEqual(result.targets.count, 2)
        XCTAssertEqual(result.targets.filter { $0.id.rawValue == "airplay-living-room" }.first?.name, "Living Room Renamed")
        XCTAssertTrue(result.targets.contains(unrelated))
        let duplicateState = await coordinator.target(for: first.id)
        XCTAssertEqual(duplicateState?.connectionState, .connected)
    }

    func testConnectSuccessUsesExplicitTransition() async throws {
        let controller = RecordingCastingController()
        let target = try CastingTargetFixture.airplay()
        let coordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [target]),
            controller: controller,
            clock: DeterministicCastingClock()
        )
        _ = try await coordinator.discover()

        let state = try await coordinator.connect(target.id)

        XCTAssertEqual(state, .connected)
        XCTAssertEqual(controller.connectedIDs, [target.id])
        let connectedState = await coordinator.target(for: target.id)
        XCTAssertEqual(connectedState?.connectionState, .connected)
    }

    func testConnectFailureSanitizesRawProviderError() async throws {
        struct RawControlError: Error, CustomStringConvertible {
            let description = "connect failed: authorization=secret-token"
        }
        let secret = RawControlError()
        let controller = RecordingCastingController(connectError: secret)
        let target = try CastingTargetFixture.airplay()
        let coordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [target]),
            controller: controller,
            clock: DeterministicCastingClock()
        )
        _ = try await coordinator.discover()

        do {
            _ = try await coordinator.connect(target.id)
            XCTFail("Expected a sanitized casting failure.")
        } catch let error as CastingCoordinatorError {
            XCTAssertEqual(error, .controlFailed(.transport))
            XCTAssertEqual(error.localizedDescription, "Communication with the casting target failed.")
        } catch {
            XCTFail("Unexpected error type: \(error)")
        }

        let failedState = await coordinator.target(for: target.id)
        XCTAssertEqual(failedState?.connectionState, .failed(.transport))
    }

    func testDisconnectCompletesExplicitTransition() async throws {
        let controller = RecordingCastingController()
        let target = try CastingTargetFixture.chromecast(address: "10.20.30.40")
        let coordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [target]),
            controller: controller,
            clock: DeterministicCastingClock()
        )
        _ = try await coordinator.discover()
        _ = try await coordinator.connect(target.id)

        let state = try await coordinator.disconnect(target.id)

        XCTAssertEqual(state, .disconnected)
        XCTAssertEqual(controller.disconnectedIDs, [target.id])
        let disconnectedState = await coordinator.target(for: target.id)
        XCTAssertEqual(disconnectedState?.connectionState, .disconnected)
    }

    func testPublicHostAndInvalidPortAreRejectedBeforeDiscovery() {
        XCTAssertThrowsError(try CastingTarget(
            id: CastingTargetID(uncheckedRawValue: "public"),
            name: "Outside",
            kind: .airplay,
            hostOrAddress: "203.0.113.10"
        )) { error in
            XCTAssertEqual(error as? CastingCoordinatorError, .invalidTarget)
        }
        XCTAssertThrowsError(try CastingTarget(
            id: CastingTargetID(uncheckedRawValue: "bad-port"),
            name: "Bad Port",
            kind: .chromecast,
            hostOrAddress: "192.168.1.20",
            port: 0
        ))
        XCTAssertNil(CastingTargetID(rawValue: "bad\nid"))
    }

    func testDiscoveryProviderErrorsAreSanitized() async throws {
        struct RawDiscoveryError: Error, CustomStringConvertible {
            let description = "scan failed: endpoint=http://192.168.1.9/token=secret"
        }
        let clock = DeterministicCastingClock()
        let discoverer = BoundedCastingDiscoverer(error: RawDiscoveryError())
        let coordinator = CastingCoordinator(
            discoverer: discoverer,
            controller: RecordingCastingController(),
            clock: clock
        )

        let result = try await coordinator.discover()

        XCTAssertEqual(result.failureReason, .unavailable)
        XCTAssertEqual(CastingCoordinatorError.discoveryUnavailable.localizedDescription, "Casting target discovery is unavailable.")
        XCTAssertTrue(result.targets.isEmpty)
    }

    func testPlaybackRequestRejectsPublicAndNonHTTPSLocalSources() throws {
        XCTAssertThrowsError(try playbackRequest(sourceString: "http://192.168.1.8/movie.mp4"))
        XCTAssertThrowsError(try playbackRequest(sourceString: "https://203.0.113.9/movie.mp4"))
        XCTAssertThrowsError(try playbackRequest(
            sourceString: "https://user:secret@192.168.1.8/movie.mp4",
            title: "Private URL"
        ))

        let request = try playbackRequest()
        XCTAssertEqual(request.title, "Kitchen Recording")
        XCTAssertEqual(request.contentType, "video/mp4")
    }

    func testPlayAndStopRequireConnectedTarget() async throws {
        let target = try CastingTargetFixture.chromecast()
        let coordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [target]),
            controller: RecordingCastingController(),
            clock: DeterministicCastingClock()
        )
        _ = try await coordinator.discover()
        let request = try playbackRequest()

        do {
            _ = try await coordinator.play(request, on: target.id)
            XCTFail("Expected play to reject a disconnected target.")
        } catch let error as CastingCoordinatorError {
            XCTAssertEqual(error, .invalidTransition(.disconnected))
        }

        do {
            _ = try await coordinator.stop(on: target.id)
            XCTFail("Expected stop to reject a disconnected target.")
        } catch let error as CastingCoordinatorError {
            XCTAssertEqual(error, .invalidTransition(.disconnected))
        }
    }

    func testExplicitPlayAndStopUseControllerAndExposeSanitizedSnapshots() async throws {
        let target = try CastingTargetFixture.chromecast()
        let controller = RecordingCastingController()
        let coordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [target]),
            controller: controller,
            clock: DeterministicCastingClock()
        )
        _ = try await coordinator.discover()
        _ = try await coordinator.connect(target.id)
        let request = try playbackRequest(title: "Front Door")

        let played = try await coordinator.play(request, on: target.id)
        XCTAssertEqual(played, CastingPlaybackSnapshot(state: .playing, title: "Front Door"))
        XCTAssertEqual(controller.playedRequests.first?.0, target.id)
        XCTAssertEqual(controller.playedRequests.first?.1.source.host, "192.168.1.24")

        let stopped = try await coordinator.stop(on: target.id)
        XCTAssertEqual(stopped.state, .stopped)
        XCTAssertNil(stopped.title)
        XCTAssertEqual(controller.stoppedIDs, [target.id])
    }

    func testAirPlayPlaybackUsesControllerAndExposesSnapshot() async throws {
        let target = try CastingTargetFixture.airplay()
        let coordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [target]),
            controller: RecordingCastingController(),
            clock: DeterministicCastingClock()
        )
        _ = try await coordinator.discover()
        _ = try await coordinator.connect(target.id)

        let played = try await coordinator.play(playbackRequest(), on: target.id)
        XCTAssertEqual(played, CastingPlaybackSnapshot(state: .playing, title: "Kitchen Recording"))

        let state = await coordinator.playback(for: target.id)
        XCTAssertEqual(state?.state, .playing)
    }

    func testCancellationAndRawFailuresMapToClosedReasons() async throws {
        struct RawPlaybackError: Error, CustomStringConvertible {
            let description = "receiver rejected source=https://192.168.1.8/secret"
        }
        let cancelledController = RecordingCastingController(playError: CancellationError())
        let rawController = RecordingCastingController(stopError: RawPlaybackError())
        let target = try CastingTargetFixture.chromecast()
        let cancelledCoordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [target]),
            controller: cancelledController,
            clock: DeterministicCastingClock()
        )
        let rawCoordinator = CastingCoordinator(
            discoverer: BoundedCastingDiscoverer(targets: [target]),
            controller: rawController,
            clock: DeterministicCastingClock()
        )
        _ = try await cancelledCoordinator.discover()
        _ = try await rawCoordinator.discover()
        _ = try await cancelledCoordinator.connect(target.id)
        _ = try await rawCoordinator.connect(target.id)

        do {
            _ = try await cancelledCoordinator.play(playbackRequest(), on: target.id)
            XCTFail("Expected cancellation mapping.")
        } catch let error as CastingCoordinatorError {
            XCTAssertEqual(error, .controlFailed(.cancelled))
        }

        do {
            _ = try await rawCoordinator.stop(on: target.id)
            XCTFail("Expected sanitized failure mapping.")
        } catch let error as CastingCoordinatorError {
            XCTAssertEqual(error, .controlFailed(.transport))
            XCTAssertEqual(error.localizedDescription, "Communication with the casting target failed.")
        }

        let cancelledState = await cancelledCoordinator.playback(for: target.id)
        let rawState = await rawCoordinator.playback(for: target.id)
        XCTAssertEqual(cancelledState?.state, .failed(.cancelled))
        XCTAssertEqual(rawState?.state, .failed(.transport))
    }

    private func playbackRequest(
        sourceString: String = "https://192.168.1.24/media/Kitchen%20Recording.mp4",
        title: String = "Kitchen Recording"
    ) throws -> CastingPlaybackRequest {
        try CastingPlaybackRequest(
            source: URL(string: sourceString)!,
            title: title,
            contentType: "video/mp4"
        )
    }
}
