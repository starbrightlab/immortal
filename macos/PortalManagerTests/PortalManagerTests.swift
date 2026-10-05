/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import XCTest
@testable import PortalManager

final class PortalManagerTests: XCTestCase {
    @MainActor
    func testBootstrapViewCanBeConstructed() {
        XCTAssertNotNil(RootView())
        // The production composition must construct without side effects.
        XCTAssertNoThrow(PortalManagerStore(dependencies: .bootstrap()))
    }

    @MainActor
    func testAppSyncRequiresExplicitConfirmation() {
        let store = PortalManagerStore(dependencies: .bootstrap())

        store.dispatch(.stageAppSync("com.example.app"))
        XCTAssertEqual(store.pendingAppSyncPackage, "com.example.app")
        XCTAssertEqual(store.eligibleTargetCount, 0)

        store.dispatch(.cancelAppSync)
        XCTAssertNil(store.pendingAppSyncPackage)
    }

    func testPortalInfoClassifierRecognizesSupportedFamiliesAndRetainsRawModel() {
        let cases: [(String, PortalModelFamily)] = [
            ("Meta Portal", .portal2018),
            ("Meta Portal+", .portalPlus),
            ("Meta Portal+ (Gen-1)", .portalPlusFirstGeneration),
            ("Meta Portal+ (Gen-2)", .portalPlus),
            ("Meta Portal Go", .portalGo),
            ("Meta Portal Mini", .portalMini),
            ("Meta Portal (gen-2)", .portalGen2),
            ("Meta Portal TV", .portalTV)
        ]

        for (rawModel, expectedFamily) in cases {
            XCTAssertEqual(PortalInfoClassifier.modelFamily(for: rawModel), expectedFamily)
        }
        XCTAssertEqual(PortalInfoClassifier.modelFamily(for: "Meta Portal Pro"), .unknown)

        let info = AuthenticatedPortalInfo(
            name: "Living Room",
            model: "Vendor Portal Variant",
            device: "unknown-device",
            apiLevel: 29,
            app: AppVersion(versionCode: 42, versionName: "1.2.3"),
            ip: "192.168.1.20",
            port: 8723
        )
        let classification = PortalInfoClassifier.classify(
            info,
            portalID: PortalID(rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!)
        )

        XCTAssertEqual(classification.identity.rawModel, "Vendor Portal Variant")
        XCTAssertEqual(classification.identity.immortalVersion?.versionName, "1.2.3")
        XCTAssertEqual(classification.capabilities.modelFamily, .unknown)
        XCTAssertEqual(classification.endpoint?.hostOrAddress, "192.168.1.20")
        XCTAssertEqual(classification.endpoint?.port, 8723)
        XCTAssertEqual(classification.compatibility, .compatible)
    }

    func testPortalInfoClassifierKeepsEndpointAndAdvertisedCapabilitiesIndependent() {
        let info = AuthenticatedPortalInfo(
            name: "Office",
            model: "Portal",
            apiLevel: 29,
            capabilities: [
                "settingsRegistry": .bool(true),
                "sources": .bool(true),
                "calendar": .bool(true),
                "screensaver": .bool(true)
            ],
            endpointPresence: PortalEndpointPresence(
                remoteSettings: false,
                remoteSources: false,
                calendar: false,
                screensaver: true,
                action: true
            )
        )
        let classification = PortalInfoClassifier.classify(
            info,
            portalID: PortalID(rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!),
            selectedOperation: .sources
        )

        XCTAssertFalse(classification.capabilities.settingsRegistry)
        XCTAssertFalse(classification.capabilities.sources)
        XCTAssertFalse(classification.capabilities.calendar)
        XCTAssertTrue(classification.capabilities.screensaver)
        XCTAssertTrue(classification.capabilities.identify)
        XCTAssertTrue(classification.capabilities.reaffirm)
        XCTAssertEqual(
            classification.policyMetadata.operationWarnings[PortalOperation.sources.rawValue],
            "The /remote/sources capability is unavailable."
        )
        XCTAssertEqual(
            classification.assessment(for: .sources),
            .operationUnavailable(
                operation: PortalOperation.sources.rawValue,
                reason: "The /remote/sources capability is unavailable."
            )
        )
        XCTAssertEqual(classification.assessment(for: .screensaver), .compatible)
    }

    func testPortalInfoClassifierWarnsOnlyForUnsupportedAPIOrSelectedMissingCapability() {
        let unsupportedAPI = AuthenticatedPortalInfo(
            name: "Unknown",
            model: "Unrecognized Model",
            apiLevel: 30,
            capabilities: ["calendar": .bool(true)]
        )
        let classification = PortalInfoClassifier.classify(
            unsupportedAPI,
            portalID: PortalID(rawValue: UUID(uuidString: "66666666-7777-8888-9999-AAAAAAAAAAAA")!)
        )

        XCTAssertEqual(
            classification.compatibility,
            .warning(reason: "Android API level 30 is not supported by the version-one Portal Manager.")
        )
        XCTAssertEqual(classification.assessment(for: .calendar), classification.compatibility)
        XCTAssertEqual(
            classification.assessment(for: .screensaver),
            .operationUnavailable(
                operation: PortalOperation.screensaver.rawValue,
                reason: "The /screensaver capability is unavailable."
            )
        )

        let supportedAPI = AuthenticatedPortalInfo(
            name: "Unknown",
            model: "Unrecognized Model",
            apiLevel: 28,
            capabilities: ["calendar": .bool(true)]
        )
        XCTAssertEqual(
            PortalInfoClassifier.classify(
                supportedAPI,
                portalID: PortalID(rawValue: UUID(uuidString: "BBBBBBBB-CCCC-DDDD-EEEE-FFFFFFFFFFFF")!)
            ).compatibility,
            .compatible
        )
    }
}


extension PortalManagerTests {
    func testConnectionAdmissionRequiresTrustBeforeCredentialOrOperation() async throws {
        let recorder = AdmissionTestRecorder()
        let resolver = AdmissionTestDNSResolver(
            responses: [
                DNSResolutionResult(addresses: [ResolvedAddress(address: "192.168.1.20")]),
                DNSResolutionResult(addresses: [ResolvedAddress(address: "192.168.1.20")])
            ],
            recorder: recorder
        )
        let trustStore = AdmissionTestTrustWarningStore(recorder: recorder)
        let credentialStore = AdmissionTestCredentialStore(recorder: recorder)
        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let request = try ConnectionAdmissionRequest(
            rawEndpoint: "portal.local",
            serviceKind: .portal,
            protocolName: "HTTP"
        )
        let expectedScope = TrustWarningScope(
            serviceKind: .portal,
            protocolName: "http",
            resolvedHostOrAddress: "192.168.1.20",
            port: 8723
        )

        do {
            _ = try await admission.withAdmittedConnection(
                request,
                credentialReference: CredentialReference(
                    namespace: "portal",
                    identifier: "test"
                ),
                credentialStore: credentialStore
            ) { _, _ in
                await recorder.append("operation")
                return true
            }
            XCTFail("An unacknowledged trust scope must stop before credentials or operation work.")
        } catch let error as ConnectionAdmissionError {
            XCTAssertEqual(error, .trustWarningRequired(scope: expectedScope))
        }

        let readsBeforeAcknowledgement = await credentialStore.readCount
        XCTAssertEqual(readsBeforeAcknowledgement, 0)
        let eventsBeforeAcknowledgement = await recorder.events()
        XCTAssertEqual(eventsBeforeAcknowledgement, ["resolve", "trust-check"])

        try await admission.acknowledgeTrust(
            for: expectedScope,
            at: Date(timeIntervalSince1970: 100)
        )
        await recorder.reset()

        let didRun = try await admission.withAdmittedConnection(
            request,
            credentialReference: CredentialReference(
                namespace: "portal",
                identifier: "test"
            ),
            credentialStore: credentialStore
        ) { _, credential in
            await recorder.append("operation")
            return credential == Data("bearer".utf8)
        }

        XCTAssertTrue(didRun)
        let orderedEvents = await recorder.events()
        XCTAssertEqual(orderedEvents, ["resolve", "trust-check", "credential", "operation"])
        let finalReadCount = await credentialStore.readCount
        XCTAssertEqual(finalReadCount, 1)
    }

    func testConnectionAdmissionSelectsOnlyPermittedResolvedAddress() async throws {
        let resolver = AdmissionTestDNSResolver(
            responses: [
                DNSResolutionResult(addresses: [
                    ResolvedAddress(address: "203.0.113.10"),
                    ResolvedAddress(address: "192.168.1.44")
                ])
            ]
        )
        let trustStore = AdmissionTestTrustWarningStore()
        let expectedScope = TrustWarningScope(
            serviceKind: .musicAssistant,
            protocolName: "ws",
            resolvedHostOrAddress: "192.168.1.44",
            port: 8095
        )
        try await trustStore.acknowledge(expectedScope, at: Date(timeIntervalSince1970: 200))

        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let admitted = try await admission.admit(
            rawEndpoint: "music.local",
            serviceKind: .musicAssistant,
            protocolName: "WS",
            defaultPort: 8095
        )

        XCTAssertEqual(admitted.endpoint.hostOrAddress, "192.168.1.44")
        XCTAssertEqual(admitted.endpoint.port, 8095)
        XCTAssertEqual(admitted.trustScope, expectedScope)
    }

    func testConnectionAdmissionRevalidatesEveryReconnect() async throws {
        let resolver = AdmissionTestDNSResolver(
            responses: [
                DNSResolutionResult(addresses: [ResolvedAddress(address: "10.0.0.15")]),
                DNSResolutionResult(addresses: [ResolvedAddress(address: "198.51.100.15")])
            ]
        )
        let trustStore = AdmissionTestTrustWarningStore()
        let scope = TrustWarningScope(
            serviceKind: .portal,
            protocolName: "http",
            resolvedHostOrAddress: "10.0.0.15",
            port: 8723
        )
        try await trustStore.acknowledge(scope, at: Date(timeIntervalSince1970: 300))

        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let request = try ConnectionAdmissionRequest(
            rawEndpoint: "portal.local",
            serviceKind: .portal,
            protocolName: "http"
        )

        _ = try await admission.admit(request)

        do {
            _ = try await admission.reconnect(request)
            XCTFail("A public address returned on reconnect must be rejected.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .lanPolicy(.noPermittedAddress))
        }

        let resolverRequestCount = await resolver.requestCount
        let trustCheckCount = await trustStore.checkCount
        XCTAssertEqual(resolverRequestCount, 2)
        XCTAssertEqual(trustCheckCount, 1)
    }

    func testConnectionAdmissionPreservesIPv6InterfaceZoneInEndpointAndScope() async throws {
        let resolver = AdmissionTestDNSResolver(
            responses: [
                DNSResolutionResult(addresses: [
                    ResolvedAddress(address: "FE80::1", interfaceZone: "en0")
                ])
            ]
        )
        let trustStore = AdmissionTestTrustWarningStore()
        let scope = TrustWarningScope(
            serviceKind: .snapcast,
            protocolName: "TCP-JSON-RPC",
            resolvedHostOrAddress: "fe80::1",
            port: 1705,
            interfaceZone: "en0"
        )
        try await trustStore.acknowledge(scope, at: Date(timeIntervalSince1970: 400))

        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let admitted = try await admission.admit(
            rawEndpoint: "snapcast.local",
            serviceKind: .snapcast,
            protocolName: "TCP-JSON-RPC",
            defaultPort: 1705
        )

        XCTAssertEqual(admitted.endpoint.hostOrAddress, "FE80::1")
        XCTAssertEqual(admitted.endpoint.interfaceZone, "en0")
        XCTAssertEqual(admitted.trustScope.resolvedHostOrAddress, "fe80::1")
        XCTAssertEqual(admitted.trustScope.interfaceZone, "en0")
    }
}

extension PortalManagerTests {
    func testSourceResponseSanitizerStripsLegacySecretsBeforeSnapshot() throws {
        let credentialStore = FakeCredentialStore()
        let coordinator = SourceSecretCoordinator(credentialStore: credentialStore)
        let response = sourceResponseData(
            fields: [
                "source": "smb",
                "immichUrl": "http://192.168.1.50:2283",
                "immichKey": "IMMICH-SENTINEL",
                "smbHost": "192.168.1.60",
                "smbShare": "photos",
                "smbUser": "SMB-USER-SENTINEL",
                "smbPass": "SMB-PASS-SENTINEL",
                "davUrl": "http://192.168.1.70/dav",
                "davUser": "DAV-USER-SENTINEL",
                "davPass": "DAV-PASS-SENTINEL",
                "futureApiToken": "FUTURE-TOKEN-SENTINEL"
            ]
        )

        let snapshot = try coordinator.sanitizedSnapshot(
            from: response,
            sourceID: "screensaver"
        )

        XCTAssertEqual(snapshot.sourceID, "screensaver")
        XCTAssertEqual(snapshot.nonSecretFields["source"], .string("smb"))
        XCTAssertEqual(snapshot.nonSecretFields["smbHost"], .string("192.168.1.60"))
        XCTAssertNil(snapshot.nonSecretFields["immichKey"])
        XCTAssertNil(snapshot.nonSecretFields["smbUser"])
        XCTAssertNil(snapshot.nonSecretFields["smbPass"])
        XCTAssertNil(snapshot.nonSecretFields["davUser"])
        XCTAssertNil(snapshot.nonSecretFields["davPass"])
        XCTAssertNil(snapshot.nonSecretFields["futureApiToken"])

        for field in SourceSecretField.allCases {
            XCTAssertEqual(
                snapshot.status(for: field),
                .legacyConfiguredMigrationRequired,
                "Expected legacy migration state for \(field.rawValue)."
            )
        }

        let serializedMetadata = try JSONEncoder().encode(snapshot.nonSecretFields)
        let metadataText = String(decoding: serializedMetadata, as: UTF8.self)
        XCTAssertFalse(metadataText.contains("SENTINEL"))
    }

    func testLegacySourceMigrationUsesExactPortalSourceKeychainItemsAndClearsInputs() async throws {
        let credentialStore = FakeCredentialStore()
        let secureInputStore = FakeSecureInputStore()
        let coordinator = SourceSecretCoordinator(
            credentialStore: credentialStore,
            secureInputStore: secureInputStore
        )
        let portalID = PortalID(
            rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
        )
        let response = sourceResponseData(
            fields: [
                "source": "smb",
                "immichKey": "IMMICH-SENTINEL",
                "smbUser": "SMB-USER-SENTINEL",
                "smbPass": "SMB-PASS-SENTINEL",
                "davUser": "DAV-USER-SENTINEL",
                "davPass": "DAV-PASS-SENTINEL"
            ]
        )

        let migration = try await coordinator.migrateLegacySecrets(
            from: response,
            portalID: portalID,
            sourceID: "screensaver"
        )

        XCTAssertEqual(migration.writes.count, SourceSecretField.allCases.count)
        XCTAssertTrue(migration.writes.allSatisfy(\.didPersist))
        XCTAssertTrue(
            SourceSecretField.allCases.allSatisfy {
                migration.status(for: $0) == .configuredInKeychain
            }
        )
        XCTAssertEqual(secureInputStore.activeInputCount, 0)

        let expectedReferences = Set(
            SourceSecretField.allCases.map {
                CredentialReference.sourceCredential(
                    portalID: portalID,
                    sourceID: "screensaver",
                    field: $0
                )
            }
        )
        let writtenReferences = Set(
            credentialStore.operations.compactMap { operation -> CredentialReference? in
                operation.operation == .write ? operation.reference : nil
            }
        )
        XCTAssertEqual(writtenReferences, expectedReferences)
        XCTAssertEqual(credentialStore.storedReferences, expectedReferences)

        let serializedMetadata = try JSONEncoder().encode(migration.snapshot.nonSecretFields)
        let metadataText = String(decoding: serializedMetadata, as: UTF8.self)
        XCTAssertFalse(metadataText.contains("SENTINEL"))
    }

    func testLegacySourceMigrationReportsReentryWhenKeychainWriteFails() async throws {
        let credentialStore = FakeCredentialStore()
        credentialStore.setWriteFailure(true)
        let secureInputStore = FakeSecureInputStore()
        let coordinator = SourceSecretCoordinator(
            credentialStore: credentialStore,
            secureInputStore: secureInputStore
        )
        let response = sourceResponseData(
            fields: ["immichKey": "IMMICH-SENTINEL"]
        )
        let portalID = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )

        let migration = try await coordinator.migrateLegacySecrets(
            from: response,
            portalID: portalID,
            sourceID: "screensaver"
        )

        XCTAssertEqual(
            migration.status(for: .immichKey),
            .configuredButReentryRequired
        )
        XCTAssertEqual(migration.writes.count, 1)
        XCTAssertEqual(
            migration.writes[0].error,
            .keychain(.writeFailed)
        )
        XCTAssertEqual(secureInputStore.activeInputCount, 0)
        XCTAssertTrue(credentialStore.storedReferences.isEmpty)
    }

    func testSourceReplacementAndPreserveEditsRespectFieldPresence() async {
        let credentialStore = FakeCredentialStore()
        let secureInputStore = FakeSecureInputStore()
        let coordinator = SourceSecretCoordinator(
            credentialStore: credentialStore,
            secureInputStore: secureInputStore
        )
        let key = SourceSecretKey(
            portalID: PortalID(
                rawValue: UUID(uuidString: "66666666-7777-8888-9999-AAAAAAAAAAAA")!
            ),
            sourceID: "screensaver",
            field: .smbPass
        )

        let preserved = await coordinator.apply(.preserve, for: key)
        XCTAssertNil(preserved)
        XCTAssertTrue(credentialStore.operations.isEmpty)

        let replacement = await coordinator.replaceSecret(
            "REPLACEMENT-SENTINEL",
            for: key
        )
        XCTAssertTrue(replacement.didPersist)
        XCTAssertEqual(replacement.key.credentialReference, key.credentialReference)
        XCTAssertEqual(secureInputStore.activeInputCount, 0)
        XCTAssertEqual(credentialStore.storedReferences, [key.credentialReference])
    }

    private func sourceResponseData(fields: [String: String]) -> Data {
        let object: [String: Any] = ["sources": fields]
        return try! JSONSerialization.data(withJSONObject: object)
    }
}

private actor AdmissionTestRecorder {
    private var values: [String] = []

    func append(_ value: String) {
        values.append(value)
    }

    func events() -> [String] {
        values
    }

    func reset() {
        values.removeAll()
    }
}

private actor AdmissionTestDNSResolver: DNSResolver {
    private var responses: [DNSResolutionResult]
    private(set) var requestCount = 0
    private let recorder: AdmissionTestRecorder?

    init(
        responses: [DNSResolutionResult],
        recorder: AdmissionTestRecorder? = nil
    ) {
        self.responses = responses
        self.recorder = recorder
    }

    func resolve(_ request: DNSResolutionRequest) async throws -> DNSResolutionResult {
        requestCount += 1
        await recorder?.append("resolve")
        guard !responses.isEmpty else {
            throw ManagerError.resolution(.noAddresses)
        }
        return responses.removeFirst()
    }
}

private actor AdmissionTestTrustWarningStore: TrustWarningStore {
    private var acknowledgedScopes: Set<TrustWarningScope> = []
    private(set) var checkCount = 0
    private let recorder: AdmissionTestRecorder?

    init(recorder: AdmissionTestRecorder? = nil) {
        self.recorder = recorder
    }

    func acknowledgement(
        for scope: TrustWarningScope
    ) async throws -> TrustWarningAcknowledgement? {
        checkCount += 1
        await recorder?.append("trust-check")
        let normalized = scope.normalized
        guard acknowledgedScopes.contains(normalized) else { return nil }
        return TrustWarningAcknowledgement(
            scope: normalized,
            acknowledgedAt: Date(timeIntervalSince1970: 1)
        )
    }

    func acknowledge(
        _ scope: TrustWarningScope,
        at date: Date
    ) async throws {
        acknowledgedScopes.insert(scope.normalized)
        await recorder?.append("trust-ack")
    }
}

private actor AdmissionTestCredentialStore: CredentialStore {
    private let recorder: AdmissionTestRecorder?
    private(set) var readCount = 0

    init(recorder: AdmissionTestRecorder? = nil) {
        self.recorder = recorder
    }

    func read(_ reference: CredentialReference) async throws -> Data? {
        readCount += 1
        await recorder?.append("credential")
        return Data("bearer".utf8)
    }

    func write(_ value: Data, for reference: CredentialReference) async throws {}

    func delete(_ reference: CredentialReference) async throws {}
}


extension PortalManagerTests {
    func testJSONRegistryPersistsOnlyNonSecretMetadataAndRejectsSensitivePayloads() async throws {
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("portal-registry-\(UUID().uuidString)")
            .appendingPathExtension("json")
        defer { try? FileManager.default.removeItem(at: fileURL) }

        let portalID = PortalID(
            rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
        )
        let timestamp = Date(timeIntervalSince1970: 1_000)
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .offline(
                lastContact: timestamp,
                reason: "The Portal could not be reached."
            ),
            identity: PortalIdentity(
                portalID: portalID,
                serial: "SERIAL-1",
                name: "Living Room",
                model: "Meta Portal",
                device: "portal-device",
                rawModel: "Meta Portal",
                androidAPILevel: 29,
                immortalVersion: AppVersion(versionCode: 7, versionName: "1.0.7")
            ),
            endpoint: LANEndpoint(
                hostOrAddress: "192.168.1.20",
                addressFamily: .ipv4,
                source: .authenticatedRefresh,
                lastAuthenticatedAt: timestamp
            ),
            capabilities: PortalCapabilities(
                modelFamily: .portal2018,
                androidAPILevel: 29,
                fleetInfo: true,
                settingsRegistry: true,
                rawAdvertisedCapabilities: ["safeCapability": .bool(true)]
            ),
            credentialReferences: [
                .portalCredential(portalID: portalID, kind: .verifiedBearer)
            ],
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: timestamp,
                responseTimeMilliseconds: 12
            ),
            policyMetadata: PortalPolicyMetadata(notes: ["safe metadata"])
        )
        let store = JSONRegistryStore(fileURL: fileURL)

        try await store.save(RegistrySnapshot(entries: [entry]))
        let persistedData = try Data(contentsOf: fileURL)
        let persistedText = String(decoding: persistedData, as: UTF8.self)
        XCTAssertTrue(persistedText.contains("credentialReferences"))
        XCTAssertFalse(persistedText.contains("TOKEN-SENTINEL"))
        let loadedSnapshot = try await store.load()
        XCTAssertEqual(loadedSnapshot, RegistrySnapshot(entries: [entry]))

        let unsafeData = Data(
            "{\"schemaVersion\":1,\"entries\":[],\"token\":\"TOKEN-SENTINEL\"}".utf8
        )
        try unsafeData.write(to: fileURL, options: [.atomic])
        do {
            _ = try await store.load()
            XCTFail("The registry must reject a sensitive persisted key.")
        } catch let error as RegistryStoreError {
            XCTAssertEqual(error, .containsSensitiveData)
        }
    }

    func testKeychainAccountDerivationIsScopedByPortalServiceAndSource() throws {
        let firstPortal = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )
        let secondPortal = PortalID(
            rawValue: UUID(uuidString: "66666666-7777-8888-9999-AAAAAAAAAAAA")!
        )
        let firstPortalID = firstPortal.rawValue.uuidString.lowercased()

        XCTAssertEqual(
            try KeychainCredentialStore.accountIdentifier(
                for: .portalCredential(portalID: firstPortal, kind: .verifiedBearer)
            ),
            "portal/\(firstPortalID)/verifiedBearer"
        )
        XCTAssertEqual(
            try KeychainCredentialStore.accountIdentifier(
                for: .portalCredential(portalID: firstPortal, kind: .remoteSession)
            ),
            "portal/\(firstPortalID)/remoteSession"
        )
        XCTAssertEqual(
            try KeychainCredentialStore.accountIdentifier(
                for: .serviceCredential(
                    service: .musicAssistant,
                    kind: .musicAssistant,
                    portalID: firstPortal
                )
            ),
            "service/\(firstPortalID)/musicAssistant/musicAssistant"
        )
        XCTAssertNotEqual(
            try KeychainCredentialStore.accountIdentifier(
                for: .serviceCredential(
                    service: .musicAssistant,
                    kind: .musicAssistant,
                    portalID: firstPortal
                )
            ),
            try KeychainCredentialStore.accountIdentifier(
                for: .serviceCredential(
                    service: .musicAssistant,
                    kind: .musicAssistant,
                    portalID: secondPortal
                )
            )
        )

        let sourceReference = CredentialReference.sourceCredential(
            portalID: firstPortal,
            sourceID: "screensaver/photos",
            field: .smbPass
        )
        XCTAssertEqual(
            try KeychainCredentialStore.accountIdentifier(for: sourceReference),
            "source/\(firstPortalID)/source/screensaver%2Fphotos/smbPass"
        )

        XCTAssertThrowsError(
            try KeychainCredentialStore.accountIdentifier(
                for: CredentialReference(namespace: "portal", identifier: "bad/source")
            )
        ) { error in
            XCTAssertEqual(error as? ManagerError, .keychain(.invalidReference))
        }
    }

    func testRedactionAndTransientSecureInputClearAcrossSuccessAndFailure() throws {
        let redactor = StructuredRedactor(
            sensitiveValues: ["BEARER-SENTINEL", "PASSWORD-SENTINEL"]
        )
        let text = redactor.redact(
            "Authorization: Bearer BEARER-SENTINEL url=https://user:PASSWORD-SENTINEL@192.168.1.20/path "
                + "smbPass=PASSWORD-SENTINEL pin=123456"
        ).value
        XCTAssertFalse(text.contains("BEARER-SENTINEL"))
        XCTAssertFalse(text.contains("PASSWORD-SENTINEL"))
        XCTAssertFalse(text.contains("123456"))
        XCTAssertTrue(text.contains(RedactionMarker.value))

        let structured = redactor.redact(
            .object([
                "nested": .object([
                    "smbPass": .string("PASSWORD-SENTINEL"),
                    "safe": .string("visible")
                ]),
                "redirectUrl": .string("https://192.168.1.20/?token=BEARER-SENTINEL")
            ])
        )
        let structuredText = String(
            decoding: try JSONEncoder().encode(structured),
            as: UTF8.self
        )
        XCTAssertFalse(structuredText.contains("BEARER-SENTINEL"))
        XCTAssertFalse(structuredText.contains("PASSWORD-SENTINEL"))

        let headers = redactor.redact(
            headers: ["Authorization": "Bearer BEARER-SENTINEL", "X-Status": "ok"]
        )
        XCTAssertEqual(headers["Authorization"], RedactionMarker.value)
        XCTAssertEqual(headers["X-Status"], "ok")

        let inputStore = FakeSecureInputStore()
        let succeeded = inputStore.withSecureInput("PASSWORD-SENTINEL") { input in
            XCTAssertFalse(input.isEmpty)
            return input.withData { data in
                String(decoding: data, as: UTF8.self) == "PASSWORD-SENTINEL"
            }
        }
        XCTAssertTrue(succeeded)
        XCTAssertEqual(inputStore.activeInputCount, 0)

        XCTAssertThrowsError(
            try inputStore.withSecureInput("FAILURE-SENTINEL") { _ -> Bool in
                throw NSError(domain: "checkpoint", code: 1)
            }
        )
        XCTAssertEqual(inputStore.activeInputCount, 0)
    }

    func testSourceSnapshotUsesConfiguredKeychainStateAfterLegacyMigration() throws {
        let coordinator = SourceSecretCoordinator(credentialStore: FakeCredentialStore())
        let response = sourceResponseData(fields: ["immichKey": "IMMICH-SENTINEL"])

        let snapshot = try coordinator.sanitizedSnapshot(
            from: response,
            sourceID: "screensaver",
            keychainConfiguredFields: [.immichKey]
        )

        XCTAssertEqual(snapshot.status(for: .immichKey), .configuredInKeychain)
        XCTAssertNil(snapshot.nonSecretFields["immichKey"])
    }

    func testRegistryReconciliationMergesAuthenticatedDuplicatesAndRetainsOfflineState() {
        let firstID = PortalID(
            rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
        )
        let duplicateID = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )
        let oldDate = Date(timeIntervalSince1970: 100)
        let duplicateDate = Date(timeIntervalSince1970: 200)
        let latestDate = Date(timeIntervalSince1970: 300)
        let firstReference = CredentialReference.portalCredential(
            portalID: firstID,
            kind: .verifiedBearer
        )
        let duplicateReference = CredentialReference.portalCredential(
            portalID: duplicateID,
            kind: .remoteSession
        )
        let firstEndpoint = LANEndpoint(
            hostOrAddress: "192.168.1.10",
            addressFamily: .ipv4,
            source: .manual,
            lastAuthenticatedAt: oldDate
        )
        let duplicateEndpoint = LANEndpoint(
            hostOrAddress: "192.168.1.11",
            addressFamily: .ipv4,
            source: .mdns(serviceName: "portal-two"),
            lastAuthenticatedAt: duplicateDate
        )
        let latestEndpoint = LANEndpoint(
            hostOrAddress: "192.168.1.12",
            addressFamily: .ipv4,
            source: .authenticatedRefresh
        )
        let firstIdentity = PortalIdentity(
            portalID: firstID,
            serial: "SERIAL-SHARED",
            name: "Old Name",
            model: "Meta Portal",
            device: "portal-device",
            rawModel: "Meta Portal"
        )
        let duplicateIdentity = PortalIdentity(
            portalID: duplicateID,
            serial: "SERIAL-SHARED",
            name: "Duplicate Name",
            model: "Meta Portal",
            device: "portal-device",
            rawModel: "Meta Portal"
        )
        let firstEntry = PortalRegistryEntry(
            id: firstID,
            connectionState: .online(lastRefresh: oldDate, latencyMs: 20),
            identity: firstIdentity,
            endpoint: firstEndpoint,
            discoveredEndpoints: [firstEndpoint],
            credentialReferences: [firstReference],
            lastSuccessfulContact: oldDate,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: oldDate,
                responseTimeMilliseconds: 20
            )
        )
        let duplicateEntry = PortalRegistryEntry(
            id: duplicateID,
            connectionState: .online(lastRefresh: duplicateDate, latencyMs: 30),
            identity: duplicateIdentity,
            endpoint: duplicateEndpoint,
            discoveredEndpoints: [duplicateEndpoint],
            credentialReferences: [duplicateReference],
            lastSuccessfulContact: duplicateDate,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: duplicateDate,
                responseTimeMilliseconds: 30
            )
        )
        var registry = PortalRegistry(
            snapshot: RegistrySnapshot(entries: [firstEntry, duplicateEntry]),
            bulkOperationMembership: [duplicateID]
        )

        let reconciliation = registry.reconcile(
            AuthenticatedPortalRecord(
                identity: PortalIdentity(
                    portalID: firstID,
                    serial: "SERIAL-SHARED",
                    name: "Current Name",
                    model: "Meta Portal",
                    device: "portal-device",
                    rawModel: "Meta Portal"
                ),
                endpoint: latestEndpoint,
                status: PortalStatus(
                    reachability: .reachable,
                    lastUpdatedAt: latestDate,
                    responseTimeMilliseconds: 14
                ),
                credentialReferences: [firstReference],
                verifiedAt: latestDate
            )
        )

        XCTAssertFalse(reconciliation.didCreateEntry)
        XCTAssertEqual(reconciliation.mergedPortalIDs, Set([firstID, duplicateID]))
        XCTAssertEqual(registry.entries.count, 1)
        XCTAssertEqual(registry.entries[0].id, firstID)
        XCTAssertEqual(registry.entries[0].identity?.name, "Current Name")
        XCTAssertEqual(
            Set(registry.entries[0].credentialReferences),
            Set([firstReference, duplicateReference])
        )
        XCTAssertEqual(registry.entries[0].endpoint?.hostOrAddress, "192.168.1.12")
        XCTAssertEqual(registry.bulkOperationMembership, Set([firstID]))

        let offline = registry.markOffline(for: firstID, reason: "timeout")
        XCTAssertEqual(offline?.lastSuccessfulContact, latestDate)
        XCTAssertEqual(offline?.lastConfirmedStatus?.responseTimeMilliseconds, 14)
        XCTAssertEqual(offline?.endpoint?.hostOrAddress, "192.168.1.12")
        XCTAssertEqual(
            offline?.connectionState,
            .offline(lastContact: latestDate, reason: "timeout")
        )
    }

    func testRegistryRemovalDeletesOnlyUnsharedCredentialsAndBulkMembership() async throws {
        let firstID = PortalID(
            rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
        )
        let secondID = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )
        let sharedReference = CredentialReference(namespace: "shared", identifier: "credential")
        let firstOnlyReference = CredentialReference(namespace: "first", identifier: "credential")
        let secondOnlyReference = CredentialReference(namespace: "second", identifier: "credential")
        let firstEntry = PortalRegistryEntry(
            id: firstID,
            connectionState: .offline(lastContact: nil, reason: "offline"),
            credentialReferences: [sharedReference, firstOnlyReference]
        )
        let secondEntry = PortalRegistryEntry(
            id: secondID,
            connectionState: .offline(lastContact: nil, reason: "offline"),
            credentialReferences: [sharedReference, secondOnlyReference]
        )
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [firstEntry, secondEntry])
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [
                sharedReference: Data("shared".utf8),
                firstOnlyReference: Data("first".utf8),
                secondOnlyReference: Data("second".utf8)
            ]
        )
        let coordinator = PortalRegistryCoordinator(
            registryStore: registryStore,
            credentialStore: credentialStore,
            bulkOperationMembership: [firstID, secondID]
        )
        _ = try await coordinator.load()

        let removal = try await coordinator.remove(firstID)
        XCTAssertEqual(removal?.portalID, firstID)
        XCTAssertTrue(removal?.wasMemberOfBulkOperation == true)
        XCTAssertFalse(credentialStore.hasValue(for: firstOnlyReference))
        XCTAssertTrue(credentialStore.hasValue(for: sharedReference))
        XCTAssertTrue(credentialStore.hasValue(for: secondOnlyReference))
        XCTAssertEqual(
            credentialStore.operations.filter { $0.operation == .delete }.map(\.reference),
            [firstOnlyReference]
        )
        let remainingEntries = try await coordinator.entries()
        let remainingMembership = try await coordinator.bulkOperationMembership()
        XCTAssertEqual(remainingEntries.map(\.id), [secondID])
        XCTAssertEqual(remainingMembership, Set([secondID]))
    }
}


