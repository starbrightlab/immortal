/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
@testable import PortalManager

// MARK: - Shared deterministic test instrumentation

/// Sanitized dependency-boundary events used to assert access ordering.
///
/// The recorder intentionally stores operation kinds only. It never stores a
/// hostname, URL, header, body, credential value, process argument, or fixture
/// payload.
enum FakeDependencyEvent: String, Equatable, Sendable {
    case dnsResolve
    case trustWarningLookup
    case trustWarningAcknowledge
    case transportSend
    case credentialRead
    case credentialWrite
    case credentialDelete
    case registryLoad
    case registrySave
    case bonjourEventsRequested
    case bonjourStart
    case bonjourStop
    case adbExecute
    case artifactVerify
    case musicConnect
    case musicTopology
    case musicDisconnect
    case snapcastConnect
    case snapcastTopology
    case snapcastDisconnect
    case clockSleep
    case redaction
    case secureInputCreate
    case secureInputClear
    case evidenceAppend
    case evidenceRead
    case fixtureDequeue
}

/// A lock-backed event recorder makes ordering assertions usable from async
/// actors without introducing a second scheduler or a real clock.
final class FakeDependencyEventRecorder: @unchecked Sendable {
    private let lock = FakeLock()
    private var recordedEvents: [FakeDependencyEvent] = []

    func append(_ event: FakeDependencyEvent) {
        lock.lock()
        recordedEvents.append(event)
        lock.unlock()
    }

    func events() -> [FakeDependencyEvent] {
        lock.lock()
        defer { lock.unlock() }
        return recordedEvents
    }

    func reset() {
        lock.lock()
        recordedEvents.removeAll(keepingCapacity: false)
        lock.unlock()
    }

    func count(of event: FakeDependencyEvent) -> Int {
        events().filter { $0 == event }.count
    }

    /// Returns true when the requested event kinds occur in the given order.
    /// Other events may occur between them.
    func occurredInOrder(_ expected: [FakeDependencyEvent]) -> Bool {
        guard !expected.isEmpty else { return true }

        var nextExpectedIndex = 0
        for event in events() where event == expected[nextExpectedIndex] {
            nextExpectedIndex += 1
            if nextExpectedIndex == expected.count {
                return true
            }
        }
        return false
    }
}

final class FakePortalSessionEventSink: PortalSessionEventSink, @unchecked Sendable {
    private let lock = FakeLock()
    private var recordedEvents: [PortalSessionEvent] = []

    func record(_ event: PortalSessionEvent) async {
        lock.lock()
        recordedEvents.append(event)
        lock.unlock()
    }

    var events: [PortalSessionEvent] {
        lock.lock()
        defer { lock.unlock() }
        return recordedEvents
    }
}

enum FakePortError: Error, Equatable, LocalizedError, Sendable {
    case noQueuedResult(DependencyPortKind)
    case configuredFailure(DependencyPortKind)
    case notConnected(DependencyPortKind)
    case clockSleepFailed

    var errorDescription: String? {
        switch self {
        case .noQueuedResult(let kind):
            return "No deterministic result is queued for the \(kind.rawValue) dependency."
        case .configuredFailure(let kind):
            return "The deterministic \(kind.rawValue) dependency is configured to fail."
        case .notConnected(let kind):
            return "The deterministic \(kind.rawValue) client is not connected."
        case .clockSleepFailed:
            return "The deterministic clock sleep failed."
        }
    }
}

/// Marker requests/results keep tests independent from future route, ADB, and
/// artifact domain models. Fixture identifiers are labels only; no request
/// payload or process input is represented here.
struct FakeFleetHTTPRequest: FleetHTTPRequest, Sendable {
    let fixtureID: String

    init(fixtureID: String = "fleet-request") {
        self.fixtureID = fixtureID
    }
}

struct FakeADBRequest: ADBRequest, Sendable {
    let fixtureID: String

    init(fixtureID: String = "adb-request") {
        self.fixtureID = fixtureID
    }
}

struct FakeArtifactVerificationRequest: ArtifactVerificationRequest, Sendable {
    let fixtureID: String

    init(fixtureID: String = "artifact-request") {
        self.fixtureID = fixtureID
    }
}

/// Replay queues retain fixture payloads only in memory. Payloads are returned
/// to the protocol fake as an active test operation and are never included in
/// metadata, logs, or assertions.
final class FakeFixtureReplay: @unchecked Sendable {
    private let lock = FakeLock()
    private var payloadsByIdentifier: [String: [Data]] = [:]
    private let recorder: FakeDependencyEventRecorder?

    init(
        fixtures: [String: [Data]] = [:],
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        payloadsByIdentifier = fixtures.mapValues { $0.map { Data(bytes: $0) } }
        self.recorder = recorder
    }

    func enqueue(_ payload: Data, identifier: String) {
        lock.lock()
        payloadsByIdentifier[identifier, default: []].append(Data(payload))
        lock.unlock()
    }

    /// Dequeues one payload for a fixture identifier. There is deliberately no
    /// accessor for the queue or for all stored payloads.
    func next(identifier: String) -> Data? {
        let payload: Data?
        lock.lock()
        if var values = payloadsByIdentifier[identifier], !values.isEmpty {
            payload = values.removeFirst()
            payloadsByIdentifier[identifier] = values
        } else {
            payload = nil
        }
        lock.unlock()

        if payload != nil {
            recorder?.append(.fixtureDequeue)
        }
        return payload.map { Data(bytes: $0) }
    }

