/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// An operation whose availability can be assessed from an authenticated
/// `/info` snapshot. The classifier never turns these values into requests.
enum PortalOperation: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case info
    case settings
    case sources
    case screensaver
    case calendar
    case identify
    case reaffirm

    var route: String {
        switch self {
        case .info:
            return "/info"
        case .settings:
            return "/remote/settings"
        case .sources:
            return "/remote/sources"
        case .screensaver:
            return "/screensaver"
        case .calendar:
            return "/calendar"
        case .identify, .reaffirm:
            return "/action"
        }
    }
}

/// Endpoint presence is kept separate from the advertised capability flags.
/// `nil` means that the `/info` snapshot did not make an explicit endpoint
/// claim; it is different from an explicit `false` claim.
struct PortalEndpointPresence: Codable, Sendable, Equatable, Hashable {
    var remoteSettings: Bool?
    var remoteSources: Bool?
    var calendar: Bool?
    var screensaver: Bool?
    var action: Bool?

    init(
        remoteSettings: Bool? = nil,
        remoteSources: Bool? = nil,
        calendar: Bool? = nil,
        screensaver: Bool? = nil,
        action: Bool? = nil
    ) {
        self.remoteSettings = remoteSettings
        self.remoteSources = remoteSources
        self.calendar = calendar
        self.screensaver = screensaver
        self.action = action
    }
}

/// The non-secret, decoded shape of the authenticated Fleet Agent `/info`
/// response. Endpoint presence may be supplied by an already-approved
/// capability snapshot; no network probe is performed by this type.
struct AuthenticatedPortalInfo: Codable, Sendable, Equatable, Hashable {
    var name: String
    var model: String
    var device: String?
    var apiLevel: Int?
    var app: AppVersion?
    var ip: String?
    var port: UInt16?
    var presence: [String: JSONValue]?
    var install: [String: JSONValue]?
    var canWriteSecureSettings: Bool?
    var devMode: Bool?
    var capabilities: [String: JSONValue]
    var apps: [JSONValue]?
    var roomLink: [String: JSONValue]?
    var endpointPresence: PortalEndpointPresence

    init(
        name: String,
        model: String,
        device: String? = nil,
        apiLevel: Int? = nil,
        app: AppVersion? = nil,
        ip: String? = nil,
        port: UInt16? = nil,
        presence: [String: JSONValue]? = nil,
        install: [String: JSONValue]? = nil,
        canWriteSecureSettings: Bool? = nil,
        devMode: Bool? = nil,
        capabilities: [String: JSONValue] = [:],
        apps: [JSONValue]? = nil,
        roomLink: [String: JSONValue]? = nil,
        endpointPresence: PortalEndpointPresence = PortalEndpointPresence()
    ) {
        self.name = name
        self.model = model
        self.device = device
        self.apiLevel = apiLevel
        self.app = app
        self.ip = ip
        self.port = port
        self.presence = presence
        self.install = install
        self.canWriteSecureSettings = canWriteSecureSettings
        self.devMode = devMode
        self.capabilities = capabilities
        self.apps = apps
        self.roomLink = roomLink
        self.endpointPresence = endpointPresence
    }

    private enum CodingKeys: String, CodingKey {
        case name
        case model
        case device
        case apiLevel
        case app
        case ip
        case port
        case presence
        case install
        case canWriteSecureSettings
        case devMode
        case capabilities
        case apps
        case roomLink
        case endpointPresence
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        model = try container.decodeIfPresent(String.self, forKey: .model) ?? ""
        device = try container.decodeIfPresent(String.self, forKey: .device)
        apiLevel = try container.decodeIfPresent(Int.self, forKey: .apiLevel)
        app = try container.decodeIfPresent(AppVersion.self, forKey: .app)
        ip = try container.decodeIfPresent(String.self, forKey: .ip)
        port = try container.decodeIfPresent(UInt16.self, forKey: .port)
        presence = try container.decodeIfPresent([String: JSONValue].self, forKey: .presence)
        install = try container.decodeIfPresent([String: JSONValue].self, forKey: .install)
        canWriteSecureSettings = try container.decodeIfPresent(Bool.self, forKey: .canWriteSecureSettings)
        devMode = try container.decodeIfPresent(Bool.self, forKey: .devMode)
        capabilities = try container.decodeIfPresent([String: JSONValue].self, forKey: .capabilities) ?? [:]
        apps = try container.decodeIfPresent([JSONValue].self, forKey: .apps)
        roomLink = try container.decodeIfPresent([String: JSONValue].self, forKey: .roomLink)
        endpointPresence = try container.decodeIfPresent(PortalEndpointPresence.self, forKey: .endpointPresence)
            ?? PortalEndpointPresence()
    }
}

