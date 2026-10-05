/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import XCTest

#if !CASTING_TYPECHECK_STANDALONE
@testable import PortalManager
#endif

private struct ScriptedCastingScanner: CastingServiceScanner {
    let services: [ScannedCastingService]
    var error: Error? = nil

    func scan(timeout: Duration) async throws -> [ScannedCastingService] {
        if let error { throw error }
        return services
    }
}

private struct RawCastingTimeout: Error {}

private final class AirPlayRequestRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [URLRequest] = []

    func append(_ value: URLRequest) {
        lock.lock()
        values.append(value)
        lock.unlock()
    }

    var all: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return values
    }
}

private func receiverStatusFrame(
    transportID: String?,
    appID: String = CastV2Message.defaultMediaReceiverAppID
) -> Data {
    var application: [String: Any] = ["appId": appID]
    if let transportID {
        application["transportId"] = transportID
    }
    let object: [String: Any] = [
        "type": "RECEIVER_STATUS",
        "status": ["applications": [application]],
    ]
    let json = (try? JSONSerialization.data(withJSONObject: object))
        .map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    return CastV2Message.framed(
        CastV2Message.payload(
            namespace: CastV2Message.receiverNamespace,
            source: "receiver-0",
            destination: "sender-0",
            json: json
        )
    )
}

private final class DeterministicCastClock: CastingClock, @unchecked Sendable {
    private let lock = NSLock()
    private var currentDate: Date

    init(now: Date = Date(timeIntervalSince1970: 1200)) {
        currentDate = now
    }

    var now: Date {
        lock.lock()
        defer { lock.unlock() }
        return currentDate
    }

    func sleep(until deadline: Date) async throws {
        lock.lock()
        defer { lock.unlock() }
        currentDate = max(currentDate, deadline)
    }
}

final class CastingAdapterTests: XCTestCase {
    func testCastingRowActionsKeepAvailableAndFailedTargetsRecoverable() {
        XCTAssertEqual(
            CastingView.rowAction(for: .disconnected),
            .connect("Connect")
        )
        XCTAssertEqual(
            CastingView.rowAction(for: .connecting),
            .connect("Connect")
        )
        XCTAssertEqual(
            CastingView.rowAction(for: .disconnecting),
            .connect("Connect")
        )
        XCTAssertEqual(
            CastingView.rowAction(for: .failed(.transport)),
            .connect("Retry")
        )
        XCTAssertEqual(
            CastingView.rowAction(for: .connected),
            .disconnect
        )
    }

    private func target(
        _ id: String,
        kind: CastingTargetKind,
        host: String = "192.168.1.20"
    ) throws -> CastingTarget {
        try CastingTarget(
            id: CastingTargetID(rawValue: id)!,
            name: "Living Room",
            kind: kind,
            hostOrAddress: host,
            port: kind == .airplay ? 700 : 8009
        )
    }

    func testDiscoveryMapsAndDeduplicatesLocalServices() async throws {
        let scanner = ScriptedCastingScanner(services: [
            ScannedCastingService(name: "Living Room", kind: .airplay, hostOrAddress: "192.168.1.20", port: 700),
            ScannedCastingService(name: "living room", kind: .airplay, hostOrAddress: "192.168.1.21", port: 700),
            ScannedCastingService(name: "Den", kind: .chromecast, hostOrAddress: "192.168.1.22", port: 8009),
            ScannedCastingService(name: "Outside", kind: .chromecast, hostOrAddress: "203.0.113.8", port: 8009),
        ])
        let adapter = CastingTargetDiscovererAdapter(scanner: scanner)
        let deadline = Date().addingTimeInterval(2)

        let mapped = CastingTargetDiscovererAdapter.unique(try await adapter.discover(deadline: deadline))

        XCTAssertEqual(
            mapped.map { target in target.id.rawValue },
            ["airplay-living-room", "chromecast-den"]
        )
        XCTAssertEqual(mapped.first?.port, 700)
    }

    func testAirPlayConnectAndDisconnectUseApprovedControlPaths() async throws {
        var requests: [URLRequest] = []
        let controller = AirPlayTargetController { request in
            requests.append(request)
            return (Data(), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let airPlay = try target("airplay-room", kind: .airplay)

        try await controller.connect(to: airPlay)
        try await controller.disconnect(from: airPlay)

        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].httpMethod, "GET")
        XCTAssertTrue(requests[0].url?.path.hasSuffix("/server-info") == true)
        XCTAssertEqual(requests[1].httpMethod, "POST")
        XCTAssertTrue(requests[1].url?.path.hasSuffix("/stop") == true)
    }

