/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

// MARK: - Operation workspace

/// The only files that a provisioning operation may create in its temporary
/// workspace. The enum is intentionally closed so a caller cannot smuggle an
/// arbitrary path into the local provisioning boundary.
enum ProvisioningWorkspaceFile: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case provisionJSON = "provision.json"
    case agentJSON = "agent.json"
    case adbTranscript = "adb-transcript.log"

    var fileName: String { rawValue }

    // Compatibility spellings for application code that names the handoff
    // files after their role rather than their on-disk names.
    static var provision: Self { .provisionJSON }
    static var agentManifest: Self { .agentJSON }
    static var transcript: Self { .adbTranscript }
}

enum ProvisioningWorkspaceError: Error, Codable, Sendable, Equatable, Hashable, LocalizedError {
    case creationFailed
    case invalidWorkspace
    case workspaceClosed
    case invalidFile
    case readFailed
    case writeFailed
    case cleanupFailed

    var errorDescription: String? {
        switch self {
        case .creationFailed:
            return "The provisioning temporary workspace could not be created."
        case .invalidWorkspace:
            return "The provisioning temporary workspace is invalid."
        case .workspaceClosed:
            return "The provisioning temporary workspace is no longer active."
        case .invalidFile:
            return "The provisioning workspace file is not allowlisted."
        case .readFailed:
            return "The provisioning workspace file could not be read."
        case .writeFailed:
            return "The provisioning workspace file could not be written."
        case .cleanupFailed:
            return "The provisioning temporary workspace could not be removed."
        }
    }
}

/// A non-persistable operation-local workspace. Its directory URL is available
/// only while the active operation is running; no workspace value is Codable
/// and no workspace path is present in provisioning progress or diagnostics.
protocol ProvisioningWorkspace: Sendable {
    var operationID: UUID { get }
    var directoryURL: URL { get }
    var isActive: Bool { get }

    func url(for file: ProvisioningWorkspaceFile) throws -> URL
    func write(_ data: Data, to file: ProvisioningWorkspaceFile) throws
    func read(_ file: ProvisioningWorkspaceFile) throws -> Data
    func appendTranscript(_ output: SanitizedADBProcessOutput) throws
    func cleanup() throws
}

extension ProvisioningWorkspace {
    func writeProvisionFile(_ data: Data) throws {
        try write(data, to: .provisionJSON)
    }

    func writeAgentManifest(_ data: Data) throws {
        try write(data, to: .agentJSON)
    }

    func readAgentManifest() throws -> Data {
        try read(.agentJSON)
    }
}

/// A real per-operation workspace rooted below the app/container temporary
/// directory. The lock makes the handle safe to pass between an application
/// actor and an infrastructure process callback without exposing a second
/// persistence surface.
final class TempWorkspace: ProvisioningWorkspace, @unchecked Sendable {
    static let directoryPermissions: Int16 = 0o700
    static let filePermissions: Int16 = 0o600
    static let maximumTranscriptBytes = 64 * 1024

    let operationID: UUID
    let directoryURL: URL

    private let fileManager: FileManager
    private let redactor: any Redactor
    private let lock = NSLock()
    private var active = true

