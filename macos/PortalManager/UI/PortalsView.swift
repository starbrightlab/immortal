/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// The Portals workspace: fleet cards plus a tabbed detail inspector for the
/// selected device.
struct PortalsWorkspaceView: View {
    @ObservedObject var store: PortalManagerStore

    var body: some View {
        VStack(spacing: 0) {
            if let selected = store.selectedPortalID.flatMap({ id in
                store.entries.first { $0.id == id }
            }) {
                PortalDetailInspector(entry: selected, store: store)
            } else {
                FleetGrid(store: store)
            }
        }
        .navigationTitle("")
    }
}

// MARK: - Fleet grid

struct FleetGrid: View {
    @ObservedObject var store: PortalManagerStore

    private let columns = [GridItem(.adaptive(minimum: 320, maximum: 420), spacing: 18)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Your Fleet")
                        .font(.pmDisplay(26))
                    Spacer()
                    GhostButton(
                        title: "Scan Network",
                        systemImage: "dot.radiowaves.left.and.right",
                        disabled: store.discoveryRunning
                    ) {
                        store.dispatch(.refreshDiscovery)
                    }
                    PrimaryButton(title: "Add by IP", systemImage: "plus") {
                        store.showManualOnboarding = true
                    }
                }

                if store.entries.isEmpty {
                    EmptyFleetCard()
                } else {
                    LazyVGrid(columns: columns, spacing: 18) {
                        ForEach(store.entries, id: \.id) { entry in
                            PortalCard(entry: entry)
                                .onTapGesture { store.select(entry.id) }
                        }
                    }
                }

                Spacer(minLength: 24)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
        }
    }
}

private struct EmptyFleetCard: View {
    @EnvironmentObject var store: PortalManagerStore

    var body: some View {
        GlassCard(padding: 44, highlight: true) {
            VStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(PortalTheme.blueWash)
                        .frame(width: 110, height: 110)
                    Image(systemName: "tv")
                        .font(.system(size: 44, weight: .medium))
                        .foregroundStyle(PortalTheme.blue)
                }
                Text("No Portals yet")
                    .font(.pmDisplay(21))
                Text("Scan your local network or add a Portal by IP address to begin.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                HStack(spacing: 12) {
                    PrimaryButton(title: "Scan Network", systemImage: "dot.radiowaves.left.and.right") {
                        store.dispatch(.refreshDiscovery)
                    }
                    GhostButton(title: "Add by IP", systemImage: "plus.circle") {
                        store.showManualOnboarding = true
                    }
                }
                .padding(.top, 6)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Portal card

struct PortalCard: View {
    let entry: PortalRegistryEntry

    @State private var hovering = false

    private var tone: PillTone {
        switch entry.connectionState {
        case .online, .bearerAuthenticated: return .success
        case .remoteSessionPaired, .remoteSessionReady: return .warning
        case .offline: return .danger
        default: return .neutral
        }
    }

    var body: some View {
        GlassCard(padding: 20, highlight: hovering) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 13) {
                    GradientIcon(
                        systemName: entry.capabilities?.modelFamily == .portalTV
                            ? "appletv"
                            : "tv",
                        size: 42
                    )
                    VStack(alignment: .leading, spacing: 3) {
                        Text(entry.identity?.name ?? "Portal")
                            .font(.system(size: 15.5, weight: .semibold))
                            .lineLimit(1)
                        Text(modelSummary)
                            .font(.system(size: 11.5, weight: .medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                    StatusPill(title: stateTitle, tone: tone)
                }

                Divider().overlay(PortalTheme.line)

                HStack(spacing: 18) {
                    cardMetric("API", value: apiLevel)
                    cardMetric("Version", value: versionName)
                    cardMetric("Address", value: addressSummary)
                    Spacer()
                }
            }
        }
        .scaleEffect(hovering ? 1.012 : 1)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: hovering)
        .onHover { hovering = $0 }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Portal \(entry.identity?.name ?? "unnamed"), \(stateTitle)")
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

    private var modelSummary: String {
        guard let identity = entry.identity else { return "Awaiting verification" }
        let family = entry.capabilities?.modelFamily.displayName ?? identity.rawModel
        return "\(family) · Immortal \(identity.immortalVersion?.versionName ?? "?")"
    }

    private var apiLevel: String {
        guard let level = entry.capabilities?.androidAPILevel ?? entry.identity?.androidAPILevel else {
            return "—"
        }
        return "\(level)"
    }

    private var versionName: String {
        entry.identity?.immortalVersion?.versionName ?? "—"
    }

    private var addressSummary: String {
        guard let endpoint = entry.endpoint else { return "—" }
        return endpoint.hostOrAddress
    }

    private func cardMetric(_ caption: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(caption)
                .font(.system(size: 9.5, weight: .bold))
                .tracking(1)
                .foregroundStyle(.tertiary)
            Text(value)
                .font(.system(size: 12.5, weight: .medium))
                .lineLimit(1)
        }
    }
}

extension PortalModelFamily {
    var displayName: String {
        switch self {
        case .portal2018: return "Portal (2018)"
        case .portalPlus: return "Portal+"
        case .portalPlusFirstGeneration: return "Portal+ (gen-1)"
        case .portalGo: return "Portal Go"
        case .portalMini: return "Portal Mini"
        case .portalGen2: return "Portal (gen-2)"
        case .portalTV: return "Portal TV"
        case .unknown: return "Unknown model"
        }
    }
}

// MARK: - Detail inspector

/// Tabbed inspector shown after selecting a Portal. Overview first; mutation
/// controls appear only after status, credential scope, and capabilities.
struct PortalDetailInspector: View {
    let entry: PortalRegistryEntry
    @ObservedObject var store: PortalManagerStore

