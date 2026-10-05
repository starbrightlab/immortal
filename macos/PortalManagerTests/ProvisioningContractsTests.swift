/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation
import XCTest
@testable import PortalManager

final class ProvisioningContractsTests: XCTestCase {
    private let serial = "ADB-SERIAL-1"
    private let model = "Meta Portal Go"

    private var executable: LocalExecutableReference {
        LocalExecutableReference(
            securityScopedURL: URL(fileURLWithPath: "/private/tmp/adb-selected-token"),
            displayName: "adb"
        )
    }

    private var artifact: LocalArtifact {
        LocalArtifact(
            securityScopedURL: URL(fileURLWithPath: "/private/tmp/Immortal-release-token.apk"),
            displayName: "Immortal-release.apk",
            expectedPackageIdentity: "com.immortal.launcher",
            expectedSignaturePolicy: .approvedPackageSignature
        )
    }

    private var compatibleSnapshot: ADBDeviceSnapshot {
        ADBDeviceSnapshot(
            authorization: .authorized,
            connection: .connected,
            serial: serial,
            model: model,
            apiLevel: 29,
            installedImmortal: true,
            immortalCompatible: true,
            fleetAgent: .disabled,
            deviceCompatibility: .compatible,
            installedPackageIdentity: "com.immortal.launcher"
        )
    }

    func testEnablementRecoveryRequiresAnInstalledCompatibleAppAndCannotCarryArtifact() throws {
        let snapshot = compatibleSnapshot
        XCTAssertEqual(
            snapshot.validation(for: .fleetAgentEnablementRecovery),
            .allowed
        )

        let plan = try EnablementRecoveryPlan.validated(
            snapshot: snapshot,
            adbExecutable: executable,
            friendlyName: "Living Room"
        )
        XCTAssertTrue(plan.isValidated)
        XCTAssertEqual(plan.mode, .fleetAgentEnablementRecovery)
        XCTAssertNil(plan.artifact)
        XCTAssertEqual(plan.deviceSerial, serial)

        let withArtifact = snapshot.validation(
            for: .fleetAgentEnablementRecovery,
            artifact: artifact
        )
        XCTAssertEqual(withArtifact.failure?.code, .artifactNotAllowed)

        let notInstalled = ADBDeviceSnapshot(
            authorization: .authorized,
            connection: .connected,
            serial: serial,
            model: model,
            apiLevel: 29,
            installedImmortal: false,
            immortalCompatible: false,
            deviceCompatibility: .compatible
        )
        XCTAssertEqual(
            notInstalled.validation(for: .fleetAgentEnablementRecovery).failure?.code,
            .immortalNotInstalled
        )

        let incompatible = ADBDeviceSnapshot(
            authorization: .authorized,
            connection: .connected,
            serial: serial,
            model: model,
            apiLevel: 29,
            installedImmortal: true,
            immortalCompatible: false,
            deviceCompatibility: .compatible
        )
        XCTAssertEqual(
            incompatible.validation(for: .fleetAgentEnablementRecovery).failure?.code,
            .immortalIncompatible
        )
    }

    func testFullProvisioningRequiresArtifactAndSeparatesSetupFromRecovery() throws {
        let snapshot = ADBDeviceSnapshot(
            authorization: .authorized,
            connection: .connected,
            serial: serial,
            model: model,
            apiLevel: 29,
            installedImmortal: false,
            immortalCompatible: false,
            fleetAgent: .unknown,
            deviceCompatibility: .compatible
        )

        XCTAssertEqual(
            snapshot.validation(for: .fullUSBProvisioning).failure?.code,
            .artifactRequired
        )

        let plan = try FullUSBProvisioningPlan.validated(
            snapshot: snapshot,
            adbExecutable: executable,
            localArtifact: artifact,
            friendlyName: "Living Room"
        )
        XCTAssertTrue(plan.isValidated)
        XCTAssertEqual(plan.mode, .fullUSBProvisioning)
        XCTAssertEqual(plan.localArtifact.displayName, "Immortal-release.apk")
        XCTAssertEqual(
            plan.mode.setupSteps,
            [.artifactVerification, .deviceSetup, .installation]
        )
        XCTAssertEqual(
            plan.mode.enablementRecoverySteps,
            [.writeProvisionFile, .relaunchImmortal, .readAgentManifest,
             .bearerVerification, .complete]
        )

        let setupEvent = ProvisioningEvent.stepStarted(
            mode: .fullUSBProvisioning,
            step: .installation
        )
        let recoveryEvent = ProvisioningEvent.stepStarted(
            mode: .fleetAgentEnablementRecovery,
            step: .writeProvisionFile
        )
        XCTAssertEqual(setupEvent.mode, .fullUSBProvisioning)
        XCTAssertEqual(setupEvent.step, .installation)
        XCTAssertEqual(recoveryEvent.mode, .fleetAgentEnablementRecovery)
        XCTAssertEqual(recoveryEvent.step?.phase, .enablementRecovery)
    }

