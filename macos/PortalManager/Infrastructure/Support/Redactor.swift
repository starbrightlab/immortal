/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// Stable placeholders used at every operator-facing redaction boundary.
///
/// These markers intentionally contain no input-derived data. Callers may use the
/// same redactor for UI snapshots, logs, diagnostics, exports, analytics, and
/// release evidence without creating a second, weaker representation.
enum RedactionMarker {
    static let value = "<redacted>"
    static let url = "<redacted-url>"
    static let binary = "<redacted-binary>"
}

/// A deterministic redactor for both free-form text and typed JSON values.
///
/// The free-form pass handles protocol headers, URL/query material, shell/process
/// output, PIN-shaped values, and key/value text. The structured pass walks every
/// nested `JSONValue`, replacing sensitive fields before non-sensitive strings are
/// passed through the free-form pass. Exact values supplied at construction time
/// are intended for operation-local sentinels such as bearer tokens, passwords,
/// PINs, and fixture values.
struct StructuredRedactor: Redactor, Sendable {
    private let sensitiveValues: [String]

    init(sensitiveValues: [String] = []) {
        self.sensitiveValues = sensitiveValues
            .filter { !$0.isEmpty }
            .sorted { lhs, rhs in
                lhs.utf8.count > rhs.utf8.count
            }
    }

    /// Returns a copy with additional exact values that must not cross an output
    /// boundary. The original redactor remains unchanged.
    func addingSensitiveValues(_ values: [String]) -> StructuredRedactor {
        StructuredRedactor(sensitiveValues: sensitiveValues + values)
    }

    func redact(_ input: String) -> RedactedText {
        guard !input.isEmpty else {
            return RedactedText(value: input)
        }

        var output = input

        // Exact values are applied first so opaque tokens and test sentinels are
        // removed even when they do not carry a recognizable field name.
        for sensitiveValue in sensitiveValues {
            output = output.replacingOccurrences(
                of: sensitiveValue,
                with: RedactionMarker.value,
                options: [],
                range: nil
            )
        }

        // URLs are never useful as diagnostic payloads and can contain userinfo,
        // signed query material, bearer values, or credentials in a path.
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.url,
            with: RedactionMarker.url
        )

