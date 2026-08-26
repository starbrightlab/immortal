/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import XCTest
@testable import PortalManager

private enum TempWorkspaceTestError: Error, Equatable {
    case expectedFailure
}

private final class RecordingSecurityScopedResourceAccess: SecurityScopedResourceAccess, @unchecked Sendable {
    private let lock = NSLock()
    private var startedURLs: [URL] = []
    private var stoppedURLs: [URL] = []
    private var activeURLs: Set<URL> = []

    func startAccessing(_ url: URL) -> Bool {
        lock.lock()
        startedURLs.append(url)
        activeURLs.insert(url)
        lock.unlock()
        return true
    }

    func stopAccessing(_ url: URL) {
        lock.lock()
        stoppedURLs.append(url)
        activeURLs.remove(url)
        lock.unlock()
    }

    var starts: [URL] {
        lock.lock()
        defer { lock.unlock() }
        return startedURLs
    }

    var stops: [URL] {
        lock.lock()
        defer { lock.unlock() }
        return stoppedURLs
    }

    var hasActiveScopes: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !activeURLs.isEmpty
    }
}

private actor WorkspaceADBProcessExecutor: ADBProcessExecutor {
    private var results: [ADBProcessResult]

    init(results: [ADBProcessResult]) {
        self.results = results
    }

    func execute(_ invocation: ADBProcessInvocation) async throws -> ADBProcessResult {
        guard !results.isEmpty else { throw ADBProcessFailure.launchFailed }
        return results.removeFirst()
    }
}

private struct WorkspaceArtifactMetadataReader: ArtifactPackageMetadataReader {
    let certificateDigest: String

    func readMetadata(from url: URL) throws -> ArtifactPackageMetadata {
        ArtifactPackageMetadata(
            packageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
            minAPILevel: 24,
            signatureCertificateSHA256: [certificateDigest],
            hasSignature: true
        )
    }
}

private final class RecordingProvisioningDownloadAudit: ProvisioningDownloadAudit, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [ProvisioningDownloadResource] = []

    func recordRejected(_ resource: ProvisioningDownloadResource) {
        lock.lock()
        values.append(resource)
        lock.unlock()
    }

    var resources: [ProvisioningDownloadResource] {
        lock.lock()
        defer { lock.unlock() }
        return values
    }
}

final class TempWorkspaceTests: XCTestCase {
    func testWorkspaceUsesRestrictedFixedFilesAndCleansUp() throws {
        let baseDirectory = try makeTestDirectory(named: "workspace")
        defer { try? FileManager.default.removeItem(at: baseDirectory) }
        let operationID = UUID()
        let workspace = try TempWorkspace(
            operationID: operationID,
            baseDirectory: baseDirectory
        )

        XCTAssertEqual(workspace.operationID, operationID)
        XCTAssertTrue(workspace.isActive)
        XCTAssertTrue(workspace.directoryURL.path.hasPrefix(baseDirectory.path))
        XCTAssertEqual(
            permissions(at: workspace.directoryURL),
            Int(TempWorkspace.directoryPermissions)
        )

        let provisionURL = try workspace.url(for: .provisionJSON)
        let agentURL = try workspace.url(for: .agentJSON)
        let transcriptURL = try workspace.url(for: .adbTranscript)
        XCTAssertEqual(provisionURL.lastPathComponent, "provision.json")
        XCTAssertEqual(agentURL.lastPathComponent, "agent.json")
        XCTAssertEqual(transcriptURL.lastPathComponent, "adb-transcript.log")

        try workspace.writeProvisionFile(Data("{\"enabled\":true}".utf8))
        try workspace.writeAgentManifest(
            Data("{\"name\":\"Living Room\",\"token\":\"TRANSIENT\",\"port\":8723}".utf8)
        )
        try workspace.appendTranscript(
            SanitizedADBProcessOutput(
                stdout: "Authorization: Bearer TRANSIENT",
                stderr: "token=TRANSIENT"
            )
        )

        XCTAssertEqual(
            try workspace.readAgentManifest(),
            Data("{\"name\":\"Living Room\",\"token\":\"TRANSIENT\",\"port\":8723}".utf8)
        )
        XCTAssertEqual(permissions(at: provisionURL), Int(TempWorkspace.filePermissions))
        XCTAssertEqual(permissions(at: agentURL), Int(TempWorkspace.filePermissions))
        XCTAssertEqual(permissions(at: transcriptURL), Int(TempWorkspace.filePermissions))

        let transcript = try String(contentsOf: transcriptURL, encoding: .utf8)
        XCTAssertFalse(transcript.contains("TRANSIENT"))
        XCTAssertTrue(transcript.contains(RedactionMarker.value))

        try workspace.cleanup()
        XCTAssertFalse(workspace.isActive)
        XCTAssertFalse(FileManager.default.fileExists(atPath: workspace.directoryURL.path))
        XCTAssertNoThrow(try workspace.cleanup())
        XCTAssertThrowsError(try workspace.readAgentManifest()) { error in
            XCTAssertEqual(error as? ProvisioningWorkspaceError, .workspaceClosed)
        }
    }

