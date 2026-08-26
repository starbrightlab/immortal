/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Compression
import CryptoKit
import Foundation
import Security

/// Safe failures from the local APK metadata reader. No path, archive bytes,
/// process output, or response text crosses this boundary.
enum ArtifactMetadataReadingError: Error, Equatable, Sendable {
    case invalidLocation
    case unreadable
    case malformedArchive
    case manifestMissing
    case manifestMalformed
    case signatureMissing
    case signatureMalformed
}

/// The metadata needed by the verifier after inspecting the local artifact.
/// This type deliberately contains no manifest text or signature bytes.
struct ArtifactPackageMetadata: Sendable, Equatable {
    let packageIdentity: String?
    let minAPILevel: Int?
    let maxAPILevel: Int?
    let targetAPILevel: Int?
    let nativeABIs: Set<String>
    let hasNativeLibraries: Bool
    let signatureCertificateSHA256: Set<String>
    let hasSignature: Bool
    let supportedModelFamilies: Set<PortalModelFamily>?

    init(
        packageIdentity: String?,
        minAPILevel: Int?,
        maxAPILevel: Int? = nil,
        targetAPILevel: Int? = nil,
        nativeABIs: Set<String> = [],
        hasNativeLibraries: Bool = false,
        signatureCertificateSHA256: Set<String> = [],
        hasSignature: Bool = false,
        supportedModelFamilies: Set<PortalModelFamily>? = nil
    ) {
        self.packageIdentity = packageIdentity?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.minAPILevel = minAPILevel
        self.maxAPILevel = maxAPILevel
        self.targetAPILevel = targetAPILevel
        self.nativeABIs = Set(nativeABIs.map { $0.lowercased() })
        self.hasNativeLibraries = hasNativeLibraries
        self.signatureCertificateSHA256 = Set(
            signatureCertificateSHA256.compactMap(Self.normalizedDigest)
        )
        self.hasSignature = hasSignature
        self.supportedModelFamilies = supportedModelFamilies
    }

    private static func normalizedDigest(_ value: String) -> String? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard normalized.count == 64,
              normalized.allSatisfy(\.isHexDigit) else {
            return nil
        }
        return normalized
    }
}

/// The production reader has no network or process dependency. Tests may use
/// this seam with deterministic metadata fixtures while still exercising the
/// complete verifier gate.
protocol ArtifactPackageMetadataReader: Sendable {
    func readMetadata(from url: URL) throws -> ArtifactPackageMetadata
}

/// Verifies a locally selected Immortal APK before a provisioning installer is
/// allowed to receive it. The verifier is a value type so it can safely be
/// injected into an actor-isolated coordinator.
struct LocalArtifactVerifier: ArtifactVerifier, Sendable {
    static let immortalPackageIdentity = "com.immortal.launcher"

    struct Configuration: Sendable, Equatable {
        let canonicalPackageIdentity: String
        let acceptedCertificateSHA256: Set<String>
        let namedCertificateSHA256: [String: Set<String>]

        init(
            canonicalPackageIdentity: String = LocalArtifactVerifier.immortalPackageIdentity,
            acceptedCertificateSHA256: Set<String> = [],
            namedCertificateSHA256: [String: Set<String>] = [:]
        ) {
            self.canonicalPackageIdentity = canonicalPackageIdentity
                .trimmingCharacters(in: .whitespacesAndNewlines)
            self.acceptedCertificateSHA256 = Set(
                acceptedCertificateSHA256.compactMap(Self.normalizedDigest)
            )
            self.namedCertificateSHA256 = namedCertificateSHA256.reduce(into: [:]) { result, item in
                let digests = Set(item.value.compactMap(Self.normalizedDigest))
                if !digests.isEmpty {
                    result[item.key] = digests
                }
            }
        }

        /// The production Immortal Release signer. Full provisioning rejects
        /// any APK that cannot prove this exact certificate.
        static let productionSigningCertificateSHA256 =
            "a0bdd0ab4a888d8d9ca31e78fd065bd6273c0d740672352c52900316df7bbbb0"

        static let immortalProduction = Configuration(
            acceptedCertificateSHA256: [productionSigningCertificateSHA256]
        )

        static let immortal = Configuration()

        private static func normalizedDigest(_ value: String) -> String? {
            let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard normalized.count == 64,
                  normalized.allSatisfy(\.isHexDigit) else {
                return nil
            }
            return normalized
        }
    }

    private let metadataReader: any ArtifactPackageMetadataReader
    private let configuration: Configuration
    private let securityScope: any SecurityScopedResourceAccess

