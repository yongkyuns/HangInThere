import XCTest
import class WorkoutCore.BodyDipWorkoutBridge
import class WorkoutCore.WorkoutRecordCodec
import class WorkoutCore.OverheadPullUpWorkoutBridge
import class WorkoutCore.BodyAthleteSelection
import class WorkoutCore.WorkoutLibraryBridge
@testable import HangInThere

/// Source owner / record transport only. This deliberately does not substitute
/// authored camera readiness for a test of the actual native live controller.
final class OverheadPullUpWorkoutNativeTests: XCTestCase {
    private func pose(_ ms: Int64, rise: Double = 0, degrees: Double = 170, wristDrop: Double = 0) -> PoseResult {
        let height = 350-rise
        let offset = height/(2*tan(degrees * .pi/360))
        let points = [ArmMeasurement.Side.left, .right].enumerated().flatMap { i,side -> [Landmark] in
            let x = i == 0 ? 250.0 : 650.0
            let names: [PoseJoint] = side == .left ? [.leftShoulder,.leftElbow,.leftWrist] : [.rightShoulder,.rightElbow,.rightWrist]
            let positions = [Point2D(x:x,y:600-rise),Point2D(x:x+(i == 0 ? -offset : offset),y:(850-rise+wristDrop)/2),Point2D(x:x,y:250+wristDrop)]
            return zip(names,positions).map { Landmark(joint:$0,position:$1,confidence:0.9) }
        } + [Landmark(joint:.leftHip,position:.init(x:250,y:1000-rise),confidence:0.9),
             Landmark(joint:.rightHip,position:.init(x:650,y:1000-rise),confidence:0.9)]
        return PoseResult(timestamp:.init(value:ms,timescale:1000),imageSize:.init(width:900,height:1400),
            people:[.init(landmarks:points)],backend:"authored native overhead",requestRevision:1)
    }
    private func observe(_ bridge: OverheadPullUpWorkoutBridge, selection: BodyAthleteSelection,
                         pose: PoseResult, camera: String = "ready") throws -> BodyPullUpWorkoutSnapshot {
        _ = try selection.observeJson(poseJson:String(decoding:JSONEncoder().encode(pose),as:UTF8.self))
        let raw = try bridge.observeSelectedJson(selection:selection,sourceValue:pose.timestamp.value,
            sourceTimescale:Int32(pose.timestamp.timescale),camera:camera,automaticFinish:false,automaticStart:true)
        return try JSONDecoder().decode(BodyPullUpWorkoutSnapshot.self,from:Data(raw.utf8))
    }
    func testBarFreeNativeBridgeAndRecordStorePreserveSourceConfirmation() throws {
        let bridge = OverheadPullUpWorkoutBridge(setId:"native-overhead",trackedAtEpochMilliseconds:0)
        let selection = BodyAthleteSelection()
        var last: BodyPullUpWorkoutSnapshot?
        for ms in stride(from:Int64(0),through:1800,by:40) {
            last = try observe(bridge,selection:selection,pose:pose(ms,rise:(1040...1360).contains(ms) ? 140 : 0,
                degrees:(1040...1360).contains(ms) ? 100 : 170))
        }
        XCTAssertTrue(try XCTUnwrap(last).started)
        bridge.finish(reason:"manual")
        let record = try JSONDecoder().decode(WorkoutSetRecord.self,from:Data(bridge.setJson().utf8))
        XCTAssertEqual(record.completedCount,1)
        XCTAssertEqual(record.countMethod,"overheadPullUpRelativeMotion")
        XCTAssertEqual(record.countPolicyVersion,4); XCTAssertEqual(record.boundaryPolicyVersion,8)
        XCTAssertNil(record.referenceEdge)
        let rep = try XCTUnwrap(record.completedReps.first)
        XCTAssertEqual(rep.countConfirmedAt,rep.returnedAt)
        let encoded = try JSONEncoder().encode(record)
        let roundTrip = try JSONDecoder().decode(WorkoutSetRecord.self,from:encoded)
        XCTAssertEqual(roundTrip,record)
        let library = WorkoutLibraryBridge()
        let records = "["+String(decoding:encoded,as:UTF8.self)+"]"
        let saved = try library.registerJson(stateJson:library.emptyJson(),recordsJson:records)
        let restored = try library.restoreJson(stateJson:library.emptyJson(),
            backupJson:library.exportJson(stateJson:saved,summariesJson:nil,gripCorrectionsJson:nil))
        XCTAssertTrue(restored.contains("countConfirmedAt"))
        XCTAssertTrue(restored.contains("overheadPullUpRelativeMotion"))
    }
    func testNativeConfirmationRetainsEarlierObservedReturnThroughPrivateSupportReset() throws {
        let bridge = OverheadPullUpWorkoutBridge(setId:"native-private",trackedAtEpochMilliseconds:0)
        let selection = BodyAthleteSelection()
        var frames = stride(from:Int64(0),through:600,by:40).map { pose($0) }
        frames.append(pose(601,wristDrop:-35))
        frames += (602...620).map { pose(Int64($0),rise:140,degrees:100) }
        frames += (621...745).map { pose(Int64($0),wristDrop:-35) }
        frames += (746...1000).map { pose(Int64($0),wristDrop:35) }
        for p in frames {
            let snapshot = try observe(bridge,selection:selection,pose:p)
            if p.timestamp.value < 850 { XCTAssertFalse(snapshot.started) }
        }
        bridge.finish(reason:"manual")
        let record = try JSONDecoder().decode(WorkoutSetRecord.self,from:Data(bridge.setJson().utf8))
        let rep = try XCTUnwrap(record.completedReps.first)
        XCTAssertEqual(record.completedCount,1); XCTAssertEqual(rep.countConfirmedAt,0.85)
        XCTAssertLessThan(try XCTUnwrap(rep.returnedAt),try XCTUnwrap(rep.countConfirmedAt))
        let times = Set(frames.map { $0.timestamp.seconds })
        XCTAssertTrue([rep.startedAt,rep.extremeAt,rep.returnedAt,rep.countConfirmedAt].compactMap { $0 }.allSatisfy { times.contains($0) })
    }
    @MainActor func testAdapterAndLiveSessionPublishDelayedMidSetCountOnlyOnItsConfirmationFrame() throws {
        let adapter=WorkoutAnalysisAdapter()
        var session=LiveSetSession()
        var prefixes:[(Double,Int)]=[]
        func feed(_ p:PoseResult,camera:String="ready") throws {
            let state=try XCTUnwrap(adapter.observeOverheadPullUp(p,camera:camera,automaticStart:true,recordCalendarTime:false))
            if state.started && session.phase == .idle {
                session.beginBodyPullUp(state,side:.left,startedAt:adapter.currentRecord?.startedAt,
                    completedTimes:adapter.reps.filter(\.isCompleteForAnalytics).compactMap(\.returnedAt))
            } else if session.phase == .running {session.consumeBodyPullUp(state)}
            if state.started {XCTAssertEqual(session.observedMovements,adapter.currentRecord?.completedCount)}
            prefixes.append((p.timestamp.seconds,adapter.currentRecord?.completedCount ?? 0))
        }
        for ms in stride(from:Int64(0),through:1800,by:40) {
            try feed(pose(ms,rise:(1040...1360).contains(ms) ? 140 : 0,degrees:(1040...1360).contains(ms) ? 100 : 170))
        }
        try feed(pose(1840),camera:"unavailable")
        adapter.interrupt(reason:"explicitSupportInvalidation")
        try feed(pose(1880));try feed(pose(1881,wristDrop:-35))
        for ms:Int64 in 1882...1900 {try feed(pose(ms,rise:140,degrees:100))}
        for ms:Int64 in 1901...2025 {try feed(pose(ms,wristDrop:-35))}
        for ms:Int64 in 2026...2280 {try feed(pose(ms,wristDrop:35))}
        let record=try XCTUnwrap(adapter.finish(reason:"manual"))
        XCTAssertEqual(record.completedCount,2);XCTAssertEqual(session.movementTimes.count,2)
        XCTAssertEqual(session.counter.observedMovements,0)
        let rep=try XCTUnwrap(record.completedReps.last)
        XCTAssertEqual(rep.countConfirmedAt,2.13)
        XCTAssertLessThan(try XCTUnwrap(rep.returnedAt),try XCTUnwrap(rep.countConfirmedAt))
        for (at,count) in prefixes {XCTAssertEqual(count,record.completedReps.filter {($0.countConfirmedAt ?? .infinity) <= at}.count,"at source \(at)")}
    }
    @MainActor func testAdapterAndLiveSessionRetainedLowerCountPublishesOnFreshCompletionFrame() throws {
        let adapter=WorkoutAnalysisAdapter()
        var session=LiveSetSession()
        var prefixes:[(Double,Int)]=[]
        func feed(_ p:PoseResult,camera:String="ready") throws {
            let state=try XCTUnwrap(adapter.observeOverheadPullUp(p,camera:camera,automaticStart:true,recordCalendarTime:false))
            if state.started && session.phase == .idle {
                session.beginBodyPullUp(state,side:.left,startedAt:adapter.currentRecord?.startedAt,
                    completedTimes:adapter.reps.filter(\.isCompleteForAnalytics).compactMap(\.returnedAt))
            } else if session.phase == .running {session.consumeBodyPullUp(state)}
            if state.started {XCTAssertEqual(session.observedMovements,adapter.currentRecord?.completedCount)}
            prefixes.append((p.timestamp.seconds,adapter.currentRecord?.completedCount ?? 0))
        }
        for ms in stride(from:Int64(0),through:1800,by:40) {
            try feed(pose(ms,rise:(1040...1360).contains(ms) ? 140 : 0,degrees:(1040...1360).contains(ms) ? 100 : 170))
        }
        try feed(pose(1840),camera:"unavailable")
        try feed(pose(1880));try feed(pose(1881,wristDrop:-35))
        for ms:Int64 in 1882...1900 {try feed(pose(ms,rise:140,degrees:100))}
        for ms:Int64 in 1901...2025 {try feed(pose(ms,wristDrop:-35))}
        for ms:Int64 in 2026...2280 {try feed(pose(ms,wristDrop:35))}
        let record=try XCTUnwrap(adapter.finish(reason:"manual"))
        XCTAssertEqual(record.completedCount,2);XCTAssertEqual(session.movementTimes.count,2)
        XCTAssertEqual(session.counter.observedMovements,0)
        let rep=try XCTUnwrap(record.completedReps.last)
        XCTAssertEqual(rep.countConfirmedAt,2.021)
        XCTAssertEqual(rep.returnedAt,rep.countConfirmedAt)
        for (at,count) in prefixes {XCTAssertEqual(count,record.completedReps.filter {($0.countConfirmedAt ?? .infinity) <= at}.count,"at source \(at)")}
    }
    func testKnownCameraMovementDropsPrivatePreparationLowerConstraint() throws {
        let bridge=OverheadPullUpWorkoutBridge(setId:"native-private-moved",trackedAtEpochMilliseconds:0)
        let selection=BodyAthleteSelection()
        func feed(_ original:PoseResult,camera:String="ready",dx:Double=0) throws -> BodyPullUpWorkoutSnapshot {
            let p=PoseResult(timestamp:original.timestamp,imageSize:original.imageSize,
                people:original.people.map { .init(landmarks:$0.landmarks.map { .init(joint:$0.joint,position:.init(x:$0.position.x+dx,y:$0.position.y),confidence:$0.confidence) }) },
                backend:original.backend,requestRevision:original.requestRevision)
            _ = try selection.observeJson(poseJson:String(decoding:JSONEncoder().encode(p),as:UTF8.self))
            let raw=try bridge.observeSelectedJson(selection:selection,sourceValue:p.timestamp.value,sourceTimescale:Int32(p.timestamp.timescale),
                camera:camera,automaticFinish:false,automaticStart:false)
            return try JSONDecoder().decode(BodyPullUpWorkoutSnapshot.self,from:Data(raw.utf8))
        }
        for ms in stride(from:Int64(0),through:1000,by:40){XCTAssertFalse(try feed(pose(ms)).started)}
        XCTAssertEqual(try feed(pose(1040),camera:"moved").motion.phase,"idle")
        var last:BodyPullUpWorkoutSnapshot?
        for ms in stride(from:Int64(1080),through:1880,by:40){last=try feed(pose(ms),dx:80)}
        XCTAssertEqual(last?.motion.phase,"ready");XCTAssertNotEqual(last?.motion.reason,"waitingForFreshLower")
        XCTAssertFalse(try XCTUnwrap(last).started);XCTAssertEqual(bridge.closedRecordCount,0)
    }
    // Authored kinematics isolate a deadline crossed on the first valid frame.
    // These fixtures verify native transport and publication, not video accuracy.
    private func deadlinePose(_ ms: Int64, missingElbows: Bool = false) -> PoseResult {
        let rise: Double
        if ms <= 1000 { rise = 0 }
        else if ms <= 1440 { rise = 140 }
        else if ms <= 1760 { rise = 70 * (1 + cos(.pi * Double(ms - 1440) / 360)) }
        else if ms <= 2600 { rise = 150 * (1 - cos(2 * .pi * Double(ms - 1800) / 800)) }
        else { rise = 0 }
        let segmentLength = 350 / (2 * sin(85 * Double.pi / 180))
        let height = 350 - rise
        let offset = sqrt(segmentLength * segmentLength - height * height / 4)
        let points = [250.0, 650.0].enumerated().flatMap { i, x -> [Landmark] in
            let names: [PoseJoint] = i == 0 ? [.leftShoulder, .leftElbow, .leftWrist] : [.rightShoulder, .rightElbow, .rightWrist]
            return [
                Landmark(joint: names[0], position: .init(x: x, y: 600 - rise), confidence: 0.9),
                Landmark(joint: names[1], position: .init(x: x + (i == 0 ? -offset : offset), y: (850 - rise) / 2), confidence: missingElbows ? 0.2 : 0.9),
                Landmark(joint: names[2], position: .init(x: x, y: 250), confidence: 0.9)
            ]
        } + [Landmark(joint: .leftHip, position: .init(x: 250, y: 1000 - rise), confidence: 0.9),
             Landmark(joint: .rightHip, position: .init(x: 650, y: 1000 - rise), confidence: 0.9)]
        return PoseResult(timestamp: .init(value: ms, timescale: 1000), imageSize: .init(width: 900, height: 1400),
            people: [.init(landmarks: points)], backend: "authored deadline control", requestRevision: 1)
    }