    func remainingCount(for identifier: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return payloadsByIdentifier[identifier]?.count ?? 0
    }

    func reset() {
        lock.lock()
        payloadsByIdentifier.removeAll(keepingCapacity: false)
        lock.unlock()
    }
}

// MARK: - Clock

/// An in-memory wall clock. Sleeping never suspends the test task; it records
/// the duration and advances `now` deterministically instead.
final class FakeManagerClock: ManagerClock, @unchecked Sendable {
    private let lock = FakeLock()
    private var currentDate: Date
    private var recordedSleeps: [Duration] = []
    private var shouldFailSleep = false
    private let recorder: FakeDependencyEventRecorder?

    init(
        now: Date = Date(timeIntervalSince1970: 0),
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        currentDate = now
        self.recorder = recorder
    }

    var now: Date {
        lock.lock()
        defer { lock.unlock() }
        return currentDate
    }

    var sleepDurations: [Duration] {
        lock.lock()
        defer { lock.unlock() }
        return recordedSleeps
    }

    func setNow(_ date: Date) {
        lock.lock()
        currentDate = date
        lock.unlock()
    }

    func advance(by duration: Duration) {
        lock.lock()
        currentDate = currentDate.addingTimeInterval(fakeTimeInterval(duration))
        lock.unlock()
    }

    func setSleepFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailSleep = enabled
        lock.unlock()
    }

    func sleep(for duration: Duration) async throws {
        recorder?.append(.clockSleep)

        lock.lock()
        recordedSleeps.append(duration)
        let failure = shouldFailSleep
        if !failure {
            currentDate = currentDate.addingTimeInterval(fakeTimeInterval(duration))
        }
        lock.unlock()

        if failure {
            throw FakePortError.clockSleepFailed
        }
    }
}

// MARK: - Registry and Keychain

/// An in-memory non-secret registry. It keeps only the already non-secret
/// `RegistrySnapshot` contract and never writes to disk.
final class FakeRegistryStore: RegistryStore, @unchecked Sendable {
    private let lock = FakeLock()
    private var currentSnapshot: RegistrySnapshot
    private var shouldFailLoad = false
    private var shouldFailSave = false
    private var loadOperations = 0
    private var saveOperations = 0
    private let recorder: FakeDependencyEventRecorder?

    init(
        initialSnapshot: RegistrySnapshot = RegistrySnapshot(),
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        currentSnapshot = initialSnapshot
        self.recorder = recorder
    }

    var snapshot: RegistrySnapshot {
        lock.lock()
        defer { lock.unlock() }
        return currentSnapshot
    }

    var loadCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return loadOperations
    }

    var saveCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return saveOperations
    }

    func setLoadFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailLoad = enabled
        lock.unlock()
    }

    func setSaveFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailSave = enabled
        lock.unlock()
    }

    func load() async throws -> RegistrySnapshot {
        recorder?.append(.registryLoad)

        lock.lock()
        loadOperations += 1
        let failure = shouldFailLoad
        let value = currentSnapshot
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.registry)
        }
        return value
    }

    func save(_ snapshot: RegistrySnapshot) async throws {
        recorder?.append(.registrySave)

        lock.lock()
        saveOperations += 1
        let failure = shouldFailSave
        if !failure {
            // RegistrySnapshot is the non-secret persistence contract. The fake
            // copies only that value type and never captures raw protocol data.
            currentSnapshot = snapshot
        }
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.registry)
        }
    }
}

struct FakeCredentialOperationMetadata: Equatable, Sendable {
    enum Operation: String, Equatable, Sendable {
        case read
        case write
        case delete
    }

    let operation: Operation
    let reference: CredentialReference
}

/// An in-memory Keychain substitute. Credential bytes are retained only to
/// satisfy the `CredentialStore` read/write contract and are never returned by
/// inspection APIs, operation metadata, or diagnostics.
final class FakeCredentialStore: CredentialStore, @unchecked Sendable {
    private let lock = FakeLock()
    private var values: [CredentialReference: Data] = [:]
    private var recordedOperations: [FakeCredentialOperationMetadata] = []
    private var shouldFailRead = false
    private var shouldFailWrite = false
    private var shouldFailDelete = false
    private let recorder: FakeDependencyEventRecorder?

    init(
        seededValues: [CredentialReference: Data] = [:],
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        values = seededValues.mapValues { Data(bytes: $0) }
        self.recorder = recorder
    }

    deinit {
        lock.lock()
        wipeAllValues()
        lock.unlock()
    }

    /// Seeds an in-memory value for a test fixture without recording a fake
    /// application operation. The value has no persistence or inspection API.
    func seed(_ value: Data, for reference: CredentialReference) {
        lock.lock()
        replaceValue(Data(value), for: reference)
        lock.unlock()
    }