        // Preserve the header label while removing the complete authorization
        // scheme/value pair. This covers ADB/process transcripts and HTTP logs.
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.authorizationHeader,
            with: "Authorization: \(RedactionMarker.value)"
        )

        // JSON-like text is handled before the generic assignment pass so quoted
        // values remain syntactically recognizable in diagnostics.
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.jsonDoubleQuotedField,
            with: "$1$2\(RedactionMarker.value)$3"
        )
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.jsonSingleQuotedField,
            with: "$1$2\(RedactionMarker.value)$3"
        )

        // This handles key=value, header-like key: value, and unquoted JSON-ish
        // values emitted by process tools or protocol debuggers.
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.sensitiveAssignment,
            with: "$1\(RedactionMarker.value)"
        )
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.sensitiveQueryParameter,
            with: "$1\(RedactionMarker.value)"
        )
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.sensitiveCommandArgument,
            with: "$1\(RedactionMarker.value)"
        )
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.bearerLikeValue,
            with: "$1\(RedactionMarker.value)"
        )

        // A six-digit value is the Portal pairing shape. It is intentionally
        // removed even when a surrounding label was lost in process output.
        output = replacingMatches(
            in: output,
            pattern: RedactionPatterns.pairingPIN,
            with: RedactionMarker.value
        )

        return RedactedText(value: output)
    }

    /// Redacts a typed JSON value recursively. Sensitive fields are replaced as
    /// a whole, while all other strings still receive free-form redaction.
    func redact(_ value: JSONValue) -> JSONValue {
        switch value {
        case .null, .bool, .number:
            return value
        case let .string(string):
            return .string(redact(string).value)
        case let .array(values):
            return .array(values.map { redact($0) })
        case let .object(fields):
            var redactedFields: [String: JSONValue] = [:]
            redactedFields.reserveCapacity(fields.count)

            for (key, nestedValue) in fields {
                if Self.isURLKey(key) {
                    redactedFields[key] = .string(RedactionMarker.url)
                } else if Self.isSensitiveKey(key) {
                    redactedFields[key] = .string(RedactionMarker.value)
                } else {
                    redactedFields[key] = redact(nestedValue)
                }
            }

            return .object(redactedFields)
        }
    }

    /// Convenience overload for structured dictionaries used by adapters and
    /// diagnostic sinks.
    func redact(_ object: [String: JSONValue]) -> [String: JSONValue] {
        guard case let .object(redacted) = redact(.object(object)) else {
            return [:]
        }
        return redacted
    }

    /// Redacts a JSON payload when it is valid JSON and falls back to the safe
    /// free-form pass for text or process output that is not valid JSON.
    func redact(_ data: Data) -> RedactedText {
        guard !data.isEmpty else {
            return RedactedText(value: "")
        }

        if let value = try? JSONDecoder().decode(JSONValue.self, from: data),
           let redactedData = try? JSONEncoder().encode(redact(value)),
           let redactedJSON = String(data: redactedData, encoding: .utf8) {
            return RedactedText(value: redactedJSON)
        }

        guard let text = String(data: data, encoding: .utf8) else {
            return RedactedText(value: RedactionMarker.binary)
        }
        return redact(text)
    }

    /// Header values are redacted by header name before free-form processing.
    /// Header names themselves are non-secret and remain available for diagnosis.
    func redact(headers: [String: String]) -> [String: String] {
        headers.reduce(into: [:]) { result, header in
            if Self.isSensitiveHeader(header.key) {
                result[header.key] = RedactionMarker.value
            } else {
                result[header.key] = redact(header.value).value
            }
        }
    }

    /// URLs are intentionally represented only by a stable marker at output
    /// boundaries. This prevents credentials hidden in userinfo, query, fragment,
    /// path, or opaque URL forms from being missed by a parser.
    func redact(_ url: URL) -> RedactedText {
        RedactedText(value: RedactionMarker.url)
    }

    func redactURL(_ url: URL) -> RedactedText {
        redact(url)
    }

    /// Named adapters make the intended boundary explicit for process/ADB sinks
    /// while using the same implementation and replacement policy.
    func redactADBOutput(_ output: String) -> RedactedText {
        redact(output)
    }

    func redactProcessOutput(_ output: String) -> RedactedText {
        redact(output)
    }

    func redactDiagnostic(_ output: String) -> RedactedText {
        redact(output)
    }

    private func replacingMatches(in input: String, pattern: String, with template: String) -> String {
        guard let expression = try? NSRegularExpression(
            pattern: pattern,
            options: [.caseInsensitive]
        ) else {
            // A static pattern failing to compile must fail closed rather than
            // return an unredacted diagnostic value.
            return RedactionMarker.value
        }

        let range = NSRange(input.startIndex..<input.endIndex, in: input)
        return expression.stringByReplacingMatches(
            in: input,
            options: [],
            range: range,
            withTemplate: template
        )
    }

    private static func normalizedKey(_ key: String) -> String {
        key.unicodeScalars
            .filter { CharacterSet.alphanumerics.contains($0) }
            .map(String.init)
            .joined()
            .lowercased()
    }

    private static func isURLKey(_ key: String) -> Bool {
        let normalized = normalizedKey(key)
        return normalized == "url"
            || normalized == "uri"
            || normalized.contains("redirecturl")
            || normalized.contains("callbackurl")
            || normalized == "location"
    }

    private static func isSensitiveKey(_ key: String) -> Bool {
        let normalized = normalizedKey(key)

        if sensitiveKeyNames.contains(normalized) {
            return true
        }

        // Future fields should remain safe by default when their names clearly
        // identify a credential-bearing category.
        return normalized.contains("password")
            || normalized.contains("passwd")
            || normalized.contains("accesstoken")
            || normalized.contains("refreshtoken")
            || normalized.contains("sessiontoken")
            || normalized.contains("bearertoken")
            || normalized.contains("username")
            || normalized.contains("secret")
            || normalized.contains("credential")
            || normalized.contains("apikey")
            || normalized == "pin"
            || normalized == "pairingpin"
    }

    private static func isSensitiveHeader(_ key: String) -> Bool {
        let normalized = normalizedKey(key)
        return normalized == "authorization"
            || normalized == "proxyauthorization"
            || normalized == "cookie"
            || normalized == "setcookie"
            || normalized == "xapikey"
            || normalized == "xauthtoken"
            || normalized == "xaccesstoken"
            || normalized == "xsessiontoken"
            || normalized == "wwwauthenticate"
            || isSensitiveKey(key)
    }

    private static let sensitiveKeyNames: Set<String> = [
        "authorization",
        "proxyauthorization",
        "bearer",
        "bearertoken",
        "token",
        "accesstoken",
        "refreshtoken",
        "sessiontoken",
        "apikey",
        "credential",
        "credentials",
        "password",
        "passwd",
        "pwd",
        "secret",
        "pin",
        "pairingpin",
        "username",
        "user",
        "login",
        "account",
        "mausername",
        "mapassword",
        "immichkey",
        "smbuser",
        "smbpass",
        "davuser",
        "davpass",
        "sourcecredential"
    ]
}

private enum RedactionPatterns {
    static let sensitiveKey =
        #"(?:authorization|proxy[-_]?authorization|bearer(?:[-_]?token)?|token|access[-_]?token|refresh[-_]?token|session[-_]?token|api[-_]?key|credential|credentials|password|passwd|pwd|secret|pin|pairing[-_]?pin|username|user[-_]?name|user|login|account|ma[-_]?username|ma[-_]?password|immich[-_]?key|smb[-_]?(?:user|pass)|dav[-_]?(?:user|pass))"#

