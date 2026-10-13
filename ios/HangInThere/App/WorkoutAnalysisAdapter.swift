import Foundation
import Observation
import WorkoutCore

// Native records use the same shared eligibility rule for current and legacy
// history. No new serialized flag or migration can misclassify an old attempt.
extension WorkoutRepRecord {
    /// Review actions transfer only the summary, independent of curve retention.
    func evidenceAssessment(setEndReason: String? = nil) throws -> WorkoutAttemptAssessmentRecord {
        var summary = self
        summary.samples = []
        let input = String(decoding: try JSONEncoder().encode(summary), as: UTF8.self)
        let output = try WorkoutEvidenceBridge().assessmentJson(recordJson: input, setEndReason: setEndReason)
        return try JSONDecoder().decode(WorkoutAttemptAssessmentRecord.self, from: Data(output.utf8))
    }
    var isCompleteForAnalytics: Bool {
        WorkoutAnalytics.shared.isCompletedRep(hasAcceptance: acceptedAt != nil,
            hasReturn: returnedAt != nil, closeReason: closeReason)
    }
}
extension WorkoutSetRecord {
    var completedReps: [WorkoutRepRecord] { reps.filter(\.isCompleteForAnalytics) }
    var completedCount: Int { completedReps.count }
}

/// The native session's serial actor owns the Kotlin engine. Views receive only
/// immutable records. There is no unchecked Sendable handle or cross-actor FFI.
@MainActor @Observable
final class WorkoutAnalysisAdapter {
    private(set) var setId = UUID().uuidString
    @ObservationIgnored private lazy var engine = makeEngine(exercise: .pullUp)
    @ObservationIgnored private var jointIndices: [PoseJoint: Int32] = [:]
    @ObservationIgnored private var revision: Int32 = -1
    private(set) var reps: [WorkoutRepRecord] = []
    private(set) var currentRecord: WorkoutSetRecord?
    private(set) var error: String?
    @ObservationIgnored private var pendingDraft: WorkoutSetRecord?
    @ObservationIgnored private let startFeedback = WorkoutStartFeedback()
    @ObservationIgnored private let trackingFeedback = WorkoutTrackingFeedback()
    @ObservationIgnored private var startFeedbackTask: Task<Void, Never>?
    private(set) var showsSetStarted = false
    var gripLabel: String? { currentRecord?.exercise == .pullUp ? engine.gripLabel : nil }
    @ObservationIgnored private var gripTimestamp: Double?
    var startSpokenMessage: String { WorkoutStartFeedback.companion.spokenMessage }
    func trackingSpokenCue(isRunning: Bool, issue: String?) -> String? {
        trackingFeedback.observe(id: setId, running: isRunning, trackingIssue: issue, nowSeconds: ProcessInfo.processInfo.systemUptime)
    }
    @discardableResult
    func beginStartFeedback(isRunning: Bool, nowSeconds: Double = ProcessInfo.processInfo.systemUptime) -> Bool {
        guard startFeedback.begin(setId: setId, live: true, running: isRunning, nowSeconds: nowSeconds) else { return false }
        startFeedbackTask?.cancel()
        showsSetStarted = true
        let duration = startFeedback.remainingSeconds(nowSeconds: nowSeconds)
        startFeedbackTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(duration)) } catch { return }
            self?.showsSetStarted = false
            self?.startFeedbackTask = nil
        }
        return true
    }
    private func clearStartFeedback() {
        startFeedbackTask?.cancel(); startFeedbackTask = nil
        startFeedback.clear(); showsSetStarted = false
    }
    func drainDraft() -> WorkoutSetRecord? { defer { pendingDraft = nil }; return pendingDraft }
    private func makeEngine(exercise: ExerciseCounter.Exercise, recordCalendarTime: Bool = true) -> WorkoutAnalysisBridge {
        WorkoutAnalysisBridge(setId: setId, exercise: exercise.rawValue,
            trackedAtEpochMilliseconds: recordCalendarTime ? Int64((Date().timeIntervalSince1970 * 1000).rounded()) : 0)
    }
    func start(exercise: ExerciseCounter.Exercise, recordCalendarTime: Bool = true) {
        clearStartFeedback()
        setId = UUID().uuidString
        engine = makeEngine(exercise: exercise, recordCalendarTime: recordCalendarTime)
        precondition(engine.policyVersion == ExerciseCounter.policyVersion, "Counting policy versions differ")
        let names = engine.jointNames
        jointIndices = Dictionary(uniqueKeysWithValues: PoseJoint.allCases.map { joint in
            guard let index = names.firstIndex(of: joint.rawValue) else { preconditionFailure("Unsupported pose joint: \(joint)") }
            return (joint, Int32(index))
        })
        gripTimestamp = nil
        revision = -1; reps = []; currentRecord = nil; error = nil; pendingDraft = nil
    }
    func shouldResume(reason: LiveSetSession.EndReason?) -> Bool { WorkoutLifecycle.shared.shouldResume(endReason: reason?.rawValue) }
    func observationOutage(elapsed: Double) -> Bool { WorkoutLifecycle.shared.observationOutage(elapsedSeconds: elapsed) }
    func ready(seconds: Double, eligible: Bool) -> Bool { engine.ready(seconds: seconds, eligible: eligible) }
    func resetReadiness() { engine.resetReadiness() }
    func consume(_ pose: PoseResult, reference: BarSegment?, counter: ExerciseCounter,
                 event: ExerciseCounter.Event?, automaticFinish: Bool) -> LiveSetSession.EndReason? {
        if pose.gripObservation?.seconds != gripTimestamp {
            do {
                let json = try pose.gripObservation.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }
                try engine.gripObservationJson(input: json)
                gripTimestamp = pose.gripObservation?.seconds
            } catch { try? engine.gripObservationJson(input: nil); gripTimestamp = nil }
        }
        let points = pose.people.count == 1 ? pose.people[0].landmarks : []
        let joints = KotlinIntArray(size: Int32(points.count))
        let triples = KotlinDoubleArray(size: Int32(points.count * 3))
        for (i, point) in points.enumerated() {
            joints.set(index: Int32(i), value: jointIndices[point.joint]!)
            triples.set(index: Int32(i * 3), value: point.position.x)
            triples.set(index: Int32(i * 3 + 1), value: point.position.y)
            triples.set(index: Int32(i * 3 + 2), value: point.confidence)
        }
        let referenceArray = KotlinDoubleArray(size: reference == nil ? 0 : 4)
        if let reference {
            for (i, value) in [reference.a.x, reference.a.y, reference.b.x, reference.b.y].enumerated() {
                referenceArray.set(index: Int32(i), value: value)
            }
        }
        let end = engine.observe(seconds: pose.timestamp.seconds, width: pose.imageSize.width,
            height: pose.imageSize.height, peopleCount: Int32(pose.people.count), joints: joints,
            triples: triples, reference: referenceArray, phase: counter.phase.rawValue,
            usable: reference != nil && counter.trackingIssue == nil, backend: pose.backend,
            modelRevision: Int32(pose.requestRevision), trackingSide: counter.side.rawValue, eventOutcome: event?.outcome.rawValue,
            eventTime: event?.sourceSeconds ?? 0, eventReason: event?.reason,
            hasActivity: counter.observedMovements + counter.partialAttempts + counter.interruptedAttempts > 0,
            automaticFinish: automaticFinish)
        refreshRecords()
        return end.flatMap(LiveSetSession.EndReason.init(rawValue:))
    }
    /// Full records are read once when review opens, outside the frame path.
    func reviewRecord() -> WorkoutSetRecord? {
        do {
            var record = try JSONDecoder().decode(WorkoutSetRecord.self,from: Data(engine.reviewDeltaJson().utf8))
            record.reps = reps + record.reps.filter { active in !reps.contains { $0.id == active.id } }
            return record
        }
        catch { self.error = "Workout analysis could not be read: \(error.localizedDescription)"; return nil }
    }
    func interrupt(reason: String) { clearStartFeedback(); engine.interrupt(reason: reason); refreshRecords() }
    func finish(reason: String) -> WorkoutSetRecord? {
        clearStartFeedback()
        engine.finish(reason: reason); refreshRecords()
        do {
            var record = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(engine.setJson().utf8))
            let end = Int64(Date().timeIntervalSince1970 * 1000)
            if let start = record.trackedAtEpochMilliseconds, end >= start { record.trackedEndedAtEpochMilliseconds = end }
            return record
        }
        catch { self.error = "Workout analysis could not be read: \(error.localizedDescription)"; return nil }
    }
    private func refreshRecords() {
        guard revision != engine.revision else { return }
        do {
            if Int(engine.closedRecordCount) > reps.count, let json = engine.lastClosedRecordJson() {
                let record = try JSONDecoder().decode(WorkoutRepRecord.self, from: Data(json.utf8))
                if reps.last?.id != record.id { reps.append(record) }
            }
            if let json = engine.draftDeltaJson() {
                pendingDraft = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(json.utf8))
                if let delta = pendingDraft {
                    let record = WorkoutSetRecord(id: delta.id, exercise: delta.exercise, startedAt: delta.startedAt,
                        endedAt: delta.endedAt, endReason: delta.endReason, reps: reps, backend: delta.backend,
                        modelRevision: delta.modelRevision, schemaVersion: delta.schemaVersion, lifecycleVersion: delta.lifecycleVersion,
                        trackingSide: delta.trackingSide, imageSize: delta.imageSize, referenceEdge: delta.referenceEdge,
                        trackedAtEpochMilliseconds: delta.trackedAtEpochMilliseconds, activity: delta.activity, grip: delta.grip)
                    currentRecord = record
                }
            }
            revision = engine.revision
        } catch { self.error = "Rep analysis could not be read: \(error.localizedDescription)" }
    }
}

