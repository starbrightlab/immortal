/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

// MARK: - Finite ADB command surface

/// The finite set of device facts the provisioning preflight may inspect.
///
/// There is intentionally no command-string or remote-command representation in
/// this type. A coordinator may choose one of these facts, but it cannot add an
/// arbitrary ADB subcommand through the application boundary.
enum DeviceField: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case authorization
    case connection
    case serial
    case model
    case apiLevel
    case installedImmortal
    case immortalVersion
    case fleetAgent
}

/// Established, product-owned setup operations. Each case maps to fixed ADB
/// arguments in `ProcessADBRunner`; no caller-provided package, path, or shell
/// text is accepted.
enum SetupStep: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case enableDeveloperSettings
    case hideStatusBar
    case allowHiddenAPI
    case disableInstallerOverlay
    case disableSettingsOverlay
}

enum ADBCommandKind: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case enumerateDevices
    case inspect
    case pushProvisionFile
    case relaunchImmortal
    case readAgentManifest
    case installVerifiedArtifact
    case applyEstablishedSetup
}

/// The only request accepted by the production ADB runner.
enum ADBCommand: Codable, Sendable, Equatable, Hashable, ADBRequest {
    case enumerateDevices
    case inspect(device: String, field: DeviceField)
    case pushProvisionFile(device: String, localURL: URL)
    case relaunchImmortal(device: String)
    case readAgentManifest(device: String)
    case installVerifiedArtifact(device: String, localURL: URL)
    case applyEstablishedSetup(device: String, step: SetupStep)

    var kind: ADBCommandKind {
        switch self {
        case .enumerateDevices:
            return .enumerateDevices
        case .inspect:
            return .inspect
        case .pushProvisionFile:
            return .pushProvisionFile
        case .relaunchImmortal:
            return .relaunchImmortal
        case .readAgentManifest:
            return .readAgentManifest
        case .installVerifiedArtifact:
            return .installVerifiedArtifact
        case .applyEstablishedSetup:
            return .applyEstablishedSetup
        }
    }

    var device: String? {
        switch self {
        case .enumerateDevices:
            return nil
        case .inspect(let device, _),
             .pushProvisionFile(let device, _),
             .relaunchImmortal(let device),
             .readAgentManifest(let device),
             .installVerifiedArtifact(let device, _),
             .applyEstablishedSetup(let device, _):
            return device
        }
    }
}

// MARK: - Typed results

struct ADBDeviceDescriptor: Codable, Sendable, Equatable, Hashable {
    let serial: String
    let authorization: ADBAuthorizationState
    let connection: ADBConnectionState
    let model: String?
}

struct ADBDeviceEnumerationResult: Codable, Sendable, Equatable, Hashable, ADBResult {
    let devices: [ADBDeviceDescriptor]
}

enum ADBInspectionValue: Sendable, Equatable, Hashable {
    case authorization(ADBAuthorizationState)
    case connection(ADBConnectionState)
    case serial(String?)
    case model(String?)
    case apiLevel(Int?)
    case installedImmortal(ImmortalInstallationState)
    case immortalVersion(AppVersion?)
    case fleetAgent(FleetAgentState)
}

struct ADBInspectionResult: Sendable, Equatable, Hashable, ADBResult {
    let device: String
    let field: DeviceField
    let value: ADBInspectionValue
}

/// A successful non-query command exposes only a redacted transcript. The
/// transcript is bounded and never contains the raw process bytes.
struct SanitizedADBProcessOutput: Codable, Sendable, Equatable, Hashable {
    let stdout: String
    let stderr: String
}

struct ADBCommandAcknowledgement: Codable, Sendable, Equatable, Hashable, ADBResult {
    let kind: ADBCommandKind
    let output: SanitizedADBProcessOutput
}

typealias ADBCommandResult = ADBCommandAcknowledgement

/// The manifest token is deliberately transient. The provisioning coordinator
/// must write `bearerToken` directly to Keychain and must not persist or project
/// it into a registry/UI model. The non-secret manifest projection is safe to
/// retain after this value is cleared by the active operation.
struct ADBManifestReadResult: Sendable, Equatable, ADBResult {
    let manifest: AgentManifest
    let bearerToken: Data

