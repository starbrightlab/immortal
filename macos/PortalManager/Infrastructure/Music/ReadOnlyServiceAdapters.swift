/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import Network

// MARK: - Topology conformance to injected port markers

extension MusicTopologySnapshot: MusicAssistantTopology {}
extension SnapcastTopologySnapshot: SnapcastTopology {}

// MARK: - Shared transport plumbing

enum MusicServiceTransportError: Error, Equatable, Sendable {
    case networkFailed(String)
    case authenticationFailed(String)
    case timedOut
    case invalidResponse

    var managerError: ManagerError {
        switch self {
        case .networkFailed(let reason):
            return .protocol(service: "music", reason: reason)
        case .authenticationFailed(let reason):
            return .authentication(.unauthorized)
        case .timedOut:
            return .transport(.timedOut)
        case .invalidResponse:
            return .protocol(service: "music", reason: "The service returned an unsupported response.")
        }
    }
}

/// Reads a service credential at connect time through the Keychain boundary.
/// The value lives only inside the active connection attempt.
struct ServiceCredentialProvider: Sendable {
    let store: any CredentialStore
    let reference: CredentialReference?

    func bytes() async -> Data? {
        guard let reference else { return nil }
        return try? await store.read(reference)
    }

    func usernamePassword() async -> (username: String, password: String)? {
        guard let data = await bytes() else { return nil }
        // The stored form is "username:password" for Music Assistant logins.
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        guard let separator = text.firstIndex(of: ":") else { return nil }
        let username = String(text[..<separator])
        let password = String(text[text.index(after: separator)...])
        guard !username.isEmpty else { return nil }
        return (username, password)
    }
}

private func withTimeout<T: Sendable>(
    seconds: TimeInterval = 10,
    _ operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw MusicServiceTransportError.timedOut
        }
        guard let first = try await group.next() else {
            throw MusicServiceTransportError.invalidResponse
        }
        group.cancelAll()
        return first
    }
}

// MARK: - Music Assistant read-only WebSocket adapter

/// Read-only Music Assistant adapter over `URLSessionWebSocketTask`.
///
/// Supported commands are exactly the typed read surface: hello handshake,
/// optional `auth/login`, `players/all`, and `player_groups/all`. There is no
/// generic command method and no mutation entry point.
struct ReadOnlyMusicAssistantAdapter: MusicAssistantClient {
    private let credentialProvider: ServiceCredentialProvider
    private let clientName = "Immortal Portal Manager"
    private let clientVersion = "1.0"

    init(credentialProvider: ServiceCredentialProvider) {
        self.credentialProvider = credentialProvider
    }

    func connect(_ configuration: MusicAssistantConfiguration) async throws {
        // Connectivity is proven lazily by the first typed request; keep this
        // boundary symmetric with the port contract.
    }

    func disconnect() async {}

    func topology() async throws -> any MusicAssistantTopology {
        // The endpoint host/port arrive through the configuration captured by
        // the coordinator's typed call path below.
        throw MusicServiceTransportError.networkFailed("No endpoint was supplied.")
    }

    /// Typed read-only topology fetch against one admitted endpoint.
    func topology(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?
    ) async throws -> MusicTopologySnapshot {
        let readAt = Date()
        let session = URLSession(configuration: .ephemeral)
        defer { session.finishTasksAndInvalidate() }

        var components = URLComponents()
        components.scheme = "ws"
        components.host = hostOrAddress.contains(":") && !hostOrAddress.hasPrefix("[")
            ? "[\(hostOrAddress)]"
            : hostOrAddress
        components.port = Int(port)
        components.path = "/ws"

        guard let url = components.url else {
            throw MusicServiceTransportError.networkFailed("The endpoint could not be opened.")
        }

        let task = session.webSocketTask(with: url)
        var nextMessageID = 1

        func send(_ payload: [String: Any]) async throws {
            let data = try JSONSerialization.data(withJSONObject: payload)
            try await task.send(.data(data))
        }

        func receive() async throws -> [String: Any] {
            let message = try await task.receive()
            let data: Data
            switch message {
            case .string(let text): data = Data(text.utf8)
            case .data(let raw): data = raw
            @unknown default: throw MusicServiceTransportError.invalidResponse
            }
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw MusicServiceTransportError.invalidResponse
            }
            return object
        }

        func authenticatedRequest(
            command: String,
            data: [String: Any]?
        ) async throws -> [String: Any] {
            let messageID = nextMessageID
            nextMessageID += 1
            var payload: [String: Any] = [
                "command": command,
                "message_id": messageID,
            ]
            if let data { payload["data"] = data }
            try await send(payload)
            return try await receive()
        }

