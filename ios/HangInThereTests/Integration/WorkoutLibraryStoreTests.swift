import Foundation
import XCTest
@testable import HangInThere

final class WorkoutLibraryStoreTests: XCTestCase {
    private func record(_ id: String = UUID().uuidString) -> WorkoutSetRecord {
        WorkoutSetRecord(id: id, exercise: .pullUp, startedAt: 0, endedAt: 8, endReason: "manual", reps: [],
            backend: "authored", modelRevision: 1, schemaVersion: 1, lifecycleVersion: 1, trackingSide: .left,
            imageSize: nil, referenceEdge: nil, trackedAtEpochMilliseconds: 100_000,
            activity: WorkoutActivity(phases: [], totals: [WorkoutPhaseTotal(phase: "hang", observedSeconds: 8)],
                longestHangSeconds: 8, longestSupportSeconds: 0, observedSeconds: 8, elapsedSeconds: 8, timelineTruncated: false, metricVersion: 2))
    }
    private func command(_ values: [String: Any]) throws -> String {
        var values = values; values["atEpochMilliseconds"] = 200_000
        return String(decoding: try JSONSerialization.data(withJSONObject: values), as: UTF8.self)
    }
    func testJourneyDrillUsesSharedCorrectionsAndNullableDayBridge() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("journey-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root); let original = record()
        try await store.save(original)
        let data = try await store.libraryJourneySet(id: original.id)
        let summary = try JSONDecoder().decode(JourneySetSummary.self, from: Data(data.utf8))
        XCTAssertEqual(summary.quality.level, "practice")
        XCTAssertEqual(summary.holdSeconds, 8)
        XCTAssertEqual(summary.completedReps, 0)
        let offsetMilliseconds = Int64(TimeZone.current.secondsFromGMT()) * 1_000
        let localDay = Int64(floor(Double(original.trackedAtEpochMilliseconds! + offsetMilliseconds) / 86_400_000))
        let pageJSON = try await store.libraryJourneyPage(day: localDay, cursor: nil)
        let page = try JSONDecoder().decode(JourneyPage.self, from: Data(pageJSON.utf8))
        XCTAssertEqual(page.workouts.count, 1)
        XCTAssertEqual(page.workouts.first?.sets.first?.id, original.id)
        _ = try await store.libraryCommand(command(["action": "review", "setId": original.id, "correction": ["exercise": "dip"]]))
        let correctedJSON = try await store.libraryJourneySet(id: original.id)
        let corrected = try JSONDecoder().decode(JourneySetSummary.self, from: Data(correctedJSON.utf8))
        XCTAssertEqual(corrected.exercise, .dip)
        XCTAssertNil(corrected.holdSeconds)
        _ = try await store.libraryCommand(command(["action": "delete", "setId": original.id]))
        do { _ = try await store.libraryJourneySet(id: original.id); XCTFail("Deleted set must not reopen") } catch { }
    }
    func testAfterSetRefreshAppliesReviewsAndRecognizesDeletion() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root); let original = record()
        try await store.save(original)
        let captured = try await store.libraryAfterSetIfNotDeleted(id: original.id)
        XCTAssertEqual(captured?.observedHoldSeconds, 8)
        _ = try await store.libraryCommand(command(["action": "review", "setId": original.id, "correction": ["exercise": "dip"]]))
        let reviewed = try await store.libraryAfterSetIfNotDeleted(id: original.id)
        XCTAssertNil(reviewed?.observedHoldSeconds)
        _ = try await store.libraryCommand(command(["action": "delete", "setId": original.id]))
        let deleted = try await store.libraryAfterSetIfNotDeleted(id: original.id)
        XCTAssertNil(deleted)
    }
    func testBatchDeletionErasesEverySourceAndSurvivesReloadAndRestore() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root)
        let originals = [record(), record()]
        for original in originals { try await store.save(original); try await store.enqueue(original, finalized: true) }
        let backup = try await store.libraryExport()
        _ = try await store.libraryCommand(command(["action": "deleteSources", "setIds": originals.map(\.id)]))
        try await store.flushPending()
        let reload = WorkoutRecordStore(directory: root)
        try await reload.libraryRestore(backup)
        for original in originals {
            let restored = try await reload.libraryRecord(id: original.id)
            XCTAssertNil(restored)
            XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(original.id + ".json").path))
        }
        let state = try JSONSerialization.jsonObject(with: Data(try await reload.libraryState().utf8)) as? [String: Any]
        XCTAssertEqual((state?["deletions"] as? [[String: Any]])?.count, 2)
    }
    func testReviewReloadPreservesMeasuredBytesAndUndo() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root); let original = record()
        try await store.save(original)
        let measured = try Data(contentsOf: root.appendingPathComponent(original.id + ".json"))
        _ = try await store.libraryCommand(command(["action": "review", "setId": original.id, "correction": ["exercise": "dip"]]))
        XCTAssertEqual(measured, try Data(contentsOf: root.appendingPathComponent(original.id + ".json")))
        let reloaded = WorkoutRecordStore(directory: root)
        let savedCorrection = try await reloaded.libraryCorrection(id: original.id)
        XCTAssertNotNil(savedCorrection)
        _ = try await reloaded.libraryCommand(command(["action": "undo", "setId": original.id]))
        let correction = try await reloaded.libraryCorrection(id: original.id)
        XCTAssertFalse(correction?.contains("\"exercise\":\"dip\"") == true)
    }
    func testDeletedSetCannotReturnFromQueueReloadOrBackup() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root); let original = record()
        try await store.save(original)
        let backup = try await store.libraryExport()
        try await store.enqueue(original, finalized: true)
        _ = try await store.libraryCommand(command(["action": "delete", "setId": original.id]))
        try await store.flushPending()
        let reloaded = WorkoutRecordStore(directory: root)
        try await reloaded.save(original)
        try await reloaded.libraryRestore(backup)
        let restored = try await reloaded.libraryRecord(id: original.id)
        XCTAssertNil(restored)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(original.id + ".json").path))
    }
    func testBackupRestoresSummaryWithoutExpiredCurvesAndRejectsConflict() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = WorkoutRecordStore(directory: root.appendingPathComponent("source")); let original = record()
        try await source.save(original)
        let backup = try await source.libraryExport()
        let target = WorkoutRecordStore(directory: root.appendingPathComponent("target"))
        try await target.libraryRestore(backup); try await target.libraryRestore(backup)
        let restored = try await target.libraryRecord(id: original.id)
        XCTAssertEqual(restored?.activity?.longestHangSeconds, 8)
        let before = try await target.libraryState()
        let changed = backup.replacingOccurrences(of: "\"longestHangSeconds\":8.0", with: "\"longestHangSeconds\":9.0")
        XCTAssertNotEqual(backup, changed)
        do { try await target.libraryRestore(changed); XCTFail("Immutable conflict must be rejected") } catch { }
        let after = try await target.libraryState()
        XCTAssertEqual(before, after)
    }
    func testCorruptIndexRecoversFromJournalWithoutDroppingTombstones() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root); let original = record(); try await store.save(original)
        _ = try await store.libraryCommand(command(["action": "delete", "setId": original.id]))
        let index = root.appendingPathComponent("Library/index.json")
        try Data("corrupt".utf8).write(to: index, options: .atomic)
        let recovered = WorkoutRecordStore(directory: root)
        let state = try await recovered.libraryState()
        XCTAssertTrue(state.contains(original.id)); XCTAssertNotEqual(try String(contentsOf: index, encoding: .utf8), "corrupt")
        try await recovered.save(original)
        let reappeared = try await recovered.libraryRecord(id: original.id)
        XCTAssertNil(reappeared)
        try Data("corrupt".utf8).write(to: index, options: .atomic)
        try Data("corrupt".utf8).write(to: root.appendingPathComponent("Library/state-journal.json"), options: .atomic)
        do { _ = try await WorkoutRecordStore(directory: root).libraryState(); XCTFail("Loss of both recovery copies must be surfaced") } catch { }
    }
    func testPublishedSummaryMissingFromIndexIsReconciledAfterCrash() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root)
        try await store.save(record())
        let orphan = record()
        let publications = root.appendingPathComponent("Library/Publication")
        try FileManager.default.createDirectory(at: publications, withIntermediateDirectories: true)
        try JSONEncoder().encode(orphan.id).write(to: publications.appendingPathComponent(orphan.id + ".json"), options: .atomic)
        try JSONEncoder().encode(orphan).write(to: root.appendingPathComponent(orphan.id + ".json"), options: .atomic)
        let reloaded = WorkoutRecordStore(directory: root)
        let state = try await reloaded.libraryState()
        XCTAssertTrue(state.contains(orphan.id))
        let backup = try await reloaded.libraryExport()
        try Data(backup.utf8).write(to: root.appendingPathComponent("Library/restore-pending.json"), options: .atomic)
        _ = try await WorkoutRecordStore(directory: root).libraryState()
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("Library/restore-pending.json").path))
    }
    func testColdIndexLoadDoesNotDecodeOldArchiveFiles() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root); let original = record()
        try await store.save(original)
        try Data("unreadable archival detail".utf8).write(to: root.appendingPathComponent(original.id + ".json"), options: .atomic)
        try FileManager.default.removeItem(at: root.appendingPathComponent(original.id + ".motion.json"))
        let state = try await WorkoutRecordStore(directory: root).libraryState()
        XCTAssertTrue(state.contains(original.id))
        do { _ = try await store.libraryRecord(id: original.id); XCTFail("Detail corruption must be surfaced on direct lookup") } catch { }
    }
    func testPhaseAndGripLedgerBackupRestoresWithoutVideoOrCurves() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let sourceRoot = root.appendingPathComponent("source"); let targetRoot = root.appendingPathComponent("target")
        let store = WorkoutRecordStore(directory: sourceRoot); var original = record()
        original.activity?.phases = [WorkoutPhaseSpan(id: "hold", phase: "hang", startedAt: 0, endedAt: 8, observedSeconds: 8)]
        try await store.save(original)
        let grip = WorkoutGripCorrectionStore(directory: sourceRoot.appendingPathComponent("GripCorrections"))
        try await grip.correct(original, repId: nil, orientation: "underhand", width: "narrow")
        let backup = try await store.libraryExport()
        let target = WorkoutRecordStore(directory: targetRoot); try await target.libraryRestore(backup)
        let restored = try await target.libraryRecord(id: original.id)
        XCTAssertEqual(restored?.activity?.phases, original.activity?.phases)
        let annotation = try WorkoutGripCorrectionStore.archiveLedger(directory: targetRoot.appendingPathComponent("GripCorrections"), id: original.id)
        XCTAssertTrue(annotation?.contains("underhand") == true)
        _ = try await target.libraryCommand(command(["action": "delete", "setId": original.id]))
        XCTAssertNil(try WorkoutGripCorrectionStore.archiveLedger(directory: targetRoot.appendingPathComponent("GripCorrections"), id: original.id))
    }
    func testColdMediaRetentionAndInterruptedDraftRecovery() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root); let original = record(); try await store.save(original)
        _ = try await store.libraryCommand(command(["action": "mediaPolicy", "mediaPolicy": ["enabled": true, "maximumAgeDays": 1, "maximumClips": 2, "maximumBytes": 100]]))
        _ = try await store.libraryCommand(command(["action": "attachMedia", "media": ["id": "clip", "setId": original.id, "bytes": 3, "attachedAtEpochMilliseconds": 200_000]]))
        let media = root.appendingPathComponent("Library/Media"); try FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)
        try Data([1,2,3]).write(to: media.appendingPathComponent("clip.mov"))
        let draft = record()
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(draft)) as! [String: Any]; json["endReason"] = "running"
        try JSONSerialization.data(withJSONObject: json).write(to: root.appendingPathComponent(draft.id + ".pending.json"), options: .atomic)
        let recovered = WorkoutRecordStore(directory: root); _ = try await recovered.libraryState()
        let recoveredDraft = try await recovered.libraryRecord(id: draft.id)
        XCTAssertEqual(recoveredDraft?.endReason, "recoveredInterruption")
        XCTAssertEqual(recoveredDraft?.activity?.longestHangSeconds, 8)
        XCTAssertFalse(FileManager.default.fileExists(atPath: media.appendingPathComponent("clip.mov").path))
    }
    func testCrashAfterTombstonePublicationRetriesPhysicalDeletion() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("library-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = WorkoutRecordStore(directory: root); let original = record(); try await store.save(original)
        let gripRoot = root.appendingPathComponent("GripCorrections")
        try await WorkoutGripCorrectionStore(directory: gripRoot).correct(original, repId: nil, orientation: "underhand", width: nil)
        let state = try await store.libraryState()
        let deletion = root.appendingPathComponent("Library/Deletion"); try FileManager.default.createDirectory(at: deletion, withIntermediateDirectories: true)
        try JSONEncoder().encode(original.id).write(to: deletion.appendingPathComponent(original.id + ".json"), options: .atomic)
        // Model a fully published, validated deletion state without linking a
        // second Kotlin runtime into the native XCTest bundle.
        var object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as! [String: Any]
        object["entries"] = []
        object["deletions"] = [["setId": original.id, "deletedAtEpochMilliseconds": 200_000]]
        object["revision"] = (object["revision"] as! Int) + 1
        let next = String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
        try Data(next.utf8).write(to: root.appendingPathComponent("Library/state-journal.json"), options: .atomic)
        try Data(next.utf8).write(to: root.appendingPathComponent("Library/index.json"), options: .atomic)
        // Model termination after publication, before cleanup. Source files still exist.
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(original.id + ".json").path))
        _ = try await WorkoutRecordStore(directory: root).libraryState()
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(original.id + ".json").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(original.id + ".motion.json").path))
        XCTAssertNil(try WorkoutGripCorrectionStore.archiveLedger(directory: gripRoot, id: original.id))
    }
}