extension PortalManagerTests {
    func testOperationPlannerEnforcesClosedFleetCredentialMatrix() throws {
        let portalID = PortalID(
            rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
        )
        let otherPortalID = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )
        let approvalDate = Date(timeIntervalSince1970: 600)
        let planner = OperationPlanner()

        let pairing = try planner.plan(
            portalID: portalID,
            route: .remotePair,
            method: .post
        )
        XCTAssertEqual(pairing.credential, nil)
        XCTAssertNil(pairing.remoteApproval)

        let info = try planner.plan(
            portalID: portalID,
            route: .info,
            method: .get,
            credential: .verifiedBearer
        )
        XCTAssertEqual(info.credential, .verifiedBearer)

        for route in [FleetRoute.remoteSettings, .remoteSources] {
            for method in [HTTPMethod.get, .post] {
                let bearerPlan = try planner.plan(
                    portalID: portalID,
                    route: route,
                    method: method,
                    credential: .verifiedBearer
                )
                XCTAssertEqual(bearerPlan.credential, .verifiedBearer)
                XCTAssertNil(bearerPlan.remoteApproval)

                let operationID = "\(route.path).\(method.rawValue.lowercased())"
                let approval = RemoteOperationApproval(
                    portalID: portalID,
                    route: route,
                    method: method,
                    operationID: operationID,
                    approvedAt: approvalDate
                )
                let sessionPlan = try planner.plan(
                    portalID: portalID,
                    route: route,
                    method: method,
                    credential: .remoteSession,
                    remoteApproval: approval,
                    operationID: operationID
                )
                XCTAssertEqual(sessionPlan.credential, .remoteSession)
                XCTAssertEqual(sessionPlan.remoteApproval, approval)
            }
        }

        XCTAssertThrowsError(
            try planner.plan(
                portalID: portalID,
                route: .info,
                method: .get,
                credential: .remoteSession
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .credentialNotPermitted)
        }

        XCTAssertThrowsError(
            try planner.plan(
                portalID: portalID,
                route: .screensaver,
                method: .get,
                credential: .remoteSession
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .credentialNotPermitted)
        }

        XCTAssertThrowsError(
            try planner.plan(
                portalID: portalID,
                route: .calendar,
                method: .post,
                credential: .remoteSession
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .credentialNotPermitted)
        }

        XCTAssertThrowsError(
            try planner.plan(
                portalID: portalID,
                route: .action(.identify),
                method: .post,
                credential: .remoteSession
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .credentialNotPermitted)
        }

        let settingsApproval = RemoteOperationApproval(
            portalID: portalID,
            route: .remoteSettings,
            method: .get,
            operationID: "settings.read",
            approvedAt: approvalDate
        )
        XCTAssertThrowsError(
            try planner.plan(
                portalID: otherPortalID,
                route: .remoteSettings,
                method: .get,
                credential: .remoteSession,
                remoteApproval: settingsApproval,
                operationID: "settings.read"
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .remoteSessionApprovalMismatch)
        }
        XCTAssertThrowsError(
            try planner.plan(
                portalID: portalID,
                route: .remoteSettings,
                method: .post,
                credential: .remoteSession,
                remoteApproval: settingsApproval,
                operationID: "settings.read"
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .remoteSessionApprovalMismatch)
        }
        XCTAssertThrowsError(
            try planner.plan(
                portalID: portalID,
                route: .remoteSettings,
                method: .get,
                credential: .remoteSession,
                remoteApproval: settingsApproval,
                operationID: "settings.apply"
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .remoteSessionApprovalMismatch)
        }

        XCTAssertThrowsError(
            try planner.plan(
                portalID: portalID,
                route: .remotePair,
                method: .post,
                credential: .verifiedBearer
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .credentialNotPermitted)
        }
        XCTAssertThrowsError(
            try planner.plan(
                portalID: portalID,
                route: .info,
                method: .post,
                credential: .verifiedBearer
            )
        ) { error in
            XCTAssertEqual(error as? OperationPlanningError, .methodNotAllowed)
        }
    }

    func testOperationExclusionGateExplainsAndContinuesWithoutRequestOrDeviceChange() {
        let gate = OperationExclusionGate()
        let result = gate.evaluate(
            route: "/apps",
            operationID: "future.install"
        )

        XCTAssertTrue(result.explanation.contains("No request was sent"))
        XCTAssertTrue(result.explanation.contains("unchanged"))
        XCTAssertEqual(result.outcome, .continueWorkflow)
        XCTAssertTrue(result.shouldContinue)
        XCTAssertFalse(result.requestEmitted)
        XCTAssertFalse(result.deviceChanged)

        let future = gate.handle(
            ExcludedOperationIntent(futureRoute: "/remote/future")
        )
        XCTAssertEqual(future.outcome, .continueWorkflow)
        XCTAssertFalse(future.requestEmitted)
        XCTAssertFalse(future.deviceChanged)
    }
}


extension PortalManagerTests {
    func testHTTPTransportRejectsRedirectWithoutConstructingLocationRequest() async throws {
        let executor = FakeHTTPRequestExecutor(
            responses: [
                FleetHTTPResponse(
                    statusCode: 302,
                    headers: ["Location": "http://203.0.113.10/info"]
                )
            ]
        )
        let transport = HTTPTransport(requestExecutor: executor)
        let request = try makeAdmittedHTTPTransportRequest(
            credential: Data("BEARER-SENTINEL".utf8)
        )

        do {
            _ = try await transport.send(request)
            XCTFail("Every 3xx response must be rejected locally.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .redirectRejected)
        }

        XCTAssertEqual(executor.requestCount, 1)
        XCTAssertTrue(executor.requestMetadata[0].hasAuthorization)
        XCTAssertFalse(
            executor.requestMetadata.contains {
                $0.url.contains("203.0.113.10")
                    || $0.url.contains("BEARER-SENTINEL")
            }
        )
    }

    func testHTTPTransportAppliesTenSecondStatusDeadlineToTypedRequest() async throws {
        let executor = FakeHTTPRequestExecutor(
            responses: [FleetHTTPResponse(statusCode: 200)]
        )
        let transport = HTTPTransport(requestExecutor: executor)
        let request = try makeAdmittedHTTPTransportRequest(
            credential: Data("BEARER-SENTINEL".utf8)
        )

        let response = try await transport.send(request)

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(executor.requestCount, 1)
        XCTAssertEqual(
            executor.requestMetadata[0].timeoutInterval,
            HTTPTransport.defaultStatusDeadline,
            accuracy: 0.001
        )
    }

    func testHTTPConnectionExecutorAdmitsBeforeCredentialAndTransport() async throws {
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(
            responses: [
                DNSResolutionResult(
                    addresses: [ResolvedAddress(address: "192.168.1.20")]
                )
            ],
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        let credentialReference = CredentialReference(
            namespace: "portal",
            identifier: "verified-bearer"
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [credentialReference: Data("BEARER-SENTINEL".utf8)],
            recorder: recorder
        )
        let transport = FakeFleetHTTPTransport(
            responses: [FleetHTTPResponse(statusCode: 200)],
            recorder: recorder
        )
        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let connectionExecutor = HTTPConnectionExecutor(
            admission: admission,
            credentialStore: credentialStore,
            transport: transport
        )
        let portalID = PortalID(
            rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
        )
        let plan = try OperationPlanner().plan(
            portalID: portalID,
            route: .info,
            method: .get,
            credential: .verifiedBearer
        )
        let request = FleetConnectionRequest(
            admissionRequest: try ConnectionAdmissionRequest(
                rawEndpoint: "portal.local",
                serviceKind: .portal,
                protocolName: "http"
            ),
            routePlan: plan,
            credentialReference: credentialReference
        )
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: "192.168.1.20",
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 700)
        )

        let response = try await connectionExecutor.execute(request)

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertTrue(
            recorder.occurredInOrder([
                .dnsResolve,
                .trustWarningLookup,
                .credentialRead,
                .transportSend
            ])
        )
        XCTAssertEqual(credentialStore.operations.count, 1)
        XCTAssertEqual(transport.requestCount, 1)
    }

    func testHTTPConnectionExecutorReconnectRevalidatesBeforeCredentialOrTransport() async throws {
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(
            responses: [
                DNSResolutionResult(
                    addresses: [ResolvedAddress(address: "192.168.1.20")]
                ),
                DNSResolutionResult(
                    addresses: [ResolvedAddress(address: "198.51.100.20")]
                )
            ],
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        let credentialReference = CredentialReference(
            namespace: "portal",
            identifier: "verified-bearer"
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [credentialReference: Data("BEARER-SENTINEL".utf8)],
            recorder: recorder
        )
        let transport = FakeFleetHTTPTransport(
            responses: [FleetHTTPResponse(statusCode: 200)],
            recorder: recorder
        )
        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let connectionExecutor = HTTPConnectionExecutor(
            admission: admission,
            credentialStore: credentialStore,
            transport: transport
        )
        let portalID = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )
        let plan = try OperationPlanner().plan(
            portalID: portalID,
            route: .info,
            method: .get,
            credential: .verifiedBearer
        )
        let request = FleetConnectionRequest(
            admissionRequest: try ConnectionAdmissionRequest(
                rawEndpoint: "portal.local",
                serviceKind: .portal,
                protocolName: "http"
            ),
            routePlan: plan,
            credentialReference: credentialReference
        )
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: "192.168.1.20",
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 800)
        )

        _ = try await connectionExecutor.execute(request)
        recorder.reset()

        do {
            _ = try await connectionExecutor.reconnect(request)
            XCTFail("Reconnect must reject a newly resolved public address.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .lanPolicy(.noPermittedAddress))
        }

        XCTAssertEqual(resolver.requestCount, 2)
        XCTAssertEqual(credentialStore.operations.count, 1)
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(recorder.events(), [.dnsResolve])
    }

    private func makeAdmittedHTTPTransportRequest(
        credential: Data?
    ) throws -> HTTPTransportRequest {
        let endpoint = try LANPolicy.validate(
            hostOrAddress: "192.168.1.20",
            port: 8723,
            source: .manual
        )
        let admitted = AdmittedConnection(
            endpoint: endpoint,
            trustScope: TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: endpoint.hostOrAddress,
                port: endpoint.port
            )
        )
        return HTTPTransportRequest(
            connection: admitted,
            routePlan: RouteCredentialPlan(
                method: .get,
                route: .info,
                credential: .verifiedBearer
            ),
            credential: credential
        )
    }
}