/// The result of classifying one successful, bearer-authenticated `/info`
/// response. All fields are non-secret and safe for the local registry.
struct PortalInfoClassification: Codable, Sendable, Equatable, Hashable {
    var info: AuthenticatedPortalInfo
    var identity: PortalIdentity
    var endpoint: LANEndpoint?
    var status: PortalStatus
    var capabilities: PortalCapabilities
    var policyMetadata: PortalPolicyMetadata

    var modelFamily: PortalModelFamily {
        capabilities.modelFamily
    }

    var compatibility: CompatibilityAssessment {
        policyMetadata.compatibility
    }

    /// Computes the warning for a selected operation without producing a
    /// warning for unselected capabilities.
    func assessment(for operation: PortalOperation) -> CompatibilityAssessment {
        PortalInfoClassifier.assessment(
            apiLevel: info.apiLevel,
            capabilities: capabilities,
            operation: operation
        )
    }
}

extension PortalCapabilities {
    /// Returns availability for an approved operation only. This is a pure
    /// projection; it does not imply that a network request is allowed.
    func supports(_ operation: PortalOperation) -> Bool {
        switch operation {
        case .info:
            return fleetInfo
        case .settings:
            return settingsRegistry
        case .sources:
            return sources
        case .screensaver:
            return screensaver
        case .calendar:
            return calendar
        case .identify:
            return identify
        case .reaffirm:
            return reaffirm
        }
    }
}

/// Pure model-family and capability classification for authenticated `/info`
/// data. It has no transport, probing, Keychain, or process dependencies.
enum PortalInfoClassifier {
    static let supportedAndroidAPILevels: Set<Int> = [28, 29]

    static func classify(
        _ info: AuthenticatedPortalInfo,
        portalID: PortalID,
        endpointSource: EndpointSource = .authenticatedRefresh,
        selectedOperation: PortalOperation? = nil
    ) -> PortalInfoClassification {
        let family = modelFamily(for: info.model, device: info.device)
        let capabilities = makeCapabilities(from: info, modelFamily: family)
        let identity = PortalIdentity(
            portalID: portalID,
            name: info.name,
            model: info.model,
            device: info.device,
            rawModel: info.model,
            androidAPILevel: info.apiLevel,
            immortalVersion: info.app
        )
        let endpoint = reportedEndpoint(from: info, source: endpointSource)
        let status = PortalStatus(
            reachability: .reachable,
            presence: stringValue(info.presence?["presence"]),
            screenState: stringValue(info.presence?["screen"] ?? info.presence?["screenState"])
        )
        let operationWarnings: [String: String]
        if let selectedOperation,
           let reason = missingCapabilityReason(for: selectedOperation, capabilities: capabilities) {
            operationWarnings = [selectedOperation.rawValue: reason]
        } else {
            operationWarnings = [:]
        }

        let policyMetadata = PortalPolicyMetadata(
            compatibility: assessment(
                apiLevel: info.apiLevel,
                capabilities: capabilities,
                operation: nil
            ),
            operationWarnings: operationWarnings
        )
        return PortalInfoClassification(
            info: info,
            identity: identity,
            endpoint: endpoint,
            status: status,
            capabilities: capabilities,
            policyMetadata: policyMetadata
        )
    }

