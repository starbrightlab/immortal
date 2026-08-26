/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import Darwin

/// How the currently tracked helper came to be running.
enum BackgroundServiceOwnership: Equatable, Sendable {
    case adopted
    case launched
}

/// Consumer-facing lifecycle state for the local helper service.
enum BackgroundServiceLifecycleState: Equatable, Sendable {
    case stopped
    case starting
    case running
    case stopping
    case failed(String)
}

/// Non-secret process identity retained only while the helper is tracked here.
struct BackgroundServiceProcessIdentity: Equatable, Sendable {
    let processID: Int32
    let ownership: BackgroundServiceOwnership
    let serviceVersion: String?
    let startedAt: Date?
    let executablePath: String?
}

/// The bounded set of health facts Portal Manager needs for lifecycle decisions.
struct BackgroundServiceHealthSnapshot: Equatable, Sendable {
    let processID: Int32
    let version: String?
    let startedAtUnixMs: Int64?

    var isCompatible: Bool {
        version == BackgroundServiceController.supportedServiceVersion
            && startedAtUnixMs.map { $0 >= 0 } == true
    }

    var startedAt: Date? {
        startedAtUnixMs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1_000) }
    }
}

enum BackgroundServiceError: Error, Equatable, Sendable {
    case executableUnavailable
    case invalidLifecycleState
    case launchFailed
    case startupTimedOut
    case healthCheckFailed
    case stopFailed

    var sanitizedMessage: String {
        switch self {
        case .executableUnavailable:
            return "The background service program is missing."
        case .invalidLifecycleState:
            return "That action is not available right now."
        case .launchFailed:
            return "The background service could not start."
        case .startupTimedOut:
            return "The background service did not become ready."
        case .healthCheckFailed:
            return "The background service is not responding."
        case .stopFailed:
            return "The background service did not close cleanly."
        }
    }
}

private struct SystemBackgroundServiceClock: ManagerClock {
    var now: Date { Date() }

    func sleep(for duration: Duration) async throws {
        try await Task.sleep(for: duration)
    }
}

/// Owns launching and graceful termination so the controller can be tested
/// without creating a real process.
protocol BackgroundServiceProcessLauncher: Sendable {
    func launch(
        executableURL: URL,
        port: UInt16
    ) async throws -> BackgroundServiceProcessIdentity

    func terminate(
        processID: Int32,
        timeout: Duration
    ) async throws
}

/// Checks the helper's exact loopback readiness response.
protocol BackgroundServiceHealthChecker: Sendable {
    func check(
        port: UInt16,
        timeout: Duration
    ) async throws -> BackgroundServiceHealthSnapshot
}

@MainActor
final class BackgroundServiceController: ObservableObject {
    nonisolated static let defaultPort: UInt16 = 1789
    nonisolated static let supportedServiceVersion = "1"

    @Published private(set) var lifecycleState: BackgroundServiceLifecycleState = .stopped
    @Published private(set) var processIdentity: BackgroundServiceProcessIdentity?
    @Published private(set) var lastHealthCheckAt: Date?

    private let executableURL: URL?
    private let launcher: any BackgroundServiceProcessLauncher
    private let healthChecker: any BackgroundServiceHealthChecker
    private let clock: any ManagerClock
    private let port: UInt16
    private let startupTimeout: Duration
    private let healthPollInterval: Duration
    private let shutdownTimeout: Duration
    private let isValidExecutable: @Sendable (URL) -> Bool

    init(
        executableURL: URL? = BackgroundServiceController.bundledExecutableURL(),
        launcher: any BackgroundServiceProcessLauncher = FoundationBackgroundServiceProcessLauncher(),
        healthChecker: any BackgroundServiceHealthChecker = FoundationBackgroundServiceHealthChecker(),
        clock: any ManagerClock = SystemBackgroundServiceClock(),
        port: UInt16 = BackgroundServiceController.defaultPort,
        startupTimeout: Duration = .seconds(5),
        healthPollInterval: Duration = .milliseconds(100),
        shutdownTimeout: Duration = .seconds(3),
        isValidExecutable: @escaping @Sendable (URL) -> Bool = {
            FileManager.default.isExecutableFile(atPath: $0.path)
        }
    ) {
        self.executableURL = executableURL
        self.launcher = launcher
        self.healthChecker = healthChecker
        self.clock = clock
        self.port = port
        self.startupTimeout = startupTimeout
        self.healthPollInterval = healthPollInterval
        self.shutdownTimeout = shutdownTimeout
        self.isValidExecutable = isValidExecutable
    }

