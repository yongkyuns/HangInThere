import CryptoKit
import Foundation
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let hangInThereDebugSession = UTType(
        exportedAs: "dev.yongkyuns.HangInThere.debug-session",
        conformingTo: .package
    )
}


struct QualificationReportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents,
              let text = String(data: data, encoding: .utf8)
        else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.text = text
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}


struct DebugSessionPackageDocument: FileDocument, Sendable {
    static var readableContentTypes: [UTType] { [.hangInThereDebugSession] }

    let videoURL: URL
    let sessionJSON: String
    let qualificationJSON: String
    let hashesJSON: String

    init(videoURL: URL, sessionJSON: String, qualificationJSON: String) throws {
        self.videoURL = videoURL
        self.sessionJSON = sessionJSON
        self.qualificationJSON = qualificationJSON

        let video = try Self.fileDigest(videoURL)
        let sessionData = Data(sessionJSON.utf8)
        let qualificationData = Data(qualificationJSON.utf8)
        let hashes: [String: Any] = [
            "schemaVersion": 1,
            "files": [
                "video.mov": [
                    "sha256": video.sha256,
                    "bytes": video.bytes
                ],
                "session.json": [
                    "sha256": Self.digest(sessionData),
                    "bytes": sessionData.count
                ],
                "qualification.json": [
                    "sha256": Self.digest(qualificationData),
                    "bytes": qualificationData.count
                ]
            ]
        ]
        let data = try JSONSerialization.data(
            withJSONObject: hashes,
            options: [.prettyPrinted, .sortedKeys]
        )
        self.hashesJSON = String(decoding: data, as: UTF8.self) + "\n"
    }

    init(configuration: ReadConfiguration) throws {
        throw CocoaError(.fileReadUnsupportedScheme)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        try makeFileWrapper()
    }

    func makeFileWrapper() throws -> FileWrapper {
        let video = try FileWrapper(url: videoURL, options: [])
        video.preferredFilename = "video.mov"

        let session = FileWrapper(regularFileWithContents: Data(sessionJSON.utf8))
        session.preferredFilename = "session.json"

        let qualification = FileWrapper(
            regularFileWithContents: Data(qualificationJSON.utf8)
        )
        qualification.preferredFilename = "qualification.json"

        let hashes = FileWrapper(regularFileWithContents: Data(hashesJSON.utf8))
        hashes.preferredFilename = "hashes.json"

        return FileWrapper(directoryWithFileWrappers: [
            "video.mov": video,
            "session.json": session,
            "qualification.json": qualification,
            "hashes.json": hashes
        ])
    }

    private static func fileDigest(_ url: URL) throws -> (sha256: String, bytes: Int) {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        var hasher = SHA256()
        var count = 0
        while true {
            guard let data = try handle.read(upToCount: 1024 * 1024),
                  !data.isEmpty
            else { break }
            count += data.count
            hasher.update(data: data)
        }
        return (hasher.finalize().map { String(format: "%02x", $0) }.joined(), count)
    }

    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
