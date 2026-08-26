/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Syntax failures produced while parsing a manually entered endpoint.
///
/// This error deliberately contains no copy of the operator's input. The UI can
/// present a stable, non-secret explanation and leave the original value in its
/// transient input control if it wants to offer correction.
enum ManualEndpointParserError: Error, Codable, Sendable, Equatable, Hashable {
    case emptyInput
    case malformedHost
    case malformedIPv6
    case ambiguousIPv6
    case invalidPort
    case invalidZone
    case bracketedAddressMustBeIPv6

    var sanitizedMessage: String {
        switch self {
        case .emptyInput:
            return "Enter a host or address."
        case .malformedHost:
            return "The host or address is malformed."
        case .malformedIPv6:
            return "The IPv6 address is malformed."
        case .ambiguousIPv6:
            return "An IPv6 address with a port must use brackets."
        case .invalidPort:
            return "The port must be a number from 1 through 65535."
        case .invalidZone:
            return "The IPv6 interface zone is malformed."
        case .bracketedAddressMustBeIPv6:
            return "Only an IPv6 address may use brackets."
        }
    }
}

extension ManualEndpointParserError: LocalizedError {
    var errorDescription: String? { sanitizedMessage }
}

/// Parses the operator-facing `host[:port]` endpoint form without resolving or
/// connecting to the destination.
///
/// The default port is the Portal Fleet Agent port. Callers configuring another
/// local service can inject that service's documented default (for example,
/// 8095 for Music Assistant or 1705 for Snapcast).
struct ManualEndpointParser: Sendable {
    static let defaultPortalPort = LANEndpoint.defaultPortalAgentPort

    let defaultPort: UInt16

    init(defaultPort: UInt16 = ManualEndpointParser.defaultPortalPort) {
        self.defaultPort = defaultPort
    }

    /// Parses a manual endpoint and retains the endpoint source as `.manual` by
    /// default. No LAN admission is performed here; hostnames remain staged as
    /// `.hostname` until a resolver supplies a literal address.
    func parse(
        _ rawInput: String,
        source: EndpointSource = .manual
    ) throws -> LANEndpoint {
        let input = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty else {
            throw ManualEndpointParserError.emptyInput
        }
        guard !containsWhitespaceOrControlCharacter(input) else {
            throw ManualEndpointParserError.malformedHost
        }
        guard defaultPort != 0 else {
            throw ManualEndpointParserError.invalidPort
        }

        let parsed = try parseHostAndPort(input)
        let hostParts = try LANHostParser.splitZone(parsed.host)
        let classification = try LANHostParser.classify(hostParts.host)

        if parsed.wasBracketed {
            guard case .ipv6 = classification else {
                throw ManualEndpointParserError.bracketedAddressMustBeIPv6
            }
        }

        if !parsed.wasBracketed && parsed.colonCount > 1 {
            guard case .ipv6 = classification else {
                throw ManualEndpointParserError.malformedIPv6
            }
            if LANHostParser.hasAmbiguousUnbracketedPort(hostParts.host) {
                throw ManualEndpointParserError.ambiguousIPv6
            }
        }

        if hostParts.zone != nil, case .ipv6 = classification {
            // A zone is retained in `interfaceZone` and is checked against the
            // link-local-only rule by LANPolicy after syntax parsing.
        } else if hostParts.zone != nil {
            throw ManualEndpointParserError.invalidZone
        }

        let port = parsed.port ?? defaultPort
        return LANEndpoint(
            hostOrAddress: hostParts.host,
            port: port,
            addressFamily: classification.addressFamily,
            interfaceZone: hostParts.zone,
            source: source
        )
    }

    /// Convenience form for callers that do not need an instance configured
    /// with a service-specific default port.
    static func parse(
        _ rawInput: String,
        defaultPort: UInt16 = ManualEndpointParser.defaultPortalPort,
        source: EndpointSource = .manual
    ) throws -> LANEndpoint {
        try ManualEndpointParser(defaultPort: defaultPort).parse(rawInput, source: source)
    }

    private struct HostAndPort {
        let host: String
        let port: UInt16?
        let wasBracketed: Bool
        let colonCount: Int
    }