extension PortalManagerTests {
    func testFleetHTTPClientPlacesPairingPINOnlyInTypedJSONBody() async throws {
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"ok":true,"token":"SESSION-SENTINEL","name":"Living Room"}"#.utf8)
                )
            ]
        )
        let credentialStore = FakeCredentialStore()
        let trustStore = FakeTrustWarningStore()
        let client = makeFleetHTTPClient(
            transport: transport,
            credentialStore: credentialStore,
            trustStore: trustStore
        )
        let admissionRequest = try makeFleetAdmissionRequest()
        try await acknowledgeFleetEndpoint(in: trustStore)
        let pin = try PairingPIN("123456")
        let request = try FleetHTTPClientRequest.pairing(
            portalID: testFleetPortalID,
            admissionRequest: admissionRequest,
            pin: pin
        )

        let result = try await client.execute(request)

        XCTAssertEqual(result.statusCode, 200)
        XCTAssertEqual(result.classification, .success)
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(credentialStore.operations.count, 0)
        XCTAssertEqual(transport.requestMetadata[0].route, .remotePair)
        XCTAssertEqual(transport.requestMetadata[0].method, .post)
        XCTAssertFalse(transport.requestMetadata[0].hasCredential)
        XCTAssertEqual(transport.requestMetadata[0].bodyFieldNames, ["pin"])
        XCTAssertTrue(transport.requestMetadata[0].bodyIsJSONObject)
    }

    func testFleetHTTPClientRevalidatesCredentialPlanAndBodyCombinationsBeforeTransport() async throws {
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"ok":true}"#.utf8)
                )
            ]
        )
        let credentialStore = FakeCredentialStore()
        let trustStore = FakeTrustWarningStore()
        let client = makeFleetHTTPClient(
            transport: transport,
            credentialStore: credentialStore,
            trustStore: trustStore
        )
        let admissionRequest = try makeFleetAdmissionRequest()
        try await acknowledgeFleetEndpoint(in: trustStore)
        let pairingPlan = try OperationPlanner().plan(
            portalID: testFleetPortalID,
            route: .remotePair,
            method: .post
        )

        let invalidPairingBody = FleetHTTPClientRequest(
            portalID: testFleetPortalID,
            admissionRequest: admissionRequest,
            routePlan: pairingPlan,
            body: .json(Data(#"{"pin":"123456"}"#.utf8))
        )
        do {
            _ = try await client.execute(invalidPairingBody)
            XCTFail("Pairing must use the typed PIN body case.")
        } catch let error as ManagerError {
            guard case .validation(field: "Fleet request body", _) = error else {
                return XCTFail("Expected a local body validation error, got \(error).")
            }
        }

        let infoPlan = try OperationPlanner().plan(
            portalID: testFleetPortalID,
            route: .info,
            method: .get,
            credential: .verifiedBearer
        )
        let invalidPINRoute = FleetHTTPClientRequest(
            portalID: testFleetPortalID,
            admissionRequest: admissionRequest,
            routePlan: infoPlan,
            credentialReference: .portalCredential(
                portalID: testFleetPortalID,
                kind: .verifiedBearer
            ),
            body: .pairingPIN(try PairingPIN("123456"))
        )
        do {
            _ = try await client.execute(invalidPINRoute)
            XCTFail("A PIN must not be accepted on a bearer-authenticated route.")
        } catch let error as ManagerError {
            guard case .validation(field: "Fleet request body", _) = error else {
                return XCTFail("Expected a local body validation error, got \(error).")
            }
        }

        XCTAssertEqual(transport.requestCount, 0)
        XCTAssertEqual(credentialStore.operations.count, 0)
    }

    func testFleetHTTPClientClassifiesHTTPFailuresWithoutOptimisticSuccess() async throws {
        let statuses = [401, 403, 404, 405, 409, 500, 502, 599]
        let classifier = FleetResponseClassifier()

        for status in statuses {
            let analysis = classifier.analyze(
                FleetHTTPResponse(statusCode: status),
                route: .info
            )
            switch status {
            case 401:
                XCTAssertEqual(analysis.classification, .unauthorized)
                XCTAssertEqual(analysis.classification.managerError, .authentication(.unauthorized))
            case 403:
                XCTAssertEqual(analysis.classification, .forbidden)
                XCTAssertEqual(analysis.classification.managerError, .http(status: 403, code: nil, detail: nil))
            case 404:
                XCTAssertEqual(analysis.classification, .notFound)
            case 405:
                XCTAssertEqual(analysis.classification, .methodNotAllowed)
            case 409:
                XCTAssertEqual(analysis.classification, .conflict)
            default:
                XCTAssertEqual(analysis.classification, .serverFailure(statusCode: status))
            }
            XCTAssertFalse(analysis.classification.isSuccessful)
            XCTAssertNil(analysis.payload)
        }
    }

    func testFleetHTTPClientRejectsRedirectAndSchemaFailuresWithoutChangingConfirmedState() async throws {
        let redirectTransport = FakeFleetHTTPTransport(
            responses: [FleetHTTPResponse(statusCode: 302, headers: ["Location": "http://public.invalid/info"])]
        )
        let credentialReference = CredentialReference.portalCredential(
            portalID: testFleetPortalID,
            kind: .verifiedBearer
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [credentialReference: Data("BEARER-SENTINEL".utf8)]
        )
        let trustStore = FakeTrustWarningStore()
        let redirectClient = makeFleetHTTPClient(
            transport: redirectTransport,
            credentialStore: credentialStore,
            trustStore: trustStore
        )
        let request = try makeFleetInfoRequest(credentialReference: credentialReference)
        try await acknowledgeFleetEndpoint(in: trustStore)

        do {
            _ = try await redirectClient.execute(request)
            XCTFail("Redirect responses must not be treated as successful Fleet operations.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .redirectRejected)
        }
        XCTAssertEqual(redirectTransport.requestCount, 1)
        XCTAssertEqual(redirectTransport.redirectFollowCount, 0)

        let schemaTransport = FakeFleetHTTPTransport(
            responses: [FleetHTTPResponse(statusCode: 200, body: Data("not-json".utf8))]
        )
        let schemaClient = makeFleetHTTPClient(
            transport: schemaTransport,
            credentialStore: credentialStore,
            trustStore: trustStore
        )
        do {
            _ = try await schemaClient.execute(request)
            XCTFail("Malformed successful responses must be rejected as schema failures.")
        } catch let error as ManagerError {
            guard case .validation(field: "Fleet response", _) = error else {
                return XCTFail("Expected a sanitized response validation error, got \(error).")
            }
        }

        let timestamp = Date(timeIntervalSince1970: 900)
        let confirmedEntry = PortalRegistryEntry(
            id: testFleetPortalID,
            connectionState: .online(lastRefresh: timestamp, latencyMs: 12),
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: timestamp,
                responseTimeMilliseconds: 12
            )
        )
        let registry = PortalRegistry(snapshot: RegistrySnapshot(entries: [confirmedEntry]))
        XCTAssertEqual(registry.entry(for: testFleetPortalID), confirmedEntry)
    }

    func testFleetHTTPClientReportsPartialApplyAndPreservesAppliedSchema() async throws {
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"ok":true,"applied":["intervalSec"]}"#.utf8)
                )
            ]
        )
        let credentialReference = CredentialReference.portalCredential(
            portalID: testFleetPortalID,
            kind: .verifiedBearer
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [credentialReference: Data("BEARER-SENTINEL".utf8)]
        )
        let trustStore = FakeTrustWarningStore()
        let client = makeFleetHTTPClient(
            transport: transport,
            credentialStore: credentialStore,
            trustStore: trustStore
        )
        try await acknowledgeFleetEndpoint(in: trustStore)
        let plan = try OperationPlanner().plan(
            portalID: testFleetPortalID,
            route: .remoteSettings,
            method: .post,
            credential: .verifiedBearer
        )
        let request = FleetHTTPClientRequest(
            portalID: testFleetPortalID,
            admissionRequest: try makeFleetAdmissionRequest(),
            routePlan: plan,
            credentialReference: credentialReference,
            body: .json(Data(#"{"domain":"screensaver","values":{"intervalSec":45,"shuffle":true}}"#.utf8)),
            expectedAppliedKeys: ["intervalSec", "shuffle"]
        )

        let result = try await client.execute(request)

        XCTAssertEqual(
            result.classification,
            .partialApply(appliedKeys: ["intervalSec"], omittedKeys: ["shuffle"])
        )
        XCTAssertEqual(credentialStore.operations.count, 1)
        XCTAssertEqual(transport.requestMetadata[0].bodyFieldNames, ["domain", "values"])
        XCTAssertTrue(result.payload != .null)
    }

    func testPairingPINRejectsBlankAndMalformedValuesWithoutCreatingRequestData() {
        XCTAssertThrowsError(try PairingPIN("")) { error in
            XCTAssertEqual(error as? ManagerError, .pairing(.blankPIN))
        }
        XCTAssertThrowsError(try PairingPIN("12-456")) { error in
            XCTAssertEqual(error as? ManagerError, .pairing(.invalidPIN))
        }
        XCTAssertThrowsError(try PairingPIN("12345")) { error in
            XCTAssertEqual(error as? ManagerError, .pairing(.invalidPIN))
        }
        XCTAssertEqual(try? PairingPIN(" 123456 ").rawValue, "123456")
    }

    private var testFleetPortalID: PortalID {
        PortalID(rawValue: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!)
    }

    private func makeFleetAdmissionRequest() throws -> ConnectionAdmissionRequest {
        ConnectionAdmissionRequest(
            endpoint: try LANPolicy.validate(
                hostOrAddress: "192.168.1.20",
                port: 8723,
                source: .manual
            ),
            serviceKind: .portal,
            protocolName: "http"
        )
    }

    private func acknowledgeFleetEndpoint(
        in trustStore: FakeTrustWarningStore
    ) async throws {
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: "192.168.1.20",
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 901)
        )
    }

    private func makeFleetHTTPClient(
        transport: FakeFleetHTTPTransport,
        credentialStore: FakeCredentialStore,
        trustStore: FakeTrustWarningStore
    ) -> FleetHTTPClient {
        FleetHTTPClient(
            transport: transport,
            admission: ConnectionAdmission(
                dnsResolver: FakeDNSResolver(),
                trustWarningStore: trustStore
            ),
            credentialStore: credentialStore
        )
    }

    private func makeFleetInfoRequest(
        credentialReference: CredentialReference
    ) throws -> FleetHTTPClientRequest {
        let plan = try OperationPlanner().plan(
            portalID: testFleetPortalID,
            route: .info,
            method: .get,
            credential: .verifiedBearer
        )
        return FleetHTTPClientRequest(
            portalID: testFleetPortalID,
            admissionRequest: try makeFleetAdmissionRequest(),
            routePlan: plan,
            credentialReference: credentialReference
        )
    }
}


extension PortalManagerTests {
    func testPortalSessionPairingRedeemsOnceAndStoresOnlyRemoteSession() async throws {
        let portalID = testFleetPortalID
        let endpoint = try makeFleetAdmissionRequest().endpoint
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .pairingRequired(endpoint: endpoint),
            endpoint: endpoint
        )
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"ok":true,"token":"REMOTE-TOKEN-SENTINEL"}"#.utf8)
                )
            ]
        )
        let credentialStore = FakeCredentialStore()
        let secureInputStore = FakeSecureInputStore()
        let trustStore = FakeTrustWarningStore()
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [entry])
        )
        let admissionRequest = try makeFleetAdmissionRequest()
        let coordinator = try await makePortalSessionCoordinator(
            portalID: portalID,
            entry: entry,
            transport: transport,
            credentialStore: credentialStore,
            secureInputStore: secureInputStore,
            trustStore: trustStore,
            registryStore: registryStore
        )

        let result = try await coordinator.pair(
            portalID: portalID,
            admissionRequest: admissionRequest,
            pin: "123456"
        )

        XCTAssertEqual(result.credentialReference, remoteReference)
        XCTAssertFalse(result.replacedExistingSession)
        XCTAssertEqual(secureInputStore.activeInputCount, 0)
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertFalse(transport.requestMetadata[0].hasCredential)
        XCTAssertEqual(transport.requestMetadata[0].bodyFieldNames, ["pin"])
        XCTAssertEqual(credentialStore.storedReferences, [remoteReference])
        XCTAssertEqual(
            credentialStore.operations.map(\.operation),
            [.write]
        )

        let persistedEntry = try XCTUnwrap(registryStore.snapshot.entries.first)
        XCTAssertEqual(
            persistedEntry.connectionState,
            .remoteSessionPaired(lastPairedAt: result.pairedAt)
        )
        XCTAssertEqual(persistedEntry.credentialReferences, [remoteReference])

        do {
            _ = try await coordinator.pair(
                portalID: portalID,
                admissionRequest: admissionRequest,
                pin: " "
            )
            XCTFail("Blank PIN input must be rejected before transport.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .pairing(.blankPIN))
        }
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(secureInputStore.activeInputCount, 0)

        do {
            _ = try await coordinator.pair(
                portalID: portalID,
                admissionRequest: admissionRequest,
                pin: "123456"
            )
            XCTFail("An active remote-session reference must reject another redemption.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .pairing(.alreadyRedeemed))
        }
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(secureInputStore.activeInputCount, 0)
    }

    func testPortalSessionInfoRequiresExactBearerReferenceAndPromotesOnlyAfterSuccess() async throws {
        let portalID = testFleetPortalID
        let endpoint = try makeFleetAdmissionRequest().endpoint
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .pairingRequired(endpoint: endpoint),
            endpoint: endpoint
        )
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"name":"Living Room","model":"Meta Portal","device":"portal","apiLevel":29,"ip":"192.168.1.20","port":8723,"capabilities":{}}"#.utf8)
                )
            ]
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [bearerReference: Data("BEARER-SENTINEL".utf8)]
        )
        let trustStore = FakeTrustWarningStore()
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [entry])
        )
        let coordinator = try await makePortalSessionCoordinator(
            portalID: portalID,
            entry: entry,
            transport: transport,
            credentialStore: credentialStore,
            trustStore: trustStore,
            registryStore: registryStore
        )
        let admissionRequest = try makeFleetAdmissionRequest()

        do {
            _ = try await coordinator.verifyBearer(
                portalID: portalID,
                admissionRequest: admissionRequest,
                credentialReference: remoteReference
            )
            XCTFail("A remote-session reference must never be accepted for /info.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .authentication(.invalidCredential))
        }
        do {
            _ = try await coordinator.verifyBearer(
                portalID: portalID,
                admissionRequest: admissionRequest,
                credentialReference: nil
            )
            XCTFail("/info must not be constructed without a supplied bearer reference.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .authentication(.missingCredential))
        }
        XCTAssertEqual(transport.requestCount, 0)

        let classification = try await coordinator.verifyBearer(
            portalID: portalID,
            admissionRequest: admissionRequest,
            credentialReference: bearerReference
        )
        XCTAssertEqual(classification.identity.name, "Living Room")
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertTrue(transport.requestMetadata[0].hasCredential)

        let persistedEntry = try XCTUnwrap(registryStore.snapshot.entries.first)
        XCTAssertEqual(persistedEntry.connectionState, .online(lastRefresh: Date(timeIntervalSince1970: 0), latencyMs: 0))
        XCTAssertEqual(persistedEntry.identity?.name, "Living Room")
        XCTAssertEqual(persistedEntry.credentialReferences, [bearerReference])
    }

    func testPortalSessionRemoteSessionRequiresExactApprovedRouteAndNeverBecomesBearerState() async throws {
        let portalID = testFleetPortalID
        let endpoint = try makeFleetAdmissionRequest().endpoint
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .remoteSessionPaired(lastPairedAt: Date(timeIntervalSince1970: 10)),
            endpoint: endpoint,
            credentialReferences: [remoteReference]
        )
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"ok":true,"domains":[]}"#.utf8)
                )
            ]
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [remoteReference: Data("REMOTE-TOKEN-SENTINEL".utf8)]
        )
        let trustStore = FakeTrustWarningStore()
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [entry])
        )
        let coordinator = try await makePortalSessionCoordinator(
            portalID: portalID,
            entry: entry,
            transport: transport,
            credentialStore: credentialStore,
            trustStore: trustStore,
            registryStore: registryStore
        )
        let admissionRequest = try makeFleetAdmissionRequest()
        let approval = RemoteOperationApproval(
            portalID: portalID,
            route: .remoteSettings,
            method: .get,
            operationID: "settings.read",
            approvedAt: Date(timeIntervalSince1970: 20)
        )

        _ = try await coordinator.execute(
            PortalSessionOperation(
                portalID: portalID,
                admissionRequest: admissionRequest,
                route: .remoteSettings,
                method: .get,
                credential: .remoteSession,
                operationID: "settings.read",
                remoteApproval: approval
            )
        )
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertTrue(transport.requestMetadata[0].hasCredential)

        do {
            _ = try await coordinator.execute(
                portalID: portalID,
                admissionRequest: admissionRequest,
                route: .remoteSettings,
                method: .get,
                credential: .remoteSession
            )
            XCTFail("A remote session must require exact operation approval.")
        } catch let error as ManagerError {
            guard case .validation = error else {
                return XCTFail("Expected a local planning failure, got \(error).")
            }
        }
        do {
            _ = try await coordinator.execute(
                portalID: portalID,
                admissionRequest: admissionRequest,
                route: .info,
                method: .get,
                credential: .remoteSession
            )
            XCTFail("A remote session must never be accepted for /info.")
        } catch let error as ManagerError {
            guard case .validation = error else {
                return XCTFail("Expected a local planning failure, got \(error).")
            }
        }
        XCTAssertEqual(transport.requestCount, 1)

        let persistedEntry = try XCTUnwrap(registryStore.snapshot.entries.first)
        XCTAssertNil(persistedEntry.identity)
        XCTAssertEqual(
            persistedEntry.connectionState,
            .remoteSessionReady(lastReadAt: Date(timeIntervalSince1970: 0))
        )
    }

    func testPortalSession401SuppressesOnlyAffectedCredentialAndPreservesConfirmedMetadata() async throws {
        let portalID = testFleetPortalID
        let endpoint = try makeFleetAdmissionRequest().endpoint
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let timestamp = Date(timeIntervalSince1970: 100)
        let identity = PortalIdentity(
            portalID: portalID,
            name: "Living Room",
            model: "Meta Portal",
            device: "portal"
        )
        let status = PortalStatus(
            reachability: .reachable,
            presence: "home",
            lastUpdatedAt: timestamp,
            responseTimeMilliseconds: 12
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .online(lastRefresh: timestamp, latencyMs: 12),
            identity: identity,
            endpoint: endpoint,
            credentialReferences: [bearerReference, remoteReference],
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: status
        )
        let transport = FakeFleetHTTPTransport(
            responses: [FleetHTTPResponse(statusCode: 401)]
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [
                bearerReference: Data("BEARER-SENTINEL".utf8),
                remoteReference: Data("REMOTE-SENTINEL".utf8)
            ]
        )
        let trustStore = FakeTrustWarningStore()
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [entry])
        )
        let coordinator = try await makePortalSessionCoordinator(
            portalID: portalID,
            entry: entry,
            transport: transport,
            credentialStore: credentialStore,
            trustStore: trustStore,
            registryStore: registryStore
        )
        let admissionRequest = try makeFleetAdmissionRequest()

        do {
            _ = try await coordinator.execute(
                portalID: portalID,
                admissionRequest: admissionRequest,
                route: .screensaver,
                method: .get,
                credential: .verifiedBearer
            )
            XCTFail("A 401 response must fail and suppress the bearer credential.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .authentication(.unauthorized))
        }

        let snapshot = try await coordinator.snapshot(for: portalID)
        XCTAssertEqual(snapshot.availableCredentialKinds, [.verifiedBearer, .remoteSession])
        XCTAssertEqual(snapshot.suppressedCredentialKinds, [.verifiedBearer])
        XCTAssertEqual(transport.requestCount, 1)

        let persistedEntry = try XCTUnwrap(registryStore.snapshot.entries.first)
        XCTAssertEqual(persistedEntry.identity, identity)
        XCTAssertEqual(persistedEntry.endpoint, endpoint)
        XCTAssertEqual(persistedEntry.lastConfirmedStatus, status)
        XCTAssertEqual(persistedEntry.credentialReferences, [bearerReference, remoteReference])
        guard case let .reauthenticationRequired(kind, _) = persistedEntry.connectionState else {
            return XCTFail("A 401 must leave the affected credential in reauthentication state.")
        }
        XCTAssertEqual(kind, .verifiedBearer)

        do {
            _ = try await coordinator.execute(
                portalID: portalID,
                admissionRequest: admissionRequest,
                route: .screensaver,
                method: .get,
                credential: .verifiedBearer
            )
            XCTFail("Suppressed bearer operations must not retry transport.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .authentication(.revokedCredential))
        }
        XCTAssertEqual(transport.requestCount, 1)
    }

    func testPortalSessionReauthenticationVerifiesBeforeKeychainCommitAndClearsSuppression() async throws {
        let portalID = testFleetPortalID
        let endpoint = try makeFleetAdmissionRequest().endpoint
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let timestamp = Date(timeIntervalSince1970: 100)
        let identity = PortalIdentity(
            portalID: portalID,
            name: "Old Name",
            model: "Meta Portal",
            device: "portal"
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .reauthenticationRequired(
                kind: .verifiedBearer,
                reason: "The Portal credential requires authentication again."
            ),
            identity: identity,
            endpoint: endpoint,
            credentialReferences: [bearerReference],
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: timestamp,
                responseTimeMilliseconds: 12
            )
        )
        let recorder = FakeDependencyEventRecorder()
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"name":"New Name","model":"Meta Portal","device":"portal","apiLevel":29,"ip":"192.168.1.20","port":8723,"capabilities":{}}"#.utf8)
                )
            ],
            recorder: recorder
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [bearerReference: Data("OLD-BEARER-SENTINEL".utf8)],
            recorder: recorder
        )
        let secureInputStore = FakeSecureInputStore()
        let trustStore = FakeTrustWarningStore()
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [entry])
        )
        let clock = FakeManagerClock(
            now: Date(timeIntervalSince1970: 200),
            recorder: recorder
        )
        let coordinator = try await makePortalSessionCoordinator(
            portalID: portalID,
            entry: entry,
            transport: transport,
            credentialStore: credentialStore,
            secureInputStore: secureInputStore,
            trustStore: trustStore,
            registryStore: registryStore,
            clock: clock,
            recorder: recorder
        )

        _ = try await coordinator.reauthenticateBearer(
            portalID: portalID,
            admissionRequest: try makeFleetAdmissionRequest(),
            token: "NEW-BEARER-SENTINEL"
        )

        XCTAssertEqual(secureInputStore.activeInputCount, 0)
        let storedBearer = try await credentialStore.read(bearerReference)
        XCTAssertEqual(storedBearer, Data("NEW-BEARER-SENTINEL".utf8))
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertTrue(recorder.occurredInOrder([.transportSend, .credentialRead, .credentialWrite]))

        let persistedEntry = try XCTUnwrap(registryStore.snapshot.entries.first)
        XCTAssertEqual(persistedEntry.connectionState, .online(lastRefresh: Date(timeIntervalSince1970: 200), latencyMs: 0))
        XCTAssertEqual(persistedEntry.identity?.name, "New Name")
        XCTAssertEqual(persistedEntry.credentialReferences, [bearerReference])
        let snapshot = try await coordinator.snapshot(for: portalID)
        XCTAssertTrue(snapshot.suppressedCredentialKinds.isEmpty)
        XCTAssertEqual(snapshot.selectedCredential, .verifiedBearer)
    }

    func testPortalSessionRemovalCleansPortalCredentialsAndSelection() async throws {
        let portalID = testFleetPortalID
        let otherPortalID = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )
        let endpoint = try makeFleetAdmissionRequest().endpoint
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let sourceReference = CredentialReference.sourceCredential(
            portalID: portalID,
            sourceID: "screensaver",
            field: .smbPass
        )
        let otherReference = CredentialReference.portalCredential(
            portalID: otherPortalID,
            kind: .verifiedBearer
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .online(lastRefresh: Date(timeIntervalSince1970: 1), latencyMs: 1),
            endpoint: endpoint,
            credentialReferences: [bearerReference, remoteReference, sourceReference]
        )
        let otherEntry = PortalRegistryEntry(
            id: otherPortalID,
            connectionState: .pairingRequired(endpoint: endpoint),
            endpoint: endpoint,
            credentialReferences: [otherReference]
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [
                bearerReference: Data("BEARER".utf8),
                remoteReference: Data("REMOTE".utf8),
                sourceReference: Data("SOURCE".utf8),
                otherReference: Data("OTHER".utf8)
            ]
        )
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [entry, otherEntry])
        )
        let coordinator = try await makePortalSessionCoordinator(
            portalID: portalID,
            entry: entry,
            transport: FakeFleetHTTPTransport(),
            credentialStore: credentialStore,
            registryStore: registryStore,
            bulkOperationMembership: [portalID, otherPortalID]
        )

        let removal = try await coordinator.remove(portalID: portalID)
        XCTAssertEqual(removal?.portalID, portalID)
        XCTAssertEqual(credentialStore.storedReferences, [otherReference])
        XCTAssertEqual(registryStore.snapshot.entries.map(\.id), [otherPortalID])

        let removedSnapshot = try await coordinator.snapshot(for: portalID)
        XCTAssertTrue(removedSnapshot.availableCredentialKinds.isEmpty)
        XCTAssertNil(removedSnapshot.connectionState)
    }

    private func makePortalSessionCoordinator(
        portalID: PortalID,
        entry: PortalRegistryEntry,
        transport: FakeFleetHTTPTransport,
        credentialStore: FakeCredentialStore,
        secureInputStore: FakeSecureInputStore = FakeSecureInputStore(),
        trustStore: FakeTrustWarningStore = FakeTrustWarningStore(),
        registryStore: FakeRegistryStore,
        clock: FakeManagerClock = FakeManagerClock(),
        recorder: FakeDependencyEventRecorder? = nil,
        bulkOperationMembership: Set<PortalID> = []
    ) async throws -> PortalSessionCoordinator {
        _ = portalID
        _ = entry
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: "192.168.1.20",
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 1)
        )
        let registryCoordinator = PortalRegistryCoordinator(
            registryStore: registryStore,
            credentialStore: credentialStore,
            bulkOperationMembership: bulkOperationMembership
        )
        let client = makeFleetHTTPClient(
            transport: transport,
            credentialStore: credentialStore,
            trustStore: trustStore
        )
        return PortalSessionCoordinator(
            fleetClient: client,
            registryCoordinator: registryCoordinator,
            credentialStore: credentialStore,
            secureInputStore: secureInputStore,
            clock: clock,
            eventSink: FakePortalSessionEventSink()
        )
    }
}


