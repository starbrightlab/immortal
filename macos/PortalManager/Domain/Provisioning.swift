/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

// MARK: - ADB preflight

/// The authorization result observed during the local ADB preflight. This is
/// deliberately not a process-output string: an adapter must classify ADB
/// output before it crosses into the domain.
enum ADBAuthorizationState: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case authorized
    case unauthorized
    case unavailable
    case unknown
}

typealias ADBAuthorizationStatus = ADBAuthorizationState

/// Whether the selected USB/ADB device is presently reachable. A device can
/// be known to ADB but still be disconnected by the time an operation starts.
enum ADBConnectionState: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case connected
    case disconnected
    case unavailable
    case unknown
}

typealias ADBConnectionStatus = ADBConnectionState

/// Compatibility of the already installed Immortal application, independent
/// of the hardware compatibility assessment used by a full install.
enum ImmortalCompatibility: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case compatible
    case incompatible
    case unknown
}

/// A compact typed projection of installed-package state for adapters that
/// prefer a state value over separate booleans.
enum ImmortalInstallationState: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case notInstalled
    case installedCompatible
    case installedIncompatible
    case unknown
}

/// Compatibility of the attached device profile for a local provisioning
/// operation. Unknown is fail-closed until the adapter has classified the
/// model/API profile.
enum ADBDeviceCompatibility: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case compatible
    case incompatible
    case unknown
}

typealias DeviceCompatibility = ADBDeviceCompatibility

/// The Fleet Agent state observed before a provisioning operation. The value
/// is a non-secret fact used for UI gating only; it is not an authorization
/// state and never replaces bearer verification.
enum FleetAgentState: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case enabled
    case disabled
    case unavailable
    case unknown
}

typealias FleetAgentStatus = FleetAgentState

/// Sanitized, non-secret facts collected before either USB flow is offered.
/// Raw ADB output, command arguments, local paths, and bearer values are not
/// representable by this type.
struct ADBDeviceSnapshot: Codable, Sendable, Equatable, Hashable, ADBResult {
    var authorization: ADBAuthorizationState
    var connection: ADBConnectionState
    var serial: String?
    var model: String?
    var apiLevel: Int?
    var installedImmortal: Bool
    var immortalCompatible: Bool
    var fleetAgent: FleetAgentState
    var deviceCompatibility: ADBDeviceCompatibility
    var immortalVersion: AppVersion?
    var installedPackageIdentity: String?

    init(
        authorization: ADBAuthorizationState,
        connection: ADBConnectionState,
        serial: String? = nil,
        model: String? = nil,
        apiLevel: Int? = nil,
        installedImmortal: Bool = false,
        immortalCompatible: Bool = false,
        fleetAgent: FleetAgentState = .unknown,
        deviceCompatibility: ADBDeviceCompatibility = .unknown,
        immortalVersion: AppVersion? = nil,
        installedPackageIdentity: String? = nil
    ) {
        self.authorization = authorization
        self.connection = connection
        self.serial = provisioningSafeText(serial)
        self.model = provisioningSafeText(model)
        self.apiLevel = apiLevel.flatMap { $0 > 0 ? $0 : nil }
        self.installedImmortal = installedImmortal
        self.immortalCompatible = installedImmortal && immortalCompatible
        self.fleetAgent = fleetAgent
        self.deviceCompatibility = deviceCompatibility
        self.immortalVersion = immortalVersion
        self.installedPackageIdentity = provisioningSafeText(installedPackageIdentity)
    }

    init(
        authorization: ADBAuthorizationState,
        connection: ADBConnectionState,
        serial: String? = nil,
        model: String? = nil,
        apiLevel: Int? = nil,
        installedImmortal: ImmortalInstallationState,
        fleetAgent: FleetAgentState = .unknown,
        deviceCompatibility: ADBDeviceCompatibility = .unknown,
        immortalVersion: AppVersion? = nil,
        installedPackageIdentity: String? = nil
    ) {
        self.init(
            authorization: authorization,
            connection: connection,
            serial: serial,
            model: model,
            apiLevel: apiLevel,
            installedImmortal: installedImmortal != .notInstalled,
            immortalCompatible: installedImmortal == .installedCompatible,
            fleetAgent: fleetAgent,
            deviceCompatibility: deviceCompatibility,
            immortalVersion: immortalVersion,
            installedPackageIdentity: installedPackageIdentity
        )
    }

    /// Compatibility spelling for adapters that use `adbAuthorization`.
    var adbAuthorization: ADBAuthorizationState { authorization }

    /// Compatibility spelling for adapters that use `adbConnection`.
    var adbConnection: ADBConnectionState { connection }

