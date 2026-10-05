/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Describes whether a Settings Registry field may participate in a bulk
/// operation. Every bulk operation still requires the normal explicit
/// confirmation at the application boundary.
enum BulkPolicy: Codable, Sendable, Equatable, Hashable {
    /// The field may be included in a confirmed bulk operation.
    case allowed
    /// The field is not approved for bulk dispatch, even when an individual
    /// edit is approved.
    case disallowed(reason: String)

    var allowsBulkOperation: Bool {
        if case .allowed = self {
            return true
        }
        return false
    }

    var denialReason: String? {
        guard case .disallowed(let reason) = self else { return nil }
        return reason
    }
}

/// Declares how a policy-approved field is represented in a partial request.
///
/// The policy layer does not construct or send a request. It records the
/// field-presence contract that a later adapter must enforce, so a policy
/// approval cannot silently turn an omitted/blank value into a destructive
/// clear operation.
enum FieldPresencePolicy: Codable, Sendable, Equatable, Hashable {
    /// A blank value is omitted and preserves the Portal's existing value.
    case preserveOnBlank
    /// The field must be present when the approved operation is submitted.
    case required
    /// The documented endpoint cannot represent a safe edit for this field.
    case unsupported(reason: String)

    /// Compatibility spelling for callers that use request-oriented language.
    static var omitWhenBlank: Self { .preserveOnBlank }

    var supportsEditing: Bool {
        if case .unsupported = self {
            return false
        }
        return true
    }

    var preservesExistingValueOnBlank: Bool {
        if case .preserveOnBlank = self {
            return true
        }
        return false
    }

    var required: Bool {
        if case .required = self {
            return true
        }
        return false
    }

    var denialReason: String? {
        guard case .unsupported(let reason) = self else { return nil }
        return reason
    }
}

/// The explicit product policy for one Settings Registry domain/control.
///
/// An approved editable classification is intentionally more demanding than
/// a Boolean permission: it carries the closed Fleet route, bulk behavior, and
/// non-secret evidence identifier used to justify the approval.
enum SettingsPolicyClassification: Codable, Sendable, Equatable, Hashable {
    case approvedEditable(route: FleetRoute, bulk: BulkPolicy, evidence: String)
    case approvedReadOnly(reason: String)
    case endpointBearingPendingApproval(reason: String)
    case credentialBearingPendingApproval(reason: String)
    case excluded(reason: String)
    case unknown

    var isApprovedEditable: Bool {
        if case .approvedEditable = self {
            return true
        }
        return false
    }

    var isReadOnly: Bool { !isApprovedEditable }

    var route: FleetRoute? {
        guard case .approvedEditable(let route, _, _) = self else { return nil }
        return route
    }

    var bulkPolicy: BulkPolicy? {
        guard case .approvedEditable(_, let bulk, _) = self else { return nil }
        return bulk
    }

    var evidence: String? {
        guard case .approvedEditable(_, _, let evidence) = self else { return nil }
        return evidence
    }

    var reason: String? {
        switch self {
        case .approvedEditable:
            return nil
        case .approvedReadOnly(let reason),
             .endpointBearingPendingApproval(let reason),
             .credentialBearingPendingApproval(let reason),
             .excluded(let reason):
            return reason
        case .unknown:
            return "No explicit Settings Policy Classification exists for this control."
        }
    }
}

/// The policy entry for one domain or control.
///
/// `controlKey == nil` identifies a domain-level entry. The default lookup
/// never treats a domain-level editable entry as a wildcard grant for every
/// control; editable controls require an exact domain/control entry.
struct SettingsPolicyEntry: Codable, Sendable, Equatable, Hashable {
    var domainID: String
    var controlKey: String?
    var classification: SettingsPolicyClassification
    var sensitive: Bool
    var fieldPresence: FieldPresencePolicy

    init(
        domainID: String,
        controlKey: String? = nil,
        classification: SettingsPolicyClassification,
        sensitive: Bool = false,
        fieldPresence: FieldPresencePolicy = .unsupported(
            reason: "No approved field-presence policy exists for this control."
        )
    ) {
        self.domainID = domainID
        self.controlKey = controlKey
        self.classification = classification
        self.sensitive = sensitive
        self.fieldPresence = fieldPresence
    }