extension PortalManagerTests {
    func testPortalRegistryRemoteSessionDoesNotDowngradeBearerAssurance() throws {
        let portalID = testFleetPortalID
        let timestamp = Date(timeIntervalSince1970: 300)
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let identity = PortalIdentity(
            portalID: portalID,
            name: "Living Room",
            model: "Meta Portal",
            device: "portal"
        )
        let endpoint = try makeFleetAdmissionRequest().endpoint
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .online(lastRefresh: timestamp, latencyMs: 8),
            identity: identity,
            endpoint: endpoint,
            credentialReferences: [bearerReference],
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: timestamp,
                responseTimeMilliseconds: 8
            )
        )
        var registry = PortalRegistry(snapshot: RegistrySnapshot(entries: [entry]))

        let updated = registry.recordRemoteSession(
            for: portalID,
            credentialReference: remoteReference,
            pairedAt: Date(timeIntervalSince1970: 301)
        )

        XCTAssertEqual(updated?.connectionState, entry.connectionState)
        XCTAssertEqual(updated?.identity, identity)
        XCTAssertEqual(updated?.credentialReferences, [bearerReference, remoteReference])
    }
}


extension PortalManagerTests {
    func testBonjourServiceCarriesOnlyUntrustedResolvedMetadata() throws {
        let candidate = BonjourService(
            serviceName: "Living Room Portal",
            resolvedHostOrAddress: "192.168.1.20",
            port: 8723,
            interfaceName: "en0",
            source: .mdns(serviceName: "Living Room Portal")
        )

        XCTAssertEqual(candidate.serviceName, "Living Room Portal")
        XCTAssertEqual(candidate.hostOrAddress, "192.168.1.20")
        XCTAssertEqual(candidate.port, 8723)
        XCTAssertEqual(candidate.interfaceName, "en0")
        XCTAssertEqual(candidate.source, .mdns(serviceName: "Living Room Portal"))
        XCTAssertTrue(candidate.isResolved)
        XCTAssertNil(candidate.resolutionError)

        let serialized = String(
            decoding: try JSONEncoder().encode(candidate),
            as: UTF8.self
        )
        XCTAssertFalse(serialized.contains("credential"))
        XCTAssertFalse(serialized.contains("authorization"))
        XCTAssertFalse(serialized.contains("TOKEN"))
    }

    func testFakeBonjourBrowserCarriesResolutionAndRefreshLifecycleEvents() async throws {
        let candidate = BonjourService(
            serviceName: "Office Portal",
            resolvedHostOrAddress: "10.0.0.25",
            port: 8723,
            interfaceName: "en1"
        )
        let failedCandidate = BonjourService(
            serviceName: "Offline Portal",
            interfaceName: "en1",
            resolutionError: .timedOut
        )
        let browser = FakeBonjourBrowser(
            pendingEvents: [
                .found(candidate),
                .resolutionFailed(failedCandidate)
            ]
        )
        let stream = browser.events()
        let collector = Task { () -> [BonjourEvent] in
            var events: [BonjourEvent] = []
            for await event in stream {
                events.append(event)
            }
            return events
        }

        try await browser.start()
        try await browser.refresh()
        await browser.stop()

        let events = await collector.value
        XCTAssertEqual(events.count, 3)
        XCTAssertEqual(events[0], .found(candidate))
        XCTAssertEqual(events[1], .resolutionFailed(failedCandidate))
        XCTAssertEqual(events[2], .state(.refreshing))
        XCTAssertTrue(browser.isStarted)
        XCTAssertTrue(browser.isStopped)
        XCTAssertEqual(
            browser.eventMetadata.map(\.kind),
            [.found, .resolutionFailed, .state]
        )
    }

    func testNWBonjourBrowserUsesImmortalServiceType() {
        XCTAssertEqual(NWBonjourBrowser.serviceType, "_immortal-remote._tcp.")
    }
}