    func testArtifactVerificationRequiresEveryCheckAndReportsDigest() {
        let digest = String(repeating: "a", count: 64)
        let passed = ArtifactVerificationSummary(
            readableRegularFile: .passed,
            packageIdentity: .passed,
            signature: .passed,
            sha256Digest: digest,
            apiCompatibility: .passed,
            abiCompatibility: .passed,
            targetModelCompatibility: .passed
        )
        XCTAssertTrue(passed.passed)
        XCTAssertEqual(passed.sha256Digest, digest)

        let failed = ArtifactVerificationSummary(
            readableRegularFile: .passed,
            packageIdentity: .failed(.packageIdentityMismatch),
            signature: .passed,
            sha256Digest: digest,
            apiCompatibility: .passed,
            abiCompatibility: .passed,
            targetModelCompatibility: .passed
        )
        XCTAssertFalse(failed.passed)
        XCTAssertEqual(failed.packageIdentity.failure, .packageIdentityMismatch)
    }

    func testManifestProjectionAndFailuresAreSecretFreeAndSanitized() throws {
        let endpoint = LANEndpoint(
            hostOrAddress: "192.168.1.40",
            port: 8723,
            addressFamily: .ipv4,
            source: .provisioning
        )
        let manifest = try AgentManifest.projected(
            name: "Living Room",
            port: 8723,
            from: compatibleSnapshot,
            admittedEndpoint: endpoint
        )
        let manifestData = try JSONEncoder().encode(manifest)
        let manifestText = String(decoding: manifestData, as: UTF8.self)
        XCTAssertTrue(manifestText.contains("Living Room"))
        XCTAssertTrue(manifestText.contains("192.168.1.40"))
        XCTAssertFalse(manifestText.contains("bearerToken"))
        XCTAssertFalse(manifestText.contains("rawManifest"))
        XCTAssertFalse(manifestText.contains("processOutput"))

        let failure = ProvisioningFailure(
            step: .readAgentManifest,
            code: .invalidAgentManifest
        )
        XCTAssertEqual(
            failure.sanitizedMessage,
            "The Fleet Agent handoff manifest was invalid."
        )
        let failureData = try JSONEncoder().encode(failure)
        let failureText = String(decoding: failureData, as: UTF8.self)
        XCTAssertFalse(failureText.contains("token"))
        XCTAssertFalse(failureText.contains("process"))
        XCTAssertFalse(failureText.contains("http"))
    }

    func testLocalSelectionURLsAreNotSerializedIntoPlansOrArtifacts() throws {
        let plan = try FullUSBProvisioningPlan.validated(
            snapshot: compatibleSnapshot,
            adbExecutable: executable,
            localArtifact: artifact
        )
        let encoded = try JSONEncoder().encode(plan)
        let text = String(decoding: encoded, as: UTF8.self)

        XCTAssertFalse(text.contains("/private/tmp/adb-selected-token"))
        XCTAssertFalse(text.contains("/private/tmp/Immortal-release-token.apk"))
        XCTAssertTrue(text.contains("Immortal-release.apk"))
        XCTAssertTrue(text.contains("com.immortal.launcher"))

        let decoded = try JSONDecoder().decode(FullUSBProvisioningPlan.self, from: encoded)
        XCTAssertFalse(decoded.adbExecutable.hasSecurityScopedSelection)
        XCTAssertFalse(decoded.localArtifact.hasSecurityScopedSelection)
        XCTAssertEqual(decoded.localArtifact.displayName, "Immortal-release.apk")
    }
}


