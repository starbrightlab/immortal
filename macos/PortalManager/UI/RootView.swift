/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// The cinematic split-view shell: ambient canvas behind a frosted sidebar and
/// a detail area routed by the selected destination.
struct RootView: View {
    @StateObject private var store: PortalManagerStore

    @MainActor
    init(store: PortalManagerStore = PortalManagerStore()) {
        _store = StateObject(wrappedValue: store)
    }

    var body: some View {
        NavigationSplitView {
            SidebarView(store: store)
                .frame(minWidth: 248, idealWidth: 268)
                .scrollContentBackground(.hidden)
                .background(.clear)
        } detail: {
            DetailRouter(store: store)
        }
        .background(AmbientCanvas().ignoresSafeArea())
        .preferredColorScheme(.light)
        .environmentObject(store)
        .onAppear { store.bootstrap() }
    }
}

// MARK: - Detail routing

struct DetailRouter: View {
    @ObservedObject var store: PortalManagerStore

    init(store: PortalManagerStore) {
        self.store = store
    }

    var body: some View {
        Group {
            switch store.navigation.selection {
            case .dashboard:
                DashboardView()
            case .portals:
                PortalsWorkspaceView(store: store)
            case .music:
                MusicView()
            case .casting:
                CastingWorkspaceView()
            case .credentials:
                CredentialsWorkspaceView()
            case .bulk:
                BulkView()
case .services:
                ServicesWorkspaceView(store: store)
            case .provisioning:
                ProvisioningView()
            case .release:
                ReleaseEvidenceView()
            case nil:
                DashboardView()
            }
        }
        .toolbarBackground(.hidden, for: .windowToolbar)
    }
}

// MARK: - Credentials & services routing

private struct CredentialsWorkspaceView: View {
    @EnvironmentObject var store: PortalManagerStore

    var body: some View {
        CredentialSyncView(
            selection: $store.credentialSyncSelection,
            portals: store.credentialSyncOptions,
            results: store.credentialSyncResults,
            isRunning: store.credentialSyncRunning
        ) {
            Task { await store.shareCredentials() }
        }
        .navigationTitle("")
    }
}

private struct CastingWorkspaceView: View {
    @EnvironmentObject var store: PortalManagerStore

    var body: some View {
        CastingView(
            targets: store.castingTargets,
            states: store.castingStates,
            isScanning: store.castingScanning,
            onScan: { Task { await store.refreshCasting() } },
            onConnect: { id in Task { await store.connectCasting(id) } },
            onDisconnect: { id in Task { await store.disconnectCasting(id) } },
            playbackStates: store.castingPlayback,
            onPlay: { playbackRequest, playbackTargetID in
                store.dispatch(.playCasting(playbackTargetID, playbackRequest))
            },
            onStop: { id in Task { await store.stopCasting(id) } }
        )
        .onAppear { Task { await store.refreshCasting() } }
    }
}

private struct ServicesWorkspaceView: View {
    @ObservedObject private var serviceController: BackgroundServiceController
    @AppStorage("backgroundServiceEnabled") private var serviceEnabled = false

    init(store: PortalManagerStore) {
        _serviceController = ObservedObject(wrappedValue: store.backgroundServiceController)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Services")
                        .font(.pmDisplay(26))
                    Text("Keep Portal Manager ready without keeping the main window open.")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }

                BackgroundServiceView(
                    state: Binding<BackgroundServiceViewState>(
                        get: {
                            BackgroundServiceViewState(
                                isEnabled: serviceEnabled,
                                lifecycleState: serviceController.lifecycleState,
                                lastHealthCheckAt: serviceController.lastHealthCheckAt,
                                ownership: serviceController.processIdentity?.ownership,
                                serviceVersion: serviceController.processIdentity?.serviceVersion,
                                serviceStartedAt: serviceController.processIdentity?.startedAt
                            )
                        },
                        set: { serviceEnabled = $0.isEnabled }
                    ),
                    onStart: { Task { await serviceController.start() } },
                    onStop: { Task { await serviceController.stop() } },
                    onRestart: { Task { await serviceController.restart() } },
                    onRefreshHealth: { Task { await serviceController.refreshHealth() } }
                )