    var token: Data { bearerToken }
}

typealias AgentManifestReadResult = ADBManifestReadResult

// MARK: - Process boundary

/// A fully constructed direct-process invocation. Only `ProcessADBRunner` can
/// construct instances for the application; the executor has no public
/// application-facing command API.
struct ADBProcessInvocation: Sendable, Equatable {
    let executableURL: URL
    let arguments: [String]
    let environment: [String: String]
    let workingDirectory: URL?
}

/// Raw process bytes live only between the executor and the runner. They are
/// parsed or redacted immediately and are never included in a failure type.
struct ADBProcessResult: Sendable, Equatable {
    let terminationStatus: Int32
    let stdout: Data
    let stderr: Data
}

typealias ADBProcessOutput = ADBProcessResult

protocol ADBProcessExecutor: Sendable {
    func execute(_ invocation: ADBProcessInvocation) async throws -> ADBProcessResult
}

enum ADBProcessFailure: Error, Sendable, Equatable {
    case launchFailed
    case timedOut
}

/// Direct Foundation `Process` execution. It never invokes a shell and never
/// inherits the caller's environment.
struct FoundationADBProcessExecutor: ADBProcessExecutor, Sendable {
    func execute(_ invocation: ADBProcessInvocation) async throws -> ADBProcessResult {
        try Task.checkCancellation()

        let process = Process()
        process.executableURL = invocation.executableURL
        process.arguments = invocation.arguments
        process.environment = invocation.environment
        process.currentDirectoryURL = invocation.workingDirectory

        let stdoutPipe = Pipe()
        let stderrPipe = Pipe()
        process.standardOutput = stdoutPipe
        process.standardError = stderrPipe

        do {
            try process.run()
        } catch {
            throw ADBProcessFailure.launchFailed
        }

        process.waitUntilExit()
        try Task.checkCancellation()

        return ADBProcessResult(
            terminationStatus: process.terminationStatus,
            stdout: stdoutPipe.fileHandleForReading.readDataToEndOfFile(),
            stderr: stderrPipe.fileHandleForReading.readDataToEndOfFile()
        )
    }
}

enum ADBRunnerErrorCode: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case invalidRequest
    case invalidExecutable
    case invalidDevice
    case invalidLocalFile
    case processLaunchFailed
    case processFailed
    case timedOut
    case malformedOutput
    case invalidAgentManifest
}

struct ADBRunnerError: Error, LocalizedError, Codable, Sendable, Equatable, Hashable {
    let code: ADBRunnerErrorCode
    let command: ADBCommandKind?

    init(code: ADBRunnerErrorCode, command: ADBCommandKind? = nil) {
        self.code = code
        self.command = command
    }

    var sanitizedMessage: String {
        switch code {
        case .invalidRequest:
            return "The provisioning request is not an allowlisted ADB command."
        case .invalidExecutable:
            return "The selected ADB executable is unavailable or not executable."
        case .invalidDevice:
            return "The selected ADB device identifier is invalid."
        case .invalidLocalFile:
            return "The selected local provisioning file is unavailable."
        case .processLaunchFailed:
            return "The selected ADB executable could not be started."
        case .processFailed:
            return "The allowlisted ADB operation failed."
        case .timedOut:
            return "The allowlisted ADB operation timed out."
        case .malformedOutput:
            return "The allowlisted ADB operation returned an unexpected result."
        case .invalidAgentManifest:
            return "The Fleet Agent handoff manifest was invalid."
        }
    }

    var errorDescription: String? { sanitizedMessage }
}

// MARK: - Production runner

/// A direct-process ADB runner with a closed command mapping.
///
/// The selected executable is the only executable this type can run. Every
/// argument other than an explicitly validated device serial or selected local
/// input is a constant owned by this adapter. The environment is replaced with
/// a small deterministic allowlist, so user shell configuration and downloader
/// hooks cannot affect provisioning.
struct ProcessADBRunner: ADBRunner, Sendable {
    static let fixedEnvironment: [String: String] = [
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": "/usr/bin:/bin:/usr/sbin:/sbin"
    ]