private actor RecordingADBProcessExecutor: ADBProcessExecutor {
    private var queuedResults: [ADBProcessResult]
    private(set) var invocations: [ADBProcessInvocation] = []

    init(results: [ADBProcessResult]) {
        queuedResults = results
    }

    func execute(_ invocation: ADBProcessInvocation) async throws -> ADBProcessResult {
        invocations.append(invocation)
        guard !queuedResults.isEmpty else {
            throw ADBProcessFailure.launchFailed
        }
        return queuedResults.removeFirst()
    }
}

private struct UnsupportedADBRequest: ADBRequest {}

final class ADBRunnerTests: XCTestCase {
    private var selectedExecutable: LocalExecutableReference {
        LocalExecutableReference(
            securityScopedURL: URL(fileURLWithPath: "/bin/echo"),
            displayName: "echo"
        )
    }

    func testFiniteCommandUsesDirectProcessAndFixedEnvironment() async throws {
        let executor = RecordingADBProcessExecutor(
            results: [
                ADBProcessResult(
                    terminationStatus: 0,
                    stdout: Data("List of devices attached\nUSB-1\tdevice\tmodel:Meta_Portal_Go\n".utf8),
                    stderr: Data()
                )
            ]
        )
        let runner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: executor
        )

        let result = try await runner.execute(ADBCommand.enumerateDevices)
        let enumeration = try XCTUnwrap(result as? ADBDeviceEnumerationResult)
        XCTAssertEqual(enumeration.devices.first?.serial, "USB-1")
        XCTAssertEqual(enumeration.devices.first?.authorization, .authorized)
        XCTAssertEqual(enumeration.devices.first?.connection, .connected)
        XCTAssertEqual(enumeration.devices.first?.model, "Meta Portal Go")

