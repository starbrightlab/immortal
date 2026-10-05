/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// A non-secret display item supplied by the application layer.
struct CredentialSyncPortalOption: Identifiable, Equatable {
    let portalID: PortalID
    let name: String
    let isEligibleSource: Bool
    let isEligibleTarget: Bool

    var id: PortalID { portalID }
}

/// Operator-controlled credential-share state. It contains identifiers and
/// field selections only; secret bytes remain behind the Keychain boundary.
struct CredentialSyncSelection: Equatable {
    var sourcePortalID: PortalID?
    var sourceID = ""
    var fields: Set<ShareableCredentialField> = []
    var targetIDs: Set<PortalID> = []
}

/// Consumer-facing flow for explicitly copying selected photo-source fields to
/// chosen Portals. The view owns no store dependency and never renders a value
/// read from Keychain or a network response.
struct CredentialSyncView: View {
    @Binding private var selection: CredentialSyncSelection
    private let portals: [CredentialSyncPortalOption]
    private let results: [CredentialSyncResult]
    private let isRunning: Bool
    private let onShare: () -> Void

    init(
        selection: Binding<CredentialSyncSelection>,
        portals: [CredentialSyncPortalOption],
        results: [CredentialSyncResult],
        isRunning: Bool,
        onShare: @escaping () -> Void
    ) {
        _selection = selection
        self.portals = portals
        self.results = results.sorted { $0.portalID.rawValue.uuidString < $1.portalID.rawValue.uuidString }
        self.isRunning = isRunning
        self.onShare = onShare
    }

    private var sourceOptions: [CredentialSyncPortalOption] {
        portals.filter(\.isEligibleSource)
    }

    private var targetOptions: [CredentialSyncPortalOption] {
        portals.filter(\.isEligibleTarget)
    }

