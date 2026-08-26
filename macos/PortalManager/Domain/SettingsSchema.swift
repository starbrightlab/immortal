/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The control types currently emitted by Immortal's declarative Settings Registry.
///
/// The raw wire value is retained separately by `SettingsControlSchema`. A future type is
/// therefore still displayable as a compatibility row without making it representable as an
/// editable control.
enum DecodedControlType: Codable, Sendable, Equatable, Hashable {
    case bool
    case int
    case enumValue
    case string
    case info
    case unknown(rawValue: String?)

    init(rawValue: String?) {
        switch rawValue {
        case "bool":
            self = .bool
        case "int":
            self = .int
        case "enum":
            self = .enumValue
        case "string":
            self = .string
        case "info":
            self = .info
        default:
            self = .unknown(rawValue: rawValue)
        }
    }

    var rawValue: String? {
        switch self {
        case .bool:
            return "bool"
        case .int:
            return "int"
        case .enumValue:
            return "enum"
        case .string:
            return "string"
        case .info:
            return "info"
        case .unknown(let rawValue):
            return rawValue
        }
    }

    var isUnknown: Bool {
        if case .unknown = self {
            return true
        }
        return false
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let rawValue = try? container.decode(String.self)
        self.init(rawValue: rawValue)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        if let rawValue {
            try container.encode(rawValue)
        } else {
            try container.encodeNil()
        }
    }
}

/// A value paired with an enum display label in the Settings Registry schema.
///
/// Current Android controls use string values, but JSONValue keeps this adapter additive if a
/// future registry advertises a non-string enum value. No option is treated as an editor by this
/// model; policy and the returned control type remain separate decisions.
struct EnumOption: Codable, Sendable, Equatable, Hashable {
    var value: JSONValue
    var label: String
    var additiveMetadata: [String: JSONValue]

    init(
        value: JSONValue,
        label: String,
        additiveMetadata: [String: JSONValue] = [:]
    ) {
        self.value = value
        self.label = label
        self.additiveMetadata = sanitizedSettingsMetadata(additiveMetadata)
    }

    init(
        value: String,
        label: String,
        additiveMetadata: [String: JSONValue] = [:]
    ) {
        self.init(
            value: .string(value),
            label: label,
            additiveMetadata: additiveMetadata
        )
    }

    var stringValue: String? {
        guard case .string(let value) = value else { return nil }
        return value
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: SettingsCodingKey.self)
        let knownKeys: Set<String> = ["value", "label"]
        let value = decodeJSONValue(from: container, named: "value") ?? .null
        let label = decodeString(from: container, named: "label") ?? ""
        self.value = value
        self.label = label
        self.additiveMetadata = decodeAdditiveMetadata(
            from: container,
            excluding: knownKeys
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: SettingsCodingKey.self)
        try container.encode(value, forKey: SettingsCodingKey("value"))
        try container.encode(label, forKey: SettingsCodingKey("label"))
        try encodeAdditiveMetadata(
            additiveMetadata,
            into: &container,
            excluding: ["value", "label"]
        )
    }
}

/// One declarative Settings Registry control projected from `/remote/settings`.
///
/// The `domainID` is contextual and is intentionally not encoded inside a control object. It is
/// filled by `SettingsDomainSchema` after decoding the domain's `controls` array.
struct SettingsControlSchema: Codable, Sendable, Equatable, Hashable {
    var domainID: String
    var key: String
    var rawType: String?
    var type: DecodedControlType
    var title: String?
    var section: String?
    var help: String?
    var value: JSONValue?
    var defaultValue: JSONValue?
    var options: [EnumOption]?
    var min: Int?
    var max: Int?
    var step: Int?
    var wrap: Bool?
    var asText: Bool?
    var readOnly: Bool
    var secret: Bool
    var hasValue: Bool?
    var visible: Bool
    var additiveMetadata: [String: JSONValue]

