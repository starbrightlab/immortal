/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import XCTest
@testable import PortalManager

final class SettingsPolicyTests: XCTestCase {
    func testDefaultPolicyDeniesUnclassifiedKnownControl() {
        let control = SettingsControlSchema(
            domainID: "immortal",
            key: "weatherUnit",
            type: .string
        )

        let entry = SettingsPolicyLookup.default.entry(for: control)

        XCTAssertEqual(entry.domainID, "immortal")
        XCTAssertEqual(entry.controlKey, "weatherUnit")
        XCTAssertEqual(entry.classification, .unknown)
        XCTAssertFalse(entry.sensitive)
        XCTAssertFalse(entry.fieldPresence.supportsEditing)
        XCTAssertFalse(SettingsPolicyLookup.default.canEdit(control))
        XCTAssertEqual(
            SettingsPolicyLookup.default.policyError(for: control)?.category,
            .settingsPolicy
        )
    }

    func testDefaultPolicyClassifiesSecretCredentialAndEndpointControls() {
        let secret = SettingsControlSchema(
            domainID: "mqtt",
            key: "password",
            type: .string,
            secret: true,
            hasValue: true
        )
        let username = SettingsControlSchema(
            domainID: "immortal",
            key: "maUsername",
            type: .string,
            secret: false
        )
        let endpoint = SettingsControlSchema(
            domainID: "immortal",
            key: "snapcastHost",
            type: .string
        )

        let policy = SettingsPolicyLookup.default
        let secretEntry = policy.entry(for: secret)
        let usernameEntry = policy.entry(for: username)
        let endpointEntry = policy.entry(for: endpoint)

        guard case .credentialBearingPendingApproval = secretEntry.classification else {
            return XCTFail("Secret controls must default to credential-bearing pending approval.")
        }
        guard case .credentialBearingPendingApproval = usernameEntry.classification else {
            return XCTFail("maUsername must always be credential-bearing pending approval.")
        }
        guard case .endpointBearingPendingApproval = endpointEntry.classification else {
            return XCTFail("Endpoint controls must default to endpoint-bearing pending approval.")
        }
        XCTAssertTrue(secretEntry.sensitive)
        XCTAssertTrue(usernameEntry.sensitive)
        XCTAssertFalse(endpointEntry.sensitive)
        XCTAssertFalse(policy.canEdit(secret))
        XCTAssertFalse(policy.canEdit(username))
        XCTAssertFalse(policy.canEdit(endpoint))
    }

    func testUnknownDomainAndTypeRemainReadOnlyByDefault() {
        let unknownDomain = SettingsDomainSchema(
            id: "future-domain",
            controls: [
                SettingsControlSchema(
                    key: "futureValue",
                    type: .string
                )
            ]
        )
        let unknownDomainControl = try! XCTUnwrap(unknownDomain.controls.first)
        let unknownType = SettingsControlSchema(
            domainID: "screensaver",
            key: "futureControl",
            rawType: "future-toggle",
            type: .unknown(rawValue: "future-toggle")
        )

        let policy = SettingsPolicyLookup.default
        XCTAssertEqual(policy.entry(for: unknownDomainControl).classification, .unknown)
        XCTAssertFalse(policy.canEdit(unknownDomainControl))
        XCTAssertEqual(policy.entry(for: unknownType).classification, .unknown)
        XCTAssertFalse(policy.canEdit(unknownType))
    }

    func testExplicitApprovalRequiresExactEntryAndCarriesRouteBulkAndFieldPresence() {
        let approved = SettingsControlSchema(
            domainID: "immortal",
            key: "weatherUnit",
            type: .string
        )
        let unlisted = SettingsControlSchema(
            domainID: "immortal",
            key: "tileSize",
            type: .int
        )
        let entry = SettingsPolicyEntry(
            domainID: "immortal",
            controlKey: "weatherUnit",
            classification: .approvedEditable(
                route: .remoteSettings,
                bulk: .allowed,
                evidence: "settings-policy-weather-unit"
            ),
            fieldPresence: .preserveOnBlank
        )
        let policy = SettingsPolicyLookup(entries: [entry])

        let result = policy.entry(for: approved)
        guard case .approvedEditable(let route, let bulk, let evidence) = result.classification else {
            return XCTFail("The exact policy entry should remain editable for a compatible schema.")
        }
        XCTAssertEqual(route, .remoteSettings)
        XCTAssertEqual(bulk, .allowed)
        XCTAssertEqual(evidence, "settings-policy-weather-unit")
        XCTAssertTrue(result.hasEditableApproval)
        XCTAssertTrue(result.fieldPresence.preservesExistingValueOnBlank)
        XCTAssertTrue(policy.canEdit(approved))

        XCTAssertEqual(policy.entry(for: unlisted).classification, .unknown)
        XCTAssertFalse(policy.canEdit(unlisted))
    }