    var deviceSerial: String? { serial }
    var deviceModel: String? { model }
    var androidAPILevel: Int? { apiLevel }
    var installedImmortalApp: Bool { installedImmortal }
    var installedImmortalCompatibility: ImmortalCompatibility {
        guard installedImmortal else { return .unknown }
        return immortalCompatible ? .compatible : .incompatible
    }
    var installedImmortalState: ImmortalInstallationState {
        guard installedImmortal else { return .notInstalled }
        return immortalCompatible ? .installedCompatible : .installedIncompatible
    }
    var isImmortalInstalled: Bool { installedImmortal }
    var isImmortalCompatible: Bool { installedImmortal && immortalCompatible }
    var currentFleetAgent: FleetAgentState { fleetAgent }
    var fleetAgentState: FleetAgentState { fleetAgent }

    var isAuthorized: Bool { authorization == .authorized }
    var isConnected: Bool { connection == .connected }
    var hasRequiredIdentity: Bool {
        nonEmpty(serial) && nonEmpty(model) && apiLevel != nil
    }

    /// A conservative target check used by both mode gates. API 28/API 29 are
    /// the known supported families; an explicit adapter classification can
    /// also admit a profile whose model label is newer but has been verified.
    var isCompatibleTarget: Bool {
        switch deviceCompatibility {
        case .compatible:
            return true
        case .incompatible:
            return false
        case .unknown:
            return [28, 29].contains(apiLevel ?? -1) && nonEmpty(model)
        }
    }

    var canOfferEnablementRecovery: Bool {
        validation(for: .fleetAgentEnablementRecovery).isAllowed
    }

    var canOfferFullUSBProvisioning: Bool {
        // A full flow cannot be offered without a selected local artifact.
        false
    }

    func canOfferFullUSBProvisioning(with artifact: LocalArtifact) -> Bool {
        validation(for: .fullUSBProvisioning, artifact: artifact).isAllowed
    }

    func validation(
        for mode: ProvisioningMode,
        artifact: LocalArtifact? = nil
    ) -> ProvisioningPlanValidation {
        mode.validate(snapshot: self, artifact: artifact)
    }