    init(
        metadataReader: any ArtifactPackageMetadataReader = APKArtifactMetadataReader(),
        configuration: Configuration = .immortal,
        securityScope: any SecurityScopedResourceAccess = FoundationSecurityScopedResourceAccess()
    ) {
        self.metadataReader = metadataReader
        self.configuration = configuration
        self.securityScope = securityScope
    }

    /// Convenience entry point for callers that have not yet attached a
    /// preflight target. A coordinator should prefer the typed request with a
    /// target snapshot so model/API checks are evaluated against that device.
    func verify(_ artifact: LocalArtifact) async throws -> ArtifactVerificationSummary {
        try await verify(LocalArtifactVerificationRequest(artifact: artifact))
    }

    /// Performs verification and throws a safe manager error when the result
    /// cannot be handed to an installer. The ordinary `verify` method returns
    /// the full per-check result so the UI can show every dimension first.
    func verifyBeforeInstall(
        _ request: LocalArtifactVerificationRequest
    ) async throws -> ArtifactVerificationSummary {
        let result = try await verify(request)
        guard result.passed else {
            throw ManagerError.artifactVerification(
                result.blockingFailure ?? .digestUnavailable
            )
        }
        return result
    }

    func verify(
        _ request: LocalArtifactVerificationRequest
    ) async throws -> ArtifactVerificationSummary {
        let artifact = request.artifact
        let didStartAccessing = securityScope.startAccessing(artifact.securityScopedURL)
        defer {
            if didStartAccessing {
                securityScope.stopAccessing(artifact.securityScopedURL)
            }
        }

        let fileCheck = readableRegularFileCheck(for: artifact)
        guard fileCheck.isPassed else {
            return ArtifactVerificationSummary(
                readableRegularFile: fileCheck,
                packageIdentity: .notEvaluated,
                signature: .notEvaluated,
                sha256Digest: nil,
                apiCompatibility: .notEvaluated,
                abiCompatibility: .notEvaluated,
                targetModelCompatibility: .notEvaluated,
                digest: .notEvaluated
            )
        }

        let digest: String
        do {
            digest = try SHA256FileDigest.digest(at: artifact.securityScopedURL)
        } catch {
            return ArtifactVerificationSummary(
                readableRegularFile: fileCheck,
                packageIdentity: .notEvaluated,
                signature: .notEvaluated,
                sha256Digest: nil,
                apiCompatibility: .notEvaluated,
                abiCompatibility: .notEvaluated,
                targetModelCompatibility: .notEvaluated,
                digest: .failed(.digestUnavailable)
            )
        }

        let digestCheck = digestCheck(
            actual: digest,
            expected: request.expectedSHA256Digest
        )

        let metadata: ArtifactPackageMetadata
        do {
            metadata = try metadataReader.readMetadata(from: artifact.securityScopedURL)
        } catch {
            return ArtifactVerificationSummary(
                readableRegularFile: fileCheck,
                packageIdentity: .failed(.packageIdentityMismatch),
                signature: .failed(.signatureRejected),
                sha256Digest: digest,
                apiCompatibility: .failed(.apiIncompatible),
                abiCompatibility: .failed(.abiIncompatible),
                targetModelCompatibility: .failed(.modelIncompatible),
                digest: digestCheck
            )
        }

        let packageCheck = packageIdentityCheck(
            expected: artifact.expectedPackageIdentity,
            observed: metadata.packageIdentity
        )
        let signatureCheck = signatureCheck(
            policy: artifact.expectedSignaturePolicy,
            metadata: metadata
        )
        let apiCheck = apiCompatibilityCheck(
            metadata: metadata,
            request: request
        )
        let abiCheck = abiCompatibilityCheck(
            metadata: metadata,
            supportedABIs: request.supportedABIs
        )
        let modelCheck = modelCompatibilityCheck(
            metadata: metadata,
            request: request
        )

        return ArtifactVerificationSummary(
            readableRegularFile: fileCheck,
            packageIdentity: packageCheck,
            signature: signatureCheck,
            sha256Digest: digest,
            apiCompatibility: apiCheck,
            abiCompatibility: abiCheck,
            targetModelCompatibility: modelCheck,
            digest: digestCheck
        )
    }

    func verify(
        _ request: any ArtifactVerificationRequest
    ) async throws -> any ArtifactVerificationResult {
        if let typedRequest = request as? LocalArtifactVerificationRequest {
            return try await verify(typedRequest)
        }
        if let artifact = request as? LocalArtifact {
            return try await verify(artifact)
        }
        throw LocalArtifactVerifierError.unsupportedRequest
    }