    static func classify(
        info: AuthenticatedPortalInfo,
        portalID: PortalID,
        endpointSource: EndpointSource = .authenticatedRefresh,
        selectedOperation: PortalOperation? = nil
    ) -> PortalInfoClassification {
        classify(
            info,
            portalID: portalID,
            endpointSource: endpointSource,
            selectedOperation: selectedOperation
        )
    }

    static func modelFamily(for rawModel: String, device: String? = nil) -> PortalModelFamily {
        let normalizedModel = normalizeModelLabel(rawModel)
        let modelFamily = recognizedFamily(in: normalizedModel)
        if modelFamily != .unknown {
            return modelFamily
        }

        guard let device else {
            return .unknown
        }
        return recognizedFamily(in: normalizeModelLabel(device))
    }

    static func assessment(
        apiLevel: Int?,
        capabilities: PortalCapabilities,
        operation: PortalOperation?
    ) -> CompatibilityAssessment {
        if let operation,
           let reason = missingCapabilityReason(for: operation, capabilities: capabilities) {
            return .operationUnavailable(operation: operation.rawValue, reason: reason)
        }
        if let apiLevel, !supportedAndroidAPILevels.contains(apiLevel) {
            return .warning(reason: "Android API level \(apiLevel) is not supported by the version-one Portal Manager.")
        }
        return .compatible
    }

    private static func makeCapabilities(
        from info: AuthenticatedPortalInfo,
        modelFamily: PortalModelFamily
    ) -> PortalCapabilities {
        let advertised = info.capabilities
        let endpoints = info.endpointPresence
        let actionAdvertised = advertisedFlag(in: advertised, matching: ["action"])

        return PortalCapabilities(
            modelFamily: modelFamily,
            androidAPILevel: info.apiLevel,
            // A successful authenticated response is proof that /info exists;
            // it must not be downgraded by an unrelated advertised flag.
            fleetInfo: true,
            settingsRegistry: effectiveCapability(
                endpoint: endpoints.remoteSettings,
                advertised: advertisedFlag(
                    in: advertised,
                    matching: ["remoteSettings", "settingsRegistry", "settings"]
                )
            ),
            sources: effectiveCapability(
                endpoint: endpoints.remoteSources,
                advertised: advertisedFlag(
                    in: advertised,
                    matching: ["remoteSources", "sources"]
                )
            ),
            screensaver: effectiveCapability(
                endpoint: endpoints.screensaver,
                advertised: advertisedFlag(in: advertised, matching: ["screensaver"])
            ),
            calendar: effectiveCapability(
                endpoint: endpoints.calendar,
                advertised: advertisedFlag(in: advertised, matching: ["calendar"])
            ),
            identify: effectiveCapability(
                endpoint: endpoints.action,
                advertised: advertisedFlag(
                    in: advertised,
                    matching: ["identify", "actionIdentify", "action"]
                ) ?? actionAdvertised
            ),
            reaffirm: effectiveCapability(
                endpoint: endpoints.action,
                advertised: advertisedFlag(
                    in: advertised,
                    matching: ["reaffirm", "actionReaffirm", "action"]
                ) ?? actionAdvertised
            ),
            rawAdvertisedCapabilities: advertised
        )
    }

    private static func effectiveCapability(endpoint: Bool?, advertised: Bool?) -> Bool {
        // An explicit endpoint absence always wins. Otherwise, an explicit
        // advertised flag wins; an explicit endpoint presence is sufficient
        // when no separate flag was supplied.
        if endpoint == false {
            return false
        }
        if let advertised {
            return advertised
        }
        return endpoint ?? false
    }

    private static func missingCapabilityReason(
        for operation: PortalOperation,
        capabilities: PortalCapabilities
    ) -> String? {
        guard !capabilities.supports(operation) else {
            return nil
        }

        switch operation {
        case .info:
            return "The /info capability is unavailable."
        case .settings:
            return "The /remote/settings capability is unavailable."
        case .sources:
            return "The /remote/sources capability is unavailable."
        case .screensaver:
            return "The /screensaver capability is unavailable."
        case .calendar:
            return "The /calendar capability is unavailable."
        case .identify, .reaffirm:
            return "The /action capability for \(operation.rawValue) is unavailable."
        }
    }