    /// Whether this entry contains an editable approval before returned-schema
    /// metadata is applied. `SettingsPolicyLookup` performs the full check.
    var hasEditableApproval: Bool {
        guard case .approvedEditable(let route, _, let evidence) = classification else {
            return false
        }
        return route == .remoteSettings
            && !evidence.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && fieldPresence.supportsEditing
    }
}

/// Default-deny lookup for Settings Registry policy.
///
/// The lookup is deliberately separate from `SettingsRegistrySchema`: the
/// server controls presentation metadata, while this product-owned policy
/// controls whether a value may ever become editable. An explicit approval is
/// exact to `domainID + controlKey`; an unclassified control never inherits an
/// editable approval from a domain-level entry.
struct SettingsPolicyLookup: Sendable, Equatable {
    var entries: [SettingsPolicyEntry]

    init(entries: [SettingsPolicyEntry] = []) {
        self.entries = entries
    }

    /// The empty policy is the safe product default: no control is editable.
    static let `default` = SettingsPolicyLookup()

    /// Looks up a policy entry without a returned schema. This overload is
    /// useful for domain-level presentation and for policy introspection. A
    /// control-level lookup still needs the returned schema before it can be
    /// considered editable.
    func entry(
        forDomain domainID: String,
        controlKey: String? = nil
    ) -> SettingsPolicyEntry {
        if let explicit = exactEntry(domainID: domainID, controlKey: controlKey) {
            return explicit
        }

        return defaultEntry(domainID: domainID, controlKey: controlKey)
    }

    /// Looks up the effective policy for a returned control. Schema metadata is
    /// authoritative for visibility, read-only state, and representability;
    /// policy approval never overrides those constraints.
    func entry(for control: SettingsControlSchema) -> SettingsPolicyEntry {
        let explicit = exactEntry(
            domainID: control.domainID,
            controlKey: control.key
        )
        let base: SettingsPolicyEntry
        if let explicit {
            base = explicit
        } else if let domainReadOnly = exactEntry(
            domainID: control.domainID,
            controlKey: nil
        ), !domainReadOnly.classification.isApprovedEditable {
            // A domain-level deny/pending classification may explain the
            // default, but a domain-level editable entry is never a wildcard.
            base = domainReadOnly
        } else {
            base = defaultEntry(for: control)
        }

        var effective = base
        effective.sensitive = base.sensitive
            || control.secret
            || isCredentialBearing(domainID: control.domainID, controlKey: control.key)

        // A returned schema can only narrow an explicit product approval.
        // It can never grant one.
        guard case .approvedEditable = effective.classification else {
            return effective
        }

        if control.isUnknownType {
            effective.classification = .unknown
        } else if !control.visible {
            effective.classification = .approvedReadOnly(
                reason: "The returned schema marks this control hidden."
            )
        } else if control.readOnly {
            effective.classification = .approvedReadOnly(
                reason: "The returned schema marks this control read-only."
            )
        } else if case .info = control.type {
            effective.classification = .approvedReadOnly(
                reason: "Information controls do not have an editable value."
            )
        } else if !effective.hasEditableApproval {
            effective.classification = .approvedReadOnly(
                reason: effective.fieldPresence.denialReason
                    ?? "The Settings Policy approval is incomplete."
            )
        }

        return effective
    }

    /// Alias with lookup-oriented naming for application callers.
    func lookup(for control: SettingsControlSchema) -> SettingsPolicyEntry {
        entry(for: control)
    }

    /// Returns the effective classification without exposing a second policy
    /// source of truth.
    func classification(for control: SettingsControlSchema) -> SettingsPolicyClassification {
        entry(for: control).classification
    }

    /// A structural policy check for a control. Draft value type/range/enum
    /// constraints remain the responsibility of the returned schema and are
    /// intentionally not validated here (that is task 10.3 behavior).
    func canEdit(_ control: SettingsControlSchema) -> Bool {
        let entry = entry(for: control)
        guard entry.hasEditableApproval,
              case .approvedEditable(let route, _, let evidence) = entry.classification,
              route == .remoteSettings,
              !evidence.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              control.visible,
              !control.readOnly,
              !control.isUnknownType,
              control.type != .info else {
            return false
        }
        return true
    }

    /// Maps a denied policy decision to the existing sanitized manager error.
    /// No schema value or secret is included in the error.
    func policyError(for control: SettingsControlSchema) -> ManagerError? {
        guard !canEdit(control) else { return nil }
        let entry = entry(for: control)
        let field = [control.domainID, control.key]
            .filter { !$0.isEmpty }
            .joined(separator: ".")
        return .settingsPolicy(
            field: field.isEmpty ? "settings" : field,
            reason: entry.classification.reason
                ?? entry.fieldPresence.denialReason
                ?? "The control is not approved for editing."
        )
    }