    init(
        domainID: String = "",
        key: String,
        rawType: String? = nil,
        type: DecodedControlType = .unknown(rawValue: nil),
        title: String? = nil,
        section: String? = nil,
        help: String? = nil,
        value: JSONValue? = nil,
        defaultValue: JSONValue? = nil,
        options: [EnumOption]? = nil,
        min: Int? = nil,
        max: Int? = nil,
        step: Int? = nil,
        wrap: Bool? = nil,
        asText: Bool? = nil,
        readOnly: Bool = false,
        secret: Bool = false,
        hasValue: Bool? = nil,
        visible: Bool = true,
        additiveMetadata: [String: JSONValue] = [:]
    ) {
        self.domainID = domainID
        self.key = key
        // The wire spelling is derivable from the typed projection; normalizing here keeps
        // hand-constructed schemas equal to their JSON read-back form.
        self.rawType = rawType ?? type.rawValue
        self.type = type
        self.title = title
        self.section = section
        self.help = help
        self.value = secret ? nil : value
        self.defaultValue = secret ? nil : defaultValue
        self.options = options
        self.min = min
        self.max = max
        self.step = step
        self.wrap = wrap
        self.asText = asText
        self.readOnly = readOnly || type.isUnknown
        self.secret = secret
        self.hasValue = hasValue
        self.visible = visible
        self.additiveMetadata = sanitizedSettingsMetadata(additiveMetadata)
    }

    /// The name used by callers that distinguish the schema's current value from its default.
    var currentValue: JSONValue? { value }

    var isUnknownType: Bool { type.isUnknown }

    /// True when this row must remain a compatibility/read-only projection rather than an editor.
    var isReadOnlyCompatibilityRow: Bool {
        readOnly && (!isKnownControl || isUnknownType)
    }

    var isKnownControl: Bool {
        CurrentSettingsControlCatalog.contains(domainID: domainID, key: key)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: SettingsCodingKey.self)
        let rawType = decodeString(from: container, named: "type")
        let type = DecodedControlType(rawValue: rawType)
        let secret = decodeBool(from: container, named: "secret") ?? false
        let wireValue = decodeJSONValue(from: container, named: "value")
        let wireDefault = decodeJSONValue(from: container, named: "default")
        let wireHasValue = decodeBool(from: container, named: "hasValue")

        // Android already redacts StringSpec(secret = true) to an empty value. Do not retain a
        // cleartext value if a future or malicious server fails to do so. `hasValue` remains the
        // only configured-state signal, with a best-effort derivation when the server omits it.
        let value = secret ? nil : wireValue
        let defaultValue = secret ? nil : wireDefault
        let hasValue: Bool?
        if secret, let wireHasValue {
            hasValue = wireHasValue
        } else if secret, let wireValue {
            hasValue = settingsValueRepresentsConfiguredState(wireValue)
        } else {
            hasValue = wireHasValue
        }