        let invocations = await executor.invocations
        XCTAssertEqual(invocations.count, 1)
        XCTAssertEqual(invocations[0].arguments, ["devices", "-l"])
        XCTAssertEqual(invocations[0].environment, ProcessADBRunner.fixedEnvironment)
        XCTAssertNil(invocations[0].workingDirectory)
        XCTAssertFalse(invocations[0].arguments.contains("-c"))
        XCTAssertFalse(invocations[0].arguments.contains("sh"))
    }

    func testHandoffAndRelaunchUseOnlyFixedDevicePaths() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ADBRunnerTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let provisionFile = directory.appendingPathComponent("provision.json")
        try Data("{\"enabled\":true}".utf8).write(to: provisionFile)

        let pushExecutor = RecordingADBProcessExecutor(
            results: [ADBProcessResult(terminationStatus: 0, stdout: Data(), stderr: Data())]
        )
        let pushRunner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: pushExecutor
        )
        _ = try await pushRunner.execute(
            ADBCommand.pushProvisionFile(device: "USB-1", localURL: provisionFile)
        )

        let pushInvocations = await pushExecutor.invocations
        let pushInvocation = try XCTUnwrap(pushInvocations.first)
        XCTAssertEqual(
            pushInvocation.arguments,
            [
                "-s", "USB-1", "push", provisionFile.path,
                "/sdcard/Android/data/com.immortal.launcher/files/fleet/provision.json"
            ]
        )
        XCTAssertFalse(pushInvocation.arguments.contains("-c"))

        let relaunchExecutor = RecordingADBProcessExecutor(
            results: [
                ADBProcessResult(terminationStatus: 0, stdout: Data(), stderr: Data()),
                ADBProcessResult(terminationStatus: 0, stdout: Data(), stderr: Data())
            ]
        )
        let relaunchRunner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: relaunchExecutor
        )
        _ = try await relaunchRunner.execute(ADBCommand.relaunchImmortal(device: "USB-1"))

        let relaunchInvocations = await relaunchExecutor.invocations
        XCTAssertEqual(relaunchInvocations.count, 2)
        XCTAssertEqual(
            relaunchInvocations.map(\.arguments),
            [
                ["-s", "USB-1", "shell", "am", "force-stop", "com.immortal.launcher"],
                ["-s", "USB-1", "shell", "am", "start", "-n", "com.immortal.launcher/.HomeActivity"]
            ]
        )
    }

    func testInspectionMapsEveryFiniteDeviceField() async throws {
        let deviceList = Data("List of devices attached\nUSB-1\tdevice\tmodel:Meta_Portal_Go\n".utf8)
        let executor = RecordingADBProcessExecutor(
            results: [
                ADBProcessResult(terminationStatus: 0, stdout: deviceList, stderr: Data()),
                ADBProcessResult(terminationStatus: 0, stdout: deviceList, stderr: Data()),
                ADBProcessResult(terminationStatus: 0, stdout: Data("USB-1\n".utf8), stderr: Data()),
                ADBProcessResult(terminationStatus: 0, stdout: Data("Meta Portal Go\n".utf8), stderr: Data()),
                ADBProcessResult(terminationStatus: 0, stdout: Data("29\n".utf8), stderr: Data()),
                ADBProcessResult(terminationStatus: 0, stdout: Data("package:/data/app/com.immortal.launcher/base.apk\n".utf8), stderr: Data()),
                ADBProcessResult(terminationStatus: 0, stdout: Data("versionCode=42\nversionName=1.2.3\n".utf8), stderr: Data()),
                ADBProcessResult(terminationStatus: 0, stdout: Data("/sdcard/Android/data/com.immortal.launcher/files/fleet/agent.json\n".utf8), stderr: Data())
            ]
        )
        let runner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: executor
        )

        let authorizationResult = try await runner.execute(
            ADBCommand.inspect(device: "USB-1", field: .authorization)
        )
        let authorization = try XCTUnwrap(authorizationResult as? ADBInspectionResult)

        let connectionResult = try await runner.execute(
            ADBCommand.inspect(device: "USB-1", field: .connection)
        )
        let connection = try XCTUnwrap(connectionResult as? ADBInspectionResult)

        let serialResult = try await runner.execute(
            ADBCommand.inspect(device: "USB-1", field: .serial)
        )
        let serial = try XCTUnwrap(serialResult as? ADBInspectionResult)

        let modelResult = try await runner.execute(
            ADBCommand.inspect(device: "USB-1", field: .model)
        )
        let model = try XCTUnwrap(modelResult as? ADBInspectionResult)

        let apiLevelResult = try await runner.execute(
            ADBCommand.inspect(device: "USB-1", field: .apiLevel)
        )
        let apiLevel = try XCTUnwrap(apiLevelResult as? ADBInspectionResult)

        let installedResult = try await runner.execute(
            ADBCommand.inspect(device: "USB-1", field: .installedImmortal)
        )
        let installed = try XCTUnwrap(installedResult as? ADBInspectionResult)

        let versionResult = try await runner.execute(
            ADBCommand.inspect(device: "USB-1", field: .immortalVersion)
        )
        let version = try XCTUnwrap(versionResult as? ADBInspectionResult)

        let fleetAgentResult = try await runner.execute(
            ADBCommand.inspect(device: "USB-1", field: .fleetAgent)
        )
        let fleetAgent = try XCTUnwrap(fleetAgentResult as? ADBInspectionResult)

        XCTAssertEqual(authorization.value, .authorization(.authorized))
        XCTAssertEqual(connection.value, .connection(.connected))
        XCTAssertEqual(serial.value, .serial("USB-1"))
        XCTAssertEqual(model.value, .model("Meta Portal Go"))
        XCTAssertEqual(apiLevel.value, .apiLevel(29))
        XCTAssertEqual(installed.value, .installedImmortal(.installedCompatible))
        XCTAssertEqual(
            version.value,
            .immortalVersion(AppVersion(versionCode: 42, versionName: "1.2.3"))
        )
        XCTAssertEqual(fleetAgent.value, .fleetAgent(.enabled))

        let invocations = await executor.invocations
        XCTAssertEqual(
            invocations.map(\.arguments),
            [
                ["devices", "-l"],
                ["devices", "-l"],
                ["-s", "USB-1", "get-serialno"],
                ["-s", "USB-1", "shell", "getprop", "ro.product.model"],
                ["-s", "USB-1", "shell", "getprop", "ro.build.version.sdk"],
                ["-s", "USB-1", "shell", "cmd", "package", "path", "com.immortal.launcher"],
                ["-s", "USB-1", "shell", "dumpsys", "package", "com.immortal.launcher"],
                ["-s", "USB-1", "shell", "ls", "/sdcard/Android/data/com.immortal.launcher/files/fleet/agent.json"]
            ]
        )
    }

    func testInstallAndEstablishedSetupUseFixedArguments() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ADBRunnerTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let artifact = directory.appendingPathComponent("Immortal-release.apk")
        try Data("local artifact fixture".utf8).write(to: artifact)

        let executor = RecordingADBProcessExecutor(
            results: Array(
                repeating: ADBProcessResult(terminationStatus: 0, stdout: Data(), stderr: Data()),
                count: 1 + SetupStep.allCases.count
            )
        )
        let runner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: executor
        )

        _ = try await runner.execute(
            ADBCommand.installVerifiedArtifact(device: "USB-1", localURL: artifact)
        )
        for step in SetupStep.allCases {
            _ = try await runner.execute(
                ADBCommand.applyEstablishedSetup(device: "USB-1", step: step)
            )
        }

        let invocations = await executor.invocations
        XCTAssertEqual(
            invocations.map(\.arguments),
            [
                ["-s", "USB-1", "install", "-r", "-d", artifact.path],
                ["-s", "USB-1", "shell", "settings", "put", "global", "development_settings_enabled", "1"],
                ["-s", "USB-1", "shell", "settings", "put", "global", "policy_control", "immersive.status=*"],
                ["-s", "USB-1", "shell", "settings", "put", "global", "hidden_api_policy", "1"],
                ["-s", "USB-1", "shell", "cmd", "overlay", "disable", "com.facebook.aloha.rro.niu.android"],
                ["-s", "USB-1", "shell", "cmd", "overlay", "disable", "com.facebook.aloha.rro.niu.settings"]
            ]
        )
        XCTAssertTrue(invocations.allSatisfy { $0.environment == ProcessADBRunner.fixedEnvironment })
        XCTAssertTrue(invocations.allSatisfy { $0.workingDirectory == nil })
        let flattenedArguments = invocations.flatMap(\.arguments)
        XCTAssertFalse(flattenedArguments.contains("-c"))
        XCTAssertFalse(flattenedArguments.contains("connect"))
        XCTAssertFalse(flattenedArguments.contains("pm"))
    }

    func testRunnerRejectsInvalidSelectionsAndBoundsSanitizedDiagnostics() async throws {
        let executor = RecordingADBProcessExecutor(results: [])
        let missingExecutable = LocalExecutableReference(
            securityScopedURL: FileManager.default.temporaryDirectory
                .appendingPathComponent("missing-adb-\(UUID().uuidString)"),
            displayName: "adb"
        )
        let runnerWithMissingExecutable = ProcessADBRunner(
            executable: missingExecutable,
            processExecutor: executor
        )

        do {
            _ = try await runnerWithMissingExecutable.execute(ADBCommand.enumerateDevices)
            XCTFail("A missing selected executable must be rejected.")
        } catch let error as ADBRunnerError {
            XCTAssertEqual(error.code, .invalidExecutable)
        }

        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ADBRunnerTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        do {
            _ = try await ProcessADBRunner(
                executable: selectedExecutable,
                processExecutor: executor
            ).execute(
                ADBCommand.pushProvisionFile(device: "USB-1", localURL: directory)
            )
            XCTFail("A directory must not cross the local-file boundary.")
        } catch let error as ADBRunnerError {
            XCTAssertEqual(error.code, .invalidLocalFile)
        }

        let wrongNamedFile = directory.appendingPathComponent("not-provision.json")
        try Data("{}".utf8).write(to: wrongNamedFile)
        do {
            _ = try await ProcessADBRunner(
                executable: selectedExecutable,
                processExecutor: executor
            ).execute(
                ADBCommand.pushProvisionFile(device: "USB-1", localURL: wrongNamedFile)
            )
            XCTFail("A handoff file with the wrong name must be rejected.")
        } catch let error as ADBRunnerError {
            XCTAssertEqual(error.code, .invalidLocalFile)
        }

        let unsafeDeviceRunner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: executor
        )
        do {
            _ = try await unsafeDeviceRunner.execute(
                ADBCommand.inspect(device: "USB-1; echo unsafe", field: .model)
            )
            XCTFail("Shell metacharacters must not cross the device identifier boundary.")
        } catch let error as ADBRunnerError {
            XCTAssertEqual(error.code, .invalidDevice)
        }
        let rejectedInvocations = await executor.invocations
        XCTAssertTrue(rejectedInvocations.isEmpty)

        let provisionFile = directory.appendingPathComponent("provision.json")
        try Data("{}".utf8).write(to: provisionFile)
        let diagnosticExecutor = RecordingADBProcessExecutor(
            results: [
                ADBProcessResult(
                    terminationStatus: 0,
                    stdout: Data(repeating: UInt8(ascii: "x"), count: 8_192),
                    stderr: Data()
                )
            ]
        )
        let diagnosticRunner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: diagnosticExecutor
        )
        let acknowledgementResult = try await diagnosticRunner.execute(
            ADBCommand.pushProvisionFile(device: "USB-1", localURL: provisionFile)
        )
        let acknowledgement = try XCTUnwrap(
            acknowledgementResult as? ADBCommandAcknowledgement
        )
        XCTAssertEqual(acknowledgement.output.stdout.count, 4_096)
        XCTAssertTrue(acknowledgement.output.stderr.isEmpty)
    }

    func testFoundationExecutorRunsTheSelectedExecutableDirectly() async throws {
        let invocation = ADBProcessInvocation(
            executableURL: URL(fileURLWithPath: "/usr/bin/printf"),
            arguments: ["process-boundary"],
            environment: ProcessADBRunner.fixedEnvironment,
            workingDirectory: nil
        )

        let result = try await FoundationADBProcessExecutor().execute(invocation)
        XCTAssertEqual(result.terminationStatus, 0)
        XCTAssertEqual(result.stdout, Data("process-boundary".utf8))
        XCTAssertTrue(result.stderr.isEmpty)
    }

    func testManifestTokenIsTypedAndNonQueryOutputIsRedacted() async throws {
        let manifestExecutor = RecordingADBProcessExecutor(
            results: [
                ADBProcessResult(
                    terminationStatus: 0,
                    stdout: Data("{\"name\":\"Living Room\",\"token\":\"SECRET-BEARER\",\"port\":8723}".utf8),
                    stderr: Data()
                )
            ]
        )
        let manifestRunner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: manifestExecutor
        )

        let manifestResult = try await manifestRunner.execute(
            ADBCommand.readAgentManifest(device: "USB-1")
        )
        let recovered = try XCTUnwrap(manifestResult as? ADBManifestReadResult)
        XCTAssertEqual(recovered.manifest.name, "Living Room")
        XCTAssertEqual(recovered.manifest.port, 8723)
        XCTAssertEqual(String(data: recovered.bearerToken, encoding: .utf8), "SECRET-BEARER")

        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ADBRunnerTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let provisionFile = directory.appendingPathComponent("provision.json")
        try Data("{}".utf8).write(to: provisionFile)

        let outputExecutor = RecordingADBProcessExecutor(
            results: [
                ADBProcessResult(
                    terminationStatus: 0,
                    stdout: Data("Authorization: Bearer SECRET-BEARER\n".utf8),
                    stderr: Data("token=SECRET-BEARER\n".utf8)
                )
            ]
        )
        let outputRunner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: outputExecutor
        )
        let acknowledgement = try await outputRunner.execute(
            ADBCommand.pushProvisionFile(device: "USB-1", localURL: provisionFile)
        ) as? ADBCommandAcknowledgement
        let output = try XCTUnwrap(acknowledgement?.output)
        XCTAssertFalse(output.stdout.contains("SECRET-BEARER"))
        XCTAssertFalse(output.stderr.contains("SECRET-BEARER"))
        XCTAssertTrue(output.stdout.contains("<redacted>"))
        XCTAssertTrue(output.stderr.contains("<redacted>"))
    }

    func testRunnerRejectsUntypedRequestsAndUnsafeDeviceIdentifiers() async throws {
        let executor = RecordingADBProcessExecutor(results: [])
        let runner = ProcessADBRunner(
            executable: selectedExecutable,
            processExecutor: executor
        )

        do {
            _ = try await runner.execute(UnsupportedADBRequest())
            XCTFail("An untyped request must not reach the process boundary.")
        } catch let error as ADBRunnerError {
            XCTAssertEqual(error.code, .invalidRequest)
        }

        do {
            _ = try await runner.execute(
                ADBCommand.inspect(device: "USB-1; echo unsafe", field: .model)
            )
            XCTFail("An unsafe device identifier must be rejected.")
        } catch let error as ADBRunnerError {
            XCTAssertEqual(error.code, .invalidDevice)
        }

        let invocations = await executor.invocations
        XCTAssertTrue(invocations.isEmpty)
    }
}