    func setReadFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailRead = enabled
        lock.unlock()
    }

    func setWriteFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailWrite = enabled
        lock.unlock()
    }

    func setDeleteFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailDelete = enabled
        lock.unlock()
    }

    var operations: [FakeCredentialOperationMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return recordedOperations
    }

    var storedReferences: Set<CredentialReference> {
        lock.lock()
        defer { lock.unlock() }
        return Set(values.keys)
    }

    func hasValue(for reference: CredentialReference) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return values[reference] != nil
    }

    func read(_ reference: CredentialReference) async throws -> Data? {
        recorder?.append(.credentialRead)

        lock.lock()
        recordedOperations.append(
            FakeCredentialOperationMetadata(operation: .read, reference: reference)
        )
        let failure = shouldFailRead
        let value = values[reference].map { Data(bytes: $0) }
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.keychain)
        }
        return value
    }

    func write(_ value: Data, for reference: CredentialReference) async throws {
        recorder?.append(.credentialWrite)

        lock.lock()
        recordedOperations.append(
            FakeCredentialOperationMetadata(operation: .write, reference: reference)
        )
        let failure = shouldFailWrite
        if !failure {
            replaceValue(Data(value), for: reference)
        }
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.keychain)
        }
    }

    func delete(_ reference: CredentialReference) async throws {
        recorder?.append(.credentialDelete)

        lock.lock()
        recordedOperations.append(
            FakeCredentialOperationMetadata(operation: .delete, reference: reference)
        )
        let failure = shouldFailDelete
        if !failure, var value = values.removeValue(forKey: reference) {
            wipe(&value)
        }
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.keychain)
        }
    }

    private func replaceValue(_ value: Data, for reference: CredentialReference) {
        if var previous = values.updateValue(value, forKey: reference) {
            wipe(&previous)
        }
    }

    private func wipeAllValues() {
        for key in values.keys {
            if var value = values.removeValue(forKey: key) {
                wipe(&value)
            }
        }
    }

    private func wipe(_ value: inout Data) {
        guard !value.isEmpty else { return }
        value.resetBytes(in: 0..<value.count)
        value.removeAll(keepingCapacity: false)
    }
}

// MARK: - Trust warnings and LAN/HTTP ports

/// A non-secret in-memory trust acknowledgement store used by admission tests.
final class FakeTrustWarningStore: TrustWarningStore, @unchecked Sendable {
    private let lock = FakeLock()
    private var acknowledgements: [TrustWarningScope: TrustWarningAcknowledgement] = [:]
    private let recorder: FakeDependencyEventRecorder?

    init(recorder: FakeDependencyEventRecorder? = nil) {
        self.recorder = recorder
    }

    var acknowledgedScopes: Set<TrustWarningScope> {
        lock.lock()
        defer { lock.unlock() }
        return Set(acknowledgements.keys)
    }

    func acknowledgement(
        for scope: TrustWarningScope
    ) async throws -> TrustWarningAcknowledgement? {
        recorder?.append(.trustWarningLookup)
        lock.lock()
        defer { lock.unlock() }
        return acknowledgements[scope.normalized]
    }

    func acknowledge(
        _ scope: TrustWarningScope,
        at date: Date
    ) async throws {
        recorder?.append(.trustWarningAcknowledge)
        let normalized = scope.normalized
        lock.lock()
        acknowledgements[normalized] = TrustWarningAcknowledgement(
            scope: normalized,
            acknowledgedAt: date
        )
        lock.unlock()
    }
}

struct FakeAddressSelectionMetadata: Equatable, Sendable {
    let sequenceNumber: Int
    let port: UInt16
}

/// A deterministic address selector for admission tests. It records only the
/// selection count and port; supplied address values remain active fixture data.
final class FakeResolvedAddressSelector: ResolvedAddressSelector, @unchecked Sendable {
    private enum Outcome {
        case success(ResolvedAddress)
        case failure(FakePortError)
    }

    private let lock = FakeLock()
    private var outcomes: [Outcome]
    private var selections: [FakeAddressSelectionMetadata] = []

    init(
        selectedAddresses: [ResolvedAddress] = [],
        failures: [FakePortError] = []
    ) {
        outcomes = selectedAddresses.map(Outcome.success)
            + failures.map(Outcome.failure)
    }

    func enqueue(_ address: ResolvedAddress) {
        lock.lock()
        outcomes.append(.success(address))
        lock.unlock()
    }

    func enqueueFailure(_ error: FakePortError = .configuredFailure(.dns)) {
        lock.lock()
        outcomes.append(.failure(error))
        lock.unlock()
    }

    var selectionMetadata: [FakeAddressSelectionMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return selections
    }

    func select(
        from addresses: [ResolvedAddress],
        port: UInt16
    ) throws -> ResolvedAddress {
        lock.lock()
        selections.append(
            FakeAddressSelectionMetadata(
                sequenceNumber: selections.count,
                port: port
            )
        )
        let outcome = outcomes.isEmpty
            ? .failure(FakePortError.noQueuedResult(.dns))
            : outcomes.removeFirst()
        lock.unlock()

        switch outcome {
        case .success(let address):
            return address
        case .failure(let error):
            throw error
        }
    }
}

struct FakeDNSRequestMetadata: Equatable, Sendable {
    let sequenceNumber: Int
}

/// A queued resolver. Responses and failures are consumed in FIFO order; only
/// request counts are retained as metadata, never hostnames or resolved payloads.
final class FakeDNSResolver: DNSResolver, @unchecked Sendable {
    private enum Outcome {
        case success(DNSResolutionResult)
        case failure(FakePortError)
    }

    private let lock = FakeLock()
    private var outcomes: [Outcome]
    private var requests: [FakeDNSRequestMetadata] = []
    private let recorder: FakeDependencyEventRecorder?

    init(
        responses: [DNSResolutionResult] = [],
        failures: [FakePortError] = [],
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        outcomes = responses.map(Outcome.success)
            + failures.map(Outcome.failure)
        self.recorder = recorder
    }

    func enqueue(_ response: DNSResolutionResult) {
        lock.lock()
        outcomes.append(.success(response))
        lock.unlock()
    }