    private static let packageIdentity = "com.immortal.launcher"
    private static let homeActivity = "com.immortal.launcher/.HomeActivity"
    private static let fleetDirectory = "/sdcard/Android/data/com.immortal.launcher/files/fleet"
    private static let provisionPath = fleetDirectory + "/provision.json"
    private static let agentManifestPath = fleetDirectory + "/agent.json"
    private static let maximumDiagnosticCharacters = 4096

    let executable: LocalExecutableReference
    private let processExecutor: any ADBProcessExecutor
    private let redactor: any Redactor
    private let securityScope: any SecurityScopedResourceAccess

    init(
        executable: LocalExecutableReference,
        processExecutor: any ADBProcessExecutor = FoundationADBProcessExecutor(),
        redactor: any Redactor = StructuredRedactor(),
        securityScope: any SecurityScopedResourceAccess = FoundationSecurityScopedResourceAccess()
    ) {
        self.executable = executable
        self.processExecutor = processExecutor
        self.redactor = redactor
        self.securityScope = securityScope
    }

    func execute(_ request: any ADBRequest) async throws -> any ADBResult {
        guard let command = request as? ADBCommand else {
            throw ADBRunnerError(code: .invalidRequest)
        }

        // Scope both selections before validation: a sandboxed file picker URL
        // may not be stat-able until its security scope is active. The scopes
        // end when this command, including cancellation/error handling, ends.
        let executableAccess = securityScope.startAccessing(executable.securityScopedURL)
        defer {
            if executableAccess {
                securityScope.stopAccessing(executable.securityScopedURL)
            }
        }

        let inputURL = command.localInputURL
        let inputAccess = inputURL.map { securityScope.startAccessing($0) } ?? false
        defer {
            if inputAccess, let inputURL {
                securityScope.stopAccessing(inputURL)
            }
        }

        try validateExecutable()
        try validate(command)

        let invocations = try makeInvocations(for: command)
        var processResults: [ADBProcessResult] = []
        var sanitizedOutputs: [SanitizedADBProcessOutput] = []
        processResults.reserveCapacity(invocations.count)
        sanitizedOutputs.reserveCapacity(invocations.count)

        for invocation in invocations {
            let result: ADBProcessResult
            do {
                result = try await processExecutor.execute(invocation)
            } catch is CancellationError {
                throw CancellationError()
            } catch let failure as ADBProcessFailure {
                switch failure {
                case .launchFailed:
                    throw ADBRunnerError(code: .processLaunchFailed, command: command.kind)
                case .timedOut:
                    throw ADBRunnerError(code: .timedOut, command: command.kind)
                }
            } catch {
                // Do not forward executor descriptions: they may include a
                // local path, argument, or environment detail.
                throw ADBRunnerError(code: .processFailed, command: command.kind)
            }

            let sanitized = sanitize(result)
            processResults.append(result)
            sanitizedOutputs.append(sanitized)

            guard result.terminationStatus == 0 else {
                throw ADBRunnerError(code: .processFailed, command: command.kind)
            }
        }

        guard let finalResult = processResults.last else {
            throw ADBRunnerError(code: .processFailed, command: command.kind)
        }

        return try makeResult(
            for: command,
            finalResult: finalResult,
            output: merge(sanitizedOutputs)
        )
    }

    private func validateExecutable() throws {
        let url = executable.securityScopedURL
        guard executable.isSafeSelection,
              url.isFileURL,
              !url.path.isEmpty,
              url.path != "/",
              !url.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              FileManager.default.fileExists(atPath: url.path),
              FileManager.default.isExecutableFile(atPath: url.path) else {
            throw ADBRunnerError(code: .invalidExecutable)
        }
    }