    private func readableRegularFileCheck(for artifact: LocalArtifact) -> ArtifactCheck {
        let url = artifact.securityScopedURL
        guard url.isFileURL,
              artifact.hasSecurityScopedSelection else {
            return .failed(.notReadable)
        }

        do {
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isReadableKey])
            guard values.isRegularFile == true else {
                return .failed(.notRegularFile)
            }
            guard values.isReadable == true,
                  FileManager.default.isReadableFile(atPath: url.path) else {
                return .failed(.notReadable)
            }
            return .passed
        } catch {
            return .failed(.notReadable)
        }
    }

    private func digestCheck(actual: String, expected: String?) -> ArtifactCheck {
        guard let expected else {
            // The computed digest is the recorded integrity identifier when
            // no separately supplied release-manifest digest is available.
            return .passed
        }
        let normalized = expected.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard normalized.count == 64,
              normalized.allSatisfy(\.isHexDigit) else {
            return .failed(.digestMismatch)
        }
        return normalized == actual ? .passed : .failed(.digestMismatch)
    }

    private func packageIdentityCheck(
        expected: String,
        observed: String?
    ) -> ArtifactCheck {
        guard expected == configuration.canonicalPackageIdentity,
              observed == expected else {
            return .failed(.packageIdentityMismatch)
        }
        return .passed
    }

    private func signatureCheck(
        policy: SignaturePolicy,
        metadata: ArtifactPackageMetadata
    ) -> ArtifactCheck {
        guard metadata.hasSignature,
              !metadata.signatureCertificateSHA256.isEmpty else {
            return .failed(.signatureRejected)
        }

        let certificateDigests = metadata.signatureCertificateSHA256
        switch policy {
        case .approvedPackageSignature:
            // The product profile may pin the release certificate. When the
            // profile has no pin, a structurally valid APK certificate is
            // still required; the explicit certificate policy remains
            // available for deployments that require a fingerprint pin.
            if configuration.acceptedCertificateSHA256.isEmpty
                || !certificateDigests.isDisjoint(with: configuration.acceptedCertificateSHA256) {
                return .passed
            }
            return .failed(.signatureRejected)
        case .certificateSHA256(let expected):
            let normalized = expected.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard normalized.count == 64,
                  normalized.allSatisfy(\.isHexDigit),
                  certificateDigests.contains(normalized) else {
                return .failed(.signatureRejected)
            }
            return .passed
        case .named(let name):
            guard let accepted = configuration.namedCertificateSHA256[name],
                  !certificateDigests.isDisjoint(with: accepted) else {
                return .failed(.signatureRejected)
            }
            return .passed
        }
    }

    private func apiCompatibilityCheck(
        metadata: ArtifactPackageMetadata,
        request: LocalArtifactVerificationRequest
    ) -> ArtifactCheck {
        let supported = request.supportedAPILevels.filter { $0 == 28 || $0 == 29 }
        guard !supported.isEmpty,
              let minimum = metadata.minAPILevel,
              minimum > 0 else {
            return .failed(.apiIncompatible)
        }

        let candidates: Set<Int>
        if let targetAPILevel = request.target?.apiLevel {
            candidates = [targetAPILevel]
        } else {
            candidates = supported
        }

        guard candidates.allSatisfy({ supported.contains($0) && $0 >= minimum }) else {
            return .failed(.apiIncompatible)
        }
        if let maximum = metadata.maxAPILevel,
           candidates.contains(where: { $0 > maximum }) {
            return .failed(.apiIncompatible)
        }
        return .passed
    }

    private func abiCompatibilityCheck(
        metadata: ArtifactPackageMetadata,
        supportedABIs: Set<String>
    ) -> ArtifactCheck {
        guard supportedABIs.contains("arm64-v8a") else {
            return .failed(.abiIncompatible)
        }
        guard !metadata.hasNativeLibraries
            || !metadata.nativeABIs.isDisjoint(with: supportedABIs) else {
            return .failed(.abiIncompatible)
        }
        return .passed
    }

    private func modelCompatibilityCheck(
        metadata: ArtifactPackageMetadata,
        request: LocalArtifactVerificationRequest
    ) -> ArtifactCheck {
        guard let targetFamily = request.target?.modelFamily,
              targetFamily != .unknown else {
            return .passed
        }

        let applicableFamilies = metadata.supportedModelFamilies
            ?? request.supportedModelFamilies
        return applicableFamilies.contains(targetFamily)
            ? .passed
            : .failed(.modelIncompatible)
    }
}

enum LocalArtifactVerifierError: Error, Equatable, Sendable, LocalizedError {
    case unsupportedRequest

    var errorDescription: String? {
        "The local artifact verification request is unsupported."
    }
}

// MARK: - Local APK metadata reader

