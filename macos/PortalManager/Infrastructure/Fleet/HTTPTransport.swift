/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A Fleet request that has already passed connection admission.
///
/// `AdmittedConnection` is intentionally the only endpoint form accepted by
/// this transport. The connection executor below is responsible for producing
/// it immediately before the request and for obtaining any credential only
/// after admission succeeds.
struct HTTPTransportRequest: FleetHTTPRequest, Sendable {
    let connection: AdmittedConnection
    let routePlan: RouteCredentialPlan
    let body: Data?
    let credential: Data?

    init(
        connection: AdmittedConnection,
        routePlan: RouteCredentialPlan,
        body: Data? = nil,
        credential: Data? = nil
    ) {
        self.connection = connection
        self.routePlan = routePlan
        self.body = body
        self.credential = credential
    }
}

/// The URLSession-facing seam used by `HTTPTransport`.
///
/// Keeping this seam separate from `FleetHTTPTransport` lets tests inspect
/// the fully constructed request without opening a socket or using a live
/// redirecting server. Implementations must not follow redirects.
protocol HTTPRequestExecutor: Sendable {
    func execute(_ request: URLRequest) async throws -> FleetHTTPResponse
}

/// URLSession delegate that explicitly refuses every HTTP redirect.
private final class NoRedirectURLSessionDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // Do not return the proposed request. In particular, this prevents
        // URLSession from sending the original Authorization header to a
        // Location target.
        completionHandler(nil)
    }
}

/// Production URLSession adapter for the typed Fleet transport.
///
/// The session is ephemeral, does not use cookies, refuses redirects, and
/// applies the same ten-second request/resource deadline as the request-level
/// timeout. No URL is accepted from a caller; callers provide only a typed
/// request produced from an admitted endpoint and closed Fleet route plan.
final class URLSessionRequestExecutor: HTTPRequestExecutor, @unchecked Sendable {
    private let session: URLSession

    init(statusDeadline: TimeInterval = HTTPTransport.defaultStatusDeadline) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = statusDeadline
        configuration.timeoutIntervalForResource = statusDeadline
        configuration.waitsForConnectivity = false
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData

        session = URLSession(
            configuration: configuration,
            delegate: NoRedirectURLSessionDelegate(),
            delegateQueue: nil
        )
    }

    deinit {
        session.invalidateAndCancel()
    }

    func execute(_ request: URLRequest) async throws -> FleetHTTPResponse {
        let data: Data
        let response: URLResponse

        do {
            (data, response) = try await session.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            if error.code == .timedOut {
                throw ManagerError.transport(.timedOut)
            }
            throw ManagerError.transport(.connectionFailed)
        } catch {
            throw ManagerError.transport(.connectionFailed)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw ManagerError.transport(.connectionFailed)
        }

        var headers: [String: String] = [:]
        for (key, value) in httpResponse.allHeaderFields {
            guard let key = key as? String else { continue }
            headers[key] = String(describing: value)
        }

        return FleetHTTPResponse(
            statusCode: httpResponse.statusCode,
            headers: headers,
            body: data
        )
    }
}

/// The concrete no-follow Fleet HTTP transport.
///
/// This adapter has no redirect-following path. It builds the target URL from
/// the admitted endpoint and the closed `FleetRoute` in `RouteCredentialPlan`,
/// never reads a Location value, and treats every 3xx response as a local
/// `redirectRejected` error.
struct HTTPTransport: FleetHTTPTransport, Sendable {
    static let defaultStatusDeadline: TimeInterval = 10

    private let requestExecutor: any HTTPRequestExecutor
    private let statusDeadline: TimeInterval

    init(
        requestExecutor: any HTTPRequestExecutor = URLSessionRequestExecutor(),
        statusDeadline: TimeInterval = HTTPTransport.defaultStatusDeadline
    ) {
        self.requestExecutor = requestExecutor
        self.statusDeadline = statusDeadline.isFinite && statusDeadline > 0
            ? statusDeadline
            : HTTPTransport.defaultStatusDeadline
    }

