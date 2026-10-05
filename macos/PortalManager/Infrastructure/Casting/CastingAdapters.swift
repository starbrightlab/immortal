/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import Network

struct ScannedCastingService: Sendable, Equatable {
    let name: String
    let kind: CastingTargetKind
    let hostOrAddress: String
    let port: UInt16
}

protocol CastingServiceScanner: Sendable {
    func scan(timeout: Duration) async throws -> [ScannedCastingService]
}

/// Maps local-network advertisements into the typed casting domain. Public
/// addresses and malformed advertisements are discarded before they become UI
/// targets.
struct CastingTargetDiscovererAdapter: CastingTargetDiscoverer {
    static let maximumWindow: TimeInterval = CastingCoordinator.maximumDiscoveryDuration

    private let scanner: any CastingServiceScanner

    init(scanner: any CastingServiceScanner) {
        self.scanner = scanner
    }

    func discover(deadline: Date) async throws -> [CastingTarget] {
        let remaining = max(0.05, deadline.timeIntervalSinceNow)
        let window = min(remaining, Self.maximumWindow)
        let scanned = try await scanner.scan(timeout: .seconds(window))

        return scanned.compactMap { service in
            let normalized = service.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !normalized.isEmpty else { return nil }
            let identifier = "\(service.kind.rawValue)-\(normalized.lowercased())"
                .replacingOccurrences(of: " ", with: "-")
            guard let id = CastingTargetID(rawValue: identifier) else { return nil }
            return try? CastingTarget(
                id: id,
                name: normalized,
                kind: service.kind,
                hostOrAddress: service.hostOrAddress,
                port: service.port
            )
        }
    }

    static func unique(_ targets: [CastingTarget]) -> [CastingTarget] {
        var seen = Set<CastingTargetID>()
        return targets.filter { seen.insert($0.id).inserted }
    }
}

/// Browses both supported casting service types over Bonjour and resolves each
/// advertisement to a LAN address without retaining service TXT metadata.
final class NetworkCastingServiceScanner: CastingServiceScanner, @unchecked Sendable {
    private struct BrowseRecord {
        let kind: CastingTargetKind
        let stream: AsyncStream<NWBrowser.Result>
        let continuation: AsyncStream<NWBrowser.Result>.Continuation?
        let browser: NWBrowser
    }

    private let queue: DispatchQueue

    init(queue: DispatchQueue = DispatchQueue(label: "com.starbrightlab.portalmanager.casting")) {
        self.queue = queue
    }

