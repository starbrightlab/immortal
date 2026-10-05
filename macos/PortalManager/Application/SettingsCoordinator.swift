/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The local outcome for one draft field. `accepted` is used only by draft
/// validation; apply results replace it with a terminal outcome.
enum SettingsFieldOutcome: String, Codable, Sendable, Equatable, Hashable {
    case accepted
    case omitted
    case applied
    case rejected
    case unsupported
    case failed
    case conflict
}

/// A sanitized result for one setting key. It contains no draft value or
/// response payload, so it is safe to expose to application/UI state.
struct SettingsFieldResult: Codable, Sendable, Equatable, Hashable {
    let key: String
    let outcome: SettingsFieldOutcome
    let error: ManagerError?

    init(
        key: String,
        outcome: SettingsFieldOutcome,
        error: ManagerError? = nil
    ) {
        self.key = key
        self.outcome = outcome
        self.error = error
    }
}

/// One domain's operation-local draft. Values are JSON values only; credentials
/// and secure input references are intentionally not representable here.
struct SettingsDomainDraft: Sendable, Equatable, Hashable {
    let domainID: String
    let values: [String: JSONValue]

    init(
        domainID: String,
        values: [String: JSONValue] = [:]
    ) {
        self.domainID = domainID
        self.values = values
    }
}

/// Compatibility spelling for callers that use the shorter draft name.
typealias SettingsDraft = SettingsDomainDraft

/// A batch is a value type so a UI/application caller can build it without
/// exposing coordinator state or any credential material.
struct SettingsDraftBatch: Sendable, Equatable, Hashable {
    let domains: [SettingsDomainDraft]

    init(domains: [SettingsDomainDraft] = []) {
        self.domains = domains
    }

    init(_ domains: SettingsDomainDraft...) {
        self.domains = domains
    }
}

/// The deterministic result of validating one domain draft against one
/// returned schema and the explicit product policy.
struct SettingsDraftValidationResult: Sendable, Equatable, Hashable {
    let domainID: String
    let acceptedValues: [String: JSONValue]
    let fields: [SettingsFieldResult]

    var acceptedKeys: [String] {
        acceptedValues.keys.sorted()
    }

    var omittedKeys: [String] {
        fields.filter { $0.outcome == .omitted }.map(\.key).sorted()
    }

    var rejectedKeys: [String] {
        fields.filter { $0.outcome == .rejected }.map(\.key).sorted()
    }

    var unsupportedKeys: [String] {
        fields.filter { $0.outcome == .unsupported }.map(\.key).sorted()
    }

    var isDispatchable: Bool {
        !acceptedValues.isEmpty
    }

    var hasLocalIssues: Bool {
        fields.contains { $0.outcome == .rejected || $0.outcome == .unsupported }
    }
}

/// The terminal result for one domain. Server-applied keys are reported
/// separately from authoritative read-back and from locally rejected keys.
struct SettingsDomainApplyResult: Sendable, Equatable, Hashable {
    let domainID: String
    let fields: [SettingsFieldResult]
    let confirmedDomain: SettingsDomainSchema?
    let error: ManagerError?

    var appliedKeys: [String] {
        fields.filter { $0.outcome == .applied }.map(\.key).sorted()
    }

    var omittedKeys: [String] {
        fields.filter { $0.outcome == .omitted }.map(\.key).sorted()
    }

    var rejectedKeys: [String] {
        fields.filter { $0.outcome == .rejected }.map(\.key).sorted()
    }

    var unsupportedKeys: [String] {
        fields.filter { $0.outcome == .unsupported }.map(\.key).sorted()
    }

    var failedKeys: [String] {
        fields.filter { $0.outcome == .failed }.map(\.key).sorted()
    }

    var conflictKeys: [String] {
        fields.filter { $0.outcome == .conflict }.map(\.key).sorted()
    }