    func testAirPlayPlayAndExplicitStopUseApprovedControlPaths() async throws {
        let recorder = AirPlayRequestRecorder()
        let controller = AirPlayTargetController { request in
            recorder.append(request)
            return (Data(), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let airPlay = try target("airplay-room", kind: .airplay)
        let request = try CastingPlaybackRequest(
            source: URL(string: "https://192.168.1.20/media/song.m4a")!,
            title: "Kitchen Song",
            contentType: "audio/mp4"
        )

        let played = try await controller.play(request, on: airPlay)
        XCTAssertEqual(played.state, .playing)
        XCTAssertEqual(played.title, "Kitchen Song")

        let stopped = try await controller.stop(on: airPlay)
        XCTAssertEqual(stopped.state, .stopped)
        let requests = recorder.all
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[0].httpMethod, "POST")
        XCTAssertTrue(requests[0].url?.path.hasSuffix("/play") == true)
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Content-Type"), "text/parameters")
        let body = try XCTUnwrap(requests[0].httpBody)
        XCTAssertEqual(String(decoding: body, as: UTF8.self), "Content-Location: https://192.168.1.20/media/song.m4a\nStart-Position: 0")
        XCTAssertEqual(requests.last?.httpMethod, "POST")
        XCTAssertTrue(requests.last?.url?.path.hasSuffix("/stop") == true)
    }

    func testChromecastHandshakeUsesTypedCastV2Frames() async throws {
        final class Socket: CastingSocket, @unchecked Sendable {
            let lock = NSLock()
            private var frames: [Data] = []

            func send(_ data: Data) async throws {
                lock.lock()
                frames.append(data)
                lock.unlock()
            }

            func close() {}

            func receive(maximumBytes: Int, timeout: Duration) async throws -> Data {
                throw RawCastingTimeout()
            }

            var recordedFrames: [Data] {
                lock.lock()
                defer { lock.unlock() }
                return frames
            }
        }

        final class Factory: CastingSocketFactory, @unchecked Sendable {
            let socket = Socket()
            func open(host: String, port: UInt16) async throws -> any CastingSocket {
                XCTAssertEqual(host, "192.168.1.20")
                XCTAssertEqual(port, 8009)
                return socket
            }
        }

        let factory = Factory()
        let chromecast = try target("chromecast-room", kind: .chromecast)
        let controller = ChromecastTargetController(factory: factory)

        try await controller.connect(to: chromecast)
        try await controller.disconnect(from: chromecast)

        let frames = factory.socket.recordedFrames
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0], CastV2Message.framed(CastV2Message.connect()))
        XCTAssertEqual(frames[1], CastV2Message.framed(CastV2Message.close()))