    func scan(timeout: Duration) async throws -> [ScannedCastingService] {
        let seconds = max(0.05, Double(timeout.components.seconds))
        let records = await withTaskGroup(
            of: [ResolvedAdvertisement].self
        ) { group in
            group.addTask { await self.browse(kind: .airplay, seconds: seconds) }
            group.addTask { await self.browse(kind: .chromecast, seconds: seconds) }

            var advertisements: [ResolvedAdvertisement] = []
            for await resolved in group {
                advertisements.append(contentsOf: resolved)
            }
            return advertisements
        }

        let services = await withTaskGroup(
            of: ScannedCastingService?.self
        ) { group in
            for advertisement in records {
                group.addTask {
                    await Self.resolve(advertisement, timeout: .seconds(seconds))
                }
            }

            var values: [ScannedCastingService] = []
            for await value in group {
                if let value { values.append(value) }
            }
            return values
        }

        return services.sorted {
            if $0.kind != $1.kind { return $0.kind.rawValue < $1.kind.rawValue }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private struct ResolvedAdvertisement {
        let name: String
        let kind: CastingTargetKind
        let endpoint: NWEndpoint
    }

    private func browse(kind: CastingTargetKind, seconds: Double) async -> [ResolvedAdvertisement] {
        let serviceName: String
        switch kind {
        case .airplay:
            serviceName = "_airplay._tcp"
        case .chromecast:
            serviceName = "_googlecast._tcp"
        }

        let stream = AsyncStream<NWBrowser.Result> { continuation in
            let descriptor = NWBrowser.Descriptor.bonjour(type: serviceName, domain: nil)
            let parameters = NWParameters.tcp
            parameters.includePeerToPeer = false
            parameters.prohibitedInterfaceTypes = [.cellular, .loopback]
            let browser = NWBrowser(for: descriptor, using: parameters)
            browser.browseResultsChangedHandler = { _, changes in
                for change in changes {
                    if case .added(let result) = change {
                        continuation.yield(result)
                    }
                }
            }
            browser.stateUpdateHandler = { state in
                if case .failed = state {
                    continuation.finish()
                }
            }
            browser.start(queue: queue)

            queue.asyncAfter(deadline: .now() + seconds) {
                browser.cancel()
                continuation.finish()
            }
        }

        var resultsByServiceName: [String: NWEndpoint] = [:]
        for await result in stream {
            if case .service(let name, _, _, _) = result.endpoint {
                resultsByServiceName[name] = result.endpoint
            }
        }

        return resultsByServiceName.map {
            ResolvedAdvertisement(name: $0.key, kind: kind, endpoint: $0.value)
        }
    }

    private static func resolve(
        _ advertisement: ResolvedAdvertisement,
        timeout: Duration
    ) async -> ScannedCastingService? {
        guard let hostPort = await resolve(endpoint: advertisement.endpoint, timeout: timeout) else {
            return nil
        }
        return ScannedCastingService(
            name: advertisement.name,
            kind: advertisement.kind,
            hostOrAddress: hostPort.host,
            port: hostPort.port
        )
    }

    private static func resolve(
        endpoint: NWEndpoint,
        timeout: Duration
    ) async -> (host: String, port: UInt16)? {
        let gate = CompletionGate<(String, UInt16)>()
        let connection = NWConnection(to: endpoint, using: .tcp)
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                if let remote = connection.currentPath?.remoteEndpoint,
                   case .hostPort(let host, let port) = remote {
                    gate.finish((hostDescription(host), port.rawValue))
                } else {
                    gate.finish(nil)
                }
            case .failed, .cancelled:
                gate.finish(nil)
            default:
                break
            }
        }

        connection.start(queue: DispatchQueue.global(qos: .utility))
        let result = await gate.wait(timeout: timeout)
        connection.cancel()
        return result
    }

    private static func hostDescription(_ host: NWEndpoint.Host) -> String {
        let text = String(describing: host).lowercased()
        if let zoneRange = text.range(of: "%") {
            return String(text[..<zoneRange.lowerBound])
        }
        return text
    }
}

private final class CompletionGate<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Value?
    private var continuation: CheckedContinuation<Value?, Never>?
    private var finished = false

    func finish(_ value: Value?) {
        lock.lock()
        defer { lock.unlock() }
        guard !finished, let continuation else {
            finished = true
            self.result = value
            return
        }
        finished = true
        continuation.resume(returning: value)
        self.continuation = nil
    }

    func wait(timeout: Duration) async -> Value? {
        let seconds = TimeInterval(timeout.components.seconds)
        DispatchQueue.global().asyncAfter(deadline: .now() + max(0.05, seconds)) {
            self.finish(nil)
        }
        return await withCheckedContinuation { continuation in
            lock.lock()
            if finished {
                continuation.resume(returning: self.result)
                self.result = nil
            } else {
                self.continuation = continuation
            }
            lock.unlock()
        }
    }
}

// MARK: - Control

typealias CastingHTTPPerformer = @Sendable (URLRequest) async throws -> (Data, HTTPURLResponse)

/// Minimal LAN-only AirPlay control surface for URL playback and session stop.
struct AirPlayTargetController: CastingTargetController {
    private let perform: CastingHTTPPerformer