    private func nonEmpty(_ value: String?) -> Bool {
        guard let value else { return false }
        return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

// MARK: - Safe local selections

/// A local ADB/platform-tools selection. The security-scoped URL is operation
/// scoped and intentionally omitted from Codable output; only an opaque
/// selection ID and a safe display name can cross a persistence/UI boundary.
struct LocalExecutableReference: Codable, Sendable, Equatable, Hashable {
    let securityScopedURL: URL
    let displayName: String
    let selectionID: UUID

    init(
        securityScopedURL: URL,
        displayName: String? = nil,
        selectionID: UUID = UUID()
    ) {
        self.securityScopedURL = securityScopedURL
        self.displayName = provisioningSafeDisplayName(
            displayName ?? securityScopedURL.lastPathComponent,
            fallback: "ADB executable"
        )
        self.selectionID = selectionID
    }

    init(
        url: URL,
        displayName: String? = nil,
        selectionID: UUID = UUID()
    ) {
        self.init(
            securityScopedURL: url,
            displayName: displayName,
            selectionID: selectionID
        )
    }

    /// Rehydrates a UI/persistence-safe reference without inventing a local
    /// path. The decoded value is not executable until the active operation
    /// supplies the original security-scoped selection again.
    init(displayName: String, selectionID: UUID) {
        self.securityScopedURL = URL(fileURLWithPath: "/")
        self.displayName = provisioningSafeDisplayName(
            displayName,
            fallback: "ADB executable"
        )
        self.selectionID = selectionID
    }

    var hasSecurityScopedSelection: Bool {
        securityScopedURL.isFileURL
            && securityScopedURL.path != "/"
            && !securityScopedURL.path.isEmpty
    }

    var isSafeSelection: Bool {
        hasSecurityScopedSelection && !displayName.isEmpty
    }

    private enum CodingKeys: String, CodingKey {
        case displayName
        case selectionID
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let displayName = try container.decode(String.self, forKey: .displayName)
        let selectionID = try container.decode(UUID.self, forKey: .selectionID)
        self.init(displayName: displayName, selectionID: selectionID)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(displayName, forKey: .displayName)
        try container.encode(selectionID, forKey: .selectionID)
    }
}

/// The signature policy selected by the operator/product profile for a local
/// artifact. It contains no certificate bytes or remote URL.
enum SignaturePolicy: Codable, Sendable, Equatable, Hashable {
    case approvedPackageSignature
    case certificateSHA256(String)
    case named(String)

    static var releaseSigned: Self { .approvedPackageSignature }
    static var trusted: Self { .approvedPackageSignature }
    static var release: Self { .approvedPackageSignature }

    var identifier: String {
        switch self {
        case .approvedPackageSignature:
            return "approved-package-signature"
        case .certificateSHA256(let digest):
            return "certificate-sha256:\(normalizedDigest(digest) ?? "invalid")"
        case .named(let value):
            return provisioningSafeDisplayName(value, fallback: "named-signature-policy")
        }
    }

    var isSafe: Bool {
        switch self {
        case .approvedPackageSignature:
            return true
        case .certificateSHA256(let digest):
            return isSHA256Digest(digest)
        case .named(let value):
            return provisioningSafeText(value) != nil
        }
    }
}

/// An operator-selected local APK/provisioning input. The file URL is retained
/// only for the active security-scoped operation and is not encoded.
struct LocalArtifact: Codable, Sendable, Equatable, Hashable, ArtifactVerificationRequest {
    let securityScopedURL: URL
    let displayName: String
    let expectedPackageIdentity: String
    let expectedSignaturePolicy: SignaturePolicy
    let selectionID: UUID

    init(
        securityScopedURL: URL,
        displayName: String? = nil,
        expectedPackageIdentity: String,
        expectedSignaturePolicy: SignaturePolicy,
        selectionID: UUID = UUID()
    ) {
        self.securityScopedURL = securityScopedURL
        self.displayName = provisioningSafeDisplayName(
            displayName ?? securityScopedURL.lastPathComponent,
            fallback: "Local artifact"
        )
        self.expectedPackageIdentity = provisioningSafeText(expectedPackageIdentity) ?? ""
        self.expectedSignaturePolicy = expectedSignaturePolicy
        self.selectionID = selectionID
    }

    init(
        url: URL,
        displayName: String? = nil,
        expectedPackageIdentity: String,
        expectedSignaturePolicy: SignaturePolicy,
        selectionID: UUID = UUID()
    ) {
        self.init(
            securityScopedURL: url,
            displayName: displayName,
            expectedPackageIdentity: expectedPackageIdentity,
            expectedSignaturePolicy: expectedSignaturePolicy,
            selectionID: selectionID
        )
    }

    init(
        displayName: String,
        expectedPackageIdentity: String,
        expectedSignaturePolicy: SignaturePolicy,
        selectionID: UUID
    ) {
        self.securityScopedURL = URL(fileURLWithPath: "/")
        self.displayName = provisioningSafeDisplayName(
            displayName,
            fallback: "Local artifact"
        )
        self.expectedPackageIdentity = provisioningSafeText(expectedPackageIdentity) ?? ""
        self.expectedSignaturePolicy = expectedSignaturePolicy
        self.selectionID = selectionID
    }

    var hasSecurityScopedSelection: Bool {
        securityScopedURL.isFileURL
            && securityScopedURL.path != "/"
            && !securityScopedURL.path.isEmpty
    }

    var isSafeSelection: Bool {
        hasSecurityScopedSelection
            && !displayName.isEmpty
            && !expectedPackageIdentity.isEmpty
            && expectedSignaturePolicy.isSafe
    }

    private enum CodingKeys: String, CodingKey {
        case displayName
        case expectedPackageIdentity
        case expectedSignaturePolicy
        case selectionID
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            displayName: try container.decode(String.self, forKey: .displayName),
            expectedPackageIdentity: try container.decode(String.self, forKey: .expectedPackageIdentity),
            expectedSignaturePolicy: try container.decode(SignaturePolicy.self, forKey: .expectedSignaturePolicy),
            selectionID: try container.decode(UUID.self, forKey: .selectionID)
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(displayName, forKey: .displayName)
        try container.encode(expectedPackageIdentity, forKey: .expectedPackageIdentity)
        try container.encode(expectedSignaturePolicy, forKey: .expectedSignaturePolicy)
        try container.encode(selectionID, forKey: .selectionID)
    }
}

/// One typed artifact verification dimension. It carries a stable failure code,
/// never a verifier transcript, path, signature blob, or response body.
enum ArtifactCheck: Codable, Sendable, Equatable, Hashable {
    case notEvaluated
    case passed
    case failed(ArtifactVerificationFailure)

    var isPassed: Bool {
        if case .passed = self { return true }
        return false
    }

    var failure: ArtifactVerificationFailure? {
        guard case .failed(let failure) = self else { return nil }
        return failure
    }
}

/// Safe artifact verification output suitable for progress/UI state. A SHA-256
/// digest is an integrity identifier, not the artifact bytes; all other output
/// is represented by typed checks.
struct ArtifactVerificationSummary: Codable, Sendable, Equatable, Hashable, ArtifactVerificationResult {
    var readableRegularFile: ArtifactCheck
    var packageIdentity: ArtifactCheck
    var signature: ArtifactCheck
    var sha256Digest: String?
    var digest: ArtifactCheck
    var apiCompatibility: ArtifactCheck
    var abiCompatibility: ArtifactCheck
    var targetModelCompatibility: ArtifactCheck

    init(
        readableRegularFile: ArtifactCheck,
        packageIdentity: ArtifactCheck,
        signature: ArtifactCheck,
        sha256Digest: String?,
        apiCompatibility: ArtifactCheck,
        abiCompatibility: ArtifactCheck,
        targetModelCompatibility: ArtifactCheck,
        digest: ArtifactCheck? = nil
    ) {
        self.readableRegularFile = readableRegularFile
        self.packageIdentity = packageIdentity
        self.signature = signature
        self.sha256Digest = normalizedDigest(sha256Digest)
        self.digest = digest ?? (self.sha256Digest == nil ? .notEvaluated : .passed)
        self.apiCompatibility = apiCompatibility
        self.abiCompatibility = abiCompatibility
        self.targetModelCompatibility = targetModelCompatibility
    }

    var passed: Bool {
        readableRegularFile.isPassed
            && packageIdentity.isPassed
            && signature.isPassed
            && digest.isPassed
            && sha256Digest != nil
            && apiCompatibility.isPassed
            && abiCompatibility.isPassed
            && targetModelCompatibility.isPassed
    }

    var isPassed: Bool { passed }
}

typealias ArtifactVerificationResultSnapshot = ArtifactVerificationSummary

// MARK: - Provisioning failures and gates

enum ProvisioningFailureCode: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case invalidPlan
    case deviceUnauthorized
    case deviceDisconnected
    case deviceUnavailable
    case missingSerial
    case missingModel
    case missingAPILevel
    case unsupportedDevice
    case immortalNotInstalled
    case immortalIncompatible
    case artifactRequired
    case artifactNotAllowed
    case artifactInvalid
    case artifactVerificationFailed
    case setupFailed
    case installationFailed
    case writeProvisionFileFailed
    case relaunchFailed
    case readAgentManifestFailed
    case invalidAgentManifest
    case lanAdmissionFailed
    case bearerVerificationFailed
    case keychainWriteFailed
    case registryCommitFailed
    case timedOut
    case cancelled

    var sanitizedMessage: String {
        switch self {
        case .invalidPlan:
            return "The provisioning prerequisites are incomplete."
        case .deviceUnauthorized:
            return "ADB authorization is required for this device."
        case .deviceDisconnected:
            return "The USB device is disconnected."
        case .deviceUnavailable:
            return "The local ADB device is unavailable."
        case .missingSerial:
            return "The ADB device serial is unavailable."
        case .missingModel:
            return "The ADB device model is unavailable."
        case .missingAPILevel:
            return "The Android API level is unavailable."
        case .unsupportedDevice:
            return "This device profile is not supported for local provisioning."
        case .immortalNotInstalled:
            return "Fleet Agent recovery requires an installed Immortal application."
        case .immortalIncompatible:
            return "The installed Immortal application is not compatible."
        case .artifactRequired:
            return "Full USB Provisioning requires a selected local artifact."
        case .artifactNotAllowed:
            return "Fleet Agent recovery does not accept or install a local artifact."
        case .artifactInvalid:
            return "The selected local artifact is not a usable security-scoped file."
        case .artifactVerificationFailed:
            return "The local artifact failed verification."
        case .setupFailed:
            return "Established device setup did not complete."
        case .installationFailed:
            return "Immortal installation did not complete."
        case .writeProvisionFileFailed:
            return "The Fleet Agent handoff file could not be written."
        case .relaunchFailed:
            return "Immortal could not be relaunched for Fleet Agent recovery."
        case .readAgentManifestFailed:
            return "The Fleet Agent handoff manifest could not be read."
        case .invalidAgentManifest:
            return "The Fleet Agent handoff manifest was invalid."
        case .lanAdmissionFailed:
            return "The recovered Portal address was not admitted to the LAN."
        case .bearerVerificationFailed:
            return "The recovered Fleet credential could not verify the Portal."
        case .keychainWriteFailed:
            return "The recovered Fleet credential could not be stored securely."
        case .registryCommitFailed:
            return "The verified Portal state could not be saved."
        case .timedOut:
            return "The provisioning step timed out."
        case .cancelled:
            return "The provisioning operation was cancelled."
        }
    }

    var recoveryAction: RecoveryAction {
        switch self {
        case .deviceUnauthorized, .deviceDisconnected, .deviceUnavailable,
             .unsupportedDevice, .setupFailed, .installationFailed,
             .writeProvisionFileFailed, .relaunchFailed, .readAgentManifestFailed,
             .lanAdmissionFailed, .registryCommitFailed, .timedOut:
            return .retryProvisioning
        case .missingSerial, .missingModel, .missingAPILevel, .invalidPlan:
            return .reviewInput
        case .immortalNotInstalled, .immortalIncompatible:
            return .retryProvisioning
        case .artifactRequired, .artifactInvalid, .artifactVerificationFailed:
            return .selectLocalArtifact
        case .artifactNotAllowed:
            return .reviewInput
        case .invalidAgentManifest, .bearerVerificationFailed, .keychainWriteFailed:
            return .reauthenticate
        case .cancelled:
            return .none
        }
    }

    var retryPolicy: RetryPolicy {
        switch self {
        case .invalidPlan, .missingSerial, .missingModel, .missingAPILevel,
             .unsupportedDevice, .immortalNotInstalled, .immortalIncompatible,
             .artifactRequired, .artifactNotAllowed, .artifactInvalid,
             .artifactVerificationFailed, .keychainWriteFailed:
            return .afterCorrection
        case .cancelled:
            return .never
        case .deviceUnauthorized, .deviceDisconnected, .deviceUnavailable,
             .setupFailed, .installationFailed, .writeProvisionFileFailed,
             .relaunchFailed, .readAgentManifestFailed, .invalidAgentManifest,
             .lanAdmissionFailed, .bearerVerificationFailed, .registryCommitFailed,
             .timedOut:
            return .immediate
        }
    }
}

typealias ProvisioningFailureKind = ProvisioningFailureCode

/// A typed diagnostic with no free-form process/ADB detail. The stable code is
/// enough for a UI and retry policy to identify the failed operation safely.
struct ProvisioningDiagnostic: Codable, Sendable, Equatable, Hashable {
    let step: ProvisioningStepID
    let code: ProvisioningFailureCode
    let summary: String
    let recoveryAction: RecoveryAction
    let retryPolicy: RetryPolicy