    /// Selects the helper from the signed application bundle. The result must
    /// remain inside that bundle and must be a regular executable file.
    nonisolated static func bundledExecutableURL(bundle: Bundle = .main) -> URL? {
        guard let resourceDirectory = bundle.resourceURL,
              let candidate = bundle.url(
                forResource: "portal-manager-background",
                withExtension: nil,
                subdirectory: nil
              ),
              candidate.isFileURL else {
            return nil
        }

        return validatedExecutableURL(candidate, inDirectory: resourceDirectory)
    }

    /// Test seam for validating an already-resolved bundle member.
    nonisolated static func bundledExecutableURL(
        candidate: URL,
        bundle: Bundle
    ) -> URL? {
        guard let resourceDirectory = bundle.resourceURL, candidate.isFileURL else {
            return nil
        }
        return validatedExecutableURL(candidate, inDirectory: resourceDirectory)
    }

    nonisolated private static func validatedExecutableURL(
        _ candidate: URL,
        inDirectory resourceDirectory: URL
    ) -> URL? {
        let resourcePrefix = resourceDirectory.standardizedFileURL.path
        let candidatePath = candidate.standardizedFileURL.path
        guard candidatePath == resourcePrefix || candidatePath.hasPrefix(resourcePrefix + "/") else {
            return nil
        }

        let manager = FileManager.default
        guard manager.isExecutableFile(atPath: candidatePath),
              (try? manager.destinationOfSymbolicLink(atPath: candidatePath)) == nil else {
            return nil
        }

        return candidate
    }

    func start() async {
        guard canStart else {
            fail(with: .invalidLifecycleState)
            return
        }

        lifecycleState = .starting

        do {
            if let existingService = try? await healthChecker.check(
                port: port,
                timeout: healthPollInterval
            ), existingService.isCompatible {
                adopt(existingService)
                return
            }
        }

        guard let executableURL else {
            await recoverFromFailedStart(reporting: .executableUnavailable)
            return
        }

        guard isValidExecutable(executableURL) else {
            await recoverFromFailedStart(reporting: .executableUnavailable)
            return
        }

        do {
            try await launchAndTrack(executableURL)
        } catch is CancellationError {
            await recoverFromFailedStart(reporting: .startupTimedOut)
        } catch {
            let reportedError = error as? BackgroundServiceError ?? .launchFailed
            await recoverFromFailedStart(reporting: reportedError)
        }
    }

    private func adopt(_ health: BackgroundServiceHealthSnapshot) {
        processIdentity = BackgroundServiceProcessIdentity(
            processID: health.processID,
            ownership: .adopted,
            serviceVersion: health.version,
            startedAt: health.startedAt,
            executablePath: nil
        )
        lifecycleState = .running
        lastHealthCheckAt = clock.now
    }

    private func launchAndTrack(_ executableURL: URL) async throws {
        let launchedIdentity = try await launcher.launch(executableURL: executableURL, port: port)
        processIdentity = BackgroundServiceProcessIdentity(
            processID: launchedIdentity.processID,
            ownership: .launched,
            serviceVersion: nil,
            startedAt: launchedIdentity.startedAt,
            executablePath: launchedIdentity.executablePath
        )
        let health = try await waitForReadiness(processID: launchedIdentity.processID)
        guard health.processID == launchedIdentity.processID, health.isCompatible else {
            throw BackgroundServiceError.healthCheckFailed
        }

        processIdentity = BackgroundServiceProcessIdentity(
            processID: launchedIdentity.processID,
            ownership: .launched,
            serviceVersion: health.version,
            startedAt: health.startedAt ?? launchedIdentity.startedAt,
            executablePath: launchedIdentity.executablePath
        )
        lifecycleState = .running
        lastHealthCheckAt = clock.now
    }

    func stop() async {
        guard canStop else {
            fail(with: .invalidLifecycleState)
            return
        }
        guard let identity = processIdentity else {
            lifecycleState = .stopped
            return
        }

        lifecycleState = .stopping
        do {
            try await launcher.terminate(processID: identity.processID, timeout: shutdownTimeout)
            processIdentity = nil
            lifecycleState = .stopped
        } catch {
            lifecycleState = .failed(BackgroundServiceError.stopFailed.sanitizedMessage)
        }
    }

    func restart() async {
        guard lifecycleState != .starting && lifecycleState != .stopping else {
            fail(with: .invalidLifecycleState)
            return
        }

        if processIdentity != nil {
            await stop()
            guard lifecycleState == .stopped else { return }
        }

        await start()
    }

