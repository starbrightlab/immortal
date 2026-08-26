/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import Network

/// The production Bonjour adapter for the Portal Manager.
///
/// `NWBrowser` performs service discovery only. It never receives a
/// credential and it never promotes a service name to Portal identity. A
/// short-lived, data-free `NWConnection` is used only to resolve a Bonjour
/// service endpoint to its host/address and port; the connection is cancelled
/// as soon as that resolution is available.
final class NWBonjourBrowser: BonjourBrowser, @unchecked Sendable {
    static let serviceType = "_immortal-remote._tcp."

    private struct ServiceKey: Hashable {
        let serviceName: String
        let interfaceName: String?
    }

    private struct ServiceDescriptor {
        let key: ServiceKey
        let serviceName: String
        let interfaceName: String?
    }

    private let stateQueue: DispatchQueue
    private let resolutionTimeout: TimeInterval
    private let stream: AsyncStream<BonjourEvent>
    private var continuation: AsyncStream<BonjourEvent>.Continuation?

    // All values below are accessed on stateQueue. NWBrowser and NWConnection
    // callbacks are scheduled on that same queue.
    private var browser: NWBrowser?
    private var activeBrowserToken: UUID?
    private var resolutionConnections: [ServiceKey: NWConnection] = [:]
    private var resolutionTimeouts: [ServiceKey: DispatchWorkItem] = [:]
    private var services: [ServiceKey: BonjourService] = [:]
    private var isRunning = false

    init(
        queue: DispatchQueue = DispatchQueue(
            label: "com.starbrightlab.portalmanager.bonjour",
            qos: .utility
        ),
        resolutionTimeout: TimeInterval = 5
    ) {
        self.stateQueue = queue
        self.resolutionTimeout = resolutionTimeout.isFinite && resolutionTimeout > 0
            ? resolutionTimeout
            : 5

        var createdContinuation: AsyncStream<BonjourEvent>.Continuation?
        self.stream = AsyncStream { continuation in
            createdContinuation = continuation
        }
        self.continuation = createdContinuation
    }

    deinit {
        browser?.cancel()
        resolutionConnections.values.forEach { $0.cancel() }
        resolutionTimeouts.values.forEach { $0.cancel() }
        continuation?.finish()
    }

    func events() -> AsyncStream<BonjourEvent> {
        stream
    }

