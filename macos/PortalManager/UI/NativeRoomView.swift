/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

struct NativeRoomPortalRow: Identifiable, Equatable {
    let portalID: PortalID
    let name: String

    var id: PortalID { portalID }
}

struct NativeRoomView: View {
    @Binding var selectedSourceID: PortalID?
    @Binding var selectedReceiverIDs: Set<PortalID>
    let portals: [NativeRoomPortalRow]
    let report: NativeRoomApplyReport?
    let isRunning: Bool
    let isReviewing: Bool
    let reviewMessage: String?
    let onPrepare: () -> Void
    let onConnect: () -> Void
    let onStop: () -> Void

    private var canConnect: Bool {
        !isRunning && isReviewing && selectedSourceID != nil && !selectedReceiverIDs.isEmpty
    }

    private var hasStoppableSelection: Bool {
        selectedSourceID != nil || !selectedReceiverIDs.isEmpty
    }

    var body: some View {
        GlassCard(padding: 22) {
            VStack(alignment: .leading, spacing: 16) {
                header

                Picker("Source room", selection: $selectedSourceID) {
                    Text("Choose source").tag(PortalID?.none)
                    ForEach(portals) { portal in
                        Text(portal.name).tag(Optional(portal.portalID))
                    }
                }
                .pickerStyle(.menu)
                .disabled(isRunning)
                .accessibilityIdentifier("roomlink.source")
                .accessibilityLabel("Source room")
                .accessibilityHint("Choose the Portal whose audio will play in receiving rooms.")

                VStack(alignment: .leading, spacing: 8) {
                    Text("Receiving rooms")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                    ForEach(portals.filter { $0.portalID != selectedSourceID }) { portal in
                        Toggle(isOn: receiverBinding(portal)) {
                            HStack(spacing: 10) {
                                Image(systemName: "hifispeaker.fill")
                                    .foregroundStyle(PortalTheme.warm)
                                Text(portal.name)
                                    .font(.system(size: 13, weight: .medium))
                                Spacer()
                            }
                        }
                        .toggleStyle(.checkbox)
                        .disabled(isRunning)
                        .accessibilityIdentifier("roomlink.receiver.\(portal.portalID.rawValue.uuidString)")
                        .accessibilityLabel("Use \(portal.name) as a receiving room")
                        .accessibilityValue(isSelected(portal) ? "Selected" : "Not selected")
                    }
                }

                if let reviewMessage {
                    Label(reviewMessage, systemImage: "exclamationmark.triangle")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(PortalTheme.warning)
                }

                HStack(spacing: 10) {
                    PrimaryButton(
                        title: isRunning ? "Setting up..." : "Set Up Rooms",
                        systemImage: "dot.radiowaves.left.and.right",
                        disabled: !canConnect
                    ) {
                        onConnect()
                    }
                        .accessibilityIdentifier("roomlink.connect")
                        .accessibilityLabel("Set up selected rooms")

                    GhostButton(
                        title: "Stop Rooms",
                        systemImage: "stop.circle",
                        disabled: isRunning || !hasStoppableSelection
                    ) {
                        onStop()
                    }
                        .accessibilityIdentifier("roomlink.stop")
                        .accessibilityLabel("Stop selected rooms")

                    GhostButton(title: "Check Setup", systemImage: "checklist") {
                        onPrepare()
                    }
                        .accessibilityIdentifier("roomlink.check")
                        .accessibilityLabel("Check Room Link setup")
                }

                if let report {
                    resultSection(report)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 11) {
                GradientIcon(
                    systemName: "hifispeaker.2.badge.plus",
                    size: 36,
                    colors: [PortalTheme.accent, PortalTheme.accentSecondary]
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text("Room Link")
                        .font(.system(size: 15, weight: .semibold))
                    Text("Play this room's sound on the Portals you choose.")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func receiverBinding(_ portal: NativeRoomPortalRow) -> Binding<Bool> {
        Binding<Bool>(
            get: { selectedReceiverIDs.contains(portal.portalID) },
            set: { isSelected in
                if isSelected {
                    selectedReceiverIDs.insert(portal.portalID)
                } else {
                    selectedReceiverIDs.remove(portal.portalID)
                }
            }
        )
    }

    private func isSelected(_ portal: NativeRoomPortalRow) -> Bool {
        selectedReceiverIDs.contains(portal.portalID)
    }

    private func resultSection(_ report: NativeRoomApplyReport) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(report.isFullySuccessful ? "Setup applied" : "Setup needs attention")
                .font(.system(size: 12.5, weight: .semibold))
                .foregroundStyle(report.isFullySuccessful ? PortalTheme.success : PortalTheme.warning)
            ForEach(report.results) { result in
                HStack(spacing: 8) {
                    Image(systemName: result.isSuccess ? "checkmark.circle" : "xmark.circle")
                        .foregroundStyle(result.isSuccess ? PortalTheme.success : PortalTheme.danger)
                    Text(result.name.isEmpty ? "Portal" : result.name)
                        .font(.system(size: 12.5))
                    Spacer()
                    if let error = result.error {
                        Text(error.sanitizedMessage)
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .multilineTextAlignment(.trailing)
                    }
                }
            }
        }
        .padding(13)
        .background(RoundedRectangle(cornerRadius: 10).fill(PortalTheme.well))
    }
}
