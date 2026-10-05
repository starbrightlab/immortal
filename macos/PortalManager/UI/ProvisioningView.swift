/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI
import UniformTypeIdentifiers

/// USB provisioning workspace: prerequisites, both modes, and step progress.
struct ProvisioningView: View {
    @EnvironmentObject var store: PortalManagerStore
    @State private var isSelectingADB = false
    @State private var isSelectingArtifact = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header
                modeCards
                noDownloadCard
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
        }
        .navigationTitle("")
        .fileImporter(
            isPresented: $isSelectingADB,
            allowedContentTypes: [.data]
        ) { result in
            if case .success(let url) = result {
                store.dispatch(.selectProvisioningADB(url))
            }
        }
        .fileImporter(
            isPresented: $isSelectingArtifact,
            allowedContentTypes: [.data]
        ) { result in
            if case .success(let url) = result {
                store.dispatch(.selectProvisioningArtifact(url))
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Provisioning")
                .font(.pmDisplay(26))
            Text("Two separate local flows — enable an existing Immortal install, or provision a new device from a verified artifact.")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
        }
    }

    private var modeCards: some View {
        VStack(alignment: .leading, spacing: 18) {
            selectionCard
            operationCard
        }
    }

    private var selectionCard: some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader(
                    title: "Local Tools",
                    subtitle: "Choose the operator-provided ADB executable and, for a full install, the local Immortal APK."
                )

                HStack(spacing: 10) {
                    GhostButton(
                        title: store.adbExecutableSelection == nil ? "Choose ADB…" : "Change ADB…",
                        systemImage: "terminal",
                        disabled: store.provisioningState.isRunning
                    ) {
                        isSelectingADB = true
                    }
                        .accessibilityIdentifier("provisioning.select.adb")
                        .accessibilityLabel(store.adbExecutableSelection == nil ? "Choose ADB executable" : "Change ADB executable")
                        .accessibilityHint("Select the local adb program provided by your platform tools.")
                    if let adbName = store.adbExecutableSelection?.displayName {
                        Text(adbName)
                            .font(.system(size: 12, weight: .medium))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }

                HStack(spacing: 10) {
                    GhostButton(
                        title: store.provisioningArtifact == nil ? "Choose APK…" : "Change APK…",
                        systemImage: "shippingbox",
                        disabled: store.provisioningState.isRunning
                    ) {
                        isSelectingArtifact = true
                    }
                        .accessibilityIdentifier("provisioning.select.artifact")
                        .accessibilityLabel(store.provisioningArtifact == nil ? "Choose local APK" : "Change local APK")
                        .accessibilityHint("Select a signed Immortal Release APK stored on this Mac.")

                    if store.provisioningArtifact != nil {
                        GhostButton(title: "Clear", systemImage: "xmark", disabled: store.provisioningState.isRunning) {
                            store.clearProvisioningArtifact()
                        }
                            .accessibilityIdentifier("provisioning.clear.artifact")
                            .accessibilityLabel("Clear selected APK")
                    }
                }

                if let artifactName = store.provisioningArtifact?.displayName {
                    Text(artifactName)
                        .font(.system(size: 12, weight: .medium))
                        .lineLimit(1)
                        .truncationMode(.middle)
                } else {
                    Text("Full provisioning requires the signed v1.73 Release APK.")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var operationCard: some View {
        GlassCard(padding: 24) {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Operation", selection: $provisioningMode) {
                    Text("Enablement / Recovery").tag(ProvisioningMode.fleetAgentEnablementRecovery)
                    Text("Full USB Provisioning").tag(ProvisioningMode.fullUSBProvisioning)
                }
                .pickerStyle(.segmented)
                .disabled(store.provisioningState.isRunning)
                .accessibilityLabel("Provisioning operation")
                .accessibilityHint("Chooses whether to recover the Fleet Agent or install a verified local APK first.")

                TextField("Device serial", text: $store.provisioningDeviceSerialInput)
                    .textFieldStyle(.roundedBorder)
                    .disabled(store.provisioningState.isRunning)
                    .accessibilityLabel("Device serial")
                    .accessibilityHint("Enter the serial shown by adb devices for the connected Portal.")

                TextField("Portal address (for example 192.168.1.50)", text: $store.provisioningPortalEndpointInput)
                    .textFieldStyle(.roundedBorder)
                    .disabled(store.provisioningState.isRunning)
                    .accessibilityLabel("Portal LAN address")
                    .accessibilityHint("Enter the Portal's private local network address used for credential verification.")

                TextField("Friendly name (optional)", text: $store.provisioningFriendlyNameInput)
                    .textFieldStyle(.roundedBorder)
                    .disabled(store.provisioningState.isRunning)
                    .accessibilityLabel("Friendly name")
                    .accessibilityHint("Optionally names this Portal in the registry.")

                Text(targetSummary)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Provisioning target")

                stepsPreview(steps: provisioningMode.expectedSteps.map(stepTitle))

                HStack(spacing: 10) {
                    PrimaryButton(
                        title: store.provisioningState.isRunning ? "Running…" : "Start",
                        systemImage: "bolt.horizontal.fill",
                        disabled: !store.canStartProvisioning(provisioningMode)
                    ) {
                        store.dispatch(.startProvisioning(provisioningMode))
                    }
                    GhostButton(
                        title: "Cancel",
                        systemImage: "stop.fill",
                        disabled: !store.provisioningState.isRunning
                    ) {
                        store.dispatch(.cancelProvisioning)
                    }
                }

                if store.provisioningState.isRunning {
                    ProgressView(value: store.provisioningState.progress.fractionCompleted)
                        .accessibilityLabel("Provisioning progress")
                        .accessibilityValue("\(Int((store.provisioningState.progress.fractionCompleted * 100).rounded())) percent")
                }

                if let status = store.provisioningState.statusMessage {
                    Text(status)
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(status.contains("passed") || status.contains("saved") ? PortalTheme.success : PortalTheme.textDim)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    @State private var provisioningMode = ProvisioningMode.fleetAgentEnablementRecovery

    private var targetSummary: String {
        let target = try? PortalManagerStore.provisioningTargetID(
            for: provisioningMode,
            serial: store.provisioningDeviceSerialInput,
            entries: store.entries
        )

        switch (provisioningMode, target) {
        case (.fleetAgentEnablementRecovery, .some):
            return "Target: the registered Portal matching this serial."
        case (.fleetAgentEnablementRecovery, .none):
            return "Recovery requires one registered Portal with this serial."
        case (.fullUSBProvisioning, .some):
            return "Target: update the registered Portal matching this serial."
        case (.fullUSBProvisioning, .none):
            return "Target: register a new Portal for this serial."
        }
    }

    private func stepTitle(_ step: ProvisioningStepID) -> String {
        switch step {
        case .preflight: return "Verify authorized USB device"
        case .artifactVerification: return "Verify local APK identity, signer, digest, API, ABI, and model"
        case .deviceSetup: return "Apply the established device setup"
        case .installation: return "Install the verified APK"
        case .writeProvisionFile: return "Write the Fleet Agent handoff"
        case .relaunchImmortal: return "Relaunch Immortal"
        case .readAgentManifest: return "Read the generated agent manifest"
        case .bearerVerification: return "Admit and verify the Portal over LAN"
        case .complete: return "Save the verified Portal"
        }
    }

    private func stepsPreview(steps: [String]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                HStack(spacing: 10) {
                    Text("\(index + 1)")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(PortalTheme.blue)
                        .frame(width: 20, height: 20)
                        .background(Circle().fill(PortalTheme.blueWash))
                        .foregroundStyle(.white)
                    Text(step)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var noDownloadCard: some View {
        GlassCard(padding: 22) {
            HStack(spacing: 14) {
                GradientIcon(
                    systemName: "tray.and.arrow.down.fill",
                    size: 38,
                    colors: [.gray.opacity(0.75), .gray.opacity(0.5)]
                )
                VStack(alignment: .leading, spacing: 4) {
                    Text("Zero downloads, ever")
                        .font(.system(size: 13.5, weight: .semibold))
                    Text("Platform tools, APKs, packages, and release artifacts are never fetched from the network. You provide ADB locally; artifacts come from your own disk.")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
            }
        }
    }
}