extension PortalManagerTests {
    func testDiscoveryCandidateAdmissionNormalizesMetadataAndDerivesPortalScope() async throws {
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(
            responses: [
                DNSResolutionResult(
                    addresses: [ResolvedAddress(address: "192.168.1.20")]
                )
            ],
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let normalizer = DiscoveryCandidateNormalizer(connectionAdmission: admission)
        let candidate = BonjourService(
            serviceName: "  Living Room  ",
            resolvedHostOrAddress: "portal.local.",
            port: 8723,
            interfaceName: "en0"
        )

        let request = try normalizer.normalize(candidate)
        XCTAssertEqual(request.serviceKind, .portal)
        XCTAssertEqual(request.protocolName, "http")
        XCTAssertEqual(request.endpoint.hostOrAddress, "portal.local.")
        XCTAssertEqual(request.endpoint.addressFamily, .hostname)
        XCTAssertEqual(request.endpoint.interfaceZone, "en0")
        XCTAssertEqual(request.endpoint.port, 8723)
        XCTAssertEqual(
            request.endpoint.source,
            .mdns(serviceName: "Living Room")
        )

        let expectedScope = TrustWarningScope(
            serviceKind: .portal,
            protocolName: "HTTP",
            resolvedHostOrAddress: "192.168.1.20",
            port: 8723
        )
        try await trustStore.acknowledge(
            expectedScope,
            at: Date(timeIntervalSince1970: 500)
        )
        recorder.reset()

        let admitted = try await normalizer.admit(candidate)
        XCTAssertEqual(admitted.serviceName, "Living Room")
        XCTAssertEqual(admitted.interfaceName, "en0")
        XCTAssertEqual(admitted.endpoint.hostOrAddress, "192.168.1.20")
        XCTAssertEqual(admitted.endpoint.addressFamily, .ipv4)
        XCTAssertNil(admitted.endpoint.interfaceZone)
        XCTAssertEqual(admitted.endpoint.source, .mdns(serviceName: "Living Room"))
        XCTAssertEqual(admitted.trustScope, expectedScope)
        XCTAssertEqual(
            recorder.events(),
            [.dnsResolve, .trustWarningLookup]
        )
    }

    func testDiscoveryCandidateAdmissionPreservesBonjourIPv6ZoneThroughDNSAndScope() async throws {
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(
            responses: [
                DNSResolutionResult(
                    addresses: [ResolvedAddress(address: "FE80::20")]
                )
            ],
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let normalizer = DiscoveryCandidateNormalizer(connectionAdmission: admission)
        let candidate = BonjourService(
            serviceName: "Kitchen Portal",
            resolvedHostOrAddress: "portal.local",
            port: 8723,
            interfaceName: "en0"
        )
        let expectedScope = TrustWarningScope(
            serviceKind: .portal,
            protocolName: "http",
            resolvedHostOrAddress: "fe80::20",
            port: 8723,
            interfaceZone: "en0"
        )
        try await trustStore.acknowledge(
            expectedScope,
            at: Date(timeIntervalSince1970: 501)
        )
        recorder.reset()

        let admitted = try await normalizer.admit(candidate)

        XCTAssertEqual(admitted.endpoint.hostOrAddress, "FE80::20")
        XCTAssertEqual(admitted.endpoint.addressFamily, .ipv6)
        XCTAssertEqual(admitted.endpoint.interfaceZone, "en0")
        XCTAssertEqual(admitted.trustScope, expectedScope)
        XCTAssertEqual(recorder.events(), [.dnsResolve, .trustWarningLookup])
    }

    func testDiscoveryCandidateAdmissionAcceptsPrivateAndIPv6LiteralResults() async throws {
        let cases: [(host: String, zone: String?, family: AddressFamily)] = [
            ("127.0.0.1", nil, .ipv4),
            ("10.0.0.15", nil, .ipv4),
            ("169.254.10.15", nil, .ipv4),
            ("::1", nil, .ipv6),
            ("fc00::15", nil, .ipv6),
            ("FE80::15", "en0", .ipv6)
        ]

        for (index, item) in cases.enumerated() {
            let trustStore = FakeTrustWarningStore()
            let admission = ConnectionAdmission(
                dnsResolver: FakeDNSResolver(),
                trustWarningStore: trustStore
            )
            let normalizer = DiscoveryCandidateNormalizer(connectionAdmission: admission)
            let candidate = BonjourService(
                serviceName: "Portal \(index)",
                resolvedHostOrAddress: item.host,
                port: 8723,
                interfaceName: item.zone
            )
            let expectedScope = TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: item.host,
                port: 8723,
                interfaceZone: item.zone
            )
            try await trustStore.acknowledge(
                expectedScope,
                at: Date(timeIntervalSince1970: TimeInterval(index))
            )

            let admitted = try await normalizer.admit(candidate)

            XCTAssertEqual(admitted.endpoint.addressFamily, item.family)
            XCTAssertEqual(
                admitted.endpoint.hostOrAddress.lowercased(),
                item.host.lowercased()
            )
            XCTAssertEqual(admitted.endpoint.interfaceZone, item.zone)
            XCTAssertEqual(admitted.trustScope, expectedScope)
        }
    }

    func testDiscoveryPublicOrUnresolvedCandidatesFailBeforeCredentialOrProbe() async throws {
        let publicRecorder = FakeDependencyEventRecorder()
        let publicResolver = FakeDNSResolver(recorder: publicRecorder)
        let publicTrustStore = FakeTrustWarningStore(recorder: publicRecorder)
        let publicCredentialStore = FakeCredentialStore(recorder: publicRecorder)
        let publicAdmission = ConnectionAdmission(
            dnsResolver: publicResolver,
            trustWarningStore: publicTrustStore
        )
        let publicNormalizer = DiscoveryCandidateNormalizer(
            connectionAdmission: publicAdmission
        )
        let publicCandidate = BonjourService(
            serviceName: "Untrusted Portal",
            resolvedHostOrAddress: "203.0.113.10",
            port: 8723
        )
        let publicRequest = try publicNormalizer.normalize(publicCandidate)
        let publicProbeRecorder = FakeDependencyEventRecorder()

        do {
            _ = try await publicAdmission.withAdmittedConnection(
                publicRequest,
                credentialReference: CredentialReference(
                    namespace: "portal",
                    identifier: "public-candidate"
                ),
                credentialStore: publicCredentialStore
            ) { _, _ in
                publicProbeRecorder.append(.transportSend)
                return true
            }
            XCTFail("A public discovery candidate must be rejected.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .lanPolicy(.publicAddress))
        }

        XCTAssertEqual(publicRecorder.events(), [])
        XCTAssertEqual(publicProbeRecorder.events(), [])
        XCTAssertEqual(publicCredentialStore.operations.count, 0)

        let dnsRecorder = FakeDependencyEventRecorder()
        let dnsResolver = FakeDNSResolver(
            responses: [
                DNSResolutionResult(
                    addresses: [ResolvedAddress(address: "198.51.100.12")]
                )
            ],
            recorder: dnsRecorder
        )
        let dnsTrustStore = FakeTrustWarningStore(recorder: dnsRecorder)
        let dnsCredentialStore = FakeCredentialStore(recorder: dnsRecorder)
        let dnsAdmission = ConnectionAdmission(
            dnsResolver: dnsResolver,
            trustWarningStore: dnsTrustStore
        )
        let dnsNormalizer = DiscoveryCandidateNormalizer(
            connectionAdmission: dnsAdmission
        )
        let dnsRequest = try dnsNormalizer.normalize(
            BonjourService(
                serviceName: "Public DNS Portal",
                resolvedHostOrAddress: "portal.example",
                port: 8723
            )
        )
        let dnsProbeRecorder = FakeDependencyEventRecorder()

        do {
            _ = try await dnsAdmission.withAdmittedConnection(
                dnsRequest,
                credentialReference: CredentialReference(
                    namespace: "portal",
                    identifier: "dns-candidate"
                ),
                credentialStore: dnsCredentialStore
            ) { _, _ in
                dnsProbeRecorder.append(.transportSend)
                return true
            }
            XCTFail("A DNS result with no permitted address must be rejected.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .lanPolicy(.noPermittedAddress))
        }

        XCTAssertFalse(dnsProbeRecorder.events().contains(.transportSend))
        XCTAssertEqual(dnsRecorder.events(), [.dnsResolve])
        XCTAssertEqual(dnsCredentialStore.operations.count, 0)
    }

    func testDiscoveryCandidateNormalizationRejectsInvalidOrUnresolvedMetadata() throws {
        let admission = ConnectionAdmission(
            dnsResolver: FakeDNSResolver(),
            trustWarningStore: FakeTrustWarningStore()
        )
        let normalizer = DiscoveryCandidateNormalizer(connectionAdmission: admission)

        XCTAssertThrowsError(
            try normalizer.normalize(
                BonjourService(
                    serviceName: "Missing Host",
                    resolvedHostOrAddress: nil,
                    port: 8723
                )
            )
        ) { error in
            XCTAssertEqual(error as? ManagerError, .resolution(.noAddresses))
        }

        XCTAssertThrowsError(
            try normalizer.normalize(
                BonjourService(
                    serviceName: "Missing Port",
                    resolvedHostOrAddress: "192.168.1.20",
                    port: 0
                )
            )
        ) { error in
            XCTAssertEqual(error as? ManagerError, .lanPolicy(.invalidPort))
        }

        XCTAssertThrowsError(
            try normalizer.normalize(
                BonjourService(
                    serviceName: "Failed Resolution",
                    resolvedHostOrAddress: nil,
                    port: 8723,
                    resolutionError: .timedOut
                )
            )
        ) { error in
            XCTAssertEqual(error as? ManagerError, .resolution(.failed))
        }
    }
}


extension PortalManagerTests {
    func testDiscoveryStartAndRefreshRetainManagedEntries() async throws {
        let portalID = testFleetPortalID
        let timestamp = Date(timeIntervalSince1970: 10)
        let endpoint = try discoveryEndpoint(
            "192.168.1.20",
            source: .authenticatedRefresh,
            authenticatedAt: timestamp
        )
        let identity = PortalIdentity(
            portalID: portalID,
            name: "Living Room",
            model: "Meta Portal",
            device: "device-1"
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .offline(
                lastContact: timestamp,
                reason: "The Portal is temporarily offline."
            ),
            identity: identity,
            endpoint: endpoint,
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: timestamp
            )
        )
        let browser = FakeBonjourBrowser()
        let (coordinator, _) = makeDiscoveryCoordinator(
            browser: browser,
            registryEntries: [entry]
        )

        try await coordinator.start()
        try await coordinator.refresh()

        XCTAssertTrue(browser.isStarted)
        XCTAssertTrue(
            browser.eventMetadata.contains { $0.kind == .state }
        )
        let managedEntries = try await coordinator.managedEntries()
        XCTAssertEqual(managedEntries, [entry])

        await coordinator.stop()
        XCTAssertTrue(browser.isStopped)
    }

    func testDiscoveryWithoutBearerCredentialDoesNotProbeAndPreservesRemoteSessionAssurance() async throws {
        let portalID = testFleetPortalID
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let timestamp = Date(timeIntervalSince1970: 20)
        let endpoint = try discoveryEndpoint("192.168.1.20")
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .remoteSessionPaired(lastPairedAt: timestamp),
            endpoint: endpoint,
            credentialReferences: [remoteReference]
        )
        let candidate = discoveryCandidate(
            serviceName: "Living Room",
            host: "192.168.1.20"
        )
        let browser = FakeBonjourBrowser(pendingEvents: [.found(candidate)])
        let trustStore = FakeTrustWarningStore()
        try await trustStore.acknowledge(
            discoveryTrustScope(for: endpoint),
            at: timestamp
        )
        let credentialStore = FakeCredentialStore()
        let transport = FakeFleetHTTPTransport()
        let (coordinator, _) = makeDiscoveryCoordinator(
            browser: browser,
            trustStore: trustStore,
            credentialStore: credentialStore,
            registryEntries: [entry],
            transport: transport
        )

        try await coordinator.start()
        let processed = await waitForDiscovery {
            guard let snapshot = await coordinator.candidates().first else {
                return false
            }
            guard snapshot.isPresent, snapshot.portalID == portalID else {
                return false
            }
            if case .remoteSessionPaired = snapshot.connectionState {
                return true
            }
            return false
        }

        XCTAssertTrue(processed)
        XCTAssertEqual(transport.requestCount, 0)
        XCTAssertTrue(credentialStore.operations.isEmpty)
        let managedEntries = try await coordinator.managedEntries()
        XCTAssertEqual(managedEntries.first?.connectionState, entry.connectionState)

        await coordinator.stop()
    }

    func testDiscoveryBearerProbePromotesOnlyAuthenticatedInfo() async throws {
        let portalID = testFleetPortalID
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let timestamp = Date(timeIntervalSince1970: 100)
        let admittedEndpoint = try discoveryEndpoint("192.168.1.20")
        let candidate = discoveryCandidate(
            serviceName: "Living Room",
            host: admittedEndpoint.hostOrAddress
        )
        let trustStore = FakeTrustWarningStore()
        try await trustStore.acknowledge(
            discoveryTrustScope(for: admittedEndpoint),
            at: timestamp
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [bearerReference: Data("BEARER-SENTINEL".utf8)]
        )
        let transport = FakeFleetHTTPTransport(
            responses: [
                discoveryInfoResponse(
                    name: "Living Room",
                    model: "Meta Portal",
                    device: "device-1",
                    ip: "192.168.1.21"
                )
            ]
        )
        let (coordinator, _) = makeDiscoveryCoordinator(
            browser: FakeBonjourBrowser(),
            trustStore: trustStore,
            credentialStore: credentialStore,
            clock: FakeManagerClock(now: timestamp),
            transport: transport
        )

        let snapshot = try await coordinator.probe(
            candidate,
            portalID: portalID,
            credentialReference: bearerReference
        )

        guard case .online(let lastRefresh, let latency) = snapshot.connectionState else {
            return XCTFail("A successful bearer /info probe must produce online state.")
        }
        XCTAssertEqual(lastRefresh, timestamp)
        XCTAssertEqual(latency, 0)
        XCTAssertEqual(snapshot.candidate.source, .mdns(serviceName: "Living Room"))
        XCTAssertEqual(snapshot.endpoint?.hostOrAddress, "192.168.1.21")
        XCTAssertEqual(snapshot.endpoint?.source, .authenticatedRefresh)
        XCTAssertEqual(snapshot.endpoint?.lastAuthenticatedAt, timestamp)
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(transport.requestMetadata[0].route, .info)
        XCTAssertEqual(transport.requestMetadata[0].method, .get)
        XCTAssertTrue(transport.requestMetadata[0].hasCredential)
        XCTAssertEqual(credentialStore.operations.map(\.reference), [bearerReference])

        let entries = try await coordinator.managedEntries()
        let entry = try XCTUnwrap(entries.first)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entry.id, portalID)
        XCTAssertEqual(entry.identity?.name, "Living Room")
        XCTAssertEqual(entry.identity?.device, "device-1")
        XCTAssertEqual(entry.endpoint?.hostOrAddress, "192.168.1.21")
        XCTAssertEqual(entry.credentialReferences, [bearerReference])
    }

    func testDiscoveryAuthenticatedProbeMergesStableIdentityDuplicatesAndPrefersNewestEndpoint() async throws {
        let canonicalID = testFleetPortalID
        let duplicateID = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )
        let canonicalBearer = CredentialReference.portalCredential(
            portalID: canonicalID,
            kind: .verifiedBearer
        )
        let duplicateBearer = CredentialReference.portalCredential(
            portalID: duplicateID,
            kind: .verifiedBearer
        )
        let firstTimestamp = Date(timeIntervalSince1970: 10)
        let secondTimestamp = Date(timeIntervalSince1970: 20)
        let probeTimestamp = Date(timeIntervalSince1970: 200)
        let firstEndpoint = try discoveryEndpoint(
            "192.168.1.20",
            source: .authenticatedRefresh,
            authenticatedAt: firstTimestamp
        )
        let secondEndpoint = try discoveryEndpoint(
            "192.168.1.19",
            source: .authenticatedRefresh,
            authenticatedAt: secondTimestamp
        )
        let duplicateIdentity = PortalIdentity(
            portalID: duplicateID,
            name: "Living Room (old)",
            model: "Meta Portal",
            device: "device-1"
        )
        let firstEntry = PortalRegistryEntry(
            id: canonicalID,
            connectionState: .online(lastRefresh: firstTimestamp, latencyMs: 4),
            identity: PortalIdentity(
                portalID: canonicalID,
                name: "Living Room",
                model: "Meta Portal",
                device: "device-1"
            ),
            endpoint: firstEndpoint,
            credentialReferences: [canonicalBearer],
            lastSuccessfulContact: firstTimestamp
        )
        let secondEntry = PortalRegistryEntry(
            id: duplicateID,
            connectionState: .online(lastRefresh: secondTimestamp, latencyMs: 5),
            identity: duplicateIdentity,
            endpoint: secondEndpoint,
            credentialReferences: [duplicateBearer],
            lastSuccessfulContact: secondTimestamp
        )
        let candidate = discoveryCandidate(
            serviceName: "Living Room",
            host: firstEndpoint.hostOrAddress
        )
        let trustStore = FakeTrustWarningStore()
        try await trustStore.acknowledge(
            discoveryTrustScope(for: firstEndpoint),
            at: probeTimestamp
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [canonicalBearer: Data("BEARER-SENTINEL".utf8)]
        )
        let transport = FakeFleetHTTPTransport(
            responses: [
                discoveryInfoResponse(
                    name: "Living Room",
                    model: "Meta Portal",
                    device: "device-1",
                    ip: "192.168.1.21"
                )
            ]
        )
        let (coordinator, _) = makeDiscoveryCoordinator(
            browser: FakeBonjourBrowser(),
            trustStore: trustStore,
            credentialStore: credentialStore,
            registryEntries: [firstEntry, secondEntry],
            clock: FakeManagerClock(now: probeTimestamp),
            transport: transport
        )

        _ = try await coordinator.probe(
            candidate,
            portalID: canonicalID,
            credentialReference: canonicalBearer
        )

        let entries = try await coordinator.managedEntries()
        let merged = try XCTUnwrap(entries.first)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(merged.id, canonicalID)
        XCTAssertEqual(
            Set(merged.credentialReferences),
            [canonicalBearer, duplicateBearer]
        )
        XCTAssertEqual(merged.endpoint?.hostOrAddress, "192.168.1.21")
        XCTAssertEqual(merged.endpoint?.source, .authenticatedRefresh)
        XCTAssertEqual(merged.endpoint?.lastAuthenticatedAt, probeTimestamp)
        XCTAssertEqual(
            Set(merged.discoveredEndpoints.map(\.hostOrAddress)),
            ["192.168.1.19", "192.168.1.20", "192.168.1.21"]
        )
    }

    func testDiscoveryBonjourRemovalMarksEstablishedEntryOfflineWithoutDeletingMetadata() async throws {
        let portalID = testFleetPortalID
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let timestamp = Date(timeIntervalSince1970: 300)
        let endpoint = try discoveryEndpoint(
            "192.168.1.20",
            source: .authenticatedRefresh,
            authenticatedAt: timestamp
        )
        let identity = PortalIdentity(
            portalID: portalID,
            name: "Living Room",
            model: "Meta Portal",
            device: "device-1"
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .remoteSessionPaired(lastPairedAt: timestamp),
            identity: identity,
            endpoint: endpoint,
            credentialReferences: [remoteReference],
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: PortalStatus(
                reachability: .reachable,
                lastUpdatedAt: timestamp,
                responseTimeMilliseconds: 8
            )
        )
        let candidate = discoveryCandidate(
            serviceName: "Living Room",
            host: endpoint.hostOrAddress
        )
        let browser = FakeBonjourBrowser(pendingEvents: [.found(candidate)])
        let trustStore = FakeTrustWarningStore()
        try await trustStore.acknowledge(
            discoveryTrustScope(for: endpoint),
            at: timestamp
        )
        let (coordinator, _) = makeDiscoveryCoordinator(
            browser: browser,
            trustStore: trustStore,
            registryEntries: [entry]
        )

        try await coordinator.start()
        let found = await waitForDiscovery {
            await coordinator.candidates().first?.isPresent == true
        }
        XCTAssertTrue(found)

        browser.enqueue(.removed(candidate))
        let markedOffline = await waitForDiscovery {
            guard let current = (try? await coordinator.managedEntries())?.first else {
                return false
            }
            if case .offline = current.connectionState {
                return true
            }
            return false
        }

        XCTAssertTrue(markedOffline)
        let snapshots = await coordinator.candidates()
        let snapshot = try XCTUnwrap(snapshots.first)
        XCTAssertFalse(snapshot.isPresent)
        XCTAssertEqual(snapshot.portalID, portalID)
        let persistedEntries = try await coordinator.managedEntries()
        let persisted = try XCTUnwrap(persistedEntries.first)
        XCTAssertEqual(persisted.identity, identity)
        XCTAssertEqual(persisted.endpoint, endpoint)
        XCTAssertEqual(persisted.credentialReferences, [remoteReference])
        XCTAssertEqual(persisted.lastSuccessfulContact, timestamp)
        XCTAssertEqual(
            persisted.lastConfirmedStatus?.responseTimeMilliseconds,
            8
        )

        await coordinator.stop()
    }

    func testDiscoveryProbeFailurePreservesConfirmedStateAndMarksOnlyReachabilityOffline() async throws {
        let portalID = testFleetPortalID
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let timestamp = Date(timeIntervalSince1970: 400)
        let endpoint = try discoveryEndpoint(
            "192.168.1.20",
            source: .authenticatedRefresh,
            authenticatedAt: timestamp
        )
        let alternateEndpoint = try discoveryEndpoint(
            "192.168.1.21",
            source: .manual,
            authenticatedAt: Date(timeIntervalSince1970: 350)
        )
        let identity = PortalIdentity(
            portalID: portalID,
            name: "Living Room",
            model: "Meta Portal",
            device: "device-1"
        )
        let status = PortalStatus(
            reachability: .reachable,
            presence: "home",
            screenState: "active",
            lastUpdatedAt: timestamp,
            responseTimeMilliseconds: 12
        )
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .online(lastRefresh: timestamp, latencyMs: 12),
            identity: identity,
            endpoint: endpoint,
            discoveredEndpoints: [alternateEndpoint],
            credentialReferences: [bearerReference],
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: status
        )
        let candidate = discoveryCandidate(
            serviceName: "Living Room",
            host: endpoint.hostOrAddress
        )
        let browser = FakeBonjourBrowser(pendingEvents: [.found(candidate)])
        let trustStore = FakeTrustWarningStore()
        try await trustStore.acknowledge(
            discoveryTrustScope(for: endpoint),
            at: timestamp
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [bearerReference: Data("BEARER-SENTINEL".utf8)]
        )
        let transport = FakeFleetHTTPTransport(
            failures: [.configuredFailure(.fleetHTTP)]
        )
        let (coordinator, _) = makeDiscoveryCoordinator(
            browser: browser,
            trustStore: trustStore,
            credentialStore: credentialStore,
            registryEntries: [entry],
            transport: transport
        )

        try await coordinator.start()
        let failed = await waitForDiscovery {
            guard let current = (try? await coordinator.managedEntries())?.first else {
                return false
            }
            if case .offline = current.connectionState {
                return true
            }
            return false
        }

        XCTAssertTrue(failed)
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(credentialStore.operations.map(\.reference), [bearerReference])
        let persistedEntries = try await coordinator.managedEntries()
        let persisted = try XCTUnwrap(persistedEntries.first)
        XCTAssertEqual(persisted.identity, identity)
        XCTAssertEqual(persisted.endpoint, endpoint)
        XCTAssertEqual(persisted.discoveredEndpoints, [alternateEndpoint])
        XCTAssertEqual(persisted.credentialReferences, [bearerReference])
        XCTAssertEqual(persisted.lastSuccessfulContact, timestamp)
        XCTAssertEqual(persisted.lastConfirmedStatus, status)
        guard case .offline(let lastContact, let reason) = persisted.connectionState else {
            return XCTFail("A failed probe must leave the established entry offline.")
        }
        XCTAssertEqual(lastContact, timestamp)
        XCTAssertEqual(reason, "The Portal could not be reached during discovery.")

        await coordinator.stop()
    }

    func testDiscoveryAdmissionCompletesDNSAndLANChecksBeforeCredentialAndTransport() async throws {
        let portalID = testFleetPortalID
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let timestamp = Date(timeIntervalSince1970: 500)
        let resolvedEndpoint = try discoveryEndpoint("192.168.1.20")
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .online(lastRefresh: timestamp, latencyMs: 0),
            identity: PortalIdentity(
                portalID: portalID,
                name: "Living Room",
                model: "Meta Portal",
                device: "device-1"
            ),
            endpoint: resolvedEndpoint,
            credentialReferences: [bearerReference],
            lastSuccessfulContact: timestamp
        )
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(
            responses: [
                DNSResolutionResult(
                    addresses: [ResolvedAddress(address: resolvedEndpoint.hostOrAddress)]
                )
            ],
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        try await trustStore.acknowledge(
            discoveryTrustScope(for: resolvedEndpoint),
            at: timestamp
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [bearerReference: Data("BEARER-SENTINEL".utf8)],
            recorder: recorder
        )
        let transport = FakeFleetHTTPTransport(
            responses: [
                discoveryInfoResponse(
                    name: "Living Room",
                    model: "Meta Portal",
                    device: "device-1",
                    ip: resolvedEndpoint.hostOrAddress
                )
            ],
            recorder: recorder
        )
        let browser = FakeBonjourBrowser(
            pendingEvents: [
                .found(
                    discoveryCandidate(
                        serviceName: "Living Room",
                        host: "portal.local"
                    )
                )
            ],
            recorder: recorder
        )
        let (coordinator, _) = makeDiscoveryCoordinator(
            browser: browser,
            resolver: resolver,
            trustStore: trustStore,
            credentialStore: credentialStore,
            registryEntries: [entry],
            transport: transport
        )

        let snapshot = try await coordinator.probe(
            discoveryCandidate(
                serviceName: "Living Room",
                host: "portal.local"
            ),
            portalID: portalID,
            credentialReference: bearerReference
        )
        XCTAssertEqual(snapshot.portalID, portalID)
        XCTAssertEqual(transport.requestCount, 1)

        let events = recorder.events()
        guard let dnsIndex = events.firstIndex(of: .dnsResolve),
              let trustIndex = events.firstIndex(of: .trustWarningLookup),
              let credentialIndex = events.firstIndex(of: .credentialRead),
              let transportIndex = events.firstIndex(of: .transportSend) else {
            XCTFail("Expected DNS, trust, credential, and transport events. Actual: \(events)")
            return
        }
        XCTAssertLessThan(dnsIndex, credentialIndex)
        XCTAssertLessThan(trustIndex, credentialIndex)
        XCTAssertLessThan(credentialIndex, transportIndex)

        await coordinator.stop()
    }
}