    @MainActor func testDeadlineRecoveryPublishesOnlyTheNextFullyObservedCycle() throws {
        let adapter = WorkoutAnalysisAdapter()
        var session = LiveSetSession()
        var prefixes: [(Double, Int)] = []
        var fullTimes = Set<Double>()
        for ms in stride(from: Int64(0), through: 2800, by: 40) {
            let missing = (1480...1760).contains(ms)
            let p = deadlinePose(ms, missingElbows: missing)
            let state = try XCTUnwrap(adapter.observeOverheadPullUp(p, camera: "ready", automaticStart: true, recordCalendarTime: false))
            if state.started && session.phase == .idle {
                session.beginBodyPullUp(state, side: .left, startedAt: adapter.currentRecord?.startedAt,
                    completedTimes: adapter.reps.filter(\.isCompleteForAnalytics).compactMap(\.returnedAt))
            } else if session.phase == .running { session.consumeBodyPullUp(state) }
            let count = adapter.currentRecord?.completedCount ?? 0
            if state.started { XCTAssertEqual(session.observedMovements, count) }
            prefixes.append((p.timestamp.seconds, count))
            if !missing { fullTimes.insert(p.timestamp.seconds) }
            if ms == 1800 { XCTAssertEqual(state.motion.reason, "waitingForFreshLower") }
            if ms == 1840 { XCTAssertEqual(state.motion.reason, "freshLowerRecovered") }
            if ms <= 1840 { XCTAssertEqual(count, 0) }
        }
        let record = try XCTUnwrap(adapter.finish(reason: "manual"))
        XCTAssertEqual(record.completedCount, 1)
        XCTAssertEqual(session.movementTimes.count, 1)
        XCTAssertEqual(record.countPolicyVersion, 4)
        XCTAssertEqual(record.boundaryPolicyVersion, 8)
        let rep = try XCTUnwrap(record.completedReps.first)
        XCTAssertGreaterThanOrEqual(rep.startedAt, 1.84)
        XCTAssertTrue([rep.startedAt, rep.extremeAt, rep.acceptedAt, rep.returnedAt, rep.countConfirmedAt].compactMap { $0 }.allSatisfy { fullTimes.contains($0) })
        for (at, count) in prefixes {
            XCTAssertEqual(count, record.completedReps.filter { ($0.countConfirmedAt ?? .infinity) <= at }.count, "at source \(at)")
        }
        let encoded = try JSONEncoder().encode(record)
        XCTAssertEqual(try JSONDecoder().decode(WorkoutSetRecord.self, from: encoded), record)
        let library = WorkoutLibraryBridge()
        let saved = try library.registerJson(stateJson: library.emptyJson(), recordsJson: "[" + String(decoding: encoded, as: UTF8.self) + "]")
        let restored = try library.restoreJson(stateJson: library.emptyJson(), backupJson: library.exportJson(stateJson: saved, summariesJson: nil, gripCorrectionsJson: nil))
        XCTAssertTrue(restored.contains("overheadPullUpRelativeMotion"))
        XCTAssertTrue(restored.contains("countConfirmedAt"))
    }