/// Reads the small, stable subset of an APK required by the provisioning gate.
/// APK entries are parsed in-process; no executable or external service is
/// consulted.
struct APKArtifactMetadataReader: ArtifactPackageMetadataReader, Sendable {
    func readMetadata(from url: URL) throws -> ArtifactPackageMetadata {
        guard url.isFileURL else {
            throw ArtifactMetadataReadingError.invalidLocation
        }

        let archive: APKArchive
        do {
            archive = try APKArchive(url: url)
        } catch {
            throw ArtifactMetadataReadingError.malformedArchive
        }

        guard let manifestData = try? archive.data(named: "AndroidManifest.xml") else {
            throw ArtifactMetadataReadingError.manifestMissing
        }

        let manifest: AndroidManifestMetadata
        do {
            manifest = try AndroidManifestParser.parse(manifestData)
        } catch {
            throw ArtifactMetadataReadingError.manifestMalformed
        }

        let allEntries = archive.entries
        let nativeEntries = allEntries.filter { entry in
            entry.name.lowercased().hasPrefix("lib/")
                && entry.name.lowercased().hasSuffix(".so")
        }
        let nativeABIs = Set(nativeEntries.compactMap { entry -> String? in
            let pieces = entry.name.split(separator: "/")
            guard pieces.count >= 3 else { return nil }
            return String(pieces[1]).lowercased()
        })

        let signatureEntries = allEntries.filter { entry in
            let name = entry.name.uppercased()
            guard name.hasPrefix("META-INF/") else { return false }
            return name.hasSuffix(".RSA") || name.hasSuffix(".DSA") || name.hasSuffix(".EC")
        }
        guard !signatureEntries.isEmpty else {
            throw ArtifactMetadataReadingError.signatureMissing
        }

        var certificates = Set<String>()
        for entry in signatureEntries {
            guard let signatureData = try? archive.data(named: entry.name) else {
                continue
            }
            certificates.formUnion(
                APKCertificateExtractor.sha256Digests(in: signatureData)
            )
        }
        guard !certificates.isEmpty else {
            throw ArtifactMetadataReadingError.signatureMalformed
        }

        return ArtifactPackageMetadata(
            packageIdentity: manifest.packageIdentity,
            minAPILevel: manifest.minAPILevel,
            maxAPILevel: manifest.maxAPILevel,
            targetAPILevel: manifest.targetAPILevel,
            nativeABIs: nativeABIs,
            hasNativeLibraries: !nativeEntries.isEmpty,
            signatureCertificateSHA256: certificates,
            hasSignature: true,
            supportedModelFamilies: manifest.supportedModelFamilies
        )
    }
}

private struct SHA256FileDigest {
    static func digest(at url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1024 * 1024), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

private struct APKArchiveEntry: Sendable, Equatable {
    let name: String
    let compressionMethod: UInt16
    let flags: UInt16
    let compressedSize: Int
    let uncompressedSize: Int
    let localHeaderOffset: Int
}

private struct APKArchive {
    let data: Data
    let entries: [APKArchiveEntry]

    init(url: URL) throws {
        guard url.isFileURL else {
            throw ArtifactMetadataReadingError.invalidLocation
        }
        do {
            data = try Data(contentsOf: url, options: [.mappedIfSafe])
        } catch {
            throw ArtifactMetadataReadingError.unreadable
        }
        entries = try Self.readEntries(from: data)
    }

    func data(named name: String) throws -> Data {
        guard let entry = entries.first(where: { $0.name == name }) else {
            throw ArtifactMetadataReadingError.manifestMissing
        }
        guard entry.flags & 0x0001 == 0,
              entry.compressedSize >= 0,
              entry.uncompressedSize >= 0 else {
            throw ArtifactMetadataReadingError.malformedArchive
        }

        let local = entry.localHeaderOffset
        guard readUInt32(data, at: local) == 0x04034b50 else {
            throw ArtifactMetadataReadingError.malformedArchive
        }
        guard let nameLength = int(readUInt16(data, at: local + 26)),
              let extraLength = int(readUInt16(data, at: local + 28)) else {
            throw ArtifactMetadataReadingError.malformedArchive
        }
        let contentStart = local + 30 + nameLength + extraLength
        guard let range = checkedRange(
            start: contentStart,
            length: entry.compressedSize,
            in: data
        ) else {
            throw ArtifactMetadataReadingError.malformedArchive
        }
        let compressed = data.subdata(in: range)

        switch entry.compressionMethod {
        case 0:
            guard compressed.count == entry.uncompressedSize else {
                throw ArtifactMetadataReadingError.malformedArchive
            }
            return compressed
        case 8:
            return try inflate(compressed, expectedSize: entry.uncompressedSize)
        default:
            throw ArtifactMetadataReadingError.malformedArchive
        }
    }