    init(step: ProvisioningStepID, code: ProvisioningFailureCode) {
        self.step = step
        self.code = code
        self.summary = code.sanitizedMessage
        self.recoveryAction = code.recoveryAction
        self.retryPolicy = code.retryPolicy
    }

    var sanitizedMessage: String { summary }
}

typealias SanitizedProvisioningDiagnostic = ProvisioningDiagnostic

/// A failure that can cross the provisioning/application boundary. It carries
/// only typed step/code metadata and a stable sanitized presentation.
struct ProvisioningFailure: Error, Codable, Sendable, Equatable, Hashable, LocalizedError {
    let diagnostic: ProvisioningDiagnostic

    init(step: ProvisioningStepID, code: ProvisioningFailureCode) {
        diagnostic = ProvisioningDiagnostic(step: step, code: code)
    }

    init(_ diagnostic: ProvisioningDiagnostic) {
        self.diagnostic = diagnostic
    }

    var step: ProvisioningStepID { diagnostic.step }
    var code: ProvisioningFailureCode { diagnostic.code }
    var sanitizedMessage: String { diagnostic.sanitizedMessage }
    var recoveryAction: RecoveryAction { diagnostic.recoveryAction }
    var retryPolicy: RetryPolicy { diagnostic.retryPolicy }
    var errorDescription: String? { sanitizedMessage }

