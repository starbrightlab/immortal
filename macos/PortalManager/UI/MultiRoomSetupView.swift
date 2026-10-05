/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

struct MultiRoomSetupPortalRow: Identifiable, Equatable {
    let portalID: PortalID
    let name: String
    let matchedClientID: String?
    let isAmbiguous: Bool

    var id: PortalID { portalID }
}

struct MultiRoomSetupView: View {
    @Binding private var host: String
    @Binding private var selectedGroupID: String
    @Binding private var selectedPortalIDs: Set<PortalID>
    private let phase: MultiRoomSetupPhase
    private let groups: [SnapcastGroupInfo]
    private let portals: [MultiRoomSetupPortalRow]
    private let onFindServer: () -> Void
    private let onApply: () -> Void

    init(
        host: Binding<String>,
        selectedGroupID: Binding<String>,
        selectedPortalIDs: Binding<Set<PortalID>>,
        phase: MultiRoomSetupPhase,
        groups: [SnapcastGroupInfo],
        portals: [MultiRoomSetupPortalRow],
        onFindServer: @escaping () -> Void,
        onApply: @escaping () -> Void
    ) {
        _host = host
        _selectedGroupID = selectedGroupID
        _selectedPortalIDs = selectedPortalIDs
        self.phase = phase
        self.groups = groups
        self.portals = portals
        self.onFindServer = onFindServer
        self.onApply = onApply
    }

    private var plan: MultiRoomSetupPlan? {
        if case .ready(let value) = phase { return value }
        return nil
    }

    private var isBusy: Bool {
        switch phase {
        case .checking, .applying: return true
        default: return false
        }
    }

    private var canApply: Bool {
        guard case .ready = phase else { return false }
        return !selectedPortalIDs.isEmpty
            && !selectedGroupID.isEmpty
            && !host.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 18) {
                header
                serverField

                if let plan {
                    roomPicker
                    portalList(plan)
                    applyButton
                } else if case .failed(let message) = phase {
                    Text(message)
                        .font(.system(size: 12.5))
                        .foregroundStyle(PortalTheme.danger)
                } else if case .completed(let attempted, let succeeded) = phase {
                    Text(succeeded == attempted
                        ? "Your selected rooms are set up."
                        : "Finished with \(attempted - succeeded) issue(s). Review each Portal.")
                        .font(.system(size: 12.5, weight: .medium))
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("multiroom.setup")
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("Guided multi-room setup")
                .font(.system(size: 15.5, weight: .semibold))
            Text("Find your audio server, choose rooms, and Portal Manager configures each Portal.")
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
        }
    }

    private var serverField: some View {
        HStack(spacing: 12) {
            TextField("Audio server host or IP", text: $host)
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 13, design: .monospaced))
                .disabled(isBusy)

            GhostButton(
                title: isBusy ? "Checking" : "Find Rooms",
                systemImage: "magnifyingglass",
                disabled: isBusy
            ) {
                onFindServer()
            }
        }
    }

    private var roomPicker: some View {
        Picker("Room group", selection: $selectedGroupID) {
            Text("Choose a room group").tag("")
            ForEach(groups) { group in
                Text(group.name ?? group.groupID).tag(group.groupID)
            }
        }
        .pickerStyle(.menu)
        .frame(maxWidth: 280, alignment: .leading)
    }

    private func portalList(_ plan: MultiRoomSetupPlan) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if portals.isEmpty {
                Text("No managed Portals are available.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
            } else {
                ForEach(portals) { portal in
                    Toggle(isOn: selectionBinding(portal)) {
                        HStack(spacing: 10) {
                            Text(portal.name)
                                .font(.system(size: 13, weight: .medium))
                            Spacer()
                            if portal.isAmbiguous {
                                StatusPill(title: "Review name", tone: .warning)
                            } else if portal.matchedClientID != nil {
                                StatusPill(title: "Ready", tone: .success)
                            } else {
                                StatusPill(title: "Not found", tone: .neutral)
                            }
                        }
                    }
                    .toggleStyle(.checkbox)
                    .disabled(portal.matchedClientID == nil || isBusy)
                }
            }

            if plan.unmatchedClientIDs.isEmpty == false {
                Text("\(plan.unmatchedClientIDs.count) audio device(s) were not matched to a Portal.")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var applyButton: some View {
        HStack(spacing: 10) {
            PrimaryButton(
                title: isBusy ? "Setting up" : "Set Up Rooms",
                systemImage: "hifispeaker.2.fill",
                disabled: !canApply
            ) {
                onApply()
            }

            if case .applying(let finished, let total) = phase {
                ProgressView(value: Double(finished), total: Double(max(1, total)))
                    .frame(maxWidth: 180)
            }
        }
    }

    private func selectionBinding(_ portal: MultiRoomSetupPortalRow) -> Binding<Bool> {
        Binding(
            get: { selectedPortalIDs.contains(portal.portalID) },
            set: { enabled in
                if enabled {
                    selectedPortalIDs.insert(portal.portalID)
                } else {
                    selectedPortalIDs.remove(portal.portalID)
                }
            }
        )
    }
}