    @State private var tab: DetailSection = .overview

    var body: some View {
        VStack(spacing: 0) {
            // Header strip.
            HStack(spacing: 14) {
                Button {
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
                        store.select(nil)
                    }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 13, weight: .bold))
                        .padding(8)
                        .background(Circle().fill(PortalTheme.well))
                        .foregroundStyle(PortalTheme.ink)
                }
                .buttonStyle(.plain)

                GradientIcon(systemName: "tv", size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.identity?.name ?? "Portal")
                        .font(.pmDisplay(19))
                    Text(entry.endpoint.map { "\($0.hostOrAddress):\($0.port)" } ?? "No endpoint")
                        .font(.system(size: 11.5, weight: .medium, design: .monospaced))
                        .foregroundStyle(.secondary)
                }

                Spacer()

                GhostButton(title: "Refresh", systemImage: "arrow.clockwise") {
                    store.dispatch(.refreshStatus)
                }
                PrimaryButton(title: "Identify", systemImage: "bell.badge") {
                    store.dispatch(.identify)
                }
                .disabled(!store.commandState.canIdentify)
            }
            .padding(.horizontal, 34)
            .padding(.vertical, 18)

            // Section switcher.
            Picker("Section", selection: $tab.animation(.spring(response: 0.4, dampingFraction: 0.9))) {
                ForEach([DetailSection.overview, .settings]) { section in
                    Label(section.title, systemImage: section.systemImage)
                        .tag(section)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(.horizontal, 34)

            ScrollView {
                Group {
                    switch tab {
                    case .overview:
                        PortalOverviewPane(entry: entry, snapshot: store.selectedSnapshot)
                    case .settings:
                        PortalSettingsPane(store: store, entry: entry)
                    default:
                        EmptyView()
                    }
                }
                .padding(.top, 20)
                .padding(.horizontal, 34)
                .transition(.asymmetric(
                    insertion: .opacity.combined(with: .move(edge: .trailing)),
                    removal: .opacity
                ))
            }

            Spacer(minLength: 16)
        }
    }
}

// MARK: - Overview pane

struct PortalOverviewPane: View {
    let entry: PortalRegistryEntry
    let snapshot: PortalSessionSnapshot?
    @EnvironmentObject var store: PortalManagerStore
    @State private var profileAction: AppProfileAction = .install

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            controlsCard
            GlassCard(padding: 24) {
                VStack(alignment: .leading, spacing: 14) {
                    SectionHeader(title: "Identity & Health", subtitle: "Verified through an authenticated Fleet session")
                    infoGrid
                }
            }

            appsCard