    func send(_ request: any FleetHTTPRequest) async throws -> FleetHTTPResponse {
        guard let request = request as? HTTPTransportRequest else {
            throw ManagerError.validation(
                field: "Fleet request",
                reason: "The request is not a validated Fleet transport request."
            )
        }

        let urlRequest = try makeURLRequest(request)

        do {
            let response = try await requestExecutor.execute(urlRequest)
            guard !(300...399).contains(response.statusCode) else {
                // Do not inspect Location and do not construct a second
                // request. The original request is the only request emitted.
                throw ManagerError.redirectRejected
            }
            return response
        } catch let error as ManagerError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .timedOut {
            throw ManagerError.transport(.timedOut)
        } catch {
            // Raw URLSession/executor errors can contain hosts, URLs, or
            // request details. Expose only the stable manager category.
            throw ManagerError.transport(.connectionFailed)
        }
    }

    private func makeURLRequest(_ request: HTTPTransportRequest) throws -> URLRequest {
        guard request.connection.trustScope.serviceKind == .portal,
              request.connection.trustScope.protocolName == "http" else {
            throw ManagerError.validation(
                field: "Fleet endpoint",
                reason: "The endpoint is not admitted for the Portal HTTP service."
            )
        }

        try validateRoutePlan(request.routePlan)

        // Revalidate the value at the transport boundary as defense in depth.
        // ConnectionAdmission remains the required DNS/trust/credential-order
        // boundary; this check prevents a forged or stale admitted value from
        // becoming a socket destination.
        let endpoint = try LANPolicy.validate(request.connection.endpoint)

        var components = URLComponents()
        components.scheme = "http"
        if endpoint.addressFamily == .ipv6, let zone = endpoint.interfaceZone {
            // `percentEncodedHost` preserves the interface zone in URI form
            // without putting a zone or credential in a query or fragment.
            components.percentEncodedHost = "\(endpoint.hostOrAddress)%25\(zone)"
        } else {
            components.host = endpoint.hostOrAddress
        }
        components.port = Int(endpoint.port)
        components.path = request.routePlan.route.path
        components.query = nil
        components.fragment = nil

        guard let url = components.url else {
            throw ManagerError.lanPolicy(.malformedHost)
        }

        var urlRequest = URLRequest(
            url: url,
            cachePolicy: .reloadIgnoringLocalCacheData,
            timeoutInterval: statusDeadline
        )
        urlRequest.httpMethod = request.routePlan.method.rawValue
        urlRequest.httpShouldHandleCookies = false

        switch request.routePlan.credentialRequirement {
        case .none:
            guard request.credential == nil else {
                throw ManagerError.authentication(.invalidCredential)
            }
        case .verifiedBearer,
             .approvedRemoteSession,
             .verifiedBearerOrApprovedRemoteSession:
            guard let credential = request.credential,
                  !credential.isEmpty,
                  let token = String(data: credential, encoding: .utf8),
                  !token.isEmpty else {
                throw ManagerError.authentication(.missingCredential)
            }
            urlRequest.setValue(
                "Bearer \(token)",
                forHTTPHeaderField: "Authorization"
            )
        }

        if let body = request.body {
            guard request.routePlan.method == .post else {
                throw ManagerError.validation(
                    field: "Fleet request body",
                    reason: "A request body is not approved for this HTTP method."
                )
            }
            urlRequest.httpBody = body
            urlRequest.setValue(
                "application/json",
                forHTTPHeaderField: "Content-Type"
            )
        }

        return urlRequest
    }