                Spacer(minLength: 24)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
            .frame(maxWidth: 780, alignment: .leading)
        }
        .navigationTitle("")
        .onAppear {
            guard serviceEnabled,
                  serviceController.lifecycleState == .stopped else { return }
            Task { await serviceController.start() }
        }
    }
}

// MARK: - Sidebar

struct SidebarView: View {
    @ObservedObject var store: PortalManagerStore

    init(store: PortalManagerStore) {
        self.store = store
    }

    var body: some View {
        List {
            Section {
                ForEach(SidebarDestination.allCases) { destination in
                    SidebarRow(destination: destination)
                        .contentShape(Rectangle())
                        .onTapGesture { store.navigate(to: destination) }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(destination.title)
                        .accessibilityAddTraits(.isButton)
                }
            }

            if !store.entries.isEmpty {
                Section("Your Fleet") {
                    ForEach(store.entries, id: \.id) { entry in
                            FleetSidebarRow(entry: entry)
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    store.select(entry.id)
                                    store.navigate(to: .portals)
                                }
                                .accessibilityElement(children: .combine)
                                .accessibilityAddTraits(.isButton)
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .navigationTitle("")
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 9) {
                    RoundedRectangle(cornerRadius: 5, style: .continuous)
                        .fill(PortalTheme.blue)
                        .frame(width: 18, height: 18)
                    Text("Portal Manager")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(PortalTheme.ink)
                }

                HStack(spacing: 8) {
                    StatusPill(
                        title: store.discoveryRunning ? "Scanning" : "LAN",
                        tone: store.discoveryRunning ? .accent : .neutral,
                        pulse: store.discoveryRunning
                    )
                    Spacer()
                    if !store.entries.isEmpty {
                        let up = store.entries.filter {
                            if case .online = $0.connectionState { return true }
                            return false
                        }.count
                        Text("\(up) online")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(PortalTheme.inkSecondary)
                    }
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
    }
}

struct SidebarRow: View {
    let destination: SidebarDestination
    @EnvironmentObject var store: PortalManagerStore

    private var isSelected: Bool {
        store.navigation.selection == destination
    }

    var body: some View {
        HStack(spacing: 11) {
            Image(systemName: destination.systemImage)
                .font(.system(size: 13.5, weight: .medium))
                .foregroundStyle(isSelected ? PortalTheme.blue : PortalTheme.inkSecondary)
                .frame(width: 24, height: 24)
            Text(destination.title)
                .font(.system(size: 13.5, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? PortalTheme.blue : PortalTheme.ink)
            Spacer(minLength: 0)
            if isSelected {
                Circle()
                    .fill(PortalTheme.blue)
                    .frame(width: 5, height: 5)
            }
            if destination == .portals && !store.entries.isEmpty {
                Text("\(store.entries.count)")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(PortalTheme.well))
                    .foregroundStyle(PortalTheme.inkSecondary)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(isSelected ? PortalTheme.blueWash : Color.clear)
        )
        .contentShape(Rectangle())
    }
}

struct FleetSidebarRow: View {
    let entry: PortalRegistryEntry

    private var tone: PillTone {
        switch entry.connectionState {
        case .online, .bearerAuthenticated:
            return .success
        case .remoteSessionPaired, .remoteSessionReady:
            return .warning
        case .offline:
            return .danger
        default:
            return .neutral
        }
    }

    private var stateTitle: String {
        switch entry.connectionState {
        case .online: return "Online"
        case .bearerAuthenticated: return "Verified"
        case .remoteSessionPaired, .remoteSessionReady: return "Session only"
        case .offline: return "Offline"
        case .discovered: return "Discovered"
        case .pairingRequired: return "Pairing"
        case .provisioning: return "Provisioning"
        case .reauthenticationRequired: return "Re-auth"
        case .unsupported: return "Unsupported"
        case .error: return "Error"
        default: return "Idle"
        }
    }

    var body: some View {
        HStack(spacing: 11) {
            Circle()
                .fill(tone.color)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.identity?.name ?? "Portal")
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                Text(stateTitle)
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(tone.color.opacity(0.85))
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(entry.identity?.name ?? "Portal"), \(stateTitle)")
    }
}