    var isSuccessful: Bool {
        error == nil
            && rejectedKeys.isEmpty
            && unsupportedKeys.isEmpty
            && failedKeys.isEmpty
            && conflictKeys.isEmpty
            && omittedKeys.isEmpty
    }

    var isPartial: Bool {
        !isSuccessful && !fields.isEmpty
    }
}

/// The aggregate result for a settings operation across one or more domains.
/// `confirmedSchema` is the last schema that was actually read back; a draft
/// value is never copied into it optimistically.
struct SettingsApplyResult: Sendable, Equatable, Hashable {
    let portalID: PortalID
    let domains: [SettingsDomainApplyResult]
    let confirmedSchema: SettingsRegistrySchema?

    var errors: [ManagerError] {
        domains.compactMap(\.error) + domains.flatMap { domain in
            domain.fields.compactMap(\.error)
        }
    }

    var appliedKeysByDomain: [String: [String]] {
        Dictionary(uniqueKeysWithValues: domains.map { ($0.domainID, $0.appliedKeys) })
    }

    var isSuccessful: Bool {
        !domains.isEmpty && domains.allSatisfy(\.isSuccessful)
    }

    var isPartial: Bool {
        !isSuccessful && domains.contains { !$0.fields.isEmpty }
    }
}

/// Coordinates schema-driven settings operations. The actor owns only
/// non-secret confirmed snapshots; all network, credential, admission, and
/// route decisions remain behind `PortalSessionCoordinator`.
actor SettingsCoordinator {
    private let sessionCoordinator: PortalSessionCoordinator
    private let policy: SettingsPolicyLookup
    private var confirmedSchemas: [PortalID: SettingsRegistrySchema] = [:]

    init(
        sessionCoordinator: PortalSessionCoordinator,
        policy: SettingsPolicyLookup = .default
    ) {
        self.sessionCoordinator = sessionCoordinator
        self.policy = policy
    }

    /// Stores a schema that was obtained from an authoritative settings read.
    /// Callers should use `refresh` for a network-backed initial snapshot.
    func setConfirmedSchema(
        _ schema: SettingsRegistrySchema,
        for portalID: PortalID
    ) {
        confirmedSchemas[portalID] = schema
    }

    /// Returns the last authoritative schema, if one has been read for the
    /// selected Portal.
    func confirmedSchema(
        for portalID: PortalID
    ) -> SettingsRegistrySchema? {
        confirmedSchemas[portalID]
    }

    /// Pure validation entry point useful to UI/application code before it
    /// starts an operation. No network, Keychain, or socket work occurs here.
    func validateDraft(
        _ draft: SettingsDomainDraft,
        against schema: SettingsRegistrySchema
    ) -> SettingsDraftValidationResult {
        Self.validateDraft(draft, against: schema, policy: policy)
    }

    /// Static form for deterministic callers that do not need coordinator
    /// state. It uses the same policy and validation implementation as apply.
    static func validateDraft(
        _ draft: SettingsDomainDraft,
        against schema: SettingsRegistrySchema,
        policy: SettingsPolicyLookup = .default
    ) -> SettingsDraftValidationResult {
        let domainID = safeIdentifier(draft.domainID, fallback: "settings")
        guard let domain = schema.domains.first(where: { $0.id == draft.domainID }) else {
            let error = policyError(
                domainID: domainID,
                key: nil,
                reason: "The Settings domain is not available for editing."
            )
            let fields = draft.values.keys.sorted().map { key in
                SettingsFieldResult(
                    key: safeIdentifier(key, fallback: "setting"),
                    outcome: .rejected,
                    error: error
                )
            }
            return SettingsDraftValidationResult(
                domainID: domainID,
                acceptedValues: [:],
                fields: fields
            )
        }

        var accepted: [String: JSONValue] = [:]
        var results: [SettingsFieldResult] = []

        for key in draft.values.keys.sorted() {
            let safeKey = safeIdentifier(key, fallback: "setting")
            guard let value = draft.values[key] else { continue }

            guard domain.visible else {
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .rejected,
                        error: policyError(
                            domainID: domain.id,
                            key: key,
                            reason: "The returned schema marks this Settings domain hidden."
                        )
                    )
                )
                continue
            }

            guard let control = domain.controls.first(where: { $0.key == key }) else {
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .rejected,
                        error: policyError(
                            domainID: domain.id,
                            key: key,
                            reason: "The control is not available in the returned Settings schema."
                        )
                    )
                )
                continue
            }

            let entry = policy.entry(for: control)

            if let issue = schemaIssue(for: control) {
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .rejected,
                        error: validationError(
                            domainID: domain.id,
                            key: key,
                            reason: issue
                        )
                    )
                )
                continue
            }

            // Visibility, read-only state, unknown controls, and unknown types
            // remain local policy failures even when a blank value was supplied.
            // No draft may use an omitted field to bypass the returned schema.
            guard control.visible,
                  !control.readOnly,
                  control.isKnownControl,
                  !control.isUnknownType else {
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .rejected,
                        error: policy.policyError(for: control)
                            ?? policyError(
                                domainID: domain.id,
                                key: key,
                                reason: "The control is not approved for editing."
                            )
                    )
                )
                continue
            }

            // Secret and credential-bearing values cannot cross this generic
            // JSON draft boundary. A blank value is an explicit preserve and
            // is omitted exactly as the Android Settings Registry requires;
            // a nonblank value must use the dedicated secure flow instead.
            if control.secret || entry.sensitive {
                if isBlank(value) {
                    results.append(
                        SettingsFieldResult(
                            key: safeKey,
                            outcome: .omitted
                        )
                    )
                } else {
                    results.append(
                        SettingsFieldResult(
                            key: safeKey,
                            outcome: .rejected,
                            error: policyError(
                                domainID: domain.id,
                                key: key,
                                reason: control.secret
                                    ? "Secret values require the secure credential flow and cannot be sent in a generic settings draft."
                                    : "Sensitive values require the Keychain-backed settings bridge."
                            )
                        )
                    )
                }
                continue
            }

            // An explicit approved policy with an unsupported field-presence
            // contract is distinguishable from an entirely unclassified field.
            // Keep it out of the request with a dedicated unsupported result.
            let hasExplicitEditableApproval = policy.entries.last {
                $0.domainID == domain.id
                    && $0.controlKey == control.key
                    && $0.classification.isApprovedEditable
            } != nil
            if hasExplicitEditableApproval,
               case .unsupported = entry.fieldPresence {
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .unsupported,
                        error: policyError(
                            domainID: domain.id,
                            key: key,
                            reason: entry.fieldPresence.denialReason
                                ?? "The field cannot be represented with safe field-presence semantics."
                        )
                    )
                )
                continue
            }

            guard policy.canEdit(control) else {
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .rejected,
                        error: policy.policyError(for: control)
                            ?? policyError(
                                domainID: domain.id,
                                key: key,
                                reason: "The control is not approved for editing."
                            )
                    )
                )
                continue
            }

            switch entry.fieldPresence {
            case .unsupported:
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .unsupported,
                        error: policyError(
                            domainID: domain.id,
                            key: key,
                            reason: entry.fieldPresence.denialReason
                                ?? "The field cannot be represented with safe field-presence semantics."
                        )
                    )
                )
                continue
            case .preserveOnBlank where isBlank(value):
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .omitted
                    )
                )
                continue
            case .preserveOnBlank, .required:
                break
            }

            if let issue = valueIssue(value, for: control) {
                results.append(
                    SettingsFieldResult(
                        key: safeKey,
                        outcome: .rejected,
                        error: validationError(
                            domainID: domain.id,
                            key: key,
                            reason: issue
                        )
                    )
                )
                continue
            }

            accepted[key] = value
            results.append(
                SettingsFieldResult(
                    key: safeKey,
                    outcome: .accepted
                )
            )
        }

        return SettingsDraftValidationResult(
            domainID: domain.id,
            acceptedValues: accepted,
            fields: results.sorted { $0.key < $1.key }
        )
    }

    /// Reads the complete Settings Registry schema with the supplied exact
    /// route/credential context and records it as the confirmed snapshot.
    @discardableResult
    func refresh(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        credential: CredentialKind? = nil,
        operationID: String? = nil,
        remoteApproval: RemoteOperationApproval? = nil
    ) async throws -> SettingsRegistrySchema {
        let response = try await executeSettings(
            portalID: portalID,
            admissionRequest: admissionRequest,
            method: .get,
            credential: credential,
            operationID: operationID,
            remoteApproval: remoteApproval
        )
        let schema = try decodeSchema(from: response.payload)
        confirmedSchemas[portalID] = schema
        return schema
    }

    /// Compatibility spelling for callers that call the authoritative GET a
    /// read rather than a refresh.
    @discardableResult
    func readSchema(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        credential: CredentialKind? = nil,
        operationID: String? = nil,
        remoteApproval: RemoteOperationApproval? = nil
    ) async throws -> SettingsRegistrySchema {
        try await refresh(
            portalID: portalID,
            admissionRequest: admissionRequest,
            credential: credential,
            operationID: operationID,
            remoteApproval: remoteApproval
        )
    }

    /// Applies one or more domain drafts. Each domain is sent as one typed
    /// `/remote/settings` POST, and each successful POST is followed by a
    /// fresh authoritative GET before its schema enters confirmed state.
    @discardableResult
    func apply(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        drafts: [SettingsDomainDraft],
        schema suppliedSchema: SettingsRegistrySchema? = nil,
        credential: CredentialKind? = nil,
        operationID: String? = nil,
        remoteApproval: RemoteOperationApproval? = nil,
        readBackOperationID: String? = nil,
        readBackRemoteApproval: RemoteOperationApproval? = nil
    ) async throws -> SettingsApplyResult {
        let baseSchema: SettingsRegistrySchema
        if let suppliedSchema {
            baseSchema = suppliedSchema
        } else if let confirmed = confirmedSchemas[portalID] {
            baseSchema = confirmed
        } else {
            // Never validate against an unconfirmed empty/default schema. The
            // initial GET is itself routed through the typed session boundary.
            baseSchema = try await refresh(
                portalID: portalID,
                admissionRequest: admissionRequest,
                credential: credential,
                operationID: readBackOperationID ?? operationID,
                remoteApproval: readBackRemoteApproval ?? remoteApproval
            )
        }

        let groupedDrafts = grouped(drafts)
        guard !groupedDrafts.isEmpty else {
            return SettingsApplyResult(
                portalID: portalID,
                domains: [],
                confirmedSchema: confirmedSchemas[portalID] ?? baseSchema
            )
        }

        var domainResults: [SettingsDomainApplyResult] = []
        var latestConfirmed = confirmedSchemas[portalID]

        for domainDraft in groupedDrafts {
            let validation = Self.validateDraft(
                domainDraft,
                against: baseSchema,
                policy: policy
            )
            var fieldResults = validation.fields

            guard !validation.acceptedValues.isEmpty else {
                domainResults.append(
                    SettingsDomainApplyResult(
                        domainID: validation.domainID,
                        fields: fieldResults,
                        confirmedDomain: currentDomain(
                            validation.domainID,
                            in: latestConfirmed ?? baseSchema
                        ),
                        error: nil
                    )
                )
                continue
            }

            let expectedKeys = validation.acceptedValues.keys.sorted()
            let body = try settingsBody(
                domainID: domainDraft.domainID,
                values: validation.acceptedValues
            )
            let response: FleetHTTPClientResponse

            do {
                response = try await executeSettings(
                    portalID: portalID,
                    admissionRequest: admissionRequest,
                    method: .post,
                    credential: credential,
                    operationID: operationID,
                    remoteApproval: remoteApproval,
                    body: .json(body),
                    expectedAppliedKeys: expectedKeys
                )
            } catch is CancellationError {
                throw ManagerError.cancelled
            } catch {
                let failure = sanitized(error)
                let failedOutcome: SettingsFieldOutcome = {
                    if case .http(status: 409, code: _, detail: _) = failure {
                        return .conflict
                    }
                    return .failed
                }()
                fieldResults = fieldResults.map { field in
                    guard validation.acceptedKeys.contains(field.key) else {
                        return field
                    }
                    return SettingsFieldResult(
                        key: field.key,
                        outcome: failedOutcome,
                        error: failure
                    )
                }
                domainResults.append(
                    SettingsDomainApplyResult(
                        domainID: validation.domainID,
                        fields: fieldResults,
                        confirmedDomain: currentDomain(
                            validation.domainID,
                            in: latestConfirmed ?? baseSchema
                        ),
                        error: failure
                    )
                )
                continue
            }

            let appliedKeys: Set<String>
            do {
                appliedKeys = try reportedAppliedKeys(
                    from: response.payload,
                    expected: Set(expectedKeys)
                )
            } catch {
                let failure = sanitized(error)
                fieldResults = fieldResults.map { field in
                    guard validation.acceptedKeys.contains(field.key) else {
                        return field
                    }
                    return SettingsFieldResult(
                        key: field.key,
                        outcome: .failed,
                        error: failure
                    )
                }
                domainResults.append(
                    SettingsDomainApplyResult(
                        domainID: validation.domainID,
                        fields: fieldResults,
                        confirmedDomain: currentDomain(
                            validation.domainID,
                            in: latestConfirmed ?? baseSchema
                        ),
                        error: failure
                    )
                )
                continue
            }

            let omittedKeys = Set(expectedKeys).subtracting(appliedKeys)
            fieldResults = fieldResults.map { field in
                guard validation.acceptedKeys.contains(field.key) else {
                    return field
                }
                if appliedKeys.contains(field.key) {
                    return SettingsFieldResult(
                        key: field.key,
                        outcome: .applied
                    )
                }
                return SettingsFieldResult(
                    key: field.key,
                    outcome: .omitted
                )
            }

            // The POST response is not a confirmed snapshot. Read the entire
            // schema again and only then replace this Portal's cached state.
            do {
                let readBack = try await executeSettings(
                    portalID: portalID,
                    admissionRequest: admissionRequest,
                    method: .get,
                    credential: credential,
                    operationID: readBackOperationID,
                    remoteApproval: readBackRemoteApproval
                )
                let readBackSchema = try decodeSchema(from: readBack.payload)
                guard let confirmedDomain = currentDomain(
                    validation.domainID,
                    in: readBackSchema
                ) else {
                    throw ManagerError.validation(
                        field: "settings",
                        reason: "The authoritative Settings read-back omitted the applied domain."
                    )
                }
                latestConfirmed = readBackSchema
                confirmedSchemas[portalID] = readBackSchema
                _ = omittedKeys
                domainResults.append(
                    SettingsDomainApplyResult(
                        domainID: validation.domainID,
                        fields: fieldResults,
                        confirmedDomain: confirmedDomain,
                        error: nil
                    )
                )
            } catch is CancellationError {
                throw ManagerError.cancelled
            } catch {
                let failure = sanitized(error)
                // Server-reported applied keys remain visibly distinct from
                // failed/unapplied keys, but the operation is not successful
                // because no authoritative state was obtained.
                fieldResults = fieldResults.map { field in
                    guard field.outcome == .omitted,
                          validation.acceptedKeys.contains(field.key) else {
                        return field
                    }
                    return SettingsFieldResult(
                        key: field.key,
                        outcome: .failed,
                        error: failure
                    )
                }
                domainResults.append(
                    SettingsDomainApplyResult(
                        domainID: validation.domainID,
                        fields: fieldResults,
                        confirmedDomain: currentDomain(
                            validation.domainID,
                            in: latestConfirmed ?? baseSchema
                        ),
                        error: failure
                    )
                )
            }
        }

        return SettingsApplyResult(
            portalID: portalID,
            domains: domainResults,
            confirmedSchema: latestConfirmed ?? confirmedSchemas[portalID] ?? baseSchema
        )
    }

    /// Single-domain convenience overload that keeps the same validation and
    /// read-back guarantees as the multi-domain operation.
    @discardableResult
    func apply(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        draft: SettingsDomainDraft,
        schema suppliedSchema: SettingsRegistrySchema? = nil,
        credential: CredentialKind? = nil,
        operationID: String? = nil,
        remoteApproval: RemoteOperationApproval? = nil,
        readBackOperationID: String? = nil,
        readBackRemoteApproval: RemoteOperationApproval? = nil
    ) async throws -> SettingsApplyResult {
        try await apply(
            portalID: portalID,
            admissionRequest: admissionRequest,
            drafts: [draft],
            schema: suppliedSchema,
            credential: credential,
            operationID: operationID,
            remoteApproval: remoteApproval,
            readBackOperationID: readBackOperationID,
            readBackRemoteApproval: readBackRemoteApproval
        )
    }

    private func executeSettings(
        portalID: PortalID,
        admissionRequest: ConnectionAdmissionRequest,
        method: HTTPMethod,
        credential: CredentialKind?,
        operationID: String? = nil,
        remoteApproval: RemoteOperationApproval? = nil,
        body: FleetRequestBody = .none,
        expectedAppliedKeys: [String] = []
    ) async throws -> FleetHTTPClientResponse {
        let selectedCredential: CredentialKind = credential
            ?? (remoteApproval == nil ? .verifiedBearer : .remoteSession)
        return try await sessionCoordinator.execute(
            portalID: portalID,
            admissionRequest: admissionRequest,
            route: .remoteSettings,
            method: method,
            credential: selectedCredential,
            operationID: operationID,
            remoteApproval: remoteApproval,
            body: body,
            expectedAppliedKeys: expectedAppliedKeys
        )
    }

    private func grouped(
        _ drafts: [SettingsDomainDraft]
    ) -> [SettingsDomainDraft] {
        var valuesByDomain: [String: [String: JSONValue]] = [:]
        for draft in drafts {
            for (key, value) in draft.values {
                valuesByDomain[draft.domainID, default: [:]][key] = value
            }
        }
        return valuesByDomain.keys.sorted().map { domainID in
            SettingsDomainDraft(
                domainID: domainID,
                values: valuesByDomain[domainID] ?? [:]
            )
        }
    }

    private func currentDomain(
        _ domainID: String,
        in schema: SettingsRegistrySchema
    ) -> SettingsDomainSchema? {
        schema.domains.first { $0.id == domainID }
    }
}

