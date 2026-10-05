/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The casting protocols represented by this foundation. The closed set keeps
/// arbitrary service types out of discovery and control paths.
enum CastingTargetKind: String, Codable, Sendable, CaseIterable, Hashable {
    case airplay
    case chromecast

    var defaultPort: UInt16 {
        switch self {
        case .airplay:
            return 700
        case .chromecast:
            return 8009
        }
    }
}

/// Opaque, caller-supplied identity for one LAN target. The value is never
/// derived from a name, address, or protocol response.
struct CastingTargetID: Codable, Sendable, Equatable, Hashable, RawRepresentable {
    let rawValue: String

    init?(rawValue: String) {
        guard Self.isValid(rawValue) else { return nil }
        self.rawValue = rawValue
    }

    init(uncheckedRawValue: String) {
        rawValue = uncheckedRawValue
    }

    static func isValid(_ value: String) -> Bool {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed == value, (1...256).contains(trimmed.count) else { return false }
        return !trimmed.contains { character in
            character.isNewline
                || character.unicodeScalars.contains { (scalar: Unicode.Scalar) in scalar.value < 0x20 }
        }
    }
}

/// Sanitized connection state for a discovered target. It intentionally has no
/// free-text associated value and therefore cannot carry a transport payload,
/// credential, address, or provider error description.
enum CastingConnectionState: Codable, Sendable, Equatable, Hashable {
    case disconnected
    case connecting
    case connected
    case disconnecting
    case failed(CastingFailureReason)
}

/// Closed failure categories safe for UI and logs. Raw underlying errors stay
/// inside their injected adapters.
enum CastingFailureReason: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case unavailable
    case timeout
    case unauthorized
    case unsupportedTarget
    case invalidEndpoint
    case transport
    case cancelled
}

/// Closed playback state for a connected target. It never carries protocol
/// identifiers or transport payloads.
enum CastingPlaybackState: Sendable, Equatable, Hashable {
    case idle
    case preparing
    case stopping
    case playing
    case stopped
    case failed(CastingFailureReason)
}

/// A consumer-facing playback snapshot. The optional title is already validated
/// by `CastingPlaybackRequest`; addresses and receiver identifiers are omitted.
struct CastingPlaybackSnapshot: Sendable, Equatable, Hashable {
    let state: CastingPlaybackState
    let title: String?

    init(state: CastingPlaybackState, title: String? = nil) {
        self.state = state
        self.title = title
    }

    static let idle = CastingPlaybackSnapshot(state: .idle)
}

/// One explicit local-media request. Initialization rejects anything except an
/// HTTPS URL whose host is admitted by the same LAN policy as targets.
struct CastingPlaybackRequest: Sendable, Equatable, Hashable {
    let source: URL
    let title: String
    let contentType: String

    init(source: URL, title: String, contentType: String) throws {
        let normalizedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedContentType = contentType.trimmingCharacters(in: .whitespacesAndNewlines)

        guard Self.isValidSource(source),
              Self.isValidTitle(normalizedTitle),
              Self.isValidContentType(normalizedContentType) else {
            throw CastingCoordinatorError.invalidTarget
        }

        self.source = source
        self.title = normalizedTitle
        self.contentType = normalizedContentType.lowercased()
    }

    init(sourceString: String, title: String, contentType: String) throws {
        guard let source = URL(string: sourceString.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw CastingCoordinatorError.invalidTarget
        }
        try self.init(source: source, title: title, contentType: contentType)
    }

    private static func isValidSource(_ source: URL) -> Bool {
        guard source.scheme?.lowercased() == "https",
              let host = source.host?.lowercased(),
              !host.isEmpty,
              source.user == nil,
              source.password == nil else {
            return false
        }
        return CastingTarget.isLANHost(host)
    }

    private static func isValidTitle(_ title: String) -> Bool {
        guard (1...128).contains(title.count) else { return false }
        return !title.contains { (character: Character) in
            character.isNewline
                || character.unicodeScalars.contains { (scalar: Unicode.Scalar) in scalar.value < 0x20 }
        }
    }

    private static func isValidContentType(_ value: String) -> Bool {
        guard (3...255).contains(value.count), value.contains("/") else { return false }
        let allowed = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/;= -._+*!\"'")
        guard value.allSatisfy(allowed.contains) else { return false }

        let components = value.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
        return components.count == 2 && !components[0].isEmpty && !components[1].isEmpty
    }
}

/// Typed errors exposed by the coordinator. Descriptions are fixed text; no
/// underlying error string is propagated through this boundary.
enum CastingCoordinatorError: Error, Sendable, Equatable, Hashable {
    case invalidTarget
    case targetNotFound(CastingTargetID)
    case invalidTransition(CastingConnectionState)
    case discoveryUnavailable
    case controlFailed(CastingFailureReason)
}

