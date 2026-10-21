import CoreGraphics
import Foundation
import WorkoutCore

protocol DipFrameObserving: Sendable {
    var generation: Int64 { get async }
    func observe(image: CGImage, pose: PoseResult, generation: Int64) async throws -> DipFrameObservation
    func invalidateEvidence() async
}
extension VisionDipObservationWorker: DipFrameObserving {}

// Opt-in local qualification only. Contains source observations; owners must
// handle it with the same privacy policy as pose data, outside normal app use.
struct DipWorkoutTrace: Codable, Sendable {
    typealias Handler = @Sendable (DipWorkoutTrace) -> Void
    let pose: PoseResult
    let inputSupport: DipRailReference?
    let camera: String
    let acquisition: DipRailAcquisitionResult.Diagnostic
    let candidates: [[DipRailCandidate]]
    let extractionFailures: [String]
    let snapshot: DipWorkoutSnapshot
    let translations: [[Double]]
    let scaleFraction: Double?
}

struct DipWorkoutFrameResult: Sendable {
    let frame: ProcessedFrame
    let snapshot: DipWorkoutSnapshot
    let support: DipRailReference?
    let camera: String
    // At most one newly closed attempt and a bounded current draft cross FFI.
    let closedRep: WorkoutRepRecord?
    let draft: WorkoutSetRecord?
}

protocol DipWorkoutProcessing: Sendable {
    func process(_ frame: ProcessedFrame) async throws -> DipWorkoutFrameResult
    func finish(reason: String) async throws -> WorkoutSetRecord
    func review() async throws -> WorkoutSetRecord
}