    init(perform: @escaping CastingHTTPPerformer = Self.defaultPerform) {
        self.perform = perform
    }

    func connect(to target: CastingTarget) async throws {
        _ = try await performRequest(target: target, method: "GET", path: "/server-info", body: nil)
    }

    func disconnect(from target: CastingTarget) async throws {
        _ = try await performRequest(target: target, method: "POST", path: "/stop", body: Data())
    }

    func play(
        _ request: CastingPlaybackRequest,
        on target: CastingTarget
    ) async throws -> CastingPlaybackSnapshot {
        let parameters = "Content-Location: \(request.source.absoluteString)\nStart-Position: 0"
        _ = try await performRequest(
            target: target,
            method: "POST",
            path: "/play",
            body: Data(parameters.utf8),
            contentType: "text/parameters"
        )
        return CastingPlaybackSnapshot(state: .playing, title: request.title)
    }

    func stop(on target: CastingTarget) async throws -> CastingPlaybackSnapshot {
        _ = try await performRequest(target: target, method: "POST", path: "/stop", body: Data())
        return CastingPlaybackSnapshot(state: .stopped)
    }

    private func performRequest(
        target: CastingTarget,
        method: String,
        path: String,
        body: Data?,
        contentType: String? = nil
    ) async throws -> Data {
        var components = URLComponents()
        components.scheme = "http"
        components.host = target.hostOrAddress
        components.port = Int(target.port)
        components.path = path
        guard let endpoint = components.url else {
            throw CastingCoordinatorError.controlFailed(.invalidEndpoint)
        }

        var request = URLRequest(url: endpoint)
        request.httpMethod = method
        request.httpBody = body
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 3

        do {
            let (data, response) = try await perform(request)
            guard (200..<300).contains(response.statusCode) else {
                throw CastingCoordinatorError.controlFailed(.unauthorized)
            }
            return data
        } catch let error as CastingCoordinatorError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw CastingCoordinatorError.controlFailed(.transport)
        }
    }

    private static func defaultPerform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw CastingCoordinatorError.controlFailed(.transport)
        }
        return (data, httpResponse)
    }
}

/// Chooses the protocol-specific control adapter after discovery has admitted
/// the target kind.
struct MultiKindCastingController: CastingTargetController {
    private let airPlay: AirPlayTargetController
    private let chromecast: ChromecastTargetController

    init(
        airPlay: AirPlayTargetController = AirPlayTargetController(),
        chromecast: ChromecastTargetController = ChromecastTargetController()
    ) {
        self.airPlay = airPlay
        self.chromecast = chromecast
    }

    func connect(to target: CastingTarget) async throws {
        switch target.kind {
        case .airplay: try await airPlay.connect(to: target)
        case .chromecast: try await chromecast.connect(to: target)
        }
    }

    func disconnect(from target: CastingTarget) async throws {
        switch target.kind {
        case .airplay: try await airPlay.disconnect(from: target)
        case .chromecast: try await chromecast.disconnect(from: target)
        }
    }

    func play(
        _ request: CastingPlaybackRequest,
        on target: CastingTarget
    ) async throws -> CastingPlaybackSnapshot {
        switch target.kind {
        case .airplay: return try await airPlay.play(request, on: target)
        case .chromecast: return try await chromecast.play(request, on: target)
        }
    }

    func stop(on target: CastingTarget) async throws -> CastingPlaybackSnapshot {
        switch target.kind {
        case .airplay: return try await airPlay.stop(on: target)
        case .chromecast: return try await chromecast.stop(on: target)
        }
    }
}

enum CastV2Message {
    static let defaultMediaReceiverAppID = "CC1AD845"
    static let receiverNamespace = "urn:x-cast:com.google.cast.receiver"
    static let mediaNamespace = "urn:x-cast:com.google.cast.media"
    static let maximumMessageSize = 64 * 1024