        self.domainID = ""
        self.key = decodeString(from: container, named: "key") ?? ""
        self.rawType = rawType
        self.type = type
        self.title = decodeString(from: container, named: "title")
        self.section = decodeString(from: container, named: "section")
        self.help = decodeString(from: container, named: "help")
        self.value = value
        self.defaultValue = defaultValue
        self.options = decodeLossyArray(from: container, named: "options")
        self.min = decodeInt(from: container, named: "min")
        self.max = decodeInt(from: container, named: "max")
        self.step = decodeInt(from: container, named: "step")
        self.wrap = decodeBool(from: container, named: "wrap")
        self.asText = decodeBool(from: container, named: "asText")
        self.readOnly = (decodeBool(from: container, named: "readOnly") ?? false) || type.isUnknown
        self.secret = secret
        self.hasValue = hasValue
        self.visible = decodeBool(from: container, named: "visible") ?? true
        self.additiveMetadata = decodeAdditiveMetadata(
            from: container,
            excluding: Self.wireKeys
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: SettingsCodingKey.self)
        try container.encode(key, forKey: SettingsCodingKey("key"))
        if let rawType = rawType ?? type.rawValue {
            try container.encode(rawType, forKey: SettingsCodingKey("type"))
        } else {
            try container.encodeNil(forKey: SettingsCodingKey("type"))
        }
        try container.encodeIfPresent(title, forKey: SettingsCodingKey("title"))
        try container.encodeIfPresent(section, forKey: SettingsCodingKey("section"))
        try container.encodeIfPresent(help, forKey: SettingsCodingKey("help"))
        if !secret {
            try container.encodeIfPresent(value, forKey: SettingsCodingKey("value"))
            try container.encodeIfPresent(defaultValue, forKey: SettingsCodingKey("default"))
        }
        try container.encodeIfPresent(options, forKey: SettingsCodingKey("options"))
        try container.encodeIfPresent(min, forKey: SettingsCodingKey("min"))
        try container.encodeIfPresent(max, forKey: SettingsCodingKey("max"))
        try container.encodeIfPresent(step, forKey: SettingsCodingKey("step"))
        try container.encodeIfPresent(wrap, forKey: SettingsCodingKey("wrap"))
        try container.encodeIfPresent(asText, forKey: SettingsCodingKey("asText"))
        try container.encode(readOnly, forKey: SettingsCodingKey("readOnly"))
        try container.encode(secret, forKey: SettingsCodingKey("secret"))
        try container.encodeIfPresent(hasValue, forKey: SettingsCodingKey("hasValue"))
        try container.encode(visible, forKey: SettingsCodingKey("visible"))
        try encodeAdditiveMetadata(
            additiveMetadata,
            into: &container,
            excluding: Self.wireKeys
        )
    }

    private static let wireKeys: Set<String> = [
        "key", "type", "title", "section", "help", "value", "default", "options",
        "min", "max", "step", "wrap", "asText", "readOnly", "secret", "hasValue", "visible"
    ]
}

/// A single Settings Registry domain. `id` is the exact server identifier and is never replaced by
/// the typed known-domain projection.
struct SettingsDomainSchema: Codable, Sendable, Equatable, Hashable {
    var id: String
    var title: String?
    var section: String?
    var help: String?
    var controls: [SettingsControlSchema]
    var visible: Bool
    var additiveMetadata: [String: JSONValue]

    init(
        id: String,
        title: String? = nil,
        section: String? = nil,
        help: String? = nil,
        controls: [SettingsControlSchema] = [],
        visible: Bool = true,
        additiveMetadata: [String: JSONValue] = [:]
    ) {
        self.id = id
        self.title = title
        self.section = section
        self.help = help
        self.controls = SettingsDomainSchema.scopedControls(
            controls,
            domainID: id
        )
        self.visible = visible
        self.additiveMetadata = sanitizedSettingsMetadata(additiveMetadata)
    }

    var domainID: String { id }

    var knownDomain: KnownSettingsDomain? {
        KnownSettingsDomain(rawValue: id)
    }

    /// Compatibility alias for callers that use "kind" for the typed projection.
    var knownDomainKind: KnownSettingsDomain? { knownDomain }

    var isKnownDomain: Bool { knownDomain != nil }

    var isReadOnlyCompatibilityRow: Bool { !isKnownDomain }