    private func validate(_ command: ADBCommand) throws {
        if let device = command.device {
            guard Self.isSafeDeviceIdentifier(device) else {
                throw ADBRunnerError(code: .invalidDevice, command: command.kind)
            }
        }

        guard let localURL = command.localInputURL else { return }
        guard Self.isReadableRegularFile(localURL) else {
            throw ADBRunnerError(code: .invalidLocalFile, command: command.kind)
        }

        if case .pushProvisionFile = command,
           localURL.lastPathComponent != "provision.json" {
            throw ADBRunnerError(code: .invalidLocalFile, command: command.kind)
        }
    }

    private func makeInvocations(for command: ADBCommand) throws -> [ADBProcessInvocation] {
        switch command {
        case .enumerateDevices:
            return [invocation(arguments: ["devices", "-l"])]

        case .inspect(let device, let field):
            switch field {
            case .authorization, .connection:
                // `devices -l` is the only ADB query that distinguishes an
                // attached-but-unauthorized device without a remote command.
                return [invocation(arguments: ["devices", "-l"])]
            case .serial:
                return [targetedInvocation(device: device, arguments: ["get-serialno"])]
            case .model:
                return [targetedInvocation(
                    device: device,
                    arguments: ["shell", "getprop", "ro.product.model"]
                )]
            case .apiLevel:
                return [targetedInvocation(
                    device: device,
                    arguments: ["shell", "getprop", "ro.build.version.sdk"]
                )]
            case .installedImmortal:
                return [targetedInvocation(
                    device: device,
                    arguments: ["shell", "cmd", "package", "path", Self.packageIdentity]
                )]
            case .immortalVersion:
                return [targetedInvocation(
                    device: device,
                    arguments: ["shell", "dumpsys", "package", Self.packageIdentity]
                )]
            case .fleetAgent:
                return [targetedInvocation(
                    device: device,
                    arguments: ["shell", "ls", Self.agentManifestPath]
                )]
            }

        case .pushProvisionFile(let device, let localURL):
            return [targetedInvocation(
                device: device,
                arguments: ["push", localURL.path, Self.provisionPath]
            )]

        case .relaunchImmortal(let device):
            return [
                targetedInvocation(
                    device: device,
                    arguments: ["shell", "am", "force-stop", Self.packageIdentity]
                ),
                targetedInvocation(
                    device: device,
                    arguments: ["shell", "am", "start", "-n", Self.homeActivity]
                )
            ]

        case .readAgentManifest(let device):
            return [targetedInvocation(
                device: device,
                arguments: ["shell", "cat", Self.agentManifestPath]
            )]

        case .installVerifiedArtifact(let device, let localURL):
            return [targetedInvocation(
                device: device,
                arguments: ["install", "-r", "-d", localURL.path]
            )]

        case .applyEstablishedSetup(let device, let step):
            return [targetedInvocation(device: device, arguments: setupArguments(for: step))]
        }
    }

    private func setupArguments(for step: SetupStep) -> [String] {
        switch step {
        case .enableDeveloperSettings:
            return [
                "shell", "settings", "put", "global",
                "development_settings_enabled", "1"
            ]
        case .hideStatusBar:
            return [
                "shell", "settings", "put", "global",
                "policy_control", "immersive.status=*"
            ]
        case .allowHiddenAPI:
            return [
                "shell", "settings", "put", "global",
                "hidden_api_policy", "1"
            ]
        case .disableInstallerOverlay:
            return [
                "shell", "cmd", "overlay", "disable",
                "com.facebook.aloha.rro.niu.android"
            ]
        case .disableSettingsOverlay:
            return [
                "shell", "cmd", "overlay", "disable",
                "com.facebook.aloha.rro.niu.settings"
            ]
        }
    }

    private func invocation(arguments: [String]) -> ADBProcessInvocation {
        ADBProcessInvocation(
            executableURL: executable.securityScopedURL,
            arguments: arguments,
            environment: Self.fixedEnvironment,
            workingDirectory: nil
        )
    }

    private func targetedInvocation(device: String, arguments: [String]) -> ADBProcessInvocation {
        invocation(arguments: ["-s", device] + arguments)
    }