    static func connect() -> Data {
        payload(
            namespace: "urn:x-cast:com.google.cast.tp.connection",
            json: #"{"type":"CONNECT"}"#
        )
    }

    static func close() -> Data {
        payload(
            namespace: "urn:x-cast:com.google.cast.tp.connection",
            json: #"{"type":"CLOSE"}"#
        )
    }

    static func launchDefaultMediaReceiver() -> Data {
        payload(
            namespace: receiverNamespace,
            json: #"{"type":"LAUNCH","appId":"CC1AD845"}"#
        )
    }

    static func stopMediaReceiver() -> Data {
        payload(
            namespace: receiverNamespace,
            json: #"{"type":"STOP"}"#
        )
    }

    static func load(
        requestID: UInt32,
        source: URL,
        title: String,
        contentType: String,
        destination transportID: String
    ) -> Data? {
        let media: [String: Any] = [
            "contentId": source.absoluteString,
            "contentType": contentType,
            "streamType": "BUFFERED",
            "metadata": [
                "metadataType": 0,
                "title": title,
            ],
        ]
        let object: [String: Any] = [
            "type": "LOAD",
            "requestId": NSNumber(value: requestID),
            "autoplay": true,
            "media": media,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return payload(
            namespace: mediaNamespace,
            source: "sender-0",
            destination: transportID,
            json: String(decoding: data, as: UTF8.self)
        )
    }

    static func framed(_ message: Data) -> Data {
        var frame = UInt32(message.count).littleEndianData
        frame.append(message)
        return frame
    }

    static func payload(namespace: String, json: String) -> Data {
        payload(namespace: namespace, source: "sender-0", destination: "receiver-0", json: json)
    }

    static func payload(
        namespace: String,
        source: String,
        destination: String,
        json: String
    ) -> Data {
        var message = Data()
        message.append(fieldTag(1, wireType: 0))
        message.append(varint(0)) // PROTOCOL_VERSION_CASTV2_1_0
        appendString(&message, field: 2, value: source)
        appendString(&message, field: 3, value: destination)
        appendString(&message, field: 4, value: namespace)
        message.append(fieldTag(5, wireType: 0))
        message.append(varint(1)) // PAYLOAD_TYPE_UTF8
        appendString(&message, field: 6, value: json)
        return message
    }

    struct DecodedMessage {
        let namespace: String
        let destination: String
        let payloadJSON: Data
    }

    static func unframed(_ data: Data) -> Data? {
        guard data.count >= 4 else { return nil }
        let length = UInt32(data[0])
            | (UInt32(data[1]) << 8)
            | (UInt32(data[2]) << 16)
            | (UInt32(data[3]) << 24)
        guard Int(length) == data.count - 4 else { return nil }
        return data.dropFirst(4)
    }

    static func decode(_ data: Data) -> DecodedMessage? {
        let bytes = [UInt8](data.prefix(maximumMessageSize))
        var offset = 0
        var namespace: String?
        var source: String?
        var destination: String?
        var payloadJSON: Data?

        while offset < bytes.count {
            guard let tag = readVarint(bytes, offset: &offset), tag > 0 else { return nil }
            let field = Int(tag >> 3)
            let wireType = Int(tag & 0x07)

            switch (field, wireType) {
            case (1, 0):
                guard let version = readVarint(bytes, offset: &offset), version == 0 else {
                    return nil
                }
            case (2, 2), (3, 2), (4, 2), (6, 2):
                guard let length = readVarint(bytes, offset: &offset),
                      length <= UInt64(bytes.count - offset),
                      offset + Int(length) <= bytes.count else {
                    return nil
                }
                let value = Data(bytes[offset..<(offset + Int(length))])
                offset += Int(length)
                if field == 2 {
                    source = String(decoding: value, as: UTF8.self)
                } else if field == 3 {
                    destination = String(decoding: value, as: UTF8.self)
                } else if field == 4 {
                    namespace = String(decoding: value, as: UTF8.self)
                } else if field == 6 {
                    payloadJSON = value
                }
            default:
                if wireType == 0 {
                    guard readVarint(bytes, offset: &offset) != nil else { return nil }
                } else if wireType == 2 {
                    guard let length = readVarint(bytes, offset: &offset),
                          length <= UInt64(bytes.count - offset) else {
                        return nil
                    }
                    offset += Int(length)
                } else {
                    return nil
                }
            }
        }

        guard let namespace, let source, let destination, let payloadJSON else { return nil }
        return DecodedMessage(
            namespace: namespace,
            destination: destination,
            payloadJSON: payloadJSON
        )
    }

    static func transportID(from data: Data) -> String? {
        guard let payload = unframed(data),
              let message = decode(payload),
              message.destination == "sender-0",
              message.namespace == receiverNamespace,
              let object = try? JSONSerialization.jsonObject(with: message.payloadJSON),
              let fields = object as? [String: Any],
              fields["type"] as? String == "RECEIVER_STATUS",
              let status = fields["status"] as? [String: Any],
              let applications = status["applications"] as? [[String: Any]] else {
            return nil
        }

        for application in applications {
            guard application["appId"] as? String == defaultMediaReceiverAppID else { continue }
            if let transportID = application["transportId"] as? String, !transportID.isEmpty {
                return transportID
            }
        }
        return nil
    }

    private static func readVarint(_ bytes: [UInt8], offset: inout Int) -> UInt64? {
        var value: UInt64 = 0
        var shift: UInt64 = 0

        while offset < bytes.count, shift < 64 {
            let byte = bytes[offset]
            offset += 1
            value |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 {
                return value
            }
            shift += 7
        }
        return nil
    }

    private static func appendString(_ data: inout Data, field: Int, value: String) {
        data.append(fieldTag(field, wireType: 2))
        data.append(bytes(value.utf8))
    }

    private static func bytes(_ content: some Sequence<UInt8>) -> Data {
        let value = Data(content)
        return varint(UInt64(value.count)) + value
    }

    private static func fieldTag(_ field: Int, wireType: Int) -> Data {
        varint(UInt64((field << 3) | wireType))
    }

    private static func varint(_ input: UInt64) -> Data {
        var value = input
        var output = Data()
        repeat {
            var byte = UInt8(value & 0x7f)
            value >>= 7
            if value != 0 { byte |= 0x80 }
            output.append(byte)
        } while value != 0
        return output
    }
}

extension UInt32 {
    fileprivate var littleEndianData: Data {
        withUnsafeBytes(of: self.littleEndian) { Data($0) }
    }
}

protocol CastingSocket: Sendable {
    func send(_ data: Data) async throws
    func receive(maximumBytes: Int, timeout: Duration) async throws -> Data
    func close() async
}

protocol CastingSocketFactory: Sendable {
    func open(host: String, port: UInt16) async throws -> any CastingSocket
}

/// Sends Cast V2 control frames and launches the default media receiver only
/// when the user explicitly plays media. Receiver transport identifiers stay
/// inside this actor.
actor ChromecastTargetController: CastingTargetController {
    private static let launchTimeout: TimeInterval = 5

    private let factory: any CastingSocketFactory
    private let clock: any CastingClock
    private var sessionsByID: [CastingTargetID: CastSession] = [:]
    private var nextRequestID: UInt32 = 1

    private struct CastSession {
        let socket: any CastingSocket
        var transportID: String?
    }

    init(
        factory: any CastingSocketFactory = NetworkCastingSocketFactory(),
        clock: any CastingClock = SystemCastingClock()
    ) {
        self.factory = factory
        self.clock = clock
    }

    func connect(to target: CastingTarget) async throws {
        if let existing = sessionsByID[target.id] {
            await existing.socket.close()
        }
        let socket = try await openSocket(target)
        sessionsByID[target.id] = CastSession(socket: socket, transportID: nil)

        do {
            try await socket.send(CastV2Message.framed(CastV2Message.connect()))
        } catch is CancellationError {
            await socket.close()
            throw CancellationError()
        } catch {
            await socket.close()
            throw CastingCoordinatorError.controlFailed(.transport)
        }
    }

    func disconnect(from target: CastingTarget) async throws {
        guard let session = sessionsByID.removeValue(forKey: target.id) else {
            throw CastingCoordinatorError.controlFailed(.unsupportedTarget)
        }

        do {
            try await session.socket.send(CastV2Message.framed(CastV2Message.close()))
        } catch {
            // Closing still proceeds; a failed close cannot strand the socket.
        }
        await session.socket.close()
    }

    func play(
        _ request: CastingPlaybackRequest,
        on target: CastingTarget
    ) async throws -> CastingPlaybackSnapshot {
        guard var session = sessionsByID[target.id] else {
            throw CastingCoordinatorError.controlFailed(.unsupportedTarget)
        }

        if session.transportID == nil {
            session.transportID = try await launchMediaReceiver(session.socket)
            sessionsByID[target.id] = session
        }
        guard let transportID = session.transportID else {
            throw CastingCoordinatorError.controlFailed(.transport)
        }

        let requestID = nextRequestID
        nextRequestID = nextRequestID == .max ? 1 : nextRequestID + 1
        guard let load = CastV2Message.load(
            requestID: requestID,
            source: request.source,
            title: request.title,
            contentType: request.contentType,
            destination: transportID
        ) else {
            throw CastingCoordinatorError.controlFailed(.invalidEndpoint)
        }

        try await send(CastV2Message.framed(load), session.socket)
        return CastingPlaybackSnapshot(state: .playing, title: request.title)
    }

    func stop(on target: CastingTarget) async throws -> CastingPlaybackSnapshot {
        guard let session = sessionsByID[target.id] else {
            throw CastingCoordinatorError.controlFailed(.unsupportedTarget)
        }
        guard session.transportID != nil else {
            return CastingPlaybackSnapshot(state: .stopped)
        }

        try await send(CastV2Message.framed(CastV2Message.stopMediaReceiver()), session.socket)
        sessionsByID[target.id]?.transportID = nil
        return CastingPlaybackSnapshot(state: .stopped)
    }

    private func launchMediaReceiver(_ socket: any CastingSocket) async throws -> String {
        try await send(CastV2Message.framed(CastV2Message.launchDefaultMediaReceiver()), socket)
        let deadline = clock.now.addingTimeInterval(Self.launchTimeout)

        while clock.now < deadline {
            let remaining = deadline.timeIntervalSince(clock.now)
            let data = try await socket.receive(
                maximumBytes: CastV2Message.maximumMessageSize + 4,
                timeout: .seconds(remaining)
            )
            if let transportID = CastV2Message.transportID(from: data) {
                return transportID
            }

            let nextPoll = min(deadline, clock.now.addingTimeInterval(0.05))
            try await clock.sleep(until: nextPoll)
        }

        throw CastingCoordinatorError.controlFailed(.timeout)
    }

    private func send(_ frame: Data, _ socket: any CastingSocket) async throws {
        do {
            try await socket.send(frame)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw CastingCoordinatorError.controlFailed(.transport)
        }
    }

    private func openSocket(_ target: CastingTarget) async throws -> any CastingSocket {
        do {
            return try await factory.open(host: target.hostOrAddress, port: target.port)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw CastingCoordinatorError.controlFailed(.transport)
        }
    }
}

struct NetworkCastingSocketFactory: CastingSocketFactory {
    func open(host: String, port: UInt16) async throws -> any CastingSocket {
        let endpoint = NWEndpoint.hostPort(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port)!
        )
        let options = NWProtocolTLS.Options()
        let parameters = NWParameters(tls: options)
        parameters.prohibitedInterfaceTypes = [.cellular, .loopback]
        let connection = NWConnection(to: endpoint, using: parameters)
        let socket = NWCastSocket(connection: connection)