    func testDeadlineRecoveryCannotBorrowAnUnobservedSecondLowerFrame() throws {
        let bridge = OverheadPullUpWorkoutBridge(setId: "deadline-missing", trackedAtEpochMilliseconds: 0)
        let selection = BodyAthleteSelection()
        for ms in stride(from: Int64(0), through: 2800, by: 40) {
            _ = try observe(bridge, selection: selection, pose: deadlinePose(ms, missingElbows: (1480...1760).contains(ms) || ms == 1840))
        }
        bridge.finish(reason: "manual")
        let record = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(bridge.setJson().utf8))
        XCTAssertEqual(record.completedCount, 0)
    }

    func testKnownMovementDuringDeadlineRecoveryDiscardsTheAttempt() throws {
        let bridge = OverheadPullUpWorkoutBridge(setId: "deadline-moved", trackedAtEpochMilliseconds: 0)
        let selection = BodyAthleteSelection()
        for ms in stride(from: Int64(0), through: 1800, by: 40) {
            _ = try observe(bridge, selection: selection, pose: deadlinePose(ms, missingElbows: (1480...1760).contains(ms)))
        }
        let state = try observe(bridge, selection: selection, pose: deadlinePose(1840), camera: "moved")
        XCTAssertEqual(state.endReason, "setupInvalidated")
        let record = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(bridge.setJson().utf8))
        XCTAssertEqual(record.completedCount, 0)
    }

    func testBridgeRejectsSelectedAthleteFromAnotherSourceFrame() throws {
        let bridge = OverheadPullUpWorkoutBridge(setId:"wrong-ticks",trackedAtEpochMilliseconds:0)
        let selection = BodyAthleteSelection()
        let p = pose(600)
        _ = try selection.observeJson(poseJson:String(decoding:JSONEncoder().encode(p),as:UTF8.self))
        XCTAssertThrowsError(try bridge.observeSelectedJson(selection:selection,sourceValue:601,sourceTimescale:1000,
            camera:"ready",automaticFinish:true,automaticStart:true))
    }
}

