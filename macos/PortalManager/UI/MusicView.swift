/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// Music Assistant topology plus actionable Snapcast room controls.
struct MusicView: View {
    @EnvironmentObject var store: PortalManagerStore

    @State private var groupNames: [String: String] = [:]
    @State private var clientVolumes: [String: Int] = [:]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header
                NativeRoomView(
                    selectedSourceID: $store.nativeRoomSelectedSourceID,
                    selectedReceiverIDs: $store.nativeRoomSelectedReceiverIDs,
                    portals: store.nativeRoomPortalRows,
                    report: store.nativeRoomReport,
                    isRunning: store.nativeRoomIsRunning,
                    isReviewing: store.nativeRoomHasReviewedSelection,
                    reviewMessage: store.nativeRoomReviewMessage,
                    onPrepare: { Task { await store.prepareNativeRoom() } },
                    onConnect: { Task { await store.applyNativeRoom() } },
                    onStop: { Task { await store.stopNativeRooms() } }
                )
                MultiRoomSetupView(
                    host: $store.snapcastHostInput,
                    selectedGroupID: $store.multiRoomSelectedGroupID,
                    selectedPortalIDs: $store.multiRoomSelectedPortalIDs,
                    phase: store.multiRoomSetupPhase,
                    groups: store.snapcastSnapshot?.groups ?? [],
                    portals: store.multiRoomSetupPortalRows,
                    onFindServer: { Task { await store.findMultiRoomServer() } },
                    onApply: { Task { await store.applyMultiRoomSetup() } }
                )
                hostInputs

                if store.musicAssistantSnapshot != nil || store.snapcastSnapshot != nil {
                    HStack(alignment: .top, spacing: 18) {
                        if let ma = store.musicAssistantSnapshot {
                            maCard(ma)
                        }
                        if let snap = store.snapcastSnapshot {
                            snapcastCard(snap)
                        }
                    }
                } else {
                    emptyState
                }