    func enqueueFailure(_ error: FakePortError = .configuredFailure(.dns)) {
        lock.lock()
        outcomes.append(.failure(error))
        lock.unlock()
    }

    var requestMetadata: [FakeDNSRequestMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }

    var requestCount: Int {
        requestMetadata.count
    }

    func resolve(_ request: DNSResolutionRequest) async throws -> DNSResolutionResult {
        recorder?.append(.dnsResolve)

        lock.lock()
        requests.append(FakeDNSRequestMetadata(sequenceNumber: requests.count))
        let outcome = outcomes.isEmpty
            ? .failure(FakePortError.noQueuedResult(.dns))
            : outcomes.removeFirst()
        lock.unlock()

        switch outcome {
        case .success(let result):
            return result
        case .failure(let error):
            throw error
        }
    }
}

struct FakeTransportRequestMetadata: Equatable, Sendable {
    let sequenceNumber: Int
    let requestTypeName: String
    let responseStatusCode: Int?
    let wasRedirectResponse: Bool
    let route: FleetRoute?
    let method: HTTPMethod?
    let hasCredential: Bool
    let bodyFieldNames: Set<String>
    let bodyIsJSONObject: Bool
}

/// A no-follow HTTP transport fake. It returns queued responses exactly once,
/// records only request type/status metadata, and has no redirect-following API.
final class FakeFleetHTTPTransport: FleetHTTPTransport, @unchecked Sendable {
    private enum Outcome {
        case response(FleetHTTPResponse)
        case failure(FakePortError)
    }

    private let lock = FakeLock()
    private var outcomes: [Outcome]
    private var recordedRequests: [FakeTransportRequestMetadata] = []
    private var redirectSeen = false
    private var requestsAfterRedirect = 0
    private let recorder: FakeDependencyEventRecorder?

    init(
        responses: [FleetHTTPResponse] = [],
        failures: [FakePortError] = [],
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        outcomes = responses.map(Outcome.response)
            + failures.map(Outcome.failure)
        self.recorder = recorder
    }

    func enqueue(_ response: FleetHTTPResponse) {
        lock.lock()
        outcomes.append(.response(response))
        lock.unlock()
    }

    func enqueueFailure(_ error: FakePortError = .configuredFailure(.fleetHTTP)) {
        lock.lock()
        outcomes.append(.failure(error))
        lock.unlock()
    }

    var requestMetadata: [FakeTransportRequestMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return recordedRequests
    }

    var requestCount: Int {
        requestMetadata.count
    }

    /// A client that follows a redirect would issue a second request after the
    /// first 3xx response. This value remains zero for a correctly no-follow
    /// execution with one request.
    var redirectFollowCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return requestsAfterRedirect
    }

    var noRedirectWasFollowed: Bool {
        redirectFollowCount == 0
    }

    func send(_ request: any FleetHTTPRequest) async throws -> FleetHTTPResponse {
        recorder?.append(.transportSend)

        let typeName = String(reflecting: type(of: request))
        let requestDetails = Self.requestDetails(for: request)
        lock.lock()
        let sequenceNumber = recordedRequests.count
        if redirectSeen {
            requestsAfterRedirect += 1
        }
        let outcome = outcomes.isEmpty
            ? .failure(FakePortError.noQueuedResult(.fleetHTTP))
            : outcomes.removeFirst()
        lock.unlock()

        switch outcome {
        case .response(let response):
            let wasRedirect = (300...399).contains(response.statusCode)
            lock.lock()
            if wasRedirect {
                redirectSeen = true
            }
            recordedRequests.append(
                FakeTransportRequestMetadata(
                    sequenceNumber: sequenceNumber,
                    requestTypeName: typeName,
                    responseStatusCode: response.statusCode,
                    wasRedirectResponse: wasRedirect,
                    route: requestDetails.route,
                    method: requestDetails.method,
                    hasCredential: requestDetails.hasCredential,
                    bodyFieldNames: requestDetails.bodyFieldNames,
                    bodyIsJSONObject: requestDetails.bodyIsJSONObject
                )
            )
            lock.unlock()
            return response
        case .failure(let error):
            lock.lock()
            recordedRequests.append(
                FakeTransportRequestMetadata(
                    sequenceNumber: sequenceNumber,
                    requestTypeName: typeName,
                    responseStatusCode: nil,
                    wasRedirectResponse: false,
                    route: requestDetails.route,
                    method: requestDetails.method,
                    hasCredential: requestDetails.hasCredential,
                    bodyFieldNames: requestDetails.bodyFieldNames,
                    bodyIsJSONObject: requestDetails.bodyIsJSONObject
                )
            )
            lock.unlock()
            throw error
        }
    }

    private static func requestDetails(
        for request: any FleetHTTPRequest
    ) -> (
        route: FleetRoute?,
        method: HTTPMethod?,
        hasCredential: Bool,
        bodyFieldNames: Set<String>,
        bodyIsJSONObject: Bool
    ) {
        guard let request = request as? HTTPTransportRequest else {
            return (nil, nil, false, [], false)
        }

        guard let body = request.body,
              let object = try? JSONSerialization.jsonObject(with: body),
              let dictionary = object as? [String: Any] else {
            return (
                request.routePlan.route,
                request.routePlan.method,
                request.credential != nil,
                [],
                false
            )
        }

        return (
            request.routePlan.route,
            request.routePlan.method,
            request.credential != nil,
            Set(dictionary.keys),
            true
        )
    }
}

