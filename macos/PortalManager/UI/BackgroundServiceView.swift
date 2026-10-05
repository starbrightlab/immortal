/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

struct BackgroundServiceViewState: Equatable, Sendable {
    var isEnabled = false
    var lifecycleState: BackgroundServiceLifecycleState = .stopped
    var lastHealthCheckAt: Date?
    var ownership: BackgroundServiceOwnership?
    var serviceVersion: String?
    var serviceStartedAt: Date?

    init(
        isEnabled: Bool = false,
        lifecycleState: BackgroundServiceLifecycleState = .stopped,
        lastHealthCheckAt: Date? = nil,
        ownership: BackgroundServiceOwnership? = nil,
        serviceVersion: String? = nil,
        serviceStartedAt: Date? = nil
    ) {
        self.isEnabled = isEnabled
        self.lifecycleState = lifecycleState
        self.lastHealthCheckAt = lastHealthCheckAt
        self.ownership = ownership
        self.serviceVersion = serviceVersion
        self.serviceStartedAt = serviceStartedAt
    }
}

/// A small ownership surface for the app's local background helper. All
/// lifecycle work stays with the injected callbacks; the view never owns the
/// process or its health checks.
struct BackgroundServiceView: View {
    @Binding private var state: BackgroundServiceViewState
    private let onStart: () -> Void
    private let onStop: () -> Void
    private let onRestart: () -> Void
    var onRefreshHealth: (() -> Void)? = nil

    init(
        state: Binding<BackgroundServiceViewState>,
        onStart: @escaping () -> Void,
        onStop: @escaping () -> Void,
        onRestart: @escaping () -> Void,
        onRefreshHealth: (() -> Void)? = nil
    ) {
        _state = state
        self.onStart = onStart
        self.onStop = onStop
        self.onRestart = onRestart
        self.onRefreshHealth = onRefreshHealth
    }

    var body: some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Portal Manager service")
                            .font(.system(size: 15.5, weight: .semibold))
                        Text(statusDetail)
                            .font(.system(size: 12.5))
                            .foregroundStyle(PortalTheme.textDim)
                    }

                    Spacer()
                    StatusPill(title: statusTitle, tone: statusTone, pulse: isBusy)
                }

                Toggle(isOn: enabledBinding) {
                    Text("Keep Portal Manager running in the background")
                        .font(.system(size: 13, weight: .medium))
                }
                .toggleStyle(.switch)
                .disabled(isBusy)
                .accessibilityIdentifier("background.service.enabled")
                .accessibilityLabel("Background service")
                .accessibilityValue(statusTitle)
                .accessibilityHint("Starts or stops the local Portal Manager helper.")

                if let errorMessage {
                    Text(errorMessage)
                        .font(.system(size: 12.5))
                        .foregroundStyle(PortalTheme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if let checkedAt = state.lastHealthCheckAt {
                    Text("Last checked \(checkedAt.formatted(.relative(presentation: .named)))")
                        .font(.system(size: 12))
                        .foregroundStyle(PortalTheme.inkSecondary)
                }

                if isRunning, let ownership = state.ownership {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(ownershipTitle(ownership))
                            .font(.system(size: 12.5, weight: .medium))

                        Text(serviceDetails)
                            .font(.system(size: 12))
                            .foregroundStyle(PortalTheme.textDim)
                    }
                }

                HStack(spacing: 10) {
                    GhostButton(
                        title: "Start",
                        systemImage: "play.fill",
                        disabled: !canStart
                    ) {
                        onStart()
                    }
                    .accessibilityLabel("Start background service")

                    GhostButton(
                        title: "Stop",
                        systemImage: "stop.fill",
                        disabled: !canStop
                    ) {
                        onStop()
                    }
                    .accessibilityLabel("Stop background service")

                    GhostButton(
                        title: "Restart",
                        systemImage: "arrow.clockwise",
                        disabled: !canRestart
                    ) {
                        onRestart()
                    }
                    .accessibilityLabel("Restart background service")
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("background.service")
        .task(id: isRunning) {
            guard isRunning else { return }

            while !Task.isCancelled {
                onRefreshHealth?()
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    private var enabledBinding: Binding<Bool> {
        Binding<Bool>(
            get: { state.isEnabled },
            set: { newValue in
                guard !isBusy else { return }
                state.isEnabled = newValue
                if newValue {
                    onStart()
                } else {
                    onStop()
                }
            }
        )
    }

    private var isBusy: Bool {
        switch state.lifecycleState {
        case .starting, .stopping:
            return true
        case .stopped, .running, .failed:
            return false
        }
    }

    private var canStart: Bool {
        if isBusy || state.lifecycleState == .running { return false }
        return true
    }

    private var canStop: Bool {
        state.lifecycleState == .running
    }

    private var canRestart: Bool {
        switch state.lifecycleState {
        case .stopped, .running, .failed:
            return true
        case .starting, .stopping:
            return false
        }
    }

    private var statusTitle: String {
        switch state.lifecycleState {
        case .stopped: return "Stopped"
        case .starting: return "Starting"
        case .running: return "Running"
        case .stopping: return "Stopping"
        case .failed: return "Error"
        }
    }

    private var statusTone: PillTone {
        switch state.lifecycleState {
        case .stopped: return .neutral
        case .starting, .stopping: return .accent
        case .running: return .success
        case .failed: return .danger
        }
    }

    private var statusDetail: String {
        switch state.lifecycleState {
        case .stopped:
            return "The helper is off. Portals remain available without it."
        case .starting:
            return "Getting ready..."
        case .running:
            if state.ownership == .adopted {
                return "Already running; Portal Manager will use it."
            }
            return "Ready and running on this Mac."
        case .stopping:
            return "Finishing up before closing."
        case .failed(let message):
            return message
        }
    }

    private var errorMessage: String? {
        if case .failed(let message) = state.lifecycleState {
            return message
        }
        return nil
    }

    private var isRunning: Bool {
        state.lifecycleState == .running
    }

    private func ownershipTitle(_ ownership: BackgroundServiceOwnership) -> String {
        switch ownership {
        case .adopted:
            return "Using the service that was already running"
        case .launched:
            return "Started by Portal Manager"
        }
    }

    private var serviceDetails: String {
        var details: [String] = []

        if let serviceVersion = state.serviceVersion {
            details.append("Version \(serviceVersion)")
        }
        if let startedAt = state.serviceStartedAt {
            details.append("Started \(startedAt.formatted(date: .abbreviated, time: .shortened))")
        }

        return details.joined(separator: " - ")
    }
}
