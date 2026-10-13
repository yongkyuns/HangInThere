import CryptoKit
import Foundation
import WorkoutCore

/// Separate from the workout archive: its JSON files cannot be mistaken for
/// measured set records. Atomic annotations never rewrite measurements.
actor WorkoutGripCorrectionStore {
    static let shared = WorkoutGripCorrectionStore()
    private let directory: URL
    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("WorkoutGripCorrections", isDirectory: true)
    }
    private func file(_ id: String) -> URL {
        let key = SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(key + ".json")
    }
    private func ledger(_ id: String) throws -> String? {
        let url = file(id)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= 512 * 1024 else { throw CocoaError(.fileReadCorruptFile) }
        return try String(contentsOf: url, encoding: .utf8)
    }
    func report(_ set: WorkoutSetRecord, useCorrections: Bool = true) throws -> WorkoutGripVariationReport {
        let input = String(decoding: try JSONEncoder().encode(set.progressInput), as: UTF8.self)
        let output = try WorkoutGripReviewBridge().reportJson(recordJson: input, ledgerJson: useCorrections ? ledger(set.id) : nil)
        return try JSONDecoder().decode(WorkoutGripVariationReport.self, from: Data(output.utf8))
    }
    func correct(_ set: WorkoutSetRecord, repId: String?, orientation: String?, width: String?) throws {
        let input = String(decoding: try JSONEncoder().encode(set.progressInput), as: UTF8.self)
        let output = try WorkoutGripReviewBridge().correctJson(recordJson: input, ledgerJson: ledger(set.id), repId: repId,
            orientation: orientation, width: width, recordedAtEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(output.utf8).write(to: file(set.id), options: .atomic)
    }
}
