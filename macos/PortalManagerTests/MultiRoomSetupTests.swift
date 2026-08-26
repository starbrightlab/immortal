/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI
import XCTest
@testable import PortalManager

final class MultiRoomSetupTests: XCTestCase {
    private func portal(_ id: PortalID, name: String, address: String? = nil) -> MultiRoomPortalCandidate {
        MultiRoomPortalCandidate(portalID: id, name: name, address: address)
    }

    func testPlanMatchesByAddressBeforeNameAndKeepsAmbiguityExplicit() {
        let first = PortalID(rawValue: UUID())
        let second = PortalID(rawValue: UUID())
        let third = PortalID(rawValue: UUID())
        let portals = [
            portal(first, name: "Kitchen", address: "192.168.1.20"),
            portal(second, name: "Office", address: "192.168.1.21"),
            portal(third, name: "Duplicate"),
            portal(PortalID(rawValue: UUID()), name: "Duplicate"),
            portal(PortalID(rawValue: UUID()), name: "Missing"),
        ]
        let clients = [
            MultiRoomClientCandidate(clientID: "wrong-name", name: "Old Kitchen", address: "192.168.1.20"),
            MultiRoomClientCandidate(clientID: "office", name: "office", address: "192.168.1.21"),
            MultiRoomClientCandidate(clientID: "duplicate-a", name: "duplicate", address: nil),
            MultiRoomClientCandidate(clientID: "duplicate-b", name: "duplicate", address: nil),
            MultiRoomClientCandidate(clientID: "unmatched", name: "Guest", address: nil),
        ]

        let plan = MultiRoomSetupPlan(portals: portals, clients: clients)

        XCTAssertEqual(plan.matches[first], "wrong-name")
        XCTAssertEqual(plan.matches[second], "office")
        XCTAssertEqual(plan.ambiguousPortalIDs.count, 2)
        XCTAssertTrue(plan.ambiguousPortalIDs.contains(third))
        XCTAssertTrue(plan.hasActionableMatches)
    }

    func testGuidedPolicyApprovesOnlyExactRequiredMultiRoomFields() {
        let enabled = SettingsControlSchema(
            domainID: "immortal",
            key: "multiRoomEnabled",
            type: .bool,
            hasValue: true
        )
        let host = SettingsControlSchema(
            domainID: "immortal",
            key: "snapcastHost",
            type: .string
        )
        let unrelated = SettingsControlSchema(
            domainID: "immortal",
            key: "weatherUnit",
            type: .string
        )
        let policy = SettingsPolicyLookup.guidedMultiRoomSetup

        XCTAssertTrue(policy.canEdit(enabled))
        XCTAssertTrue(policy.canEdit(host))
        XCTAssertFalse(policy.canEdit(unrelated))
        XCTAssertTrue(policy.entry(for: host).fieldPresence.required)
    }

    @MainActor
    func testSetupViewCanBeConstructed() {
        let portalID = PortalID()
        let view = MultiRoomSetupView(
            host: Binding<String>(get: { "server.local" }, set: { _ in }),
            selectedGroupID: Binding<String>(get: { "group" }, set: { _ in }),
            selectedPortalIDs: Binding<Set<PortalID>>(
                get: { [portalID] },
                set: { _ in }
            ),
            phase: .ready(MultiRoomSetupPlan(portals: [], clients: [])),
            groups: [],
            portals: [
                MultiRoomSetupPortalRow(
                    portalID: portalID,
                    name: "Kitchen",
                    matchedClientID: "client",
                    isAmbiguous: false
                )
            ],
            onFindServer: {},
            onApply: {}
        )

        XCTAssertNotNil(view.body)
    }
}