    private static func readEntries(from data: Data) throws -> [APKArchiveEntry] {
        guard data.count >= 22 else {
            throw ArtifactMetadataReadingError.malformedArchive
        }

        let minimumOffset = max(0, data.count - 65_557)
        var endOfCentralDirectory: Int?
        if data.count >= 22 {
            for offset in stride(from: data.count - 22, through: minimumOffset, by: -1) {
                if readUInt32(data, at: offset) == 0x06054b50 {
                    endOfCentralDirectory = offset
                    break
                }
            }
        }
        guard let eocd = endOfCentralDirectory,
              let count = int(readUInt16(data, at: eocd + 10)),
              let centralSize = int(readUInt32(data, at: eocd + 12)),
              let centralOffset = int(readUInt32(data, at: eocd + 16)),
              let centralRange = checkedRange(
                start: centralOffset,
                length: centralSize,
                in: data
              ) else {
            throw ArtifactMetadataReadingError.malformedArchive
        }

        var entries: [APKArchiveEntry] = []
        entries.reserveCapacity(count)
        var cursor = centralRange.lowerBound
        for _ in 0..<count {
            guard readUInt32(data, at: cursor) == 0x02014b50 else {
                throw ArtifactMetadataReadingError.malformedArchive
            }
            guard let flags = uint16(data, at: cursor + 8),
                  let compressionMethod = uint16(data, at: cursor + 10),
                  let compressedSize = int(readUInt32(data, at: cursor + 20)),
                  let uncompressedSize = int(readUInt32(data, at: cursor + 24)),
                  let nameLength = int(readUInt16(data, at: cursor + 28)),
                  let extraLength = int(readUInt16(data, at: cursor + 30)),
                  let commentLength = int(readUInt16(data, at: cursor + 32)),
                  let localHeaderOffset = int(readUInt32(data, at: cursor + 42)),
                  let nameRange = checkedRange(
                    start: cursor + 46,
                    length: nameLength,
                    in: data
                  ),
                  let name = String(data: data.subdata(in: nameRange), encoding: .utf8),
                  nameLength > 0 else {
                throw ArtifactMetadataReadingError.malformedArchive
            }

            entries.append(
                APKArchiveEntry(
                    name: name,
                    compressionMethod: compressionMethod,
                    flags: flags,
                    compressedSize: compressedSize,
                    uncompressedSize: uncompressedSize,
                    localHeaderOffset: localHeaderOffset
                )
            )
            cursor += 46 + nameLength + extraLength + commentLength
            guard cursor <= centralRange.upperBound else {
                throw ArtifactMetadataReadingError.malformedArchive
            }
        }
        return entries
    }

    private func inflate(_ compressed: Data, expectedSize: Int) throws -> Data {
        guard expectedSize >= 0,
              expectedSize <= 256 * 1024 * 1024 else {
            throw ArtifactMetadataReadingError.malformedArchive
        }
        var output = Data(count: expectedSize)
        let decoded = output.withUnsafeMutableBytes { outputBuffer in
            compressed.withUnsafeBytes { inputBuffer in
                guard !outputBuffer.isEmpty, !inputBuffer.isEmpty,
                      let outputBaseAddress = outputBuffer.bindMemory(to: UInt8.self).baseAddress,
                      let inputBaseAddress = inputBuffer.bindMemory(to: UInt8.self).baseAddress else {
                    return 0
                }
                return compression_decode_buffer(
                    outputBaseAddress,
                    outputBuffer.count,
                    inputBaseAddress,
                    compressed.count,
                    nil,
                    COMPRESSION_ZLIB
                )
            }
        }
        guard decoded == expectedSize else {
            throw ArtifactMetadataReadingError.malformedArchive
        }
        return output
    }
}

private struct AndroidManifestMetadata: Sendable, Equatable {
    var packageIdentity: String?
    var minAPILevel: Int?
    var maxAPILevel: Int?
    var targetAPILevel: Int?
    var supportedModelFamilies: Set<PortalModelFamily>?
}

private enum AndroidManifestParser {
    static func parse(_ data: Data) throws -> AndroidManifestMetadata {
        let trimmed = data.drop { byte in
            byte == 0 || byte == 9 || byte == 10 || byte == 13 || byte == 32
        }
        if trimmed.first == UInt8(ascii: "<"),
           let text = String(data: Data(trimmed), encoding: .utf8) {
            return parseText(text)
        }
        return try parseBinary(data)
    }