// MARK: - Draft validation helpers

private extension SettingsCoordinator {
    static func schemaIssue(
        for control: SettingsControlSchema
    ) -> String? {
        guard control.type != .unknown(rawValue: control.rawType) else {
            return "The returned control type is not supported for editing."
        }
        if control.type == .int {
            if let step = control.step, step <= 0 {
                return "The returned integer step constraint is invalid."
            }
            if let min = control.min, let max = control.max, min > max {
                return "The returned integer range constraint is invalid."
            }
        }
        if control.type == .enumValue,
           control.options?.isEmpty != false {
            return "The returned enum options are unavailable."
        }
        return nil
    }

    static func valueIssue(
        _ value: JSONValue,
        for control: SettingsControlSchema
    ) -> String? {
        switch control.type {
        case .bool:
            guard case .bool = value else {
                return "The value has the wrong type for the returned schema."
            }
        case .int:
            guard case let .number(number) = value,
                  number.isFinite,
                  number.rounded() == number,
                  number >= Double(Int.min),
                  number <= Double(Int.max) else {
                return "The value has the wrong type for the returned schema."
            }
            let integer = Int(number)
            if control.wrap != true,
               (control.min.map { integer < $0 } == true
                    || control.max.map { integer > $0 } == true) {
                return "The integer value is outside the returned range."
            }
            if let step = control.step, step > 0 {
                let anchor = control.min ?? 0
                if (integer - anchor) % step != 0 {
                    return "The integer value does not satisfy the returned step."
                }
            }
        case .enumValue:
            guard let options = control.options,
                  options.contains(where: { $0.value == value }) else {
                return "The value is not one of the returned enum options."
            }
        case .string:
            guard case .string = value else {
                return "The value has the wrong type for the returned schema."
            }
        case .info:
            return "Information controls do not have an editable value."
        case .unknown:
            return "The returned control type is not supported for editing."
        }
        return nil
    }