    /// Bridges to the existing manager error without forwarding any raw
    /// process/ADB text.
    var managerError: ManagerError {
        .provisioning(step: step, reason: sanitizedMessage)
    }
}

typealias SanitizedProvisioningFailure = ProvisioningFailure

enum ProvisioningPlanValidation: Codable, Sendable, Equatable, Hashable {
    case allowed
    case blocked(ProvisioningFailure)

    var isAllowed: Bool {
        if case .allowed = self { return true }
        return false
    }

    var failure: ProvisioningFailure? {
        guard case .blocked(let failure) = self else { return nil }
        return failure
    }
}

typealias ProvisioningGateResult = ProvisioningPlanValidation

// MARK: - Plans and mode sequencing

extension ProvisioningMode: CaseIterable {
    static let allCases: [ProvisioningMode] = [
        .fleetAgentEnablementRecovery,
        .fullUSBProvisioning
    ]

    var requiresLocalArtifact: Bool {
        self == .fullUSBProvisioning
    }

    var isEnablementRecovery: Bool {
        self == .fleetAgentEnablementRecovery
    }

    var setupSteps: [ProvisioningStepID] {
        switch self {
        case .fleetAgentEnablementRecovery:
            return []
        case .fullUSBProvisioning:
            return [.artifactVerification, .deviceSetup, .installation]
        }
    }

