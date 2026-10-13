import Foundation
import WorkoutCore

/// A contract/performance check of the shipping FFI, with synthetic observations.
/// This does not measure Vision/camera latency or qualify a physical device.
@main
struct WorkoutAnalysisBridgeCheck {
    @MainActor static func main() async throws {
        if CommandLine.arguments.count > 1 { try checkSavedObservations(); return }
        var completed: [WorkoutSetRecord] = []
        let reference = BarSegment(a: Point2D(x: 100, y: 500), b: Point2D(x: 900, y: 500))
        func pose(_ time: Double, _ angle: Double, exercise: ExerciseCounter.Exercise) -> PoseResult {
            let half = angle * .pi / 360
            let y = 120 * sin(half) * (exercise == .pullUp ? 1.0 : -1.0)
            var landmarks: [Landmark] = []
            for side in ArmMeasurement.Side.allCases {
                let x = side == .left ? 300.0 : 700.0
                let joints: [PoseJoint] = side == .left ? [.leftShoulder, .leftElbow, .leftWrist] : [.rightShoulder, .rightElbow, .rightWrist]
                let points = [Point2D(x: x, y: 500 + 2 * y), Point2D(x: x + 120 * cos(half), y: 500 + y), Point2D(x: x, y: 500)]
                landmarks += zip(joints, points).map { Landmark(joint: $0, position: $1, confidence: 1) }
            }
            return PoseResult(timestamp: PresentationTime(value: Int64((time * 1000).rounded()), timescale: 1000),
                imageSize: ImageSize(width: 1000, height: 1000), people: [PoseObservation(landmarks: landmarks)], backend: "synthetic bridge check", requestRevision: 0)
        }
        for exercise in ExerciseCounter.Exercise.allCases {
            let analysis = WorkoutAnalysisAdapter(); analysis.start(exercise: exercise)
            var counter = ExerciseCounter(exercise: exercise, side: .left)
            for (time, angle) in [(0.0, 170.0), (0.15, 170.0), (0.30, 80.0), (0.45, 80.0), (0.60, 170.0), (0.75, 170.0)] {
                let p = pose(time, angle, exercise: exercise)
                let count = counter.events.count
                counter.consume(p, referenceEdge: reference)
                _ = analysis.consume(p, reference: reference, counter: counter,
                    event: counter.events.count > count ? counter.events.last : nil, automaticFinish: false)
            }
            guard let set = analysis.finish(reason: "manual"), set.acceptedCount == counter.observedMovements,
                  set.acceptedCount == 1, set.reps.count == 1, set.reps[0].returnedAt == 0.75 else {
                fatalError("Bridge changed \(exercise) count/return contract")
            }
            guard let trackedAt = set.trackedAtEpochMilliseconds, trackedAt > 1_000_000_000_000 else { fatalError("Bridge lost calendar timestamp") }
            completed.append(set)
            let rep = set.reps[0]
            guard rep.left.mean != nil, rep.right.mean != nil,
                  rep.durationCoverage == 1, rep.countPolicyVersion == 8, rep.metricVersion == 1,
                  set.activity?.metricVersion == 2 else { fatalError("Bridge lost metric validity or phase version") }
            guard set.completedCount == 1, set.completedReps == [rep], rep.isCompleteForAnalytics else {
                fatalError("Shipping Apple analytics omitted a completed rep")
            }
            let evidence = try rep.evidenceAssessment(setEndReason: set.endReason)
            guard evidence.status == "completed", evidence.angleComparisonEligible,
                  evidence.leftCoverage == 1, evidence.rightCoverage == 1 else {
                fatalError("Shipping Apple evidence differs from shared completed-cycle assessment")
            }

            // A final attempt is retained as evidence but never appears in
            // timing/angle analytics, including a pull-up accepted before return.
            for ending in ["manual", "disengaged", "trackingLost"] {
                let partialAnalysis = WorkoutAnalysisAdapter(); partialAnalysis.start(exercise: exercise)
                var partialCounter = ExerciseCounter(exercise: exercise, side: .left)
                for (time, angle) in [(0.0, 170.0), (0.15, 170.0), (0.30, 80.0), (0.45, 80.0),
                    (0.60, 170.0), (0.75, 170.0), (0.90, 80.0), (1.05, 80.0)] {
                    let p = pose(time, angle, exercise: exercise)
                    let before = partialCounter.events.count
                    partialCounter.consume(p, referenceEdge: reference)
                    _ = partialAnalysis.consume(p, reference: reference, counter: partialCounter,
                        event: partialCounter.events.count > before ? partialCounter.events.last : nil, automaticFinish: false)
                }
                guard let saved = partialAnalysis.finish(reason: ending), saved.reps.count == 2,
                      saved.acceptedCount == (exercise == .pullUp ? 2 : 1), saved.completedCount == 1,
                      saved.completedReps == [saved.reps[0]], !saved.reps[1].isCompleteForAnalytics,
                      partialAnalysis.reps.filter(\.isCompleteForAnalytics) == saved.completedReps else {
                    fatalError("Shipping Apple analytics included an unfinished final \(exercise)")
                }
                let attempt = saved.reps[1]
                let assessment = try attempt.evidenceAssessment(setEndReason: saved.endReason)
                guard assessment.status == (exercise == .pullUp ? "returnNotRecorded" : ending == "trackingLost" ? "interrupted" : "ended"),
                      ending != "trackingLost" || !assessment.angleComparisonEligible else {
                    fatalError("Shipping Apple evidence mislabeled an unfinished/interrupted attempt")
                }
                let summaryJSON = try WorkoutRecordCodec().summaryJson(input: String(decoding: JSONEncoder().encode(saved), as: UTF8.self))
                let summary = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(summaryJSON.utf8))
                guard try summary.reps[1].evidenceAssessment(setEndReason: summary.endReason) == assessment else {
                    fatalError("Curve retirement changed Apple attempt evidence")
                }
                let recoveredJSON = try WorkoutRecordCodec().recoveryJson(input: String(decoding: JSONEncoder().encode(saved), as: UTF8.self))
                let recovered = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(recoveredJSON.utf8))
                guard recovered.completedCount == 1, recovered.reps.count == 2 else {
                    fatalError("Recovered Apple history included an unfinished rep in analytics")
                }
            }

