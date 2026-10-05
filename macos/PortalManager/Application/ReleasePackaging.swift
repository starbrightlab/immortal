/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import Foundation

/// A signed/notarized macOS candidate selected by the release workflow.
struct ReleaseCandidate: Equatable, Sendable {
    var version: String
    var appPath: String
    var notarizationTicketPath: String?
}

enum ReleasePackagingFailure: Error, Equatable, Sendable {
    case appNotReadable
    case bundleIdentifierMismatch
    case signatureMissing
    case ticketMissingOrInvalid
}

protocol ReleasePackagingVerifier: Sendable {
    func verify(_ candidate: ReleaseCandidate) async throws
}

protocol ReleasePackagingProcessRunner: Sendable {
    func run(executablePath: String, arguments: [String]) async throws -> Int32
}

struct FoundationReleasePackagingProcessRunner: ReleasePackagingProcessRunner {
    func run(executablePath: String, arguments: [String]) async throws -> Int32 {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executablePath)
        process.arguments = arguments
        try process.run()
        process.waitUntilExit()
        return process.terminationStatus
    }
}

struct SystemReleasePackagingVerifier: ReleasePackagingVerifier {
    static let expectedBundleIdentifier = "com.starbrightlab.portalmanager"

    private let processRunner: any ReleasePackagingProcessRunner

    init(processRunner: any ReleasePackagingProcessRunner = FoundationReleasePackagingProcessRunner()) {
        self.processRunner = processRunner
    }

    func verify(_ candidate: ReleaseCandidate) async throws {
        var isDirectory: ObjCBool = false
        let fileManager = FileManager.default
        guard !candidate.appPath.isEmpty,
              fileManager.fileExists(atPath: candidate.appPath, isDirectory: &isDirectory),
              isDirectory.boolValue,
              URL(fileURLWithPath: candidate.appPath).pathExtension == "app" else {
            throw ReleasePackagingFailure.appNotReadable
        }

        try Self.validateBundleIdentity(appPath: candidate.appPath)

        try await run(
            processRunner,
            "/usr/bin/codesign",
            ["--verify", "--strict", "--verbose=2", candidate.appPath],
            failure: .signatureMissing
        )
        try await run(
            processRunner,
            "/usr/sbin/spctl",
            ["--assess", "--type", "execute", "--verbose=2", candidate.appPath],
            failure: .signatureMissing
        )

        guard let ticketPath = candidate.notarizationTicketPath else {
            throw ReleasePackagingFailure.ticketMissingOrInvalid
        }

        var ticketIsDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: ticketPath, isDirectory: &ticketIsDirectory),
              !ticketIsDirectory.boolValue,
              fileManager.isReadableFile(atPath: ticketPath) else {
            throw ReleasePackagingFailure.ticketMissingOrInvalid
        }

        try await run(
            processRunner,
            "/usr/bin/stapler",
            ["validate", candidate.appPath],
            failure: .ticketMissingOrInvalid
        )
    }

    private func run(
        _ runner: any ReleasePackagingProcessRunner,
        _ executablePath: String,
        _ arguments: [String],
        failure: ReleasePackagingFailure
    ) async throws {
        do {
            guard try await runner.run(executablePath: executablePath, arguments: arguments) == 0 else {
                throw failure
            }
        } catch let failure as ReleasePackagingFailure {
            throw failure
        } catch {
            throw failure
        }
    }

    private static func validateBundleIdentity(appPath: String) throws {
        let infoURL = URL(fileURLWithPath: appPath)
            .appendingPathComponent("Contents")
            .appendingPathComponent("Info.plist")

        let data: Data
        do {
            data = try Data(contentsOf: infoURL)
        } catch {
            throw ReleasePackagingFailure.appNotReadable
        }

        let plist: Any
        do {
            plist = try PropertyListSerialization.propertyList(
                from: data,
                options: [],
                format: nil
            )
        } catch {
            throw ReleasePackagingFailure.appNotReadable
        }

        guard let values = plist as? [String: Any],
              let bundleIdentifier = values["CFBundleIdentifier"] as? String,
              bundleIdentifier == expectedBundleIdentifier else {
            throw ReleasePackagingFailure.bundleIdentifierMismatch
        }

        guard let executableName = values["CFBundleExecutable"] as? String else {
            throw ReleasePackagingFailure.appNotReadable
        }
        let executableURL = URL(fileURLWithPath: appPath)
            .appendingPathComponent("Contents")
            .appendingPathComponent("MacOS")
            .appendingPathComponent(executableName)
        var isExecutableDirectory: ObjCBool = false
        guard FileManager.default.fileExists(
                  atPath: executableURL.path,
                  isDirectory: &isExecutableDirectory
              ),
              !isExecutableDirectory.boolValue,
              FileManager.default.isExecutableFile(atPath: executableURL.path) else {
            throw ReleasePackagingFailure.appNotReadable
        }
    }
}