    private func makeResult(
        for command: ADBCommand,
        finalResult: ADBProcessResult,
        output: SanitizedADBProcessOutput
    ) throws -> any ADBResult {
        switch command {
        case .enumerateDevices:
            return try parseEnumeration(finalResult.stdout)
        case .inspect(let device, let field):
            return try parseInspection(device: device, field: field, output: finalResult.stdout)
        case .readAgentManifest:
            return try parseAgentManifest(finalResult.stdout)
        case .pushProvisionFile,
             .relaunchImmortal,
             .installVerifiedArtifact,
             .applyEstablishedSetup:
            return ADBCommandAcknowledgement(kind: command.kind, output: output)
        }
    }

    private func sanitize(_ result: ADBProcessResult) -> SanitizedADBProcessOutput {
        let stdout = bounded(redactor.redact(result.stdout).value)
        let stderr = bounded(redactor.redact(result.stderr).value)
        return SanitizedADBProcessOutput(stdout: stdout, stderr: stderr)
    }

    private func merge(_ outputs: [SanitizedADBProcessOutput]) -> SanitizedADBProcessOutput {
        SanitizedADBProcessOutput(
            stdout: bounded(outputs.map(\.stdout).filter { !$0.isEmpty }.joined(separator: "\n")),
            stderr: bounded(outputs.map(\.stderr).filter { !$0.isEmpty }.joined(separator: "\n"))
        )
    }

    private func bounded(_ value: String) -> String {
        guard value.count > Self.maximumDiagnosticCharacters else { return value }
        let end = value.index(value.startIndex, offsetBy: Self.maximumDiagnosticCharacters)
        return String(value[..<end])
    }

    private func parseEnumeration(_ data: Data) throws -> ADBDeviceEnumerationResult {
        let lines = text(data).split(whereSeparator: \.isNewline)
        var devices: [ADBDeviceDescriptor] = []

        for line in lines {
            let fields = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
            guard fields.count >= 2,
                  fields[0] != "List",
                  let serial = safeDeviceIdentifier(String(fields[0])) else {
                continue
            }

            let state = String(fields[1]).lowercased()
            let authorization: ADBAuthorizationState
            let connection: ADBConnectionState
            switch state {
            case "device":
                authorization = .authorized
                connection = .connected
            case "unauthorized":
                authorization = .unauthorized
                connection = .connected
            case "offline":
                authorization = .unknown
                connection = .disconnected
            default:
                authorization = .unknown
                connection = .unknown
            }

            let model = fields.dropFirst(2)
                .first(where: { $0.hasPrefix("model:") })
                .map { String($0.dropFirst("model:".count)).replacingOccurrences(of: "_", with: " ") }
                .flatMap(safeText)

            devices.append(
                ADBDeviceDescriptor(
                    serial: serial,
                    authorization: authorization,
                    connection: connection,
                    model: model
                )
            )
        }

        return ADBDeviceEnumerationResult(devices: devices)
    }

    private func parseInspection(
        device: String,
        field: DeviceField,
        output: Data
    ) throws -> ADBInspectionResult {
        let raw = text(output)
        switch field {
        case .authorization:
            let descriptor = try parseEnumeration(output).devices.first { $0.serial == device }
            return ADBInspectionResult(
                device: device,
                field: field,
                value: .authorization(descriptor?.authorization ?? .unknown)
            )
        case .connection:
            let descriptor = try parseEnumeration(output).devices.first { $0.serial == device }
            return ADBInspectionResult(
                device: device,
                field: field,
                value: .connection(descriptor?.connection ?? .unavailable)
            )
        case .serial:
            return ADBInspectionResult(
                device: device,
                field: field,
                value: .serial(safeText(raw))
            )
        case .model:
            return ADBInspectionResult(
                device: device,
                field: field,
                value: .model(safeText(raw))
            )
        case .apiLevel:
            let level = Int(raw.trimmingCharacters(in: .whitespacesAndNewlines))
            return ADBInspectionResult(
                device: device,
                field: field,
                value: .apiLevel(level.flatMap { $0 > 0 ? $0 : nil })
            )
        case .installedImmortal:
            let normalized = raw.lowercased()
            let state: ImmortalInstallationState
            if normalized.contains("package:") {
                state = .installedCompatible
            } else if normalized.contains("not found") || normalized.contains("no package") {
                state = .notInstalled
            } else {
                state = .unknown
            }
            return ADBInspectionResult(
                device: device,
                field: field,
                value: .installedImmortal(state)
            )
        case .immortalVersion:
            return ADBInspectionResult(
                device: device,
                field: field,
                value: .immortalVersion(parseVersion(raw))
            )
        case .fleetAgent:
            let normalized = raw.lowercased()
            let state: FleetAgentState
            if normalized.contains("agent.json") {
                state = .enabled
            } else if normalized.contains("no such file") || normalized.contains("not found") {
                state = .disabled
            } else {
                state = .unknown
            }
            return ADBInspectionResult(
                device: device,
                field: field,
                value: .fleetAgent(state)
            )
        }
    }