/// Disk I/O stays off the camera/UI actor; each immutable set is atomically saved.
actor WorkoutRecordStore {
    static let shared = WorkoutRecordStore()
    private let directory: URL
    private let gripDirectory: URL
    private let writes = WorkoutWriteQueue()
    private var activeJournalIds: Set<String> = []
    private var libraryCache: String?
    private var libraryDeletedIds: Set<String> = []
    private var restoringLibrary = false
    init(directory: URL? = nil) {
        gripDirectory = directory?.appendingPathComponent("GripCorrections", isDirectory: true) ?? WorkoutGripCorrectionStore.archiveDirectory
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Workouts", isDirectory: true)
    }
    func enqueue(_ record: WorkoutSetRecord, finalized: Bool) throws {
        // Enqueue must survive unavailable storage. The durable tombstone check
        // happens before publication; a loaded in-memory index can reject sooner.
        if libraryDeletedIds.contains(record.id) { return }
        let data = try JSONEncoder().encode(record)
        try writes.enqueueJson(input: String(decoding: data, as: UTF8.self), finalized: finalized)
    }
    func flushPending() throws {
        while let json = writes.nextJson() {
            let token = writes.nextToken
            let record = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(json.utf8))
            if writes.nextFinalized { try save(record) } else { try saveDraft(record) }
            writes.acknowledge(token: token)
        }
    }
    func save(_ record: WorkoutSetRecord) throws {
        guard try !libraryDeleted(record.id) else { writes.discard(setId: record.id); return }
        guard !record.id.isEmpty, record.id.count <= 64,
              record.id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(record.id).appendingPathExtension("json")
        let motionURL = directory.appendingPathComponent(record.id + ".motion").appendingPathExtension("json")
        let full = try JSONEncoder().encode(record)
        let summaryData = Data(try WorkoutRecordCodec().summaryJson(input: String(decoding: full, as: UTF8.self)).utf8)
        try FileManager.default.createDirectory(at: publicationsDirectory, withIntermediateDirectories: true)
        let publication = publicationsDirectory.appendingPathComponent(record.id + ".json")
        try JSONEncoder().encode(record.id).write(to: publication, options: .atomic)
        if FileManager.default.fileExists(atPath: url.path) {
            let existing = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(contentsOf: url))
            let summary = try JSONDecoder().decode(WorkoutSetRecord.self, from: summaryData)
            guard existing == summary else { throw CocoaError(.fileWriteFileExists) }
        } else {
            // Publish the summary last. A crash during the curve write cannot
            // expose a partially written workout.
            try full.write(to: motionURL, options: .atomic)
            try summaryData.write(to: url, options: .atomic)
        }
        let pending = directory.appendingPathComponent(record.id + ".pending.json")
        if FileManager.default.fileExists(atPath: pending.path) { try FileManager.default.removeItem(at: pending) }
        activeJournalIds.remove(record.id)
        try registerLibrary([record])
        try FileManager.default.removeItem(at: publication)
        try pruneMotion()
    }
    func saveDraft(_ delta: WorkoutSetRecord) throws {
        guard try !libraryDeleted(delta.id) else { writes.discard(setId: delta.id); return }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard !delta.id.isEmpty, delta.id.count <= 64, delta.id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        if FileManager.default.fileExists(atPath: directory.appendingPathComponent(delta.id + ".json").path) { return }
        activeJournalIds.insert(delta.id)
        let file = directory.appendingPathComponent(delta.id + ".pending.json")
        let previous = FileManager.default.fileExists(atPath: file.path) ? try String(contentsOf: file, encoding: .utf8) : nil
        let data = try JSONEncoder().encode(delta)
        let merged = try WorkoutRecordCodec().mergeDraftJson(previous: previous, delta: String(decoding: data, as: UTF8.self))
        try Data(merged.utf8).write(to: file, options: .atomic)
    }
    private func pruneMotion() throws {
        let files = try orderedFiles().filter { $0.lastPathComponent.hasSuffix(".motion.json") }
        var bytes: Int64 = 0
        for (index, file) in files.enumerated() {
            bytes += Int64(try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
            if index >= Int(WorkoutRecordCodec.companion.maximumFeatureSets) || bytes > WorkoutRecordCodec.companion.maximumFeatureBytes { try FileManager.default.removeItem(at: file) }
        }
    }
    private func orderedFiles() throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])
            .sorted {
                (try $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? .distantPast) >
                (try $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate ?? .distantPast)
            }
    }
    /// Scan immutable compact summaries off the camera actor. All-time records
    /// survive the 50-set detail window; motion sidecars are never loaded here.
    func progressEvidence(excludingSetId: String? = nil) throws -> [WorkoutProgressEvidence] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        let bridge = WorkoutProgressBridge()
        return try orderedFiles().filter { file in
                guard file.pathExtension == "json", !file.lastPathComponent.hasSuffix(".motion.json") else { return false }
                if file.lastPathComponent.hasSuffix(".pending.json") {
                    let id = String(file.lastPathComponent.dropLast(".pending.json".count))
                    return id != excludingSetId && !activeJournalIds.contains(id) &&
                        !FileManager.default.fileExists(atPath: directory.appendingPathComponent(id + ".json").path)
                }
                return true
            }.map { file in
                var json = try String(contentsOf: file, encoding: .utf8)
                if file.lastPathComponent.hasSuffix(".pending.json") { json = try WorkoutRecordCodec().recoveryJson(input: json) }
                return try JSONDecoder().decode(WorkoutProgressEvidence.self, from: Data(try bridge.evidenceJson(recordJson: json).utf8))
            }
    }
    func recent() throws -> [WorkoutSetRecord] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        return try orderedFiles().filter { file in
                file.pathExtension == "json" && !file.lastPathComponent.hasSuffix(".motion.json") &&
                !(file.lastPathComponent.hasSuffix(".pending.json") && FileManager.default.fileExists(atPath:
                    directory.appendingPathComponent(String(file.lastPathComponent.dropLast(".pending.json".count)) + ".json").path))
            }
            .prefix(50).map { summary in
                if summary.lastPathComponent.hasSuffix(".pending.json") {
                    let data = try String(contentsOf: summary, encoding: .utf8)
                    return try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(try WorkoutRecordCodec().recoveryJson(input: data).utf8))
                }
                let motion = directory.appendingPathComponent(summary.deletingPathExtension().lastPathComponent + ".motion.json")
                let file = FileManager.default.fileExists(atPath: motion.path) ? motion : summary
                return try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(contentsOf: file))
            }
    }

    // Application state lives in a separate directory so legacy summary scans
    // cannot mistake a correction/index/backup for a measured workout.
    private var libraryDirectory: URL { directory.appendingPathComponent("Library", isDirectory: true) }
    private var libraryURL: URL { libraryDirectory.appendingPathComponent("index.json") }
    private var libraryJournalURL: URL { libraryDirectory.appendingPathComponent("state-journal.json") }
    private var publicationsDirectory: URL { libraryDirectory.appendingPathComponent("Publication", isDirectory: true) }
    private var deletionDirectory: URL { libraryDirectory.appendingPathComponent("Deletion", isDirectory: true) }
    private func markDeletion(_ id: String) throws {
        try FileManager.default.createDirectory(at: deletionDirectory, withIntermediateDirectories: true)
        try JSONEncoder().encode(id).write(to: deletionDirectory.appendingPathComponent(id + ".json"), options: .atomic)
    }
    private func cleanupDeletions() throws {
        guard FileManager.default.fileExists(atPath: deletionDirectory.path) else { return }
        for marker in try FileManager.default.contentsOfDirectory(at: deletionDirectory, includingPropertiesForKeys: nil) where marker.pathExtension == "json" {
            let id = try JSONDecoder().decode(String.self, from: Data(contentsOf: marker))
            guard !id.isEmpty, id.count <= 64, id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }), marker.lastPathComponent == id + ".json" else { throw CocoaError(.fileReadCorruptFile) }
            if libraryDeletedIds.contains(id) {
                writes.discard(setId: id); activeJournalIds.remove(id)
                try WorkoutGripCorrectionStore.deleteLedger(directory: gripDirectory, id: id)
                for suffix in [".json", ".motion.json", ".pending.json"] {
                    let file = directory.appendingPathComponent(id + suffix)
                    if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
                }
            }
            try FileManager.default.removeItem(at: marker)
        }
    }
    func libraryState() throws -> String {
        if let libraryCache {
            try cleanupDeletions()
            let pending = libraryDirectory.appendingPathComponent("restore-pending.json")
            if !restoringLibrary && FileManager.default.fileExists(atPath: pending.path) {
                try libraryRestore(String(contentsOf: pending, encoding: .utf8))
                return self.libraryCache ?? libraryCache
            }
            return libraryCache
        }
        let bridge = WorkoutLibraryBridge()
        var restored: String?
        if FileManager.default.fileExists(atPath: libraryJournalURL.path) {
            restored = try bridge.validateJson(input: String(contentsOf: libraryJournalURL, encoding: .utf8))
        } else if FileManager.default.fileExists(atPath: libraryURL.path) {
            restored = try bridge.validateJson(input: String(contentsOf: libraryURL, encoding: .utf8))
        }
        if let restored {
            let object = try JSONSerialization.jsonObject(with: Data(restored.utf8)) as? [String: Any]
            libraryDeletedIds = Set((object?["deletions"] as? [[String: Any]] ?? []).compactMap { $0["setId"] as? String })
            // Tombstones authorize cleanup before parsing any residual source
            // files; a corrupt deleted draft must not block physical erasure.
            try cleanupDeletions()
        }
        let publications = FileManager.default.fileExists(atPath: publicationsDirectory.path) ? try FileManager.default.contentsOfDirectory(at: publicationsDirectory, includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" } : []
        let pendingSummaries = try publications.compactMap { marker -> URL? in
            let id = try JSONDecoder().decode(String.self, from: Data(contentsOf: marker))
            guard !id.isEmpty, id.count <= 64, id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }), marker.lastPathComponent == id + ".json" else { throw CocoaError(.fileReadCorruptFile) }
            let file = directory.appendingPathComponent(id + ".json")
            return FileManager.default.fileExists(atPath: file.path) ? file : nil
        }
        // Initial migration scans once. A valid recovery journal makes routine
        // cold loads independent of the number of archival summary files.
        let files = restored != nil ? pendingSummaries : (FileManager.default.fileExists(atPath: directory.path) ? try orderedFiles() : [])
        var records = try files.filter {
            $0.pathExtension == "json" && !$0.lastPathComponent.hasSuffix(".motion.json") && !$0.lastPathComponent.hasSuffix(".pending.json")
        }.map { try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(contentsOf: $0)) }
        let drafts = FileManager.default.fileExists(atPath: directory.path) ? try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil).filter {
            let name = $0.lastPathComponent
            return name.hasSuffix(".pending.json") && !activeJournalIds.contains(String(name.dropLast(".pending.json".count))) &&
                !FileManager.default.fileExists(atPath: directory.appendingPathComponent(String(name.dropLast(".pending.json".count)) + ".json").path)
        } : []
        records += try drafts.map { file in
            let recovered = try WorkoutRecordCodec().recoveryJson(input: String(contentsOf: file, encoding: .utf8))
            return try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(recovered.utf8))
        }
        let reconciled = try bridge.registerJson(stateJson: restored ?? bridge.emptyJson(), recordsJson: String(decoding: JSONEncoder().encode(records), as: UTF8.self))
        let state = try bridge.maintainJson(stateJson: reconciled, nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
        try publishLibrary(state)
        try cleanupDeletions()
        try pruneLibraryMedia(state)
        for marker in publications { try FileManager.default.removeItem(at: marker) }
        let pending = libraryDirectory.appendingPathComponent("restore-pending.json")
        if !restoringLibrary && FileManager.default.fileExists(atPath: pending.path) { try libraryRestore(String(contentsOf: pending, encoding: .utf8)) }
        return libraryCache ?? state
    }
    private func publishLibrary(_ state: String) throws {
        try FileManager.default.createDirectory(at: libraryDirectory, withIntermediateDirectories: true)
        // Authoritative recovery journal is published first. The index can be
        // rebuilt without losing corrections, consent or deletion tombstones.
        try Data(state.utf8).write(to: libraryJournalURL, options: .atomic)
        libraryCache = state
        let object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as? [String: Any]
        libraryDeletedIds = Set((object?["deletions"] as? [[String: Any]] ?? []).compactMap { $0["setId"] as? String })
        try Data(state.utf8).write(to: libraryURL, options: .atomic)
    }
    private func libraryDeleted(_ id: String) throws -> Bool {
        if libraryCache == nil { _ = try libraryState() }
        return libraryDeletedIds.contains(id)
    }
    private func registerLibrary(_ records: [WorkoutSetRecord]) throws {
        let old = try libraryState()
        let next = try WorkoutLibraryBridge().registerJson(stateJson: old, recordsJson: String(decoding: JSONEncoder().encode(records), as: UTF8.self))
        if next != old { try publishLibrary(next) }
    }
    func libraryCommand(_ command: String) throws -> String {
        let next = try WorkoutLibraryBridge().applyJson(stateJson: libraryState(), commandJson: command)
        let object = try JSONSerialization.jsonObject(with: Data(command.utf8)) as? [String: Any]
        if object?["action"] as? String == "delete", let id = object?["setId"] as? String { try markDeletion(id) }
        // Publish tombstones before deleting data; a failed cleanup stays deleted.
        try publishLibrary(next)
        try cleanupDeletions()
        try pruneLibraryMedia(next)
        return next
    }
    func libraryRecord(id: String) throws -> WorkoutSetRecord? {
        guard id.count <= 64, id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }), try !libraryDeleted(id) else { return nil }
        let summary = directory.appendingPathComponent(id + ".json")
        let motion = directory.appendingPathComponent(id + ".motion.json")
        if FileManager.default.fileExists(atPath: summary.path) {
            return try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(contentsOf: FileManager.default.fileExists(atPath: motion.path) ? motion : summary))
        }
        let pending = directory.appendingPathComponent(id + ".pending.json")
        if !activeJournalIds.contains(id), FileManager.default.fileExists(atPath: pending.path) {
            return try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(try WorkoutRecordCodec().recoveryJson(input: String(contentsOf: pending, encoding: .utf8)).utf8))
        }
        // Restored backups preserve compact evidence even without an original curve sidecar.
        let object = try JSONSerialization.jsonObject(with: Data(libraryState().utf8)) as? [String: Any]
        guard let entry = (object?["entries"] as? [[String: Any]])?.first(where: { ($0["measured"] as? [String: Any])?["id"] as? String == id }), let measured = entry["measured"] else { return nil }
        return try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: measured))
    }
    func libraryExport() throws -> String {
        let state = try libraryState()
        let object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as? [String: Any] ?? [:]
        let entries = object["entries"] as? [[String: Any]] ?? []
        let records = try entries.compactMap { entry -> WorkoutSetRecord? in
            guard let id = (entry["measured"] as? [String: Any])?["id"] as? String, let record = try libraryRecord(id: id) else { return nil }
            let raw = String(decoding: try JSONEncoder().encode(record), as: UTF8.self)
            return try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(try WorkoutRecordCodec().summaryJson(input: raw).utf8))
        }
        let ledgers = try records.compactMap { try WorkoutGripCorrectionStore.archiveLedger(directory: gripDirectory, id: $0.id) }
        return try WorkoutLibraryBridge().exportJson(stateJson: state, summariesJson: String(decoding: JSONEncoder().encode(records), as: UTF8.self), gripCorrectionsJson: "[" + ledgers.joined(separator: ",") + "]")
    }
    func libraryDiagnostics() throws -> String { try WorkoutLibraryBridge().diagnosticsJson(stateJson: libraryState()) }
    func libraryAfterSet(id: String) throws -> WorkoutAfterSetReport {
        let json = try WorkoutFeedbackBridge().librarySummaryJson(stateJson: libraryState(), setId: id)
        return try JSONDecoder().decode(WorkoutAfterSetReport.self, from: Data(json.utf8))
    }
    func libraryDashboard(key: String?, cursor: String?, now: Int64, offset: Int32) throws -> String {
        let prior = try libraryState()
        let bridge = WorkoutLibraryBridge()
        let state = try bridge.maintainJson(stateJson: prior, nowEpochMilliseconds: now)
        if state != prior { try publishLibrary(state) }
        try pruneLibraryMedia(state)
        let object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as? [String: Any] ?? [:]
        let dashboard: [String: Any] = [
            "page": try JSONSerialization.jsonObject(with: Data(bridge.pageJson(stateJson: state, cursorJson: cursor, limit: 25).utf8)),
            "insights": try JSONSerialization.jsonObject(with: Data(bridge.insightsJson(stateJson: state, comparisonKey: key, nowEpochMilliseconds: now, utcOffsetMinutes: offset).utf8)),
            "mediaPolicy": object["mediaPolicy"] ?? [:], "media": object["media"] ?? [],
            "diagnosticsConsent": object["diagnosticsConsent"] ?? false, "revision": object["revision"] ?? 0]
        return String(decoding: try JSONSerialization.data(withJSONObject: dashboard), as: UTF8.self)
    }
    func libraryCorrection(id: String) throws -> String? {
        let object = try JSONSerialization.jsonObject(with: Data(libraryState().utf8)) as? [String: Any]
        let events = (object?["reviews"] as? [String: Any])?["events"] as? [[String: Any]] ?? []
        guard let correction = events.last(where: { $0["setId"] as? String == id })?["correction"] else { return nil }
        return String(decoding: try JSONSerialization.data(withJSONObject: correction), as: UTF8.self)
    }
    func libraryRestore(_ backup: String) throws {
        let wasRestoring = restoringLibrary
        restoringLibrary = true
        defer { restoringLibrary = wasRestoring }
        let next = try WorkoutLibraryBridge().restoreJson(stateJson: libraryState(), backupJson: backup)
        let object = try JSONSerialization.jsonObject(with: Data(backup.utf8)) as? [String: Any] ?? [:]
        let summaries = try (object["measuredSummaries"] as? [[String: Any]] ?? []).map { try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: $0)) }
        let ledgers = object["gripCorrections"] as? [[String: Any]] ?? []
        for ledger in ledgers {
            guard let id = ledger["setId"] as? String, let record = summaries.first(where: { $0.id == id }) else { throw CocoaError(.fileReadCorruptFile) }
            try WorkoutGripCorrectionStore.restoreLedger(directory: gripDirectory, record: record, incoming: String(decoding: JSONSerialization.data(withJSONObject: ledger), as: UTF8.self), publish: false)
        }
        // Validate every native archival conflict before publishing any change.
        for record in summaries {
            let file = directory.appendingPathComponent(record.id + ".json")
            if FileManager.default.fileExists(atPath: file.path) {
                guard try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(contentsOf: file)) == record else { throw CocoaError(.fileWriteFileExists) }
            }
        }
        let pending = libraryDirectory.appendingPathComponent("restore-pending.json")
        try Data(backup.utf8).write(to: pending, options: .atomic)
        let nextObject = try JSONSerialization.jsonObject(with: Data(next.utf8)) as? [String: Any]
        for deletion in nextObject?["deletions"] as? [[String: Any]] ?? [] {
            if let id = deletion["setId"] as? String, !libraryDeletedIds.contains(id) { try markDeletion(id) }
        }
        try publishLibrary(next)
        try cleanupDeletions()
        for record in summaries {
            if try libraryDeleted(record.id) { continue }
            let file = directory.appendingPathComponent(record.id + ".json")
            if !FileManager.default.fileExists(atPath: file.path) { try JSONEncoder().encode(record).write(to: file, options: .atomic) }
        }
        for ledger in ledgers {
            guard let id = ledger["setId"] as? String, try !libraryDeleted(id), let record = summaries.first(where: { $0.id == id }) else { continue }
            try WorkoutGripCorrectionStore.restoreLedger(directory: gripDirectory, record: record, incoming: String(decoding: JSONSerialization.data(withJSONObject: ledger), as: UTF8.self), publish: true)
        }
        try pruneLibraryMedia(next)
        try FileManager.default.removeItem(at: pending)
    }
    func libraryRestore(source: URL) throws {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        guard (try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= 32 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
        try libraryRestore(String(contentsOf: source, encoding: .utf8))
    }
    private var mediaDirectory: URL { libraryDirectory.appendingPathComponent("Media", isDirectory: true) }
    private func pruneLibraryMedia(_ state: String) throws {
        let object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as? [String: Any]
        let retained = Set((object?["media"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String })
        if FileManager.default.fileExists(atPath: mediaDirectory.path) {
            for file in try FileManager.default.contentsOfDirectory(at: mediaDirectory, includingPropertiesForKeys: nil) where !retained.contains(file.deletingPathExtension().lastPathComponent) {
                try FileManager.default.removeItem(at: file)
            }
        }
    }
    func attachLibraryMedia(source: URL, setId: String) throws -> String {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let bytes = try source.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard bytes.isRegularFile == true, let count = bytes.fileSize, count > 0, count <= 1024 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
        let id = UUID().uuidString
        let command: [String: Any] = ["action": "attachMedia", "atEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000),
            "media": ["id": id, "setId": setId, "bytes": count, "attachedAtEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000)]]
        let commandJSON = String(decoding: try JSONSerialization.data(withJSONObject: command), as: UTF8.self)
        let next = try WorkoutLibraryBridge().applyJson(stateJson: libraryState(), commandJson: commandJSON)
        let object = try JSONSerialization.jsonObject(with: Data(next.utf8)) as? [String: Any]
        guard (object?["media"] as? [[String: Any]])?.contains(where: { $0["id"] as? String == id }) == true else { throw CocoaError(.fileWriteOutOfSpace) }
        try FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
        let target = mediaDirectory.appendingPathComponent(id + ".mov")
        try FileManager.default.copyItem(at: source, to: target)
        do { try publishLibrary(next); try pruneLibraryMedia(next) }
        catch { try? FileManager.default.removeItem(at: target); throw error }
        return id
    }
    func libraryMediaURL(id: String) throws -> URL? {
        guard id.count <= 64, id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else { return nil }
        let old = try libraryState()
        let state = try WorkoutLibraryBridge().maintainJson(stateJson: old, nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
        if state != old { try publishLibrary(state) }
        try pruneLibraryMedia(state)
        let object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as? [String: Any]
        guard (object?["media"] as? [[String: Any]])?.contains(where: { $0["id"] as? String == id }) == true else { return nil }
        let url = mediaDirectory.appendingPathComponent(id + ".mov")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }
}