    private func parseHostAndPort(_ input: String) throws -> HostAndPort {
        if input.first == "[" {
            guard let closingBracket = input.firstIndex(of: "]") else {
                throw ManualEndpointParserError.malformedHost
            }

            let hostStart = input.index(after: input.startIndex)
            let host = String(input[hostStart..<closingBracket])
            guard !host.isEmpty else {
                throw ManualEndpointParserError.malformedHost
            }

            let suffixStart = input.index(after: closingBracket)
            let suffix = String(input[suffixStart...])
            let port: UInt16?
            if suffix.isEmpty {
                port = nil
            } else {
                guard suffix.first == ":" else {
                    throw ManualEndpointParserError.malformedHost
                }
                port = try parsePort(String(suffix.dropFirst()))
            }

            return HostAndPort(
                host: host,
                port: port,
                wasBracketed: true,
                colonCount: host.reduce(into: 0) { count, character in
                    if character == ":" { count += 1 }
                }
            )
        }

        guard !input.contains("[") && !input.contains("]") else {
            throw ManualEndpointParserError.malformedHost
        }

        let colonCount = input.reduce(into: 0) { count, character in
            if character == ":" { count += 1 }
        }

        switch colonCount {
        case 0:
            return HostAndPort(host: input, port: nil, wasBracketed: false, colonCount: 0)
        case 1:
            guard let colon = input.firstIndex(of: ":") else {
                throw ManualEndpointParserError.malformedHost
            }
            let host = String(input[..<colon])
            let portText = String(input[input.index(after: colon)...])
            guard !host.isEmpty else {
                throw ManualEndpointParserError.malformedHost
            }
            return HostAndPort(
                host: host,
                port: try parsePort(portText),
                wasBracketed: false,
                colonCount: colonCount
            )
        default:
            // More than one colon is parsed as an IPv6 host with no port. A
            // numeric suffix that could instead be a port is rejected below;
            // an explicit IPv6 host-and-port must use brackets.
            return HostAndPort(host: input, port: nil, wasBracketed: false, colonCount: colonCount)
        }
    }

    private func parsePort(_ text: String) throws -> UInt16 {
        guard !text.isEmpty,
              text.unicodeScalars.allSatisfy({ $0.value >= 48 && $0.value <= 57 }),
              let port = UInt32(text),
              port > 0,
              port <= UInt32(UInt16.max) else {
            throw ManualEndpointParserError.invalidPort
        }
        return UInt16(port)
    }

    private func containsWhitespaceOrControlCharacter(_ input: String) -> Bool {
        input.unicodeScalars.contains {
            CharacterSet.whitespacesAndNewlines.contains($0)
                || CharacterSet.controlCharacters.contains($0)
        }
    }
}

/// A pure, shared LAN admission policy.
///
/// `LANPolicy` validates already-known address values. It does not resolve
/// hostnames, read credentials, display trust warnings, create sockets, or
/// perform network I/O. A parsed hostname therefore remains `.hostname` until
/// a later DNS boundary supplies a literal address to `validate`.
struct LANPolicy: Sendable {
    static let defaultPortalPort = LANEndpoint.defaultPortalAgentPort

    /// Admits a staged endpoint only when its host is already a permitted
    /// literal address. Hostnames are intentionally rejected as unresolved.
    static func validate(_ endpoint: LANEndpoint) throws -> LANEndpoint {
        try validate(
            hostOrAddress: endpoint.hostOrAddress,
            interfaceZone: endpoint.interfaceZone,
            port: endpoint.port,
            source: endpoint.source,
            lastAuthenticatedAt: endpoint.lastAuthenticatedAt
        )
    }

