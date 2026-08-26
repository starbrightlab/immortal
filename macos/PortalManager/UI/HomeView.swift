/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// The cinematic landing surface: hero header, live fleet metrics, and quick
/// actions. Everything is sanitized, non-secret projection state.
struct DashboardView: View {
    @EnvironmentObject var store: PortalManagerStore

    private var onlineCount: Int {
        store.entries.filter {
            if case .online = $0.connectionState { return true }
            return false
        }.count
    }

    private var sessionOnlyCount: Int {
        store.entries.filter {
            switch $0.connectionState {
            case .remoteSessionPaired, .remoteSessionReady:
                return true
            default:
                return false
            }
        }.count
    }

    private var candidatesCount: Int { store.discoveryCandidates.count }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                hero

                HStack(alignment: .top, spacing: 18) {
                    quickActions
                    activityFeed
                }

                Spacer(minLength: 30)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
        }
        .navigationTitle("")
    }

    // MARK: Hero

    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 5..<12: return "Good morning."
        case 12..<18: return "Good afternoon."
        default: return "Good evening."
        }
    }

    private var hero: some View {
        GlassCard(padding: 30, highlight: true) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline) {
                    Text(greeting)
                        .font(.pmDisplay(30))
                        .tracking(-0.6)
                    Spacer()
                    PrimaryButton(
                        title: store.discoveryRunning ? "Scanning…" : "Scan Network",
                        systemImage: "dot.radiowaves.left.and.right",
                        disabled: store.discoveryRunning
                    ) {
                        store.dispatch(.refreshDiscovery)
                    }
                    .accessibilityIdentifier("dashboard.scan")
                }

                Text(store.statusMessage)
                    .font(.system(size: 13.5))
                    .foregroundStyle(PortalTheme.textDim)
                    .contentTransition(.opacity)
                    .animation(.easeInOut(duration: 0.25), value: store.statusMessage)
                    .padding(.top, 6)
                    .accessibilityIdentifier("dashboard.status")

                Rectangle()
                    .fill(PortalTheme.line)
                    .frame(height: 1)
                    .padding(.vertical, 16)

                HStack(spacing: 28) {
                    heroStat("\(store.entries.count)", label: "Portals")
                    hairline
                    heroStat("\(onlineCount)", label: "Online")
                    hairline
                    heroStat("\(sessionOnlyCount)", label: "Session only")
                    hairline
                    heroStat("\(candidatesCount)", label: "Nearby")
                    Spacer()
                }
            }
        }
    }

    private var hairline: some View {
        Rectangle()
            .fill(PortalTheme.line)
            .frame(width: 1, height: 30)
    }

    private func heroStat(_ value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.pmDisplay(21))
                .contentTransition(.numericText())
            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(PortalTheme.inkSecondary)
        }
    }

    // MARK: Metrics


    // MARK: Quick actions + feed

    private var quickActions: some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(title: "Quick Actions")
                VStack(spacing: 10) {
                    QuickActionRow(
                        title: "Add a Portal by IP",
                        detail: "Manual onboarding without mDNS",
                        symbol: "plus.circle",
                        tint: PortalTheme.accent
                    ) {
                        store.dispatch(.openManualEndpoint)
                    }
                    QuickActionRow(
                        title: "Set Up Rooms",
                        detail: "Group rooms and control volume",
                        symbol: "hifispeaker.2",
                        tint: PortalTheme.warm
                    ) {
                        store.navigate(to: .music)
                    }
                    QuickActionRow(
                        title: "Cast Nearby",
                        detail: "Find AirPlay and Chromecast devices",
                        symbol: "airplayvideo",
                        tint: PortalTheme.accent
                    ) {
                        store.navigate(to: .casting)
                    }
                    QuickActionRow(
                        title: "Share Credentials",
                        detail: "Copy source access to another Portal",
                        symbol: "key.horizontal.fill",
                        tint: PortalTheme.warm
                    ) {
                        store.navigate(to: .credentials)
                    }
                    QuickActionRow(
                        title: "Check Fleet Health",
                        detail: "Review readiness and compatibility",
                        symbol: "checkmark.seal",
                        tint: PortalTheme.accentSecondary
                    ) {
                        store.navigate(to: .release)
                    }
                }
            }
            .frame(maxWidth: 400, alignment: .leading)
        }
    }

    private var activityFeed: some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(title: "Nearby Devices")

                if store.discoveryCandidates.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "wifi.slash")
                            .font(.system(size: 30, weight: .light))
                            .foregroundStyle(.quaternary)
                    Text(store.discoveryRunning
                        ? "Looking for Portals…"
                        : "Nothing found yet. Scan again or add a Portal by IP.")
                            .font(.system(size: 12.5))
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, minHeight: 120)
                } else {
                    ForEach(store.discoveryCandidates, id: \.instanceName) { candidate in
                        DiscoveryCandidateRow(candidate: candidate)
                    }
                }
            }
        }
    }
}

// MARK: - Subcomponents

private struct QuickActionRow: View {
    let title: String
    let detail: String
    let symbol: String
    let tint: Color
    let action: () -> Void

    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 13) {
                GradientIcon(systemName: symbol, size: 36, colors: [tint, tint.opacity(0.65)])
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(.primary)
                    Text(detail)
                        .font(.system(size: 11.5))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(PortalTheme.inkSecondary.opacity(0.7))
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(hovering ? PortalTheme.canvas : Color.clear)
            )
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title). \(detail)")
        .accessibilityAddTraits(.isButton)
        .onHover { hovering in
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                self.hovering = hovering
            }
        }
    }
}

struct DiscoveryCandidateRow: View {
    let candidate: BonjourService

    var body: some View {
        HStack(spacing: 12) {
            GradientIcon(systemName: "tv.and.hifispeaker.fill", size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(candidate.instanceName)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                Text("\(candidate.hostOrAddress ?? "?"):\(candidate.port)")
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            StatusPill(title: "Candidate", tone: .accent, pulse: true)
        }
        .padding(.vertical, 4)
    }
}