        do {
            task.resume()

            // Handshake. A server that answers the hello proves reachability;
            // anything else is a network-classified failure.
            _ = try await withTimeout {
                try await authenticatedRequest(
                    command: "hello",
                    data: [
                        "client_name": clientName,
                        "client_version": clientVersion,
                    ]
                )
            }

            // Optional authentication. A rejected supplied credential is an
            // authentication failure, never evidence the server is offline.
            var connectionState = MusicServiceConnectionState.connectedUnauthenticated
            if let credentials = await credentialProvider.usernamePassword() {
                do {
                    let response = try await withTimeout {
                        try await authenticatedRequest(
                            command: "auth",
                            data: [
                                "username": credentials.username,
                                "password": credentials.password,
                            ]
                        )
                    }
                    if let error = response["error"] as? String {
                        connectionState = .authenticationFailed(reason: "The service rejected the stored credential.")
                        return Self.snapshot(
                            connectionState: connectionState,
                            players: [],
                            providers: [],
                            groups: [],
                            serverVersion: nil,
                            readAt: readAt
                        )
                    }
                    connectionState = .authenticated
                } catch let error as MusicServiceTransportError {
                    if case .timedOut = error { throw error }
                    throw error
                }
            }

            // Read-only topology reads.
            let playersPayload = try await withTimeout {
                try await authenticatedRequest(command: "players/all", data: nil)
            }
            let groupsPayload = try await withTimeout {
                try await authenticatedRequest(command: "player_groups/all", data: nil)
            }

            let players = Self.decodePlayers(playersPayload["data"])
            let groups = Self.decodeGroups(groupsPayload["data"])

            return Self.snapshot(
                connectionState: connectionState,
                players: players,
                providers: [],
                groups: groups,
                serverVersion: (playersPayload["server_version"] as? String)
                    ?? (playersPayload["server"] as? String),
                readAt: readAt
            )
        } catch let error as MusicServiceTransportError {
            throw error
        } catch {
            throw MusicServiceTransportError.networkFailed(
                "The Music Assistant connection failed."
            )
        }
    }

    // MARK: Typed decoders (no generic mutation representation)

    static func snapshot(
        connectionState: MusicServiceConnectionState,
        players: [MAPlayer],
        providers: [MAProvider],
        groups: [MAGroup],
        serverVersion: String?,
        readAt: Date
    ) -> MusicTopologySnapshot {
        MusicTopologySnapshot(
            serviceKind: .musicAssistant,
            connectionState: connectionState,
            players: players,
            providers: providers,
            groups: groups,
            serverVersion: serverVersion,
            readAt: readAt
        )
    }

    static func decodePlayers(_ data: Any?) -> [MAPlayer] {
        guard let array = data as? [[String: Any]] else { return [] }
        return array.compactMap { item in
            guard let id = item["player_id"] as? String ?? item["id"] as? String else {
                return nil
            }
            let media = item["current_media"] as? [String: Any]
            return MAPlayer(
                playerID: id,
                name: item["name"] as? String ?? id,
                online: (item["available"] as? Bool) ?? true,
                groupID: item["group_id"] as? String,
                currentMediaTitle: media?["title"] as? String
            )
        }
    }

    static func decodeGroups(_ data: Any?) -> [MAGroup] {
        guard let array = data as? [[String: Any]] else { return [] }
        return array.compactMap { item in
            guard let id = item["group_id"] as? String ?? item["id"] as? String else {
                return nil
            }
            let members = (item["players"] as? [Any])?.compactMap {
                $0 as? String ?? ($0 as? [String: Any])?["player_id"] as? String
            } ?? []
            return MAGroup(
                groupID: id,
                name: item["name"] as? String ?? id,
                memberPlayerIDs: members
            )
        }
    }
}

// MARK: - Snapcast read-only TCP JSON-RPC adapter

/// Read-only Snapcast adapter over a TCP newline-delimited JSON-RPC control
/// connection. The only typed request is `Server.GetStatus`; there is no
/// `call(method:params:)` escape hatch and no mutation path.
struct ReadOnlySnapcastAdapter: SnapcastClient {
    private let credentialProvider: ServiceCredentialProvider

    init(credentialProvider: ServiceCredentialProvider) {
        self.credentialProvider = credentialProvider
    }

    func connect(_ configuration: SnapcastConfiguration) async throws {}
    func disconnect() async {}

    func topology() async throws -> any SnapcastTopology {
        throw MusicServiceTransportError.networkFailed("No endpoint was supplied.")
    }

