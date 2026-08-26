/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A single bounded discovery attempt. Implementations must return or throw by
/// `deadline` and must honor task cancellation; the coordinator never retries
/// or loops over this port.
protocol CastingTargetDiscoverer: Sendable {
    func discover(deadline: Date) async throws -> [CastingTarget]
}

/// Typed control boundary for an already admitted LAN-only target. Raw errors
/// are adapter-private and are reduced to `CastingFailureReason` here.
protocol CastingTargetController: Sendable {
    func connect(to target: CastingTarget) async throws
    func disconnect(from target: CastingTarget) async throws
    func play(_ request: CastingPlaybackRequest, on target: CastingTarget) async throws -> CastingPlaybackSnapshot
    func stop(on target: CastingTarget) async throws -> CastingPlaybackSnapshot
}

/// Clock and delay boundary so discovery bounds and state timing are testable
/// without real waits.
protocol CastingClock: Sendable {
    var now: Date { get }
    func sleep(until deadline: Date) async throws
}

struct SystemCastingClock: CastingClock {
    var now: Date { Date() }

    func sleep(until deadline: Date) async throws {
        let interval = deadline.timeIntervalSinceNow
        guard interval > 0 else { return }
        try await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
    }
}

/// The non-secret result of one bounded discovery pass.
struct CastingDiscoveryResult: Sendable, Equatable {
    let targets: [CastingTarget]
    let startedAt: Date
    let endedAt: Date
    let deadline: Date
    let failureReason: CastingFailureReason?

    var timedOut: Bool { failureReason == .timeout }
}

private struct CastingTargetRecord: Sendable, Equatable {
    var target: CastingTarget
    var connectionState: CastingConnectionState
}

private enum CastingDiscoveryOutcome: Sendable {
    case discovered([CastingTarget])
    case cancelled
    case failed
    case timedOut
}

