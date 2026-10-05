/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The narrow Fleet surface needed by credential synchronization. Restricting
/// this port keeps route selection and credential handling out of SwiftUI and
/// lets deterministic tests replace the complete network boundary.
protocol CredentialSyncFleetClient: Sendable {
    func execute(_ request: FleetHTTPClientRequest) async throws -> FleetHTTPClientResponse
}

extension FleetHTTPClient: CredentialSyncFleetClient {}

/// Non-secret routing and authorization facts for one admitted destination.
struct CredentialSyncTargetContext: Sendable, Equatable {
    let portalID: PortalID
    let admissionRequest: ConnectionAdmissionRequest
    let authorizedReferences: Set<CredentialReference>
    let supportsSources: Bool

    init(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        authorizedReferences: Set<CredentialReference>,
        supportsSources: Bool
    ) throws {
        guard !authorizedReferences.isEmpty else {
            throw ManagerError.authentication(.missingCredential)
        }

        self.portalID = portalID
        self.admissionRequest = admissionRequest
        self.authorizedReferences = authorizedReferences
        self.supportsSources = supportsSources
    }

    init(entry: PortalRegistryEntry) throws {
        guard let endpoint = entry.endpoint else {
            throw ManagerError.validation(
                field: "Credential sync target",
                reason: "The selected Portal has no admitted endpoint."
            )
        }

        try self.init(
            portalID: entry.id,
            admissionRequest: ConnectionAdmissionRequest(
                endpoint: endpoint,
                serviceKind: .portal,
                protocolName: "http"
            ),
            authorizedReferences: Set(entry.credentialReferences),
            supportsSources: entry.capabilities?.sources == true
        )
    }

    func requireAuthorizedPortalBearer() throws -> CredentialReference {
        let reference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        guard authorizedReferences.contains(reference) else {
            throw ManagerError.authentication(.missingCredential)
        }
        return reference
    }
}

/// Production credential-share transport.
///
/// Source values are read through exact Portal/source/field references only at
/// dispatch time. The destination's current non-secret source endpoint is read
/// first so a credential-only share can be applied without inventing or copying
/// connection configuration. Failures are reduced to existing sanitized
/// manager errors; injected errors, response bodies, and credential bytes are
/// never retained in diagnostics.
struct FleetCredentialSyncTransport: CredentialSyncTransport {
    private enum SourceGroup: String, CaseIterable {
        case immich
        case smb
        case dav

        init?(for field: ShareableCredentialField) {
            switch field {
            case .immichKey: self = .immich
            case .smbUser, .smbPass: self = .smb
            case .davUser, .davPass: self = .dav
            }
        }

        var appliedKey: String { rawValue }
    }

    private let fleetClient: any CredentialSyncFleetClient
    private let credentialStore: any CredentialStore
    private let planner: OperationPlanner
    private let targets: [PortalID: CredentialSyncTargetContext]

    init(
        fleetClient: any CredentialSyncFleetClient,
        credentialStore: any CredentialStore,
        targets: [CredentialSyncTargetContext]
    ) {
        self.fleetClient = fleetClient
        self.credentialStore = credentialStore
        self.planner = OperationPlanner()
        self.targets = Dictionary(
            uniqueKeysWithValues: targets.map { ($0.portalID, $0) }
        )
    }

    func send(
        _ source: SharedCredentialSource,
        fields: Set<ShareableCredentialField>,
        to portalID: PortalID
    ) async throws {
        guard fields == source.fields else {
            throw ManagerError.validation(
                field: "Credential sync fields",
                reason: "The selected credential fields do not match the validated source."
            )
        }

        let target = try targetContext(for: portalID)
        let groups = Self.groups(for: fields)
        let sourceValues = try await readSourceValues(source, groups: groups)
        let destinationConfig = try await readDestinationSourceConfiguration(target)
        let body = try makeBody(
            destinationConfig: destinationConfig,
            sourceValues: sourceValues,
            groups: groups
        )

        try await apply(body, to: target)
    }

    private func targetContext(
        for portalID: PortalID
    ) throws -> CredentialSyncTargetContext {
        guard let target = targets[portalID] else {
            throw ManagerError.validation(
                field: "Credential sync target",
                reason: "The selected Portal is not an approved sync target."
            )
        }
        guard target.supportsSources else {
            throw ManagerError.capabilityUnavailable(
                operation: PortalOperation.sources.rawValue,
                reason: "The remote source capability is unavailable."
            )
        }
        return target
    }

    private func readSourceValues(
        _ source: SharedCredentialSource,
        groups: [SourceGroup]
    ) async throws -> [ShareableCredentialField: String] {
        var requiredFields: Set<ShareableCredentialField> = []
        for group in groups {
            switch group {
            case .immich:
                requiredFields.insert(.immichKey)
            case .smb:
                requiredFields.formUnion([.smbUser, .smbPass])
            case .dav:
                requiredFields.formUnion([.davUser, .davPass])
            }
        }

        guard requiredFields.isSubset(of: source.fields) else {
            throw ManagerError.settingsPolicy(
                field: "credential pair",
                reason: "A shared credential requires its complete field pair."
            )
        }

        var values: [ShareableCredentialField: String] = [:]
        for field in ShareableCredentialField.allCases where requiredFields.contains(field) {
            values[field] = try await scopedSourceValue(source, field: field)
        }
        return values
    }

