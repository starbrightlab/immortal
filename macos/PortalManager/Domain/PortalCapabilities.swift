/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A small lossless-enough JSON value used for non-secret advertised metadata.
/// It is intentionally a value type so capability snapshots remain Sendable.
indirect enum JSONValue: Codable, Sendable, Equatable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int64.self) {
            self = .number(Double(value))
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported JSON value"
            )
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()

        switch self {
        case .null:
            try container.encodeNil()
        case let .bool(value):
            try container.encode(value)
        case let .number(value):
            try container.encode(value)
        case let .string(value):
            try container.encode(value)
        case let .array(value):
            try container.encode(value)
        case let .object(value):
            try container.encode(value)
        }
    }
}

enum PortalModelFamily: String, Codable, Sendable, Equatable, Hashable {
    case portal2018
    case portalPlus
    case portalPlusFirstGeneration
    case portalGo
    case portalMini
    case portalGen2
    case portalTV
    case unknown
}

/// Capabilities observed from authenticated `/info` data and approved probes.
struct PortalCapabilities: Codable, Sendable, Equatable, Hashable {
    var modelFamily: PortalModelFamily
    var androidAPILevel: Int?
    var fleetInfo: Bool
    var settingsRegistry: Bool
    var sources: Bool
    var screensaver: Bool
    var calendar: Bool
    var identify: Bool
    var reaffirm: Bool
    var rawAdvertisedCapabilities: [String: JSONValue]

    init(
        modelFamily: PortalModelFamily = .unknown,
        androidAPILevel: Int? = nil,
        fleetInfo: Bool = false,
        settingsRegistry: Bool = false,
        sources: Bool = false,
        screensaver: Bool = false,
        calendar: Bool = false,
        identify: Bool = false,
        reaffirm: Bool = false,
        rawAdvertisedCapabilities: [String: JSONValue] = [:]
    ) {
        self.modelFamily = modelFamily
        self.androidAPILevel = androidAPILevel
        self.fleetInfo = fleetInfo
        self.settingsRegistry = settingsRegistry
        self.sources = sources
        self.screensaver = screensaver
        self.calendar = calendar
        self.identify = identify
        self.reaffirm = reaffirm
        self.rawAdvertisedCapabilities = rawAdvertisedCapabilities
    }
}

enum CompatibilityAssessment: Codable, Sendable, Equatable, Hashable {
    case compatible
    case warning(reason: String)
    case operationUnavailable(operation: String, reason: String)
}

/// Non-secret policy/compatibility projection kept with a registry entry.
struct PortalPolicyMetadata: Codable, Sendable, Equatable, Hashable {
    var compatibility: CompatibilityAssessment
    var operationWarnings: [String: String]
    var notes: [String]

    init(
        compatibility: CompatibilityAssessment = .compatible,
        operationWarnings: [String: String] = [:],
        notes: [String] = []
    ) {
        self.compatibility = compatibility
        self.operationWarnings = operationWarnings
        self.notes = notes
    }
}
