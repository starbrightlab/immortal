/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

// MARK: - Palette

/// The Portal Manager visual language: cool neutral surfaces, generous white
/// space, calm hierarchy, and a single confident brand blue. Type is SF
/// throughout; separation comes from surface contrast, not rules or shadows.
enum PortalTheme {
    // Surfaces.
    /// Cool gray canvas behind everything.
    static let canvas = Color(red: 0.941, green: 0.949, blue: 0.961)
    /// Card surface.
    static let surface = Color.white
    /// Soft gray fill for wells, inputs, and pressed states.
    static let well = Color(red: 0.929, green: 0.937, blue: 0.953)

    // Brand.
    /// The single accent. Used sparingly: primary actions, active states,
    /// and live indicators.
    static let blue = Color(red: 0.00, green: 0.392, blue: 0.878)
    /// Soft blue wash behind selected/branded elements.
    static let blueWash = Color(red: 0.906, green: 0.949, blue: 0.992)

    // Ink.
    static let ink = Color(red: 0.11, green: 0.17, blue: 0.20)
    static let inkSecondary = Color(red: 0.42, green: 0.45, blue: 0.47)

    // Rules — used rarely; surfaces separate first.
    static let line = Color(red: 0.894, green: 0.906, blue: 0.922)

    // Compatibility aliases for existing call sites.
    static let accent = blue
    static let accentSecondary = inkSecondary
    static let warm = inkSecondary
    static var textDim: Color { inkSecondary }

    // Semantic state — dots only, never decoration.
    static let success = Color(red: 0.13, green: 0.60, blue: 0.33)
    static let warning = Color(red: 0.83, green: 0.58, blue: 0.05)
    static let danger = Color(red: 0.85, green: 0.22, blue: 0.22)

    /// Retained for source compatibility; resolves to flat brand blue.
    static var brandGradient: LinearGradient {
        LinearGradient(colors: [blue, blue], startPoint: .top, endPoint: .bottom)
    }

    /// Retained for source compatibility; the backdrop is flat canvas.
    static var auroraColors: [Color] { [canvas] }
}

// MARK: - Type

extension Font {
    /// Product display type: SF semibold with the size doing the work.
    /// Call sites pair it with negative tracking for hero moments.
    static func pmDisplay(_ size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        .system(size: size, weight: weight)
    }
}

// MARK: - Surfaces

/// A white card floating on the gray canvas. No border, no shadow — the
/// surface contrast is the separation.
struct GlassCard<Content: View>: View {
    var cornerRadius: CGFloat = 16
    var padding: CGFloat = 26
    var highlight: Bool = false
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(PortalTheme.surface)
            )
    }
}

// MARK: - Icon

/// A glyph on a soft wash tile when emphasized; bare when quiet. The
/// `colors` parameter remains for source compatibility: passing a tone tints
/// both wash and glyph (brand blue by convention).
struct GradientIcon: View {
    let systemName: String
    var size: CGFloat = 34
    var colors: [Color] = []

    private var tint: Color? {
        colors.first
    }

    var body: some View {
        Group {
            if let tint {
                Image(systemName: systemName)
                    .font(.system(size: size * 0.42, weight: .medium))
                    .foregroundStyle(tint)
                    .frame(width: size, height: size)
                    .background(
                        RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                            .fill(tint.opacity(0.12))
                    )
            } else {
                Image(systemName: systemName)
                    .font(.system(size: size * 0.42, weight: .medium))
                    .foregroundStyle(PortalTheme.inkSecondary)
                    .frame(width: size, height: size)
            }
        }
    }
}

// MARK: - Status indicator

enum PillTone {
    case success
    case warning
    case danger
    case neutral
    case accent

    var color: Color {
        switch self {
        case .success: return PortalTheme.success
        case .warning: return PortalTheme.warning
        case .danger: return PortalTheme.danger
        case .neutral: return PortalTheme.inkSecondary
        case .accent: return PortalTheme.blue
        }
    }
}

/// A status marker: a small dot and a calm label.
struct StatusPill: View {
    let title: String
    var tone: PillTone = .neutral
    var pulse = false

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(tone.color)
                .frame(width: 6, height: 6)
                .opacity(pulse ? 1 : 0.40)
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(
                    tone == .accent ? PortalTheme.blue : PortalTheme.inkSecondary
                )
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title) status")
    }
}

// MARK: - Section header

struct SectionHeader: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(PortalTheme.ink)
            if let subtitle {
                Text(subtitle)
                    .font(.system(size: 12.5))
                    .foregroundStyle(PortalTheme.inkSecondary)
            }
        }
    }
}

// MARK: - Metric

/// Metric block: large figure, quiet caption, soft branded glyph tile.
struct MetricTile: View {
    let value: String
    let caption: String
    let symbol: String
    var tint: Color = PortalTheme.blue

    var body: some View {
        HStack(spacing: 14) {
            GradientIcon(systemName: symbol, size: 40, colors: [tint])
            VStack(alignment: .leading, spacing: 3) {
                Text(value)
                    .font(.pmDisplay(24))
                    .foregroundStyle(PortalTheme.ink)
                    .contentTransition(.numericText())
                Text(caption)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(PortalTheme.inkSecondary)
            }
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Backdrop

/// Flat canvas. Nothing moves; the calm is the feature.
struct AmbientCanvas: View {
    var body: some View {
        PortalTheme.canvas.ignoresSafeArea()
    }
}

// MARK: - Buttons

/// Primary action: brand-blue capsule, white label.
struct PrimaryButton: View {
    let title: String
    var systemImage: String? = nil
    var disabled = false
    let action: () -> Void

    @State private var pressing = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 12.5, weight: .semibold))
                }
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background(Capsule().fill(PortalTheme.blue))
            .opacity(disabled ? 0.35 : 1)
            .scaleEffect(pressing && !disabled ? 0.98 : 1)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .onLongPressGesture(minimumDuration: .infinity, pressing: { p in pressing = p }, perform: {})
    }
}

/// Secondary action: soft gray capsule, ink label.
struct GhostButton: View {
    let title: String
    var systemImage: String? = nil
    var disabled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 12, weight: .semibold))
                }
                Text(title)
                    .font(.system(size: 12.5, weight: .medium))
            }
            .foregroundStyle(disabled ? PortalTheme.inkSecondary : PortalTheme.ink)
            .padding(.horizontal, 15)
            .padding(.vertical, 9)
            .background(Capsule().fill(PortalTheme.well))
            .opacity(disabled ? 0.55 : 1)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }
}