extension PortalManagerTests {
    func testManualEndpointParserRetainsDefaultPortIPv6BracketsAndZones() throws {
        let parser = ManualEndpointParser()

        let hostname = try parser.parse("portal.local")
        XCTAssertEqual(hostname.port, 8723)
        XCTAssertEqual(hostname.addressFamily, .hostname)
        XCTAssertEqual(hostname.source, .manual)

        let explicitPort = try parser.parse("portal.local:9000")
        XCTAssertEqual(explicitPort.port, 9000)
        XCTAssertEqual(explicitPort.source, .manual)

        let bracketed = try parser.parse("[fe80::1]")
        XCTAssertEqual(bracketed.hostOrAddress, "fe80::1")
        XCTAssertEqual(bracketed.port, 8723)
        XCTAssertEqual(bracketed.addressFamily, .ipv6)
        XCTAssertNil(bracketed.interfaceZone)

        let nativeZone = try parser.parse("[fe80::1%en0]:8724")
        XCTAssertEqual(nativeZone.port, 8724)
        XCTAssertEqual(nativeZone.addressFamily, .ipv6)
        XCTAssertEqual(nativeZone.interfaceZone, "en0")
        XCTAssertEqual(nativeZone.source, .manual)

        let encodedZone = try parser.parse("[fe80::1%25en0]:8725")
        XCTAssertEqual(encodedZone.port, 8725)
        XCTAssertEqual(encodedZone.interfaceZone, "en0")
        XCTAssertEqual(encodedZone.source, .manual)

        XCTAssertThrowsError(try parser.parse("fe80::1:8723")) { error in
            XCTAssertEqual(error as? ManualEndpointParserError, .ambiguousIPv6)
        }
    }

    func testManualBearerVerificationUsesAdmissionOrderAndCommitsTheAdmittedManualEndpoint() async throws {
        let portalID = testFleetPortalID
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let oldEndpoint = try LANPolicy.validate(
            hostOrAddress: "192.168.1.19",
            source: .authenticatedRefresh,
            lastAuthenticatedAt: Date(timeIntervalSince1970: 10)
        )
        let existingEntry = PortalRegistryEntry(
            id: portalID,
            connectionState: .pairingRequired(endpoint: oldEndpoint),
            endpoint: oldEndpoint
        )
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(
            responses: [
                DNSResolutionResult(addresses: [ResolvedAddress(address: "192.168.1.20")]),
                DNSResolutionResult(addresses: [ResolvedAddress(address: "192.168.1.20")])
            ],
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: "192.168.1.20",
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 20)
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [bearerReference: Data("BEARER-SENTINEL".utf8)],
            recorder: recorder
        )
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"name":"Living Room","model":"Meta Portal","device":"portal","apiLevel":29,"ip":"192.168.1.99","port":8723,"capabilities":{}}"#.utf8)
                )
            ],
            recorder: recorder
        )
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [existingEntry]),
            recorder: recorder
        )
        let clock = FakeManagerClock(
            now: Date(timeIntervalSince1970: 100),
            recorder: recorder
        )
        let coordinator = makeManualCoordinator(
            resolver: resolver,
            trustStore: trustStore,
            credentialStore: credentialStore,
            transport: transport,
            registryStore: registryStore,
            clock: clock
        )

        let result = try await coordinator.verifyBearer(
            rawEndpoint: "portal.local",
            for: portalID,
            credentialReference: bearerReference
        )

        XCTAssertEqual(result.admission.request.endpoint.port, 8723)
        XCTAssertEqual(result.admission.endpoint.hostOrAddress, "192.168.1.20")
        XCTAssertEqual(result.admission.endpoint.source, .manual)
        XCTAssertEqual(result.entry.endpoint?.hostOrAddress, "192.168.1.20")
        XCTAssertEqual(result.entry.endpoint?.source, .manual)
        XCTAssertEqual(result.entry.endpoint?.lastAuthenticatedAt, clock.now)
        XCTAssertEqual(result.entry.identity?.name, "Living Room")
        XCTAssertEqual(result.entry.credentialReferences, [bearerReference])
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(transport.requestMetadata[0].route, .info)
        XCTAssertTrue(transport.requestMetadata[0].hasCredential)
        XCTAssertEqual(credentialStore.operations.map(\.reference), [bearerReference])
        XCTAssertTrue(
            recorder.occurredInOrder([
                .dnsResolve,
                .trustWarningLookup,
                .credentialRead,
                .transportSend
            ])
        )
    }

    func testManualBearerFailurePreservesConfirmedIdentityEndpointStatusAndCredentials() async throws {
        let portalID = testFleetPortalID
        let bearerReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .verifiedBearer
        )
        let timestamp = Date(timeIntervalSince1970: 200)
        let oldEndpoint = try LANPolicy.validate(
            hostOrAddress: "192.168.1.20",
            source: .authenticatedRefresh,
            lastAuthenticatedAt: timestamp
        )
        let identity = PortalIdentity(
            portalID: portalID,
            name: "Living Room",
            model: "Meta Portal",
            device: "portal"
        )
        let status = PortalStatus(
            reachability: .reachable,
            presence: "home",
            lastUpdatedAt: timestamp,
            responseTimeMilliseconds: 12
        )
        let existingEntry = PortalRegistryEntry(
            id: portalID,
            connectionState: .online(lastRefresh: timestamp, latencyMs: 12),
            identity: identity,
            endpoint: oldEndpoint,
            discoveredEndpoints: [oldEndpoint],
            credentialReferences: [bearerReference],
            lastSuccessfulContact: timestamp,
            lastConfirmedStatus: status
        )
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(
            responses: [
                DNSResolutionResult(addresses: [ResolvedAddress(address: "192.168.1.21")]),
                DNSResolutionResult(addresses: [ResolvedAddress(address: "192.168.1.21")])
            ],
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: "192.168.1.21",
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 201)
        )
        let credentialStore = FakeCredentialStore(
            seededValues: [bearerReference: Data("BEARER-SENTINEL".utf8)],
            recorder: recorder
        )
        let transport = FakeFleetHTTPTransport(
            responses: [FleetHTTPResponse(statusCode: 401)],
            recorder: recorder
        )
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [existingEntry]),
            recorder: recorder
        )
        let coordinator = makeManualCoordinator(
            resolver: resolver,
            trustStore: trustStore,
            credentialStore: credentialStore,
            transport: transport,
            registryStore: registryStore,
            clock: FakeManagerClock(now: Date(timeIntervalSince1970: 300))
        )

        do {
            _ = try await coordinator.verifyBearer(
                rawEndpoint: "portal.local",
                for: portalID,
                credentialReference: bearerReference
            )
            XCTFail("A failed manual /info probe must not commit the candidate endpoint.")
        } catch let error as ManagerError {
            XCTAssertEqual(error, .authentication(.unauthorized))
        } catch {
            XCTFail("Expected a sanitized authentication failure, got \(error).")
        }

        let persistedEntry = try XCTUnwrap(registryStore.snapshot.entries.first)
        XCTAssertEqual(persistedEntry.identity, identity)
        XCTAssertEqual(persistedEntry.endpoint, oldEndpoint)
        XCTAssertEqual(persistedEntry.discoveredEndpoints, [oldEndpoint])
        XCTAssertEqual(persistedEntry.lastSuccessfulContact, timestamp)
        XCTAssertEqual(persistedEntry.lastConfirmedStatus, status)
        XCTAssertEqual(persistedEntry.credentialReferences, [bearerReference])
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(credentialStore.operations.map(\.reference), [bearerReference])
        XCTAssertFalse(
            persistedEntry.discoveredEndpoints.contains {
                $0.hostOrAddress == "192.168.1.21"
            }
        )
    }

    func testManualPairingUsesDirectRemotePairAndScopesRemoteSessionToIntendedPortal() async throws {
        let portalID = testFleetPortalID
        let otherPortalID = PortalID(
            rawValue: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        )
        let oldEndpoint = try LANPolicy.validate(
            hostOrAddress: "192.168.1.20",
            source: .authenticatedRefresh,
            lastAuthenticatedAt: Date(timeIntervalSince1970: 400)
        )
        let otherEndpoint = try LANPolicy.validate(
            hostOrAddress: "192.168.1.40",
            source: .manual
        )
        let targetEntry = PortalRegistryEntry(
            id: portalID,
            connectionState: .pairingRequired(endpoint: oldEndpoint),
            endpoint: oldEndpoint
        )
        let otherEntry = PortalRegistryEntry(
            id: otherPortalID,
            connectionState: .pairingRequired(endpoint: otherEndpoint),
            endpoint: otherEndpoint
        )
        let remoteReference = CredentialReference.portalCredential(
            portalID: portalID,
            kind: .remoteSession
        )
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(recorder: recorder)
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        try await trustStore.acknowledge(
            TrustWarningScope(
                serviceKind: .portal,
                protocolName: "http",
                resolvedHostOrAddress: "192.168.1.30",
                port: 8723
            ),
            at: Date(timeIntervalSince1970: 500)
        )
        let credentialStore = FakeCredentialStore(recorder: recorder)
        let transport = FakeFleetHTTPTransport(
            responses: [
                FleetHTTPResponse(
                    statusCode: 200,
                    body: Data(#"{"ok":true,"token":"REMOTE-TOKEN-SENTINEL"}"#.utf8)
                )
            ],
            recorder: recorder
        )
        let registryStore = FakeRegistryStore(
            initialSnapshot: RegistrySnapshot(entries: [targetEntry, otherEntry]),
            recorder: recorder
        )
        let clock = FakeManagerClock(
            now: Date(timeIntervalSince1970: 600),
            recorder: recorder
        )
        let coordinator = makeManualCoordinator(
            resolver: resolver,
            trustStore: trustStore,
            credentialStore: credentialStore,
            transport: transport,
            registryStore: registryStore,
            clock: clock
        )

        let result = try await coordinator.pair(
            rawEndpoint: "192.168.1.30",
            for: portalID,
            pin: "123456"
        )

        XCTAssertEqual(result.admission.endpoint.port, 8723)
        XCTAssertEqual(result.admission.endpoint.source, .manual)
        XCTAssertEqual(result.pairing.portalID, portalID)
        XCTAssertEqual(result.pairing.credentialReference, remoteReference)
        XCTAssertEqual(transport.requestCount, 1)
        XCTAssertEqual(transport.requestMetadata[0].route, .remotePair)
        XCTAssertEqual(transport.requestMetadata[0].method, .post)
        XCTAssertFalse(transport.requestMetadata[0].hasCredential)
        XCTAssertEqual(transport.requestMetadata[0].bodyFieldNames, ["pin"])
        XCTAssertEqual(resolver.requestCount, 0)
        XCTAssertEqual(credentialStore.operations.map(\.reference), [remoteReference])

        let persistedTarget = try XCTUnwrap(
            registryStore.snapshot.entries.first { $0.id == portalID }
        )
        XCTAssertEqual(persistedTarget.endpoint?.hostOrAddress, "192.168.1.30")
        XCTAssertEqual(persistedTarget.endpoint?.source, .manual)
        XCTAssertEqual(persistedTarget.endpoint?.lastAuthenticatedAt, clock.now)
        XCTAssertEqual(persistedTarget.credentialReferences, [remoteReference])
        XCTAssertNil(persistedTarget.identity)
        XCTAssertEqual(
            persistedTarget.connectionState,
            .remoteSessionPaired(lastPairedAt: clock.now)
        )
        let persistedOther = try XCTUnwrap(
            registryStore.snapshot.entries.first { $0.id == otherPortalID }
        )
        XCTAssertEqual(persistedOther, otherEntry)
    }

    func testManualRejectedDestinationsNeverReadCredentialsOrSendRequests() async {
        let invalid = await runManualVerification(
            rawEndpoint: "fe80::1:8723"
        )
        XCTAssertEqual(invalid.parserError, .ambiguousIPv6)
        XCTAssertNil(invalid.managerError)

        let publicDestination = await runManualVerification(
            rawEndpoint: "203.0.113.10"
        )
        XCTAssertEqual(publicDestination.managerError, .lanPolicy(.publicAddress))
        XCTAssertNil(publicDestination.parserError)

        let unresolved = await runManualVerification(
            rawEndpoint: "portal.invalid",
            failures: [.configuredFailure(.dns)]
        )
        XCTAssertEqual(unresolved.managerError, .resolution(.failed))
        XCTAssertNil(unresolved.parserError)

        for observation in [invalid, publicDestination, unresolved] {
            XCTAssertTrue(observation.credentialOperations.isEmpty)
            XCTAssertEqual(observation.transportRequestCount, 0)
            XCTAssertEqual(observation.events.filter { $0 == .credentialRead }, [])
            XCTAssertEqual(observation.events.filter { $0 == .transportSend }, [])
            XCTAssertEqual(
                observation.events.filter { $0 == .trustWarningLookup },
                []
            )
        }
    }

    private struct ManualRejectionObservation: Sendable {
        let parserError: ManualEndpointParserError?
        let managerError: ManagerError?
        let events: [FakeDependencyEvent]
        let credentialOperations: [FakeCredentialOperationMetadata]
        let transportRequestCount: Int
    }

    private func runManualVerification(
        rawEndpoint: String,
        responses: [DNSResolutionResult] = [],
        failures: [FakePortError] = []
    ) async -> ManualRejectionObservation {
        let recorder = FakeDependencyEventRecorder()
        let resolver = FakeDNSResolver(
            responses: responses,
            failures: failures,
            recorder: recorder
        )
        let trustStore = FakeTrustWarningStore(recorder: recorder)
        let credentialStore = FakeCredentialStore(recorder: recorder)
        let transport = FakeFleetHTTPTransport(recorder: recorder)
        let registryStore = FakeRegistryStore(recorder: recorder)
        let coordinator = makeManualCoordinator(
            resolver: resolver,
            trustStore: trustStore,
            credentialStore: credentialStore,
            transport: transport,
            registryStore: registryStore
        )
        let reference = CredentialReference.portalCredential(
            portalID: testFleetPortalID,
            kind: .verifiedBearer
        )

        var parserError: ManualEndpointParserError?
        var managerError: ManagerError?
        do {
            _ = try await coordinator.verifyBearer(
                rawEndpoint: rawEndpoint,
                for: testFleetPortalID,
                credentialReference: reference
            )
        } catch {
            parserError = error as? ManualEndpointParserError
            managerError = error as? ManagerError
        }

        return ManualRejectionObservation(
            parserError: parserError,
            managerError: managerError,
            events: recorder.events(),
            credentialOperations: credentialStore.operations,
            transportRequestCount: transport.requestCount
        )
    }

    private func makeManualCoordinator(
        resolver: FakeDNSResolver,
        trustStore: FakeTrustWarningStore,
        credentialStore: FakeCredentialStore,
        transport: FakeFleetHTTPTransport,
        registryStore: FakeRegistryStore,
        clock: FakeManagerClock = FakeManagerClock()
    ) -> ManualPortalCoordinator {
        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let registryCoordinator = PortalRegistryCoordinator(
            registryStore: registryStore,
            credentialStore: credentialStore
        )
        let fleetClient = FleetHTTPClient(
            transport: transport,
            admission: admission,
            credentialStore: credentialStore
        )
        let sessionCoordinator = PortalSessionCoordinator(
            fleetClient: fleetClient,
            registryCoordinator: registryCoordinator,
            credentialStore: credentialStore,
            clock: clock
        )
        return ManualPortalCoordinator(
            connectionAdmission: admission,
            sessionCoordinator: sessionCoordinator,
            registryCoordinator: registryCoordinator,
            clock: clock
        )
    }
}

