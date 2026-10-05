/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// Release evidence workspace: mandatory gates, their statuses, and exactly
/// which v1 claims are publishable versus withheld.
struct ReleaseEvidenceView: View {
    @EnvironmentObject var store: PortalManagerStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header

                if let report = store.evidenceReport {
                    claimsCard(report)
                    gatesCard(report)
                } else {
                    GlassCard(padding: 40, highlight: true) {
                        VStack(spacing: 10) {
                            ProgressRing(progress: 0.66)
                                .frame(width: 72, height: 72)
                            Text("Evaluating release gates…")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }

                Spacer(minLength: 24)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
        }
        .navigationTitle("")
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Release")
                    .font(.pmDisplay(26))
                Text("Version-one support claims are backed by typed validation evidence — nothing is claimed without a passed gate.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }

            Spacer()

            GhostButton(
                title: "Copy Report",
                systemImage: "doc.text",
                disabled: store.evidenceReport == nil
            ) {
                store.dispatch(.copyReleaseReport)
            }
            .accessibilityLabel("Copy sanitized release report")
            .accessibilityHint("Copies a machine-readable summary of passed and withheld release gates.")
        }
    }

    private func claimsCard(_ report: ReleaseGateReport) -> some View {
        GlassCard(padding: 26) {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    GradientIcon(systemName: "seal", size: 42)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Candidate \(report.candidateVersion)")
                            .font(.system(size: 15.5, weight: .semibold))
                        Text(report.publishableClaims.isEmpty
                            ? "No claims are publishable yet."
                            : "\(report.publishableClaims.count) claim\(report.publishableClaims.count == 1 ? "" : "s") publishable")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }

                if !report.publishableClaims.isEmpty {
                    ForEach(report.publishableClaims, id: \.self) { claim in
                        HStack(spacing: 10) {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(PortalTheme.success)
                            Text(claim)
                                .font(.system(size: 13, weight: .semibold))
                        }
                    }
                }

                if !report.withheldClaims.isEmpty {
                    Divider().overlay(PortalTheme.line)
                    ForEach(report.withheldClaims, id: \.self) { withheld in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "minus.circle.fill")
                                .foregroundStyle(PortalTheme.warning)
                                .padding(.top, 1)
                            Text(withheld)
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private func gatesCard(_ report: ReleaseGateReport) -> some View {
        GlassCard(padding: 26) {
            VStack(alignment: .leading, spacing: 14) {
                SectionHeader(title: "Mandatory Gates", subtitle: "Security · LAN · Provisioning · Model Matrix")

                ForEach(Array(report.statusByGate.sorted(by: { $0.key < $1.key }).enumerated()), id: \.offset) { _, entry in
                    HStack(spacing: 12) {
                        gateIcon(entry.value)
                        Text(gateTitle(entry.key))
                            .font(.system(size: 13, weight: .semibold))
                        Spacer()
                        StatusPill(title: entry.value.capitalized, tone: tone(for: entry.value), pulse: entry.value == "passed")
                    }
                    .padding(.vertical, 4)
                }
            }
        }
    }

    private func gateIcon(_ status: String) -> some View {
        let tint = tone(for: status).color
        return Image(systemName: status == "passed" ? "checkmark.seal.fill" : "seal")
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(tint.opacity(status == "missing" ? 0.5 : 0.95))
    }

    private func gateTitle(_ key: String) -> String {
        switch key {
        case "security": return "Security Release Validation"
        case "lan": return "LAN Release Validation"
        case "provisioning": return "Provisioning Release Validation"
        case "packaging": return "Signed and Notarized Packaging"
        case "modelMatrix": return "Model Matrix Validation"
        case "portalTV": return "Portal TV Validation"
        default: return key
        }
    }

    private func tone(for status: String) -> PillTone {
        switch status {
        case "passed": return .success
        case "failed", "withheld": return .danger
        case "pending": return .warning
        default: return .neutral
        }
    }
}