    func start() async throws {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            stateQueue.async {
                self.startOnQueue()
                continuation.resume()
            }
        }
    }

    /// Recreates the NWBrowser so an operator refresh starts a fresh Bonjour
    /// browse without deleting managed registry entries. Existing service
    /// observations remain cached until a new add/change/remove result arrives.
    func refresh() async throws {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            stateQueue.async {
                self.refreshOnQueue()
                continuation.resume()
            }
        }
    }

    func stop() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            stateQueue.async {
                self.stopOnQueue()
                continuation.resume()
            }
        }
    }

    private func startOnQueue() {
        guard browser == nil else { return }

        publish(.state(.starting))
        isRunning = true

        let browserToken = UUID()
        activeBrowserToken = browserToken

        let parameters = NWParameters.tcp
        // Bonjour itself is local-network discovery. Explicitly exclude
        // transports that cannot be part of the version-one LAN data plane;
        // NWBrowser selects currently active Wi-Fi, wired, and other local
        // interfaces that remain available under these constraints.
        parameters.includePeerToPeer = false
        parameters.prohibitedInterfaceTypes = [.cellular, .loopback]

        let descriptor = NWBrowser.Descriptor.bonjour(
            type: Self.serviceType,
            domain: nil
        )
        let browser = NWBrowser(for: descriptor, using: parameters)
        browser.stateUpdateHandler = { [weak self] state in
            self?.handle(browserState: state, token: browserToken)
        }
        browser.browseResultsChangedHandler = { [weak self] _, changes in
            self?.handle(changes: changes, token: browserToken)
        }

        self.browser = browser
        browser.start(queue: stateQueue)
    }

    private func refreshOnQueue() {
        publish(.state(.refreshing))
        cancelBrowserOnQueue()
        startOnQueue()
    }

    private func stopOnQueue() {
        guard browser != nil || isRunning else {
            publish(.state(.cancelled))
            return
        }

        publish(.state(.cancelling))
        cancelBrowserOnQueue()
        publish(.state(.cancelled))
    }

    private func cancelBrowserOnQueue() {
        activeBrowserToken = nil
        browser?.cancel()
        browser = nil
        isRunning = false

        resolutionConnections.values.forEach { $0.cancel() }
        resolutionConnections.removeAll(keepingCapacity: false)
        resolutionTimeouts.values.forEach { $0.cancel() }
        resolutionTimeouts.removeAll(keepingCapacity: false)
    }

    private func handle(
        browserState: NWBrowser.State,
        token: UUID
    ) {
        guard activeBrowserToken == token else { return }

        switch browserState {
        case .setup:
            publish(.state(.starting))
        case .waiting:
            publish(.state(.waiting))
        case .ready:
            publish(.state(.browsing))
        case .failed:
            cancelBrowserOnQueue()
            publish(.failed(message: "Bonjour browsing is unavailable."))
            publish(.state(.failed))
        case .cancelled:
            browser = nil
            activeBrowserToken = nil
            isRunning = false
            publish(.state(.cancelled))
        @unknown default:
            isRunning = false
            publish(.failed(message: "Bonjour browsing returned an unknown state."))
            publish(.state(.failed))
        }
    }

    private func handle(
        changes: Set<NWBrowser.Result.Change>,
        token: UUID
    ) {
        guard activeBrowserToken == token else { return }

        for change in changes {
            switch change {
            case .added(let result):
                beginResolution(for: result, eventKind: .found)
            case .removed(let result):
                remove(result: result)
            case .changed(let oldResult, let newResult, _):
                cancelResolution(for: descriptor(for: oldResult)?.key)
                beginResolution(for: newResult, eventKind: .updated)
            case .identical:
                break
            @unknown default:
                break
            }
        }
    }

    private enum ResolutionEventKind {
        case found
        case updated
    }

    private func beginResolution(
        for result: NWBrowser.Result,
        eventKind: ResolutionEventKind
    ) {
        guard let descriptor = descriptor(for: result) else {
            let service = BonjourService(
                serviceName: "unknown",
                interfaceName: nil,
                source: .mdns(serviceName: "unknown"),
                resolutionError: .invalidEndpoint
            )
            publish(.resolutionFailed(service))
            return
        }

        cancelResolution(for: descriptor.key)

        let connection = NWConnection(to: result.endpoint, using: .tcp)
        resolutionConnections[descriptor.key] = connection

        let timeout = DispatchWorkItem { [weak self, weak connection] in
            guard let connection else { return }
            self?.resolutionTimedOut(
                for: descriptor,
                connection: connection
            )
        }
        resolutionTimeouts[descriptor.key] = timeout

        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            self.handle(
                resolutionState: state,
                for: descriptor,
                connection: connection,
                eventKind: eventKind
            )
        }
        connection.start(queue: stateQueue)
        stateQueue.asyncAfter(
            deadline: .now() + resolutionTimeout,
            execute: timeout
        )
    }

    private func handle(
        resolutionState: NWConnection.State,
        for descriptor: ServiceDescriptor,
        connection: NWConnection,
        eventKind: ResolutionEventKind
    ) {
        guard resolutionConnections[descriptor.key] === connection else { return }

        switch resolutionState {
        case .ready:
            guard let remoteEndpoint = connection.currentPath?.remoteEndpoint,
                  let hostPort = hostPort(from: remoteEndpoint) else {
                finishResolution(
                    for: descriptor,
                    connection: connection,
                    error: .endpointUnavailable
                )
                return
            }

            let service = BonjourService(
                serviceName: descriptor.serviceName,
                resolvedHostOrAddress: hostPort.host,
                port: hostPort.port,
                interfaceName: descriptor.interfaceName,
                source: .mdns(serviceName: descriptor.serviceName)
            )
            finishResolution(
                for: descriptor,
                connection: connection,
                service: service,
                eventKind: eventKind
            )
        case .failed:
            finishResolution(
                for: descriptor,
                connection: connection,
                error: .connectionFailed
            )
        case .cancelled:
            // Explicit browser stop/refresh cancellation removes the pending
            // connection first, so this branch is only a service resolution
            // cancellation that still belongs to the active browse.
            finishResolution(
                for: descriptor,
                connection: connection,
                error: .connectionFailed
            )
        case .waiting:
            break
        case .setup, .preparing:
            break
        @unknown default:
            finishResolution(
                for: descriptor,
                connection: connection,
                error: .connectionFailed
            )
        }
    }

    private func resolutionTimedOut(
        for descriptor: ServiceDescriptor,
        connection: NWConnection
    ) {
        guard resolutionConnections[descriptor.key] === connection else { return }
        finishResolution(
            for: descriptor,
            connection: connection,
            error: .timedOut
        )
    }

    private func finishResolution(
        for descriptor: ServiceDescriptor,
        connection: NWConnection,
        service: BonjourService? = nil,
        eventKind: ResolutionEventKind? = nil,
        error: BonjourResolutionError? = nil
    ) {
        guard resolutionConnections[descriptor.key] === connection else { return }

        resolutionConnections.removeValue(forKey: descriptor.key)
        resolutionTimeouts.removeValue(forKey: descriptor.key)?.cancel()
        connection.cancel()

        if let service, let eventKind {
            let wasPreviouslyKnown = services.updateValue(
                service,
                forKey: descriptor.key
            ) != nil
            switch eventKind {
            case .found:
                publish(wasPreviouslyKnown ? .updated(service) : .found(service))
            case .updated:
                publish(.updated(service))
            }
            return
        }

        let failedService = BonjourService(
            serviceName: descriptor.serviceName,
            interfaceName: descriptor.interfaceName,
            source: .mdns(serviceName: descriptor.serviceName),
            resolutionError: error ?? .connectionFailed
        )
        publish(.resolutionFailed(failedService))
    }

    private func remove(result: NWBrowser.Result) {
        guard let descriptor = descriptor(for: result) else { return }
        cancelResolution(for: descriptor.key)

        let service = services.removeValue(forKey: descriptor.key)
            ?? BonjourService(
                serviceName: descriptor.serviceName,
                interfaceName: descriptor.interfaceName,
                source: .mdns(serviceName: descriptor.serviceName)
            )
        publish(.removed(service))
    }

    private func cancelResolution(for key: ServiceKey?) {
        guard let key else { return }
        resolutionConnections.removeValue(forKey: key)?.cancel()
        resolutionTimeouts.removeValue(forKey: key)?.cancel()
    }

    private func descriptor(for result: NWBrowser.Result) -> ServiceDescriptor? {
        guard case let .service(name, _, _, interface) = result.endpoint else {
            return nil
        }
        let interfaceName = interface?.name
        return ServiceDescriptor(
            key: ServiceKey(
                serviceName: name,
                interfaceName: interfaceName
            ),
            serviceName: name,
            interfaceName: interfaceName
        )
    }

    private func hostPort(from endpoint: NWEndpoint) -> (host: String, port: UInt16)? {
        guard case let .hostPort(host, port) = endpoint else { return nil }
        let hostValue = String(describing: host)
        guard !hostValue.isEmpty, port.rawValue != 0 else { return nil }
        return (hostValue, port.rawValue)
    }

    private func publish(_ event: BonjourEvent) {
        continuation?.yield(event)
    }
}

/// Name used by callers that prefer an adapter-oriented type name while the
/// dependency port remains the existing `BonjourBrowser` protocol.
typealias BonjourBrowserAdapter = NWBonjourBrowser