    private static func parseText(_ text: String) -> AndroidManifestMetadata {
        var result = AndroidManifestMetadata(
            packageIdentity: attribute(named: "package", in: tag(named: "manifest", text: text)),
            minAPILevel: nil,
            maxAPILevel: nil,
            targetAPILevel: nil,
            supportedModelFamilies: nil
        )
        let sdkAttributes = tag(named: "uses-sdk", text: text)
        result.minAPILevel = apiLevel(from: attribute(named: "minSdkVersion", in: sdkAttributes))
        result.maxAPILevel = apiLevel(from: attribute(named: "maxSdkVersion", in: sdkAttributes))
        result.targetAPILevel = apiLevel(from: attribute(named: "targetSdkVersion", in: sdkAttributes))

        let modelTags = allTags(named: "meta-data", in: text)
        for modelTag in modelTags {
            guard let name = attribute(named: "name", in: modelTag),
                  name == "com.immortal.portal.modelFamilies",
                  let value = attribute(named: "value", in: modelTag) else {
                continue
            }
            result.supportedModelFamilies = parseModelFamilies(value)
            break
        }
        return result
    }

    private static func parseBinary(_ data: Data) throws -> AndroidManifestMetadata {
        let stringPool = try BinaryStringPool(data: data)
        var result = AndroidManifestMetadata(
            packageIdentity: nil,
            minAPILevel: nil,
            maxAPILevel: nil,
            targetAPILevel: nil,
            supportedModelFamilies: nil
        )

        var offset = 8
        while offset + 8 <= data.count {
            let chunkType = readUInt16(data, at: offset)
            let headerSize = Int(readUInt16(data, at: offset + 2))
            let chunkSize = Int(readUInt32(data, at: offset + 4))
            guard headerSize >= 8,
                  chunkSize >= headerSize,
                  offset + chunkSize <= data.count else {
                throw ArtifactMetadataReadingError.manifestMalformed
            }

            if chunkType == 0x0102 {
                let nameIndex = Int(readUInt32(data, at: offset + 20))
                let elementName = stringPool.string(at: nameIndex)
                let attributes = try parseAttributes(
                    data: data,
                    chunkOffset: offset,
                    headerSize: headerSize,
                    stringPool: stringPool
                )
                if elementName == "manifest" {
                    result.packageIdentity = attributes["package"]?.stringValue
                } else if elementName == "uses-sdk" {
                    result.minAPILevel = attributes["minSdkVersion"].flatMap { apiLevel(from: $0.stringValue) ?? $0.integerValue }
                    result.maxAPILevel = attributes["maxSdkVersion"].flatMap { apiLevel(from: $0.stringValue) ?? $0.integerValue }
                    result.targetAPILevel = attributes["targetSdkVersion"].flatMap { apiLevel(from: $0.stringValue) ?? $0.integerValue }
                } else if elementName == "meta-data",
                          attributes["name"]?.stringValue == "com.immortal.portal.modelFamilies",
                          let value = attributes["value"]?.stringValue {
                    result.supportedModelFamilies = parseModelFamilies(value)
                }
            }
            offset += chunkSize
        }

        guard result.packageIdentity != nil else {
            throw ArtifactMetadataReadingError.manifestMalformed
        }
        return result
    }

    private struct ParsedAttribute {
        let stringValue: String?
        let integerValue: Int?
    }

    private static func parseAttributes(
        data: Data,
        chunkOffset: Int,
        headerSize: Int,
        stringPool: BinaryStringPool
    ) throws -> [String: ParsedAttribute] {
        guard chunkOffset + 36 <= data.count else {
            throw ArtifactMetadataReadingError.manifestMalformed
        }
        let attributeStart = Int(readUInt16(data, at: chunkOffset + 24))
        let attributeSize = Int(readUInt16(data, at: chunkOffset + 26))
        let attributeCount = Int(readUInt16(data, at: chunkOffset + 28))
        guard attributeSize >= 20 else {
            throw ArtifactMetadataReadingError.manifestMalformed
        }

        let attributesOffset = chunkOffset + attributeStart
        var result: [String: ParsedAttribute] = [:]
        for index in 0..<attributeCount {
            let offset = attributesOffset + index * attributeSize
            guard offset + 20 <= data.count else {
                throw ArtifactMetadataReadingError.manifestMalformed
            }
            let nameIndex = Int(readUInt32(data, at: offset + 4))
            let rawValueIndex = readUInt32(data, at: offset + 8)
            let valueType = data[offset + 15]
            let valueData = readUInt32(data, at: offset + 16)
            guard let name = stringPool.string(at: nameIndex) else { continue }

            let rawValue = rawValueIndex == 0xFFFFFFFF
                ? nil
                : stringPool.string(at: Int(rawValueIndex))
            let typedString: String?
            let typedInteger: Int?
            switch valueType {
            case 0x03:
                typedString = stringPool.string(at: Int(valueData)) ?? rawValue
                typedInteger = nil
            case 0x10, 0x11:
                typedString = rawValue
                typedInteger = Int(valueData)
            default:
                typedString = rawValue
                typedInteger = nil
            }
            result[normalizedAttributeName(name)] = ParsedAttribute(
                stringValue: typedString,
                integerValue: typedInteger
            )
        }
        _ = headerSize
        return result
    }