    /// Validates a literal resolved address and builds the reusable endpoint
    /// value used by Portal, discovery, provisioning, and local services.
    static func validate(
        hostOrAddress: String,
        interfaceZone: String? = nil,
        port: UInt16 = LANPolicy.defaultPortalPort,
        source: EndpointSource = .manual,
        lastAuthenticatedAt: Date? = nil
    ) throws -> LANEndpoint {
        guard port != 0 else {
            throw ManagerError.lanPolicy(.invalidPort)
        }

        let hostParts: LANHostParser.HostParts
        do {
            hostParts = try LANHostParser.splitZone(hostOrAddress)
        } catch {
            throw ManagerError.lanPolicy(.malformedHost)
        }

        let zone: String?
        do {
            zone = try LANHostParser.mergedZone(
                embeddedZone: hostParts.zone,
                suppliedZone: interfaceZone
            )
        } catch {
            throw ManagerError.lanPolicy(.malformedHost)
        }

        let classification: LANHostParser.Classification
        do {
            classification = try LANHostParser.classify(hostParts.host)
        } catch {
            throw ManagerError.lanPolicy(.malformedHost)
        }

        switch classification {
        case .hostname:
            // A hostname is syntactically valid, but only a resolver can prove
            // that its current destination is on the permitted LAN.
            throw ManagerError.lanPolicy(.unresolved)
        case .ipv4(let octets):
            guard zone == nil else {
                throw ManagerError.lanPolicy(.unsupportedAddressFamily)
            }
            guard LANHostParser.isPermittedIPv4(octets) else {
                throw ManagerError.lanPolicy(.publicAddress)
            }
            return LANEndpoint(
                hostOrAddress: hostParts.host,
                port: port,
                addressFamily: .ipv4,
                source: source,
                lastAuthenticatedAt: lastAuthenticatedAt
            )
        case .ipv6(let groups):
            let linkLocal = LANHostParser.isIPv6LinkLocal(groups)
            if linkLocal {
                guard let zone, LANHostParser.isValidZone(zone) else {
                    throw ManagerError.lanPolicy(.missingInterfaceZone)
                }
            } else if zone != nil {
                // Interface zones are meaningful only for scoped link-local
                // IPv6 destinations in this LAN policy.
                throw ManagerError.lanPolicy(.unsupportedAddressFamily)
            }

            guard LANHostParser.isPermittedIPv6(groups) else {
                throw ManagerError.lanPolicy(.publicAddress)
            }

            return LANEndpoint(
                hostOrAddress: hostParts.host,
                port: port,
                addressFamily: .ipv6,
                interfaceZone: zone,
                source: source,
                lastAuthenticatedAt: lastAuthenticatedAt
            )
        }
    }

    /// Explicitly named form for DNS/application adapters. It is equivalent
    /// to `validate(hostOrAddress:interfaceZone:port:source:)` and remains a
    /// value-only operation.
    static func validateResolvedAddress(
        _ address: String,
        interfaceZone: String? = nil,
        port: UInt16 = LANPolicy.defaultPortalPort,
        source: EndpointSource = .manual,
        lastAuthenticatedAt: Date? = nil
    ) throws -> LANEndpoint {
        try validate(
            hostOrAddress: address,
            interfaceZone: interfaceZone,
            port: port,
            source: source,
            lastAuthenticatedAt: lastAuthenticatedAt
        )
    }

    /// Convenience bridge for the existing injected DNS port value type. No
    /// resolution is performed by this overload.
    static func validate(
        _ resolvedAddress: ResolvedAddress,
        port: UInt16 = LANPolicy.defaultPortalPort,
        source: EndpointSource = .manual,
        lastAuthenticatedAt: Date? = nil
    ) throws -> LANEndpoint {
        try validate(
            hostOrAddress: resolvedAddress.address,
            interfaceZone: resolvedAddress.interfaceZone,
            port: port,
            source: source,
            lastAuthenticatedAt: lastAuthenticatedAt
        )
    }

    static func isPermitted(
        _ address: String,
        interfaceZone: String? = nil
    ) -> Bool {
        (try? validateResolvedAddress(address, interfaceZone: interfaceZone)) != nil
    }

    static func isPermitted(_ address: ResolvedAddress) -> Bool {
        (try? validate(address)) != nil
    }
}

