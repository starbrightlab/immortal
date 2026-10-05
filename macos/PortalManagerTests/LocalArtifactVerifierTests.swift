/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import CryptoKit
import Foundation
import XCTest
@testable import PortalManager

private struct FixedArtifactMetadataReader: ArtifactPackageMetadataReader {
    let metadata: ArtifactPackageMetadata

    func readMetadata(from url: URL) throws -> ArtifactPackageMetadata {
        XCTAssertTrue(url.isFileURL)
        return metadata
    }
}

final class LocalArtifactVerifierTests: XCTestCase {
    private let certificateDigest = String(repeating: "a", count: 64)

    func testVerifierExposesEveryCheckAndAllowsOnlyACompleteLocalArtifact() async throws {
        let data = Data("verified local artifact".utf8)
        let url = try makeTemporaryArtifact(data: data)
        defer { try? FileManager.default.removeItem(at: url) }

        let artifact = LocalArtifact(
            securityScopedURL: url,
            displayName: "Immortal-release.apk",
            expectedPackageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
            expectedSignaturePolicy: .certificateSHA256(certificateDigest)
        )
        let reader = FixedArtifactMetadataReader(
            metadata: ArtifactPackageMetadata(
                packageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
                minAPILevel: 24,
                targetAPILevel: 36,
                nativeABIs: ["arm64-v8a", "armeabi-v7a"],
                hasNativeLibraries: true,
                signatureCertificateSHA256: [certificateDigest],
                hasSignature: true,
                supportedModelFamilies: [.portalGo]
            )
        )
        let verifier = LocalArtifactVerifier(metadataReader: reader)
        let request = LocalArtifactVerificationRequest(
            artifact: artifact,
            expectedSHA256Digest: digest(of: data),
            target: ArtifactCompatibilityTarget(
                apiLevel: 29,
                modelFamily: .portalGo,
                rawModel: "Meta Portal Go"
            )
        )

        let result = try await verifier.verify(request)

        XCTAssertTrue(result.readableRegularFile.isPassed)
        XCTAssertTrue(result.packageIdentity.isPassed)
        XCTAssertTrue(result.signature.isPassed)
        XCTAssertTrue(result.digest.isPassed)
        XCTAssertTrue(result.apiCompatibility.isPassed)
        XCTAssertTrue(result.abiCompatibility.isPassed)
        XCTAssertTrue(result.targetModelCompatibility.isPassed)
        XCTAssertEqual(result.sha256Digest, digest(of: data))
        XCTAssertTrue(result.passed)
        XCTAssertTrue(result.installationAllowed)
        XCTAssertTrue(result.canInstall)
        XCTAssertTrue(result.failedChecks.isEmpty)
    }

    func testVerifierBlocksInstallationAndNamesEveryFailedDimension() async throws {
        let data = Data("invalid local artifact".utf8)
        let url = try makeTemporaryArtifact(data: data)
        defer { try? FileManager.default.removeItem(at: url) }

        let artifact = LocalArtifact(
            securityScopedURL: url,
            displayName: "wrong.apk",
            expectedPackageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
            expectedSignaturePolicy: .certificateSHA256(certificateDigest)
        )
        let reader = FixedArtifactMetadataReader(
            metadata: ArtifactPackageMetadata(
                packageIdentity: "com.example.untrusted",
                minAPILevel: 30,
                nativeABIs: ["x86_64"],
                hasNativeLibraries: true,
                signatureCertificateSHA256: [String(repeating: "b", count: 64)],
                hasSignature: true,
                supportedModelFamilies: [.portalGo]
            )
        )
        let verifier = LocalArtifactVerifier(metadataReader: reader)
        let request = LocalArtifactVerificationRequest(
            artifact: artifact,
            expectedSHA256Digest: String(repeating: "c", count: 64),
            target: ArtifactCompatibilityTarget(
                apiLevel: 29,
                modelFamily: .portalTV,
                rawModel: "Meta Portal TV"
            )
        )

        let result = try await verifier.verify(request)

        XCTAssertEqual(result.packageIdentity.failure, .packageIdentityMismatch)
        XCTAssertEqual(result.signature.failure, .signatureRejected)
        XCTAssertEqual(result.digest.failure, .digestMismatch)
        XCTAssertEqual(result.apiCompatibility.failure, .apiIncompatible)
        XCTAssertEqual(result.abiCompatibility.failure, .abiIncompatible)
        XCTAssertEqual(result.targetModelCompatibility.failure, .modelIncompatible)
        XCTAssertFalse(result.passed)
        XCTAssertFalse(result.installationAllowed)
        XCTAssertEqual(result.failedChecks.count, 6)

        do {
            _ = try await verifier.verifyBeforeInstall(request)
            XCTFail("A failed artifact dimension must block installation.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .artifactVerification(.packageIdentityMismatch))
        }
    }

    func testVerifierRejectsDirectoryBeforeReadingArtifactMetadata() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("portal-manager-artifact-directory-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }

        let artifact = LocalArtifact(
            securityScopedURL: directory,
            displayName: "directory",
            expectedPackageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
            expectedSignaturePolicy: .approvedPackageSignature
        )
        let reader = FixedArtifactMetadataReader(
            metadata: ArtifactPackageMetadata(
                packageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
                minAPILevel: 24,
                signatureCertificateSHA256: [certificateDigest],
                hasSignature: true
            )
        )
        let verifier = LocalArtifactVerifier(metadataReader: reader)

        let result = try await verifier.verify(artifact)

        XCTAssertEqual(result.readableRegularFile.failure, .notRegularFile)
        XCTAssertEqual(result.packageIdentity, .notEvaluated)
        XCTAssertEqual(result.signature, .notEvaluated)
        XCTAssertEqual(result.digest, .notEvaluated)
        XCTAssertFalse(result.passed)
    }

    func testCertificateAndNamedSignaturePoliciesAreExact() async throws {
        let data = Data("signature policy".utf8)
        let url = try makeTemporaryArtifact(data: data)
        defer { try? FileManager.default.removeItem(at: url) }

        let artifact = LocalArtifact(
            securityScopedURL: url,
            expectedPackageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
            expectedSignaturePolicy: .named("release")
        )
        let reader = FixedArtifactMetadataReader(
            metadata: ArtifactPackageMetadata(
                packageIdentity: LocalArtifactVerifier.immortalPackageIdentity,
                minAPILevel: 24,
                signatureCertificateSHA256: [certificateDigest],
                hasSignature: true
            )
        )
        let verifier = LocalArtifactVerifier(
            metadataReader: reader,
            configuration: LocalArtifactVerifier.Configuration(
                namedCertificateSHA256: ["release": [certificateDigest]]
            )
        )

        let result = try await verifier.verify(
            LocalArtifactVerificationRequest(artifact: artifact)
        )

        XCTAssertTrue(result.signature.isPassed)
        XCTAssertTrue(result.passed)
    }

    private func makeTemporaryArtifact(data: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("portal-manager-artifact-\(UUID().uuidString)")
            .appendingPathExtension("apk")
        try data.write(to: url, options: [.atomic])
        return url
    }

    private func digest(of data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