// MARK: - Bonjour

struct FakeBonjourEventMetadata: Equatable, Sendable {
    enum Kind: String, Equatable, Sendable {
        case found
        case updated
        case removed
        case resolutionFailed
        case state
        case failed
    }

    let kind: Kind
}

/// An in-memory Bonjour event stream. Pending events are fixture inputs; event
/// history retains only event kinds and never stores discovery payloads.
final class FakeBonjourBrowser: BonjourBrowser, @unchecked Sendable {
    private let lock = FakeLock()
    private let stream: AsyncStream<BonjourEvent>
    private var continuation: AsyncStream<BonjourEvent>.Continuation?
    private var pendingEvents: [BonjourEvent]
    private var recordedEvents: [FakeBonjourEventMetadata] = []
    private var started = false
    private var stopped = false
    private var shouldFailStart = false
    private let recorder: FakeDependencyEventRecorder?

    init(
        pendingEvents: [BonjourEvent] = [],
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        var createdContinuation: AsyncStream<BonjourEvent>.Continuation?
        stream = AsyncStream { continuation in
            createdContinuation = continuation
        }
        continuation = createdContinuation
        self.pendingEvents = pendingEvents
        self.recorder = recorder
    }

    func events() -> AsyncStream<BonjourEvent> {
        recorder?.append(.bonjourEventsRequested)
        return stream
    }

    func start() async throws {
        recorder?.append(.bonjourStart)

        lock.lock()
        let failure = shouldFailStart
        started = !failure
        let eventsToReplay = failure ? [] : pendingEvents
        pendingEvents.removeAll(keepingCapacity: false)
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.bonjour)
        }

        for event in eventsToReplay {
            emit(event)
        }
    }

    func refresh() async throws {
        recorder?.append(.bonjourStart)

        lock.lock()
        let failure = shouldFailStart
        started = !failure
        stopped = false
        let eventsToReplay = failure ? [] : pendingEvents
        pendingEvents.removeAll(keepingCapacity: false)
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.bonjour)
        }

        emit(.state(.refreshing))
        for event in eventsToReplay {
            emit(event)
        }
    }

    func stop() async {
        recorder?.append(.bonjourStop)
        lock.lock()
        stopped = true
        let streamContinuation = continuation
        continuation = nil
        lock.unlock()
        streamContinuation?.finish()
    }

    func setStartFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailStart = enabled
        lock.unlock()
    }

    func enqueue(_ event: BonjourEvent) {
        lock.lock()
        let shouldEmit = started && !stopped
        if !shouldEmit {
            pendingEvents.append(event)
        }
        lock.unlock()

        if shouldEmit {
            emit(event)
        }
    }

    func replay(_ events: [BonjourEvent]) {
        for event in events {
            enqueue(event)
        }
    }

    var eventMetadata: [FakeBonjourEventMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return recordedEvents
    }

    var isStarted: Bool {
        lock.lock()
        defer { lock.unlock() }
        return started
    }

    var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    private func emit(_ event: BonjourEvent) {
        let metadata = FakeBonjourEventMetadata(kind: Self.kind(of: event))
        lock.lock()
        guard !stopped else {
            lock.unlock()
            return
        }
        recordedEvents.append(metadata)
        let streamContinuation = continuation
        lock.unlock()
        streamContinuation?.yield(event)
    }

    private static func kind(of event: BonjourEvent) -> FakeBonjourEventMetadata.Kind {
        switch event {
        case .found:
            return .found
        case .updated:
            return .updated
        case .removed(let service):
            _ = service
            return .removed
        case .removedInstance:
            return .removed
        case .resolutionFailed:
            return .resolutionFailed
        case .state:
            return .state
        case .failed:
            return .failed
        }
    }
}

// MARK: - ADB/process and artifact boundaries

enum FakeProcessOperationKind: String, Equatable, Sendable {
    case typedADBRequest
    case downloadLikeRequest
    case shellLikeRequest
    case packageManagerLikeRequest
}

struct FakeProcessOperationMetadata: Equatable, Sendable {
    let kind: FakeProcessOperationKind
}

/// Shared process audit state records only a safe operation category. It never
/// retains executable paths, shell strings, arguments, manifests, or output.
final class FakeProcessAudit: @unchecked Sendable {
    private let lock = FakeLock()
    private var recordedOperations: [FakeProcessOperationMetadata] = []

    func record(requestTypeName: String) {
        let normalized = requestTypeName.lowercased()
        let kind: FakeProcessOperationKind
        if normalized.contains("download") || normalized.contains("curl") || normalized.contains("wget") {
            kind = .downloadLikeRequest
        } else if normalized.contains("shell") || normalized.contains("shc") {
            kind = .shellLikeRequest
        } else if normalized.contains("package") || normalized.contains("pmrequest") {
            kind = .packageManagerLikeRequest
        } else {
            kind = .typedADBRequest
        }

        lock.lock()
        recordedOperations.append(FakeProcessOperationMetadata(kind: kind))
        lock.unlock()
    }

    var operations: [FakeProcessOperationMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return recordedOperations
    }

    var downloadAttemptCount: Int {
        operations.filter { $0.kind == .downloadLikeRequest }.count
    }

    var unallowlistedOperationCount: Int {
        operations.filter {
            $0.kind == .shellLikeRequest || $0.kind == .packageManagerLikeRequest
        }.count
    }

    var noDownloadAttempts: Bool {
        downloadAttemptCount == 0
    }