    private func parseVersion(_ output: String) -> AppVersion? {
        var versionCode: Int64?
        var versionName: String?

        for line in output.split(whereSeparator: \.isNewline) {
            let value = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if value.hasPrefix("versionCode=") {
                let suffix = value.dropFirst("versionCode=".count)
                versionCode = Int64(String(suffix.split(separator: " ").first ?? ""))
            } else if value.hasPrefix("versionName=") {
                versionName = safeText(String(value.dropFirst("versionName=".count)))
            }
        }

        guard versionCode != nil || versionName != nil else { return nil }
        return AppVersion(versionCode: versionCode, versionName: versionName)
    }

    private func parseAgentManifest(_ data: Data) throws -> ADBManifestReadResult {
        struct RawAgentManifest: Decodable {
            let name: String?
            let token: String?
            let port: Int?
        }

        let raw: RawAgentManifest
        do {
            raw = try JSONDecoder().decode(RawAgentManifest.self, from: data)
        } catch {
            throw ADBRunnerError(code: .invalidAgentManifest, command: .readAgentManifest)
        }

        guard let name = raw.name.flatMap(safeText),
              let token = raw.token.flatMap(safeText),
              let port = raw.port,
              (1...Int(UInt16.max)).contains(port) else {
            throw ADBRunnerError(code: .invalidAgentManifest, command: .readAgentManifest)
        }

        return ADBManifestReadResult(
            manifest: AgentManifest(name: name, port: UInt16(port)),
            bearerToken: Data(token.utf8)
        )
    }

    private func text(_ data: Data) -> String {
        String(decoding: data, as: UTF8.self)
    }

    private static func isReadableRegularFile(_ url: URL) -> Bool {
        guard url.isFileURL,
              !url.path.isEmpty,
              url.path != "/",
              !url.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              FileManager.default.fileExists(atPath: url.path),
              FileManager.default.isReadableFile(atPath: url.path) else {
            return false
        }

        return (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true
    }

    private static func isSafeDeviceIdentifier(_ value: String) -> Bool {
        safeDeviceIdentifier(value) != nil
    }

    private func safeDeviceIdentifier(_ value: String) -> String? {
        Self.safeDeviceIdentifier(value)
    }

    private static func safeDeviceIdentifier(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              trimmed.count <= 256,
              !trimmed.hasPrefix("-"),
              trimmed.unicodeScalars.allSatisfy({
                  CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._:")).contains($0)
              }) else {
            return nil
        }
        return trimmed
    }

    private func safeText(_ value: String) -> String? {
        Self.safeText(value)
    }

    private static func safeText(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              trimmed.count <= 4096,
              !trimmed.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
            return nil
        }
        return trimmed
    }
}

// Compatibility names for callers that describe the adapter by its role.
typealias LocalADBRunner = ProcessADBRunner
typealias ADBProcessRunner = ProcessADBRunner

private extension ADBCommand {
    var localInputURL: URL? {
        switch self {
        case .pushProvisionFile(_, let localURL),
             .installVerifiedArtifact(_, let localURL):
            return localURL
        default:
            return nil
        }
    }
}