    func testFactoryCleansWorkspaceOnSuccessFailureAndCancellation() async throws {
        let baseDirectory = try makeTestDirectory(named: "factory")
        defer { try? FileManager.default.removeItem(at: baseDirectory) }
        let factory = TempWorkspaceFactory(baseDirectory: baseDirectory)

        let successID = UUID()
        let successURL = try await factory.withWorkspace(operationID: successID) { workspace in
            try workspace.writeProvisionFile(Data("{}".utf8))
            return workspace.directoryURL
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: successURL.path))

        let failureID = UUID()
        let failureURL = baseDirectory
            .appendingPathComponent("portal-manager-provisioning-\(failureID.uuidString)")
        do {
            let _: Void = try await factory.withWorkspace(operationID: failureID) { workspace in
                try workspace.writeAgentManifest(Data("{\"token\":\"TRANSIENT\"}".utf8))
                throw TempWorkspaceTestError.expectedFailure
            }
            XCTFail("The operation failure should be propagated.")
        } catch let error as TempWorkspaceTestError {
            XCTAssertEqual(error, .expectedFailure)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: failureURL.path))

        let cancellationID = UUID()
        let cancellationURL = baseDirectory
            .appendingPathComponent("portal-manager-provisioning-\(cancellationID.uuidString)")
        do {
            let _: Void = try await factory.withWorkspace(operationID: cancellationID) { _ in
                try Task.checkCancellation()
                throw CancellationError()
            }
            XCTFail("The cancellation should be propagated.")
        } catch is CancellationError {
            // Expected: the factory must clean before cancellation escapes.
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: cancellationURL.path))
    }

    func testADBAndArtifactSelectionsAreScopedOnlyForActiveOperations() async throws {
        let access = RecordingSecurityScopedResourceAccess()
        let provisionDirectory = try makeTestDirectory(named: "selection-scope")
        defer { try? FileManager.default.removeItem(at: provisionDirectory) }
        let provisionURL = provisionDirectory.appendingPathComponent("provision.json")
        try Data("{}".utf8).write(to: provisionURL)

        let adbExecutor = WorkspaceADBProcessExecutor(
            results: [ADBProcessResult(terminationStatus: 0, stdout: Data(), stderr: Data())]
        )
        let runner = ProcessADBRunner(
            executable: LocalExecutableReference(
                securityScopedURL: URL(fileURLWithPath: "/bin/echo"),
                displayName: "adb"
            ),
            processExecutor: adbExecutor,
            securityScope: access
        )
        _ = try await runner.execute(
            ADBCommand.pushProvisionFile(device: "USB-1", localURL: provisionURL)
        )

        let artifactURL = provisionDirectory.appendingPathComponent("Immortal-release.apk")
        try Data("local artifact".utf8).write(to: artifactURL)
        let digest = String(repeating: "a", count: 64)
        let artifact = LocalArtifact(
            securityScopedURL: artifactURL,
            displayName: "Immortal-release.apk",
            expectedPackageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
            expectedSignaturePolicy: .certificateSHA256(digest)
        )
        let verifier = LocalArtifactVerifier(
            metadataReader: WorkspaceArtifactMetadataReader(certificateDigest: digest),
            securityScope: access
        )
        let result = try await verifier.verify(artifact)
        XCTAssertTrue(result.passed)

        XCTAssertEqual(access.starts.count, 3)
        XCTAssertEqual(access.stops.count, 3)
        XCTAssertTrue(access.starts.contains(URL(fileURLWithPath: "/bin/echo")))
        XCTAssertTrue(access.starts.contains(provisionURL))
        XCTAssertTrue(access.starts.contains(artifactURL))
        XCTAssertFalse(access.hasActiveScopes)
    }

    func testNoDownloadBoundaryRejectsEveryProhibitedProvisioningResource() throws {
        let audit = RecordingProvisioningDownloadAudit()
        let boundary = NoDownloadProvisioningBoundary(audit: audit)

        XCTAssertFalse(boundary.allowsNetworkAcquisition)
        XCTAssertEqual(
            Set(ProvisioningDownloadResource.allCases),
            [.platformTools, .apk, .package, .releaseArtifact, .setupDependency]
        )

        for resource in ProvisioningDownloadResource.allCases {
            XCTAssertThrowsError(try boundary.reject(resource)) { error in
                XCTAssertEqual(
                    error as? ProvisioningDownloadBoundaryError,
                    .disabled(resource)
                )
            }
        }
        XCTAssertEqual(Set(audit.resources), Set(ProvisioningDownloadResource.allCases))
    }

    func testBootstrapUsesTheLocalWorkspaceAndRejectingDownloadBoundary() {
        let dependencies = DependencyContainer.bootstrap()
        XCTAssertTrue(dependencies.provisioningWorkspace is TempWorkspaceFactory)
        XCTAssertTrue(dependencies.provisioningDownloads is NoDownloadProvisioningBoundary)
        XCTAssertFalse(dependencies.provisioningDownloads.allowsNetworkAcquisition)
    }

    private func makeTestDirectory(named name: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("portal-manager-\(name)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        return url
    }

    private func permissions(at url: URL) -> Int? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
              let value = attributes[.posixPermissions] as? NSNumber else {
            return nil
        }
        return value.intValue & 0o777
    }
}