final class BodyDipWorkoutNativeTests: XCTestCase {
    private func pose(_ ms: Int64, lowered: Bool = false) -> PoseResult {
        pose(ms, drop: lowered ? 100.0 : 0.0, degrees: lowered ? 110.0 : 170.0)
    }
    private func pose(_ ms: Int64, drop: Double, degrees: Double) -> PoseResult {
        let height = 320.0 - drop
        let offset = height / (2 * tan(degrees * .pi / 360))
        let points: [Landmark] = [ArmMeasurement.Side.left, .right].enumerated().flatMap { i, side in
            let x = i == 0 ? 250.0 : 650.0
            let names: [PoseJoint] = side == .left ? [.leftShoulder, .leftElbow, .leftWrist] : [.rightShoulder, .rightElbow, .rightWrist]
            let positions = [Point2D(x: x, y: 280+drop), Point2D(x: x + (i == 0 ? -offset : offset), y: 600-height/2), Point2D(x: x, y: 600)]
            return zip(names, positions).map { Landmark(joint: $0, position: $1, confidence: 0.9) }
        }
        return PoseResult(timestamp: .init(value: ms, timescale: 1000), imageSize: .init(width: 900, height: 1400),
            people: [.init(landmarks: points)], backend: "authored native bridge", requestRevision: 1)
    }
    private func observe(_ bridge: BodyDipWorkoutBridge, ms: Int64, camera: String = "ready") throws -> BodyDipWorkoutSnapshot {
        let p = pose(ms, lowered: (440...680).contains(ms))
        XCTAssertNil(p.gripObservation)
        let raw = try bridge.observeJson(poseJson: String(decoding: JSONEncoder().encode(p), as: UTF8.self),
            camera: camera, automaticFinish: true)
        return try JSONDecoder().decode(BodyDipWorkoutSnapshot.self, from: Data(raw.utf8))
    }
    func testNativeBodyOnlyCycleAndDurableMethodMetadata() throws {
        let bridge = BodyDipWorkoutBridge(setId: "native", trackedAtEpochMilliseconds: 0)
        var result: BodyDipWorkoutSnapshot?
        for ms in stride(from: Int64(0), through: 1000, by: 40) { result = try observe(bridge, ms: ms) }
        XCTAssertTrue(try XCTUnwrap(result).started)
        XCTAssertEqual(result?.motion.cycles, 1)
        XCTAssertEqual(bridge.closedRecordCount, 1)
        bridge.finish(reason: "manual")
        let set = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(bridge.setJson().utf8))
        XCTAssertEqual(set.reps.count, 1)
        XCTAssertNotNil(set.reps.first?.returnedAt)
        XCTAssertEqual(set.schemaVersion, 2)
        XCTAssertEqual(set.countMethod, "bilateralBodyDipMotion")
        XCTAssertEqual(set.countPolicyVersion, 1)
        XCTAssertNil(set.referenceEdge)
        let compact = try WorkoutRecordCodec().summaryJson(input: bridge.setJson())
        let saved = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(compact.utf8))
        XCTAssertEqual(saved.countMethod, set.countMethod)
        XCTAssertEqual(saved.reps.first?.acceptedAt, set.reps.first?.acceptedAt)
    }
    func testCameraLossCannotCompleteACycleAndStaticPostureCannotStart() throws {
        for camera in ["unavailable", "moved"] {
            let bridge = BodyDipWorkoutBridge(setId: "camera", trackedAtEpochMilliseconds: 0)
            var result: BodyDipWorkoutSnapshot?
            for ms in stride(from: Int64(0), through: 1000, by: 40) {
                result = try observe(bridge, ms: ms, camera: (440...680).contains(ms) ? camera : "ready")
            }
            XCTAssertFalse(try XCTUnwrap(result).started)
            XCTAssertEqual(result?.motion.cycles, 0)
        }
        let standing = BodyDipWorkoutBridge(setId: "standing", trackedAtEpochMilliseconds: 0)
        for ms in stride(from: Int64(2000), through: 4000, by: 40) {
            XCTAssertFalse(try observe(standing, ms: ms).started)
        }
        XCTAssertNil(standing.draftDeltaJson())
    }
    @MainActor func testHeldDipColorUsesFreshFramesBetweenRecordCheckpoints() throws {
        let adapter = WorkoutAnalysisAdapter()
        var heldFrames = 0, framesBeyondOldTimeout = 0
        for ms in stride(from: Int64(0), through: 4000, by: 40) {
            let p = pose(ms, lowered: ms >= 1080)
            let state = try XCTUnwrap(adapter.observeBodyDip(p, camera: "ready", automaticStart: true, recordCalendarTime: false))
            guard state.started, let activity = adapter.currentRecord?.activity,
                  activity.phases.last?.phase == "effortHold" else { continue }
            heldFrames += 1
            if p.timestamp.seconds - (activity.phases.last?.endedAt ?? 0) > 0.35 { framesBeyondOldTimeout += 1 }
            XCTAssertEqual(adapter.effortObservationSeconds, p.timestamp.seconds)
            XCTAssertEqual(PoseOverlayEffort.rate(activity: activity, at: p.timestamp.seconds,
                observedAt: adapter.effortObservationSeconds), 1)
        }
        XCTAssertGreaterThan(heldFrames, 20)
        XCTAssertGreaterThan(framesBeyondOldTimeout, 10, "Exercise the gap between summary checkpoints")
        _ = adapter.observeBodyDip(pose(4040, lowered: true), camera: "unavailable", automaticStart: true, recordCalendarTime: false)
        XCTAssertNil(adapter.effortObservationSeconds)
        XCTAssertNil(PoseOverlayEffort.rate(activity: adapter.currentRecord?.activity, at: 4.04,
            observedAt: adapter.effortObservationSeconds))
        _ = adapter.observeBodyDip(pose(4080, lowered: true), camera: "ready", automaticStart: true, recordCalendarTime: false)
        adapter.interrupt(reason: "test")
        XCTAssertNil(adapter.effortObservationSeconds)
        adapter.start(exercise: .dip, recordCalendarTime: false)
        XCTAssertNil(adapter.effortObservationSeconds)
    }
    @MainActor func testAnalysisAdapterAndLivePresentationUseSharedBodyCountsAndMethod() async throws {
        let adapter = WorkoutAnalysisAdapter()
        var session = LiveSetSession()
        for ms in stride(from: Int64(0), through: 1640, by: 40) {
            let p = pose(ms, lowered: (1080...1320).contains(ms))
            let state = try XCTUnwrap(adapter.observeBodyDip(p, camera: "ready", automaticStart: true, recordCalendarTime: false))
            if state.started {
                XCTAssertNotNil(adapter.currentRecord, "Automatic commit must publish its source-owned record immediately")
                XCTAssertEqual(adapter.currentRecord?.completedCount, state.motion.cycles)
            }
            if state.started, session.phase == .idle {
                session.beginBodyDip(state, side: .left, startedAt: adapter.currentRecord?.startedAt)
            } else if session.phase == .running { session.consumeBodyDip(state) }
        }
        XCTAssertEqual(session.observedMovements, 1)
        XCTAssertEqual(session.counter.observedMovements, 0, "The legacy counter must not run in parallel")
        XCTAssertEqual(adapter.currentRecord?.countMethod, "bilateralTorsoDipRelativeMotion")
        XCTAssertEqual(adapter.currentRecord?.completedCount, 1)
        XCTAssertEqual(adapter.reviewRecord()?.completedCount, 1)
        XCTAssertEqual(session.movementTimes.count, 1)
        let finished = try XCTUnwrap(adapter.finish(reason: "manual"))
        XCTAssertEqual(finished.countMethod, "bilateralTorsoDipRelativeMotion")
        XCTAssertEqual(finished.completedCount, 1)
        adapter.resetBodyDipPreparation()
        _ = adapter.observeBodyDip(pose(2000), camera: "ready", automaticStart: true, recordCalendarTime: false)
        XCTAssertNotEqual(adapter.setId, finished.id)
        XCTAssertTrue(adapter.reps.isEmpty)
    }
    @MainActor func testPartialReturnAndRedescentCannotCompleteBeforeActualTopSupport() throws {
        let adapter = WorkoutAnalysisAdapter()
        for ms in stride(from: Int64(0), through: 2400, by: 40) {
            let p: PoseResult
            if (1040...1360).contains(ms) || ms >= 2040 { p = pose(ms, drop: 140, degrees: 100) }
            else if ms >= 1400 { p = pose(ms, drop: 60, degrees: 120) }
            else { p = pose(ms) }
            let state = try XCTUnwrap(adapter.observeBodyDip(p, camera: "ready", automaticStart: true, recordCalendarTime: false))
            XCTAssertEqual(state.motion.cycles, 0)
            if state.started { XCTAssertEqual(adapter.currentRecord?.completedCount, 0) }
        }
        for ms in stride(from: Int64(2440), through: 2800, by: 40) {
            _ = try XCTUnwrap(adapter.observeBodyDip(pose(ms), camera: "ready", automaticStart: true, recordCalendarTime: false))
        }
        let record = try XCTUnwrap(adapter.finish(reason: "manual"))
        XCTAssertEqual(record.completedCount, 1)
        XCTAssertEqual(record.countMethod, "bilateralTorsoDipRelativeMotion")
        XCTAssertEqual(record.countPolicyVersion, 2)
    }

    @MainActor func testPartialOppositeArmKeepsNativePresentationAndSavedCountConsistent() throws {
        let adapter = WorkoutAnalysisAdapter()
        var session = LiveSetSession()
        for ms in stride(from: Int64(0), through: 1640, by: 40) {
            let original = pose(ms, lowered: (1080...1320).contains(ms))
            let partial = PoseResult(timestamp: original.timestamp, imageSize: original.imageSize,
                people: original.people.map { person in
                    .init(landmarks: person.landmarks.filter { ms < 1080 || $0.joint != .rightWrist })
                }, backend: original.backend, requestRevision: original.requestRevision)
            let state = try XCTUnwrap(adapter.observeBodyDip(partial, camera: "ready", automaticStart: true, recordCalendarTime: false))
            if ms >= 1080 {
                XCTAssertEqual(state.motion.elbowDegrees.count, 2)
                XCTAssertNotNil(state.motion.elbowDegrees[0])
                XCTAssertNil(state.motion.elbowDegrees[1], "Missing arm angle must stay unavailable in native transport")
                XCTAssertEqual(state.motion.currentArmSlots, [0])
                XCTAssertEqual(state.motion.countMethod, "bilateralTorsoDipRelativeMotion")
            }
            if state.started {
                XCTAssertEqual(adapter.currentRecord?.completedCount, state.motion.cycles)
                if session.phase == .idle { session.beginBodyDip(state, side: .left, startedAt: adapter.currentRecord?.startedAt) }
                else { session.consumeBodyDip(state) }
            }
        }
        XCTAssertEqual(session.observedMovements, 1)
        XCTAssertEqual(session.counter.observedMovements, 0)
        let record = try XCTUnwrap(adapter.finish(reason: "manual"))
        XCTAssertEqual(record.completedCount, 1)
        XCTAssertEqual(record.countMethod, "bilateralTorsoDipRelativeMotion")
        XCTAssertEqual(record.countPolicyVersion, 2)
        XCTAssertEqual(record.boundaryPolicyVersion, 4)
        let compact = try WorkoutRecordCodec().summaryJson(input: String(decoding: JSONEncoder().encode(record), as: UTF8.self))
        XCTAssertEqual(try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(compact.utf8)).completedCount, 1)
    }
    @MainActor func testAnalysisAdapterManualStartKeepsPreparedIdentityAndDiscardsPreviewCycles() async throws {
        let adapter = WorkoutAnalysisAdapter()
        for ms in stride(from: Int64(0), through: 1400, by: 40) {
            _ = try XCTUnwrap(adapter.observeBodyDip(pose(ms, lowered: (440...680).contains(ms)),
                camera: "ready", automaticStart: false, recordCalendarTime: false))
        }
        XCTAssertFalse(try XCTUnwrap(adapter.bodyDipSnapshot).started)
        XCTAssertEqual(adapter.bodyDipSnapshot?.motion.cycles, 0)
        let preparedId = adapter.setId
        XCTAssertTrue(adapter.startBodyDipManually())
        XCTAssertNotNil(adapter.currentRecord)
        XCTAssertEqual(adapter.currentRecord?.id, preparedId)
        for ms in stride(from: Int64(1440), through: 2400, by: 40) {
            _ = adapter.observeBodyDip(pose(ms, lowered: (1800...2040).contains(ms)),
                camera: "ready", automaticStart: false, recordCalendarTime: false)
        }
        let finished = try XCTUnwrap(adapter.finish(reason: "manual"))
        XCTAssertEqual(finished.id, preparedId)
        XCTAssertEqual(finished.startedAt, 1.4)
        XCTAssertEqual(finished.completedCount, 1)
    }
}