    static func isBlank(_ value: JSONValue) -> Bool {
        switch value {
        case .null:
            return true
        case .string(let string):
            return string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .bool, .number, .array, .object:
            return false
        }
    }

    static func policyError(
        domainID: String,
        key: String?,
        reason: String
    ) -> ManagerError {
        let field = [
            safeIdentifier(domainID, fallback: "settings"),
            key.map { safeIdentifier($0, fallback: "setting") }
        ]
        .compactMap { $0 }
        .joined(separator: ".")
        return .settingsPolicy(
            field: field.isEmpty ? "settings" : field,
            reason: reason
        )
    }

    static func validationError(
        domainID: String,
        key: String,
        reason: String
    ) -> ManagerError {
        .validation(
            field: [
                safeIdentifier(domainID, fallback: "settings"),
                safeIdentifier(key, fallback: "setting")
            ].joined(separator: "."),
            reason: reason
        )
    }

    static func safeIdentifier(
        _ value: String,
        fallback: String
    ) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              trimmed.count <= 96,
              trimmed.unicodeScalars.allSatisfy({ scalar in
                  CharacterSet.alphanumerics.contains(scalar)
                      || scalar == "-"
                      || scalar == "_"
                      || scalar == "."
              }) else {
            return fallback
        }
        return trimmed
    }

    func settingsBody(
        domainID: String,
        values: [String: JSONValue]
    ) throws -> Data {
        let object = JSONValue.object([
            "domain": .string(domainID),
            "values": .object(values)
        ])
        do {
            return try JSONEncoder().encode(object)
        } catch {
            throw ManagerError.validation(
                field: "settings",
                reason: "The approved Settings request could not be encoded."
            )
        }
    }

    func decodeSchema(
        from payload: JSONValue
    ) throws -> SettingsRegistrySchema {
        do {
            let data = try JSONEncoder().encode(payload)
            return try JSONDecoder().decode(SettingsRegistrySchema.self, from: data)
        } catch {
            throw ManagerError.validation(
                field: "settings",
                reason: "The Fleet Agent returned an invalid Settings schema."
            )
        }
    }

    func reportedAppliedKeys(
        from payload: JSONValue,
        expected: Set<String>
    ) throws -> Set<String> {
        guard case let .object(object) = payload,
              case let .array(rawApplied) = object["applied"] else {
            throw ManagerError.validation(
                field: "settings",
                reason: "The Fleet Agent returned an invalid applied-key set."
            )
        }

        var reported: Set<String> = []
        for value in rawApplied {
            guard case let .string(key) = value,
                  !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw ManagerError.validation(
                    field: "settings",
                    reason: "The Fleet Agent returned an invalid applied-key set."
                )
            }
            // Only keys that were validated and sent can become an applied
            // result. Unknown response keys never enter application state.
            if expected.contains(key) {
                reported.insert(key)
            }
        }
        return reported
    }

    func sanitized(_ error: Error) -> ManagerError {
        if error is CancellationError {
            return .cancelled
        }
        if let managerError = error as? ManagerError {
            return managerError
        }
        if error is ConnectionAdmissionError {
            return .validation(
                field: "settings",
                reason: "A local-network trust acknowledgement is required before applying Settings."
            )
        }
        return .transport(.connectionFailed)
    }
}
