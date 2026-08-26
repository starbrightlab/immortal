/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A parsed, service-specific connection target. The endpoint may still contain
/// a hostname; DNS resolution and LAN admission happen only in
/// `ConnectionAdmission`.
struct ConnectionAdmissionRequest: Sendable, Equatable, Hashable {
    let endpoint: LANEndpoint
    let serviceKind: ServiceKind
    let protocolName: String

    init(
        endpoint: LANEndpoint,
        serviceKind: ServiceKind,
        protocolName: String
    ) {
        self.endpoint = endpoint
        self.serviceKind = serviceKind
        self.protocolName = protocolName
    }

    /// Parses a host or address without resolving or connecting to it. The
    /// default port is the Portal Agent port; callers for Music Assistant or
    /// Snapcast provide their service-specific default.
    init(
        rawEndpoint: String,
        serviceKind: ServiceKind,
        protocolName: String,
        defaultPort: UInt16 = LANEndpoint.defaultPortalAgentPort,
        source: EndpointSource = .manual
    ) throws {
        self.init(
            endpoint: try ManualEndpointParser(defaultPort: defaultPort).parse(
                rawEndpoint,
                source: source
            ),
            serviceKind: serviceKind,
            protocolName: protocolName
        )
    }
}

/// A destination that has passed parsing, resolution, LAN policy, and trust
/// acknowledgement. Only this value should be handed to a request or socket
/// adapter.
struct AdmittedConnection: Sendable, Equatable, Hashable {
    let endpoint: LANEndpoint
    let trustScope: TrustWarningScope
}

/// The local-network warning is deliberately represented separately from
/// transport and authentication failures so a UI can explain the required
/// acknowledgement without exposing a credential or opening a connection.
enum ConnectionAdmissionError: Error, LocalizedError, Sendable, Equatable, Hashable {
    case trustWarningRequired(scope: TrustWarningScope)

    var errorDescription: String? {
        "A local-network trust acknowledgement is required before connecting."
    }

    var sanitizedMessage: String {
        errorDescription ?? "A local-network trust acknowledgement is required before connecting."
    }
}

/// Selects one address from a DNS result without allowing a public address to
/// become the connection target.
protocol ResolvedAddressSelector: Sendable {
    func select(
        from addresses: [ResolvedAddress],
        port: UInt16
    ) throws -> ResolvedAddress
}

/// The production selection policy preserves resolver ordering while choosing
/// only addresses accepted by the shared LAN policy. The selected value is
/// validated again by `ConnectionAdmission` after the selector returns so an
/// injected adapter cannot bypass the policy boundary.
struct PermittedAddressSelector: ResolvedAddressSelector, Sendable {
    func select(
        from addresses: [ResolvedAddress],
        port: UInt16
    ) throws -> ResolvedAddress {
        guard !addresses.isEmpty else {
            throw ManagerError.resolution(.noAddresses)
        }

        for address in addresses {
            if (try? LANPolicy.validate(address, port: port)) != nil {
                return address
            }
        }

        throw ManagerError.lanPolicy(.noPermittedAddress)
    }
}

/// Performs the non-UI, non-transport admission sequence shared by Portal,
/// Music Assistant, Snapcast, discovery, manual endpoints, provisioning
/// verification, and reconnects.
///
/// Every `admit` call resolves a hostname again and checks LAN policy again.
/// There is intentionally no endpoint cache: a previously acknowledged scope
/// never bypasses DNS or a new LAN validation result.
struct ConnectionAdmission: Sendable {
    private let dnsResolver: any DNSResolver
    private let addressSelector: any ResolvedAddressSelector
    private let trustWarningStore: any TrustWarningStore

    init(
        dnsResolver: any DNSResolver,
        addressSelector: any ResolvedAddressSelector = PermittedAddressSelector(),
        trustWarningStore: any TrustWarningStore
    ) {
        self.dnsResolver = dnsResolver
        self.addressSelector = addressSelector
        self.trustWarningStore = trustWarningStore
    }

    /// Admits an already parsed endpoint. Hostnames are resolved through the
    /// injected resolver; literal addresses are validated without a DNS call.
    /// Trust acknowledgement is checked only after the resolved address has
    /// passed LAN policy.
    func admit(_ request: ConnectionAdmissionRequest) async throws -> AdmittedConnection {
        let endpoint = try await resolveAndValidate(request.endpoint)
        let scope = TrustWarningScope(
            serviceKind: request.serviceKind,
            protocolName: request.protocolName,
            resolvedHostOrAddress: endpoint.hostOrAddress,
            port: endpoint.port,
            interfaceZone: endpoint.interfaceZone
        )

        guard try await trustWarningStore.isAcknowledged(scope) else {
            throw ConnectionAdmissionError.trustWarningRequired(scope: scope)
        }

        return AdmittedConnection(endpoint: endpoint, trustScope: scope)
    }