    /// Reconciles the advertised running state with the helper's loopback
    /// health contract. The services screen calls this periodically so a crash,
    /// port takeover, or stale adopted process cannot remain reported as ready.
    func refreshHealth() async {
        guard case .running = lifecycleState, let identity = processIdentity else {
            return
        }

        do {
            let health = try await healthChecker.check(
                port: port,
                timeout: healthPollInterval
            )

            // A stop or restart may have completed while health I/O was active;
            // never apply a stale observation over newer lifecycle ownership.
            guard case .running = lifecycleState,
                  processIdentity == identity else {
                return
            }
            guard health.processID == identity.processID, health.isCompatible else {
                throw BackgroundServiceError.healthCheckFailed
            }

            lastHealthCheckAt = clock.now
        } catch {
            guard case .running = lifecycleState, processIdentity == identity else {
                return
            }
            await recoverUnhealthy(identity)
        }
    }

    private func recoverUnhealthy(
        _ identity: BackgroundServiceProcessIdentity
    ) async {
        lifecycleState = .stopping
        do {
            try await launcher.terminate(
                processID: identity.processID,
                timeout: shutdownTimeout
            )
        } catch {
            fail(with: .stopFailed)
            return
        }

        if processIdentity == identity {
            processIdentity = nil
        }
        lastHealthCheckAt = nil
        lifecycleState = .failed(BackgroundServiceError.healthCheckFailed.sanitizedMessage)
    }

    private var canStart: Bool {
        switch lifecycleState {
        case .stopped, .failed:
            return processIdentity == nil
        case .starting, .stopping:
            return false
        case .running:
            return false
        }
    }

    private var canStop: Bool {
        switch lifecycleState {
        case .running, .failed:
            return true
        case .stopped, .starting, .stopping:
            return false
        }
    }

    private func waitForReadiness(processID: Int32) async throws -> BackgroundServiceHealthSnapshot {
        let deadline = clock.now.addingTimeInterval(timeInterval(from: startupTimeout))

        repeat {
            do {
                return try await healthChecker.check(
                    port: port,
                    timeout: healthPollInterval
                )
            } catch {
                if clock.now >= deadline {
                    throw BackgroundServiceError.startupTimedOut
                }
                try await clock.sleep(for: healthPollInterval)
            }
        } while clock.now < deadline

        throw BackgroundServiceError.startupTimedOut
    }

    private func recoverFromFailedStart(
        reporting failure: BackgroundServiceError = .launchFailed
    ) async {
        var cleanupFailure: BackgroundServiceError?
        if let identity = processIdentity {
            do {
                try await launcher.terminate(
                    processID: identity.processID,
                    timeout: shutdownTimeout
                )
            } catch {
                cleanupFailure = .stopFailed
            }

            if cleanupFailure == nil {
                processIdentity = nil
            }
        }

        fail(with: cleanupFailure ?? failure)
    }

    private func fail(with error: BackgroundServiceError) {
        lifecycleState = .failed(error.sanitizedMessage)
    }

    private func timeInterval(from duration: Duration) -> TimeInterval {
        let components = duration.components
        return TimeInterval(components.seconds) + TimeInterval(components.attoseconds) / 1_000_000_000_000_000_000
    }
}