    var noUnallowlistedOperations: Bool {
        unallowlistedOperationCount == 0
    }

    func reset() {
        lock.lock()
        recordedOperations.removeAll(keepingCapacity: false)
        lock.unlock()
    }
}

struct FakeADBRequestMetadata: Equatable, Sendable {
    let sequenceNumber: Int
    let requestTypeName: String
}

struct FakeADBResult: ADBResult, Equatable, Sendable {
    let fixtureID: String

    init(fixtureID: String = "adb-result") {
        self.fixtureID = fixtureID
    }
}

/// A queued typed-ADB/process boundary. The fake accepts marker-typed requests
/// and returns fixture results, while recording only the request type name.
final class FakeADBRunner: ADBRunner, @unchecked Sendable {
    private enum Outcome {
        case success(any ADBResult)
        case failure(FakePortError)
    }

    private let lock = FakeLock()
    private var outcomes: [Outcome]
    private var recordedRequests: [FakeADBRequestMetadata] = []
    private var shouldFail = false
    private let processAudit: FakeProcessAudit?
    private let recorder: FakeDependencyEventRecorder?

    init(
        results: [any ADBResult] = [],
        failures: [FakePortError] = [],
        processAudit: FakeProcessAudit? = nil,
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        outcomes = results.map(Outcome.success)
            + failures.map(Outcome.failure)
        self.processAudit = processAudit
        self.recorder = recorder
    }

    func enqueue(_ result: any ADBResult) {
        lock.lock()
        outcomes.append(.success(result))
        lock.unlock()
    }

    func enqueueFailure(_ error: FakePortError = .configuredFailure(.adb)) {
        lock.lock()
        outcomes.append(.failure(error))
        lock.unlock()
    }

    func setFailure(_ enabled: Bool) {
        lock.lock()
        shouldFail = enabled
        lock.unlock()
    }

    var requestMetadata: [FakeADBRequestMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return recordedRequests
    }

    func execute(_ request: any ADBRequest) async throws -> any ADBResult {
        recorder?.append(.adbExecute)

        let typeName = String(reflecting: type(of: request))
        processAudit?.record(requestTypeName: typeName)

        lock.lock()
        recordedRequests.append(
            FakeADBRequestMetadata(
                sequenceNumber: recordedRequests.count,
                requestTypeName: typeName
            )
        )
        let failure = shouldFail
        let outcome = outcomes.isEmpty
            ? .failure(FakePortError.noQueuedResult(.adb))
            : outcomes.removeFirst()
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.adb)
        }

        switch outcome {
        case .success(let result):
            return result
        case .failure(let error):
            throw error
        }
    }
}

struct FakeArtifactVerificationResult: ArtifactVerificationResult, Equatable, Sendable {
    let fixtureID: String
    let passed: Bool

    init(fixtureID: String = "artifact-result", passed: Bool = true) {
        self.fixtureID = fixtureID
        self.passed = passed
    }
}

/// Artifact verification is kept as a separate fake boundary. It records no
/// local path, digest, package identity, signature, or artifact bytes.
final class FakeArtifactVerifier: ArtifactVerifier, @unchecked Sendable {
    private enum Outcome {
        case success(any ArtifactVerificationResult)
        case failure(FakePortError)
    }

    private let lock = FakeLock()
    private var outcomes: [Outcome]
    private var verificationOperations = 0
    private var shouldFail = false
    private let processAudit: FakeProcessAudit?
    private let recorder: FakeDependencyEventRecorder?

    init(
        results: [any ArtifactVerificationResult] = [],
        failures: [FakePortError] = [],
        processAudit: FakeProcessAudit? = nil,
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        outcomes = results.map(Outcome.success)
            + failures.map(Outcome.failure)
        self.processAudit = processAudit
        self.recorder = recorder
    }

    func enqueue(_ result: any ArtifactVerificationResult) {
        lock.lock()
        outcomes.append(.success(result))
        lock.unlock()
    }

    func enqueueFailure(_ error: FakePortError = .configuredFailure(.artifactVerification)) {
        lock.lock()
        outcomes.append(.failure(error))
        lock.unlock()
    }

    func setFailure(_ enabled: Bool) {
        lock.lock()
        shouldFail = enabled
        lock.unlock()
    }

    var verificationCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return verificationOperations
    }

    func verify(_ request: any ArtifactVerificationRequest) async throws -> any ArtifactVerificationResult {
        recorder?.append(.artifactVerify)
        processAudit?.record(requestTypeName: String(reflecting: type(of: request)))

        lock.lock()
        verificationOperations += 1
        let failure = shouldFail
        let outcome = outcomes.isEmpty
            ? .failure(FakePortError.noQueuedResult(.artifactVerification))
            : outcomes.removeFirst()
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.artifactVerification)
        }

        switch outcome {
        case .success(let result):
            return result
        case .failure(let error):
            throw error
        }
    }
}

// MARK: - Secure input and redaction

/// A redacting fake delegates to the production deterministic redactor while
/// recording only the number/kind of redaction calls.
struct FakeRedactor: Redactor, Sendable {
    private let implementation: StructuredRedactor
    private let recorder: FakeDependencyEventRecorder?

    init(
        sensitiveValues: [String] = [],
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        implementation = StructuredRedactor(sensitiveValues: sensitiveValues)
        self.recorder = recorder
    }

    func redact(_ input: String) -> RedactedText {
        recorder?.append(.redaction)
        return implementation.redact(input)
    }