extension CastingCoordinatorError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .invalidTarget:
            return "The casting target is not valid."
        case .targetNotFound:
            return "That casting target is not available."
        case .invalidTransition:
            return "That casting action is not available in the current state."
        case .discoveryUnavailable:
            return "Casting target discovery is unavailable."
        case .controlFailed(.unavailable):
            return "The casting target is unavailable."
        case .controlFailed(.timeout):
            return "The casting operation timed out."
        case .controlFailed(.unauthorized):
            return "Permission to control the casting target was denied."
        case .controlFailed(.unsupportedTarget):
            return "The casting target does not support that operation."
        case .controlFailed(.invalidEndpoint):
            return "The casting endpoint is not valid."
        case .controlFailed(.transport):
            return "Communication with the casting target failed."
        case .controlFailed(.cancelled):
            return "The casting operation was cancelled."
        }
    }
}

/// A typed LAN-only AirPlay or Chromecast destination. Initialization rejects
/// public IP literals, non-local hostnames, empty fields, and unsafe ports.
struct CastingTarget: Codable, Sendable, Equatable, Hashable {
    let id: CastingTargetID
    let name: String
    let kind: CastingTargetKind
    let hostOrAddress: String
    let port: UInt16

    init(
        id: CastingTargetID,
        name: String,
        kind: CastingTargetKind,
        hostOrAddress: String,
        port: UInt16? = nil
    ) throws {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedHost = Self.normalizedHost(hostOrAddress)

        guard Self.isValidName(trimmedName),
              Self.isLANHost(normalizedHost),
              let resolvedPort = port.map(Optional.init) ?? kind.defaultPort,
              resolvedPort != 0 else {
            throw CastingCoordinatorError.invalidTarget
        }

        self.id = id
        self.name = trimmedName
        self.kind = kind
        self.hostOrAddress = normalizedHost
        self.port = resolvedPort
    }

    private static func normalizedHost(_ input: String) -> String {
        input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func isValidName(_ name: String) -> Bool {
        guard (1...128).contains(name.count) else { return false }
        return !name.contains { (character: Character) in
            character.isNewline
                || character.unicodeScalars.contains { (scalar: Unicode.Scalar) in scalar.value < 0x20 }
        }
    }

    /// Accepts only local-multicast-DNS names plus RFC 1918, RFC 4193, and
    /// IPv6 link-local literals. Public addresses and ordinary DNS names are
    /// rejected before an adapter ever sees them.
    static func isLANHost(_ host: String) -> Bool {
        if isLocalHostname(host) {
            return true
        }
        if let ipv4 = ipv4Components(host) {
            return isPrivateIPv4(ipv4)
        }
        guard isIPv6Literal(host), let prefix = ipv6Prefix(host) else { return false }
        return ["fe8", "fe9", "fea", "feb", "fc", "fd"].contains(prefix)
    }

    private static func isLocalHostname(_ host: String) -> Bool {
        let base = host.hasSuffix(".") ? String(host.dropLast()) : host
        return base == "local" || base.hasSuffix(".local")
    }

    private static func ipv4Components(_ host: String) -> [Int]? {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4 else { return nil }
        var values: [Int] = []
        for part in parts {
            guard (1...3).contains(part.count),
                  part.allSatisfy(\.isNumber),
                  let value = Int(part),
                  (0...255).contains(value) else {
                return nil
            }
            values.append(value)
        }
        return values
    }

    private static func isPrivateIPv4(_ values: [Int]) -> Bool {
        switch values[0] {
        case 10:
            return true
        case 172:
            return (16...31).contains(values[1])
        case 192:
            return values[1] == 168
        case 169:
            return values[1] == 254
        default:
            return false
        }
    }

    private static func isIPv6Literal(_ host: String) -> Bool {
        // A conservative textual check avoids hostname parsing that could turn
        // a non-LAN name into an endpoint. Adapters still perform local
        // resolution only for values admitted here.
        let allowed = Set("0123456789abcdef:")
        return host.contains(":")
            && host.filter { $0 == ":" }.count >= 2
            && host.allSatisfy(allowed.contains)
    }

    private static func ipv6Prefix(_ host: String) -> String? {
        let withoutZone = host.split(separator: "%", maxSplits: 1).first.map(String.init) ?? host
        return String(expandIPv6(withoutZone).prefix(3))
    }

    private static func expandIPv6(_ input: String) -> String {
        let pieces = input.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        let nonEmptyPieces = pieces.filter { !$0.isEmpty }
        var left = nonEmptyPieces
        var right: [String] = []
        if pieces.contains("") {
            let consumed = left.count + right.count
            let fill = Array(repeating: "0", count: max(0, 8 - consumed))
            left += fill + right
            right = []
        } else {
            right = []
        }

        return left.prefix(8).map { group in
            String(repeating: "0", count: max(0, 4 - group.count)) + group
        }.joined()
    }
}