/// Direct Foundation process execution. No shell is created and the helper is
/// launched only from its validated application-bundle location.
actor FoundationBackgroundServiceProcessLauncher: BackgroundServiceProcessLauncher {
    private var processes: [Int32: Process] = [:]

    func launch(
        executableURL: URL,
        port: UInt16
    ) async throws -> BackgroundServiceProcessIdentity {
        guard FileManager.default.isExecutableFile(atPath: executableURL.path) else {
            throw BackgroundServiceError.executableUnavailable
        }

        let process = Process()
        process.executableURL = executableURL
        process.arguments = ["--port", String(port)]

        do {
            try process.run()
        } catch {
            throw BackgroundServiceError.launchFailed
        }

        let processID = process.processIdentifier
        processes[processID] = process
        return BackgroundServiceProcessIdentity(
            processID: processID,
            ownership: .launched,
            serviceVersion: nil,
            startedAt: Date(),
            executablePath: executableURL.path,
        )
    }

    func terminate(
        processID: Int32,
        timeout: Duration
    ) async throws {
        if let process = processes[processID] {
            if process.isRunning {
                process.terminate()
            }

            let finishedBeforeDeadline = await waitForExit(process, timeout: timeout)
            guard finishedBeforeDeadline else {
                throw BackgroundServiceError.stopFailed
            }
        } else {
            try await terminateForeignProcess(processID, timeout: timeout)
        }

        processes[processID] = nil
    }

    private func waitForExit(_ process: Process, timeout: Duration) async -> Bool {
        let nanoseconds = UInt64(durationNanoseconds(timeout))
        let deadline = ContinuousClock.now.advanced(by: .nanoseconds(Int64(clamping: nanoseconds)))

        while process.isRunning {
            if ContinuousClock.now >= deadline {
                return false
            }
            try? await Task.sleep(nanoseconds: min(nanoseconds, 25_000_000))
        }
        return true
    }

    private func durationNanoseconds(_ duration: Duration) -> UInt64 {
        let seconds = UInt64(clamping: duration.components.seconds)
        let attoseconds = duration.components.attoseconds
        let remainder = UInt64(attoseconds / 1_000_000_000)
        let secondNanoseconds: UInt64 = 1_000_000_000
        let (total, overflow) = seconds.multipliedReportingOverflow(by: secondNanoseconds)
        return overflow ? .max : total.addingReportingOverflow(remainder).partialValue
    }

    private func terminateForeignProcess(
        _ processID: Int32,
        timeout: Duration
    ) async throws {
        guard kill(processID, SIGTERM) == 0 else {
            if errno == ESRCH {
                return
            }
            throw BackgroundServiceError.stopFailed
        }

        let finishedBeforeDeadline = await waitForForeignExit(processID, timeout: timeout)
        guard finishedBeforeDeadline else {
            guard kill(processID, SIGKILL) == 0 || errno == ESRCH else {
                throw BackgroundServiceError.stopFailed
            }

            let forcedBeforeDeadline = await waitForForeignExit(
                processID,
                timeout: .milliseconds(500)
            )

            if !forcedBeforeDeadline {
                throw BackgroundServiceError.stopFailed
            }

            return
        }
    }

    private func waitForForeignExit(
        _ processID: Int32,
        timeout: Duration
    ) async -> Bool {
        let nanoseconds = UInt64(clamping: durationNanoseconds(timeout))
        let deadline = ContinuousClock.now.advanced(by: .nanoseconds(Int64(clamping: nanoseconds)))

        while isForeignProcessAlive(processID) {
            if ContinuousClock.now >= deadline {
                return false
            }
            try? await Task.sleep(nanoseconds: min(nanoseconds, 25_000_000))
        }
        return true
    }

    private func isForeignProcessAlive(_ processID: Int32) -> Bool {
        if kill(processID, 0) == 0 {
            return true
        }
        return errno != ESRCH
    }
}

/// Uses Foundation's HTTP client for the one permitted loopback readiness
/// endpoint and validates the complete helper contract before reporting ready.
struct FoundationBackgroundServiceHealthChecker: BackgroundServiceHealthChecker {
    func check(
        port: UInt16,
        timeout: Duration
    ) async throws -> BackgroundServiceHealthSnapshot {
        var components = URLComponents()
        components.scheme = "http"
        components.host = "127.0.0.1"
        components.port = Int(port)
        components.path = "/healthz"
        guard let endpoint = components.url else {
            throw BackgroundServiceError.healthCheckFailed
        }

        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = max(0.05, timeInterval(from: timeout))
        configuration.timeoutIntervalForResource = max(0.05, timeInterval(from: timeout))
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration)

        var request = URLRequest(url: endpoint)
        request.httpMethod = "GET"
        request.cachePolicy = .reloadIgnoringLocalCacheData

        do {
            // Ephemeral sessions are operation-local. Invalidating them here
            // keeps frequent readiness polling from accumulating sessions.
            defer { session.finishTasksAndInvalidate() }
            let (data, response) = try await session.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200,
                  httpResponse.url?.host == "127.0.0.1",
                  httpResponse.url?.port == Int(port),
                  let payload = try? JSONDecoder().decode(HealthPayload.self, from: data),
                  payload.ok,
                  payload.service == "portal-manager-background" else {
                throw BackgroundServiceError.healthCheckFailed
            }

            return BackgroundServiceHealthSnapshot(
                processID: payload.pid,
                version: payload.version,
                startedAtUnixMs: payload.startedAtUnixMs
            )
        } catch {
            throw BackgroundServiceError.healthCheckFailed
        }
    }

    private struct HealthPayload: Decodable {
        let ok: Bool
        let service: String
        let pid: Int32
        let version: String?
        let startedAtUnixMs: Int64?
    }

    private func timeInterval(from duration: Duration) -> TimeInterval {
        TimeInterval(duration.components.seconds)
            + TimeInterval(duration.components.attoseconds) / 1_000_000_000_000_000_000
    }
}