        let gate = CompletionGate<Bool>()
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: gate.finish(true)
            case .failed, .cancelled: gate.finish(false)
            default: break
            }
        }
        connection.start(queue: DispatchQueue.global(qos: .userInitiated))
        let ready = await gate.wait(timeout: .seconds(3))
        guard ready == true else {
            connection.cancel()
            throw CastingCoordinatorError.controlFailed(.transport)
        }
        return socket
    }
}

private final class NWCastSocket: CastingSocket, @unchecked Sendable {
    private let connection: NWConnection
    private let lock = NSLock()
    private var closed = false

    init(connection: NWConnection) {
        self.connection = connection
    }

    func send(_ data: Data) async throws {
        let box = ContinuationBox<Void>()
        return try await withCheckedThrowingContinuation { continuation in
            box.store(continuation)
            connection.send(content: data, completion: .contentProcessed { error in
                if let error {
                    box.resume(throwing: error)
                } else {
                    box.resume(returning: ())
                }
            })
        }
    }

    func receive(maximumBytes: Int, timeout: Duration) async throws -> Data {
        guard maximumBytes > 0 else {
            throw CastingCoordinatorError.controlFailed(.invalidEndpoint)
        }

        return try await withThrowingTaskGroup(of: Data.self) { group in
            group.addTask { try await self.receiveFrame(maximumBytes: maximumBytes) }
            group.addTask {
                try await Task.sleep(for: timeout)
                throw CastingCoordinatorError.controlFailed(.timeout)
            }

            while let frame = try await group.next() {
                group.cancelAll()
                return frame
            }
            throw CastingCoordinatorError.controlFailed(.transport)
        }
    }