/// One instance belongs to exactly one source epoch. A complete native frame
/// transaction commits here even if its presentation task is paused. Source
/// replacement creates another instance; it never resets this actor in flight.
actor VisionDipWorkout: DipWorkoutProcessing {
    enum ProcessingError: Error { case overlappingFrame, finishedSource }
    private let observer: any DipFrameObserving
    private let cameraWorker: any StaticSceneRegistering
    private let scene = WorkoutSceneBridge()
    private let engine: BilateralDipWorkoutBridge
    private let automaticFinish: Bool
    private let trace: DipWorkoutTrace.Handler?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var support: DipRailReference?
    private var sceneReference: StaticSceneRegistrationReference?
    private var closedReps: [WorkoutRepRecord] = []
    private var revision: Int = -1
    private var finished: WorkoutSetRecord?
    private var failure: (any Error)?
    private var closing = false
    private var cached: (input: ProcessedFrame, result: DipWorkoutFrameResult)?
    private var inFlight: (input: ProcessedFrame, task: Task<DipWorkoutFrameResult, Error>)?

    init(setId: String = UUID().uuidString, trackedAtEpochMilliseconds: Int64 = 0,
         automaticFinish: Bool = false,
         observer: any DipFrameObserving = VisionDipObservationWorker(),
         cameraWorker: any StaticSceneRegistering = VisionStaticSceneRegistrationWorker(),
         trace: DipWorkoutTrace.Handler? = nil) {
        self.observer = observer
        self.cameraWorker = cameraWorker
        self.automaticFinish = automaticFinish
        self.trace = trace
        engine = BilateralDipWorkoutBridge(setId: setId, trackedAtEpochMilliseconds: trackedAtEpochMilliseconds)
    }

    func process(_ frame: ProcessedFrame) async throws -> DipWorkoutFrameResult {
        if let cached, sameFrame(cached.input, frame) { return cached.result }
        if let inFlight {
            guard sameFrame(inFlight.input, frame) else { throw ProcessingError.overlappingFrame }
            return try await inFlight.task.value
        }
        if let failure { throw failure }
        guard finished == nil, !closing else { throw ProcessingError.finishedSource }
        // This unstructured task belongs to the source owner. Cancelling an
        // awaiting playback task does not cancel half a native transaction.
        let task = Task {
            do { return try await self.perform(frame) }
            catch { self.failure = error; throw error }
        }
        inFlight = (frame, task)
        return try await task.value
    }

    private func sameFrame(_ a: ProcessedFrame, _ b: ProcessedFrame) -> Bool {
        // Exact rational PTS, original primary pose and originating decoded
        // image are all required; pointer identity or Double time alone is not.
        a.image === b.image && a.pose == b.pose
    }

    private struct Input: Encodable {
        let pose: PoseResult
        let support: DipRailReference?
        let camera: String
        let automaticFinish: Bool
    }

    private func perform(_ frame: ProcessedFrame) async throws -> DipWorkoutFrameResult {
        defer { inFlight = nil }
        let generation = await observer.generation
        let observation = try await observer.observe(image: frame.image, pose: frame.pose, generation: generation)
        if support == nil, let acquired = observation.acquisition.reference {
            support = acquired
            sceneReference = VisionStaticSceneRegistrationWorker.makeReference(image: frame.image)
            _ = scene.calibrate(width: frame.pose.imageSize.width, height: frame.pose.imageSize.height)
        }
        var measurement: StaticSceneRegistrationMeasurement?
        if let reference = sceneReference {
            measurement = try? await cameraWorker.measure(reference: reference, image: frame.image)
            let patches = measurement?.translations ?? []
            let values = KotlinDoubleArray(size: Int32(patches.count * 2))
            for (i, patch) in patches.enumerated() {
                values.set(index: Int32(i * 2), value: patch.dxPixels)
                values.set(index: Int32(i * 2 + 1), value: patch.dyPixels)
            }
            _ = scene.observe(seconds: frame.pose.timestamp.seconds, translations: values,
                scaleFraction: measurement?.globalScaleFraction ?? .nan)
        }
        let camera = scene.state == "moved" ? "moved" :
            (scene.isReady(seconds: frame.pose.timestamp.seconds) ? "ready" : "unavailable")
        let request = Input(pose: observation.pose, support: support, camera: camera, automaticFinish: automaticFinish)
        let json = try engine.observeJson(input: String(decoding: encoder.encode(request), as: UTF8.self))
        let snapshot = try decoder.decode(DipWorkoutSnapshot.self, from: Data(json.utf8))
        var closedRep: WorkoutRepRecord?
        var draft: WorkoutSetRecord?
        if snapshot.analysisRevision != revision {
            if Int(engine.closedRecordCount) > closedReps.count, let json = engine.lastClosedRecordJson() {
                let rep = try decoder.decode(WorkoutRepRecord.self, from: Data(json.utf8))
                closedReps.append(rep); closedRep = rep
            }
            if let json = engine.draftDeltaJson() {
                draft = try decoder.decode(WorkoutSetRecord.self, from: Data(json.utf8))
            }
            revision = snapshot.analysisRevision
        }
        if camera == "moved" {
            await observer.invalidateEvidence()
            support = nil; sceneReference = nil; scene.reset()
        }
        let result = DipWorkoutFrameResult(frame: ProcessedFrame(image: frame.image, pose: observation.pose,
            processingMilliseconds: frame.processingMilliseconds), snapshot: snapshot,
            support: support, camera: camera, closedRep: closedRep, draft: draft)
        // Commit before any result reaches a cancelled presentation task.
        cached = (frame, result)
        if snapshot.endReason != nil { finished = try decodeRecord(engine.setJson(includeActive: false)) }
        trace?(DipWorkoutTrace(pose: observation.pose, inputSupport: request.support, camera: camera,
            acquisition: observation.acquisition.diagnostic, candidates: observation.candidates, extractionFailures: observation.extractionFailures,
            snapshot: snapshot, translations: measurement?.translations.map { [$0.dxPixels, $0.dyPixels] } ?? [],
            scaleFraction: measurement?.globalScaleFraction))
        return result
    }

    func finish(reason: String) async throws -> WorkoutSetRecord {
        closing = true
        if let inFlight { _ = try? await inFlight.task.value }
        if let finished { return finished }
        engine.finish(reason: reason)
        let record = try decodeRecord(engine.setJson(includeActive: false))
        finished = record
        return record
    }

    func review() async throws -> WorkoutSetRecord {
        if let inFlight { _ = try await inFlight.task.value }
        if let finished { return finished }
        var record = try decodeRecord(engine.reviewDeltaJson())
        record.reps = closedReps + record.reps
        return record
    }

    private func decodeRecord(_ json: String) throws -> WorkoutSetRecord {
        try decoder.decode(WorkoutSetRecord.self, from: Data(json.utf8))
    }
}
