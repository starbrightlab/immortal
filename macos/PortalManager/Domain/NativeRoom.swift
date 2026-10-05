/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The closed Immortal settings contract used by Native Room Link.
enum NativeRoomIntercomMode: String, Codable, Sendable, Equatable {
    case off
    case broadcast
    case receive
}

/// A managed Portal that can participate in Native Room Link.
struct NativeRoomParticipant: Equatable, Sendable {
    let portalID: PortalID
    let name: String
    let endpointHostOrAddress: String
    let addressFamily: AddressFamily?

    init(
        portalID: PortalID,
        name: String,
        endpointHostOrAddress: String,
        addressFamily: AddressFamily? = nil
    ) {
        self.portalID = portalID
        self.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        self.endpointHostOrAddress = endpointHostOrAddress
            .trimmingCharacters(in: .whitespacesAndNewlines)
        self.addressFamily = addressFamily
    }
}

struct NativeRoomTargetResult: Identifiable, Equatable, Sendable {
    let portalID: PortalID
    let name: String
    let error: ManagerError?

    var id: PortalID { portalID }
    var isSuccess: Bool { error == nil }
}

struct NativeRoomApplyReport: Equatable, Sendable {
    let sourceResult: NativeRoomTargetResult
    let receiverResults: [NativeRoomTargetResult]

    var results: [NativeRoomTargetResult] {
        [sourceResult] + receiverResults
    }

    /// True only when every selected participant succeeded.
    var isFullySuccessful: Bool {
        results.allSatisfy(\.isSuccess)
    }
}

enum NativeRoomPlanError: Error, Equatable, Sendable {
    case missingSource
    case duplicateSource
    case receiverIsSource(PortalID)
    case missingReceivers
    case duplicateReceiver(PortalID)
    case invalidSourceEndpoint
    case invalidReceiverEndpoint
    case privateAddressRequired
    case publicAddressRejected
}

extension NativeRoomPlanError: LocalizedError {
    var sanitizedMessage: String {
        switch self {
        case .missingSource:
            return "Choose one source room."
        case .duplicateSource:
            return "Only one source room can be selected."
        case .receiverIsSource:
            return "The source cannot also be a receiver."
        case .missingReceivers:
            return "Choose at least one receiving room."
        case .duplicateReceiver:
            return "Each room can be selected only once."
        case .invalidSourceEndpoint:
            return "The source needs a valid local address."
        case .invalidReceiverEndpoint:
            return "A receiving room has an invalid local address."
        case .privateAddressRequired:
            return "Native Room Link requires private LAN addresses."
        case .publicAddressRejected:
            return "Public addresses are not permitted for Native Room Link."
        }
    }

    var errorDescription: String? { sanitizedMessage }
}

/// The immutable dispatch plan for one live-audio session.
struct NativeRoomPlan: Equatable, Sendable {
    static let audioPort: UInt16 = 8724
    static let domainID = "immortal"
    static let modeKey = "intercomMode"
    static let peerHostKey = "intercomPeerHost"

    let source: NativeRoomParticipant
    let receivers: [NativeRoomParticipant]

    init(
        source: NativeRoomParticipant,
        receivers: [NativeRoomParticipant],
        lanPolicy: any NativeRoomLANPolicy.Type = LANPolicy.self
    ) throws {
        guard !source.name.isEmpty else {
            throw NativeRoomPlanError.missingSource
        }
        guard !source.endpointHostOrAddress.isEmpty else {
            throw NativeRoomPlanError.invalidSourceEndpoint
        }

        var seenReceivers = Set<PortalID>()
        for receiver in receivers {
            if receiver.portalID == source.portalID {
                throw NativeRoomPlanError.receiverIsSource(receiver.portalID)
            }
            if !seenReceivers.insert(receiver.portalID).inserted {
                throw NativeRoomPlanError.duplicateReceiver(receiver.portalID)
            }
            if receiver.name.isEmpty || receiver.endpointHostOrAddress.isEmpty {
                throw NativeRoomPlanError.invalidReceiverEndpoint
            }
        }

        try Self.validatePrivateEndpoint(
            source.endpointHostOrAddress,
            addressFamily: source.addressFamily,
            using: lanPolicy,
            invalidFailure: .invalidSourceEndpoint
        )
        for receiver in receivers {
            try Self.validatePrivateEndpoint(
                receiver.endpointHostOrAddress,
                addressFamily: receiver.addressFamily,
                using: lanPolicy,
                invalidFailure: .invalidReceiverEndpoint
            )
        }

        guard !receivers.isEmpty else {
            throw NativeRoomPlanError.missingReceivers
        }

        self.source = source
        self.receivers = receivers
    }

    /// The host string receivers use to reach this source on port 8724.
    var sourcePeerHost: String { source.endpointHostOrAddress }

    func sourceSettings() -> SettingsDomainDraft {
        Self.settings(mode: .broadcast)
    }

    func receiverSettings() -> SettingsDomainDraft {
        Self.settings(mode: .receive, peerHost: sourcePeerHost)
    }

    static func stopSettings() -> SettingsDomainDraft {
        settings(mode: .off)
    }

    private static func settings(
        mode: NativeRoomIntercomMode,
        peerHost: String? = nil
    ) -> SettingsDomainDraft {
        var values: [String: JSONValue] = [modeKey: .string(mode.rawValue)]
        if let peerHost {
            values[peerHostKey] = .string(peerHost)
        }
        return SettingsDomainDraft(domainID: domainID, values: values)
    }

    private static func validatePrivateEndpoint(
        _ hostOrAddress: String,
        addressFamily: AddressFamily?,
        using policy: any NativeRoomLANPolicy.Type,
        invalidFailure: NativeRoomPlanError
    ) throws {
        do {
            let endpoint = try policy.validate(
                hostOrAddress: hostOrAddress,
                interfaceZone: nil,
                port: audioPort,
                source: .manual,
                lastAuthenticatedAt: nil
            )
            if endpoint.addressFamily == .hostname {
                // `LANPolicy` has already classified this as an unresolved
                // hostname. Keep that distinction instead of treating the name
                // as proof of a local destination.
                throw ManagerError.lanPolicy(.unresolved)
            }
        } catch {
            if case .lanPolicy(.publicAddress) = error as? ManagerError {
                throw NativeRoomPlanError.publicAddressRejected
            }
            throw invalidFailure
        }
    }
}

/// Narrow test seam over the existing shared LAN admission rules.
protocol NativeRoomLANPolicy {
    static func validate(
        hostOrAddress: String,
        interfaceZone: String?,
        port: UInt16,
        source: EndpointSource,
        lastAuthenticatedAt: Date?
    ) throws -> LANEndpoint
}

extension LANPolicy: NativeRoomLANPolicy {}