    private static func normalizedAttributeName(_ name: String) -> String {
        if let separator = name.lastIndex(of: ":") {
            return String(name[name.index(after: separator)...])
        }
        return name
    }

    private static func tag(named name: String, text: String) -> String {
        let pattern = "<\(name)(?:\\s[^>]*)?>"
        guard let expression = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return ""
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = expression.firstMatch(in: text, range: range),
              let matchRange = Range(match.range, in: text) else {
            return ""
        }
        return String(text[matchRange])
    }

    private static func allTags(named name: String, in text: String) -> [String] {
        let pattern = "<\(name)(?:\\s[^>]*)?>"
        guard let expression = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return []
        }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return expression.matches(in: text, range: range).compactMap { match in
            guard let matchRange = Range(match.range, in: text) else { return nil }
            return String(text[matchRange])
        }
    }

    private static func attribute(named name: String, in tag: String) -> String? {
        let escapedName = NSRegularExpression.escapedPattern(for: name)
        let pattern = "(?:^|\\s)(?:android:)?\(escapedName)\\s*=\\s*[\\\"]([^\\\"]*)[\\\"]"
        guard let expression = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let match = expression.firstMatch(
                in: tag,
                range: NSRange(tag.startIndex..<tag.endIndex, in: tag)
              ),
              let valueRange = Range(match.range(at: 1), in: tag) else {
            return nil
        }
        return String(tag[valueRange])
    }

    private static func apiLevel(from value: String?) -> Int? {
        guard let value else { return nil }
        if let integer = Int(value) { return integer }
        let names: [String: Int] = [
            "base": 1, "gingerbread": 9, "honeycomb": 11, "ice_cream_sandwich": 14,
            "jelly_bean": 16, "kitkat": 19, "lollipop": 21, "marshmallow": 23,
            "nougat": 24, "oreo": 26, "pie": 28, "q": 29, "red_velvet_cake": 30
        ]
        return names[value.lowercased()]
    }

    private static func parseModelFamilies(_ value: String) -> Set<PortalModelFamily> {
        Set(value.split { $0 == "," || $0 == " " || $0 == ";" }.compactMap { token in
            switch token.lowercased().replacingOccurrences(of: "-", with: "") {
            case "portal2018", "portal": return .portal2018
            case "portalplus": return .portalPlus
            case "portalplusfirstgeneration", "portalplusgen1": return .portalPlusFirstGeneration
            case "portalgo": return .portalGo
            case "portalmini": return .portalMini
            case "portalgen2", "portal2": return .portalGen2
            case "portaltv": return .portalTV
            default: return nil
            }
        })
    }
}

private struct BinaryStringPool {
    let strings: [String]

    init(data: Data) throws {
        guard data.count >= 28,
              readUInt16(data, at: 0) == 0x0003 else {
            throw ArtifactMetadataReadingError.manifestMalformed
        }

        let offset = 8
        guard offset + 8 <= data.count,
              readUInt16(data, at: offset) == 0x0001 else {
            throw ArtifactMetadataReadingError.manifestMalformed
        }
        let headerSize = Int(readUInt16(data, at: offset + 2))
        let chunkSize = Int(readUInt32(data, at: offset + 4))
        let stringCount = Int(readUInt32(data, at: offset + 8))
        let flags = readUInt32(data, at: offset + 16)
        let stringsStart = Int(readUInt32(data, at: offset + 20))
        guard headerSize >= 28,
              chunkSize >= headerSize,
              offset + chunkSize <= data.count,
              stringCount >= 0,
              stringCount <= 1_000_000,
              offset + headerSize + stringCount * 4 <= data.count else {
            throw ArtifactMetadataReadingError.manifestMalformed
        }

        let offsetsStart = offset + headerSize
        let stringDataStart = offset + stringsStart
        let isUTF8 = flags & 0x00000100 != 0
        var parsed: [String] = []
        parsed.reserveCapacity(stringCount)
        for index in 0..<stringCount {
            let stringOffset = Int(readUInt32(data, at: offsetsStart + index * 4))
            let start = stringDataStart + stringOffset
            guard start >= 0, start < data.count else {
                throw ArtifactMetadataReadingError.manifestMalformed
            }
            parsed.append(try Self.decodeString(data: data, at: start, utf8: isUTF8))
        }
        strings = parsed
    }

    func string(at index: Int) -> String? {
        guard index >= 0, index < strings.count else { return nil }
        return strings[index]
    }

