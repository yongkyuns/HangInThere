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
    static var startingEvidencePolicyVersion: Int { Int(WorkoutStartEvidence.shared.policyVersion) }
    private(set) var setId = UUID().uuidString
    @ObservationIgnored private lazy var engine = makeEngine(exercise: .pullUp)
    @ObservationIgnored private var bodyDipEngine: RelativeDipWorkoutBridge?
    @ObservationIgnored private let localDipAthleteSelection = BodyAthleteSelection()
    private(set) var bodyDipSnapshot: BodyDipWorkoutSnapshot?
    @ObservationIgnored private var bodyPullUpEngine: BodyPullUpWorkoutBridge?
    @ObservationIgnored private var overheadPullUpEngine: OverheadPullUpWorkoutBridge?
    private(set) var bodyPullUpSnapshot: BodyPullUpWorkoutSnapshot?
    @ObservationIgnored private var jointIndices: [PoseJoint: Int32] = [:]
    @ObservationIgnored private lazy var framingBridge = WorkoutFramingBridge()
    @ObservationIgnored private lazy var setupRecovery = WorkoutSetupRecoveryBridge()
    private(set) var framingHint: String?
    @ObservationIgnored private var revision: Int32 = -1
    private(set) var reps: [WorkoutRepRecord] = []
    private(set) var currentRecord: WorkoutSetRecord?
    private(set) var error: String?
    @ObservationIgnored private var pendingDraft: WorkoutSetRecord?
    @ObservationIgnored private let startFeedback = WorkoutStartFeedback()
    @ObservationIgnored private let trackingFeedback = WorkoutTrackingFeedback()
    @ObservationIgnored private var startFeedbackTask: Task<Void, Never>?
    @ObservationIgnored private let feedbackClock: @MainActor () -> Double
    private var startCueVisible = false
    var showsSetStarted: Bool {
        startCueVisible && startFeedback.remainingSeconds(nowSeconds: feedbackClock()) > 0
    }
    // Presentation-only monotonic time, independent of workout source PTS.
    init(feedbackClock: @escaping @MainActor () -> Double = { ProcessInfo.processInfo.systemUptime }) {
        self.feedbackClock = feedbackClock
    }
    var gripLabel: String? { currentRecord?.exercise == .pullUp ? (overheadPullUpEngine?.gripLabel ?? bodyPullUpEngine?.gripLabel ?? engine.gripLabel) : nil }
    @ObservationIgnored private var gripTimestamp: Double?
    var startSpokenMessage: String { WorkoutStartFeedback.companion.spokenMessage }
    func trackingSpokenCue(isRunning: Bool, issue: String?) -> String? {
        trackingFeedback.observe(id: setId, running: isRunning, trackingIssue: issue, nowSeconds: ProcessInfo.processInfo.systemUptime)
    }
    @discardableResult
    func beginStartFeedback(isRunning: Bool) -> Bool {
        beginStartFeedback(isRunning: isRunning, nowSeconds: feedbackClock())
    }
    @discardableResult
    func beginStartFeedback(isRunning: Bool, nowSeconds: Double) -> Bool {
        guard startFeedback.begin(setId: setId, live: true, running: isRunning,
                                  nowSeconds: nowSeconds) else { return false }
        startFeedbackTask?.cancel()
        startCueVisible = true
        let duration = startFeedback.remainingSeconds(nowSeconds: feedbackClock())
        let deadline = ContinuousClock.now.advanced(by: .seconds(duration))
        let owner = setId
        startFeedbackTask = Task { [weak self] in
            do { try await Task.sleep(until: deadline, clock: .continuous) } catch { return }
            guard !Task.isCancelled, let self, self.setId == owner else { return }
            self.startCueVisible = false
            self.startFeedbackTask = nil
        }
        return true
    }
    private func clearStartFeedback() {
        startFeedbackTask?.cancel(); startFeedbackTask = nil
        startFeedback.clear(); startCueVisible = false
    }
    func drainDraft() -> WorkoutSetRecord? { defer { pendingDraft = nil }; return pendingDraft }
    private func makeEngine(exercise: ExerciseCounter.Exercise, recordCalendarTime: Bool = true) -> WorkoutAnalysisBridge {
        WorkoutAnalysisBridge(setId: setId, exercise: exercise.rawValue,
            trackedAtEpochMilliseconds: recordCalendarTime ? Int64((Date().timeIntervalSince1970 * 1000).rounded()) : 0)
    }
    func start(exercise: ExerciseCounter.Exercise, recordCalendarTime: Bool = true) {
        clearStartFeedback()
        setId = UUID().uuidString
        bodyDipEngine = nil; bodyDipSnapshot = nil
        bodyPullUpEngine = nil; overheadPullUpEngine = nil; bodyPullUpSnapshot = nil
        engine = makeEngine(exercise: exercise, recordCalendarTime: recordCalendarTime)
        precondition(engine.policyVersion == ExerciseCounter.policyVersion, "Counting policy versions differ")
        jointIndices = makeJointIndices(engine.jointNames)
        gripTimestamp = nil
        revision = -1; reps = []; currentRecord = nil; error = nil; pendingDraft = nil
    }
    func resetBodyDipPreparation() {
        bodyDipEngine = nil; bodyDipSnapshot = nil
    }
    func resetBodyPullUpPreparation() {
        bodyPullUpEngine = nil; overheadPullUpEngine = nil; bodyPullUpSnapshot = nil
    }
    func observeBodyPullUp(_ pose: PoseResult, reference: BarSegment?, camera: String,
                          automaticStart: Bool,
                          recordCalendarTime: Bool = true) -> BodyPullUpWorkoutSnapshot? {
        if bodyPullUpEngine == nil {
            start(exercise: .pullUp, recordCalendarTime: recordCalendarTime)
            bodyPullUpEngine = BodyPullUpWorkoutBridge(setId: setId,
                trackedAtEpochMilliseconds: recordCalendarTime ? Int64(Date().timeIntervalSince1970 * 1000) : 0)
        }
        guard let bodyPullUpEngine else { return nil }
        do {
            let edge = try reference.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }
            let raw = try bodyPullUpEngine.observePreparedJson(poseJson: String(decoding: JSONEncoder().encode(pose), as: UTF8.self),
                referenceJson: edge, camera: camera, automaticStart: automaticStart)
            let snapshot = try JSONDecoder().decode(BodyPullUpWorkoutSnapshot.self, from: Data(raw.utf8))
            if bodyPullUpSnapshot?.started != snapshot.started { revision = -1 }
            bodyPullUpSnapshot = snapshot
            refreshRecords()
            return snapshot
        } catch {
            self.error = "Pull-up analysis could not be read: \(error.localizedDescription)"
            return nil
        }
    }
    /// The live route shares the original source selection with framing and
    /// camera setup. Retain observeBodyPullUp for older explicit bar clients.
    func observeOverheadPullUp(_ pose: PoseResult, selection: BodyAthleteSelection? = nil,
                              camera: String, automaticStart: Bool,
                              recordCalendarTime: Bool = true) -> BodyPullUpWorkoutSnapshot? {
        if overheadPullUpEngine == nil {
            start(exercise: .pullUp, recordCalendarTime: recordCalendarTime)
            overheadPullUpEngine = OverheadPullUpWorkoutBridge(setId: setId,
                trackedAtEpochMilliseconds: recordCalendarTime ? Int64(Date().timeIntervalSince1970 * 1000) : 0)
        }
        guard let overheadPullUpEngine else { return nil }
        do {
            let selected = selection ?? localDipAthleteSelection
            if selection == nil {
                _ = try selected.observeJson(poseJson: String(decoding: JSONEncoder().encode(pose), as: UTF8.self))
            }
            let raw = try overheadPullUpEngine.observeSelectedJson(selection: selected,
                sourceValue: pose.timestamp.value, sourceTimescale: Int32(pose.timestamp.timescale),
                camera: camera, automaticFinish: true, automaticStart: automaticStart)
            let snapshot = try JSONDecoder().decode(BodyPullUpWorkoutSnapshot.self, from: Data(raw.utf8))
            if bodyPullUpSnapshot?.started != snapshot.started { revision = -1 }
            bodyPullUpSnapshot = snapshot
            refreshRecords()
            return snapshot
        } catch {
            self.error = "Pull-up analysis could not be read: \(error.localizedDescription)"
            return nil
        }
    }
    func startBodyPullUpManually() -> Bool {
        let started = overheadPullUpEngine?.startManually() ?? bodyPullUpEngine?.startManually() ?? false
        guard started else { return false }
        revision = -1; refreshRecords()
        return true
    }
    func observeBodyDip(_ pose: PoseResult, selection: BodyAthleteSelection? = nil, camera: String, automaticStart: Bool,
                        recordCalendarTime: Bool = true) -> BodyDipWorkoutSnapshot? {
        if bodyDipEngine == nil {
            start(exercise: .dip, recordCalendarTime: recordCalendarTime)
            bodyDipEngine = RelativeDipWorkoutBridge(setId: setId,
                trackedAtEpochMilliseconds: recordCalendarTime ? Int64(Date().timeIntervalSince1970 * 1000) : 0)
        }
        guard let bodyDipEngine else { return nil }
        do {
            let selected = selection ?? localDipAthleteSelection
            if selection == nil {
                _ = try selected.observeJson(poseJson: String(decoding: JSONEncoder().encode(pose), as: UTF8.self))
            }
            let raw = try bodyDipEngine.observeSelectedJson(selection: selected,
                sourceValue: pose.timestamp.value, sourceTimescale: Int32(pose.timestamp.timescale),
                camera: camera, automaticFinish: true, automaticStart: automaticStart)
            let snapshot = try JSONDecoder().decode(BodyDipWorkoutSnapshot.self, from: Data(raw.utf8))
            if bodyDipSnapshot?.started != snapshot.started { revision = -1 }
            bodyDipSnapshot = snapshot
            refreshRecords()
            return snapshot
        } catch {
            self.error = "Dip analysis could not be read: \(error.localizedDescription)"
            return nil
        }
    }
    func startBodyDipManually() -> Bool {
        guard bodyDipEngine?.startManually() == true else { return false }
        revision = -1
        refreshRecords()
        return true
    }
    func shouldResume(reason: LiveSetSession.EndReason?) -> Bool { WorkoutLifecycle.shared.shouldResume(endReason: reason?.rawValue) }
    func observationOutage(elapsed: Double) -> Bool { WorkoutLifecycle.shared.observationOutage(elapsedSeconds: elapsed) }
    func ready(seconds: Double, eligible: Bool) -> Bool { engine.ready(seconds: seconds, eligible: eligible) }
    func ready(at time: PresentationTime, eligible: Bool) -> Bool {
        engine.readyAt(sourceValue: time.value, sourceTimescale: time.timescale, eligible: eligible)
    }
    func hasStartingPose(_ pose: PoseResult, exercise: ExerciseCounter.Exercise, automatic: Bool,
                         side: ArmMeasurement.Side, reference: BarSegment?) -> Bool {
        let payload = poseTransport(pose)
        let values = reference.map { [$0.a.x, $0.a.y, $0.b.x, $0.b.y] } ?? []
        let edge = KotlinDoubleArray(size: Int32(values.count))
        for (i, value) in values.enumerated() { edge.set(index: Int32(i), value: value) }
        do {
            return try engine.isStartingPose(sourceValue: pose.timestamp.value, sourceTimescale: pose.timestamp.timescale,
                width: pose.imageSize.width, height: pose.imageSize.height,
                peopleCount: Int32(pose.people.count), joints: payload.joints, triples: payload.triples,
                exercise: exercise.rawValue, automatic: automatic, side: side.rawValue, reference: edge)
        } catch { return false }
    }
    func resetReadiness() { engine.resetReadiness() }
    func resetSetupRecovery() { setupRecovery.reset() }
    func retainSetup(_ pose: PoseResult, exercise: ExerciseCounter.Exercise?) -> Bool {
        let payload = poseTransport(pose)
        do {
            return try setupRecovery.retain(sourceValue: pose.timestamp.value, sourceTimescale: pose.timestamp.timescale,
                width: pose.imageSize.width, height: pose.imageSize.height, peopleCount: Int32(pose.people.count),
                joints: payload.joints, triples: payload.triples, exercise: exercise?.rawValue)
        } catch { setupRecovery.reset(); return false }
    }
    private func makeJointIndices(_ names: [String]) -> [PoseJoint: Int32] {
        Dictionary(uniqueKeysWithValues: PoseJoint.allCases.map { joint in
            guard let index = names.firstIndex(of: joint.rawValue) else { preconditionFailure("Unsupported pose joint: \(joint)") }
            return (joint, Int32(index))
        })
    }
    private func poseTransport(_ pose: PoseResult) -> (joints: KotlinIntArray, triples: KotlinDoubleArray) {
        if jointIndices.isEmpty { jointIndices = makeJointIndices(framingBridge.jointNames) }
        let points = pose.people.count == 1 ? pose.people[0].landmarks : []
        let joints = KotlinIntArray(size: Int32(points.count))
        let triples = KotlinDoubleArray(size: Int32(points.count * 3))
        for (i, point) in points.enumerated() {
            joints.set(index: Int32(i), value: jointIndices[point.joint]!)
            triples.set(index: Int32(i * 3), value: point.position.x)
            triples.set(index: Int32(i * 3 + 1), value: point.position.y)
            triples.set(index: Int32(i * 3 + 2), value: point.confidence)
        }
        return (joints, triples)
    }
    func framingMessage(_ pose: PoseResult, requireBothArms: Bool, side: ArmMeasurement.Side) -> String? {
        let payload = poseTransport(pose)
        do {
            return try framingBridge.message(width: pose.imageSize.width, height: pose.imageSize.height,
                peopleCount: Int32(pose.people.count), joints: payload.joints, triples: payload.triples,
                requireBothArms: requireBothArms, side: side.rawValue)
        } catch { return "Pose unavailable · retry the camera" }
    }
    func setupFramingMessage(_ pose: PoseResult, idle: Bool, barConfirmed: Bool, automatic: Bool,
                             pullUp: Bool, side: ArmMeasurement.Side) -> String? {
        let payload = poseTransport(pose)
        do {
            return try framingBridge.setupMessage(width: pose.imageSize.width, height: pose.imageSize.height,
                peopleCount: Int32(pose.people.count), joints: payload.joints, triples: payload.triples,
                idle: idle, barConfirmed: barConfirmed, automatic: automatic, pullUp: pullUp,
                side: side.rawValue)
        } catch { return "Pose unavailable · retry the camera" }
    }
    func resetFramingFeedback() { framingBridge.resetFeedback(); framingHint = nil }
    func resetAcquisitionFramingFeedback() {
        if framingBridge.invalidateAcquisitionFeedback() { framingHint = nil }
    }
    func observeSetupFraming(_ pose: PoseResult, idle: Bool, barConfirmed: Bool, automatic: Bool,
                            pullUp: Bool, side: ArmMeasurement.Side,
                            acquisitionState: AutomaticPullUpBar.State? = nil,
                            referenceStability: AutomaticReferenceStability.Diagnostic? = nil) -> String? {
        let payload = poseTransport(pose)
        do {
            framingHint = try framingBridge.observeSetupWithAcquisition(seconds: pose.timestamp.seconds,
                width: pose.imageSize.width, height: pose.imageSize.height, peopleCount: Int32(pose.people.count),
                joints: payload.joints, triples: payload.triples, idle: idle, barConfirmed: barConfirmed,
                automatic: automatic, pullUp: pullUp, side: side.rawValue,
                acquisitionState: acquisitionState.map { String(describing: $0) } ?? "",
                stabilityStatus: referenceStability?.status.rawValue ?? "",
                stabilitySeconds: referenceStability?.sourceSeconds ?? .nan,
                stabilityObservations: referenceStability.flatMap { Int32(exactly: $0.observations) } ?? 0,
                stableSeconds: referenceStability?.stableSeconds ?? .nan)
        } catch { framingBridge.resetFeedback(); framingHint = "Pose unavailable · retry the camera" }
        return framingHint
    }
    func consume(_ pose: PoseResult, reference: BarSegment?, counter: ExerciseCounter,
                 event: ExerciseCounter.Event?, automaticFinish: Bool) -> LiveSetSession.EndReason? {
        if pose.gripObservation?.seconds != gripTimestamp {
            do {
                let json = try pose.gripObservation.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }
                try engine.gripObservationJson(input: json)
                gripTimestamp = pose.gripObservation?.seconds
            } catch { try? engine.gripObservationJson(input: nil); gripTimestamp = nil }
        }
        let payload = poseTransport(pose)
        let referenceArray = KotlinDoubleArray(size: reference == nil ? 0 : 4)
        if let reference {
            for (i, value) in [reference.a.x, reference.a.y, reference.b.x, reference.b.y].enumerated() {
                referenceArray.set(index: Int32(i), value: value)
            }
        }
        let end = engine.observe(seconds: pose.timestamp.seconds, width: pose.imageSize.width,
            height: pose.imageSize.height, peopleCount: Int32(pose.people.count), joints: payload.joints,
            triples: payload.triples, reference: referenceArray, phase: counter.phase.rawValue,
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
            var record = try JSONDecoder().decode(WorkoutSetRecord.self,from: Data((overheadPullUpEngine?.reviewDeltaJson() ?? bodyPullUpEngine?.reviewDeltaJson() ?? bodyDipEngine?.reviewDeltaJson() ?? engine.reviewDeltaJson()).utf8))
            record.reps = reps + record.reps.filter { active in !reps.contains { $0.id == active.id } }
            return record
        }
        catch { self.error = "Workout analysis could not be read: \(error.localizedDescription)"; return nil }
    }
    func interrupt(reason: String) {
        clearStartFeedback()
        if let overheadPullUpEngine { overheadPullUpEngine.interrupt(reason: reason) }
        else if let bodyPullUpEngine { bodyPullUpEngine.interrupt(reason: reason) }
        else if let bodyDipEngine { bodyDipEngine.interrupt(reason: reason) } else { engine.interrupt(reason: reason) }
        refreshRecords()
    }
    func finish(reason: String) -> WorkoutSetRecord? {
        clearStartFeedback()
        if let overheadPullUpEngine { overheadPullUpEngine.finish(reason: reason) }
        else if let bodyPullUpEngine { bodyPullUpEngine.finish(reason: reason) }
        else if let bodyDipEngine { bodyDipEngine.finish(reason: reason) } else { engine.finish(reason: reason) }
        refreshRecords()
        do {
            var record = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data((overheadPullUpEngine?.setJson() ?? bodyPullUpEngine?.setJson() ?? bodyDipEngine?.setJson() ?? engine.setJson()).utf8))
            let end = Int64(Date().timeIntervalSince1970 * 1000)
            if let start = record.trackedAtEpochMilliseconds, end >= start { record.trackedEndedAtEpochMilliseconds = end }
            return record
        }
        catch { self.error = "Workout analysis could not be read: \(error.localizedDescription)"; return nil }
    }
    private func refreshRecords() {
        let currentRevision = overheadPullUpEngine?.revision ?? bodyPullUpEngine?.revision ?? bodyDipEngine?.revision ?? engine.revision
        guard revision != currentRevision else { return }
        do {
            let closedCount = Int(overheadPullUpEngine?.closedRecordCount ?? bodyPullUpEngine?.closedRecordCount ?? bodyDipEngine?.closedRecordCount ?? engine.closedRecordCount)
            let deliveredBatch = closedCount > reps.count + 1
            if deliveredBatch, let full = overheadPullUpEngine?.setJson() ?? bodyPullUpEngine?.setJson() {
                let observed = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(full.utf8))
                reps = observed.reps
            } else if closedCount > reps.count,
               let json = overheadPullUpEngine?.lastClosedRecordJson() ?? bodyPullUpEngine?.lastClosedRecordJson() ?? bodyDipEngine?.lastClosedRecordJson() ?? engine.lastClosedRecordJson() {
                let record = try JSONDecoder().decode(WorkoutRepRecord.self, from: Data(json.utf8))
                if reps.last?.id != record.id { reps.append(record) }
            }
            let draft: String?
            if let overheadPullUpEngine { draft = overheadPullUpEngine.draftDeltaJson() }
            else if let bodyPullUpEngine { draft = bodyPullUpEngine.draftDeltaJson() }
            else if let bodyDipEngine { draft = bodyDipEngine.draftDeltaJson() }
            else { draft = engine.draftDeltaJson() }
            if let json = draft {
                pendingDraft = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(json.utf8))
                // A single qualification may deliver several actual returns.
                // Persist all newly delivered records before a crash can lose
                // earlier members of that batch; future deltas remain small.
                if deliveredBatch, var delta = pendingDraft {
                    delta.reps = reps + delta.reps.filter { active in !reps.contains { $0.id == active.id } }
                    pendingDraft = delta
                }
                if let delta = pendingDraft {
                    let record = WorkoutSetRecord(id: delta.id, exercise: delta.exercise, startedAt: delta.startedAt,
                        endedAt: delta.endedAt, endReason: delta.endReason, reps: reps, backend: delta.backend,
                        modelRevision: delta.modelRevision, schemaVersion: delta.schemaVersion, lifecycleVersion: delta.lifecycleVersion,
                        trackingSide: delta.trackingSide, imageSize: delta.imageSize, referenceEdge: delta.referenceEdge,
                        trackedAtEpochMilliseconds: delta.trackedAtEpochMilliseconds, activity: delta.activity, grip: delta.grip,
                        countMethod: delta.countMethod, countPolicyVersion: delta.countPolicyVersion, setGrip: delta.setGrip,
                        boundaryPolicyVersion: delta.boundaryPolicyVersion)
                    currentRecord = record
                }
            }
            revision = currentRevision
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
        if object?["action"] as? String == "deleteSources" { for id in object?["setIds"] as? [String] ?? [] { try markDeletion(id) } }
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
        try decodeAfterSet(state: libraryState(), id: id)
    }
    /// Nil is an authoritative deletion; an unpublished/failed save still throws.
    func libraryAfterSetIfNotDeleted(id: String) throws -> WorkoutAfterSetReport? {
        let state = try libraryState()
        if libraryDeletedIds.contains(id) { return nil }
        return try decodeAfterSet(state: state, id: id)
    }
    private func decodeAfterSet(state: String, id: String) throws -> WorkoutAfterSetReport {
        let json = try WorkoutFeedbackBridge().librarySummaryJson(stateJson: state, setId: id)
        return try JSONDecoder().decode(WorkoutAfterSetReport.self, from: Data(json.utf8))
    }
    func libraryJourneySet(id: String) throws -> String {
        try WorkoutJourneyBridge().reviewedSetJson(stateJson: libraryState(), setId: id)
    }
    func libraryJourneyPage(day: Int64?, cursor: String?, range: WorkoutCalendarDay? = nil) throws -> String {
        if let range {
            return try WorkoutJourneyBridge().pageInRangeJson(stateJson: libraryState(),
                rangeJson: String(decoding: JSONEncoder().encode(range), as: UTF8.self),
                nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000), cursorJson: cursor)
        }
        return try WorkoutJourneyBridge().pageJson(stateJson: libraryState(), nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000),
            utcOffsetMinutes: Int32(TimeZone.current.secondsFromGMT() / 60), day: day.map { KotlinLong(longLong: $0) }, cursorJson: cursor)
    }
    func libraryCalendarDays(_ ranges: [WorkoutCalendarDay]) throws -> [JourneyPracticeDay] {
        let json = try WorkoutJourneyBridge().calendarDaysJson(stateJson: libraryState(),
            rangesJson: String(decoding: JSONEncoder().encode(ranges), as: UTF8.self),
            nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
        return try JSONDecoder().decode([JourneyPracticeDay].self, from: Data(json.utf8))
    }
    /// Also runs on foreground entry, so an in-memory library does not retain expired video.
    @discardableResult
    func maintainLibraryMedia(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> String {
        let prior = try libraryState()
        let state = try WorkoutLibraryBridge().maintainJson(stateJson: prior, nowEpochMilliseconds: now)
        if state != prior { try publishLibrary(state) }
        try pruneLibraryMedia(state)
        return state
    }
    func libraryDashboard(key: String?, cursor: String?, now: Int64, offset: Int32) throws -> String {
        let state = try maintainLibraryMedia(now: now)
        let bridge = WorkoutLibraryBridge()
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
    func coachingGripIdentity(_ record: WorkoutSetRecord) throws -> String? {
        let input = String(decoding: try JSONEncoder().encode(record.progressInput), as: UTF8.self)
        let json = try WorkoutGripReviewBridge().reportJson(recordJson: input,
            ledgerJson: WorkoutGripCorrectionStore.archiveLedger(directory: gripDirectory, id: record.id))
        let report = try JSONDecoder().decode(WorkoutGripVariationReport.self, from: Data(json.utf8))
        let complete = Set(record.completedReps.map(\.id))
        let reps = report.reps.filter { complete.contains($0.repId) }
        let orientations = Set(reps.map(\.orientation).filter { $0 != "unknown" })
        let widths = Set(reps.map(\.width).filter { $0 != "unknown" })
        guard orientations.count == 1, widths.count == 1, let orientation = orientations.first, let width = widths.first,
              ["overhand", "underhand", "neutral"].contains(orientation), ["narrow", "shoulderWidth", "wide"].contains(width) else { return nil }
        return orientation + ":" + width
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
    private var keyframeDirectory: URL { libraryDirectory.appendingPathComponent("Keyframes", isDirectory: true) }
    private func pruneLibraryMedia(_ state: String) throws {
        let object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as? [String: Any]
        let retained = Set((object?["media"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String })
        if FileManager.default.fileExists(atPath: mediaDirectory.path) {
            for file in try FileManager.default.contentsOfDirectory(at: mediaDirectory, includingPropertiesForKeys: nil) where !retained.contains(file.deletingPathExtension().lastPathComponent) {
                try FileManager.default.removeItem(at: file)
            }
        }
        if FileManager.default.fileExists(atPath: keyframeDirectory.path) {
            for file in try FileManager.default.contentsOfDirectory(at: keyframeDirectory, includingPropertiesForKeys: nil) where !retained.contains(file.lastPathComponent) {
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
    /// An explicit automatic-recording preference authorizes local retention.
    /// Publish the immutable set before attaching its clip, even if its save task is still queued.
    func saveRecordedSetVideo(source: URL, record: WorkoutSetRecord) throws -> String? {
        guard !record.completedReps.isEmpty, try !libraryDeleted(record.id) else { return nil }
        try save(record)
        let state = try JSONSerialization.jsonObject(with: Data(libraryState().utf8)) as? [String: Any]
        if var policy = state?["mediaPolicy"] as? [String: Any], policy["enabled"] as? Bool != true {
            policy["enabled"] = true
            _ = try libraryCommand(String(decoding: JSONSerialization.data(withJSONObject: ["action": "mediaPolicy", "mediaPolicy": policy, "atEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000)]), as: UTF8.self))
        }
        let id = try attachLibraryMedia(source: source, setId: record.id)
        if let range = CountedSetVideoRange(record: record) {
            let folder = keyframeDirectory.appendingPathComponent(id, isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try JSONEncoder().encode(WorkoutVideoOrigin(setId: record.id, clipStart: range.start))
                .write(to: folder.appendingPathComponent("origin.json"), options: .atomic)
            Task.detached(priority: .utility) { _ = try? await self.libraryVideoReview(id: id) }
        }
        return id
    }

    func libraryVideoReview(id: String) async throws -> WorkoutVideoReviewData? {
        guard let url = try libraryMediaURL(id: id) else { return nil }
        let state = try JSONSerialization.jsonObject(with: Data(libraryState().utf8)) as? [String: Any]
        let media = (state?["media"] as? [[String: Any]])?.first { $0["id"] as? String == id }
        let record = try (media?["setId"] as? String).flatMap { try libraryRecord(id: $0) }
        let folder = keyframeDirectory.appendingPathComponent(id, isDirectory: true)
        // Manually attached and older clips have no proven camera-clock mapping.
        guard let record, let data = try? Data(contentsOf: folder.appendingPathComponent("origin.json")),
              let origin = try? JSONDecoder().decode(WorkoutVideoOrigin.self, from: data), origin.setId == record.id else {
            return WorkoutVideoReviewData(url: url, record: record, markers: [], thumbnailDirectory: nil, thumbnailError: nil)
        }
        let markers = WorkoutKeyframeMarker.markers(record: record, clipStart: origin.clipStart)
        var message: String?
        do { try await WorkoutKeyframeExtractor.extract(video: url, markers: markers, directory: folder) }
        catch { message = "Some previews couldn't be extracted. You can still play the video and tap rep moments." }
        // A deletion/retention change may happen while AVFoundation is extracting.
        guard try libraryMediaURL(id: id) != nil else {
            try? FileManager.default.removeItem(at: folder)
            return nil
        }
        return WorkoutVideoReviewData(url: url, record: record, markers: markers, thumbnailDirectory: folder, thumbnailError: message)
    }

    func libraryMediaURL(id: String) throws -> URL? {
        guard id.count <= 64, id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else { return nil }
        let state = try maintainLibraryMedia()
        let object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as? [String: Any]
        guard (object?["media"] as? [[String: Any]])?.contains(where: { $0["id"] as? String == id }) == true else { return nil }
        let url = mediaDirectory.appendingPathComponent(id + ".mov")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }
}