    static let queryKey =
        #"(?:authorization|bearer|token|access[-_]?token|refresh[-_]?token|session[-_]?token|api[-_]?key|password|passwd|pwd|secret|pin|pairing[-_]?pin|username|user|login|account|ma[-_]?username|ma[-_]?password|immich[-_]?key|smb[-_]?(?:user|pass)|dav[-_]?(?:user|pass))"#

    static let url = #"(?:https?|wss?|ftp|file)://[^\s<>"']+"#

    static let authorizationHeader =
        #"\bAuthorization\s*:\s*(?:Bearer|Basic|Token|Digest)\s+[^\s,;]+"#

    static let jsonDoubleQuotedField =
        #"(["']?"# + sensitiveKey + #"["']?\s*:\s*)(")(?:\\.|[^"\\])*(")"#

    static let jsonSingleQuotedField =
        #"(["']?"# + sensitiveKey + #"["']?\s*:\s*)(')(?:\\.|[^'\\])*(')"#

    static let sensitiveAssignment =
        #"(["']?"# + sensitiveKey + #"["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;&}\]]+)"#

    static let sensitiveQueryParameter =
        #"([?&]"# + queryKey + #"=)([^&#\s]+)"#

    static let sensitiveCommandArgument =
        #"(--?(?:password|passwd|pwd|token|access[-_]?token|username)\s+)([^\s]+)"#

    static let bearerLikeValue =
        #"(\b(?:Bearer|Basic|Token)\s+)[^\s,;]+"#

    static let pairingPIN = #"\b\d{6}\b"#
}

/// Compatibility name retained for the composition root created during project
/// bootstrap. It now uses the complete structured/free-form implementation rather
/// than the old whole-string placeholder.
typealias ConservativeRedactor = StructuredRedactor

/// A non-persistable operation-local secure input buffer.
///
/// The buffer stores UTF-8 bytes rather than a published `String`, exposes data
/// only for the synchronous duration of `withData`, and wipes its owned storage on
/// every explicit clear and during deinitialization. The `@unchecked Sendable`
/// conformance is safe because all mutable storage is protected by `NSLock`.
final class TransientSecureInput: SecureInput, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Data

    init(_ value: String) {
        storage = Data(value.utf8)
    }

    var isEmpty: Bool {
        lock.lock()
        defer { lock.unlock() }
        return storage.isEmpty
    }

    func withData<Result: Sendable>(
        _ body: @Sendable (Data) throws -> Result
    ) rethrows -> Result {
        lock.lock()
        var snapshot = storage
        lock.unlock()

        defer {
            if !snapshot.isEmpty {
                snapshot.resetBytes(in: 0..<snapshot.count)
                snapshot.removeAll(keepingCapacity: false)
            }
        }

        return try body(snapshot)
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        guard !storage.isEmpty else { return }
        storage.resetBytes(in: 0..<storage.count)
        storage.removeAll(keepingCapacity: false)
    }

    deinit {
        clear()
    }
}

/// Default operation-local secure-input store. It has no persistent state and
/// therefore cannot accidentally become a registry, UserDefaults, or view-model
/// storage boundary.
struct TransientSecureInputStore: SecureInputStore, Sendable {
    func makeReference(from value: String) -> SecureInputRef {
        TransientSecureInput(value)
    }

    func clear(_ input: SecureInputRef) {
        input.clear()
    }
}

/// Lifecycle helpers guarantee cleanup for success, cancellation, and thrown
/// failure. The async overload is especially useful for Keychain/network
/// coordinators: `defer` still runs when the operation throws `CancellationError`.
extension SecureInputStore {
    func withSecureInput<Result: Sendable>(
        _ value: String,
        operation: @Sendable (SecureInputRef) throws -> Result
    ) rethrows -> Result {
        let input = makeReference(from: value)
        defer { clear(input) }
        return try operation(input)
    }

    func withSecureInput<Result: Sendable>(
        _ value: String,
        operation: @Sendable (SecureInputRef) async throws -> Result
    ) async rethrows -> Result {
        let input = makeReference(from: value)
        defer { clear(input) }
        return try await operation(input)
    }

    /// Alias for coordinators that describe the operation as using an input
    /// rather than a secure-input scope.
    func withInput<Result: Sendable>(
        _ value: String,
        operation: @Sendable (SecureInputRef) throws -> Result
    ) rethrows -> Result {
        try withSecureInput(value, operation: operation)
    }

    func withInput<Result: Sendable>(
        _ value: String,
        operation: @Sendable (SecureInputRef) async throws -> Result
    ) async rethrows -> Result {
        try await withSecureInput(value, operation: operation)
    }
}
