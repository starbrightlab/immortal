/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// Bulk operations workspace: target selection, explicit confirmation, and a
/// truthful per-target results ledger.
struct BulkView: View {
    @EnvironmentObject var store: PortalManagerStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                header

                if case .completed(let report) = store.bulkPhase {
                    reportCard(report)
                }

                switch store.bulkPhase {
                case .awaitingConfirmation(let summary):
                    confirmationCard(summary)
                case .running(let summary, let finished, let total):
                    runningCard(summary: summary, finished: finished, total: total)
                case .blocked(let reason):
                    blockedCard(reason)
                default:
                    idleCard
                }

                Spacer(minLength: 24)
            }
            .padding(.horizontal, 34)
            .padding(.top, 22)
        }
        .navigationTitle("")
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Fleet Actions")
                .font(.pmDisplay(26))
            Text("One explicitly confirmed operation, dispatched independently to every eligible target.")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
        }
    }

    // MARK: States

    private var idleCard: some View {
        GlassCard(padding: 40) {
            VStack(spacing: 12) {
                Image(systemName: "arrow.triangle.branch")
                    .font(.system(size: 40, weight: .light))
                    .foregroundStyle(PortalTheme.accent)
                Text("Plan a bulk operation")
                    .font(.pmDisplay(17))
                Text("Select targets in your fleet and run identify or reaffirm across all of them at once. Every target gets its own credential check, dispatch, and read-back — nothing is ever copied between devices.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 460)
                PrimaryButton(
                    title: "Plan Identify Across Fleet",
                    systemImage: "bell.badge",
                    disabled: store.entries.isEmpty
                ) {
                    planIdentify()
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private func confirmationCard(_ summary: BulkPreflightSummary) -> some View {
        GlassCard(padding: 28) {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 14) {
                    GradientIcon(systemName: "checkmark.shield", size: 44)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(summary.confirmationHeadline)
                            .font(.system(size: 17, weight: .semibold))
                        Text("Review before dispatch — no request is sent until you confirm.")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }

                ForEach(Array(summary.confirmationDetail.enumerated()), id: \.offset) { _, line in
                    HStack(alignment: .top, spacing: 9) {
                        Image(systemName: "circle.fill")
                            .font(.system(size: 5))
                            .foregroundStyle(PortalTheme.accentSecondary)
                            .padding(.top, 5)
                        Text(line)
                            .font(.system(size: 12.5, weight: .medium))
                    }
                }

                HStack(spacing: 12) {
                    PrimaryButton(title: "Dispatch", systemImage: "paperplane.fill") {
                        store.confirmBulk()
                    }
                    GhostButton(title: "Cancel", systemImage: "xmark") {
                        withAnimation { store.cancelBulkPlanning() }
                    }
                }
            }
        }
    }

    private func runningCard(
        summary: BulkPreflightSummary,
        finished: Int,
        total: Int
    ) -> some View {
        GlassCard(padding: 28) {
            VStack(spacing: 18) {
                ProgressRing(progress: total == 0 ? 0 : Double(finished) / Double(total))
                    .frame(width: 92, height: 92)
                Text("\(finished)/\(total) targets complete")
                    .font(.system(size: 15, weight: .semibold))
                Text(summary.operation.operationLabel)
                    .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.secondary)

                ForEach(store.bulkResults, id: \.portalID) { result in
                    BulkResultRow(result: result)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private func blockedCard(_ reason: String) -> some View {
        GlassCard(padding: 26) {
            HStack(spacing: 14) {
                GradientIcon(
                    systemName: "exclamationmark.shield",
                    size: 40,
                    colors: [PortalTheme.warning, PortalTheme.danger]
                )
                VStack(alignment: .leading, spacing: 3) {
                    Text("Dispatch blocked")
                        .font(.system(size: 15, weight: .semibold))
                    Text(reason)
                        .font(.system(size: 12.5))
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
        }
    }

    private func reportCard(_ report: BulkOperationReport) -> some View {
        GlassCard(padding: 28) {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 20) {
                    reportMetric(report.successCount, "Succeeded", PortalTheme.success)
                    reportMetric(report.partialCount, "Partial", PortalTheme.warning)
                    reportMetric(report.failureCount, "Failed", PortalTheme.danger)
                    reportMetric(report.skippedCount + report.cancelledCount, "Skipped", Color.secondary.opacity(0.6))
                    Spacer()
                }

                Divider().overlay(PortalTheme.line)

                ForEach(report.results, id: \.portalID) { result in
                    BulkResultRow(result: result)
                }

                Text(report.isFullySuccessful
                    ? "Fleet-wide success confirmed by per-target read-back."
                    : "Not all targets succeeded; the aggregate never claims more than individual results prove.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func reportMetric(_ value: Int, _ caption: String, _ tint: Color) -> some View {
        HStack(spacing: 10) {
            Circle().fill(tint).frame(width: 8, height: 8)
            Text("\(value)")
                .font(.system(size: 22, weight: .medium))
            Text(caption)
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(.secondary)
        }
    }

    // MARK: Actions

    /// Plans an identify action across every managed entry as the demo bulk op.
    private func planIdentify() {
        let targets = store.entries.map { entry in
            BulkOperationTarget(portalID: entry.id, admissionRequest: entry.admissionRequestOrPlaceholder())
        }
        store.stageBulk(targets: targets)
        store.planBulk(operation: .approvedAction(.identify), values: [:], targets: targets)
    }
}

private struct BulkResultRow: View {
    let result: BulkTargetResult

    private var tone: PillTone {
        switch result.outcome {
        case .success: return .success
        case .partial: return .warning
        case .failure: return .danger
        default: return .neutral
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            StatusPill(title: result.outcome.rawValue.capitalized, tone: tone)
            Text(result.portalID.rawValue.uuidString.prefix(8))
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
            if !result.appliedKeys.isEmpty {
                Text(result.appliedKeys.joined(separator: ", "))
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            if let error = result.error {
                Text(error.recoveryTitle)
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(tone.color)
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(PortalTheme.well))
        .accessibilityElement(children: .combine)
    }
}

/// A circular animated progress gauge used during long operations.
struct ProgressRing: View {
    let progress: Double

    var body: some View {
        ZStack {
            Circle()
                .stroke(PortalTheme.line, lineWidth: 6)
            Circle()
                .trim(from: 0, to: max(0.02, min(1, progress)))
                .stroke(
                    PortalTheme.ink,
                    style: StrokeStyle(lineWidth: 6, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
            Text("\(Int((min(1, max(0, progress))) * 100))%")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(PortalTheme.ink)
        }
        .animation(.easeOut(duration: 0.35), value: progress)
    }
}

// MARK: - Helpers

extension PortalRegistryEntry {
    /// Builds a typed admission request from this entry's stored endpoint.
    /// The endpoint is re-validated by LAN policy on every connect.
    func admissionRequestOrPlaceholder() -> ConnectionAdmissionRequest {
        if let endpoint = endpoint,
           let request = try? ConnectionAdmissionRequest(
               rawEndpoint: endpoint.hostOrAddress,
               serviceKind: .portal,
               protocolName: "http",
               defaultPort: endpoint.port,
               source: endpoint.source
           ) {
            return request
        }
        // Unreachable for admitted registry entries; loopback keeps the value
        // constructible without ever bypassing later LAN admission.
        return (try? ConnectionAdmissionRequest(
            rawEndpoint: "127.0.0.1",
            serviceKind: .portal,
            protocolName: "http"
        )) ?? ConnectionAdmissionRequest(
            endpoint: LANEndpoint(
                hostOrAddress: "127.0.0.1",
                addressFamily: .ipv4,
                source: .manual
            ),
            serviceKind: .portal,
            protocolName: "http"
        )
    }
}

extension ManagerError {
    var recoveryTitle: String {
        Self.recoveryAction(for: self)?.rawValue.capitalized ?? "Review"
    }

    static func recoveryAction(for error: ManagerError) -> RecoveryAction? {
        switch error {
        case .lanPolicy, .resolution:
            return .editEndpoint
        case .authentication:
            return .reauthenticate
        case .pairing:
            return .pairAgain
        case .capabilityUnavailable:
            return .reviewCapability
        case .keychain:
            return .retry
        default:
            return .none
        }
    }
}