        let payload = CastV2Message.connect()
        XCTAssertTrue(payload.starts(with: [0x08, 0x00]))
        XCTAssertTrue(String(decoding: payload, as: UTF8.self).contains("sender-0"))
        XCTAssertTrue(String(decoding: payload, as: UTF8.self).contains("receiver-0"))
    }

    func testCastV2FramingIsLengthPrefixedAndBounded() {
        let message = Data("abc".utf8)
        let frame = CastV2Message.framed(message)

        XCTAssertEqual(Array(frame.prefix(4)), [3, 0, 0, 0])
        XCTAssertEqual(frame.suffix(3), message)
        XCTAssertEqual(CastV2Message.maximumMessageSize, 64 * 1024)
    }

    func testCastV2DecodeExtractsTypedMessageFields() throws {
        let json = #"{"type":"PING"}"#
        let payload = CastV2Message.payload(
            namespace: CastV2Message.mediaNamespace,
            source: "sender-0",
            destination: "web-7",
            json: json
        )

        let decoded = try XCTUnwrap(CastV2Message.decode(payload))
        XCTAssertEqual(decoded.namespace, CastV2Message.mediaNamespace)
        XCTAssertEqual(decoded.destination, "web-7")
        XCTAssertEqual(String(decoding: decoded.payloadJSON, as: UTF8.self), json)
        XCTAssertNil(CastV2Message.decode(Data([0xff])))
    }

    func testChromecastLaunchStatusLoadAndStopSequence() async throws {
        final class ScriptedSocket: CastingSocket, @unchecked Sendable {
            let lock = NSLock()
            private var _frames: [Data] = []
            private var launchResponses = 0

            func send(_ data: Data) async throws {
                lock.lock()
                _frames.append(data)
                if data == CastV2Message.framed(CastV2Message.launchDefaultMediaReceiver()) {
                    launchResponses = 1
                }
                lock.unlock()
            }

            func receive(maximumBytes: Int, timeout: Duration) async throws -> Data {
                lock.lock()
                guard launchResponses > 0 else {
                    lock.unlock()
                    throw RawCastingTimeout()
                }
                launchResponses -= 1
                lock.unlock()
                let frame = receiverStatusFrame(transportID: "web-9")
                return frame
            }

            func close() async {}

            var frames: [Data] {
                lock.lock()
                defer { lock.unlock() }
                return _frames
            }
        }

        final class ScriptedFactory: CastingSocketFactory, @unchecked Sendable {
            let socket = ScriptedSocket()
            func open(host: String, port: UInt16) async throws -> any CastingSocket {
                XCTAssertEqual(host, "192.168.1.20")
                XCTAssertEqual(port, 8009)
                return socket
            }
        }

        let factory = ScriptedFactory()
        let controller = ChromecastTargetController(factory: factory)
        let chromecast = try target("chromecast-room", kind: .chromecast)
        try await controller.connect(to: chromecast)

        let playback = try CastingPlaybackRequest(
            source: URL(string: "https://192.168.1.20:8443/media/front-door.mp4")!,
            title: "Front Door",
            contentType: "video/mp4"
        )
        let played = try await controller.play(playback, on: chromecast)
        XCTAssertEqual(played.state, .playing)
        XCTAssertEqual(played.title, "Front Door")

        let stopped = try await controller.stop(on: chromecast)
        XCTAssertEqual(stopped.state, .stopped)

        let frames = factory.socket.frames
        XCTAssertEqual(frames.count, 4)
        XCTAssertEqual(frames[0], CastV2Message.framed(CastV2Message.connect()))
        XCTAssertEqual(frames[1], CastV2Message.framed(CastV2Message.launchDefaultMediaReceiver()))
        XCTAssertEqual(frames[3], CastV2Message.framed(CastV2Message.stopMediaReceiver()))

        let loadPayload = frames[2].dropFirst(4)
        let decoded = try XCTUnwrap(CastV2Message.decode(Data(loadPayload)))
        XCTAssertEqual(decoded.namespace, CastV2Message.mediaNamespace)
        XCTAssertEqual(decoded.destination, "web-9")
        let load = try XCTUnwrap(try JSONSerialization.jsonObject(with: decoded.payloadJSON) as? [String: Any])
        XCTAssertEqual(load["type"] as? String, "LOAD")
        XCTAssertEqual(load["requestId"] as? Int, 1)
        let media = try XCTUnwrap(load["media"] as? [String: Any])
        XCTAssertEqual(media["contentId"] as? String, "https://192.168.1.20:8443/media/front-door.mp4")
        XCTAssertEqual(media["contentType"] as? String, "video/mp4")
    }

    func testChromecastLaunchTimesOutWithoutValidReceiverStatus() async throws {
        final class InvalidStatusSocket: CastingSocket, @unchecked Sendable {
            func send(_ data: Data) async throws {}
            func receive(maximumBytes: Int, timeout: Duration) async throws -> Data {
                receiverStatusFrame(transportID: nil, appID: "OTHERAPP")
            }
            func close() async {}
        }

        final class InvalidFactory: CastingSocketFactory, @unchecked Sendable {
            func open(host: String, port: UInt16) async throws -> any CastingSocket {
                InvalidStatusSocket()
            }
        }

        let controller = ChromecastTargetController(factory: InvalidFactory(), clock: DeterministicCastClock())
        let chromecast = try target("chromecast-timeout", kind: .chromecast)
        try await controller.connect(to: chromecast)
        let playback = try CastingPlaybackRequest(
            source: URL(string: "https://192.168.1.20/media/front-door.mp4")!,
            title: "Front Door",
            contentType: "video/mp4"
        )

        do {
            _ = try await controller.play(playback, on: chromecast)
            XCTFail("Expected launch to time out without a valid transport identifier.")
        } catch let error as CastingCoordinatorError {
            XCTAssertEqual(error, .controlFailed(.timeout))
        }
    }

    @MainActor
    func testCastingViewCanBeConstructedWithoutTargets() {
        let view = CastingView(
            targets: [],
            states: [:],
            isScanning: false,
            onScan: {},
            onConnect: { _ in },
            onDisconnect: { _ in }
        )
        XCTAssertNotNil(view.body)
    }

    @MainActor
    func testCastingViewConstructionIncludesPlaybackCallbacksAndRequests() {
        let target = try? target("chromecast-room", kind: .chromecast)
        let request = try? CastingPlaybackRequest(
            source: URL(string: "https://192.168.1.20/media/song.m4a")!,
            title: "Kitchen Song",
            contentType: "audio/mp4"
        )
        guard let target, let request else {
            return XCTFail("Expected deterministic casting fixtures.")
        }

        let view = CastingView(
            targets: [target],
            states: [target.id: .connected],
            isScanning: false,
            onScan: {},
            onConnect: { _ in },
            onDisconnect: { _ in },
            playbackStates: [target.id: CastingPlaybackSnapshot(state: .playing, title: "Kitchen Song")],
            pendingPlayback: [target.id: request],
            onPlay: { _, _ in },
            onStop: { _ in }
        )
        XCTAssertNotNil(view.body)
    }
}
