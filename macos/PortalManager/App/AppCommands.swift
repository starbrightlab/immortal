/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import SwiftUI

/// AppKit menu commands bound to the store's typed intents. Unavailable
/// commands stay discoverable but disabled — they never silently target
/// another Portal.
@MainActor
struct PortalCommands: Commands {
    @FocusedObject var store: PortalManagerStore?

    init() {}

    var body: some Commands {
        CommandGroup(replacing: .newItem) {}

        CommandMenu("Fleet") {
            Button("Refresh Discovery") {
                store?.dispatch(.refreshDiscovery)
            }
            .keyboardShortcut("r", modifiers: [.command, .option])
            .disabled(!(store?.commandState.canRefreshDiscovery ?? false))

            Button("Add Manual Endpoint…") {
                store?.dispatch(.openManualEndpoint)
            }
            .keyboardShortcut("n", modifiers: [.command, .option])
            .disabled(!(store?.commandState.canOpenManualEndpoint ?? false))

            Divider()

            Button("Refresh Status") {
                store?.dispatch(.refreshStatus)
            }
            .keyboardShortcut("r", modifiers: [.command, .shift])
            .disabled(!(store?.commandState.canRefreshStatus ?? false))

            Button("Identify") {
                store?.dispatch(.identify)
            }
            .keyboardShortcut("i", modifiers: [.command, .option])
            .disabled(!(store?.commandState.canIdentify ?? false))

            Button("Reaffirm") {
                store?.dispatch(.reaffirm)
            }
            .keyboardShortcut("a", modifiers: [.command, .option])
            .disabled(!(store?.commandState.canReaffirm ?? false))
        }

        CommandMenu("Navigate") {
            Button("Dashboard") {
                store?.navigate(to: .dashboard)
            }
            .keyboardShortcut("1", modifiers: [.command])

            Button("Portals") {
                store?.navigate(to: .portals)
            }
            .keyboardShortcut("2", modifiers: [.command])

            Button("Music") {
                store?.navigate(to: .music)
            }
            .keyboardShortcut("3", modifiers: [.command])

            Button("Fleet Actions") {
                store?.navigate(to: .bulk)
            }
            .keyboardShortcut("4", modifiers: [.command])

            Button("Provisioning") {
                store?.navigate(to: .provisioning)
            }
            .keyboardShortcut("5", modifiers: [.command])

            Button("Release") {
                store?.navigate(to: .release)
            }
            .keyboardShortcut("6", modifiers: [.command])
        }
    }
}
