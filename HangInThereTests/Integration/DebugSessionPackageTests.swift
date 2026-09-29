import Foundation
import Testing
@testable import HangInThere

struct DebugSessionPackageTests {
    @Test func packagePinsVideoAndMetadata() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("HangInThere-debug-package-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: root) }

        let videoURL = root.appendingPathComponent("source.mov")
        let videoBytes = Data([0, 1, 2, 3, 4, 5, 6, 7])
        try videoBytes.write(to: videoURL)

        let sessionJSON = "{\"schemaVersion\":1,\"counterPolicyVersion\":6}\n"
        let qualificationJSON = "{\"schemaVersion\":1,\"observedMovements\":3}\n"
        let document = try DebugSessionPackageDocument(
            videoURL: videoURL,
            sessionJSON: sessionJSON,
            qualificationJSON: qualificationJSON
        )
        let wrapper = try document.makeFileWrapper()
        let files = try #require(wrapper.fileWrappers)

        #expect(Set(files.keys) == [
            "video.mov",
            "session.json",
            "qualification.json",
            "hashes.json"
        ])

        let hashesData = try #require(files["hashes.json"]?.regularFileContents)
        let rootJSON = try #require(
            JSONSerialization.jsonObject(with: hashesData) as? [String: Any]
        )
        let fileJSON = try #require(rootJSON["files"] as? [String: Any])
        let videoJSON = try #require(fileJSON["video.mov"] as? [String: Any])
        let sessionJSONEntry = try #require(fileJSON["session.json"] as? [String: Any])
        let qualificationJSONEntry = try #require(
            fileJSON["qualification.json"] as? [String: Any]
        )

        #expect(videoJSON["bytes"] as? Int == videoBytes.count)
        #expect((videoJSON["sha256"] as? String)?.count == 64)
        #expect(sessionJSONEntry["bytes"] as? Int == Data(sessionJSON.utf8).count)
        #expect((sessionJSONEntry["sha256"] as? String)?.count == 64)
        #expect(
            qualificationJSONEntry["bytes"] as? Int
                == Data(qualificationJSON.utf8).count
        )
        #expect((qualificationJSONEntry["sha256"] as? String)?.count == 64)
    }
}