    private static func decodeString(data: Data, at offset: Int, utf8: Bool) throws -> String {
        if utf8 {
            let (_, afterUTF16Length) = try readLength8(data, at: offset)
            let (byteLength, afterByteLength) = try readLength8(data, at: afterUTF16Length)
            let range = try boundedRange(start: afterByteLength, length: byteLength, in: data)
            guard let value = String(data: data.subdata(in: range), encoding: .utf8) else {
                throw ArtifactMetadataReadingError.manifestMalformed
            }
            return value
        }

        let (length, afterLength) = try readLength16(data, at: offset)
        let range = try boundedRange(start: afterLength, length: length * 2, in: data)
        guard let value = String(data: data.subdata(in: range), encoding: .utf16LittleEndian) else {
            throw ArtifactMetadataReadingError.manifestMalformed
        }
        return value
    }

    private static func readLength8(_ data: Data, at offset: Int) throws -> (Int, Int) {
        guard offset < data.count else { throw ArtifactMetadataReadingError.manifestMalformed }
        let first = data[offset]
        if first & 0x80 == 0 {
            return (Int(first), offset + 1)
        }
        guard offset + 1 < data.count else { throw ArtifactMetadataReadingError.manifestMalformed }
        return (Int(first & 0x7F) << 8 | Int(data[offset + 1]), offset + 2)
    }

    private static func readLength16(_ data: Data, at offset: Int) throws -> (Int, Int) {
        guard offset + 1 < data.count else { throw ArtifactMetadataReadingError.manifestMalformed }
        let first = readUInt16(data, at: offset)
        if first & 0x8000 == 0 {
            return (Int(first), offset + 2)
        }
        guard offset + 3 < data.count else { throw ArtifactMetadataReadingError.manifestMalformed }
        let second = readUInt16(data, at: offset + 2)
        return (Int(first & 0x7FFF) << 16 | Int(second), offset + 4)
    }
}

private enum APKCertificateExtractor {
    static func sha256Digests(in data: Data) -> Set<String> {
        var result = Set<String>()
        if let certificate = SecCertificateCreateWithData(nil, data as CFData) {
            result.insert(digest(certificateData: SecCertificateCopyData(certificate) as Data))
        }

        // APK v1 signature entries are PKCS#7 containers. Scan bounded DER
        // sequences and ask Security to validate certificate candidates; this
        // avoids depending on a command-line certificate parser.
        guard data.count >= 128 else { return result }
        for offset in 0..<(data.count - 1) where data[offset] == 0x30 {
            guard let totalLength = derSequenceLength(data, at: offset),
                  totalLength >= 128,
                  offset + totalLength <= data.count else {
                continue
            }
            let candidate = data.subdata(in: offset..<(offset + totalLength))
            guard let certificate = SecCertificateCreateWithData(nil, candidate as CFData) else {
                continue
            }
            let certificateData = SecCertificateCopyData(certificate) as Data
            result.insert(digest(certificateData: certificateData))
        }
        return result
    }

    private static func derSequenceLength(_ data: Data, at offset: Int) -> Int? {
        guard offset + 2 <= data.count, data[offset] == 0x30 else { return nil }
        let lengthByte = data[offset + 1]
        if lengthByte & 0x80 == 0 {
            return 2 + Int(lengthByte)
        }
        let count = Int(lengthByte & 0x7F)
        guard count > 0, count <= 4, offset + 2 + count <= data.count else { return nil }
        var length = 0
        for index in 0..<count {
            length = (length << 8) | Int(data[offset + 2 + index])
        }
        return 2 + count + length
    }

    private static func digest(certificateData: Data) -> String {
        SHA256.hash(data: certificateData)
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

private func readUInt16(_ data: Data, at offset: Int) -> UInt16 {
    guard offset >= 0, offset + 1 < data.count else { return 0 }
    return UInt16(data[offset]) | UInt16(data[offset + 1]) << 8
}

private func readUInt32(_ data: Data, at offset: Int) -> UInt32 {
    guard offset >= 0, offset + 3 < data.count else { return 0 }
    return UInt32(data[offset])
        | UInt32(data[offset + 1]) << 8
        | UInt32(data[offset + 2]) << 16
        | UInt32(data[offset + 3]) << 24
}

private func uint16(_ data: Data, at offset: Int) -> UInt16? {
    guard offset >= 0, offset + 1 < data.count else { return nil }
    return readUInt16(data, at: offset)
}

private func int<T: BinaryInteger>(_ value: T) -> Int? {
    guard value <= T(Int.max) else { return nil }
    return Int(value)
}

private func checkedRange(start: Int, length: Int, in data: Data) -> Range<Int>? {
    guard start >= 0, length >= 0,
          start <= data.count,
          length <= data.count - start else { return nil }
    return start..<(start + length)
}

private func boundedRange(start: Int, length: Int, in data: Data) throws -> Range<Int> {
    guard let range = checkedRange(start: start, length: length, in: data) else {
        throw ArtifactMetadataReadingError.manifestMalformed
    }
    return range
}