    /// The common final phase for both modes. Full USB Provisioning reports
    /// this phase with the enablement/recovery mode after setup/installation.
    var enablementRecoverySteps: [ProvisioningStepID] {
        [
            .writeProvisionFile,
            .relaunchImmortal,
            .readAgentManifest,
            .bearerVerification,
            .complete
        ]
    }

    var expectedSteps: [ProvisioningStepID] {
        [.preflight] + setupSteps + enablementRecoverySteps
    }

    func validate(
        snapshot: ADBDeviceSnapshot,
        artifact: LocalArtifact? = nil
    ) -> ProvisioningPlanValidation {
        if self == .fleetAgentEnablementRecovery, artifact != nil {
            return .blocked(
                ProvisioningFailure(
                    step: .preflight,
                    code: .artifactNotAllowed
                )
            )
        }

        if self == .fullUSBProvisioning, artifact == nil {
            return .blocked(
                ProvisioningFailure(
                    step: .artifactVerification,
                    code: .artifactRequired
                )
            )
        }

        guard snapshot.authorization == .authorized else {
            let code: ProvisioningFailureCode = snapshot.authorization == .unauthorized
                ? .deviceUnauthorized
                : .deviceUnavailable
            return .blocked(ProvisioningFailure(step: .preflight, code: code))
        }
        guard snapshot.connection == .connected else {
            let code: ProvisioningFailureCode = snapshot.connection == .disconnected
                ? .deviceDisconnected
                : .deviceUnavailable
            return .blocked(ProvisioningFailure(step: .preflight, code: code))
        }
        guard snapshot.serial?.isEmpty == false else {
            return .blocked(ProvisioningFailure(step: .preflight, code: .missingSerial))
        }
        guard snapshot.model?.isEmpty == false else {
            return .blocked(ProvisioningFailure(step: .preflight, code: .missingModel))
        }
        guard snapshot.apiLevel != nil else {
            return .blocked(ProvisioningFailure(step: .preflight, code: .missingAPILevel))
        }
        guard snapshot.isCompatibleTarget else {
            return .blocked(ProvisioningFailure(step: .preflight, code: .unsupportedDevice))
        }

        if self == .fleetAgentEnablementRecovery {
            guard snapshot.installedImmortal else {
                return .blocked(ProvisioningFailure(step: .preflight, code: .immortalNotInstalled))
            }
            guard snapshot.immortalCompatible else {
                return .blocked(ProvisioningFailure(step: .preflight, code: .immortalIncompatible))
            }
            return .allowed
        }

        guard let artifact else {
            // The nil case is handled above; this keeps the guard exhaustive
            // if the mode implementation is changed later.
            return .blocked(
                ProvisioningFailure(step: .artifactVerification, code: .artifactRequired)
            )
        }
        guard artifact.isSafeSelection else {
            return .blocked(
                ProvisioningFailure(step: .artifactVerification, code: .artifactInvalid)
            )
        }
        return .allowed
    }

    func validation(
        snapshot: ADBDeviceSnapshot,
        artifact: LocalArtifact? = nil
    ) -> ProvisioningPlanValidation {
        validate(snapshot: snapshot, artifact: artifact)
    }
}

struct EnablementRecoveryPlan: Codable, Sendable, Equatable, Hashable {
    let deviceSerial: String
    let adbExecutable: LocalExecutableReference
    let friendlyName: String?
    let preflightSnapshot: ADBDeviceSnapshot?

    init(
        deviceSerial: String,
        adbExecutable: LocalExecutableReference,
        friendlyName: String? = nil,
        preflightSnapshot: ADBDeviceSnapshot? = nil
    ) {
        self.deviceSerial = provisioningSafeText(deviceSerial) ?? ""
        self.adbExecutable = adbExecutable
        self.friendlyName = friendlyName.flatMap { provisioningSafeText($0) }
        self.preflightSnapshot = preflightSnapshot
    }

    init(
        validating snapshot: ADBDeviceSnapshot,
        adbExecutable: LocalExecutableReference,
        friendlyName: String? = nil
    ) throws {
        guard case .allowed = ProvisioningMode.fleetAgentEnablementRecovery.validate(snapshot: snapshot) else {
            throw snapshot.validation(for: .fleetAgentEnablementRecovery).failure
                ?? ProvisioningFailure(step: .preflight, code: .invalidPlan)
        }
        self.init(
            deviceSerial: snapshot.serial ?? "",
            adbExecutable: adbExecutable,
            friendlyName: friendlyName,
            preflightSnapshot: snapshot
        )
    }

    static func validated(
        snapshot: ADBDeviceSnapshot,
        adbExecutable: LocalExecutableReference,
        friendlyName: String? = nil
    ) throws -> Self {
        try Self(
            validating: snapshot,
            adbExecutable: adbExecutable,
            friendlyName: friendlyName
        )
    }