    /// Parses and admits an operator/discovery supplied endpoint in one
    /// operation. Parsing remains before DNS resolution and before any trust,
    /// credential, or request/socket boundary.
    func admit(
        rawEndpoint: String,
        serviceKind: ServiceKind,
        protocolName: String,
        defaultPort: UInt16 = LANEndpoint.defaultPortalAgentPort,
        source: EndpointSource = .manual
    ) async throws -> AdmittedConnection {
        let request = try ConnectionAdmissionRequest(
            rawEndpoint: rawEndpoint,
            serviceKind: serviceKind,
            protocolName: protocolName,
            defaultPort: defaultPort,
            source: source
        )
        return try await admit(request)
    }

    /// Reconnects through the same complete admission path. In particular, it
    /// does not reuse an earlier resolved address or trust-scope decision.
    func reconnect(_ request: ConnectionAdmissionRequest) async throws -> AdmittedConnection {
        try await admit(request)
    }

    /// Records only the non-secret scope after the operator has accepted the
    /// local-network warning. Callers normally obtain the scope from
    /// `ConnectionAdmissionError.trustWarningRequired` and then retry `admit`.
    func acknowledgeTrust(
        for scope: TrustWarningScope,
        at date: Date
    ) async throws {
        try await trustWarningStore.acknowledge(scope.normalized, at: date)
    }

    /// Runs a request/socket operation only after all admission checks have
    /// completed. When a credential reference is supplied, the Keychain read
    /// occurs after DNS, LAN validation, scope lookup, and acknowledgement;
    /// the operation closure is invoked only after that read succeeds.
    func withAdmittedConnection<Result: Sendable>(
        _ request: ConnectionAdmissionRequest,
        credentialReference: CredentialReference? = nil,
        credentialStore: (any CredentialStore)? = nil,
        operation: @escaping @Sendable (AdmittedConnection, Data?) async throws -> Result
    ) async throws -> Result {
        let admitted = try await admit(request)
        let credential: Data?

        if let credentialReference {
            guard let credentialStore else {
                throw ManagerError.keychain(.unavailable)
            }

            do {
                guard let value = try await credentialStore.read(credentialReference) else {
                    throw ManagerError.keychain(.missing)
                }
                credential = value
            } catch let error as ManagerError {
                throw error
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                throw ManagerError.keychain(.readFailed)
            }
        } else {
            credential = nil
        }

        return try await operation(admitted, credential)
    }

    private func resolveAndValidate(_ endpoint: LANEndpoint) async throws -> LANEndpoint {
        guard endpoint.port != 0 else {
            throw ManagerError.lanPolicy(.invalidPort)
        }

        if endpoint.addressFamily != .hostname {
            return try LANPolicy.validate(
                ResolvedAddress(
                    address: endpoint.hostOrAddress,
                    interfaceZone: endpoint.interfaceZone
                ),
                port: endpoint.port,
                source: endpoint.source,
                lastAuthenticatedAt: endpoint.lastAuthenticatedAt
            )
        }

        let result: DNSResolutionResult
        do {
            result = try await dnsResolver.resolve(
                DNSResolutionRequest(hostname: endpoint.hostOrAddress)
            )
        } catch let error as ManagerError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ManagerError.resolution(.failed)
        }

        // Bonjour may provide the interface name separately from a hostname
        // or literal IPv6 address. Supply that zone only to link-local DNS
        // results that genuinely need it; attaching a zone to IPv4 or
        // non-link-local IPv6 would incorrectly reject an otherwise permitted
        // address. The selector still applies the shared LAN policy to every
        // candidate before one can be selected.
        let selectableAddresses = result.addresses.map { address in
            guard address.interfaceZone == nil,
                  let fallbackZone = endpoint.interfaceZone else {
                return address
            }

            do {
                _ = try LANPolicy.validate(address, port: endpoint.port)
                return address
            } catch let error as ManagerError {
                guard case .lanPolicy(.missingInterfaceZone) = error else {
                    return address
                }
                return ResolvedAddress(
                    address: address.address,
                    interfaceZone: fallbackZone
                )
            } catch {
                return address
            }
        }

        let selected = try addressSelector.select(
            from: selectableAddresses,
            port: endpoint.port
        )
        guard selectableAddresses.contains(selected) else {
            throw ManagerError.lanPolicy(.noPermittedAddress)
        }

        return try LANPolicy.validate(
            selected,
            port: endpoint.port,
            source: endpoint.source,
            lastAuthenticatedAt: endpoint.lastAuthenticatedAt
        )
    }
}

/// A small non-secret implementation useful for composition roots and
/// deterministic tests. A persistent implementation can replace it without
/// changing the admission sequence.
actor InMemoryTrustWarningStore: TrustWarningStore {
    private var acknowledgements: [TrustWarningScope: TrustWarningAcknowledgement] = [:]

    func acknowledgement(
        for scope: TrustWarningScope
    ) async throws -> TrustWarningAcknowledgement? {
        acknowledgements[scope.normalized]
    }

    func acknowledge(
        _ scope: TrustWarningScope,
        at date: Date
    ) async throws {
        let normalized = scope.normalized
        acknowledgements[normalized] = TrustWarningAcknowledgement(
            scope: normalized,
            acknowledgedAt: date
        )
    }
}
