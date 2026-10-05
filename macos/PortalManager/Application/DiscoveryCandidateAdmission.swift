/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The trusted, non-secret result of admitting one Bonjour discovery record.
///
/// The service name remains display/discovery metadata only. It is deliberately
/// not an identity or credential key; identity promotion belongs to the
/// bearer-authenticated `/info` flow in the discovery coordinator.
struct AdmittedDiscoveryCandidate: Sendable, Equatable, Hashable {
    let serviceName: String
    let interfaceName: String?
    let endpoint: LANEndpoint
    let trustScope: TrustWarningScope

    var connection: AdmittedConnection {
        AdmittedConnection(endpoint: endpoint, trustScope: trustScope)
    }
}

/// Normalizes untrusted Bonjour metadata and passes it through the shared
/// connection admission boundary.
///
/// `normalize` performs only metadata parsing. `admit` then performs the
/// complete parse -> resolve -> permitted-address selection -> LAN validation
/// -> trust-scope acknowledgement sequence. Neither method probes the Portal
/// or reads a credential. Callers can therefore not accidentally promote an
/// mDNS service name to identity before a later bearer-authenticated `/info`.
struct DiscoveryCandidateNormalizer: Sendable {
    private static let portalProtocol = "http"

    private let connectionAdmission: ConnectionAdmission

    init(connectionAdmission: ConnectionAdmission) {
        self.connectionAdmission = connectionAdmission
    }

    /// Builds a Portal admission request from service metadata without doing
    /// DNS, trust-store, credential, or transport work.
    func normalize(
        _ candidate: DiscoveryCandidate
    ) throws -> ConnectionAdmissionRequest {
        let serviceName = candidate.serviceName
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !serviceName.isEmpty else {
            throw ManagerError.discovery("The Bonjour service metadata is invalid.")
        }

        if let resolutionError = candidate.resolutionError {
            switch resolutionError {
            case .invalidEndpoint:
                throw ManagerError.resolution(.invalidHost)
            case .endpointUnavailable, .timedOut, .connectionFailed:
                throw ManagerError.resolution(.failed)
            }
        }

        guard candidate.port != 0 else {
            throw ManagerError.lanPolicy(.invalidPort)
        }
        guard let rawHost = candidate.resolvedHostOrAddress,
              !rawHost.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ManagerError.resolution(.noAddresses)
        }

        let parsed: LANEndpoint
        do {
            parsed = try ManualEndpointParser(defaultPort: candidate.port).parse(rawHost)
        } catch let error as ManualEndpointParserError {
            throw managerError(for: error)
        } catch {
            throw ManagerError.lanPolicy(.malformedHost)
        }

        // Bonjour carries the service port separately. Do not silently accept
        // a second, conflicting port embedded in an untrusted host value.
        guard parsed.port == candidate.port else {
            throw ManagerError.lanPolicy(.invalidPort)
        }

        let advertisedInterface = candidate.interfaceName?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let interfaceName = advertisedInterface?.isEmpty == true
            ? nil
            : advertisedInterface
        let interfaceZone = try interfaceZone(
            for: parsed,
            advertisedInterface: interfaceName
        )

        let endpoint = LANEndpoint(
            hostOrAddress: parsed.hostOrAddress,
            port: candidate.port,
            addressFamily: parsed.addressFamily,
            interfaceZone: interfaceZone,
            source: .mdns(serviceName: serviceName)
        )

        return ConnectionAdmissionRequest(
            endpoint: endpoint,
            serviceKind: .portal,
            protocolName: Self.portalProtocol
        )
    }

    /// Compatibility spelling for application coordinators that call the
    /// parsed value a request rather than a normalized candidate.
    func normalizedRequest(
        for candidate: DiscoveryCandidate
    ) throws -> ConnectionAdmissionRequest {
        try normalize(candidate)
    }

    /// Resolves and admits a candidate through the shared LAN/trust boundary.
    /// Public or unresolved results fail here, before any later `/info` probe or
    /// Keychain credential read can be attempted by a caller.
    func admit(
        _ candidate: DiscoveryCandidate
    ) async throws -> AdmittedDiscoveryCandidate {
        let request = try normalize(candidate)
        let admitted = try await connectionAdmission.admit(request)
        return AdmittedDiscoveryCandidate(
            serviceName: request.endpoint.mdnsServiceName ?? "",
            interfaceName: candidate.interfaceName?
                .trimmingCharacters(in: .whitespacesAndNewlines),
            endpoint: admitted.endpoint,
            trustScope: admitted.trustScope
        )
    }

    private func interfaceZone(
        for parsed: LANEndpoint,
        advertisedInterface: String?
    ) throws -> String? {
        if let parsedZone = parsed.interfaceZone {
            if let advertisedInterface,
               parsedZone != advertisedInterface {
                throw ManagerError.lanPolicy(.malformedHost)
            }
            return parsedZone
        }

        guard let advertisedInterface else {
            return nil
        }

        switch parsed.addressFamily {
        case .hostname:
            // The resolver may return a link-local IPv6 address later. The
            // shared admission layer uses this as a fallback zone only for
            // that result, and drops it for IPv4/non-link-local addresses.
            return advertisedInterface
        case .ipv4:
            // An interface name from Bonjour is useful metadata but is not an
            // IPv4 scope and must not be attached to the connection endpoint.
            return nil
        case .ipv6:
            // A literal link-local address needs the Bonjour interface name.
            // A valid non-link-local IPv6 destination must remain unscoped.
            do {
                _ = try LANPolicy.validate(parsed)
                return nil
            } catch let error as ManagerError {
                if case .lanPolicy(.missingInterfaceZone) = error {
                    return advertisedInterface
                }
                // Leave public/otherwise invalid literals to the normal
                // admission path so they receive the shared LAN error.
                return nil
            } catch {
                return nil
            }
        }
    }

    private func managerError(
        for error: ManualEndpointParserError
    ) -> ManagerError {
        switch error {
        case .invalidPort:
            return .lanPolicy(.invalidPort)
        default:
            return .lanPolicy(.malformedHost)
        }
    }
}

/// Name used by discovery coordinators that treat normalization and admission
/// as one operation. Keeping the alias avoids a second implementation path.
typealias DiscoveryCandidateAdmission = DiscoveryCandidateNormalizer

private extension LANEndpoint {
    var mdnsServiceName: String? {
        guard case let .mdns(serviceName) = source else { return nil }
        return serviceName
    }
}