    init(
        operationID: UUID = UUID(),
        baseDirectory: URL = FileManager.default.temporaryDirectory,
        fileManager: FileManager = .default,
        redactor: any Redactor = StructuredRedactor()
    ) throws {
        self.operationID = operationID
        self.fileManager = fileManager
        self.redactor = redactor

        let base = baseDirectory.standardizedFileURL
        let directory = base.appendingPathComponent(
            "portal-manager-provisioning-\(operationID.uuidString)",
            isDirectory: true
        )
        self.directoryURL = directory

        do {
            try fileManager.createDirectory(
                at: base,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: NSNumber(value: Self.directoryPermissions)]
            )
            try fileManager.createDirectory(
                at: directory,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: NSNumber(value: Self.directoryPermissions)]
            )
            try Self.setPermissions(
                Self.directoryPermissions,
                at: directory,
                using: fileManager
            )
        } catch {
            throw ProvisioningWorkspaceError.creationFailed
        }
    }

    var isActive: Bool {
        lock.lock()
        defer { lock.unlock() }
        return active
    }

    func url(for file: ProvisioningWorkspaceFile) throws -> URL {
        lock.lock()
        defer { lock.unlock() }
        try requireActive()
        return fileURL(for: file)
    }

    func write(_ data: Data, to file: ProvisioningWorkspaceFile) throws {
        lock.lock()
        defer { lock.unlock() }
        try requireActive()

        do {
            let url = fileURL(for: file)
            try data.write(to: url, options: [.atomic])
            try Self.setPermissions(Self.filePermissions, at: url, using: fileManager)
        } catch {
            throw ProvisioningWorkspaceError.writeFailed
        }
    }

    func read(_ file: ProvisioningWorkspaceFile) throws -> Data {
        lock.lock()
        defer { lock.unlock() }
        try requireActive()

        do {
            return try Data(contentsOf: fileURL(for: file), options: [.mappedIfSafe])
        } catch {
            throw ProvisioningWorkspaceError.readFailed
        }
    }

    func appendTranscript(_ output: SanitizedADBProcessOutput) throws {
        lock.lock()
        defer { lock.unlock() }
        try requireActive()

        let safeOutput = SanitizedADBProcessOutput(
            stdout: redactor.redact(output.stdout).value,
            stderr: redactor.redact(output.stderr).value
        )
        do {
            var line = try JSONEncoder().encode(safeOutput)
            line.append(0x0A)

            let transcriptURL = fileURL(for: .adbTranscript)
            let existing = (try? Data(contentsOf: transcriptURL, options: [.mappedIfSafe])) ?? Data()
            let combined = existing + line
            let bounded: Data
            if combined.count > Self.maximumTranscriptBytes {
                bounded = Data(combined.suffix(Self.maximumTranscriptBytes))
            } else {
                bounded = combined
            }
            try bounded.write(to: transcriptURL, options: [.atomic])
            try Self.setPermissions(Self.filePermissions, at: transcriptURL, using: fileManager)
        } catch {
            throw ProvisioningWorkspaceError.writeFailed
        }
    }

    /// Cleanup is idempotent. The handle becomes inactive before removal so a
    /// concurrent callback cannot create new data while deletion is in flight.
    func cleanup() throws {
        lock.lock()
        guard active else {
            lock.unlock()
            return
        }
        active = false
        let directory = directoryURL
        lock.unlock()

        do {
            if fileManager.fileExists(atPath: directory.path) {
                try fileManager.removeItem(at: directory)
            }
            if fileManager.fileExists(atPath: directory.path) {
                throw ProvisioningWorkspaceError.cleanupFailed
            }
        } catch let error as ProvisioningWorkspaceError {
            throw error
        } catch {
            throw ProvisioningWorkspaceError.cleanupFailed
        }
    }

    deinit {
        try? cleanup()
    }

    private func requireActive() throws {
        guard active else { throw ProvisioningWorkspaceError.workspaceClosed }
        guard fileManager.fileExists(atPath: directoryURL.path) else {
            throw ProvisioningWorkspaceError.invalidWorkspace
        }
    }

    private func fileURL(for file: ProvisioningWorkspaceFile) -> URL {
        directoryURL.appendingPathComponent(file.fileName, isDirectory: false)
    }

    private static func setPermissions(
        _ permissions: Int16,
        at url: URL,
        using fileManager: FileManager
    ) throws {
        try fileManager.setAttributes(
            [.posixPermissions: NSNumber(value: permissions)],
            ofItemAtPath: url.path
        )
    }
}

/// Factory boundary used by the future provisioning coordinator. The default
/// helper guarantees cleanup on success, failure, and cooperative cancellation.
protocol ProvisioningWorkspaceFactory: Sendable {
    func makeWorkspace(operationID: UUID) throws -> any ProvisioningWorkspace
}

extension ProvisioningWorkspaceFactory {
    func makeWorkspace() throws -> any ProvisioningWorkspace {
        try makeWorkspace(operationID: UUID())
    }

    func withWorkspace<Result: Sendable>(
        operationID: UUID = UUID(),
        operation: @Sendable (any ProvisioningWorkspace) async throws -> Result
    ) async throws -> Result {
        let workspace = try makeWorkspace(operationID: operationID)
        do {
            let result = try await operation(workspace)
            try workspace.cleanup()
            return result
        } catch {
            try? workspace.cleanup()
            throw error
        }
    }
}