            GlassCard(padding: 24) {
                VStack(alignment: .leading, spacing: 14) {
                    SectionHeader(title: "Capabilities")
                    capabilityChips
                }
            }
        }
        .onAppear {
            if selectedAppSummary.isEmpty { store.dispatch(.refreshApps) }
            if store.selectedPortalProfiles.isEmpty { store.dispatch(.refreshAppProfiles) }
        }
    }

    private var selectedAppSummary: [PortalAppSummary] { store.selectedPortalApps }

    @ViewBuilder
    private func appRow(_ app: PortalAppSummary) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(app.name)
                    .font(.system(size: 13, weight: .medium))
                Text(app.packageName)
                    .font(.system(size: 10.5, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            StatusPill(
                title: app.stateTitle,
                tone: app.installedVersionCode == nil ? .neutral : (app.updateAvailable ? .warning : .success)
            )
            GhostButton(
                title: "Sync",
                systemImage: "square.and.arrow.down.on.square",
                disabled: store.fleetSyncRunning || store.pendingAppSyncPackage != nil
            ) {
                store.dispatch(.stageAppSync(app.packageName))
            }
                .accessibilityIdentifier("app.sync.\(app.packageName)")
                .accessibilityLabel("Sync \(app.name) across Portals")
                .accessibilityHint("Choose the ready Portals that should receive this app.")
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(PortalTheme.well))
    }

    private func appSyncConfirmation(_ packageName: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "square.and.arrow.down.on.square")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(PortalTheme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Sync \(packageName)?")
                        .font(.system(size: 14, weight: .semibold))
                    Text("Choose the Portals that should receive this app.")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }

            let eligibleTargets = store.appSyncTargetOptions.filter(\.isEligible)
            if eligibleTargets.isEmpty {
                Text("No ready Portals are available.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(eligibleTargets) { portal in
                        Toggle(isOn: appSyncTargetBinding(portal.portalID)) {
                            HStack(spacing: 8) {
                                Text(portal.name)
                                    .font(.system(size: 12.5, weight: .medium))
                                Text(portal.stateTitle)
                                    .font(.system(size: 10.5, weight: .semibold))
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                        }
                        .toggleStyle(.checkbox)
                        .accessibilityIdentifier("app.sync.target.\(portal.portalID.rawValue.uuidString)")
                        .accessibilityLabel("Sync to \(portal.name), \(portal.stateTitle)")
                        .accessibilityValue(portal.isEligible ? "Eligible" : "Not eligible")
                    }
                }
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 8).fill(PortalTheme.well))
            }

            HStack(spacing: 10) {
                PrimaryButton(
                    title: "Sync Now",
                    systemImage: "checkmark",
                    disabled: store.fleetSyncRunning || store.pendingAppSyncTargets.isEmpty
                ) {
                    store.dispatch(.confirmAppSync)
                }
                    .accessibilityIdentifier("app.sync.confirm")
                    .accessibilityLabel("Sync app now")

                GhostButton(title: "Cancel", systemImage: "xmark") {
                    store.dispatch(.cancelAppSync)
                }
                    .accessibilityIdentifier("app.sync.cancel")
                    .accessibilityLabel("Cancel app sync")
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(PortalTheme.blueWash))
    }

    @ViewBuilder
    private var appSyncResults: some View {
        let rows = store.appSyncTargetOptions.filter { store.fleetSyncResults[$0.portalID] != nil }
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("Last sync")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.secondary)
                ForEach(rows) { portal in
                    if let outcome = store.fleetSyncResults[portal.portalID] {
                        HStack(spacing: 8) {
                            Text(portal.name)
                                .font(.system(size: 12, weight: .medium))
                            Spacer()
                            Text(outcome.title)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(appSyncOutcomeTone(outcome).color)
                                .lineLimit(1)
                        }
                    }
                }
            }
        }
    }

    private func appSyncTargetBinding(_ portalID: PortalID) -> Binding<Bool> {
        Binding<Bool>(
            get: { store.pendingAppSyncTargets.contains(portalID) },
            set: { _ in store.dispatch(.toggleAppSyncTarget(portalID)) }
        )
    }

    private func appSyncOutcomeTone(_ outcome: FleetTargetOutcome) -> PillTone {
        switch outcome {
        case .success: return .success
        case .failure: return .danger
        }
    }

    private var desiredStateSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                SectionHeader(title: "Desired state", subtitle: "Persisted app policy for this Portal")
                Spacer()
                GhostButton(
                    title: store.profilesRefreshing ? "Refreshing…" : "Refresh",
                    systemImage: "arrow.clockwise",
                    disabled: store.profilesRefreshing
                ) {
                    store.dispatch(.refreshAppProfiles)
                }
            }

            Picker("Action", selection: $profileAction) {
                Text("Install").tag(AppProfileAction.install)
                Text("Remove").tag(AppProfileAction.remove)
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            TextField("com.example.app", text: $store.profilePackageInput)
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 12, design: .monospaced))
                .accessibilityLabel("Package identifier")
                .accessibilityHint("Enter the Android package to add as a desired-state profile.")

            if profileAction == .install {
                TextField("Optional direct APK URL", text: $store.profileApkURLInput)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 12, design: .monospaced))
                    .accessibilityLabel("Direct APK URL")
                    .accessibilityHint("Optionally override the catalog download location.")
            }

            PrimaryButton(
                title: "Apply",
                systemImage: "checkmark.circle",
                disabled: !canApplyProfile
            ) {
                store.dispatch(.applyAppProfile(profileAction))
            }
            .accessibilityLabel("Apply desired app state")

            if let portalID = store.selectedPortalID,
               let outcome = store.profileResults[portalID] {
                Text(outcome.title)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(appSyncOutcomeTone(outcome).color)
            }

            if store.selectedPortalProfiles.isEmpty {
                Text(store.profilesRefreshing ? "Reading desired state…" : "No desired app profiles are set.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
            } else {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(store.selectedPortalProfiles) { profile in
                        profileRow(profile)
                    }
                }
            }
        }
    }

    private var canApplyProfile: Bool {
        !store.profilesRefreshing &&
        !store.fleetSyncRunning &&
        !store.profilePackageInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func profileRow(_ profile: FleetAppProfile) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(profile.packageName)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text("\(profile.action) · \(profile.attempts) attempt\(profile.attempts == 1 ? "" : "s")")
                    .font(.system(size: 10.5, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if profile.state != FleetAppProfile.installedState {
                GhostButton(
                    title: "Retry",
                    systemImage: "arrow.clockwise",
                    disabled: store.profilesRefreshing || store.fleetSyncRunning
                ) {
                    store.dispatch(.retryAppProfile(profile))
                }
                .accessibilityLabel("Retry \(profile.packageName)")
            }
            StatusPill(
                title: profile.stateTitle,
                tone: profile.state == FleetAppProfile.failedState
                    ? .danger
                    : (profile.state == FleetAppProfile.installedState ? .success : .warning)
            )
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(PortalTheme.well))
    }

    private var controlsCard: some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(title: "This Room", subtitle: "Volume and playback use the Portal's authenticated control surface")
                HStack(spacing: 12) {
                    IconButton(systemName: "speaker.minus.fill", title: "Lower volume") {
                        store.dispatch(.portalVolume(.down))
                    }
                    IconButton(systemName: "speaker.plus.fill", title: "Raise volume") {
                        store.dispatch(.portalVolume(.up))
                    }
                    IconButton(systemName: "speaker.slash.fill", title: "Toggle mute") {
                        store.dispatch(.portalVolume(.mute))
                    }
                    Spacer()
                    IconButton(systemName: "backward.fill", title: "Previous track") {
                        store.dispatch(.portalMedia(.previous))
                    }
                    IconButton(systemName: "playpause.fill", title: "Play or pause") {
                        store.dispatch(.portalMedia(.playpause))
                    }
                    IconButton(systemName: "forward.fill", title: "Next track") {
                        store.dispatch(.portalMedia(.next))
                    }
                }
            }
        }
    }

    private var appsCard: some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .firstTextBaseline) {
                    SectionHeader(title: "Apps", subtitle: "Catalog state for this Portal")
                    Spacer()
                    GhostButton(
                        title: store.appsRefreshing ? "Refreshing…" : "Refresh",
                        systemImage: "arrow.clockwise",
                        disabled: store.appsRefreshing
                    ) {
                        store.dispatch(.refreshApps)
                    }
                }

                if store.selectedPortalApps.isEmpty {
                    Text(store.appsRefreshing ? "Reading the catalog…" : "No catalog apps are available yet.")
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                } else {
                    if let packageName = store.pendingAppSyncPackage {
                        appSyncConfirmation(packageName)
                    }
                    if !store.fleetSyncResults.isEmpty {
                        appSyncResults
                    }
                    desiredStateSection
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(store.selectedPortalApps) { app in
                            appRow(app)
                        }
                    }
                }
            }
        }
    }

    private var infoGrid: some View {
        let items: [(String, String)] = [
            ("Model", entry.identity?.rawModel ?? "—"),
            ("Family", entry.capabilities?.modelFamily.displayName ?? "—"),
            ("Android API", entry.identity?.androidAPILevel.map(String.init) ?? "—"),
            ("Serial", entry.identity?.serial ?? "—"),
            ("Presence", entry.lastConfirmedStatus?.presence ?? "—"),
            ("Last contact", entry.lastSuccessfulContact.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "—"),
        ]

        return LazyVGrid(columns: [GridItem(.adaptive(minimum: 190))], alignment: .leading, spacing: 14) {
            ForEach(items, id: \.0) { caption, value in
                VStack(alignment: .leading, spacing: 2) {
                    Text(caption.uppercased())
                        .font(.system(size: 9.5, weight: .bold))
                        .tracking(1)
                        .foregroundStyle(.tertiary)
                    Text(value)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                }
            }
        }
    }

    private var capabilityChips: some View {
        let flags: [(String, Bool)] = [
            ("Settings Registry", entry.capabilities?.settingsRegistry == true),
            ("Sources", entry.capabilities?.sources == true),
            ("Screensaver", entry.capabilities?.screensaver == true),
            ("Calendar", entry.capabilities?.calendar == true),
            ("Identify", entry.capabilities?.identify == true),
            ("Reaffirm", entry.capabilities?.reaffirm == true),
        ]
        return FlowLayoutCompat(items: flags)
    }
}