/// Serializes casting-target discovery and explicit connection transitions.
///
/// Discovery is a one-shot call through the injected discoverer with a maximum
/// four-second deadline. Repeated advertisements for the same stable ID update
/// endpoint metadata without duplicating records or resetting active state.
actor CastingCoordinator {
    static let maximumDiscoveryDuration: TimeInterval = 4

    private let discoverer: any CastingTargetDiscoverer
    private let controller: any CastingTargetController
    private let clock: any CastingClock
    private var recordsByID: [CastingTargetID: CastingTargetRecord] = [:]
    private var playbackByTargetID: [CastingTargetID: CastingPlaybackSnapshot] = [:]

    init(
        discoverer: any CastingTargetDiscoverer,
        controller: any CastingTargetController,
        clock: any CastingClock = SystemCastingClock()
    ) {
        self.discoverer = discoverer
        self.controller = controller
        self.clock = clock
    }

    /// Performs exactly one bounded discovery scan. A provider failure becomes
    /// a closed reason; raw transport/provider text is never returned.
    func discover(
        maximumDuration: TimeInterval = CastingCoordinator.maximumDiscoveryDuration
    ) async throws -> CastingDiscoveryResult {
        let startedAt = clock.now
        let clampedDuration = min(
            max(0, maximumDuration),
            Self.maximumDiscoveryDuration
        )
        let deadline = startedAt.addingTimeInterval(clampedDuration)

        let discoverer = self.discoverer
        let clock = self.clock
        let outcome = try await withTaskGroup(
            of: CastingDiscoveryOutcome.self
        ) { group in
            group.addTask {
                do {
                    return .discovered(try await discoverer.discover(deadline: deadline))
                } catch is CancellationError {
                    return .cancelled
                } catch {
                    return .failed
                }
            }
            group.addTask {
                do {
                    try await clock.sleep(until: deadline)
                } catch {
                    return .failed
                }
                return .timedOut
            }

            var first = await group.next() ?? .failed
            if case .timedOut = first {
                group.cancelAll()
                // An immediate provider result may finish just after the clock
                // child. Prefer a concrete result; an unbounded provider that
                // observes cancellation keeps the timeout.
                if let completed = await group.next() {
                    switch completed {
                    case .cancelled:
                        break
                    default:
                        first = completed
                    }
                }
            }
            group.cancelAll()
            return first
        }

        switch outcome {
        case .discovered(let discovered):
            upsert(CastingTargetDiscovererAdapter.unique(discovered))
            if Task.isCancelled { throw CancellationError() }
            return result(startedAt: startedAt, deadline: deadline, reason: nil)
        case .cancelled:
            throw CancellationError()
        case .failed:
            if Task.isCancelled {
                throw CancellationError()
            }
            upsert([])
            return result(
                startedAt: startedAt,
                deadline: deadline,
                reason: .unavailable
            )
        case .timedOut:
            upsert([])
            return result(startedAt: startedAt, deadline: deadline, reason: .timeout)
        }
    }

    func targets() -> [CastingTarget] {
        recordsByID.values
            .map(\.target)
            .sorted { lhs, rhs in
                if lhs.kind != rhs.kind {
                    return lhs.kind.rawValue < rhs.kind.rawValue
                }
                return lhs.id.rawValue < rhs.id.rawValue
            }
    }

    func target(for id: CastingTargetID) -> (target: CastingTarget, connectionState: CastingConnectionState)? {
        guard let record = recordsByID[id] else { return nil }
        return (record.target, record.connectionState)
    }

    @discardableResult
    func connect(_ id: CastingTargetID) async throws -> CastingConnectionState {
        guard let target = targetValue(for: id) else {
            throw CastingCoordinatorError.targetNotFound(id)
        }

        let currentState = recordsByID[id]?.connectionState ?? .disconnected
        switch currentState {
        case .disconnected, .failed:
            break
        case .connecting, .connected, .disconnecting:
            throw CastingCoordinatorError.invalidTransition(currentState)
        }

        recordsByID[id] = CastingTargetRecord(target: target, connectionState: .connecting)
        do {
            try await controller.connect(to: target)
            recordsByID[id] = CastingTargetRecord(target: target, connectionState: .connected)
            playbackByTargetID[id] = .idle
            return .connected
        } catch is CancellationError {
            recordsByID[id] = CastingTargetRecord(
                target: target,
                connectionState: .failed(.cancelled)
            )
            playbackByTargetID[id] = CastingPlaybackSnapshot(state: .failed(.cancelled))
            throw CastingCoordinatorError.controlFailed(.cancelled)
        } catch {
            let reason = sanitizedFailureReason(error, fallback: .transport)
            recordsByID[id] = CastingTargetRecord(
                target: target,
                connectionState: .failed(reason)
            )
            playbackByTargetID[id] = CastingPlaybackSnapshot(state: .failed(reason))
            throw CastingCoordinatorError.controlFailed(reason)
        }
    }

    @discardableResult
    func disconnect(_ id: CastingTargetID) async throws -> CastingConnectionState {
        guard let target = targetValue(for: id) else {
            throw CastingCoordinatorError.targetNotFound(id)
        }

        let currentState = recordsByID[id]?.connectionState ?? .disconnected
        guard currentState == .connected else {
            throw CastingCoordinatorError.invalidTransition(currentState)
        }

        recordsByID[id] = CastingTargetRecord(target: target, connectionState: .disconnecting)
        do {
            try await controller.disconnect(from: target)
            recordsByID[id] = CastingTargetRecord(target: target, connectionState: .disconnected)
            playbackByTargetID[id] = nil
            return .disconnected
        } catch is CancellationError {
            recordsByID[id] = CastingTargetRecord(
                target: target,
                connectionState: .failed(.cancelled)
            )
            playbackByTargetID[id] = CastingPlaybackSnapshot(state: .failed(.cancelled))
            throw CastingCoordinatorError.controlFailed(.cancelled)
        } catch {
            let reason = sanitizedFailureReason(error, fallback: .transport)
            recordsByID[id] = CastingTargetRecord(
                target: target,
                connectionState: .failed(reason)
            )
            playbackByTargetID[id] = CastingPlaybackSnapshot(state: .failed(reason))
            throw CastingCoordinatorError.controlFailed(reason)
        }
    }

    @discardableResult
    func play(
        _ request: CastingPlaybackRequest,
        on id: CastingTargetID
    ) async throws -> CastingPlaybackSnapshot {
        guard let target = targetValue(for: id) else {
            throw CastingCoordinatorError.targetNotFound(id)
        }

        let currentState = recordsByID[id]?.connectionState ?? .disconnected
        guard currentState == .connected else {
            throw CastingCoordinatorError.invalidTransition(currentState)
        }

        let currentPlayback = playbackByTargetID[id]?.state ?? .idle
        guard currentPlayback != .preparing, currentPlayback != .stopping else {
            throw CastingCoordinatorError.invalidTransition(currentState)
        }

        playbackByTargetID[id] = CastingPlaybackSnapshot(state: .preparing, title: request.title)
        do {
            let snapshot = try await controller.play(request, on: target)
            playbackByTargetID[id] = snapshot
            return snapshot
        } catch is CancellationError {
            playbackByTargetID[id] = CastingPlaybackSnapshot(state: .failed(.cancelled))
            throw CastingCoordinatorError.controlFailed(.cancelled)
        } catch {
            let reason = sanitizedFailureReason(error, fallback: .transport)
            playbackByTargetID[id] = CastingPlaybackSnapshot(state: .failed(reason))
            throw CastingCoordinatorError.controlFailed(reason)
        }
    }

    @discardableResult
    func stop(on id: CastingTargetID) async throws -> CastingPlaybackSnapshot {
        guard let target = targetValue(for: id) else {
            throw CastingCoordinatorError.targetNotFound(id)
        }

        let currentState = recordsByID[id]?.connectionState ?? .disconnected
        guard currentState == .connected else {
            throw CastingCoordinatorError.invalidTransition(currentState)
        }

        let currentPlayback = playbackByTargetID[id]?.state ?? .idle
        guard currentPlayback != .stopping else {
            throw CastingCoordinatorError.invalidTransition(currentState)
        }

        playbackByTargetID[id] = CastingPlaybackSnapshot(
            state: .stopping,
            title: playbackByTargetID[id]?.title
        )
        do {
            let snapshot = try await controller.stop(on: target)
            playbackByTargetID[id] = snapshot
            return snapshot
        } catch is CancellationError {
            playbackByTargetID[id] = CastingPlaybackSnapshot(state: .failed(.cancelled))
            throw CastingCoordinatorError.controlFailed(.cancelled)
        } catch {
            let reason = sanitizedFailureReason(error, fallback: .transport)
            playbackByTargetID[id] = CastingPlaybackSnapshot(state: .failed(reason))
            throw CastingCoordinatorError.controlFailed(reason)
        }
    }

    func playback(for id: CastingTargetID) -> CastingPlaybackSnapshot? {
        playbackByTargetID[id]
    }

    private func targetValue(for id: CastingTargetID) -> CastingTarget? {
        recordsByID[id]?.target
    }

    private func upsert(_ discovered: [CastingTarget]) {
        for target in discovered {
            let existingState = recordsByID[target.id]?.connectionState ?? .disconnected
            recordsByID[target.id] = CastingTargetRecord(
                target: target,
                connectionState: existingState
            )
        }
    }

    private func result(
        startedAt: Date,
        deadline: Date,
        reason: CastingFailureReason?
    ) -> CastingDiscoveryResult {
        CastingDiscoveryResult(
            targets: targets(),
            startedAt: startedAt,
            endedAt: clock.now,
            deadline: deadline,
            failureReason: reason
        )
    }

    private func sanitizedFailureReason(_ error: Error, fallback: CastingFailureReason) -> CastingFailureReason {
        guard case let .controlFailed(reason) = error as? CastingCoordinatorError else {
            return fallback
        }
        return reason
    }
}