    var visibleControls: [SettingsControlSchema] {
        controls.filter(\.visible)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: SettingsCodingKey.self)
        let id = decodeString(from: container, named: "id") ?? ""
        let controls: [SettingsControlSchema] = decodeLossyArray(
            from: container,
            named: "controls"
        ) ?? []
        self.id = id
        self.title = decodeString(from: container, named: "title")
        self.section = decodeString(from: container, named: "section")
        self.help = decodeString(from: container, named: "help")
        self.visible = decodeBool(from: container, named: "visible") ?? true
        self.controls = SettingsDomainSchema.scopedControls(controls, domainID: id)
        self.additiveMetadata = decodeAdditiveMetadata(
            from: container,
            excluding: Self.wireKeys
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: SettingsCodingKey.self)
        try container.encode(id, forKey: SettingsCodingKey("id"))
        try container.encodeIfPresent(title, forKey: SettingsCodingKey("title"))
        try container.encodeIfPresent(section, forKey: SettingsCodingKey("section"))
        try container.encodeIfPresent(help, forKey: SettingsCodingKey("help"))
        try container.encode(controls, forKey: SettingsCodingKey("controls"))
        try container.encode(visible, forKey: SettingsCodingKey("visible"))
        try encodeAdditiveMetadata(
            additiveMetadata,
            into: &container,
            excluding: Self.wireKeys
        )
    }

    private static let wireKeys: Set<String> = [
        "id", "title", "section", "help", "controls", "visible"
    ]

    private static func scopedControls(
        _ controls: [SettingsControlSchema],
        domainID: String
    ) -> [SettingsControlSchema] {
        let knownDomain = KnownSettingsDomain(rawValue: domainID) != nil
        return controls.map { control in
            var scoped = control
            scoped.domainID = domainID
            // A future domain or control key is a compatibility row, even if the server claims it
            // is writable. Unknown types are already forced read-only by the control decoder.
            if !knownDomain || !CurrentSettingsControlCatalog.contains(
                domainID: domainID,
                key: scoped.key
            ) {
                scoped.readOnly = true
            }
            return scoped
        }
    }
}

/// The current Settings Registry domain identifiers. This is recognition for presentation only;
/// it does not grant edit permission or select a Fleet route.
enum KnownSettingsDomain: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case screensaver
    case calendar
    case immortal
    case mqtt
    case quickbar
    case fleet
    case chime
    case digitalclock
    case welcome
    case sunrise
}

typealias SettingsDomainKind = KnownSettingsDomain

/// The inner `settings` object returned by `GET /remote/settings`.
///
/// The decoder also accepts the complete response envelope (`{"settings":{"domains":...}}`) so
/// transport adapters can decode the actual route response without a second DTO. Encoding emits
/// the inner schema object, not an HTTP success envelope.
struct SettingsRegistrySchema: Codable, Sendable, Equatable, Hashable {
    var domains: [SettingsDomainSchema]
    var additiveMetadata: [String: JSONValue]

    init(
        domains: [SettingsDomainSchema] = [],
        additiveMetadata: [String: JSONValue] = [:]
    ) {
        self.domains = domains
        self.additiveMetadata = sanitizedSettingsMetadata(additiveMetadata)
    }

    var visibleDomains: [SettingsDomainSchema] {
        domains.filter(\.visible)
    }

    var knownDomains: [SettingsDomainSchema] {
        domains.filter(\.isKnownDomain)
    }

    var unknownDomains: [SettingsDomainSchema] {
        domains.filter { !$0.isKnownDomain }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: SettingsCodingKey.self)
        let settingsKey = SettingsCodingKey("settings")

        // `/remote/settings` wraps the registry in an `{ok, settings}` envelope. Accepting the
        // inner object as well keeps this model useful for the returned domain read-back.
        if container.contains(settingsKey),
           let nestedDecoder = try? container.superDecoder(forKey: settingsKey),
           let nested = try? SettingsRegistrySchema(from: nestedDecoder) {
            self = nested
            return
        }

        self.domains = decodeLossyArray(from: container, named: "domains") ?? []
        self.additiveMetadata = decodeAdditiveMetadata(
            from: container,
            excluding: ["domains", "settings", "ok"]
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: SettingsCodingKey.self)
        try container.encode(domains, forKey: SettingsCodingKey("domains"))
        try encodeAdditiveMetadata(
            additiveMetadata,
            into: &container,
            excluding: ["domains", "settings", "ok"]
        )
    }
}

// MARK: - Known-domain/control projection