    var mode: ProvisioningMode { .fleetAgentEnablementRecovery }
    var artifact: LocalArtifact? { nil }

    var validation: ProvisioningPlanValidation {
        guard let preflightSnapshot else {
            return .blocked(ProvisioningFailure(step: .preflight, code: .invalidPlan))
        }
        return mode.validate(snapshot: preflightSnapshot, artifact: nil)
    }

    var isValidated: Bool { validation.isAllowed }
    var isValid: Bool { isValidated }
}

struct FullUSBProvisioningPlan: Codable, Sendable, Equatable, Hashable {
    let deviceSerial: String
    let adbExecutable: LocalExecutableReference
    let localArtifact: LocalArtifact
    let friendlyName: String?
    let preflightSnapshot: ADBDeviceSnapshot?

    init(
        deviceSerial: String,
        adbExecutable: LocalExecutableReference,
        localArtifact: LocalArtifact,
        friendlyName: String? = nil,
        preflightSnapshot: ADBDeviceSnapshot? = nil
    ) {
        self.deviceSerial = provisioningSafeText(deviceSerial) ?? ""
        self.adbExecutable = adbExecutable
        self.localArtifact = localArtifact
        self.friendlyName = friendlyName.flatMap { provisioningSafeText($0) }
        self.preflightSnapshot = preflightSnapshot
    }

    init(
        validating snapshot: ADBDeviceSnapshot,
        adbExecutable: LocalExecutableReference,
        localArtifact: LocalArtifact,
        friendlyName: String? = nil
    ) throws {
        guard case .allowed = ProvisioningMode.fullUSBProvisioning.validate(
            snapshot: snapshot,
            artifact: localArtifact
        ) else {
            throw snapshot.validation(
                for: .fullUSBProvisioning,
                artifact: localArtifact
            ).failure ?? ProvisioningFailure(
                step: .preflight,
                code: .invalidPlan
            )
        }
        self.init(
            deviceSerial: snapshot.serial ?? "",
            adbExecutable: adbExecutable,
            localArtifact: localArtifact,
            friendlyName: friendlyName,
            preflightSnapshot: snapshot
        )
    }

    static func validated(
        snapshot: ADBDeviceSnapshot,
        adbExecutable: LocalExecutableReference,
        localArtifact: LocalArtifact,
        friendlyName: String? = nil
    ) throws -> Self {
        try Self(
            validating: snapshot,
            adbExecutable: adbExecutable,
            localArtifact: localArtifact,
            friendlyName: friendlyName
        )
    }

    var mode: ProvisioningMode { .fullUSBProvisioning }
    var artifact: LocalArtifact { localArtifact }

    var validation: ProvisioningPlanValidation {
        guard let preflightSnapshot else {
            return .blocked(ProvisioningFailure(step: .preflight, code: .invalidPlan))
        }
        return mode.validate(snapshot: preflightSnapshot, artifact: localArtifact)
    }

    var isValidated: Bool { validation.isAllowed }
    var isValid: Bool { isValidated }
}

// MARK: - Manifest projection and lifecycle events

enum ProvisioningPhase: String, Codable, Sendable, Equatable, Hashable, CaseIterable {
    case preflight
    case artifactVerification
    case deviceSetup
    case installation
    case enablementRecovery
    case bearerVerification
    case complete
}

extension ProvisioningStepID {
    var phase: ProvisioningPhase {
        switch self {
        case .preflight:
            return .preflight
        case .artifactVerification:
            return .artifactVerification
        case .deviceSetup:
            return .deviceSetup
        case .installation:
            return .installation
        case .writeProvisionFile, .relaunchImmortal, .readAgentManifest:
            return .enablementRecovery
        case .bearerVerification:
            return .bearerVerification
        case .complete:
            return .complete
        }
    }
}

/// A manifest projection containing only non-secret handoff fields. There is
/// intentionally no bearer-token, raw JSON, or process-output property.
struct AgentManifest: Codable, Sendable, Equatable, Hashable {
    let name: String
    let port: UInt16
    let serial: String?
    let model: String?
    let admittedEndpoint: LANEndpoint?

    init(
        name: String,
        port: UInt16,
        serial: String? = nil,
        model: String? = nil,
        admittedEndpoint: LANEndpoint? = nil
    ) {
        self.name = provisioningSafeDisplayName(name, fallback: "Immortal Portal")
        self.port = port
        self.serial = provisioningSafeText(serial)
        self.model = provisioningSafeText(model)
        self.admittedEndpoint = admittedEndpoint
    }