    func testApprovalCannotOverrideReturnedSchemaReadOnlyVisibilityOrType() {
        let policyEntry = SettingsPolicyEntry(
            domainID: "screensaver",
            controlKey: "intervalSec",
            classification: .approvedEditable(
                route: .remoteSettings,
                bulk: .disallowed(reason: "Per-Portal confirmation required."),
                evidence: "settings-policy-interval"
            ),
            fieldPresence: .required
        )
        let policy = SettingsPolicyLookup(entries: [policyEntry])

        let readOnly = SettingsControlSchema(
            domainID: "screensaver",
            key: "intervalSec",
            type: .int,
            min: 5,
            max: 600,
            step: 5,
            readOnly: true
        )
        let hidden = SettingsControlSchema(
            domainID: "screensaver",
            key: "intervalSec",
            type: .int,
            min: 5,
            max: 600,
            step: 5,
            visible: false
        )
        let unknownType = SettingsControlSchema(
            domainID: "screensaver",
            key: "intervalSec",
            rawType: "future-int",
            type: .unknown(rawValue: "future-int"),
            min: 5,
            max: 600,
            step: 5
        )

        XCTAssertFalse(policy.canEdit(readOnly))
        XCTAssertFalse(policy.canEdit(hidden))
        XCTAssertFalse(policy.canEdit(unknownType))
        guard case .approvedReadOnly = policy.entry(for: readOnly).classification else {
            return XCTFail("Schema readOnly must narrow an editable policy approval.")
        }
        guard case .approvedReadOnly = policy.entry(for: hidden).classification else {
            return XCTFail("Schema visibility must narrow an editable policy approval.")
        }
        XCTAssertEqual(policy.entry(for: unknownType).classification, .unknown)

        // The policy does not replace or relax returned constraints. Draft
        // type/range/enum validation remains a later schema-validation step.
        XCTAssertEqual(readOnly.min, 5)
        XCTAssertEqual(readOnly.max, 600)
        XCTAssertEqual(readOnly.step, 5)
    }

    func testIncompleteApprovalsDefaultToReadOnlyAndDomainEditableEntriesAreNotWildcards() {
        let unsupportedFieldPresence = SettingsPolicyEntry(
            domainID: "calendar",
            controlKey: "url",
            classification: .approvedEditable(
                route: .remoteSettings,
                bulk: .allowed,
                evidence: "calendar-url"
            )
        )
        let wrongRoute = SettingsPolicyEntry(
            domainID: "calendar",
            controlKey: "range",
            classification: .approvedEditable(
                route: .screensaver,
                bulk: .allowed,
                evidence: "calendar-range"
            ),
            fieldPresence: .required
        )
        let domainApproval = SettingsPolicyEntry(
            domainID: "calendar",
            classification: .approvedEditable(
                route: .remoteSettings,
                bulk: .allowed,
                evidence: "calendar-domain"
            ),
            fieldPresence: .required
        )
        let policy = SettingsPolicyLookup(
            entries: [unsupportedFieldPresence, wrongRoute, domainApproval]
        )

        let url = SettingsControlSchema(
            domainID: "calendar",
            key: "url",
            type: .string
        )
        let range = SettingsControlSchema(
            domainID: "calendar",
            key: "range",
            type: .int
        )
        let unlisted = SettingsControlSchema(
            domainID: "calendar",
            key: "side",
            type: .string
        )

        XCTAssertFalse(policy.canEdit(url))
        XCTAssertFalse(policy.canEdit(range))
        XCTAssertFalse(policy.canEdit(unlisted))
        XCTAssertEqual(policy.entry(for: url).classification.reason, "No approved field-presence policy exists for this control.")
        XCTAssertEqual(policy.entry(for: range).classification.reason, "The Settings Policy approval is incomplete.")
        XCTAssertEqual(policy.entry(for: unlisted).classification, .unknown)
    }

    func testPolicyClassificationCodableRoundTripsAllContractCases() throws {
        let values: [SettingsPolicyClassification] = [
            .approvedEditable(
                route: .remoteSettings,
                bulk: .allowed,
                evidence: "evidence"
            ),
            .approvedReadOnly(reason: "read-only"),
            .endpointBearingPendingApproval(reason: "endpoint"),
            .credentialBearingPendingApproval(reason: "credential"),
            .excluded(reason: "excluded"),
            .unknown
        ]

        let data = try JSONEncoder().encode(values)
        let decoded = try JSONDecoder().decode(
            [SettingsPolicyClassification].self,
            from: data
        )
        XCTAssertEqual(decoded, values)
    }
}