    private var trimmedSourceID: String {
        selection.sourceID.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canShare: Bool {
        selection.sourcePortalID != nil
            && !trimmedSourceID.isEmpty
            && !selection.fields.isEmpty
            && !selection.targetIDs.isEmpty
            && !isRunning
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header
                sourceCard
                fieldCard
                destinationCard
                actionCard

                if !results.isEmpty {
                    resultCard
                }
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
            .frame(maxWidth: 780, alignment: .leading)
        }
        .navigationTitle("Credential Sharing")
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Credential Sharing")
                .font(.pmDisplay(26))
            Text("Copy selected source credentials between managed Portals.")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
        }
    }

    private var sourceCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader(
                    title: "Source",
                    subtitle: "Choose the Portal that already has the credentials."
                )

                Picker("Source Portal", selection: sourceBinding) {
                    Text("Select a Portal").tag(Optional<PortalID>.none)
                    ForEach(sourceOptions) { option in
                        Text(option.name).tag(Optional(option.portalID))
                    }
                }
                .labelsHidden()

                VStack(alignment: .leading, spacing: 6) {
                    Text("Source name")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                    TextField("Photo source", text: $selection.sourceID)
                        .textFieldStyle(.roundedBorder)
                        .disabled(selection.sourcePortalID == nil || isRunning)
                }
            }
        }
    }

    private var fieldCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader(
                    title: "Fields",
                    subtitle: "Only these closed fields are eligible. Values are never shown."
                )

                fieldToggle("Immich key", field: .immichKey) { newValue in
                    change(.immichKey, newValue)
                }
                Divider().overlay(PortalTheme.line)
                fieldToggle("SMB user", field: .smbUser) { newValue in
                    changePair(.smbUser, .smbPass, enabled: newValue)
                }
                fieldToggle("SMB password", field: .smbPass) { newValue in
                    changePair(.smbPass, .smbUser, enabled: newValue)
                }
                Divider().overlay(PortalTheme.line)
                fieldToggle("WebDAV user", field: .davUser) { newValue in
                    changePair(.davUser, .davPass, enabled: newValue)
                }
                fieldToggle("WebDAV password", field: .davPass) { newValue in
                    changePair(.davPass, .davUser, enabled: newValue)
                }
            }
        }
        .disabled(selection.sourcePortalID == nil || isRunning)
    }

    private func fieldToggle(
        _ title: String,
        field: ShareableCredentialField,
        onChange: @escaping (Bool) -> Void
    ) -> some View {
        Toggle(isOn: Binding(
            get: { selection.fields.contains(field) },
            set: onChange
        )) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 13, weight: .medium))
                Text("Configured")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
            }
        }
        .toggleStyle(.switch)
    }

    private var destinationCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader(
                    title: "Destinations",
                    subtitle: "Only online, authenticated Portals with the sources capability are listed."
                )

                if targetOptions.isEmpty {
                    Text("No eligible destinations are available.")
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(targetOptions) { option in
                        Toggle(isOn: targetBinding(for: option.portalID)) {
                            HStack(spacing: 10) {
                                Text(option.name)
                                    .font(.system(size: 13, weight: .medium))
                                Spacer()
                                StatusPill(title: "Eligible", tone: .success)
                            }
                        }
                        .toggleStyle(.checkbox)
                        .disabled(isRunning)
                    }
                }
            }
        }
        .disabled(selection.sourcePortalID == nil || isRunning)
    }

    private var actionCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                Text("Review and share")
                    .font(.pmDisplay(17))
                Text("\(selectedSummary) will be sent independently to \(selection.targetIDs.count) selected Portal\(selection.targetIDs.count == 1 ? "" : "s"). The request is not sent until you choose Share.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)

                HStack(spacing: 12) {
                    PrimaryButton(
                        title: isRunning ? "Sharing" : "Share",
                        systemImage: "key.horizontal.fill",
                        disabled: !canShare
                    ) {
                        onShare()
                    }

                    if isRunning {
                        ProgressView()
                            .controlSize(.small)
                    }
                }
            }
        }
    }

    private var resultCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader(
                    title: "Results",
                    subtitle: "Each destination reports its own terminal outcome."
                )

                ForEach(results, id: \.portalID) { result in
                    resultRow(result)
                }
            }
        }
    }

    private func resultRow(_ result: CredentialSyncResult) -> some View {
        HStack(spacing: 12) {
            StatusPill(title: outcomeLabel(result.outcome), tone: outcomeTone(result.outcome))
            Text(portalName(result.portalID))
                .font(.system(size: 13, weight: .medium))
            Spacer()
            Text(outcomeExplanation(result.outcome))
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(PortalTheme.well))
        .accessibilityElement(children: .combine)
    }

    private var sourceBinding: Binding<PortalID?> {
        Binding(
            get: { selection.sourcePortalID },
            set: { newValue in
                selection.sourcePortalID = newValue
                if newValue != selection.targetIDs.first,
                   selection.targetIDs.contains(newValue ?? firstTargetSentinel()) {
                    selection.targetIDs.remove(newValue!)
                }
            }
        )
    }

    private func targetBinding(for portalID: PortalID) -> Binding<Bool> {
        Binding(
            get: { selection.targetIDs.contains(portalID) },
            set: { isSelected in
                if isSelected {
                    selection.targetIDs.insert(portalID)
                } else {
                    selection.targetIDs.remove(portalID)
                }
            }
        )
    }

    private func firstTargetSentinel() -> PortalID {
        PortalID(rawValue: UUID())
    }

    private func change(_ field: ShareableCredentialField, _ isEnabled: Bool) {
        if isEnabled {
            selection.fields.insert(field)
        } else {
            selection.fields.remove(field)
        }
    }

    private func changePair(
        _ changed: ShareableCredentialField,
        _ pairedWith: ShareableCredentialField,
        enabled: Bool
    ) {
        if enabled {
            selection.fields.formUnion([changed, pairedWith])
        } else {
            selection.fields.subtract([changed, pairedWith])
        }
    }

    private var selectedSummary: String {
        let names = [
            selection.fields.contains(.immichKey) ? "Immich" : nil,
            selection.fields.contains(.smbUser) ? "SMB" : nil,
            selection.fields.contains(.davUser) ? "WebDAV" : nil
        ].compactMap { $0 }

        return names.isEmpty ? "No fields" : names.joined(separator: ", ")
    }

    private func portalName(_ portalID: PortalID) -> String {
        portals.first { $0.portalID == portalID }?.name
            ?? String(portalID.rawValue.uuidString.prefix(8))
    }

    private func outcomeLabel(_ outcome: CredentialSyncOutcome) -> String {
        switch outcome {
        case .succeeded: return "Accepted"
        case .offline: return "Offline"
        case .rejected: return "Rejected"
        case .cancelled: return "Cancelled"
        }
    }

    private func outcomeTone(_ outcome: CredentialSyncOutcome) -> PillTone {
        switch outcome {
        case .succeeded: return .success
        case .offline: return .warning
        case .rejected: return .danger
        case .cancelled: return .neutral
        }
    }

    private func outcomeExplanation(_ outcome: CredentialSyncOutcome) -> String {
        switch outcome {
        case .succeeded: return "The destination confirmed the update."
        case .offline: return "The destination was unavailable."
        case .rejected: return "The destination did not accept the update."
        case .cancelled: return "The operation stopped before confirmation."
        }
    }
}