    init(
        name: String,
        port: UInt16,
        snapshot: ADBDeviceSnapshot,
        admittedEndpoint: LANEndpoint? = nil
    ) throws {
        guard port > 0 else {
            throw ProvisioningFailure(step: .readAgentManifest, code: .invalidAgentManifest)
        }
        guard let serial = snapshot.serial,
              let model = snapshot.model,
              !serial.isEmpty,
              !model.isEmpty else {
            throw ProvisioningFailure(step: .readAgentManifest, code: .invalidAgentManifest)
        }
        self.init(
            name: name,
            port: port,
            serial: serial,
            model: model,
            admittedEndpoint: admittedEndpoint
        )
    }

    static func projected(
        name: String,
        port: UInt16,
        from snapshot: ADBDeviceSnapshot,
        admittedEndpoint: LANEndpoint? = nil
    ) throws -> Self {
        try Self(
            name: name,
            port: port,
            snapshot: snapshot,
            admittedEndpoint: admittedEndpoint
        )
    }

    var deviceSerial: String? { serial }
    var deviceModel: String? { model }
    var endpoint: LANEndpoint? { admittedEndpoint }
    var isSafeProjection: Bool {
        !name.isEmpty && port > 0
            && (serial == nil || !serial!.isEmpty)
            && (model == nil || !model!.isEmpty)
    }
}

typealias AgentManifestProjection = AgentManifest

struct ProvisioningProgress: Codable, Sendable, Equatable, Hashable {
    let mode: ProvisioningMode
    let step: ProvisioningStepID
    let completedStepCount: Int
    let totalStepCount: Int

    init(
        mode: ProvisioningMode,
        step: ProvisioningStepID,
        completedStepCount: Int,
        totalStepCount: Int
    ) {
        self.mode = mode
        self.step = step
        self.completedStepCount = max(0, completedStepCount)
        self.totalStepCount = max(self.completedStepCount, totalStepCount)
    }

    var fractionCompleted: Double {
        guard totalStepCount > 0 else { return 0 }
        return min(1, Double(completedStepCount) / Double(totalStepCount))
    }
}

/// Sanitized typed lifecycle events. Full USB setup/install events use the
/// full mode; the final handoff/recovery events use the enablement mode, making
/// the two phases distinct without carrying local paths or secrets.
enum ProvisioningEvent: Codable, Sendable, Equatable, Hashable {
    case started(mode: ProvisioningMode, deviceSerial: String)
    case preflightCompleted(snapshot: ADBDeviceSnapshot)
    case stepStarted(mode: ProvisioningMode, step: ProvisioningStepID)
    case progress(ProvisioningProgress)
    case stepCompleted(mode: ProvisioningMode, step: ProvisioningStepID)
    case artifactVerificationCompleted(
        mode: ProvisioningMode,
        artifactName: String,
        digest: String?,
        summary: ArtifactVerificationSummary
    )
    case agentManifestRecovered(manifest: AgentManifest)
    case failed(ProvisioningFailure)
    case cancelled(mode: ProvisioningMode, step: ProvisioningStepID)
    case completed(mode: ProvisioningMode)

    var mode: ProvisioningMode? {
        switch self {
        case .started(let mode, _),
             .stepStarted(let mode, _),
             .stepCompleted(let mode, _),
             .artifactVerificationCompleted(let mode, _, _, _),
             .cancelled(let mode, _),
             .completed(let mode):
            return mode
        case .progress(let progress):
            return progress.mode
        case .preflightCompleted, .agentManifestRecovered, .failed:
            return nil
        }
    }

    var step: ProvisioningStepID? {
        switch self {
        case .stepStarted(_, let step),
             .stepCompleted(_, let step),
             .cancelled(_, let step):
            return step
        case .artifactVerificationCompleted:
            return .artifactVerification
        case .progress(let progress):
            return progress.step
        case .failed(let failure):
            return failure.step
        case .started, .preflightCompleted, .agentManifestRecovered, .completed:
            return nil
        }
    }

    var isTerminal: Bool {
        switch self {
        case .failed, .cancelled, .completed:
            return true
        default:
            return false
        }
    }
}

// MARK: - Sanitization helpers

private func provisioningSafeText(_ value: String?) -> String? {
    guard let value else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty,
          !trimmed.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
        return nil
    }
    return trimmed
}

private func provisioningSafeDisplayName(_ value: String, fallback: String) -> String {
    let basename = value.split(separator: "/", omittingEmptySubsequences: true).last.map(String.init) ?? value
    return provisioningSafeText(basename) ?? fallback
}

private func normalizedDigest(_ value: String?) -> String? {
    guard let value = provisioningSafeText(value)?.lowercased(), isSHA256Digest(value) else {
        return nil
    }
    return value
}

private func isSHA256Digest(_ value: String) -> Bool {
    value.count == 64 && value.allSatisfy { $0.isHexDigit }
}
