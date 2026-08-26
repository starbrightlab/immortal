/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// Consumer-facing casting destination picker for local AirPlay and Chromecast
/// receivers. All protocol work stays behind the injected callbacks.
struct CastingView: View {
    enum RowAction: Equatable {
        case connect(String)
        case disconnect
    }

    let targets: [CastingTarget]
    let states: [CastingTargetID: CastingConnectionState]
    let isScanning: Bool
    let onScan: () -> Void
    let onConnect: (CastingTargetID) -> Void
    let onDisconnect: (CastingTargetID) -> Void
    var playbackStates: [CastingTargetID: CastingPlaybackSnapshot] = [:]
    var pendingPlayback: [CastingTargetID: CastingPlaybackRequest] = [:]
    var onPlay: ((CastingTargetID, CastingPlaybackRequest) -> Void)?
    var onStop: ((CastingTargetID) -> Void)?

    @State private var mediaSourceText = ""
    @State private var mediaTitleText = ""
    @State private var playbackMessage: String?

    private var airPlayTargets: [CastingTarget] {
        targets.filter { $0.kind == .airplay }
    }

    private var chromecastTargets: [CastingTarget] {
        targets.filter { $0.kind == .chromecast }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header

                if targets.isEmpty {
                    emptyState
                } else {
                    targetSection(
                        title: "AirPlay",
                        icon: "airplayaudio",
                        targets: airPlayTargets
                    )
                    targetSection(
                        title: "Chromecast",
                        icon: "tv",
                        targets: chromecastTargets
                    )
                    mediaCard
                }

                Spacer(minLength: 24)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
            .frame(maxWidth: 780, alignment: .leading)
        }
        .navigationTitle("")
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Cast")
                    .font(.pmDisplay(26))
                Text("Send sound or video to a nearby receiver on your home network.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            PrimaryButton(
                title: isScanning ? "Scanning" : "Find Devices",
                systemImage: "dot.radiowaves.left.and.right",
                disabled: isScanning
            ) {
                onScan()
            }
        }
    }

    private var emptyState: some View {
        GlassCard(padding: 44, highlight: true) {
            VStack(spacing: 12) {
                Image(systemName: "airplayvideo")
                    .font(.system(size: 44, weight: .light))
                    .foregroundStyle(PortalTheme.blue)
                Text("No receivers found yet")
                    .font(.pmDisplay(17))
                Text("Choose Find Devices to look for AirPlay and Chromecast receivers.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 420)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var connectedPlaybackTarget: CastingTarget? {
        targets.first { target in
            states[target.id] == .connected
        }
    }

    @ViewBuilder
    private var mediaCard: some View {
        if let target = connectedPlaybackTarget {
            GlassCard(padding: 24) {
                VStack(alignment: .leading, spacing: 14) {
                    SectionHeader(
                        title: "Play on \(target.name)",
                        subtitle: "Use an HTTPS address on your home network."
                    )

                    TextField("https://media.local/movie.mp4", text: $mediaSourceText)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(size: 12.5, design: .monospaced))
                        .accessibilityIdentifier("casting.media.source")
                        .accessibilityLabel("Media address")
                        .accessibilityHint("Enter an HTTPS URL hosted on your local network.")

                    TextField("Title", text: $mediaTitleText)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityIdentifier("casting.media.title")
                        .accessibilityLabel("Media title")

                    HStack(spacing: 12) {
                        PrimaryButton(
                            title: isPlaybackBusy(target.id) ? "Preparing" : "Play",
                            systemImage: "play.fill",
                            disabled: !canPlay(target)
                        ) {
                            play(target)
                        }
                            .accessibilityIdentifier("casting.media.play")
                            .accessibilityLabel("Play on \(target.name)")

                        GhostButton(
                            title: "Stop",
                            systemImage: "stop.fill",
                            disabled: isStopDisabled(target.id)
                        ) {
                            onStop?(target.id)
                        }
                            .accessibilityIdentifier("casting.media.stop")
                            .accessibilityLabel("Stop playback on \(target.name)")
                    }

                    if let playbackMessage {
                        Text(playbackMessage)
                            .font(.system(size: 12))
                            .foregroundStyle(PortalTheme.danger)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func targetSection(
        title: String,
        icon: String,
        targets: [CastingTarget]
    ) -> some View {
        if !targets.isEmpty {
            GlassCard(padding: 24) {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 12) {
                        GradientIcon(systemName: icon, size: 36)
                        Text(title)
                            .font(.system(size: 15, weight: .semibold))
                        Spacer()
                        Text("\(targets.count) available")
                            .font(.system(size: 11.5, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }

                    ForEach(targets, id: \.id) { target in
                        row(target)
                    }
                }
            }
        }
    }

    private func row(_ target: CastingTarget) -> some View {
        let state = states[target.id] ?? .disconnected

        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(target.name)
                    .font(.system(size: 13.5, weight: .medium))
                    .lineLimit(1)
                Text(target.hostOrAddress)
                    .font(.system(size: 10.5, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()
            StatusPill(title: stateTitle(state), tone: stateTone(state), pulse: isBusy(state))

            switch Self.rowAction(for: state) {
            case .disconnect:
                GhostButton(title: "Disconnect", systemImage: "xmark.circle") {
                    onDisconnect(target.id)
                }
                .disabled(isBusy(state))
                .accessibilityLabel("Disconnect \(target.name)")
            case .connect(let title):
                PrimaryButton(title: title, systemImage: "play.fill", disabled: isBusy(state)) {
                    onConnect(target.id)
                }
                .accessibilityLabel("\(title) \(target.name)")
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(PortalTheme.well))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(target.name), \(stateTitle(state))")
    }

    static func rowAction(for state: CastingConnectionState) -> RowAction {
        switch state {
        case .disconnected, .connecting, .disconnecting:
            return .connect("Connect")
        case .failed:
            return .connect("Retry")
        case .connected:
            return .disconnect
        }
    }

    private func play(_ target: CastingTarget) {
        do {
            let request = try CastingPlaybackRequest(
                sourceString: mediaSourceText,
                title: mediaTitleText.isEmpty ? "Selected media" : mediaTitleText,
                contentType: "video/mp4"
            )
            playbackMessage = nil
            onPlay?(target.id, request)
        } catch {
            playbackMessage = "Enter an HTTPS address hosted on your home network."
        }
    }

    private func canPlay(_ target: CastingTarget) -> Bool {
        !mediaSourceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !isPlaybackBusy(target.id)
    }

    private func playbackSnapshot(for id: CastingTargetID) -> CastingPlaybackSnapshot {
        playbackStates[id] ?? .idle
    }

    private func isPlaybackBusy(_ id: CastingTargetID) -> Bool {
        switch playbackSnapshot(for: id).state {
        case .preparing, .stopping: return true
        default: return false
        }
    }

    private func isStopDisabled(_ id: CastingTargetID) -> Bool {
        switch playbackSnapshot(for: id).state {
        case .idle, .stopped, .preparing, .stopping: return true
        case .playing, .failed: return false
        }
    }

    private func isBusy(_ state: CastingConnectionState) -> Bool {
        switch state {
        case .connecting, .disconnecting: return true
        default: return false
        }
    }

    private func stateTitle(_ state: CastingConnectionState) -> String {
        switch state {
        case .disconnected: return "Available"
        case .connecting: return "Connecting"
        case .connected: return "Connected"
        case .disconnecting: return "Stopping"
        case .failed: return "Failed"
        }
    }

    private func stateTone(_ state: CastingConnectionState) -> PillTone {
        switch state {
        case .disconnected: return .neutral
        case .connecting, .disconnecting: return .accent
        case .connected: return .success
        case .failed: return .danger
        }
    }
}
