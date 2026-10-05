/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// The non-secret device facts that affect whether a local artifact may be
/// installed. It is intentionally separate from the ADB process boundary.
struct ArtifactCompatibilityTarget: Codable, Sendable, Equatable, Hashable {
    let apiLevel: Int?
    let modelFamily: PortalModelFamily?
    let rawModel: String?
    let device: String?

    init(
        apiLevel: Int? = nil,
        modelFamily: PortalModelFamily? = nil,
        rawModel: String? = nil,
        device: String? = nil
    ) {
        self.apiLevel = apiLevel
        self.modelFamily = modelFamily
        self.rawModel = rawModel
        self.device = device
    }

    init(snapshot: ADBDeviceSnapshot) {
        let family: PortalModelFamily?
        if let model = snapshot.model {
            let classified = PortalInfoClassifier.modelFamily(for: model)
            family = classified == .unknown ? nil : classified
        } else {
            family = nil
        }

        self.init(
            apiLevel: snapshot.apiLevel,
            modelFamily: family,
            rawModel: snapshot.model,
            device: nil
        )
    }
}

/// A typed request for local artifact verification. The request contains only
/// operator-selected local-file metadata and non-secret compatibility facts;
/// it has no URL for a remote artifact and no process or credential payload.
struct LocalArtifactVerificationRequest: ArtifactVerificationRequest, Codable, Sendable, Equatable, Hashable {
    let artifact: LocalArtifact
    let expectedSHA256Digest: String?
    let target: ArtifactCompatibilityTarget?
    let supportedAPILevels: Set<Int>
    let supportedABIs: Set<String>
    let supportedModelFamilies: Set<PortalModelFamily>

    init(
        artifact: LocalArtifact,
        expectedSHA256Digest: String? = nil,
        target: ArtifactCompatibilityTarget? = nil,
        supportedAPILevels: Set<Int> = [28, 29],
        supportedABIs: Set<String> = ["arm64-v8a"],
        supportedModelFamilies: Set<PortalModelFamily> = Self.defaultSupportedModelFamilies
    ) {
        self.artifact = artifact
        self.expectedSHA256Digest = expectedSHA256Digest?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.target = target
        self.supportedAPILevels = supportedAPILevels
        self.supportedABIs = supportedABIs
        self.supportedModelFamilies = supportedModelFamilies
    }

    init(
        artifact: LocalArtifact,
        snapshot: ADBDeviceSnapshot,
        expectedSHA256Digest: String? = nil,
        supportedAPILevels: Set<Int> = [28, 29],
        supportedABIs: Set<String> = ["arm64-v8a"],
        supportedModelFamilies: Set<PortalModelFamily> = Self.defaultSupportedModelFamilies
    ) {
        self.init(
            artifact: artifact,
            expectedSHA256Digest: expectedSHA256Digest,
            target: ArtifactCompatibilityTarget(snapshot: snapshot),
            supportedAPILevels: supportedAPILevels,
            supportedABIs: supportedABIs,
            supportedModelFamilies: supportedModelFamilies
        )
    }

    var localArtifact: LocalArtifact { artifact }
    var expectedDigest: String? { expectedSHA256Digest }
    var targetAPILevel: Int? { target?.apiLevel }
    var targetModelFamily: PortalModelFamily? { target?.modelFamily }

    static let defaultSupportedModelFamilies: Set<PortalModelFamily> = [
        .portal2018,
        .portalPlus,
        .portalPlusFirstGeneration,
        .portalGo,
        .portalMini,
        .portalGen2,
        .portalTV
    ]
}

extension ArtifactVerificationSummary {
    /// The provisioning layer must inspect this value before creating any
    /// install request. A failed dimension is never implicitly bypassed.
    var installationAllowed: Bool { passed }
    var canInstall: Bool { passed }

    var failedChecks: [ArtifactVerificationFailure] {
        [
            readableRegularFile,
            packageIdentity,
            signature,
            digest,
            apiCompatibility,
            abiCompatibility,
            targetModelCompatibility
        ].compactMap(\.failure)
    }

    var blockingFailure: ArtifactVerificationFailure? {
        failedChecks.first
    }
}