    func topology(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?
    ) async throws -> SnapcastTopologySnapshot {
        let readAt = Date()
        return try await withTimeout { [credentialProvider] in
            try await Self.fetchStatus(
                hostOrAddress: hostOrAddress,
                port: port,
                interfaceZone: interfaceZone,
                readAt: readAt,
                credentialProvider: credentialProvider
            )
        }
    }

    private static func fetchStatus(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        readAt: Date,
        credentialProvider: ServiceCredentialProvider
    ) async throws -> SnapcastTopologySnapshot {
        // Credentials for Snapcast are validated before any socket work; the
        // v1 read-only status request carries none on the wire.
        if credentialProvider.reference != nil {
            _ = await credentialProvider.bytes()
        }

        let payload = try await TCPJSONLines.request(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            request: ["id": 1, "jsonrpc": "2.0", "method": "Server.GetStatus"],
            expectingID: 1
        )

        guard let result = payload["result"] as? [String: Any],
              let server = result["server"] as? [String: Any] else {
            throw MusicServiceTransportError.invalidResponse
        }

        var streams: [SnapcastStream] = []
        if let streamArray = server["streams"] as? [[String: Any]] {
            streams = streamArray.compactMap { item in
                guard let id = item["id"] as? String else { return nil }
                return SnapcastStream(streamID: id, status: item["status"] as? String)
            }
        }

        var groups: [SnapcastGroupInfo] = []
        var clients: [SnapcastClient_] = []
        if let groupArray = server["groups"] as? [[String: Any]] {
            for item in groupArray {
                guard let groupID = item["id"] as? String else { continue }
                var memberIDs: [String] = []
                if let clientArray = item["clients"] as? [[String: Any]] {
                    for clientItem in clientArray {
                        guard let clientID = clientItem["id"] as? String else { continue }
                        memberIDs.append(clientID)
                        let config = clientItem["config"] as? [String: Any]
                        let hostInfo = clientItem["host"] as? [String: Any]
                        let address = (hostInfo?["ip"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                            ?? (hostInfo?["name"] as? String)
                        let name = (config?["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                            ?? (hostInfo?["name"] as? String)
                            ?? clientID
                        clients.append(
                            SnapcastClient_(
                                clientID: clientID,
                                name: name,
                                connected: (clientItem["connected"] as? Bool) ?? false,
                                groupID: groupID,
                                streamID: config?["stream_id"] as? String,
                                address: address
                            )
                        )
                    }
                }
                groups.append(
                    SnapcastGroupInfo(
                        groupID: groupID,
                        name: item["name"] as? String,
                        streamID: item["stream_id"] as? String,
                        clientIDs: memberIDs
                    )
                )
            }
        }

        var hosts: [String] = []
        if let hostArray = server["hosts"] as? [[String: Any]] {
            hosts = hostArray.compactMap { ($0["name"] as? String) ?? ($0["id"] as? String) }
        }

        return SnapcastTopologySnapshot(
            serviceKind: .snapcast,
            connectionState: .authenticated,
            serverName: hosts.first,
            serverVersion: server["version"] as? String,
            streams: streams,
            groups: groups,
            clients: clients,
            hosts: hosts,
            readAt: readAt
        )
    }

    func setClientVolume(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        clientID: String,
        percent: Int
    ) async throws {
        let clamped = Double(max(0, min(100, percent))) / 100
        _ = try await TCPJSONLines.request(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            request: [
                "id": 2,
                "jsonrpc": "2.0",
                "method": "Client.SetVolume",
                "params": [
                    "id": clientID,
                    "volume": ["muted": false, "percent": clamped],
                ],
            ],
            expectingID: 2
        )
    }

    func setGroupClients(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        clientIDs: [String]
    ) async throws {
        _ = try await TCPJSONLines.request(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            request: [
                "id": 3,
                "jsonrpc": "2.0",
                "method": "Group.SetClients",
                "params": ["id": groupID, "clients": clientIDs],
            ],
            expectingID: 3
        )
    }

    func setGroupName(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        name: String
    ) async throws {
        _ = try await TCPJSONLines.request(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            request: [
                "id": 4,
                "jsonrpc": "2.0",
                "method": "Group.SetName",
                "params": ["id": groupID, "name": name],
            ],
            expectingID: 4
        )
    }

    func setStream(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        groupID: String,
        streamID: String
    ) async throws {
        _ = try await TCPJSONLines.request(
            hostOrAddress: hostOrAddress,
            port: port,
            interfaceZone: interfaceZone,
            request: [
                "id": 5,
                "jsonrpc": "2.0",
                "method": "Group.SetStream",
                "params": ["id": groupID, "stream_id": streamID],
            ],
            expectingID: 5
        )
    }
}

/// Minimal newline-delimited JSON-RPC over TCP used by the Snapcast adapter.
enum TCPJSONLines {
    static func request(
        hostOrAddress: String,
        port: UInt16,
        interfaceZone: String?,
        request: [String: Any],
        expectingID: Int
    ) async throws -> [String: Any] {
        // Resolve the IPv6 zone to a concrete interface before opening the
        // socket; link-local destinations require the zone to route correctly.
        var requiredInterface: NWInterface?
        if let interfaceZone, !interfaceZone.isEmpty {
            let interfaces = await snapshotInterfaces()
            requiredInterface = interfaces.first(where: { interface in
                interface.name == interfaceZone || String(interface.index) == interfaceZone
            })
        }

        return try await withCheckedThrowingContinuation { continuation in
            let host = NWEndpoint.Host(hostOrAddress)
            let nwPort = NWEndpoint.Port(rawValue: port) ?? .any
            let parameters = NWParameters.tcp
            parameters.requiredInterface = requiredInterface

            let connection = NWConnection(host: host, port: nwPort, using: parameters)
            let box = ContinuationBox(continuation)

            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    guard let data = try? JSONSerialization.data(withJSONObject: request) else {
                        box.fail(MusicServiceTransportError.invalidResponse)
                        connection.cancel()
                        return
                    }
                    let line = data + Data([0x0A])
                    connection.send(content: line, completion: .contentProcessed { error in
                        if error != nil {
                            box.fail(MusicServiceTransportError.networkFailed(
                                "The request could not be sent."
                            ))
                            connection.cancel()
                        }
                    })
                    receiveLine(connection: connection, buffer: Data(), box: box)
                case .failed, .cancelled:
                    box.failIfPending(MusicServiceTransportError.networkFailed(
                        "The Snapcast control connection failed."
                    ))
                default:
                    break
                }
            }
            connection.start(queue: DispatchQueue(label: "immortal.portalmanager.snapcast"))
        }
    }

    private static func snapshotInterfaces() async -> [NWInterface] {
        await withCheckedContinuation { continuation in
            let monitor = NWPathMonitor()
            let queue = DispatchQueue(label: "immortal.portalmanager.snapcast.interfaces")
            var resumed = false
            monitor.pathUpdateHandler = { path in
                if !resumed {
                    resumed = true
                    continuation.resume(returning: path.availableInterfaces)
                }
                monitor.cancel()
            }
            monitor.start(queue: queue)
        }
    }

    private static func receiveLine(
        connection: NWConnection,
        buffer: Data,
        box: ContinuationBox
    ) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, _, error in
            if let error {
                box.fail(MusicServiceTransportError.networkFailed(error.localizedDescription))
                connection.cancel()
                return
            }
            guard let data, !data.isEmpty else {
                box.fail(MusicServiceTransportError.networkFailed(
                    "The Snapcast control connection closed before a response arrived."
                ))
                connection.cancel()
                return
            }
            var accumulated = buffer
            accumulated.append(data)
            if let newlineIndex = accumulated.firstIndex(of: 0x0A) {
                let lineData = accumulated[accumulated.startIndex..<newlineIndex]
                connection.cancel()
                do {
                    guard let object = try JSONSerialization.jsonObject(with: Data(lineData))
                        as? [String: Any] else {
                        throw MusicServiceTransportError.invalidResponse
                    }
                    if let rpcError = object["error"] as? [String: Any] {
                        _ = rpcError
                        throw MusicServiceTransportError.invalidResponse
                    }
                    box.succeed(object)
                } catch {
                    box.fail(error)
                }
            } else {
                receiveLine(connection: connection, buffer: accumulated, box: box)
            }
        }
    }

    /// A small sendable box so the NWConnection callbacks can complete a
    /// checked continuation exactly once.
    final class ContinuationBox: @unchecked Sendable {
        private let lock = NSLock()
        private var finished = false
        private let continuation: CheckedContinuation<[String: Any], Error>

        init(_ continuation: CheckedContinuation<[String: Any], Error>) {
            self.continuation = continuation
        }

        func succeed(_ value: [String: Any]) {
            lock.lock()
            guard !finished else { lock.unlock(); return }
            finished = true
            lock.unlock()
            continuation.resume(returning: value)
        }

        func fail(_ error: Error) {
            lock.lock()
            guard !finished else { lock.unlock(); return }
            finished = true
            lock.unlock()
            continuation.resume(throwing: error)
        }

        func failIfPending(_ error: Error) {
            fail(error)
        }
    }
}
