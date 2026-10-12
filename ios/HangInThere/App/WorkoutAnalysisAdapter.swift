import Foundation
import Observation
import WorkoutCore

// Native records use the same shared eligibility rule for current and legacy
// history. No new serialized flag or migration can misclassify an old attempt.
extension WorkoutRepRecord {
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
    private(set) var error: String?
    @ObservationIgnored private var pendingDraft: WorkoutSetRecord?
    @ObservationIgnored private let startFeedback = WorkoutStartFeedback()
    @ObservationIgnored private var startFeedbackTask: Task<Void, Never>?
    private(set) var showsSetStarted = false
    var startSpokenMessage: String { WorkoutStartFeedback.companion.spokenMessage }
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
    private func makeEngine(exercise: ExerciseCounter.Exercise) -> WorkoutAnalysisBridge {
        WorkoutAnalysisBridge(setId: setId, exercise: exercise.rawValue,
            trackedAtEpochMilliseconds: Int64((Date().timeIntervalSince1970 * 1000).rounded()))
    }
    func start(exercise: ExerciseCounter.Exercise) {
        clearStartFeedback()
        setId = UUID().uuidString
        engine = makeEngine(exercise: exercise)
        precondition(engine.policyVersion == ExerciseCounter.policyVersion, "Counting policy versions differ")
        let names = engine.jointNames
        jointIndices = Dictionary(uniqueKeysWithValues: PoseJoint.allCases.map { joint in
            guard let index = names.firstIndex(of: joint.rawValue) else { preconditionFailure("Unsupported pose joint: \(joint)") }
            return (joint, Int32(index))
        })
        revision = -1; reps = []; error = nil; pendingDraft = nil
    }
    func shouldResume(reason: LiveSetSession.EndReason?) -> Bool { WorkoutLifecycle.shared.shouldResume(endReason: reason?.rawValue) }
    func observationOutage(elapsed: Double) -> Bool { WorkoutLifecycle.shared.observationOutage(elapsedSeconds: elapsed) }
    func ready(seconds: Double, eligible: Bool) -> Bool { engine.ready(seconds: seconds, eligible: eligible) }
    func resetReadiness() { engine.resetReadiness() }
    func consume(_ pose: PoseResult, reference: BarSegment?, counter: ExerciseCounter,
                 event: ExerciseCounter.Event?, automaticFinish: Bool) -> LiveSetSession.EndReason? {
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
    func interrupt(reason: String) { clearStartFeedback(); engine.interrupt(reason: reason); refreshRecords() }
    func finish(reason: String) -> WorkoutSetRecord? {
        clearStartFeedback()
        engine.finish(reason: reason); refreshRecords()
        do { return try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(engine.setJson().utf8)) }
        catch { self.error = "Workout analysis could not be read: \(error.localizedDescription)"; return nil }
    }
    private func refreshRecords() {
        guard revision != engine.revision else { return }
        do {
            if let json = engine.lastClosedRecordJson() {
                let record = try JSONDecoder().decode(WorkoutRepRecord.self, from: Data(json.utf8))
                if reps.last?.id != record.id { reps.append(record) }
            }
            if let json = engine.draftDeltaJson() {
                pendingDraft = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(json.utf8))
            }
            revision = engine.revision
        } catch { self.error = "Rep analysis could not be read: \(error.localizedDescription)" }
    }
}

/// Disk I/O stays off the camera/UI actor; each immutable set is atomically saved.
actor WorkoutRecordStore {
    static let shared = WorkoutRecordStore()
    private let directory: URL
    private let writes = WorkoutWriteQueue()
    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Workouts", isDirectory: true)
    }
    func enqueue(_ record: WorkoutSetRecord, finalized: Bool) throws {
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
        guard !record.id.isEmpty, record.id.count <= 64,
              record.id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(record.id).appendingPathExtension("json")
        let motionURL = directory.appendingPathComponent(record.id + ".motion").appendingPathExtension("json")
        let full = try JSONEncoder().encode(record)
        let summaryData = Data(try WorkoutRecordCodec().summaryJson(input: String(decoding: full, as: UTF8.self)).utf8)
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
        try pruneMotion()
    }
    func saveDraft(_ delta: WorkoutSetRecord) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard !delta.id.isEmpty, delta.id.count <= 64, delta.id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        if FileManager.default.fileExists(atPath: directory.appendingPathComponent(delta.id + ".json").path) { return }
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
}