/// A deliberately narrow catalog used only to identify future control keys. Policy classification
/// remains a separate task and is still required before any write, including for these known keys.
private enum CurrentSettingsControlCatalog {
    private static let keys: [KnownSettingsDomain: Set<String>] = [
        .calendar: ["widgetOn", "url", "range", "size", "side"],
        .screensaver: [
            "enabled", "albumRefreshMin", "fit", "intervalSec", "shuffle", "includeVideo",
            "cropVertical", "blurBackground", "cacheEnabled", "cacheBudgetGb", "batterySaver",
            "showNowPlaying", "antiBurnIn", "showGradient", "presenceMode", "idleSleepMin",
            "overnightEnabled", "overnightStartMin", "overnightEndMin", "overnightNightClock",
            "feed", "sleepTimerEnabled", "sleepTimerMin", "pauseAudioOnSleep", "closeAppOnSleep",
            "soundscape", "soundscapeVolume", "ambientDashboard", "gestureWave", "welcomeEnabled"
        ],
        .immortal: [
            "weatherUnit", "tileSize", "weatherWidget", "clockFormat", "showMiniPlayer",
            "hideStatusBar", "constrainPageWidth", "portalPresence", "multiRoomEnabled",
            "snapcastHost", "maPort", "maUsername", "maPassword",
            "intercomMode", "intercomPeerHost"
        ],
        .mqtt: [
            "enabled", "host", "port", "username", "password", "useTls", "validateCert",
            "ambientSensors", "cameraEnabled", "cameraAudio", "tempOffset"
        ],
        .quickbar: ["enabled", "alwaysShow", "showSystemRecents"],
        .fleet: ["name"],
        .chime: [
            "hourlyChimeOn", "chimeVolume", "spokenTimeOn", "spokenVolume", "goldenHourOn",
            "goldenVolume", "sunriseVariant", "pingVolume", "quietHoursOn", "quietStartMin",
            "quietEndMin"
        ],
        .digitalclock: [
            "enabled", "style", "color", "font", "size", "layout", "background", "glow",
            "showDate", "showSeconds"
        ],
        .welcome: ["durationMs", "showGreeting", "showClock", "showDate", "enableTts"],
        .sunrise: ["enabled", "hour", "minute", "rampMinutes", "chime"]
    ]

    static func contains(domainID: String, key: String) -> Bool {
        guard let domain = KnownSettingsDomain(rawValue: domainID) else { return false }
        return keys[domain]?.contains(key) == true
    }
}

// MARK: - Defensive Codable helpers

private struct SettingsCodingKey: CodingKey, Hashable {
    let stringValue: String
    let intValue: Int?

    init(_ stringValue: String) {
        self.stringValue = stringValue
        self.intValue = nil
    }

    init?(stringValue: String) {
        self.init(stringValue)
    }

    init?(intValue: Int) {
        self.stringValue = String(intValue)
        self.intValue = intValue
    }
}

private struct LossyDecodableArray<Element: Decodable>: Decodable {
    let elements: [Element]

    init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        var values: [Element] = []
        while !container.isAtEnd {
            let elementDecoder = try container.superDecoder()
            if let value = try? Element(from: elementDecoder) {
                values.append(value)
            }
        }
        elements = values
    }
}

private extension KeyedDecodingContainer where Key == SettingsCodingKey {
    func contains(_ name: String) -> Bool {
        contains(SettingsCodingKey(name))
    }
}

private func decodeString(
    from container: KeyedDecodingContainer<SettingsCodingKey>,
    named name: String
) -> String? {
    guard let key = SettingsCodingKey(stringValue: name), container.contains(key) else { return nil }
    return try? container.decode(String.self, forKey: key)
}

private func decodeBool(
    from container: KeyedDecodingContainer<SettingsCodingKey>,
    named name: String
) -> Bool? {
    guard let key = SettingsCodingKey(stringValue: name), container.contains(key) else { return nil }
    return try? container.decode(Bool.self, forKey: key)
}