    private func exactEntry(
        domainID: String,
        controlKey: String?
    ) -> SettingsPolicyEntry? {
        entries.last {
            $0.domainID == domainID && $0.controlKey == controlKey
        }
    }

    private func defaultEntry(
        domainID: String,
        controlKey: String?
    ) -> SettingsPolicyEntry {
        let sensitive = controlKey.map {
            isCredentialBearing(domainID: domainID, controlKey: $0)
        } ?? false
        let endpointBearing = controlKey.map {
            isEndpointBearing(domainID: domainID, controlKey: $0)
        } ?? false

        let classification: SettingsPolicyClassification
        if sensitive {
            classification = .credentialBearingPendingApproval(
                reason: "Credential-bearing values require explicit Keychain, redaction, and bulk approval."
            )
        } else if endpointBearing {
            classification = .endpointBearingPendingApproval(
                reason: "Endpoint-bearing values require explicit LAN route and bulk approval."
            )
        } else {
            classification = .unknown
        }

        return SettingsPolicyEntry(
            domainID: domainID,
            controlKey: controlKey,
            classification: classification,
            sensitive: sensitive
        )
    }

    private func defaultEntry(for control: SettingsControlSchema) -> SettingsPolicyEntry {
        let sensitive = control.secret
            || isCredentialBearing(
                domainID: control.domainID,
                controlKey: control.key
            )
        let endpointBearing = isEndpointBearing(
            domainID: control.domainID,
            controlKey: control.key
        )

        let classification: SettingsPolicyClassification
        if control.isUnknownType {
            classification = .unknown
        } else if !control.isKnownControl {
            if sensitive {
                classification = .credentialBearingPendingApproval(
                    reason: "Credential-bearing future controls require explicit approval."
                )
            } else if endpointBearing {
                classification = .endpointBearingPendingApproval(
                    reason: "Endpoint-bearing future controls require explicit approval."
                )
            } else {
                classification = .unknown
            }
        } else if !control.visible {
            classification = .approvedReadOnly(
                reason: "The returned schema marks this control hidden."
            )
        } else if control.readOnly {
            classification = .approvedReadOnly(
                reason: "The returned schema marks this control read-only."
            )
        } else if case .info = control.type {
            classification = .approvedReadOnly(
                reason: "Information controls do not have an editable value."
            )
        } else if sensitive {
            classification = .credentialBearingPendingApproval(
                reason: "Credential-bearing values require explicit Keychain, redaction, and bulk approval."
            )
        } else if endpointBearing {
            classification = .endpointBearingPendingApproval(
                reason: "Endpoint-bearing values require explicit LAN route and bulk approval."
            )
        } else {
            classification = .unknown
        }

        return SettingsPolicyEntry(
            domainID: control.domainID,
            controlKey: control.key,
            classification: classification,
            sensitive: sensitive
        )
    }

    private func isCredentialBearing(domainID: String, controlKey: String) -> Bool {
        let normalized = normalizedKey(controlKey)
        if normalized == "mausername" || normalized == "mapassword" {
            return true
        }

        // Reuse the source-secret naming rules for credential-shaped future
        // settings, then cover the settings-specific auth names.
        if SourceSecretFieldPolicy.isSensitiveKey(controlKey) {
            return true
        }

        let credentialFragments = [
            "password", "passwd", "username", "user", "token", "secret",
            "credential", "apikey", "accesstoken", "refreshtoken", "bearer",
            "session"
        ]
        return credentialFragments.contains { normalized.contains($0) }
    }

    private func isEndpointBearing(domainID: String, controlKey: String) -> Bool {
        let normalized = normalizedKey(controlKey)
        let endpointFragments = [
            "host", "hostname", "port", "url", "uri", "address", "endpoint",
            "feed"
        ]
        return endpointFragments.contains { normalized.contains($0) }
    }

    private func normalizedKey(_ key: String) -> String {
        key.unicodeScalars
            .filter { CharacterSet.alphanumerics.contains($0) }
            .map(String.init)
            .joined()
            .lowercased()
    }
}

/// Short name used by application code that treats the lookup as the policy
/// itself. The two names intentionally refer to the same default-deny type.
typealias SettingsPolicy = SettingsPolicyLookup