    func close() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        lock.unlock()
        connection.cancel()
    }

    private func receiveFrame(maximumBytes: Int) async throws -> Data {
        let header = try await receiveExact(byteCount: 4)
        let length = UInt64(header[0])
            | (UInt64(header[1]) << 8)
            | (UInt64(header[2]) << 16)
            | (UInt64(header[3]) << 24)
        guard length > 0, length <= UInt64(maximumBytes) else {
            throw CastingCoordinatorError.controlFailed(.invalidEndpoint)
        }
        let payload = try await receiveExact(byteCount: Int(length))
        return header + payload
    }

    private func receiveExact(byteCount: Int) async throws -> Data {
        var output = Data()

        while output.count < byteCount {
            let box = ContinuationBox<Data?>()
            let chunk = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data?, Error>) in
                    box.store(continuation)
                    connection.receive(
                        minimumIncompleteLength: 1,
                        maximumLength: byteCount - output.count
                    ) { content, _, isComplete, error in
                        if let error {
                            box.resume(throwing: error)
                        } else if isComplete, content == nil || content?.isEmpty == true {
                            box.resume(returning: nil)
                        } else {
                            box.resume(returning: content)
                        }
                    }
                }
            } onCancel: {
                box.resume(throwing: CancellationError())
            }

            guard var received = chunk, !received.isEmpty else {
                throw CastingCoordinatorError.controlFailed(.transport)
            }
            let allowed = byteCount - output.count
            if received.count > allowed {
                received = received.prefix(allowed)
            }
            output.append(received)
        }
        return output
    }

}

private final class ContinuationBox<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?

    func store(_ value: CheckedContinuation<Value, Error>) {
        lock.lock()
        continuation = value
        lock.unlock()
    }

    func resume(returning value: Value) {
        lock.lock()
        let current = continuation
        continuation = nil
        lock.unlock()
        current?.resume(returning: value)
    }

    func resume(throwing error: Error) {
        lock.lock()
        let value = continuation
        continuation = nil
        lock.unlock()
        value?.resume(throwing: error)
    }
}