struct TempWorkspaceFactory: ProvisioningWorkspaceFactory, @unchecked Sendable {
    let baseDirectory: URL
    let fileManager: FileManager
    let redactor: any Redactor

    init(
        baseDirectory: URL = FileManager.default.temporaryDirectory,
        fileManager: FileManager = .default,
        redactor: any Redactor = StructuredRedactor()
    ) {
        self.baseDirectory = baseDirectory
        self.fileManager = fileManager
        self.redactor = redactor
    }

    func makeWorkspace(operationID: UUID) throws -> any ProvisioningWorkspace {
        try TempWorkspace(
            operationID: operationID,
            baseDirectory: baseDirectory,
            fileManager: fileManager,
            redactor: redactor
        )
    }
}

typealias TemporaryProvisioningWorkspaceFactory = TempWorkspaceFactory
typealias ProvisioningTempWorkspace = TempWorkspace

// MARK: - Security-scoped local selections

/// Injectable access to a security-scoped local selection. Ordinary local test
/// files may return false from `startAccessing...`; that means no extra scope is
/// needed, not that the file is rejected.
protocol SecurityScopedResourceAccess: Sendable {
    func startAccessing(_ url: URL) -> Bool
    func stopAccessing(_ url: URL)
}

struct FoundationSecurityScopedResourceAccess: SecurityScopedResourceAccess, Sendable {
    func startAccessing(_ url: URL) -> Bool {
        url.startAccessingSecurityScopedResource()
    }

    func stopAccessing(_ url: URL) {
        url.stopAccessingSecurityScopedResource()
    }
}

/// Keeps a selected local resource scoped for exactly the active synchronous
/// operation. It is shared by the ADB runner and artifact verifier so neither
/// adapter can accidentally retain a security-scoped selection.
func withSecurityScopedResourceAccess<Result>(
    _ url: URL,
    using access: any SecurityScopedResourceAccess,
    operation: () throws -> Result
) rethrows -> Result {
    let didStart = access.startAccessing(url)
    defer {
        if didStart {
            access.stopAccessing(url)
        }
    }
    return try operation()
}

// MARK: - No-download boundary

enum ProvisioningDownloadResource: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case platformTools
    case apk
    case package
    case releaseArtifact
    case setupDependency
}

enum ProvisioningDownloadBoundaryError: Error, Codable, Sendable, Equatable, Hashable, LocalizedError {
    case disabled(ProvisioningDownloadResource)

    var resource: ProvisioningDownloadResource {
        if case .disabled(let resource) = self { return resource }
        fatalError("unreachable")
    }

    var errorDescription: String? {
        "Network acquisition of provisioning inputs is disabled."
    }
}

/// A tiny audit seam lets deterministic tests prove that a future coordinator
/// attempted no prohibited acquisition. It carries only a typed resource kind,
/// never a URL or request payload.
protocol ProvisioningDownloadAudit: Sendable {
    func recordRejected(_ resource: ProvisioningDownloadResource)
}

struct NoDownloadProvisioningAudit: ProvisioningDownloadAudit, Sendable {
    func recordRejected(_ resource: ProvisioningDownloadResource) {}
}

protocol ProvisioningDownloadBoundary: Sendable {
    var allowsNetworkAcquisition: Bool { get }
    func reject(_ resource: ProvisioningDownloadResource) throws
}

/// This is deliberately a rejecting boundary rather than a downloader. Local
/// ADB and artifact selections are the only provisioning inputs; platform-tools,
/// APKs, packages, release artifacts, and setup dependencies have no network
/// acquisition representation in the macOS target.
struct NoDownloadProvisioningBoundary: ProvisioningDownloadBoundary, Sendable {
    let audit: any ProvisioningDownloadAudit

    init(audit: any ProvisioningDownloadAudit = NoDownloadProvisioningAudit()) {
        self.audit = audit
    }

    var allowsNetworkAcquisition: Bool { false }

    func reject(_ resource: ProvisioningDownloadResource) throws {
        audit.recordRejected(resource)
        throw ProvisioningDownloadBoundaryError.disabled(resource)
    }
}

typealias LocalOnlyProvisioningDownloadBoundary = NoDownloadProvisioningBoundary