/// Simple wrapping chip layout that works on macOS 13.
struct FlowLayoutCompat: View {
    let items: [(String, Bool)]
    private let chipSpacing: CGFloat = 8

    var body: some View {
        // Fixed two-row chunking keeps the layout deterministic without
        // pulling in Layout protocol backports.
        VStack(alignment: .leading, spacing: chipSpacing) {
            ForEach(0..<max(1, (items.count + 2) / 3), id: \.self) { rowIndex in
                HStack(spacing: chipSpacing) {
                    let start = rowIndex * 3
                    let rowItems = Array(items.dropFirst(start).prefix(3))
                    ForEach(0..<rowItems.count, id: \.self) { index in
                        let item = rowItems[index]
                        StatusPill(
                            title: item.0,
                            tone: item.1 ? .success : .neutral
                        )
                    }
                    Spacer(minLength: 0)
                }
            }
        }
    }
}

// MARK: - Settings pane

struct PortalSettingsPane: View {
    @ObservedObject var store: PortalManagerStore
    let entry: PortalRegistryEntry

    init(store: PortalManagerStore, entry: PortalRegistryEntry) {
        self.store = store
        self.entry = entry
    }

    var body: some View {
        GlassCard(padding: 26) {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(
                    title: "Settings Registry",
                    subtitle: "Schema-driven controls are applied only after policy approval and authoritative read-back."
                )
                SettingsPlaceholderBody(portalID: entry.id)
            }
        }
    }
}

private struct SettingsPlaceholderBody: View {
    let portalID: PortalID
    @EnvironmentObject var store: PortalManagerStore

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(.quaternary)
            Text("Read the registry from this Portal to load its controls.")
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
            PrimaryButton(title: "Load Schema", systemImage: "square.and.arrow.down") {
                store.dispatch(.refreshStatus)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 160)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Settings schema not loaded yet")
    }
}

private struct IconButton: View {
    let systemName: String
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 14, weight: .semibold))
                .frame(width: 36, height: 34)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(PortalTheme.well))
        }
        .buttonStyle(.plain)
        .help(title)
        .accessibilityLabel(title)
    }
}