    func redact(_ input: Data) -> RedactedText {
        recorder?.append(.redaction)
        return implementation.redact(input)
    }
}

/// Operation-local secure input used by tests that exercise Keychain migration
/// or replacement. Data exists only while the caller's closure is active.
final class FakeSecureInput: SecureInput, @unchecked Sendable {
    private let lock = FakeLock()
    private var storage: Data

    init(_ value: String) {
        storage = Data(value.utf8)
    }

    var isEmpty: Bool {
        lock.lock()
        defer { lock.unlock() }
        return storage.isEmpty
    }

    func withData<Result: Sendable>(
        _ body: @Sendable (Data) throws -> Result
    ) rethrows -> Result {
        lock.lock()
        var transient = Data(storage)
        lock.unlock()

        defer { wipe(&transient) }
        return try body(transient)
    }

    func clear() {
        lock.lock()
        wipe(&storage)
        lock.unlock()
    }

    deinit {
        clear()
    }

    private func wipe(_ value: inout Data) {
        guard !value.isEmpty else { return }
        value.resetBytes(in: 0..<value.count)
        value.removeAll(keepingCapacity: false)
    }
}

final class FakeSecureInputStore: SecureInputStore, @unchecked Sendable {
    private let lock = FakeLock()
    private var createdInputs = 0
    private var clearedInputs = 0
    private let recorder: FakeDependencyEventRecorder?

    init(recorder: FakeDependencyEventRecorder? = nil) {
        self.recorder = recorder
    }

    var activeInputCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return max(0, createdInputs - clearedInputs)
    }

    func makeReference(from value: String) -> SecureInputRef {
        recorder?.append(.secureInputCreate)
        lock.lock()
        createdInputs += 1
        lock.unlock()
        return FakeSecureInput(value)
    }

    func clear(_ input: SecureInputRef) {
        recorder?.append(.secureInputClear)
        input.clear()
        lock.lock()
        clearedInputs += 1
        lock.unlock()
    }
}

// MARK: - Music Assistant and Snapcast

struct FakeMusicAssistantTopology: MusicAssistantTopology, Equatable, Sendable {
    let fixtureID: String

    init(fixtureID: String = "music-topology") {
        self.fixtureID = fixtureID
    }
}

struct FakeSnapcastTopology: SnapcastTopology, Equatable, Sendable {
    let fixtureID: String

    init(fixtureID: String = "snapcast-topology") {
        self.fixtureID = fixtureID
    }
}

struct FakeServiceConnectionMetadata: Equatable, Sendable {
    let port: UInt16
    let credentialWasConfigured: Bool
}

/// A local, queued Music Assistant client. It retains only connection metadata
/// and typed fixture identifiers; credentials and endpoint hosts never enter its
/// recorded state.
final class FakeMusicAssistantClient: MusicAssistantClient, @unchecked Sendable {
    private let lock = FakeLock()
    private var topologyResults: [any MusicAssistantTopology]
    private var shouldFailConnect = false
    private var connected = false
    private var connections: [FakeServiceConnectionMetadata] = []
    private let recorder: FakeDependencyEventRecorder?

    init(
        topologies: [any MusicAssistantTopology] = [],
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        topologyResults = topologies
        self.recorder = recorder
    }

    func enqueueTopology(_ topology: any MusicAssistantTopology) {
        lock.lock()
        topologyResults.append(topology)
        lock.unlock()
    }

    func setConnectFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailConnect = enabled
        lock.unlock()
    }

    var connectionMetadata: [FakeServiceConnectionMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return connections
    }

    var isConnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        return connected
    }

    func connect(_ configuration: MusicAssistantConfiguration) async throws {
        recorder?.append(.musicConnect)
        lock.lock()
        let failure = shouldFailConnect
        if !failure {
            connected = true
            connections.append(
                FakeServiceConnectionMetadata(
                    port: configuration.endpoint.port,
                    credentialWasConfigured: configuration.credential != nil
                )
            )
        }
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.musicAssistant)
        }
    }

    func topology() async throws -> any MusicAssistantTopology {
        recorder?.append(.musicTopology)
        lock.lock()
        guard connected else {
            lock.unlock()
            throw FakePortError.notConnected(.musicAssistant)
        }
        guard !topologyResults.isEmpty else {
            lock.unlock()
            throw FakePortError.noQueuedResult(.musicAssistant)
        }
        let result = topologyResults.removeFirst()
        lock.unlock()
        return result
    }

    func disconnect() async {
        recorder?.append(.musicDisconnect)
        lock.lock()
        connected = false
        lock.unlock()
    }
}

/// A local, queued Snapcast client with the same no-secret metadata boundary as
/// the Music Assistant fake.
final class FakeSnapcastClient: SnapcastClient, @unchecked Sendable {
    private let lock = FakeLock()
    private var topologyResults: [any SnapcastTopology]
    private var shouldFailConnect = false
    private var connected = false
    private var connections: [FakeServiceConnectionMetadata] = []
    private let recorder: FakeDependencyEventRecorder?

    init(
        topologies: [any SnapcastTopology] = [],
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        topologyResults = topologies
        self.recorder = recorder
    }

    func enqueueTopology(_ topology: any SnapcastTopology) {
        lock.lock()
        topologyResults.append(topology)
        lock.unlock()
    }