    private func validateRoutePlan(
        _ plan: RouteCredentialPlan
    ) throws {
        guard plan.route.allowedMethods.contains(plan.method) else {
            throw ManagerError.validation(
                field: "Fleet operation",
                reason: "The HTTP method is not approved for this Fleet route."
            )
        }

        let isApprovedRemoteSession: Bool = {
            guard let approval = plan.remoteApproval else { return false }
            return approval.isStructurallyValid
                && approval.route == plan.route
                && approval.method == plan.method
        }()

        switch plan.route.credentialRequirement {
        case .none:
            guard plan.credential == nil, plan.remoteApproval == nil else {
                throw ManagerError.validation(
                    field: "Fleet operation",
                    reason: "The route credential plan is not validated."
                )
            }
        case .verifiedBearer:
            guard plan.credential == .verifiedBearer,
                  plan.remoteApproval == nil else {
                throw ManagerError.validation(
                    field: "Fleet operation",
                    reason: "The route credential plan is not validated."
                )
            }
        case .approvedRemoteSession:
            guard plan.credential == .remoteSession,
                  isApprovedRemoteSession else {
                throw ManagerError.validation(
                    field: "Fleet operation",
                    reason: "The route credential plan is not validated."
                )
            }
        case .verifiedBearerOrApprovedRemoteSession:
            switch plan.credential {
            case .verifiedBearer:
                guard plan.remoteApproval == nil else {
                    throw ManagerError.validation(
                        field: "Fleet operation",
                        reason: "The route credential plan is not validated."
                    )
                }
            case .remoteSession:
                guard isApprovedRemoteSession else {
                    throw ManagerError.validation(
                        field: "Fleet operation",
                        reason: "The route credential plan is not validated."
                    )
                }
            default:
                throw ManagerError.validation(
                    field: "Fleet operation",
                    reason: "The route credential plan is not validated."
                )
            }
        }
    }
}

/// A high-level Fleet execution request before admission.
///
/// The endpoint may still be a hostname here because this value is handed to
/// `ConnectionAdmission`, not to URLSession. The route plan is already created
/// by `OperationPlanner`; this request contains only an opaque Keychain
/// reference and never a credential value.
struct FleetConnectionRequest: FleetHTTPRequest, Sendable {
    let admissionRequest: ConnectionAdmissionRequest
    let routePlan: RouteCredentialPlan
    let credentialReference: CredentialReference?
    let body: Data?

    init(
        admissionRequest: ConnectionAdmissionRequest,
        routePlan: RouteCredentialPlan,
        credentialReference: CredentialReference? = nil,
        body: Data? = nil
    ) {
        self.admissionRequest = admissionRequest
        self.routePlan = routePlan
        self.credentialReference = credentialReference
        self.body = body
    }
}

/// Executes Fleet requests through the required admission and credential
/// ordering. Both initial execution and reconnects use a fresh admission call.
struct HTTPConnectionExecutor: Sendable {
    private let admission: ConnectionAdmission
    private let credentialStore: any CredentialStore
    private let transport: any FleetHTTPTransport

    init(
        admission: ConnectionAdmission,
        credentialStore: any CredentialStore,
        transport: any FleetHTTPTransport
    ) {
        self.admission = admission
        self.credentialStore = credentialStore
        self.transport = transport
    }

    /// Reuses the same admission and transport policy with an operation-local
    /// credential store, without exposing the executor's internal dependencies.
    func withCredentialStore(
        _ credentialStore: any CredentialStore
    ) -> HTTPConnectionExecutor {
        HTTPConnectionExecutor(
            admission: admission,
            credentialStore: credentialStore,
            transport: transport
        )
    }

    func execute(_ request: FleetConnectionRequest) async throws -> FleetHTTPResponse {
        try await perform(request, reconnect: false)
    }

    /// Re-resolves the original endpoint and re-applies LAN/trust policy before
    /// reading the Keychain or invoking the transport. No admitted address is
    /// cached between calls.
    func reconnect(_ request: FleetConnectionRequest) async throws -> FleetHTTPResponse {
        try await perform(request, reconnect: true)
    }

    private func perform(
        _ request: FleetConnectionRequest,
        reconnect: Bool
    ) async throws -> FleetHTTPResponse {
        try validateCredentialReference(request)

        let admitted: AdmittedConnection
        if reconnect {
            admitted = try await admission.reconnect(request.admissionRequest)
        } else {
            admitted = try await admission.admit(request.admissionRequest)
        }

        let credential: Data?
        if let reference = request.credentialReference {
            do {
                guard let value = try await credentialStore.read(reference) else {
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

        return try await transport.send(
            HTTPTransportRequest(
                connection: admitted,
                routePlan: request.routePlan,
                body: request.body,
                credential: credential
            )
        )
    }

    private func validateCredentialReference(
        _ request: FleetConnectionRequest
    ) throws {
        switch request.routePlan.credential {
        case nil:
            guard request.credentialReference == nil else {
                throw ManagerError.authentication(.invalidCredential)
            }
        case .some:
            guard request.credentialReference != nil else {
                throw ManagerError.authentication(.missingCredential)
            }
        }
    }
}