    private static func recognizedFamily(in normalized: String) -> PortalModelFamily {
        guard normalized.contains("portal") else {
            return .unknown
        }

        // More specific labels must be checked before generation or generic
        // Portal matching. Portal TV is also a first-generation device, but
        // it has its own family and must remain recognizable as Portal TV.
        if normalized.contains("portal tv") {
            return .portalTV
        }
        if normalized.contains("portal mini") {
            return .portalMini
        }
        if normalized.contains("portal go") {
            return .portalGo
        }
        if normalized.contains("portal plus") {
            if containsFirstGenerationMarker(normalized) {
                return .portalPlusFirstGeneration
            }
            return .portalPlus
        }
        if containsSecondGenerationMarker(normalized) {
            return .portalGen2
        }
        if normalized.contains("2018 portal") || normalized.contains("portal 2018") {
            return .portal2018
        }

        let tokens = normalized.split(separator: " ").map(String.init)
        let modelWithoutBrand = tokens.filter { $0 != "meta" }
        if modelWithoutBrand == ["portal"] || containsFirstGenerationMarker(normalized) {
            return .portal2018
        }
        return .unknown
    }

    private static func containsFirstGenerationMarker(_ normalized: String) -> Bool {
        [
            "first generation",
            "first gen",
            "gen 1",
            "generation 1",
            "1st gen",
            "original"
        ].contains { normalized.contains($0) }
    }

    private static func containsSecondGenerationMarker(_ normalized: String) -> Bool {
        [
            "second generation",
            "second gen",
            "gen 2",
            "generation 2",
            "2nd gen"
        ].contains { normalized.contains($0) }
    }

    private static func normalizeModelLabel(_ value: String) -> String {
        let folded = value.folding(
            options: [.caseInsensitive, .diacriticInsensitive],
            locale: Locale(identifier: "en_US_POSIX")
        )
        return folded
            .replacingOccurrences(of: "+", with: " plus ")
            .replacingOccurrences(of: "–", with: "-")
            .replacingOccurrences(of: "—", with: "-")
            .split { character in
                !character.isLetter && !character.isNumber
            }
            .map(String.init)
            .joined(separator: " ")
    }

    private static func normalizeCapabilityKey(_ value: String) -> String {
        value
            .lowercased()
            .filter { $0.isLetter || $0.isNumber }
    }

    private static func advertisedFlag(
        in capabilities: [String: JSONValue],
        matching names: [String]
    ) -> Bool? {
        for name in names {
            let normalizedName = normalizeCapabilityKey(name)
            guard let key = capabilities.keys.first(where: {
                normalizeCapabilityKey($0) == normalizedName
            }), let value = capabilities[key] else {
                continue
            }
            if case let .bool(flag) = value {
                return flag
            }
        }
        return nil
    }

    private static func reportedEndpoint(
        from info: AuthenticatedPortalInfo,
        source: EndpointSource
    ) -> LANEndpoint? {
        guard let ip = info.ip?.trimmingCharacters(in: .whitespacesAndNewlines),
              !ip.isEmpty else {
            return nil
        }
        let port = info.port ?? LANEndpoint.defaultPortalAgentPort
        guard port > 0 else {
            return nil
        }

        let addressFamily: AddressFamily
        if ip.contains(":") {
            addressFamily = .ipv6
        } else if ip.split(separator: ".").count == 4,
                  ip.split(separator: ".").allSatisfy({ Int($0) != nil }) {
            addressFamily = .ipv4
        } else {
            addressFamily = .hostname
        }
        return LANEndpoint(
            hostOrAddress: ip,
            port: port,
            addressFamily: addressFamily,
            source: source
        )
    }

    private static func stringValue(_ value: JSONValue?) -> String? {
        guard case let .string(string) = value else {
            return nil
        }
        return string
    }
}

/// The `/info` response name used by callers that do not need the assurance
/// wording. Kept as an alias so wire adapters can use either domain term.
typealias PortalInfoResponse = AuthenticatedPortalInfo
