import CryptoKit
import Foundation
import WorkoutCore

/// Separate from the workout archive: its JSON files cannot be mistaken for
/// measured set records. Atomic annotations never rewrite measurements.
actor WorkoutGripCorrectionStore {
    static let shared = WorkoutGripCorrectionStore()
    private let directory: URL
    nonisolated private static let archiveLock = NSRecursiveLock()
    nonisolated static var archiveDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("WorkoutGripCorrections", isDirectory: true)
    }
    init(directory: URL? = nil) {
        self.directory = directory ?? Self.archiveDirectory
    }
    private func file(_ id: String) -> URL {
        Self.archiveFile(directory: directory, id: id)
    }
    nonisolated private static func archiveFile(directory: URL, id: String) -> URL {
        let key = SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(key + ".json")
    }
    private func ledger(_ id: String) throws -> String? {
        try Self.archiveLedger(directory: directory, id: id)
    }
    nonisolated static func archiveLedger(directory: URL, id: String) throws -> String? {
        archiveLock.lock(); defer { archiveLock.unlock() }
        let url = archiveFile(directory: directory, id: id)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= 512 * 1024 else { throw CocoaError(.fileReadCorruptFile) }
        return try String(contentsOf: url, encoding: .utf8)
    }
    nonisolated static func restoreLedger(directory: URL, record: WorkoutSetRecord, incoming: String, publish: Bool) throws {
        archiveLock.lock(); defer { archiveLock.unlock() }
        let input = String(decoding: try JSONEncoder().encode(record), as: UTF8.self)
        let merged = try WorkoutGripReviewBridge().mergeBackupLedgerJson(recordJson: input, existingJson: archiveLedger(directory: directory, id: record.id), incomingJson: incoming)
        if publish {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try Data(merged.utf8).write(to: archiveFile(directory: directory, id: record.id), options: .atomic)
        }
    }
    nonisolated static func deleteLedger(directory: URL, id: String) throws {
        archiveLock.lock(); defer { archiveLock.unlock() }
        let url = archiveFile(directory: directory, id: id)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
    func report(_ set: WorkoutSetRecord, useCorrections: Bool = true) throws -> WorkoutGripVariationReport {
        let input = String(decoding: try JSONEncoder().encode(set.progressInput), as: UTF8.self)
        let output = try WorkoutGripReviewBridge().reportJson(recordJson: input, ledgerJson: useCorrections ? ledger(set.id) : nil)
        return try JSONDecoder().decode(WorkoutGripVariationReport.self, from: Data(output.utf8))
    }
    func correct(_ set: WorkoutSetRecord, repId: String?, orientation: String?, width: String?) throws {
        Self.archiveLock.lock(); defer { Self.archiveLock.unlock() }
        let input = String(decoding: try JSONEncoder().encode(set.progressInput), as: UTF8.self)
        let output = try WorkoutGripReviewBridge().correctJson(recordJson: input, ledgerJson: ledger(set.id), repId: repId,
            orientation: orientation, width: width, recordedAtEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(output.utf8).write(to: file(set.id), options: .atomic)
    }
}