            // Retained accepted evidence must fit within the set's source bounds
            // even after the real Swift counter rejects an out-of-order frame.
            for inferenceFailure in [false, true] {
                let timelineAnalysis = WorkoutAnalysisAdapter(); timelineAnalysis.start(exercise: exercise)
                var timelineCounter = ExerciseCounter(exercise: exercise, side: .left)
                let timelineFrames = [(10.0, 170.0), (10.15, 170.0), (10.30, 80.0), (10.45, 80.0),
                    (10.60, 170.0), (10.75, 170.0), (10.90, 80.0), (11.05, 80.0)] +
                    (2...42).map { (Double($0) / 10, 80.0) }
                for (index, entry) in timelineFrames.enumerated() {
                    if index == 8, inferenceFailure {
                        timelineCounter.interrupt(reason: "inferenceFailure")
                        timelineAnalysis.interrupt(reason: "inferenceFailure")
                    }
                    let (time, angle) = entry
                    let p = pose(time, angle, exercise: exercise)
                    let before = timelineCounter.events.count
                    timelineCounter.consume(p, referenceEdge: reference)
                    let ended = timelineAnalysis.consume(p, reference: reference, counter: timelineCounter,
                        event: timelineCounter.events.count > before ? timelineCounter.events.last : nil, automaticFinish: true)
                    guard ended == nil else { fatalError("Rejected source frames earned an automatic set boundary") }
                }
                guard let set = timelineAnalysis.finish(reason: "manual"), set.startedAt == 10, set.endedAt == 11.05,
                      set.acceptedCount == timelineCounter.observedMovements,
                      set.reps.allSatisfy({ $0.closedAt <= 11.05 }),
                      set.reps.last?.closeReason == (inferenceFailure ? "inferenceFailure" : "nonIncreasingTimestamp") else {
                    fatalError("Bridge changed rejected-frame source bounds or accepted evidence")
                }
            }
        }
        // A recovered journal may retain inProgress in the raw attempt. Its
        // saved review must never imply the camera is still tracking it.
        let recoveryAnalysis = WorkoutAnalysisAdapter(); recoveryAnalysis.start(exercise: .pullUp)
        var recoveryCounter = ExerciseCounter(exercise: .pullUp, side: .left)
        for (time, angle) in [(0.0,170.0),(0.15,170.0),(0.3,120.0)] {
            let p = pose(time,angle,exercise: .pullUp)
            recoveryCounter.consume(p,referenceEdge: reference)
            _ = recoveryAnalysis.consume(p,reference: reference,counter: recoveryCounter,event: nil,automaticFinish: false)
        }
        let live = recoveryAnalysis.reviewRecord()!
        let recoveredJSON = try WorkoutRecordCodec().recoveryJson(input: String(decoding: JSONEncoder().encode(live),as: UTF8.self))
        let recoveredDraft = try JSONDecoder().decode(WorkoutSetRecord.self,from: Data(recoveredJSON.utf8))
        guard let recoveredAttempt = recoveredDraft.reps.first, recoveredAttempt.closeReason == "inProgress",
              try recoveredAttempt.evidenceAssessment(setEndReason: recoveredDraft.endReason).status == "interrupted" else {
            fatalError("Recovered Apple attempt still claims to be tracked")
        }
        // Shipping native ownership: failed starts cannot show feedback, repeated
        // frames cannot announce again, and ending a set cancels its UI timer.
        let feedback = WorkoutAnalysisAdapter(); feedback.start(exercise: .pullUp)
        guard !feedback.beginStartFeedback(isRunning: false), !feedback.showsSetStarted,
              feedback.beginStartFeedback(isRunning: true), feedback.showsSetStarted,
              !feedback.beginStartFeedback(isRunning: true) else { fatalError("Apple start feedback contract failed") }
        _ = feedback.finish(reason: "disengaged")
        guard !feedback.showsSetStarted else { fatalError("Ended Apple set retained its start cue") }
        feedback.start(exercise: .dip)
        guard feedback.beginStartFeedback(isRunning: true), feedback.showsSetStarted else { fatalError("Next Apple set lost its start cue") }
        try await Task.sleep(for: .seconds(2.1))
        guard !feedback.showsSetStarted else { fatalError("Apple start cue did not expire") }
        // Baseline acquisition and steady poses exercise allocation/FFI hot path.
        let engine = WorkoutAnalysisAdapter(); engine.start(exercise: .pullUp)
        var counter = ExerciseCounter(); var latencies: [Double] = []
        for i in 0..<2000 {
            let angles = [170.0, 170, 170, 80, 80, 80, 170, 170, 170]
            let p = pose(Double(i) * 0.1, angles[i % angles.count], exercise: .pullUp)
            let eventCount = counter.events.count
            counter.consume(p, referenceEdge: reference)
            let start = ContinuousClock.now
            _ = engine.consume(p, reference: reference, counter: counter, event: counter.events.count > eventCount ? counter.events.last : nil, automaticFinish: true)
            let duration = start.duration(to: .now).components
            latencies.append(Double(duration.seconds) * 1000 + Double(duration.attoseconds) / 1e15)
        }
        latencies.sort()
        let p95 = latencies[Int(Double(latencies.count - 1) * 0.95)]
        guard p95 < 5 else { fatalError("Host FFI p95 exceeds 5 ms budget: \(p95)") }
        guard let review = engine.reviewRecord(), review.reps.count == engine.reps.count,
              (review.activity?.phases.count ?? 1000) <= 256 else { fatalError("Bounded review lost cached reps or phase limits") }
        guard let activeSet = engine.finish(reason: "manual"), activeSet.acceptedCount == counter.observedMovements,
              activeSet.acceptedCount > 200 else { fatalError("Active rep bridge lost or duplicated records") }
        // A zero-rep hold has durable source-time evidence on the real FFI.
        for exercise in ExerciseCounter.Exercise.allCases {
            let holdAnalysis = WorkoutAnalysisAdapter(); holdAnalysis.start(exercise: exercise)
            guard holdAnalysis.drainDraft() == nil else { fatalError("Pre-frame journal emitted") }
            var holdCounter = ExerciseCounter(exercise: exercise, side: .left)
            let holdDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: holdDirectory) }
            let holdStore = WorkoutRecordStore(directory: holdDirectory)
            for i in 0...30 {
                let p = pose(Double(i)*0.1,170,exercise: exercise)
                holdCounter.consume(p,referenceEdge: reference)
                _ = holdAnalysis.consume(p,reference: reference,counter: holdCounter,event: nil,automaticFinish: false)
                if let draft = holdAnalysis.drainDraft() { try await holdStore.saveDraft(draft) }
            }
            guard let hold = holdAnalysis.finish(reason: "disengaged"), hold.hasPractice,
                  hold.reps.isEmpty, hold.completedCount == 0,
                  let activity = hold.activity, activity.observedSeconds > 2.8 else {
                fatalError("Zero-rep hold lost practice evidence")
            }
            try await holdStore.save(hold)
            let history = try await holdStore.recent()
            guard history == [hold] else { fatalError("Hang-only saved history lost") }
            let evidence = try await holdStore.progressEvidence()
            guard evidence.count == 1, evidence[0].completedCount == 0, (evidence[0].holdSeconds ?? 0) > 2.8 else {
                fatalError("Compact Apple hold history lost")
            }
            let suite = "WorkoutGoals-" + UUID().uuidString
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let goals = WorkoutGoalStore(defaults: defaults)
            let initial = try goals.report(evidence: evidence,exercise: exercise)
            let repeated = try goals.report(evidence: evidence,exercise: exercise)
            guard initial.goal == repeated.goal, !repeated.goalAchieved else { fatalError("Goal did not stay fixed") }
            try goals.setGoal(exercise: exercise,metric: "holdSeconds",target: 4)
            guard try goals.report(evidence: evidence,exercise: exercise).goal.target == 4 else { fatalError("Goal edit did not persist") }
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("workout-store-check-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = WorkoutRecordStore(directory: directory)
        for _ in 0..<55 {
            let id = UUID().uuidString
            var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(completed[0])) as! [String: Any]
            object["id"] = id
            object["reps"] = (object["reps"] as! [[String: Any]]).map { original in
                var rep = original; rep["setId"] = id; rep["id"] = id + ":rep:1"; return rep
            }
            let record = try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: object))
            try await store.save(record)
            try await store.save(record) // Idempotent, including after motion expiry.
        }
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        guard files.filter({ $0.lastPathComponent.hasSuffix(".motion.json") }).count == 50,
              files.filter({ !$0.lastPathComponent.hasSuffix(".motion.json") }).count == 55 else { fatalError("Motion retention discarded summaries or exceeded bounds") }
        let recent = try await store.recent()
        guard recent.count == 50, recent.allSatisfy({ $0.acceptedCount == 1 }) else { fatalError("Stored records do not recover") }
        let allEvidence = try await store.progressEvidence()
        guard allEvidence.count == 55 else { fatalError("All-time history was limited to detail retention") }
        let pending = completed[1]
        try await store.saveDraft(pending)
        let recovered = try await store.recent()
        guard recovered.contains(where: { $0.id == pending.id && $0.endReason == "recoveredInterruption" && $0.acceptedCount == 1 }) else {
            fatalError("Accepted rep journal was not recovered")
        }
        let recoveredEvidence = try await WorkoutRecordStore(directory: directory).progressEvidence()
        guard recoveredEvidence.contains(where: { $0.id == pending.id && $0.endReason == "recoveredInterruption" }) else {
            fatalError("Recovered journals missing from all-time progress")
        }
        guard !(try await store.progressEvidence()).contains(where: { $0.id == pending.id }) else {
            fatalError("Active journal credited as recovered history")
        }
        try await store.save(pending)
        try await store.saveDraft(pending)
        guard !FileManager.default.fileExists(atPath: directory.appendingPathComponent(pending.id + ".pending.json").path) else {
            fatalError("A stale draft replaced finalized evidence")
        }
        // A real first-write failure leaves immutable evidence in the shared
        // outbox. Repair the directory, retry, and reload through a new store.
        let blocked = directory.appendingPathComponent("blocked")
        try Data([0]).write(to: blocked)
        let retryStore = WorkoutRecordStore(directory: blocked)
        try await retryStore.enqueue(pending, finalized: true)
        do { try await retryStore.flushPending(); fatalError("Expected blocked-directory write failure") }
        catch { /* The failed write remains queued. */ }
        try FileManager.default.removeItem(at: blocked)
        try await retryStore.flushPending()
        let reloaded = try await WorkoutRecordStore(directory: blocked).recent()
        guard reloaded == [pending] else { fatalError("Retry lost finalized evidence") }
        // Simulate termination after summary publication but before journal
        // cleanup/retention. Idempotent retries must finish that work too.
        let orphan = blocked.appendingPathComponent(pending.id + ".pending.json")
        try JSONEncoder().encode(pending).write(to: orphan)
        for i in 0..<55 { try JSONEncoder().encode(pending).write(to: blocked.appendingPathComponent("extra-\(i).motion.json")) }
        try await retryStore.save(pending)
        let cleaned = try FileManager.default.contentsOfDirectory(at: blocked, includingPropertiesForKeys: nil)
        guard !FileManager.default.fileExists(atPath: orphan.path), cleaned.filter({$0.lastPathComponent.hasSuffix(".motion.json")}).count == 50 else {
            fatalError("Idempotent retry skipped post-commit cleanup")
        }
        let correctionDirectory = directory.appendingPathComponent("grip-corrections")
        let correctionStore = WorkoutGripCorrectionStore(directory: correctionDirectory)
        let measuredSet = completed[0]
        let measuredDirectory = directory.appendingPathComponent("grip-measured")
        try await WorkoutRecordStore(directory: measuredDirectory).save(measuredSet)
        let summaryFile = measuredDirectory.appendingPathComponent(measuredSet.id + ".json")
        let motionFile = measuredDirectory.appendingPathComponent(measuredSet.id + ".motion.json")
        let originalSummary = try Data(contentsOf: summaryFile)
        let originalMotion = try Data(contentsOf: motionFile)
        try await correctionStore.correct(measuredSet, repId: nil, orientation: "underhand", width: "wide")
        let corrected = try await WorkoutGripCorrectionStore(directory: correctionDirectory).report(measuredSet)
        guard corrected.reps.first?.orientation == "underhand", corrected.reps.first?.orientationSource == "user",
              corrected.totals.first?.completedReps == measuredSet.completedCount else { fatalError("Apple grip correction storage/FFI lost annotation") }
        try await correctionStore.correct(measuredSet, repId: measuredSet.reps[0].id, orientation: nil, width: nil)
        let reset = try await correctionStore.report(measuredSet)
        guard reset.reps[0].orientation == (measuredSet.reps[0].grip?.orientation ?? "unknown"),
              try Data(contentsOf: summaryFile) == originalSummary,
              try Data(contentsOf: motionFile) == originalMotion else { fatalError("Apple grip reset rewrote measured evidence") }
        // Exercise the real array/JSON FFI with independent source-clock hand
        // observations. These are authored anatomy fixtures, not native accuracy.
        let gripAnalysis = WorkoutAnalysisAdapter(); gripAnalysis.start(exercise: .pullUp)
        var gripCounter = ExerciseCounter(exercise: .pullUp, side: .left)
        var gripLatencies: [Double] = []
        let bodyWorld: [PoseJoint: WorkoutGripVector3] = [
            .leftShoulder: .init(x: 0.2,y: -0.2,z: 0), .rightShoulder: .init(x: -0.2,y: -0.2,z: 0),
            .leftHip: .init(x: 0.2,y: 0.2,z: 0), .rightHip: .init(x: -0.2,y: 0.2,z: 0),
            .leftWrist: .init(x: 0.2,y: -0.4,z: 0), .rightWrist: .init(x: -0.2,y: -0.4,z: 0)]
        for index in 0..<2000 {
            let time = Double(index) * 0.05
            let base = pose(time,170,exercise: .pullUp)
            var points = base.people[0].landmarks.map { Landmark(joint: $0.joint,position: Point2D(x: 1000 - $0.position.x,y: $0.position.y),confidence: $0.confidence) }
            points += [Landmark(joint: .leftHip,position: Point2D(x: 700,y: 900),confidence: 1),
                Landmark(joint: .rightHip,position: Point2D(x: 300,y: 900),confidence: 1)]
            var frame = PoseResult(timestamp: base.timestamp,imageSize: base.imageSize,people: [PoseObservation(landmarks: points)],backend: base.backend,requestRevision: 0)
            if index % 5 == 0 {
                let body = points.compactMap { p -> WorkoutGripBodyPoint? in
                    bodyWorld[p.joint].map { WorkoutGripBodyPoint(joint: p.joint.rawValue,position: p.position,world: $0,score: 1) }
                }
                let hands = [PoseJoint.leftWrist, .rightWrist].map { joint in
                    let position = points.first { $0.joint == joint }!.position
                    let isLeft = joint == .leftWrist
                    let indexPoint = WorkoutGripVector3(x: isLeft ? 0.04 : -0.04,y: -0.06,z: 0)
                    let pinky = WorkoutGripVector3(x: isLeft ? -0.04 : 0.04,y: -0.06,z: 0)
                    return WorkoutGripHandObservation(points: [
                        WorkoutGripHandPoint(joint: "wrist",position: position,world: .init(x: 0,y: 0,z: 0),score: nil),
                        WorkoutGripHandPoint(joint: "indexMCP",position: position,world: indexPoint,score: nil),
                        WorkoutGripHandPoint(joint: "middleMCP",position: position,world: .init(x: 0,y: -0.07,z: 0),score: nil),
                        WorkoutGripHandPoint(joint: "pinkyMCP",position: position,world: pinky,score: nil)],sdkHandedness: isLeft ? "Left" : "Right",sdkHandednessScore: 0.99)
                }
                frame.gripObservation = WorkoutGripObservation(seconds: time,imageSize: base.imageSize,
                    trackedBody: body.map { .init(joint: $0.joint,position: $0.position,score: $0.score) },body: body,hands: hands,
                    modelId: "authored-anatomy",backend: "authored transport")
                if index == 0 {
                    let inference = WorkoutGripInferenceBridge()
                    let requestsJSON = try inference.handRequestsJson(poseJson: String(decoding: JSONEncoder().encode(frame), as: UTF8.self))
                    let requests = try JSONDecoder().decode([WorkoutGripCropRequest].self, from: Data(requestsJSON.utf8))
                    let stageJSON = try inference.handStageRequestsJson(poseJson: String(decoding: JSONEncoder().encode(frame),as: UTF8.self),observationJson: String(decoding: JSONEncoder().encode(frame.gripObservation!),as: UTF8.self))
                    let stage = try JSONDecoder().decode([WorkoutGripCropRequest].self,from: Data(stageJSON.utf8))
                    let empty = WorkoutGripObservation(seconds: time,imageSize: frame.imageSize,trackedBody: [],body: [],hands: [],modelId: "authored-anatomy",backend: "authored transport")
                    let rejectedJSON = try inference.handStageRequestsJson(poseJson: String(decoding: JSONEncoder().encode(frame),as: UTF8.self),observationJson: String(decoding: JSONEncoder().encode(empty),as: UTF8.self))
                    let estimateJSON = try inference.orientationJson(observationJson: String(decoding: JSONEncoder().encode(frame.gripObservation!), as: UTF8.self))
                    let estimate = try JSONDecoder().decode(WorkoutGripEstimate.self, from: Data(estimateJSON.utf8))
                    let identity = try inference.hasHandIdentityJson(observationJson: String(decoding: JSONEncoder().encode(frame.gripObservation!),as: UTF8.self),handJson: String(decoding: JSONEncoder().encode(hands[0]),as: UTF8.self),expectedSide: "left")
                    guard inference.cropPolicyVersion == 3, inference.bodyPreflightVersion == 1,
                          stage.map(\.side) == requests.map(\.side), stage.map(\.regions) == requests.map(\.regions), rejectedJSON == "[]",
                          requests.map(\.side) == ["left","right"], identity,
                          requests.allSatisfy({ !$0.regions.isEmpty && $0.regions.count <= 2 }), estimate.policyVersion == 3,
                          estimate.orientation == "underhand" else {
                        fatalError("Native crop planning or offline geometric-estimate FFI changed")
                    }
                }
            }
            gripCounter.consume(frame,referenceEdge: reference)
            let started = ContinuousClock.now
            _ = gripAnalysis.consume(frame,reference: reference,counter: gripCounter,event: nil,automaticFinish: false)
            let elapsed = started.duration(to: .now).components
            gripLatencies.append(Double(elapsed.seconds) * 1000 + Double(elapsed.attoseconds) / 1e15)
        }
        gripLatencies.sort()
        let gripP95 = gripLatencies[Int(Double(gripLatencies.count - 1) * 0.95)]
        guard gripP95 < 5 else { fatalError("Grip-enabled host FFI p95 exceeds 5 ms budget: \(gripP95)") }
        guard let gripSet = gripAnalysis.finish(reason: "manual"), let grip = gripSet.grip,
              grip.latest.orientation == "underhand", grip.latest.width == "shoulderWidth",
              grip.latest.inferenceBackend == "authored transport", grip.orientationSpans.count <= 128,
              gripAnalysis.gripLabel == "Underhand · Shoulder-width", gripSet.completedCount == 0 else {
            fatalError("Native Swift/shared grip transport or confirmation changed")
        }
        let result: [String: Any] = ["gripInferenceCases": 2, "gripCorrectionCases": 2, "gripTransportCases": 1, "gripFrames": 2000, "gripHostBridgeP95Milliseconds": gripP95, "practiceCases": 2, "passed": true, "cases": 2, "timestampRegressionCases": 4, "frames": 2000, "activeReps": activeSet.acceptedCount, "persistenceSets": 55, "hostBridgeP95Milliseconds": p95,
            "qualification": "synthetic Apple host FFI; camera/model/device performance not qualified"]
        let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: URL(fileURLWithPath: "build/workout-analysis/apple-bridge-check.json"), options: .atomic)
        print(String(decoding: data, as: UTF8.self))
    }
    @MainActor private static func checkSavedObservations() throws {
        let args = CommandLine.arguments
        guard args.count == 9, let exercise = ExerciseCounter.Exercise(rawValue: args[1]),
              let side = ArmMeasurement.Side(rawValue: args[2]), let expected = Int(args[7]) else {
            fatalError("Usage: apple-bridge-check exercise side ax ay bx by expected observations.jsonl")
        }
        let coords = args[3...6].map { Double($0)! }
        let reference = BarSegment(a: Point2D(x: coords[0], y: coords[1]), b: Point2D(x: coords[2], y: coords[3]))
        precondition(reference.isValid)
        let analysis = WorkoutAnalysisAdapter(); analysis.start(exercise: exercise)
        var counter = ExerciseCounter(exercise: exercise, side: side)
        var frames = 0
        for line in try String(contentsOfFile: args[8], encoding: .utf8).split(separator: "\n") {
            let data = Data(line.utf8)
            let metadata = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            precondition(metadata["frameIndex"] as? Int == frames && metadata["timebase"] as? String == "source_pts")
            let pose = try JSONDecoder().decode(PoseResult.self, from: data)
            let count = counter.events.count
            counter.consume(pose, referenceEdge: reference)
            _ = analysis.consume(pose, reference: reference, counter: counter,
                event: counter.events.count > count ? counter.events.last : nil, automaticFinish: false)
            frames += 1
        }
        _ = counter.finish()
        guard let set = analysis.finish(reason: "endOfInput"), frames > 0,
              counter.observedMovements == expected, set.acceptedCount == expected else {
            fatalError("Saved-observation count changed or accepted records were lost")
        }
        print("Saved-observation regression: \(exercise), \(frames) frames, \(set.acceptedCount)/\(expected) accepted records. No new inference or form qualification.")
    }

}