private func decodeInt(
    from container: KeyedDecodingContainer<SettingsCodingKey>,
    named name: String
) -> Int? {
    guard let key = SettingsCodingKey(stringValue: name), container.contains(key) else { return nil }
    if let value = try? container.decode(Int.self, forKey: key) {
        return value
    }
    guard let value = try? container.decode(Double.self, forKey: key),
          value.isFinite,
          value.rounded() == value,
          value >= Double(Int.min),
          value <= Double(Int.max) else {
        return nil
    }
    return Int(value)
}

private func decodeJSONValue(
    from container: KeyedDecodingContainer<SettingsCodingKey>,
    named name: String
) -> JSONValue? {
    guard let key = SettingsCodingKey(stringValue: name), container.contains(key) else { return nil }
    return try? container.decode(JSONValue.self, forKey: key)
}

private func decodeLossyArray<Element: Decodable>(
    from container: KeyedDecodingContainer<SettingsCodingKey>,
    named name: String
) -> [Element]? {
    guard let key = SettingsCodingKey(stringValue: name), container.contains(key) else { return nil }
    return try? container.decode(LossyDecodableArray<Element>.self, forKey: key).elements
}

private func decodeAdditiveMetadata(
    from container: KeyedDecodingContainer<SettingsCodingKey>,
    excluding knownKeys: Set<String>
) -> [String: JSONValue] {
    var metadata: [String: JSONValue] = [:]
    for key in container.allKeys where !knownKeys.contains(key.stringValue) {
        guard let value = try? container.decode(JSONValue.self, forKey: key),
              let sanitized = sanitizeSettingsMetadata(value, forKey: key.stringValue) else {
            continue
        }
        metadata[key.stringValue] = sanitized
    }
    return metadata
}

private func encodeAdditiveMetadata(
    _ metadata: [String: JSONValue],
    into container: inout KeyedEncodingContainer<SettingsCodingKey>,
    excluding knownKeys: Set<String>
) throws {
    for (name, value) in metadata where !knownKeys.contains(name) {
        guard let key = SettingsCodingKey(stringValue: name),
              let sanitized = sanitizeSettingsMetadata(value, forKey: name) else {
            continue
        }
        try container.encode(sanitized, forKey: key)
    }
}

private func sanitizedSettingsMetadata(
    _ metadata: [String: JSONValue]
) -> [String: JSONValue] {
    metadata.reduce(into: [:]) { result, item in
        if let sanitized = sanitizeSettingsMetadata(item.value, forKey: item.key) {
            result[item.key] = sanitized
        }
    }
}

private func sanitizeSettingsMetadata(
    _ value: JSONValue,
    forKey key: String? = nil
) -> JSONValue? {
    if let key, isSensitiveSettingsMetadataKey(key) {
        return nil
    }

    switch value {
    case .null, .bool, .number, .string:
        return value
    case .array(let values):
        return .array(values.compactMap { sanitizeSettingsMetadata($0) })
    case .object(let object):
        return .object(
            object.reduce(into: [:]) { result, item in
                if let sanitized = sanitizeSettingsMetadata(item.value, forKey: item.key) {
                    result[item.key] = sanitized
                }
            }
        )
    }
}

private func isSensitiveSettingsMetadataKey(_ key: String) -> Bool {
    let normalized = key.lowercased().filter { $0.isLetter || $0.isNumber }
    let sensitiveKeys: Set<String> = [
        "password", "pass", "token", "secret", "credential", "authorization", "bearer",
        "session", "accesstoken", "refreshtoken", "apikey", "immichkey", "smbuser", "smbpass",
        "davuser", "davpass", "mausername", "mapassword"
    ]
    return sensitiveKeys.contains(normalized)
}

private func settingsValueRepresentsConfiguredState(_ value: JSONValue) -> Bool {
    switch value {
    case .null:
        return false
    case .string(let string):
        return !string.isEmpty
    case .array, .object, .bool, .number:
        return true
    }
}