    private func scopedSourceValue(
        _ source: SharedCredentialSource,
        field: ShareableCredentialField
    ) async throws -> String {
        do {
            guard let data = try await credentialStore.read(source.reference(for: field)) else {
                throw ManagerError.keychain(.missing)
            }
            var scopedData = data
            defer {
                scopedData.resetBytes(in: 0..<scopedData.count)
                scopedData.removeAll(keepingCapacity: false)
            }

            let value = String(decoding: scopedData, as: UTF8.self)
            guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw ManagerError.keychain(.missing)
            }
            return value
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as ManagerError {
            throw error
        } catch {
            throw ManagerError.keychain(.readFailed)
        }
    }

    private func readDestinationSourceConfiguration(
        _ target: CredentialSyncTargetContext
    ) async throws -> [String: JSONValue] {
        let response = try await execute(
            method: .get,
            target: target,
            body: nil,
            expectedAppliedKeys: []
        )

        guard case let .object(envelope) = response.payload,
              case .object(let rawSources)? = envelope["sources"] else {
            throw ManagerError.validation(
                field: "source configuration",
                reason: "The Portal did not return a usable source snapshot."
            )
        }
        return rawSources
    }

    private func makeBody(
        destinationConfig: [String: JSONValue],
        sourceValues: [ShareableCredentialField: String],
        groups: [SourceGroup]
    ) throws -> Data {
        var nativeBody: [String: Any] = [:]
        var expectedAppliedKeys: [String] = []

        for group in groups {
            switch group {
            case .immich:
                guard let url = requiredString("immichUrl", in: destinationConfig) else {
                    throw missingDestinationConfiguration()
                }
                nativeBody["immichUrl"] = url
                nativeBody["immichKey"] = sourceValues[.immichKey] ?? ""

            case .smb:
                guard let host = requiredString("smbHost", in: destinationConfig),
                      let share = requiredString("smbShare", in: destinationConfig) else {
                    throw missingDestinationConfiguration()
                }
                nativeBody["smbHost"] = host
                nativeBody["smbShare"] = share
                if case let .string(path)? = destinationConfig["smbPath"] {
                    nativeBody["smbPath"] = path
                }
                nativeBody["smbUser"] = sourceValues[.smbUser] ?? ""
                nativeBody["smbPass"] = sourceValues[.smbPass] ?? ""

            case .dav:
                guard let url = requiredString("davUrl", in: destinationConfig) else {
                    throw missingDestinationConfiguration()
                }
                nativeBody["davUrl"] = url
                nativeBody["davUser"] = sourceValues[.davUser] ?? ""
                nativeBody["davPass"] = sourceValues[.davPass] ?? ""
            }

            expectedAppliedKeys.append(group.appliedKey)
        }

        do {
            return try JSONSerialization.data(withJSONObject: nativeBody)
        } catch {
            throw ManagerError.validation(
                field: "credential payload",
                reason: "The credential update could not be encoded."
            )
        }
    }

    private func apply(
        _ body: Data,
        to target: CredentialSyncTargetContext
    ) async throws {
        _ = try await execute(
            method: .post,
            target: target,
            body: body,
            expectedAppliedKeys: Self.expectedKeys(for: body)
        )
    }

    private func execute(
        method: HTTPMethod,
        target: CredentialSyncTargetContext,
        body: Data?,
        expectedAppliedKeys: [String]
    ) async throws -> FleetHTTPClientResponse {
        let plan: RouteCredentialPlan
        do {
            plan = try planner.plan(
                portalID: target.portalID,
                route: .remoteSources,
                method: method,
                credential: .verifiedBearer
            )
        } catch {
            throw ManagerError.validation(
                field: "credential operation",
                reason: "The credential operation is not approved."
            )
        }

        let requestBody: FleetRequestBody
        if let body {
            requestBody = .json(body)
        } else {
            requestBody = .none
        }

        let request = FleetHTTPClientRequest(
            portalID: target.portalID,
            admissionRequest: target.admissionRequest,
            routePlan: plan,
            credentialReference: try target.requireAuthorizedPortalBearer(),
            body: requestBody,
            expectedAppliedKeys: expectedAppliedKeys
        )

        do {
            return try await fleetClient.execute(request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as ConnectionAdmissionError {
            throw error
        } catch let error as ManagerError {
            throw error
        } catch {
            throw ManagerError.transport(.connectionFailed)
        }
    }

    private static func groups(
        for fields: Set<ShareableCredentialField>
    ) -> [SourceGroup] {
        Set(fields.compactMap(SourceGroup.init(for:)))
            .map(\.rawValue)
            .sorted()
            .compactMap { SourceGroup(rawValue: $0) }
    }

    private static func expectedKeys(
        for body: Data
    ) -> [String] {
        guard let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
            return []
        }

        var keys: [String] = []
        if object["immichKey"] != nil { keys.append(SourceGroup.immich.appliedKey) }
        if object["smbPass"] != nil { keys.append(SourceGroup.smb.appliedKey) }
        if object["davPass"] != nil { keys.append(SourceGroup.dav.appliedKey) }
        return keys.sorted()
    }

    private func requiredString(
        _ key: String,
        in config: [String: JSONValue]
    ) -> String? {
        guard case let .string(value)? = config[key],
              !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return value
    }

    private func missingDestinationConfiguration() -> ManagerError {
        .validation(
            field: "destination source",
            reason: "The destination must already contain the matching source connection."
        )
    }
}