                Spacer(minLength: 24)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
        }
        .navigationTitle("")
    }

    // MARK: Sections

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Music")
                    .font(.pmDisplay(26))
                Text("Set up rooms, volume, and playback for your local multi-room system.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            PrimaryButton(
                title: store.musicRefreshing ? "Reading…" : "Refresh",
                systemImage: "antenna.radiowaves.left.and.right",
                disabled: store.musicRefreshing
            ) {
                store.dispatch(.refreshMusic)
            }
        }
    }

    private var hostInputs: some View {
        GlassCard(padding: 20) {
            HStack(spacing: 16) {
                HostField(
                    icon: "hifispeaker.2",
                    tint: PortalTheme.accent,
                    title: "Music Assistant",
                    placeholder: "host or IP (default :8095)",
                    text: $store.maHostInput
                )
                Divider().frame(height: 40).overlay(PortalTheme.line)
                HostField(
                    icon: "waveform",
                    tint: PortalTheme.warm,
                    title: "Snapcast",
                    placeholder: "host or IP (default :1705)",
                    text: $store.snapcastHostInput
                )
            }
        }
    }

    private func maCard(_ snapshot: MusicTopologySnapshot) -> some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    GradientIcon(systemName: "hifispeaker.2", size: 38, colors: [PortalTheme.accent, PortalTheme.accentSecondary])
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Music Assistant")
                            .font(.system(size: 15, weight: .semibold))
                        connectionPill(snapshot.connectionState)
                    }
                    Spacer()
                    Text("\(snapshot.players.count) players · \(snapshot.groups.count) groups")
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(.secondary)
                }

                ForEach(snapshot.groups) { group in
                    maGroupRow(group: group, players: snapshot.players)
                }

                ForEach(snapshot.players.filter { $0.groupID == nil }) { player in
                    playerRow(player.name, online: player.online, media: player.currentMediaTitle)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func snapcastCard(_ snapshot: SnapcastTopologySnapshot) -> some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    GradientIcon(systemName: "waveform", size: 38, colors: [PortalTheme.warm, PortalTheme.warning])
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Snapcast")
                            .font(.system(size: 15, weight: .semibold))
                        connectionPill(snapshot.connectionState)
                    }
                    Spacer()
                    Text("\(snapshot.clients.count) clients · \(snapshot.streams.count) streams")
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(.secondary)
                }

                ForEach(snapshot.groups) { group in
                    snapcastGroupCard(group: group, snapshot: snapshot)
                }

                ungroupedClients(snapshot)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private func maGroupRow(group: MAGroup, players: [MAPlayer]) -> some View {
        let members = group.memberPlayerIDs.map { id in
            players.first { $0.playerID == id }?.name ?? id
        }
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "rectangle.stack.badge.person.crop")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(PortalTheme.accentSecondary)
                Text(group.name)
                    .font(.system(size: 13, weight: .semibold))
            }
            Text(members.joined(separator: " · "))
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(.secondary)
                .padding(.leading, 19)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(PortalTheme.well))
    }

    private func snapcastGroupCard(
        group: SnapcastGroupInfo,
        snapshot: SnapcastTopologySnapshot
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "speaker.wave.2.fill")
                    .foregroundStyle(PortalTheme.blue)
                Text(group.name ?? group.groupID)
                    .font(.system(size: 15, weight: .semibold))
                Spacer()
                TextField("Room name", text: Binding<String>(
                    get: { groupNames[group.groupID] ?? "" },
                    set: { groupNames[group.groupID] = $0 }
                ))
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 120)
                GhostButton(title: "Rename", systemImage: "pencil") {
                    let name = (groupNames[group.groupID] ?? "").trimmingCharacters(in: .whitespaces)
                    guard !name.isEmpty else { return }
                    Task { await store.renameSnapcastGroup(groupID: group.groupID, name: name) }
                }
            }

            Picker("Source", selection: Binding<String>(
                get: { group.streamID ?? snapshot.streams.first?.streamID ?? "" },
                set: { streamID in
                    Task { await store.setSnapcastStream(groupID: group.groupID, streamID: streamID) }
                }
            )) {
                ForEach(snapshot.streams) { stream in
                    Text(stream.streamID).tag(stream.streamID)
                }
            }
            .pickerStyle(.menu)

            ForEach(group.clientIDs, id: \.self) { clientID in
                if let client = snapshot.clients.first(where: { $0.clientID == clientID }) {
                    clientControl(client)
                }
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(PortalTheme.well))
    }

    private func ungroupedClients(_ snapshot: SnapcastTopologySnapshot) -> some View {
        let grouped = Set(snapshot.groups.flatMap(\.clientIDs))
        let clients = snapshot.clients.filter { !grouped.contains($0.clientID) }

        return Group {
            if !clients.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    SectionHeader(title: "Available rooms", subtitle: "Add a Portal to a group below")
                    ForEach(clients) { client in
                        HStack(spacing: 10) {
                            Circle()
                                .fill(client.connected ? PortalTheme.success : PortalTheme.danger)
                                .frame(width: 8, height: 8)
                            Text(client.name)
                                .font(.system(size: 13, weight: .medium))
                            Spacer()
                            Menu("Add to room") {
                                ForEach(snapshot.groups) { group in
                                    Button(group.name ?? group.groupID) {
                                        Task {
                                            await store.setSnapcastGroup(
                                                groupID: group.groupID,
                                                clients: group.clientIDs + [client.clientID]
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(PortalTheme.well))
            }
        }
    }

    @ViewBuilder
    private func clientControl(_ client: SnapcastClient_) -> some View {
        let volumeBinding = Binding<Double>(
            get: { Double(clientVolumes[client.clientID] ?? 50) },
            set: { clientVolumes[client.clientID] = Int($0.rounded()) }
        )

        HStack(spacing: 12) {
            Circle()
                .fill(client.connected ? PortalTheme.success : PortalTheme.danger)
                .frame(width: 8, height: 8)
            Text(client.name)
                .font(.system(size: 13, weight: .medium))
                .lineLimit(1)
                .frame(maxWidth: 140, alignment: .leading)
            Slider(value: volumeBinding, in: 0...100)
                .frame(width: 150)
            Text("\(clientVolumes[client.clientID] ?? 50)%")
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(.secondary)
                .frame(width: 34)
            GhostButton(title: "Apply", systemImage: "speaker.wave.1") {
                Task {
                    await store.setSnapcastVolume(
                        clientID: client.clientID,
                        percent: clientVolumes[client.clientID] ?? 50
                    )
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(client.name) volume")
    }

    private func playerRow(_ name: String, online: Bool, media: String?) -> some View {
        HStack(spacing: 10) {
            Circle()
                .fill(online ? PortalTheme.success : Color.secondary.opacity(0.5))
                .frame(width: 7, height: 7)
            Text(name)
                .font(.system(size: 12.5, weight: .medium))
            if let media {
                Text(media)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Player \(name), \(online ? "online" : "offline")")
    }

    private func connectionPill(_ state: MusicServiceConnectionState) -> some View {
        let (title, tone): (String, PillTone) = {
            switch state {
            case .authenticated: return ("Authenticated", .success)
            case .connectedUnauthenticated: return ("Connected", .accent)
            case .authenticationFailed: return ("Auth failed", .danger)
            case .networkFailed: return ("Unreachable", .danger)
            case .timedOut: return ("Timed out", .warning)
            case .connecting: return ("Connecting…", .accent)
            case .disconnected: return ("Disconnected", .neutral)
            }
        }()
        return StatusPill(title: title, tone: tone)
    }

    private var emptyState: some View {
        GlassCard(padding: 44, highlight: true) {
            VStack(spacing: 12) {
                Image(systemName: "hifispeaker.2")
                    .font(.system(size: 44, weight: .light))
                    .foregroundStyle(PortalTheme.accent)
                Text("Connect your multi-room system")
                    .font(.pmDisplay(17))
                Text("Choose Find Rooms and Portal Manager will reuse a server address from a managed Portal when one is available. You can also enter a Snapcast or Music Assistant host manually.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 420)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

private struct HostField: View {
    let icon: String
    let tint: Color
    let title: String
    let placeholder: String
    @Binding var text: String

    var body: some View {
        HStack(spacing: 11) {
            GradientIcon(systemName: icon, size: 32, colors: [tint, tint.opacity(0.65)])
            VStack(alignment: .leading, spacing: 4) {
                Text(title.uppercased())
                    .font(.system(size: 9.5, weight: .bold))
                    .tracking(1)
                    .foregroundStyle(.tertiary)
                TextField(placeholder, text: $text)
                    .textFieldStyle(.plain)
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .fill(PortalTheme.well)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .strokeBorder(PortalTheme.line, lineWidth: 1)
        )
    }
}