/// Internal, allocation-light address syntax helpers shared by the parser and
/// policy. They implement literal IPv4/IPv6 parsing without using Network,
/// DNS, or any socket API, so the domain boundary remains deterministic.
fileprivate enum LANHostParser {
    struct HostParts {
        let host: String
        let zone: String?
    }

    enum Classification {
        case ipv4([UInt8])
        case ipv6([UInt16])
        case hostname

        var addressFamily: AddressFamily {
            switch self {
            case .ipv4: return .ipv4
            case .ipv6: return .ipv6
            case .hostname: return .hostname
            }
        }
    }

    static func splitZone(_ value: String) throws -> HostParts {
        guard !value.isEmpty else {
            throw ManualEndpointParserError.emptyInput
        }

        let percentIndices = value.indices.filter { value[$0] == "%" }
        guard percentIndices.count <= 1 else {
            throw ManualEndpointParserError.invalidZone
        }

        guard let percent = percentIndices.first else {
            return HostParts(host: value, zone: nil)
        }

        let host = String(value[..<percent])
        var rawZone = String(value[value.index(after: percent)...])
        // URI-form bracketed IPv6 uses `%25` for the literal percent separator.
        if rawZone.hasPrefix("25") {
            rawZone.removeFirst(2)
        }
        guard !host.isEmpty, isValidZone(rawZone) else {
            throw ManualEndpointParserError.invalidZone
        }
        return HostParts(host: host, zone: rawZone)
    }

    static func mergedZone(
        embeddedZone: String?,
        suppliedZone: String?
    ) throws -> String? {
        if let suppliedZone, !isValidZone(suppliedZone) {
            throw ManualEndpointParserError.invalidZone
        }
        guard let embeddedZone else {
            return suppliedZone
        }
        guard let suppliedZone else {
            return embeddedZone
        }
        guard embeddedZone == suppliedZone else {
            throw ManualEndpointParserError.invalidZone
        }
        return embeddedZone
    }

    static func isValidZone(_ zone: String) -> Bool {
        guard !zone.isEmpty else { return false }
        return zone.unicodeScalars.allSatisfy { scalar in
            switch scalar.value {
            case 48...57, 65...90, 97...122, 45, 46, 95, 126:
                return true
            default:
                return false
            }
        }
    }

    static func classify(_ host: String) throws -> Classification {
        guard !host.isEmpty,
              !host.contains("["),
              !host.contains("]"),
              !host.contains("%"),
              !containsWhitespaceOrControlCharacter(host) else {
            throw ManualEndpointParserError.malformedHost
        }

        if let octets = parseIPv4(host) {
            return .ipv4(octets)
        }
        if let groups = parseIPv6(host) {
            return .ipv6(groups)
        }

        if host.contains(":") {
            throw ManualEndpointParserError.malformedIPv6
        }
        if looksLikeMalformedIPv4(host) || !isHostname(host) {
            throw ManualEndpointParserError.malformedHost
        }
        return .hostname
    }

    static func hasAmbiguousUnbracketedPort(_ host: String) -> Bool {
        guard let colon = host.lastIndex(of: ":") else { return false }
        let suffix = String(host[host.index(after: colon)...])
        guard isPortText(suffix) else { return false }
        let prefix = String(host[..<colon])
        guard !prefix.isEmpty, !prefix.hasSuffix(":") else { return false }
        return parseIPv6(prefix) != nil
    }

    static func isPermittedIPv4(_ octets: [UInt8]) -> Bool {
        guard octets.count == 4 else { return false }
        let first = octets[0]
        let second = octets[1]

        if first == 127 { return true } // IPv4 loopback.
        if first == 10 { return true } // RFC 1918.
        if first == 172 && (16...31).contains(second) { return true } // RFC 1918.
        if first == 192 && second == 168 { return true } // RFC 1918.
        if first == 169 && second == 254 { return true } // IPv4 link-local.
        return false
    }

    static func isPermittedIPv6(_ groups: [UInt16]) -> Bool {
        isIPv6Loopback(groups) || isIPv6UniqueLocal(groups) || isIPv6LinkLocal(groups)
    }

    static func isIPv6Loopback(_ groups: [UInt16]) -> Bool {
        groups.count == 8
            && groups.dropLast().allSatisfy { $0 == 0 }
            && groups.last == 1
    }

    static func isIPv6UniqueLocal(_ groups: [UInt16]) -> Bool {
        guard let first = groups.first else { return false }
        return (first & 0xfe00) == 0xfc00
    }

    static func isIPv6LinkLocal(_ groups: [UInt16]) -> Bool {
        guard let first = groups.first else { return false }
        return (first & 0xffc0) == 0xfe80
    }

    private static func parseIPv4(_ value: String) -> [UInt8]? {
        let components = value.split(separator: ".", omittingEmptySubsequences: false)
        guard components.count == 4 else { return nil }

        var octets: [UInt8] = []
        octets.reserveCapacity(4)
        for component in components {
            guard !component.isEmpty,
                  component.count <= 3,
                  component.unicodeScalars.allSatisfy({ $0.value >= 48 && $0.value <= 57 }),
                  let number = UInt16(component),
                  number <= UInt16(UInt8.max) else {
                return nil
            }
            octets.append(UInt8(number))
        }
        return octets
    }

    private static func parseIPv6(_ value: String) -> [UInt16]? {
        guard !value.isEmpty else { return nil }

        let sections = value.components(separatedBy: "::")
        guard sections.count <= 2 else { return nil }

        if sections.count == 2 {
            guard let leftGroups = parseIPv6Section(sections[0]),
                  let rightGroups = parseIPv6Section(sections[1]),
                  leftGroups.count + rightGroups.count < 8 else {
                return nil
            }
            return leftGroups
                + Array(repeating: UInt16(0), count: 8 - leftGroups.count - rightGroups.count)
                + rightGroups
        }

        guard let groups = parseIPv6Section(sections[0]), groups.count == 8 else { return nil }
        return groups
    }

    private static func parseIPv6Section(_ section: String) -> [UInt16]? {
        guard !section.isEmpty else { return [] }
        let components = section.split(separator: ":", omittingEmptySubsequences: false)
        guard !components.contains(where: { $0.isEmpty }) else { return nil }

        var groups: [UInt16] = []
        groups.reserveCapacity(components.count + 1)
        for (index, component) in components.enumerated() {
            if component.contains(".") {
                guard index == components.count - 1,
                      let octets = parseIPv4(String(component)) else {
                    return nil
                }
                groups.append((UInt16(octets[0]) << 8) | UInt16(octets[1]))
                groups.append((UInt16(octets[2]) << 8) | UInt16(octets[3]))
                continue
            }

            guard component.count <= 4,
                  !component.isEmpty,
                  component.unicodeScalars.allSatisfy(isHexScalar),
                  let number = UInt16(component, radix: 16) else {
                return nil
            }
            groups.append(number)
        }
        return groups
    }

    private static func isHexScalar(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 48...57, 65...70, 97...102:
            return true
        default:
            return false
        }
    }

    private static func isPortText(_ value: String) -> Bool {
        guard !value.isEmpty,
              value.unicodeScalars.allSatisfy({ $0.value >= 48 && $0.value <= 57 }),
              let number = UInt32(value) else {
            return false
        }
        return number > 0 && number <= UInt32(UInt16.max)
    }

    private static func looksLikeMalformedIPv4(_ value: String) -> Bool {
        value.contains(".")
            && value.unicodeScalars.allSatisfy { scalar in
                (scalar.value >= 48 && scalar.value <= 57) || scalar.value == 46
            }
    }

    private static func isHostname(_ value: String) -> Bool {
        guard value.utf8.count <= 253 else { return false }
        let withoutTrailingDot = value.hasSuffix(".") ? String(value.dropLast()) : value
        guard !withoutTrailingDot.isEmpty else { return false }

        let labels = withoutTrailingDot.split(separator: ".", omittingEmptySubsequences: false)
        guard !labels.isEmpty else { return false }
        for label in labels {
            guard !label.isEmpty,
                  label.utf8.count <= 63,
                  !label.hasPrefix("-"),
                  !label.hasSuffix("-") else {
                return false
            }
            guard label.unicodeScalars.allSatisfy({ scalar in
                switch scalar.value {
                case 48...57, 65...90, 97...122, 45:
                    return true
                default:
                    return false
                }
            }) else {
                return false
            }
        }
        return true
    }

    private static func containsWhitespaceOrControlCharacter(_ input: String) -> Bool {
        input.unicodeScalars.contains {
            CharacterSet.whitespacesAndNewlines.contains($0)
                || CharacterSet.controlCharacters.contains($0)
        }
    }
}