final class BodyPullUpWorkoutNativeTests: XCTestCase {
    private func pose(_ ms: Int64, rise: Double = 0, degrees: Double = 170, wristDrop: Double = 0) -> PoseResult {
        let h = 350-rise, offset = h/(2*tan(degrees * .pi/360))
        let points: [Landmark] = [ArmMeasurement.Side.left,.right].enumerated().flatMap { i,side in
            let x = i == 0 ? 250.0 : 650.0
            let names: [PoseJoint] = side == .left ? [.leftShoulder,.leftElbow,.leftWrist] : [.rightShoulder,.rightElbow,.rightWrist]
            let positions = [Point2D(x:x,y:250+h+wristDrop),Point2D(x:x+(i == 0 ? -offset : offset),y:250+h/2+wristDrop),Point2D(x:x,y:250+wristDrop)]
            return zip(names,positions).map { Landmark(joint:$0,position:$1,confidence:0.9) }
        }
        return PoseResult(timestamp:.init(value:ms,timescale:1000),imageSize:.init(width:900,height:1400),
            people:[.init(landmarks:points)],backend:"authored native pullup",requestRevision:1)
    }
    private var edge: BarSegment { .init(a:.init(x:100,y:250),b:.init(x:800,y:250)) }
    @MainActor func testNativeOwnerPresentationAndDurableCountUseOneMethodWithoutLegacyCounter() throws {
        let adapter = WorkoutAnalysisAdapter()
        var session = LiveSetSession()
        for ms in stride(from:Int64(0),through:1120,by:40) {
            let p = pose(ms,rise:(440...720).contains(ms) ? 140 : 0,degrees:(440...720).contains(ms) ? 100 : 170)
            let state = try XCTUnwrap(adapter.observeBodyPullUp(p,reference:edge,camera:"ready",automaticStart:true,recordCalendarTime:false))
            if state.started, session.phase == .idle {
                session.beginBodyPullUp(state,side:.left,startedAt:adapter.currentRecord?.startedAt,
                    completedTimes:adapter.reps.filter(\.isCompleteForAnalytics).compactMap(\.returnedAt))
            } else if session.phase == .running { session.consumeBodyPullUp(state) }
        }
        XCTAssertEqual(session.observedMovements,1)
        XCTAssertEqual(session.counter.observedMovements,0)
        XCTAssertEqual(session.movementTimes.count,1)
        XCTAssertEqual(adapter.currentRecord?.completedCount,1)
        XCTAssertEqual(adapter.reviewRecord()?.completedCount,1)
        let saved = try XCTUnwrap(adapter.finish(reason:"manual"))
        XCTAssertEqual(saved.countMethod,"bilateralPullUpRelativeMotion")
        XCTAssertEqual(saved.countPolicyVersion,1);XCTAssertEqual(saved.schemaVersion,2)
        XCTAssertEqual(saved.completedCount,1);XCTAssertNil(saved.trackedAtEpochMilliseconds)
        let compact = try WorkoutRecordCodec().summaryJson(input:String(decoding:JSONEncoder().encode(saved),as:UTF8.self))
        XCTAssertEqual(try JSONDecoder().decode(WorkoutSetRecord.self,from:Data(compact.utf8)).completedCount,1)
    }
    @MainActor func testNativeStaticSetupDoesNotStartAndManualPreviewCannotBankCycles() throws {
        let adapter = WorkoutAnalysisAdapter()
        for ms in stride(from:Int64(0),through:1120,by:40) {
            let p=pose(ms,rise:(440...720).contains(ms) ? 140 : 0,degrees:(440...720).contains(ms) ? 100 : 170)
            let state=try XCTUnwrap(adapter.observeBodyPullUp(p,reference:edge,camera:"ready",automaticStart:false,recordCalendarTime:false))
            XCTAssertFalse(state.started);XCTAssertEqual(state.motion.cycles,0)
        }
        let id=adapter.setId
        XCTAssertTrue(adapter.startBodyPullUpManually())
        for ms in stride(from:Int64(1160),through:2280,by:40) {
            _ = adapter.observeBodyPullUp(pose(ms,rise:(1600...1880).contains(ms) ? 140 : 0,
                degrees:(1600...1880).contains(ms) ? 100 : 170),reference:edge,camera:"ready",automaticStart:false,recordCalendarTime:false)
        }
        let saved=try XCTUnwrap(adapter.finish(reason:"manual"))
        XCTAssertEqual(saved.id,id);XCTAssertEqual(saved.startedAt,1.12);XCTAssertEqual(saved.completedCount,1)
        adapter.resetBodyPullUpPreparation()
        for ms in stride(from:Int64(3000),through:4400,by:40) {
            let state=try XCTUnwrap(adapter.observeBodyPullUp(pose(ms),reference:edge,camera:"ready",automaticStart:true,recordCalendarTime:false))
            XCTAssertFalse(state.started)
        }
        XCTAssertNotEqual(adapter.setId,id);XCTAssertTrue(adapter.reps.isEmpty)
    }
    @MainActor func testNativeCameraOrObservedReferenceLossCannotCreateAWorkout() throws {
        for mode in ["camera","reference"] {
            let adapter=WorkoutAnalysisAdapter()
            for ms in stride(from:Int64(0),through:1120,by:40) {
                let p=pose(ms,rise:(440...720).contains(ms) ? 140 : 0,degrees:(440...720).contains(ms) ? 100 : 170)
                let state=try XCTUnwrap(adapter.observeBodyPullUp(p,reference:mode == "reference" ? nil : edge,
                    camera:mode == "camera" ? "unavailable" : "ready",automaticStart:true,recordCalendarTime:false))
                XCTAssertFalse(state.started);XCTAssertEqual(state.motion.cycles,0)
            }
            XCTAssertNil(adapter.currentRecord)
        }
    }
}