private extension PortalManagerTests {
    func makeDiscoveryCoordinator(
        browser: FakeBonjourBrowser,
        resolver: FakeDNSResolver = FakeDNSResolver(),
        trustStore: FakeTrustWarningStore = FakeTrustWarningStore(),
        credentialStore: FakeCredentialStore = FakeCredentialStore(),
        registryEntries: [PortalRegistryEntry] = [],
        clock: FakeManagerClock = FakeManagerClock(),
        transport: FakeFleetHTTPTransport = FakeFleetHTTPTransport()
    ) -> (DiscoveryCoordinator, PortalRegistryCoordinator) {
        let admission = ConnectionAdmission(
            dnsResolver: resolver,
            trustWarningStore: trustStore
        )
        let fleetClient = FleetHTTPClient(
            transport: transport,
            admission: admission,
            credentialStore: credentialStore
        )
        let registryCoordinator = PortalRegistryCoordinator(
            registryStore: FakeRegistryStore(
                initialSnapshot: RegistrySnapshot(entries: registryEntries)
            ),
            credentialStore: credentialStore
        )
        let coordinator = DiscoveryCoordinator(
            browser: browser,
            connectionAdmission: admission,
            fleetClient: fleetClient,
            registryCoordinator: registryCoordinator,
            clock: clock
        )
        return (coordinator, registryCoordinator)
    }

    func discoveryEndpoint(
        _ host: String,
        source: EndpointSource = .manual,
        authenticatedAt: Date? = nil
    ) throws -> LANEndpoint {
        try LANPolicy.validate(
            hostOrAddress: host,
            port: 8723,
            source: source,
            lastAuthenticatedAt: authenticatedAt
        )
    }

    func discoveryCandidate(
        serviceName: String,
        host: String?
    ) -> BonjourService {
        BonjourService(
            serviceName: serviceName,
            resolvedHostOrAddress: host,
            port: 8723,
            source: .mdns(serviceName: serviceName)
        )
    }

    func discoveryTrustScope(for endpoint: LANEndpoint) -> TrustWarningScope {
        TrustWarningScope(
            serviceKind: .portal,
            protocolName: "http",
            resolvedHostOrAddress: endpoint.hostOrAddress,
            port: endpoint.port,
            interfaceZone: endpoint.interfaceZone
        )
    }

    func discoveryInfoResponse(
        name: String,
        model: String,
        device: String,
        ip: String
    ) -> FleetHTTPResponse {
        let object: [String: Any] = [
            "name": name,
            "model": model,
            "device": device,
            "apiLevel": 29,
            "ip": ip,
            "port": 8723,
            "capabilities": [:]
        ]
        let body = try! JSONSerialization.data(withJSONObject: object)
        return FleetHTTPResponse(statusCode: 200, body: body)
    }

    func waitForDiscovery(
        _ condition: @escaping @Sendable () async -> Bool
    ) async -> Bool {
        for _ in 0..<500 {
            if await condition() {
                return true
            }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return false
    }
}


extension PortalManagerTests {
    func testSettingsRegistrySchemaDecodesEnvelopeAndPreservesForwardCompatibleMetadata() throws {
        let data = Data(
            """
            {
              "ok": true,
              "settings": {
                "schemaVersion": 4,
                "domains": [
                  {
                    "id": "screensaver",
                    "title": "Screensaver",
                    "section": "Display",
                    "help": "Photo frame settings",
                    "visible": true,
                    "futureDomainMetadata": {"label": "preserved"},
                    "controls": [
                      {
                        "key": "enabled",
                        "type": "bool",
                        "title": "Enabled",
                        "section": "Display",
                        "help": "Show the photo frame",
                        "value": true,
                        "default": false,
                        "readOnly": false,
                        "secret": false,
                        "visible": true,
                        "display": "On",
                        "futureControlMetadata": {"unit": "flag"}
                      },
                      {
                        "key": "intervalSec",
                        "type": "int",
                        "title": "Photo interval",
                        "value": 30,
                        "default": 60,
                        "min": 5,
                        "max": 600,
                        "step": 5,
                        "wrap": false,
                        "asText": false
                      },
                      {
                        "key": "fit",
                        "type": "enum",
                        "title": "Fit",
                        "value": "fit",
                        "options": [
                          {"value": "fill", "label": "Fill", "shortcut": "F"},
                          {"value": "fit", "label": "Fit"}
                        ]
                      },
                      {
                        "key": "password",
                        "type": "string",
                        "title": "Password",
                        "value": "PASSWORD-SENTINEL",
                        "default": "DEFAULT-PASSWORD-SENTINEL",
                        "secret": true,
                        "hasValue": true
                      },
                      {
                        "key": "futureControl",
                        "type": "future-toggle",
                        "title": "Future control",
                        "value": "opaque",
                        "readOnly": false
                      }
                    ]
                  },
                  {
                    "id": "future-domain",
                    "title": "Future domain",
                    "controls": [
                      {
                        "key": "futureValue",
                        "type": "string",
                        "value": "future-value",
                        "readOnly": false
                      },
                      {
                        "key": "futureUnknown",
                        "type": "future-control",
                        "value": {"nested": true}
                      }
                    ]
                  }
                ]
              }
            }
            """.utf8
        )

        let schema = try JSONDecoder().decode(SettingsRegistrySchema.self, from: data)
        XCTAssertEqual(schema.domains.map(\.id), ["screensaver", "future-domain"])
        XCTAssertEqual(schema.additiveMetadata["schemaVersion"], .number(4))

        let screensaver = try XCTUnwrap(schema.domains.first)
        XCTAssertEqual(screensaver.knownDomain, .screensaver)
        XCTAssertTrue(screensaver.isKnownDomain)
        XCTAssertEqual(screensaver.section, "Display")
        XCTAssertEqual(screensaver.help, "Photo frame settings")
        XCTAssertTrue(screensaver.visible)
        XCTAssertEqual(
            screensaver.additiveMetadata["futureDomainMetadata"],
            .object(["label": .string("preserved")])
        )

        let enabled = try XCTUnwrap(screensaver.controls.first { $0.key == "enabled" })
        XCTAssertEqual(enabled.domainID, "screensaver")
        XCTAssertEqual(enabled.rawType, "bool")
        XCTAssertEqual(enabled.type, .bool)
        XCTAssertEqual(enabled.value, .bool(true))
        XCTAssertEqual(enabled.defaultValue, .bool(false))
        XCTAssertFalse(enabled.readOnly)
        XCTAssertEqual(enabled.additiveMetadata["display"], .string("On"))

        let interval = try XCTUnwrap(screensaver.controls.first { $0.key == "intervalSec" })
        XCTAssertEqual(interval.type, .int)
        XCTAssertEqual(interval.min, 5)
        XCTAssertEqual(interval.max, 600)
        XCTAssertEqual(interval.step, 5)
        XCTAssertEqual(interval.wrap, false)
        XCTAssertEqual(interval.asText, false)

        let fit = try XCTUnwrap(screensaver.controls.first { $0.key == "fit" })
        XCTAssertEqual(fit.type, .enumValue)
        XCTAssertEqual(fit.options?.map(\.stringValue), ["fill", "fit"])
        XCTAssertEqual(fit.options?.first?.additiveMetadata["shortcut"], .string("F"))

        let secret = try XCTUnwrap(screensaver.controls.first { $0.key == "password" })
        XCTAssertTrue(secret.secret)
        XCTAssertTrue(secret.hasValue == true)
        XCTAssertNil(secret.value)
        XCTAssertNil(secret.defaultValue)

        let futureControl = try XCTUnwrap(screensaver.controls.first { $0.key == "futureControl" })
        XCTAssertEqual(futureControl.type, .unknown(rawValue: "future-toggle"))
        XCTAssertTrue(futureControl.readOnly)
        XCTAssertTrue(futureControl.isReadOnlyCompatibilityRow)

        let futureDomain = try XCTUnwrap(schema.domains.last)
        XCTAssertNil(futureDomain.knownDomain)
        XCTAssertTrue(futureDomain.isReadOnlyCompatibilityRow)
        XCTAssertTrue(futureDomain.controls.allSatisfy(\.readOnly))
        XCTAssertTrue(futureDomain.controls.allSatisfy(\.isReadOnlyCompatibilityRow))

        let encodedText = String(
            decoding: try JSONEncoder().encode(schema),
            as: UTF8.self
        )
        XCTAssertFalse(encodedText.contains("PASSWORD-SENTINEL"))
        XCTAssertFalse(encodedText.contains("DEFAULT-PASSWORD-SENTINEL"))
        XCTAssertTrue(encodedText.contains("future-value"))

        let roundTripped = try JSONDecoder().decode(
            SettingsRegistrySchema.self,
            from: Data(encodedText.utf8)
        )
        XCTAssertEqual(roundTripped, schema)
    }

    func testSettingsRegistrySchemaRecognizesExactlyTheCurrentDomainIdentifiers() {
        let expected: Set<String> = [
            "screensaver", "calendar", "immortal", "mqtt", "quickbar", "fleet", "chime",
            "digitalclock", "welcome", "sunrise"
        ]
        XCTAssertEqual(
            Set(KnownSettingsDomain.allCases.map(\.rawValue)),
            expected
        )
        XCTAssertEqual(
            Set(expected.compactMap { SettingsDomainSchema(id: $0).knownDomain?.rawValue }),
            expected
        )
    }

    @MainActor
    func testProvisioningSelectionsFailClosedBeforeAnyDeviceWork() {
        let store = PortalManagerStore(dependencies: .bootstrap())

        store.dispatch(.selectProvisioningArtifact(
            URL(fileURLWithPath: "/private/tmp/missing-immortal.apk")
        ))
        XCTAssertNil(store.provisioningArtifact)

        store.dispatch(.selectProvisioningADB(URL(fileURLWithPath: "/bin/echo")))
        XCTAssertEqual(store.adbExecutableSelection?.displayName, "echo")
        store.provisioningDeviceSerialInput = "serial"
        store.provisioningPortalEndpointInput = "192.168.1.50"

        XCTAssertFalse(store.canStartProvisioning(.fleetAgentEnablementRecovery))
        XCTAssertFalse(store.canStartProvisioning(.fullUSBProvisioning))
    }

    func testProvisioningTargetRequiresOneRegisteredSerialForRecovery() throws {
        let portalID = PortalID(rawValue: UUID(uuidString: "33333333-3333-3333-3333-333333333333")!)
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .offline(lastContact: nil, reason: "offline"),
            identity: PortalIdentity(
                portalID: portalID,
                serial: "PORTAL-SERIAL",
                name: "Living Room",
                model: "Portal Mini"
            )
        )

        let recovered = try PortalManagerStore.provisioningTargetID(
            for: .fleetAgentEnablementRecovery,
            serial: " portal-serial ",
            entries: [entry]
        )
        XCTAssertEqual(recovered, portalID)

        XCTAssertThrowsError(try PortalManagerStore.provisioningTargetID(
            for: .fleetAgentEnablementRecovery,
            serial: "PORTAL-SERIAL",
            entries: []
        )) { error in
            XCTAssertEqual(error as? ProvisioningTargetError, .registeredPortalRequired)
        }
    }

    func testProvisioningTargetUpdatesExactSerialMatchOrCreatesNewFullTarget() throws {
        let portalID = PortalID(rawValue: UUID(uuidString: "44444444-4444-4444-4444-444444444444")!)
        let entry = PortalRegistryEntry(
            id: portalID,
            connectionState: .offline(lastContact: nil, reason: "offline"),
            identity: PortalIdentity(
                portalID: portalID,
                serial: "PORTAL-SERIAL",
                name: "Living Room",
                model: "Portal Mini"
            )
        )
        let duplicate = PortalRegistryEntry(
            id: PortalID(rawValue: UUID(uuidString: "55555555-5555-5555-5555-555555555555")!),
            connectionState: .offline(lastContact: nil, reason: "offline"),
            identity: PortalIdentity(
                portalID: PortalID(rawValue: UUID(uuidString: "55555555-5555-5555-5555-555555555555")!),
                serial: "PORTAL-SERIAL",
                name: "Duplicate",
                model: "Portal Mini"
            )
        )

        let updated = try PortalManagerStore.provisioningTargetID(
            for: .fullUSBProvisioning,
            serial: "portal-serial",
            entries: [entry]
        )
        XCTAssertEqual(updated, portalID)

        let created = try PortalManagerStore.provisioningTargetID(
            for: .fullUSBProvisioning,
            serial: "NEW-SERIAL",
            entries: [entry]
        )
        XCTAssertNotEqual(created, portalID)

        XCTAssertThrowsError(try PortalManagerStore.provisioningTargetID(
            for: .fullUSBProvisioning,
            serial: "PORTAL-SERIAL",
            entries: [entry, duplicate]
        )) { error in
            XCTAssertEqual(error as? ProvisioningTargetError, .ambiguousSerial)
        }
    }
}