    func setConnectFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailConnect = enabled
        lock.unlock()
    }

    var connectionMetadata: [FakeServiceConnectionMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return connections
    }

    var isConnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        return connected
    }

    func connect(_ configuration: SnapcastConfiguration) async throws {
        recorder?.append(.snapcastConnect)
        lock.lock()
        let failure = shouldFailConnect
        if !failure {
            connected = true
            connections.append(
                FakeServiceConnectionMetadata(
                    port: configuration.endpoint.port,
                    credentialWasConfigured: configuration.credential != nil
                )
            )
        }
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.snapcast)
        }
    }

    func topology() async throws -> any SnapcastTopology {
        recorder?.append(.snapcastTopology)
        lock.lock()
        guard connected else {
            lock.unlock()
            throw FakePortError.notConnected(.snapcast)
        }
        guard !topologyResults.isEmpty else {
            lock.unlock()
            throw FakePortError.noQueuedResult(.snapcast)
        }
        let result = topologyResults.removeFirst()
        lock.unlock()
        return result
    }

    func disconnect() async {
        recorder?.append(.snapcastDisconnect)
        lock.lock()
        connected = false
        lock.unlock()
    }

    func setClientVolume(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        clientID: String,
        percent: Int
    ) async throws {
        recorder?.append(.snapcastTopology)
    }

    func setGroupClients(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        clientIDs: [String]
    ) async throws {
        recorder?.append(.snapcastTopology)
    }

    func setGroupName(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        name: String
    ) async throws {
        recorder?.append(.snapcastTopology)
    }

    func setStream(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        streamID: String
    ) async throws {
        recorder?.append(.snapcastTopology)
    }
}

// MARK: - Evidence

/// Evidence storage is in-memory and sanitizes the two fields that are allowed
/// to cross the evidence boundary. No raw request, response, credential, or
/// process data is retained.
final class FakeEvidenceStore: EvidenceStore, @unchecked Sendable {
    private let lock = FakeLock()
    private var storedRecords: [EvidenceRecord] = []
    private var shouldFailAppend = false
    private let redactor: any Redactor
    private let recorder: FakeDependencyEventRecorder?

    init(
        redactor: any Redactor = FakeRedactor(),
        recorder: FakeDependencyEventRecorder? = nil
    ) {
        self.redactor = redactor
        self.recorder = recorder
    }

    func setAppendFailure(_ enabled: Bool) {
        lock.lock()
        shouldFailAppend = enabled
        lock.unlock()
    }

    func append(_ record: EvidenceRecord) async throws {
        recorder?.append(.evidenceAppend)
        let sanitizedRecord = EvidenceRecord(
            identifier: redactor.redact(record.identifier).value,
            outcome: record.outcome,
            summary: redactor.redact(record.summary).value
        )

        lock.lock()
        let failure = shouldFailAppend
        if !failure {
            storedRecords.append(sanitizedRecord)
        }
        lock.unlock()

        if failure {
            throw FakePortError.configuredFailure(.evidence)
        }
    }

    func records() async throws -> [EvidenceRecord] {
        recorder?.append(.evidenceRead)
        lock.lock()
        defer { lock.unlock() }
        return storedRecords
    }

    var recordCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return storedRecords.count
    }
}

// MARK: - Private deterministic helpers

private func fakeTimeInterval(_ duration: Duration) -> TimeInterval {
    let components = duration.components
    return TimeInterval(components.seconds)
        + (TimeInterval(components.attoseconds) / 1_000_000_000_000_000_000)
}

/// The Foundation lock is kept behind a synchronous wrapper so async protocol
/// methods do not call an API annotated unavailable from async contexts.
private final class FakeLock: @unchecked Sendable {
    private let underlying = NSLock()

    func lock() {
        underlying.lock()
    }

    func unlock() {
        underlying.unlock()
    }
}

// MARK: - HTTP request executor

/// Sanitized metadata captured after `HTTPTransport` constructs a URLRequest.
/// The authorization value itself is never retained.
struct FakeHTTPRequestMetadata: Equatable, Sendable {
    let sequenceNumber: Int
    let url: String
    let method: String?
    let hasAuthorization: Bool
    let timeoutInterval: TimeInterval
}

/// A deterministic URLSession replacement for HTTPTransport tests. It returns
/// queued HTTP responses and records only non-secret request metadata, so a
/// redirect response can prove that no second Location request was emitted.
final class FakeHTTPRequestExecutor: HTTPRequestExecutor, @unchecked Sendable {
    private let lock = FakeLock()
    private var responses: [FleetHTTPResponse]
    private var requests: [FakeHTTPRequestMetadata] = []

    init(responses: [FleetHTTPResponse] = []) {
        self.responses = responses
    }

    func enqueue(_ response: FleetHTTPResponse) {
        lock.lock()
        responses.append(response)
        lock.unlock()
    }

    var requestMetadata: [FakeHTTPRequestMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }

    var requestCount: Int {
        requestMetadata.count
    }

    func execute(_ request: URLRequest) async throws -> FleetHTTPResponse {
        let metadata = FakeHTTPRequestMetadata(
            sequenceNumber: requestMetadata.count,
            url: request.url?.absoluteString ?? "",
            method: request.httpMethod,
            hasAuthorization: request.value(forHTTPHeaderField: "Authorization") != nil,
            timeoutInterval: request.timeoutInterval
        )

        lock.lock()
        requests.append(metadata)
        let response = responses.isEmpty ? nil : responses.removeFirst()
        lock.unlock()

        guard let response else {
            throw FakePortError.noQueuedResult(.fleetHTTP)
        }
        return response
    }
}
