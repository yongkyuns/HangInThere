#if os(iOS)
import AVFoundation
import CryptoKit
import ObservedRailPixels
import Testing
@testable import HangInThere

@MainActor
struct PoseOverlayPresentationTests {
    private func pose(_ tick: Int64, x: Double = 100, missing: Bool = false,
                      backend: String = "display-test", people: Int = 1) -> PoseResult {
        let person = PoseObservation(landmarks: missing ? [] : [Landmark(joint: .leftElbow,
            position: Point2D(x: x, y: 200), confidence: 0.9)])
        return PoseResult(timestamp: .init(value: tick, timescale: 1000), imageSize: .init(width: 1000, height: 600),
            people: Array(repeating: person, count: people), backend: backend, requestRevision: 1)
    }
    @Test func effortColorsUseObservedSharedPhaseAndNeverKeepStaleHeat() {
        func activity(_ phase: String) -> WorkoutActivity {
            WorkoutActivity(phases: [.init(id: "phase", phase: phase, startedAt: 1, endedAt: 2, observedSeconds: 1)],
                totals: [.init(phase: phase, observedSeconds: 1)], longestHangSeconds: 0, longestSupportSeconds: 0,
                observedSeconds: 1, elapsedSeconds: 1, timelineTruncated: false, metricVersion: 3)
        }
        #expect(PoseOverlayEffort.rate(activity: activity("hang"), at: 2) == 0.25)
        #expect(PoseOverlayEffort.rate(activity: activity("pulling"), at: 2) == 1)
        #expect(PoseOverlayEffort.rate(activity: activity("lowering"), at: 2) == 0.5)
        #expect(PoseOverlayEffort.rate(activity: activity("rising"), at: 2) == 1)
        #expect(PoseOverlayEffort.rate(activity: activity("pulling"), at: 2.36) == nil)
        #expect(PoseOverlayEffort.rate(activity: activity("pulling"), at: 1.9) == nil)
        #expect(PoseOverlayEffort.rate(activity: activity("unobserved"), at: 2) == nil)
        #expect(PoseOverlayEffort.pushUpRate(phase: "ready", setStarted: true, usable: true) == 0.25)
        #expect(PoseOverlayEffort.pushUpRate(phase: "lowering", setStarted: true, usable: true) == 0.5)
        #expect(PoseOverlayEffort.pushUpRate(phase: "lowered", setStarted: true, usable: true) == 1)
        #expect(PoseOverlayEffort.pushUpRate(phase: "ready", setStarted: false, usable: true) == nil)
        #expect(PoseOverlayEffort.pushUpRate(phase: "lowered", setStarted: true, usable: false) == nil)
        #expect(PoseOverlayEffort.pushUpRate(phase: "seekingPlank", setStarted: true, usable: true) == nil)
    }
    @Test func smoothingIsDisplayOnlyAndMissingJointsCannotBorrowOldCoordinates() throws {
        var display = PoseOverlaySmoothing()
        display.observe(pose(0)); let raw = pose(40, x: 120); display.observe(raw)
        let eased = try #require(display.pose?.people.first?.landmark(.leftElbow)?.position.x)
        #expect(eased > 100 && eased < 120)
        #expect(raw.people.first?.landmark(.leftElbow)?.position.x == 120)
        display.observe(pose(80, missing: true))
        #expect(display.pose?.people.first?.landmark(.leftElbow) == nil)
        display.observe(pose(120, x: 130))
        #expect(display.pose?.people.first?.landmark(.leftElbow)?.position.x == 130)
    }
    @Test func gapsSeeksSourceChangesCrowdsAndLargeJumpsResetEasing() {
        let resets = [pose(500, x: 120), pose(0, x: 120), pose(40, x: 120, backend: "new-source"),
                      pose(40, x: 120, people: 2), pose(40, x: 300)]
        for next in resets {
            var display = PoseOverlaySmoothing(); display.observe(pose(0)); display.observe(next)
            #expect(display.pose?.people.first?.landmark(.leftElbow)?.position == next.people.first?.landmark(.leftElbow)?.position)
        }
    }
    @Test func reducedMotionUsesUnsmoothedObservations() {
        var display = PoseOverlaySmoothing(); display.observe(pose(0)); display.observe(pose(40, x: 120), enabled: false)
        #expect(display.pose?.people.first?.landmark(.leftElbow)?.position.x == 120)
    }
}

@MainActor @Suite(.serialized)
struct LiveCameraSelectionTests {
    @Test func qualifiedHangDoesNotResetLiveSetupDuringHighPullBeforeBarLock() {
        let c = LiveCameraPreviewController()
        c.beginEvaluationInput()
        defer { c.endEvaluationInput() }
        let fixture = AutomaticExerciseDetectionTests()
        for t in [0.0,0.2,0.4,0.6] { c.observeExerciseSelection(fixture.pose(.pullUp,t)) }
        #expect(c.resolvedExercise == .pullUp)
        let generation = c.takeEvaluationSetupDiagnostics()["setupGeneration"] as? UInt64
        #expect(generation != nil)
        for t in [0.8,1.0,1.2] {
            let frame = fixture.highPull(t)
            c.observeExerciseSelection(frame)
            #expect(c.resolvedExercise == .pullUp)
            #expect(c.takeEvaluationSetupDiagnostics()["setupGeneration"] as? UInt64 == generation)
            #expect(c.currentBar == nil)
            #expect(!c.observeHandsFreeReadiness(frame,eligible:true,
                reference:AutomaticPullUpBarTests().candidate().edge))
            #expect(c.liveSet.phase == .idle)
            #expect(c.completedRepCount == 0)
        }
        let released = AutomaticDipRailsTests().pose(1.4)
        c.observeExerciseSelection(released)
        #expect(c.resolvedExercise == nil)
        #expect(c.takeEvaluationSetupDiagnostics()["setupGeneration"] as? UInt64 != generation)
        #expect(!c.startSet())
    }

    @Test func singleDipContactCanEarnReadinessOnlyWithItsObservedReference() {
        for side in ArmMeasurement.Side.allCases {
            let fixture = AutomaticDipRailsTests(), controller = LiveCameraPreviewController()
            controller.configureWorkout(selection: .automatic, side: side)
            var acquisition = AutomaticApparatusReference()
            var bar: ConfirmedBar?
            for time in [0.0, 0.2, 0.4, 0.6, 0.9] {
                let frame = fixture.singlePose(time, side: side)
                controller.observeExerciseSelection(frame)
                bar = acquisition.observe(pose: frame, exercise: .dip, side: side,
                    candidates: [fixture.rails[side == .left ? 0 : 1]])
                #expect(!controller.observeHandsFreeReadiness(frame, eligible: true))
            }
            #expect(controller.resolvedExercise == .dip)
            #expect(controller.barRole == (side == .left ? .leftDipRail : .rightDipRail))
            let reference = bar?.referenceEdge
            #expect(reference != nil)
            for ticks: Int64 in [1_000_000, 1_119_999, 1_120_000] {
                let original = fixture.singlePose(Double(ticks) / 1e6, side: side)
                let frame = PoseResult(timestamp: .init(value: ticks, timescale: 1_000_000),
                    imageSize: original.imageSize, people: original.people,
                    backend: original.backend, requestRevision: original.requestRevision)
                controller.observeExerciseSelection(frame)
                #expect(controller.observeHandsFreeReadiness(frame, eligible: true, reference: reference) == (ticks == 1_120_000))
            }
            // The readiness adapter never invents apparatus or starts capture.
            #expect(controller.currentBar == nil)
            #expect(controller.liveSet.phase == .idle)
            #expect(controller.liveSet.counter.observedMovements == 0)
            #expect(!controller.session.isRunning)
        }
    }

    @Test func deepDipMotionRetainsLiveAutoContextButRequiresAFreshTopToEarnReadiness() {
        for side in ArmMeasurement.Side.allCases {
            let controller = LiveCameraPreviewController()
            controller.configureWorkout(selection: .automatic, side: side)
            let fixture = AutomaticDipRailsTests()
            let reference = fixture.rails[side == .left ? 0 : 1][0].edge
            for seconds in [0.0, 0.2, 0.4, 0.6] { controller.observeExerciseSelection(fixture.pose(seconds)) }
            #expect(controller.resolvedExercise == .dip)
            for seconds in [0.8, 1.0] {
                let original = fixture.pose(seconds)
                let deep = PoseResult(timestamp: original.timestamp, imageSize: original.imageSize,
                    people: original.people.map { person in PoseObservation(landmarks: person.landmarks.map { point in
                        let x = ArmMeasurement.Side.right.joints.contains(point.joint) ? 700.0 : 500.0
                        let position: Point2D
                        if point.joint == .leftShoulder || point.joint == .rightShoulder { position = .init(x: x, y: 480) }
                        else if point.joint == .leftElbow || point.joint == .rightElbow { position = .init(x: x + 100, y: 400) }
                        else { position = point.position }
                        return Landmark(joint: point.joint, position: position, confidence: point.confidence)
                    }) }, backend: original.backend, requestRevision: original.requestRevision)
                #expect(AutomaticDipRails.grip(in: deep) == nil)
                controller.observeExerciseSelection(deep)
                #expect(controller.resolvedExercise == .dip)
                #expect(!controller.observeHandsFreeReadiness(deep, eligible: true, reference: reference))
            }
            for ticks: Int64 in [1_100_000, 1_219_999, 1_220_000] {
                let original = fixture.pose(Double(ticks) / 1e6)
                let frame = PoseResult(timestamp: .init(value: ticks, timescale: 1_000_000), imageSize: original.imageSize,
                    people: original.people, backend: original.backend, requestRevision: original.requestRevision)
                controller.observeExerciseSelection(frame)
                #expect(controller.observeHandsFreeReadiness(frame, eligible: true, reference: reference) == (ticks == 1_220_000))
            }
            #expect(controller.liveSet.phase == .idle)
            #expect(controller.liveSet.counter.observedMovements == 0)
            #expect(controller.currentBar == nil)
        }
    }

    @Test func retainedDipIdentityCannotCarryReadinessAcrossFlexion() {
        let controller = LiveCameraPreviewController()
        let fixture = AutomaticDipRailsTests()
        for seconds in [0.0, 0.2, 0.4, 0.6] { controller.observeExerciseSelection(fixture.pose(seconds)) }
        #expect(controller.resolvedExercise == .dip)
        #expect(!controller.observeHandsFreeReadiness(fixture.pose(0.7), eligible: true, reference: fixture.rails[0][0].edge))
        let bent = fixture.pose(0.8, degrees: 100)
        controller.observeExerciseSelection(bent)
        #expect(controller.resolvedExercise == .dip)
        #expect(!controller.observeHandsFreeReadiness(bent, eligible: true, reference: fixture.rails[0][0].edge))
        for ticks: Int64 in [1_000_000, 1_119_999, 1_120_000] {
            let original = fixture.pose(Double(ticks) / 1e6)
            let frame = PoseResult(timestamp: .init(value: ticks, timescale: 1_000_000), imageSize: original.imageSize,
                people: original.people, backend: original.backend, requestRevision: original.requestRevision)
            controller.observeExerciseSelection(frame)
            #expect(controller.observeHandsFreeReadiness(frame, eligible: true, reference: fixture.rails[0][0].edge) == (ticks == 1_120_000))
        }
        #expect(controller.liveSet.phase == .idle)
        #expect(controller.liveSet.counter.observedMovements == 0)
        #expect(controller.currentBar == nil)
    }

    @Test func briefSingleArmLossRetainsQualifiedIdentityWithoutReadinessAndCannotRenewExpiry() {
        // Dips now have a separately qualified single-contact path. Pull-ups
        // still require bilateral overhead evidence and expire after arm loss.
        for exercise in [ExerciseCounter.Exercise.pullUp] {
            let reference = exercise == .pullUp ? AutomaticPullUpBarTests().candidate().edge : AutomaticDipRailsTests().rails[0][0].edge
            let controller = LiveCameraPreviewController()
            func frame(_ ticks: Int64, missing: Bool = false) -> PoseResult {
                let original = exercise == .pullUp ? AutomaticPullUpBarTests().pose(Double(ticks) / 1e6)
                    : AutomaticDipRailsTests().pose(Double(ticks) / 1e6)
                return PoseResult(timestamp: .init(value: ticks, timescale: 1_000_000), imageSize: original.imageSize,
                    people: original.people.map { person in PoseObservation(landmarks: person.landmarks.filter {
                        !missing || ![PoseJoint.rightShoulder, .rightElbow, .rightWrist].contains($0.joint)
                    }) }, backend: original.backend, requestRevision: original.requestRevision)
            }
            for ticks: Int64 in [0,200_000,400_000,700_000,800_000] { controller.observeExerciseSelection(frame(ticks)) }
            #expect(controller.resolvedExercise == exercise)
            for ticks: Int64 in [900_000,1_000_000,1_150_000] {
                controller.observeExerciseSelection(frame(ticks, missing: true))
                #expect(controller.resolvedExercise == exercise)
                #expect(controller.handsFreeStart.state == .waiting)
                #expect(controller.liveSet.phase == .idle)
                #expect(controller.currentBar == nil)
            }
            controller.observeExerciseSelection(frame(1_150_001, missing: true))
            #expect(controller.resolvedExercise == nil)
            #expect(!controller.startSet())

            // Exercise the actual final readiness method used by frame delivery;
            // the recovery frame must not seed the next bilateral dwell.
            let fresh = LiveCameraPreviewController()
            for ticks: Int64 in [0,200_000,400_000,700_000,800_000] { fresh.observeExerciseSelection(frame(ticks)) }
            fresh.observeExerciseSelection(frame(900_000, missing: true))
            #expect(!fresh.observeHandsFreeReadiness(frame(900_000, missing: true), eligible: true, reference: reference))
            fresh.observeExerciseSelection(frame(1_020_000))
            #expect(!fresh.observeHandsFreeReadiness(frame(1_020_000), eligible: true, reference: reference))
            fresh.observeExerciseSelection(frame(1_139_999))
            #expect(!fresh.observeHandsFreeReadiness(frame(1_139_999), eligible: true, reference: reference))
            fresh.observeExerciseSelection(frame(1_140_000))
            #expect(fresh.observeHandsFreeReadiness(frame(1_140_000), eligible: true, reference: reference))
        }
    }

    @Test func automaticIdentityChangesCounterAndRailRoleWithoutStartingOrInventingApparatus() async {
        let controller = LiveCameraPreviewController()
        controller.configureWorkout(selection: .automatic, side: .right)
        #expect(controller.resolvedExercise == nil)
        #expect(controller.workoutTitle == "Auto · detecting")
        for t in [0.0, 0.2, 0.4, 0.7] {
            controller.observeExerciseSelection(AutomaticDipRailsTests().pose(t))
        }
        #expect(controller.resolvedExercise == .dip)
        #expect(controller.liveSet.exercise == .dip)
        #expect(controller.barRole == .rightDipRail)
        #expect(controller.workoutTitle == "Dips detected")
        #expect(controller.currentBar == nil)
        #expect(!controller.startSet())
        #expect(!controller.showsSetStarted)
        #expect(controller.liveSet.phase == .idle)
        #expect(controller.handsFreeStart.state == .waiting)
        controller.observeExerciseSelection(AutomaticPullUpBarTests().pose(0.8))
        #expect(controller.resolvedExercise == nil)
        for t in [1.0, 1.2, 1.4, 1.7] {
            controller.observeExerciseSelection(AutomaticPullUpBarTests().pose(t))
        }
        #expect(controller.resolvedExercise == .pullUp)
        #expect(controller.liveSet.exercise == .pullUp)
        #expect(controller.barRole == .pullUpGrip)
        #expect(!controller.session.isRunning)
        #expect(await controller.selectCamera(.front))
        #expect(controller.resolvedExercise == nil)
        #expect(controller.exerciseSelection == .automatic)
    }

    @Test func manualExerciseSelectionOverridesPoseAndReturningToAutoRequiresNewEvidence() {
        let controller = LiveCameraPreviewController()
        controller.configureWorkout(exercise: .pullUp, side: .left)
        for t in [0.0, 0.2, 0.4, 0.7] {
            controller.observeExerciseSelection(AutomaticDipRailsTests().pose(t))
        }
        #expect(controller.resolvedExercise == .pullUp)
        #expect(controller.exerciseSelection == .pullUp)
        controller.configureWorkout(selection: .automatic, side: .left)
        #expect(controller.resolvedExercise == nil)
        for t in [1.0, 1.2, 1.4, 1.7] {
            controller.observeExerciseSelection(AutomaticDipRailsTests().pose(t))
        }
        #expect(controller.resolvedExercise == .dip)
        controller.configureWorkout(selection: .automatic, side: .right)
        #expect(controller.resolvedExercise == nil)
        #expect(controller.liveSet.side == .right)
    }

    @Test func detailsPauseSurvivesSetupChangesWithoutChangingHandsFreePreference() async {
        let controller = LiveCameraPreviewController()
        controller.setWorkoutDetailsPresented(true)
        #expect(controller.workoutDetailsPresented)
        #expect(controller.handsFreeStartEnabled)
        controller.setHandsFreeStartEnabled(false)
        #expect(await controller.selectCamera(.front))
        controller.configureWorkout(exercise: .dip, side: .right)
        controller.configureWorkout(exercise: .pullUp, side: .left)
        controller.endBarSetup()
        #expect(controller.workoutDetailsPresented)
        #expect(controller.handsFreeStart.state == .waiting)
        controller.setWorkoutDetailsPresented(false)
        #expect(!controller.workoutDetailsPresented)
        #expect(!controller.handsFreeStartEnabled)
        #expect(controller.liveSet.phase == .idle)
    }

    @Test func handsFreePreferenceSurvivesCameraAndExerciseChanges() async {
        let controller = LiveCameraPreviewController()
        #expect(controller.handsFreeStartEnabled)
        #expect(controller.handsFreeStart.state == .waiting)
        #expect(!controller.startSet(), "Hands-free mode still requires valid camera/bar setup.")
        #expect(!controller.showsSetStarted)
        controller.setHandsFreeStartEnabled(false)
        #expect(await controller.selectCamera(.front))
        #expect(!controller.handsFreeStartEnabled)
        controller.configureWorkout(exercise: .dip, side: .right)
        controller.configureWorkout(exercise: .pullUp, side: .left)
        #expect(!controller.handsFreeStartEnabled)
        controller.endBarSetup()
        #expect(controller.handsFreeStart.state == .waiting)
        #expect(!controller.handsFreeStartEnabled)
        controller.setHandsFreeStartEnabled(true)
        #expect(controller.handsFreeStartEnabled)
        #expect(controller.liveSet.phase == .idle)
        #expect(!controller.session.isRunning)
    }

    @Test func selectingFrontBeforeStartDoesNotStartOrAuthorizeCapture() async {
        let controller = LiveCameraPreviewController()
        let authorization = AVCaptureDevice.authorizationStatus(for: .video)
        #expect(controller.cameraPosition == .rear)
        #expect(await controller.selectCamera(.front))
        #expect(controller.cameraPosition.capturePosition == .front)
        #expect(controller.state == .idle)
        #expect(!controller.session.isRunning)
        #expect(controller.session.inputs.isEmpty)
        #expect(controller.session.outputs.isEmpty)
        #expect(controller.currentBar == nil)
        #expect(controller.latestImageSize == nil)
        #expect(!controller.setupReadiness.state.isReady)
        #expect(AVCaptureDevice.authorizationStatus(for: .video) == authorization)
    }

    @Test func cameraSelectionRoundTripRetainsWorkoutConfiguration() async {
        let controller = LiveCameraPreviewController()
        controller.configureWorkout(exercise: .dip, side: .right)
        #expect(await controller.selectCamera(.front))
        #expect(await controller.selectCamera(.rear))
        #expect(controller.cameraPosition.capturePosition == .back)
        #expect(controller.liveSet.exercise == .dip)
        #expect(controller.liveSet.side == .right)
        #expect(controller.liveSet.phase == .idle)
        #expect(controller.barRole == .rightDipRail)
        #expect(controller.canSwitchCamera)
        #expect(!controller.isSwitchingCamera)
        #expect(controller.session.inputs.isEmpty)
    }

    @Test func selectingSameCameraPreservesSessionConfiguration() async {
        let controller = LiveCameraPreviewController()
        let output = AVCaptureVideoDataOutput()
        // An unchanged selection must not tear down a session.
        controller.session.addOutput(output)
        #expect(await controller.selectCamera(.rear))
        #expect(controller.session.outputs.contains { $0 === output })
        // A changed selection removes stale outputs before a later start.
        #expect(await controller.selectCamera(.front))
        #expect(controller.session.outputs.isEmpty)
        #expect(controller.state == .idle)
    }

    @Test func portraitConnectionDisablesAutomaticMirroring() throws {
        let connection = AVCaptureConnection(
            inputPorts: [], output: AVCaptureVideoDataOutput()
        )
        connection.automaticallyAdjustsVideoMirroring = true
        try LiveCameraConnectionConfiguration.apply(to: connection)
        #expect(connection.videoRotationAngle == 90)
        #expect(!connection.automaticallyAdjustsVideoMirroring)
        if connection.isVideoMirroringSupported {
            #expect(!connection.isVideoMirrored)
        }
    }
}
@MainActor @Suite(.serialized)
struct LivePushUpPracticeTests {
    @Test func landscapeIsIsolatedFromTheExistingPortraitWorkouts() {
        let normal = LiveCameraPreviewController(), preview = LiveCameraPreviewController(pushUpPractice: true)
        #expect(normal.usesBodyPullUpTracking)
        #expect(!preview.usesBodyPullUpTracking && preview.usesBodyMotionTracking)
        preview.setPushUpOrientation(.landscapeRight)
        #expect(preview.pushUpVideoRotationAngle == 0)
        preview.setPushUpOrientation(.landscapeLeft)
        #expect(preview.pushUpVideoRotationAngle == 180)
        normal.setPushUpOrientation(.landscapeLeft)
        #expect(normal.pushUpVideoRotationAngle == 90)
        preview.setPushUpOrientation(.portrait)
        #expect(preview.pushUpVideoRotationAngle == 90)
        #expect(preview.session.inputs.isEmpty && preview.workoutHistory.isEmpty)
    }

    /// Authored body/phone/registration observations verify routing and gates,
    /// not native estimator accuracy or physical phone performance.
    @Test(arguments: [true, false]) func practiceUsesTheSharedCounterAndSavesOnlyCameraQualifiedPushUps(cameraAvailable: Bool) async throws {
        let clock = BodyDipCameraClock()
        clock.available = cameraAvailable
        let c = LiveCameraPreviewController(sceneRegistrationWorker: BodyDipCameraRegistration(),
            phoneMotionProvider: { clock.sample }, pushUpPractice: true)
        let context = try #require(CGContext(data: nil, width: 1000, height: 600, bitsPerComponent: 8,
            bytesPerRow: 4000, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray: 0.4, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: 1000, height: 600))
        let pixels = try #require(context.makeImage())
        c.setPushUpOrientation(.landscapeRight)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        var ticks: Int64 = 0
        func deliver(_ fraction: Double) async {
            clock.seconds = Double(ticks)/1000
            let points: [(PoseJoint, Point2D)] = [
                (.leftShoulder, .init(x: 160, y: 180+120*fraction)),
                (.leftElbow, .init(x: 160+100*fraction, y: 300+40*fraction)),
                (.leftWrist, .init(x: 150, y: 420)), (.leftHip, .init(x: 420, y: 220+60*fraction)),
                (.leftKnee, .init(x: 620, y: 240+30*fraction)), (.leftAnkle, .init(x: 820, y: 260))]
            let pose = PoseResult(timestamp: .init(value: ticks, timescale: 1000), imageSize: .init(width: 1000, height: 600),
                people: [.init(landmarks: points.map { .init(joint: $0.0, position: $0.1, confidence: 0.95) })],
                backend: "authored-side-view", requestRevision: 1)
            c.acceptEvaluationFrame(.init(image: pixels, pose: pose, processingMilliseconds: 1))
            await c.settleEvaluationInput()
            ticks += 40
        }
        for _ in 0..<100 { await deliver(0) }
        #expect(c.dipAthleteDecision?.status == "associated")
        #expect(c.hasBodyCameraCalibration == cameraAvailable)
        #expect(c.pushUpSnapshot?.setStarted == false)
        for i in 1...10 { await deliver(Double(i)/10) }
        for _ in 0..<10 { await deliver(1) }
        for i in (0..<10).reversed() { await deliver(Double(i)/10) }
        for _ in 0..<10 { await deliver(0) }
        #expect(c.pushUpSnapshot?.setCycles == (cameraAvailable ? 1 : 0))
        #expect(c.liveSet.phase == .idle && c.completedRepCount == 0)
        #expect(c.latestCompletedWorkout == nil && c.workoutHistory.isEmpty && c.recordingVideoSetId == nil)
        c.finishPushUpPractice()
        #expect(c.lastPushUpSetCount == (cameraAvailable ? 1 : 0))
        #expect(c.pushUpSnapshot == nil)
        #expect(c.workoutHistory.count == (cameraAvailable ? 1 : 0))
        if cameraAvailable {
            let record = try #require(c.latestCompletedWorkout)
            #expect(record.exercise == .pushUp && record.completedCount == 1)
            #expect(record.countMethod == "sideViewPushUp" && record.boundaryPolicyVersion == 9)
            #expect(record.trackedAtEpochMilliseconds != nil && record.activity == nil)
            #expect(record.reps.first?.right.maximum == nil)
            #expect(WorkoutTempo(record: record).reps.count == 1)
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("pushup-history-" + UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: root) }
            let store = WorkoutRecordStore(directory: root)
            try await store.enqueue(record, finalized: true)
            try await store.flushPending()
            let reloaded = try await WorkoutRecordStore(directory: root).recent()
            #expect(reloaded.count == 1 && reloaded.first?.exercise == .pushUp)
            #expect(reloaded.first?.completedCount == 1)
            let summaryJSON = try await store.libraryJourneySet(id: record.id)
            let summary = try JSONDecoder().decode(JourneySetSummary.self, from: Data(summaryJSON.utf8))
            #expect(summary.exercise == .pushUp && summary.completedReps == 1)
            #expect(summary.outwardSeconds != nil && summary.returnSeconds != nil)
        }
    }
}

private actor BodyDipCameraRegistration: StaticSceneRegistering {
    let available: Bool
    init(available: Bool = true) { self.available = available }
    func measure(reference: StaticSceneRegistrationReference, image: CGImage) throws -> StaticSceneRegistrationMeasurement {
        struct Unavailable: Error {}
        guard available else { throw Unavailable() }
        return .init(translations: Array(repeating: .init(dxPixels: 0, dyPixels: 0), count: 4), globalScaleFraction: 0)
    }
}

private actor RecoverableBodyCameraRegistration: StaticSceneRegistering {
    var unavailable = false
    let firstReferenceUnavailable: Bool
    private var firstPatch: CGImage?
    private(set) var referenceIDs = Set<ObjectIdentifier>()
    init(firstReferenceUnavailable: Bool = false) { self.firstReferenceUnavailable = firstReferenceUnavailable }
    func setUnavailable(_ value: Bool) { unavailable = value }
    func measure(reference:StaticSceneRegistrationReference,image:CGImage) -> StaticSceneRegistrationMeasurement {
        let patch = reference.patches[0].image
        if firstPatch == nil { firstPatch = patch }
        referenceIDs.insert(ObjectIdentifier(patch))
        if unavailable || firstReferenceUnavailable && firstPatch === patch {
            return .init(translations:[],globalScaleFraction:nil)
        }
        return .init(translations:Array(repeating:.init(dxPixels:0,dyPixels:0),count:4),globalScaleFraction:0)
    }
}

private actor DeferredBodyDipRegistration: StaticSceneRegistering {
    private var requests:[CheckedContinuation<StaticSceneRegistrationMeasurement,Never>] = []
    private enum Failure: Error { case missingRequest }
    func measure(reference:StaticSceneRegistrationReference,image:CGImage) async -> StaticSceneRegistrationMeasurement {
        await withCheckedContinuation { continuation in
            requests.append(continuation)
        }
    }
    func waitForRequests(_ count:Int) async throws {
        let deadline = ContinuousClock.now.advanced(by:.seconds(5))
        while requests.count < count {
            guard ContinuousClock.now < deadline else { throw Failure.missingRequest }
            try await Task.sleep(for:.milliseconds(10))
        }
    }
    func release(_ index:Int) {
        requests[index].resume(returning:.init(translations:Array(repeating:.init(dxPixels:0,dyPixels:0),count:4),globalScaleFraction:0))
    }
}

@MainActor private final class BodyDipCameraClock {
    var available = true
    var seconds = 0.0
    var angle = 0.0
    var gravity: SIMD3<Double>? = .init(0, -1, 0)
    var sample: PhoneMotionSample? {
        guard available, let q = PhoneOrientationStability.Quaternion(x: 0, y: 0, z: sin(angle/2), w: cos(angle/2)) else { return nil }
        return .init(attitude: q, timestamp: seconds, gravity: gravity)
    }
}

@MainActor @Suite(.serialized)
struct LiveCompletedRepDisplayTests {
    /// Authored trajectory, not pose inference: avoid teleporting a 130px-wide
    /// torso by 200px between adjacent 40ms frames. The production association
    /// correctly rejects that displacement; do not relax it for a test fixture.
    private func interpolate(_ a:PoseResult,_ b:PoseResult,_ fraction:Double) -> PoseResult {
        let f=max(0,min(1,fraction))
        let points=a.people[0].landmarks.map { point -> Landmark in
            guard let other=b.people[0].landmark(point.joint) else {return point}
            return .init(joint:point.joint,position:.init(x:point.position.x+(other.position.x-point.position.x)*f,
                y:point.position.y+(other.position.y-point.position.y)*f),confidence:min(point.confidence,other.confidence))
        }
        return .init(timestamp:a.timestamp,imageSize:a.imageSize,people:[.init(landmarks:points)],backend:a.backend,requestRevision:a.requestRevision)
    }
    @Test func barFreeFlexedPreparationStartsOnlyOnMeasuredMotion() async throws {
        let width = 720, height = 1280
        var bytes = [UInt8](repeating:220,count:width*height*4)
        for i in stride(from:3,to:bytes.count,by:4) { bytes[i] = 255 }
        for y in 300...312 { for x in 130...590 {
            let i = (y*width+x)*4
            bytes[i] = 20; bytes[i+1] = 20; bytes[i+2] = 20
        } }
        let provider = try #require(CGDataProvider(data:Data(bytes) as CFData))
        let pixels = try #require(CGImage(width:width,height:height,bitsPerComponent:8,bitsPerPixel:32,
            bytesPerRow:width*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:.init(rawValue:CGImageAlphaInfo.premultipliedLast.rawValue),
            provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent))
        let clock = BodyDipCameraClock()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.configureWorkout(selection:.automatic,side:.left)
        c.setHandsFreeStartEnabled(true)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        func feed(_ ms:Int64, bent:Bool, high:Bool = false) async {
            let fixture = AutomaticExerciseDetectionTests()
            var p = high ? fixture.highPull(Double(ms)/1000) : bent ? fixture.bentPullUpPose(Double(ms)/1000) : fixture.pose(.pullUp,Double(ms)/1000)
            if (2200...2320).contains(ms) {p=interpolate(fixture.pose(.pullUp,Double(ms)/1000),fixture.highPull(Double(ms)/1000),Double(ms-2160)/160)}
            if (2520...2640).contains(ms) {p=interpolate(fixture.highPull(Double(ms)/1000),fixture.pose(.pullUp,Double(ms)/1000),Double(ms-2480)/160)}
            clock.seconds = Double(ms)/1000
            c.acceptEvaluationFrame(.init(image:pixels,pose:p,processingMilliseconds:1))
            await c.settleEvaluationInput(waitForAcquisition:true)
        }
        for ms in stride(from:Int64(0),through:2000,by:40) {
            await feed(ms,bent:true)
            #expect(c.liveSet.phase == .idle)
            #expect(c.completedRepCount == 0)
            if ms == 1200 {
                let diagnostic = c.takeEvaluationSetupDiagnostics()
                #expect(diagnostic["observedBarConfirmed"] as? Bool == false)
                #expect(diagnostic["exerciseIdentityResolved"] as? Bool == false)
                #expect(c.setupReadiness.state == .exerciseIdentityNeeded)
                #expect(c.readinessChecklist.actionTitle == "IDENTIFYING EXERCISE")
            }
        }
        #expect(c.resolvedExercise == nil)
        #expect(c.currentBar == nil)
        #expect(c.hasBodyCameraCalibration)
        for ms in [Int64(2040),2080,2120,2160] {
            await feed(ms,bent:false)
            #expect(c.liveSet.phase == .idle)
            #expect(c.completedRepCount == 0)
        }
        for ms in stride(from:Int64(2200),through:2480,by:40) { await feed(ms,bent:false,high:true) }
        #expect(c.liveSet.phase == .running)
        #expect(c.completedRepCount == 0)
        for ms in stride(from:Int64(2520),through:2840,by:40) { await feed(ms,bent:false) }
        #expect(c.completedRepCount == 1, "Last motion: \(String(describing:c.liveSet.bodyPullUpSnapshot))")
        #expect(c.liveSet.counter.observedMovements == 0)
        #expect(c.takeEvaluationSetupDiagnostics()["exerciseIdentityPolicyVersion"] as? Int == 8)
        #expect(c.takeEvaluationSetupDiagnostics()["sceneConsensusPolicyVersion"] as? Int == 3)
    }

    @Test func automaticStartUsesFreshMeasuredArmsAfterIdentityAndReferenceAreQualified() async throws {
        let context = try #require(CGContext(data:nil,width:720,height:1280,bitsPerComponent:8,
            bytesPerRow:720*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray:0.4,alpha:1)); context.fill(CGRect(x:0,y:0,width:720,height:1280))
        let pixels = try #require(context.makeImage()), clock = BodyDipCameraClock()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.setHandsFreeStartEnabled(false)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        func feed(_ ms:Int64, scoreDip:Bool = false, moving:Bool = false) async {
            var original = moving ? AutomaticExerciseDetectionTests().highPull(Double(ms+1200)/1000) : AutomaticPullUpBarTests().pose(Double(ms+1200)/1000)
            if (1160...1280).contains(ms) { original=interpolate(AutomaticPullUpBarTests().pose(Double(ms+1200)/1000),original,Double(ms-1120)/160) }
            let pose = PoseResult(timestamp:original.timestamp,imageSize:original.imageSize,
                people:original.people.map { p in .init(landmarks:p.landmarks.map { point in
                    guard scoreDip, point.joint == .rightWrist else { return point }
                    return .init(joint:point.joint,position:point.position,confidence:0.39)
                }) },backend:original.backend,requestRevision:original.requestRevision)
            if scoreDip { #expect(AutomaticExerciseDetection.candidate(in:pose) == nil) }
            clock.seconds = Double(ms+1200)/1000
            c.acceptEvaluationFrame(.init(image:pixels,pose:pose,processingMilliseconds:1))
            await c.settleEvaluationInput(waitForAcquisition:true)
        }
        for ms in stride(from:Int64(-1200),through:-40,by:40) { await feed(ms) }
        for ms in stride(from:Int64(0),through:640,by:40) { await feed(ms) }
        #expect(c.resolvedExercise == .pullUp)
        let setup = try #require(c.beginBarSetup())
        let bar = ConfirmedBar(role:.pullUpGrip,method:.manualEdge,
            referenceEdge:AutomaticPullUpBarTests().candidate().edge,oppositeEdge:nil,
            imageSize:setup.frame.pose.imageSize,sourceTime:setup.frame.pose.timestamp)
        #expect(c.confirmBar(bar,for:setup))
        for ms in stride(from:Int64(680),through:920,by:40) { await feed(ms) }
        #expect(c.liveSet.phase == .idle)
        c.setHandsFreeStartEnabled(true)
        for ms in [Int64(1000),1040,1080,1120] {
            await feed(ms,scoreDip:ms == 1040)
            #expect(c.currentBar == bar)
            #expect(c.resolvedExercise == .pullUp)
            #expect(c.liveSet.phase == .idle)
            #expect(c.completedRepCount == 0)
        }
        for ms in stride(from:Int64(1160),through:1440,by:40) { await feed(ms,moving:true) }
        #expect(c.liveSet.phase == .running)
        #expect(c.completedRepCount == 0)
        #expect(c.takeEvaluationSetupDiagnostics()["startingEvidencePolicyVersion"] as? Int == 1)
    }

    @Test func releasedStraightArmsCannotCompletePullupWhileSetBoundaryConfirmsRelease() async throws {
        let context = try #require(CGContext(data:nil,width:720,height:1280,bitsPerComponent:8,
            bytesPerRow:720*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray:0.4,alpha:1)); context.fill(CGRect(x:0,y:0,width:720,height:1280))
        let pixels = try #require(context.makeImage()), clock = BodyDipCameraClock()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.configureWorkout(exercise:.pullUp,side:.left); c.setHandsFreeStartEnabled(false)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        var afterSetupOffset:Int64=0
        func feed(_ ms:Int64, top:Bool = false, released:Bool = false) async {
            let original = AutomaticPullUpBarTests().pose(Double(ms+1200+afterSetupOffset)/1000)
            var pose = PoseResult(timestamp:original.timestamp,imageSize:original.imageSize,
                people:original.people.map { person in .init(landmarks:person.landmarks.map { point in
                    var position = point.position
                    if released {
                        let x = point.joint.rawValue.hasPrefix("left") ? 410.0 : 280.0
                        switch point.joint {
                        case .leftShoulder, .rightShoulder: position = .init(x:x,y:550)
                        case .leftElbow, .rightElbow: position = .init(x:x,y:650)
                        case .leftWrist, .rightWrist: position = .init(x:x,y:760)
                        default: break
                        }
                    } else if top {
                        switch point.joint {
                        case .leftShoulder: position = .init(x:410,y:350)
                        case .rightShoulder: position = .init(x:280,y:350)
                        case .leftElbow: position = .init(x:520,y:390)
                        case .rightElbow: position = .init(x:180,y:390)
                        default: break
                        }
                    }
                    return .init(joint:point.joint,position:position,confidence:point.confidence)
                }) },backend:original.backend,requestRevision:original.requestRevision)
            if (680...800).contains(ms) {pose=interpolate(original,pose,Double(ms-640)/160)}
            clock.seconds = Double(ms+1200+afterSetupOffset)/1000
            c.acceptEvaluationFrame(.init(image:pixels,pose:pose,processingMilliseconds:1))
            await c.settleEvaluationInput()
        }
        for ms in stride(from:Int64(-1200),through:-40,by:40) { await feed(ms) }
        await feed(0)
        let setup = try #require(c.beginBarSetup())
        let bar = ConfirmedBar(role:.pullUpGrip,method:.manualEdge,
            referenceEdge:AutomaticPullUpBarTests().candidate().edge,oppositeEdge:nil,
            imageSize:setup.frame.pose.imageSize,sourceTime:setup.frame.pose.timestamp)
        #expect(c.confirmBar(bar,for:setup))
        for ms in stride(from:Int64(40),through:320,by:40) { await feed(ms) }
        for ms in stride(from:Int64(360),through:960,by:40) {await feed(ms)}
        afterSetupOffset=640
        #expect(c.startSet(discardQueuedFrame:false))
        for ms in stride(from:Int64(360),through:640,by:40) { await feed(ms) }
        for ms in stride(from:Int64(680),through:1000,by:40) { await feed(ms,top:true) }
        #expect(c.liveSet.observedMovements == 0)
        for ms in stride(from:Int64(1040),through:1320,by:40) { await feed(ms,released:true) }
        #expect(c.liveSet.phase == .running)
        #expect(c.liveSet.counter.observedMovements == 0)
        #expect(c.completedRepCount == 0)
        for ms in stride(from:Int64(1360),through:2480,by:40) { await feed(ms,released:true) }
        let saved = try #require(c.latestCompletedWorkout)
        #expect(saved.endReason == "disengaged")
        #expect(saved.completedCount == 0)
        let attempt = try #require(saved.reps.first)
        #expect(attempt.acceptedAt == nil)
        #expect(attempt.returnedAt == nil)
        #expect(attempt.countPolicyVersion == 4)
        #expect(attempt.countMethod == "overheadPullUpRelativeMotion")
    }

    @Test func acceptedTopDoesNotIncrementDisplayUntilRecordedReturn() async throws {
        let context = try #require(CGContext(data:nil,width:720,height:1280,bitsPerComponent:8,
            bytesPerRow:720*4,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray:0.4,alpha:1)); context.fill(CGRect(x:0,y:0,width:720,height:1280))
        let pixels = try #require(context.makeImage()), clock = BodyDipCameraClock()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.configureWorkout(exercise:.pullUp,side:.left); c.setHandsFreeStartEnabled(false)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        var afterSetupOffset:Int64=0
        func feed(_ ms:Int64, top:Bool = false) async {
            let original = AutomaticPullUpBarTests().pose(Double(ms+1200+afterSetupOffset)/1000)
            var pose = PoseResult(timestamp:original.timestamp,imageSize:original.imageSize,
                people:original.people.map { person in .init(landmarks:person.landmarks.map { point in
                    guard top else { return point }
                    let position:Point2D
                    switch point.joint {
                    case .leftShoulder: position = .init(x:410,y:350)
                    case .rightShoulder: position = .init(x:280,y:350)
                    case .leftElbow: position = .init(x:520,y:390)
                    case .rightElbow: position = .init(x:180,y:390)
                    default: position = point.position
                    }
                    return .init(joint:point.joint,position:position,confidence:point.confidence)
                }) },backend:original.backend,requestRevision:original.requestRevision)
            if (680...800).contains(ms) {pose=interpolate(original,pose,Double(ms-640)/160)}
            if (1400...1520).contains(ms) {pose=interpolate(original,pose,Double(ms-1360)/160)}
            if (1040...1160).contains(ms) {
                let elevated=AutomaticExerciseDetectionTests().highPull(Double(ms+1200+afterSetupOffset)/1000)
                pose=interpolate(elevated,original,Double(ms-1000)/160)
            }
            clock.seconds = Double(ms+1200+afterSetupOffset)/1000
            c.acceptEvaluationFrame(.init(image:pixels,pose:pose,processingMilliseconds:1))
            await c.settleEvaluationInput()
        }
        func displayed() -> Int {
            LiveWorkoutOverlay(session:c.liveSet,liveCompletedReps:c.completedRepCount,
                latestCompletedSet:c.latestCompletedWorkout,afterSetReport:c.latestAfterSetReport,
                cameraReady:true,status:"test",statusSymbol:"checkmark",needsAttention:false,
                showsManualStart:true,canStart:false,canSwitchCamera:false,nextCameraName:"front",
                isRecording:false,countdown:nil,canClose:true,onClose:{},onOptions:{},onSwitchCamera:{},
                onStart:{},onFinish:{},onNextSet:{},onResults:{}).displayedCount
        }
        clock.gravity = .init(0,-sqrt(0.5),sqrt(0.5))
        for ms in stride(from:Int64(-1200),through:-40,by:40) { await feed(ms) }
        await feed(0)
        #expect(c.beginBarSetup() == nil)
        #expect(!c.startSet())
        clock.gravity = .init(0,-1,0)
        await feed(40)
        let setup = try #require(c.beginBarSetup())
        let bar = ConfirmedBar(role:.pullUpGrip,method:.manualEdge,
            referenceEdge:AutomaticPullUpBarTests().candidate().edge,oppositeEdge:nil,
            imageSize:setup.frame.pose.imageSize,sourceTime:setup.frame.pose.timestamp)
        #expect(c.confirmBar(bar,for:setup))
        for ms in stride(from:Int64(80),through:320,by:40) { await feed(ms) }
        for ms in stride(from:Int64(360),through:960,by:40) {await feed(ms)}
        afterSetupOffset=640
        #expect(c.startSet(discardQueuedFrame:false))
        for ms in stride(from:Int64(360),through:640,by:40) { await feed(ms) }
        for ms in stride(from:Int64(680),through:1000,by:40) { await feed(ms,top:true) }
        #expect(c.liveSet.observedMovements == 0)
        #expect(displayed() == 0)
        for ms in stride(from:Int64(1040),through:1360,by:40) { await feed(ms) }
        #expect(displayed() == 1)
        for ms in stride(from:Int64(1400),through:1720,by:40) { await feed(ms,top:true) }
        #expect(c.liveSet.observedMovements == 1)
        #expect(displayed() == 1)
        let report = try #require(JSONSerialization.jsonObject(with:Data(c.qualificationReportJSON().utf8)) as? [String:Any])
        #expect(report["observedMovements"] as? Int == 1)
        #expect(report["completedReps"] as? Int == 1)
        clock.gravity = nil
        // Returning pixels with stable relative attitude/background are not a
        // completed cycle while absolute placement is unavailable.
        for ms in stride(from:Int64(1760),through:2040,by:40) { await feed(ms) }
        #expect(c.cameraPlacement.tiltDegrees == nil)
        #expect(displayed() == 1)
        c.stopSet()
        #expect(c.latestCompletedWorkout?.completedCount == 1)
        #expect(displayed() == 1)
    }
}

/// Actual controller orchestration with authored sensor/registration results.
/// These tests do not qualify camera sensors, body inference or real video.
@MainActor @Suite(.serialized)
struct LiveBodyDipCameraTests {
    @Test func steepOrMissingAbsoluteTiltCannotCalibrateStartOrCount() async throws {
        let pixels = try image(), clock = BodyDipCameraClock()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.configureWorkout(exercise:.dip,side:.left)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        clock.gravity = .init(0, -sqrt(0.5), sqrt(0.5))
        for ms in stride(from:Int64(0),through:2400,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(1440...1680).contains(ms))
        }
        #expect(!c.cameraPlacement.isUsable)
        #expect(c.setupReadiness.state == .cameraPlacementNeeded)
        #expect(c.readinessChecklist.actionTitle == "LEVEL YOUR PHONE")
        #expect(c.liveSet.phase == .idle)
        #expect(!c.startSet()); #expect(c.beginBarSetup() == nil)
        #expect(c.completedRepCount == 0)
        clock.gravity = .init(0,-1,0)
        for ms in stride(from:Int64(2440),through:4800,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(3880...4120).contains(ms))
        }
        #expect(c.liveSet.phase == .running)
        #expect(c.completedRepCount == 1)
        // Good relative attitude and background cannot replace missing gravity.
        clock.gravity = nil
        for ms in stride(from:Int64(4840),through:5600,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(4880...5120).contains(ms))
        }
        #expect(!c.cameraPlacement.isUsable)
        #expect(c.completedRepCount == 1)
        #expect(c.liveSet.observedMovements == 1)
    }

    @Test func frozenMotionReadingsCannotKeepASetQualified() async throws {
        let clock = BodyDipCameraClock(), pixels = try image()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.configureWorkout(exercise:.dip,side:.left)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:2200,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(1440...1680).contains(ms))
        }
        #expect(c.liveSet.observedMovements == 1)
        try await Task.sleep(for:.milliseconds(400))
        // The pose clock advances; the injected sensor timestamp remains frozen.
        c.acceptEvaluationFrame(.init(image:pixels,pose:pose(2240),processingMilliseconds:1))
        await c.settleEvaluationInput()
        #expect(c.cameraPlacement.tiltDegrees == nil)
        #expect(c.liveSet.phase == .finished)
        #expect(c.liveSet.endReason == .setupInvalidated)
        #expect(c.latestCompletedWorkout?.completedCount == 1)
    }
    @Test func temporaryPoseLossKeepsIndependentCameraCalibrationButCannotStart() async throws {
        let clock = BodyDipCameraClock(), pixels = try image()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:1400,by:40) { await deliver(c,clock,pixels,ms) }
        #expect(c.resolvedExercise == .dip)
        #expect(c.hasBodyDipCameraCalibration)
        #expect(c.sceneIsReady)
        clock.seconds = 1.44
        let p = pose(1440)
        c.acceptEvaluationFrame(.init(image:pixels,pose:.init(timestamp:p.timestamp,imageSize:p.imageSize,
            people:[],backend:p.backend,requestRevision:p.requestRevision),processingMilliseconds:1))
        await c.settleEvaluationInput()
        #expect(c.resolvedExercise == nil)
        #expect(c.hasBodyDipCameraCalibration)
        #expect(c.sceneIsReady)
        #expect(c.liveSet.phase == .idle)
        #expect(c.completedRepCount == 0)
        for ms in stride(from:Int64(1480),through:2760,by:40) { await deliver(c,clock,pixels,ms) }
        #expect(c.hasBodyDipCameraCalibration)
        #expect(c.resolvedExercise == .dip)
        #expect(c.liveSet.phase == .idle)
        // Real phone qualification remains independent and mandatory.
        clock.gravity = nil
        await deliver(c,clock,pixels,2800)
        #expect(!c.cameraPlacement.isUsable)
        #expect(!c.startSet())
    }
    @Test func referenceFrameCannotQualifyItsOwnCameraEpoch() async throws {
        let clock = BodyDipCameraClock(), pixels = try image()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.configureWorkout(exercise:.dip,side:.left)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:600,by:40) { await deliver(c,clock,pixels,ms) }
        #expect(c.hasBodyDipCameraCalibration)
        #expect(!c.sceneIsReady)
        #expect(!c.startSet())
        await deliver(c,clock,pixels,640)
        #expect(!c.sceneIsReady)
        for ms in stride(from:Int64(680),through:1040,by:40) { await deliver(c,clock,pixels,ms) }
        #expect(c.sceneIsReady)
        #expect(c.liveSet.phase == .idle)
        #expect(c.completedRepCount == 0)
    }
    @Test func idleUnusableReferenceRecoversOnlyThroughNewIndependentSceneEvidence() async throws {
        let clock = BodyDipCameraClock(), pixels = try image(), worker = RecoverableBodyCameraRegistration(firstReferenceUnavailable:true)
        let c = LiveCameraPreviewController(sceneRegistrationWorker:worker,phoneMotionProvider:{ clock.sample })
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:3600,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(2600...2840).contains(ms))
            if ms < 2600 { #expect(c.liveSet.phase == .idle); #expect(c.completedRepCount == 0) }
        }
        #expect(await worker.referenceIDs.count >= 2)
        #expect(c.sceneIsReady)
        #expect(c.liveSet.phase == .running)
        #expect(c.activeWorkoutRecord?.completedCount == 1)
    }
    @Test func runningSetCannotRebaseAnUnusableCameraReference() async throws {
        let clock = BodyDipCameraClock(), pixels = try image(), worker = RecoverableBodyCameraRegistration()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:worker,phoneMotionProvider:{ clock.sample })
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:2200,by:40) { await deliver(c,clock,pixels,ms,lowered:(1440...1680).contains(ms)) }
        let id = try #require(c.activeWorkoutRecord?.id)
        await worker.setUnavailable(true)
        for ms in stride(from:Int64(2240),through:3440,by:40) { await deliver(c,clock,pixels,ms) }
        #expect(await worker.referenceIDs.count == 1)
        #expect(c.liveSet.phase == .running)
        #expect(c.activeWorkoutRecord?.id == id)
        #expect(c.completedRepCount == 1)
        await worker.setUnavailable(false)
        for ms in stride(from:Int64(3480),through:5000,by:40) { await deliver(c,clock,pixels,ms,lowered:(4200...4440).contains(ms)) }
        #expect(c.activeWorkoutRecord?.id == id)
        #expect(c.completedRepCount == 2)
    }

    @Test func anOldRegistrationCompletionCannotQualifyANewCalibration() async throws {
        let clock = BodyDipCameraClock(), pixels = try image(), worker = DeferredBodyDipRegistration()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:worker,phoneMotionProvider:{ clock.sample })
        c.configureWorkout(exercise:.dip,side:.left)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        // Qualify the original athlete before expecting camera calibration.
        // The deferred registration still belongs to the old camera epoch.
        for ms in stride(from:Int64(0),through:640,by:40) {
            clock.seconds = Double(ms)/1000
            c.acceptEvaluationFrame(.init(image:pixels,pose:pose(ms),processingMilliseconds:1))
        }
        try await worker.waitForRequests(1)
        c.clearBar()
        for ms:Int64 in [680,720] {
            clock.seconds = Double(ms)/1000
            c.acceptEvaluationFrame(.init(image:pixels,pose:pose(ms),processingMilliseconds:1))
        }
        try await worker.waitForRequests(2)
        await worker.release(0)
        await Task.yield()
        #expect(!c.sceneIsReady)
        #expect(c.liveSet.phase == .idle)
        await worker.release(1)
        await c.settleEvaluationInput()
        #expect(!c.sceneIsReady)
        clock.seconds = 1.08
        c.acceptEvaluationFrame(.init(image:pixels,pose:pose(1080),processingMilliseconds:1))
        try await worker.waitForRequests(3)
        await worker.release(2)
        await c.settleEvaluationInput()
        #expect(c.sceneIsReady)
        #expect(c.currentBar == nil)
        #expect(c.liveSet.phase == .idle)
    }
    private func image() throws -> CGImage {
        let context = try #require(CGContext(data: nil, width: 900, height: 1400, bitsPerComponent: 8,
            bytesPerRow: 900*4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray: 0.4, alpha: 1)); context.fill(CGRect(x: 0,y: 0,width: 900,height: 1400))
        return try #require(context.makeImage())
    }
    private func pose(_ ms: Int64, lowered: Bool = false, release: Bool = false) -> PoseResult {
        let drop = lowered ? 100.0 : 0.0, degrees = lowered ? 110.0 : 170.0
        let height = 320-drop, offset = height/(2*tan(degrees * .pi/360))
        let shift = release ? 200.0 : 0.0
        let points = [ArmMeasurement.Side.left,.right].enumerated().flatMap { i, side -> [Landmark] in
            let x = i == 0 ? 250.0 : 650.0
            let names: [PoseJoint] = side == .left ? [.leftShoulder,.leftElbow,.leftWrist] : [.rightShoulder,.rightElbow,.rightWrist]
            let positions = [Point2D(x:x,y:280+drop+shift),Point2D(x:x+(i==0 ? -offset:offset),y:600-height/2+shift),Point2D(x:x,y:600+shift)]
            return zip(names,positions).map { Landmark(joint:$0,position:$1,confidence:0.9) }
        }
        return .init(timestamp:.init(value:ms,timescale:1000),imageSize:.init(width:900,height:1400),
            people:[.init(landmarks:points)],backend:"authored live controller",requestRevision:1)
    }
    private func deliver(_ controller: LiveCameraPreviewController, _ clock: BodyDipCameraClock,
                         _ image: CGImage, _ ms: Int64, lowered: Bool = false, release: Bool = false) async {
        clock.seconds = Double(ms)/1000
        controller.acceptEvaluationFrame(.init(image:image,pose:pose(ms,lowered:lowered,release:release),processingMilliseconds:1))
        await controller.settleEvaluationInput()
    }
    @Test(arguments:[true,false]) func committedDipMotionCanIdentifyExerciseWithoutStaticRailSearchWidth(initialWide: Bool) async throws {
        let clock = BodyDipCameraClock(), pixels = try image()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        // One case loses the old apparatus-search width before static dwell;
        // the other never satisfies that width at all. Both arm chains stay
        // measured and the independent shared motion geometry remains valid.
        for ms in stride(from:Int64(0),through:3000,by:40) {
            clock.seconds = Double(ms)/1000
            let original = pose(ms,lowered:(1440...1680).contains(ms))
            let shift = initialWide && ms < 640 ? 120.0 : 155.0
            let p = PoseResult(timestamp:original.timestamp,imageSize:original.imageSize,
                people:original.people.map { person in .init(landmarks:person.landmarks.map { point in
                    let left = [.leftShoulder,.leftElbow,.leftWrist].contains(point.joint)
                    return .init(joint:point.joint,position:.init(x:point.position.x+(left ? shift : -shift),y:point.position.y),confidence:point.confidence)
                }) },backend:original.backend,requestRevision:original.requestRevision)
            if ms >= 640 || !initialWide { #expect(AutomaticExerciseDetection.apparatusCandidate(in:p) == nil) }
            c.acceptEvaluationFrame(.init(image:pixels,pose:p,processingMilliseconds:1))
            await c.settleEvaluationInput()
            if ms < 1440 {
                #expect(c.liveSet.phase == .idle)
                #expect(c.completedRepCount == 0)
            }
        }
        #expect(c.resolvedExercise == .dip)
        #expect(c.liveSet.phase == .running)
        #expect(c.completedRepCount == 1)
        #expect(c.activeWorkoutRecord?.completedCount == 1)
        #expect(c.currentBar == nil)
        #expect(c.hasBodyDipCameraCalibration)
    }

    @Test func automaticDipStartCountReleaseAndNextSetNeedNoRailOrHandInference() async throws {
        let clock = BodyDipCameraClock(), pixels = try image()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:3000,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(1440...1680).contains(ms))
            #expect(c.currentBar == nil)
        }
        #expect(c.resolvedExercise == .dip)
        #expect(c.liveSet.phase == .running)
        #expect(c.liveSet.observedMovements == 1)
        #expect(c.liveSet.counter.observedMovements == 0)
        let first = try #require(c.activeWorkoutRecord)
        #expect(first.countMethod == "bilateralTorsoDipRelativeMotion")
        #expect(first.countPolicyVersion == 2)
        #expect(first.completedCount == 1)
        let report = try #require(JSONSerialization.jsonObject(with:Data(c.qualificationReportJSON().utf8)) as? [String:Any])
        #expect(report["countMethod"] as? String == "bilateralTorsoDipRelativeMotion")
        #expect(report["boundaryPolicyVersion"] as? Int == 4)
        #expect(report["counterPolicyVersion"] as? Int == 2)
        #expect(report["observedMovements"] as? Int == 1)
        for ms in stride(from:Int64(3040),through:4320,by:40) { await deliver(c,clock,pixels,ms,release:true) }
        let saved = try #require(c.latestCompletedWorkout)
        #expect(saved.id == first.id); #expect(saved.completedCount == 1); #expect(saved.endReason == "disengaged")
        for ms in stride(from:Int64(4360),through:6520,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(5840...6080).contains(ms))
        }
        #expect(c.liveSet.phase == .running)
        #expect(c.liveSet.observedMovements == 1)
        #expect(c.activeWorkoutRecord?.id != saved.id)
        #expect(c.activeWorkoutRecord?.completedCount == 1)
        #expect(c.currentBar == nil)
        #expect(!c.session.isRunning)
    }
    @Test(arguments:["missingElbows","weakElbow","shortForearms"],[70.0,200.0])
    func measuredReleaseCanFinishWhenElbowAnglesBecomeUnavailable(kind: String,wristShift: Double) async throws {
        let pixels = try image(), clock = BodyDipCameraClock()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:2200,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(1440...1680).contains(ms))
        }
        #expect(c.liveSet.phase == .running)
        #expect(c.activeWorkoutRecord?.completedCount == 1)
        let expectedEnd: Int64 = wristShift == 200 ? 2520 : 3240
        for ms in stride(from:Int64(2240),through:expectedEnd,by:40) {
            clock.seconds = Double(ms)/1000
            let full = pose(ms,release:true)
            let original = PoseResult(timestamp:full.timestamp,imageSize:full.imageSize,
                people:full.people.map { person in .init(landmarks:person.landmarks.map { p in
                    guard [.leftWrist,.rightWrist].contains(p.joint) else { return p }
                    return Landmark(joint:p.joint,position:.init(x:p.position.x,y:p.position.y-200+wristShift),confidence:p.confidence)
                }) },backend:full.backend,requestRevision:full.requestRevision)
            let people = original.people.map { person in
                PoseObservation(landmarks:person.landmarks.compactMap { p in
                    guard [.leftElbow,.rightElbow].contains(p.joint) else { return p }
                    if kind == "missingElbows" { return nil }
                    if kind == "weakElbow" { return Landmark(joint:p.joint,position:p.position,confidence:0.2) }
                    let wrist = person.landmark(p.joint == .leftElbow ? .leftWrist : .rightWrist)!.position
                    return Landmark(joint:p.joint,position:.init(x:wrist.x+1,y:wrist.y+1),confidence:p.confidence)
                })
            }
            let observation = PoseResult(timestamp:original.timestamp,imageSize:original.imageSize,people:people,
                backend:original.backend,requestRevision:original.requestRevision)
            #expect(PullUpArmMeasurement(pose:observation).arms.isEmpty)
            c.acceptEvaluationFrame(.init(image:pixels,pose:observation,processingMilliseconds:1))
            await c.settleEvaluationInput()
            if ms < expectedEnd { #expect(c.liveSet.phase == .running) }
        }
        let saved = try #require(c.latestCompletedWorkout)
        #expect(saved.endReason == "disengaged")
        #expect(saved.completedCount == 1)
        #expect(saved.boundaryPolicyVersion == 4)
    }

    @Test func missingPhoneOrBackgroundEvidenceCannotStartOrCount() async throws {
        let pixels = try image()
        for missingPhone in [true,false] {
            let clock = BodyDipCameraClock(); clock.available = !missingPhone
            let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(available:missingPhone),phoneMotionProvider:{ clock.sample })
            c.configureWorkout(exercise:.dip,side:.left)
            c.beginEvaluationInput()
            for ms in stride(from:Int64(0),through:2400,by:40) {
                await deliver(c,clock,pixels,ms,lowered:(1440...1680).contains(ms))
            }
            #expect(c.liveSet.phase == .idle)
            #expect(c.liveSet.observedMovements == 0)
            #expect(c.latestCompletedWorkout == nil)
            #expect(c.currentBar == nil)
            c.endEvaluationInput()
        }
    }
    @Test func manualDipStartDoesNotResetPreparedIdentityOrKeepEarlierCycles() async throws {
        let pixels = try image(), clock = BodyDipCameraClock()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.configureWorkout(exercise:.dip,side:.left); c.setHandsFreeStartEnabled(false)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:2400,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(1440...1680).contains(ms))
        }
        #expect(c.liveSet.phase == .idle); #expect(c.liveSet.observedMovements == 0)
        #expect(c.setupReadiness.state.isReady)
        #expect(c.startSet(discardQueuedFrame:false))
        for ms in stride(from:Int64(2440),through:3400,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(2600...2840).contains(ms))
        }
        #expect(c.liveSet.observedMovements == 1)
        #expect(c.activeWorkoutRecord?.completedCount == 1)
        #expect(c.activeWorkoutRecord?.startedAt == 2.4)
    }
    @Test func phoneRotationEndsTheBodySetWithoutErasingCompletedCycles() async throws {
        let pixels = try image(), clock = BodyDipCameraClock()
        let c = LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{ clock.sample })
        c.configureWorkout(exercise:.dip,side:.left)
        c.beginEvaluationInput(); defer { c.endEvaluationInput() }
        for ms in stride(from:Int64(0),through:2200,by:40) {
            await deliver(c,clock,pixels,ms,lowered:(1440...1680).contains(ms))
        }
        #expect(c.liveSet.observedMovements == 1)
        clock.angle = 3 * .pi/180
        for ms in stride(from:Int64(2240),through:2640,by:40) { await deliver(c,clock,pixels,ms) }
        #expect(c.liveSet.phase == .finished)
        #expect(c.liveSet.endReason == .phoneMoved)
        #expect(c.latestCompletedWorkout?.completedCount == 1)
        #expect(!c.hasBodyDipCameraCalibration)
    }
}

/// Actual live orchestration, with authored sensor/registration results only.
@MainActor @Suite(.serialized)
struct LiveBodyPullUpCameraTests {
    private func image() throws -> CGImage {
        let context = try #require(CGContext(data:nil,width:900,height:1400,bitsPerComponent:8,
            bytesPerRow:3600,space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray:0.4,alpha:1));context.fill(CGRect(x:0,y:0,width:900,height:1400))
        return try #require(context.makeImage())
    }
    private func pose(_ ms:Int64,rise:Double=0,degrees:Double=100,wristDrop:Double=0) -> PoseResult {
        let h=350-rise,offset=h/(2*tan(degrees * .pi/360))
        let points=[ArmMeasurement.Side.left,.right].enumerated().flatMap { i,side -> [Landmark] in
            let x=i == 0 ? 250.0 : 650.0
            let names:[PoseJoint]=side == .left ? [.leftShoulder,.leftElbow,.leftWrist] : [.rightShoulder,.rightElbow,.rightWrist]
            let positions=[Point2D(x:x,y:600-rise),Point2D(x:x+(i == 0 ? -offset : offset),y:(850-rise+wristDrop)/2),Point2D(x:x,y:250+wristDrop)]
            return zip(names,positions).map { Landmark(joint:$0,position:$1,confidence:0.9) }
        }+[Landmark(joint:.leftHip,position:.init(x:250,y:1000-rise),confidence:0.9),
           Landmark(joint:.rightHip,position:.init(x:650,y:1000-rise),confidence:0.9)]
        return .init(timestamp:.init(value:ms,timescale:1000),imageSize:.init(width:900,height:1400),
            people:[.init(landmarks:points)],backend:"authored overhead live",requestRevision:1)
    }
    private func feed(_ c:LiveCameraPreviewController,_ clock:BodyDipCameraClock,_ pixels:CGImage,
                      _ ms:Int64,rise:Double=0,degrees:Double=100,wristDrop:Double=0) async {
        clock.seconds=Double(ms)/1000
        c.acceptEvaluationFrame(.init(image:pixels,pose:pose(ms,rise:rise,degrees:degrees,wristDrop:wristDrop),processingMilliseconds:1))
        await c.settleEvaluationInput()
    }
    @Test func confirmedOverheadMotionIdentifiesStartsCountsReleasesAndRearmsWithoutABar() async throws {
        let clock=BodyDipCameraClock(),pixels=try image()
        let c=LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{clock.sample})
        c.beginEvaluationInput();defer {c.endEvaluationInput()}
        for ms in stride(from:Int64(0),through:1560,by:40) {
            await feed(c,clock,pixels,ms)
            #expect(c.liveSet.phase == .idle);#expect(c.completedRepCount == 0)
        }
        #expect(c.resolvedExercise == nil,"Flexed preparation alone must not confirm identity")
        #expect(c.hasBodyCameraCalibration);#expect(c.sceneIsReady);#expect(c.currentBar == nil)
        for ms in stride(from:Int64(1600),through:1920,by:40) {await feed(c,clock,pixels,ms,rise:140,degrees:70)}
        #expect(c.resolvedExercise == .pullUp);#expect(c.liveSet.phase == .running);#expect(c.completedRepCount == 0)
        for ms in stride(from:Int64(1960),through:2360,by:40) {await feed(c,clock,pixels,ms)}
        #expect(c.completedRepCount == 1);#expect(c.liveSet.observedMovements == 1)
        #expect(c.activeWorkoutRecord?.completedCount == 1);#expect(c.liveSet.counter.observedMovements == 0)
        #expect(c.activeWorkoutRecord?.countMethod == "overheadPullUpRelativeMotion")
        #expect(c.activeWorkoutRecord?.boundaryPolicyVersion == 8)
        #expect(c.repAnalysis.first?.countConfirmedAt != nil)
        #expect(c.currentBar == nil);#expect(c.liveSet.movementTimes.count == 1)
        for ms in stride(from:Int64(2400),through:3680,by:40) {await feed(c,clock,pixels,ms,wristDrop:700)}
        #expect(c.latestCompletedWorkout?.completedCount == 1)
        #expect(c.latestCompletedWorkout?.endReason == "disengaged")
        for ms in stride(from:Int64(3720),through:4920,by:40) {await feed(c,clock,pixels,ms)}
        #expect(c.liveSet.phase == .idle)
        for ms in stride(from:Int64(4960),through:5280,by:40) {await feed(c,clock,pixels,ms,rise:140,degrees:70)}
        for ms in stride(from:Int64(5320),through:5720,by:40) {await feed(c,clock,pixels,ms)}
        #expect(c.liveSet.phase == .running);#expect(c.completedRepCount == 1)
        #expect(c.activeWorkoutRecord?.id != c.latestCompletedWorkout?.id)
    }
    @Test(arguments:["phone","background","placement"]) func independentCameraFailuresCannotStartAnOverheadSet(fault:String) async throws {
        let clock=BodyDipCameraClock(),pixels=try image()
        if fault == "phone" {clock.available=false}
        if fault == "placement" {clock.gravity = .init(0,-sqrt(0.5),sqrt(0.5))}
        let c=LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(available:fault != "background"),phoneMotionProvider:{clock.sample})
        c.configureWorkout(exercise:.pullUp,side:.left)
        c.beginEvaluationInput();defer {c.endEvaluationInput()}
        for ms in stride(from:Int64(0),through:2600,by:40) {
            await feed(c,clock,pixels,ms,rise:(1600...1920).contains(ms) ? 140 : 0,degrees:(1600...1920).contains(ms) ? 70 : 100)
        }
        #expect(c.currentBar == nil);#expect(c.liveSet.phase == .idle)
        #expect(c.completedRepCount == 0);#expect(!c.startSet())
    }
    @Test func manualPreviewDoesNotBankCyclesAndPhoneMovementPreservesCompletedCount() async throws {
        let clock=BodyDipCameraClock(),pixels=try image()
        let c=LiveCameraPreviewController(sceneRegistrationWorker:BodyDipCameraRegistration(),phoneMotionProvider:{clock.sample})
        c.configureWorkout(exercise:.pullUp,side:.left);c.setHandsFreeStartEnabled(false)
        c.beginEvaluationInput();defer {c.endEvaluationInput()}
        for ms in stride(from:Int64(0),through:2360,by:40) {
            await feed(c,clock,pixels,ms,rise:(1600...1920).contains(ms) ? 140 : 0,degrees:(1600...1920).contains(ms) ? 70 : 100)
        }
        #expect(c.liveSet.phase == .idle);#expect(c.completedRepCount == 0)
        #expect(c.startSet(discardQueuedFrame:false))
        for ms in stride(from:Int64(2400),through:3200,by:40) {
            await feed(c,clock,pixels,ms,rise:(2440...2760).contains(ms) ? 140 : 0,degrees:(2440...2760).contains(ms) ? 70 : 100)
        }
        #expect(c.completedRepCount == 1)
        clock.angle=3 * .pi/180
        for ms in stride(from:Int64(3240),through:3560,by:40) {await feed(c,clock,pixels,ms)}
        #expect(c.liveSet.phase == .finished);#expect(c.liveSet.endReason == .phoneMoved)
        #expect(c.latestCompletedWorkout?.completedCount == 1)
    }
}

/// Opt-in development replay through the real iOS live controller. The manifest
/// contains media and optional native observation identities only: no exercise,
/// support, event or set labels enter automatic acquisition. Phone attitude is
/// authored. Source-bound Mac observations are an explicit transport fallback
/// when the simulator's Vision body weights are unavailable; never a claim of
/// fresh iOS inference. Serial delivery excludes throughput/watchdog qualification.
@MainActor @Suite(.serialized)
struct LiveCameraVideoDevelopmentTests {
    private struct Manifest: Decodable {
        struct Clip: Decodable {
            let id: String; let path: String; let sha256: String
            let observations: String?; let observationsSHA256: String?; let export: String?
            let delivery: String?
            let phoneRotationAt: Double?
            // Diagnostic entry at an authored shot boundary only. Decoder and
            // observation inventory still verify the entire original source;
            // this must never masquerade as an uncut full-session evaluation.
            let window: [Double]?
        }
        let clips: [Clip]
        let output: String
    }
    private final class Clock {
        var seconds = 0.0
        var angle = 0.0
        var sample: PhoneMotionSample {
            .init(attitude: PhoneOrientationStability.Quaternion(x:0,y:0,z:sin(angle/2),w:cos(angle/2))!, timestamp:seconds,
                gravity:.init(0,-1,0))
        }
    }
    private struct BoundObservations: PoseEstimator {
        let samples:[String:PoseResult]
        func estimate(image:CGImage,timestamp:PresentationTime) throws -> PoseResult {
            let pose = try #require(samples["\(timestamp.value)/\(timestamp.timescale)"])
            #expect(pose.imageSize == ImageSize(width:Double(image.width),height:Double(image.height)))
            return pose
        }
    }
    private struct RegistrationDiagnostic: Sendable {
        let referenceSHA256: String
        let imageSHA256: String
        let measurement: StaticSceneRegistrationMeasurement
    }
    private actor RegistrationTrace: StaticSceneRegistering {
        let worker = VisionStaticSceneRegistrationWorker(includeDiagnosticHomography:true)
        var latest: RegistrationDiagnostic?
        func measure(reference:StaticSceneRegistrationReference,image:CGImage) async throws -> StaticSceneRegistrationMeasurement {
            let measurement = try await worker.measure(reference:reference,image:image)
            latest = .init(referenceSHA256:Self.digest(reference.image),imageSHA256:Self.digest(image),measurement:measurement)
            return measurement
        }
        static func digest(_ image:CGImage) -> String {
            guard let bytes=image.dataProvider?.data else { return "unavailable" }
            return SHA256.hash(data:bytes as Data).map { String(format:"%02x",$0) }.joined()
        }
    }
    @Test(.enabled(if: ProcessInfo.processInfo.environment["LIVE_CAMERA_VIDEO_MANIFEST"] != nil))
    func unseededAutomaticVideoReplay() async throws {
        let path = try #require(ProcessInfo.processInfo.environment["LIVE_CAMERA_VIDEO_MANIFEST"])
        let manifestURL = evaluationURL(path)
        let manifestData = try Data(contentsOf:manifestURL)
        let manifest = try JSONDecoder().decode(Manifest.self,from:manifestData)
        #expect(!manifest.clips.isEmpty)
        #expect(Set(manifest.clips.map(\.id)).count == manifest.clips.count)
        let output = evaluationURL(manifest.output)
        try FileManager.default.createDirectory(at:output,withIntermediateDirectories:true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        for clip in manifest.clips {
            #expect(!clip.id.isEmpty && !clip.id.contains("/") && !clip.id.contains(".."))
            let source = evaluationURL(clip.path)
            let thermalStateAtStart = ProcessInfo.processInfo.thermalState.rawValue
            let delivery = clip.delivery ?? "accelerated"
            #expect(["accelerated","settledAcquisition","sourcePaced"].contains(delivery))
            #expect(try hash(source) == clip.sha256)
            let expected = try await inventory(source)
            if let window = clip.window {
                #expect(window.count == 2)
                #expect(window.allSatisfy(\.isFinite) && window[0] < window[1])
            }
            let reader:VideoReplayReader
            if let observations = clip.observations {
                let url = evaluationURL(observations)
                #expect(try hash(url) == clip.observationsSHA256)
                let export = try #require(clip.export)
                let metadata = try #require(JSONSerialization.jsonObject(with:Data(contentsOf:evaluationURL(export))) as? [String:Any])
                #expect(metadata["videoSHA256"] as? String == clip.sha256)
                #expect(metadata["observationsSHA256"] as? String == clip.observationsSHA256)
                #expect(metadata["sourceClockVerified"] as? Bool == true)
                let poses = try Data(contentsOf:url).split(separator:10).map { try JSONDecoder().decode(PoseResult.self,from:Data($0)) }
                #expect(poses.map(\.timestamp) == expected)
                #expect(poses.allSatisfy { $0.backend == "Apple Vision 2D" && $0.requestRevision == 1 })
                let indexed = Dictionary(poses.map { ("\($0.timestamp.value)/\($0.timestamp.timescale)",$0) },uniquingKeysWith:{ first,_ in first })
                #expect(indexed.count == poses.count)
                reader = VideoReplayReader(estimator:BoundObservations(samples:indexed))
            } else {
                #expect(clip.observationsSHA256 == nil && clip.export == nil)
                reader = VideoReplayReader()
            }
            _ = try await reader.open(source)
            let clock = Clock()
            let registration = RegistrationTrace()
            let c = LiveCameraPreviewController(sceneRegistrationWorker:registration,phoneMotionProvider:{ clock.sample })
            c.beginEvaluationInput()
            defer { c.endEvaluationInput() }
            var seen:[PresentationTime] = []
            var delivered:[PresentationTime] = []
            var rows:[[String:Any]] = []
            var records:[String:WorkoutSetRecord] = [:]
            var firstSeconds:Double?
            let began = ContinuousClock.now
            let expectedSeen = expected.filter { time in
                clip.window.map { time.seconds < $0[1] } ?? true
            }
            while let frame = try await nextFrame(reader) {
                // Prefix diagnostics preserve all earlier setup/context. Stop
                // at their declared end; full-source deliveries are unchanged.
                if let window = clip.window, frame.pose.timestamp.seconds >= window[1] { break }
                seen.append(frame.pose.timestamp)
                if let window = clip.window,
                   !(window[0]..<window[1]).contains(frame.pose.timestamp.seconds) { continue }
                if firstSeconds == nil { firstSeconds = frame.pose.timestamp.seconds }
                if delivery == "sourcePaced" {
                    try await Task.sleep(until:began.advanced(by:.seconds(frame.pose.timestamp.seconds-firstSeconds!)),clock:.continuous)
                }
                clock.seconds = frame.pose.timestamp.seconds
                if let at = clip.phoneRotationAt, clock.seconds >= at { clock.angle = 3 * .pi/180 }
                delivered.append(frame.pose.timestamp)
                let deliveryBegan = ContinuousClock.now
                c.acceptEvaluationFrame(frame)
                await c.settleEvaluationInput(waitForAcquisition:delivery == "settledAcquisition")
                let deliveryMilliseconds = ReplayTrace.milliseconds(since:deliveryBegan)
                for record in c.workoutHistory { records[record.id] = record }
                var row:[String:Any] = ["seconds":clock.seconds,
                    "sourceValue":frame.pose.timestamp.value,"sourceTimescale":frame.pose.timestamp.timescale,
                    // Reader timing includes decode/render/body inference. It
                    // excludes live capture and is not a camera drop/latency budget.
                    "frameProcessingMilliseconds":frame.processingMilliseconds,
                    "controllerDeliveryMilliseconds":deliveryMilliseconds,
                    "exercise":c.resolvedExercise?.rawValue ?? "unknown",
                    "phase":c.liveSet.phase.rawValue,"count":c.liveSet.observedMovements,
                    "displayedCompletedReps":c.completedRepCount,
                    "sceneReady":c.sceneIsReady,"phoneReady":c.phoneOrientation.state.allowsLiveSet,
                    "sceneState":c.sceneTranslation.state.rawValue,
                    "sceneShift":c.sceneTranslation.latestShiftFraction as Any? ?? NSNull(),
                    "sceneScale":c.sceneTranslation.latestScaleFraction as Any? ?? NSNull(),
                    "sceneConsensusPatches":c.sceneTranslation.latestConsensusPatches,
                    "cameraEvidenceFrames":c.bodyCameraIndependentObservations,
                    "cameraReferenceSeconds":c.bodyCameraReferenceSeconds as Any? ?? NSNull(),
                    "cameraReacquisitions":c.bodyCameraReacquisitions,
                    "imageSHA256":RegistrationTrace.digest(frame.image),
                    "hasSupportReference":c.currentBar != nil,"readiness":c.setupReadiness.state.rawValue,
                    "setupDiagnostic":c.takeEvaluationSetupDiagnostics(),
                    "pose":try JSONSerialization.jsonObject(with:encoder.encode(frame.pose))]
                if let trace = await registration.latest {
                    row["registration"] = ["referenceSHA256":trace.referenceSHA256,"imageSHA256":trace.imageSHA256,
                        "translations":trace.measurement.translations.map { [$0.dxPixels,$0.dyPixels] },
                        "scale":trace.measurement.globalScaleFraction as Any? ?? NSNull(),
                        "homography":trace.measurement.diagnosticHomography as Any? ?? NSNull(),
                        "scaleMeasurementPolicyVersion":VisionStaticSceneRegistrationWorker.measurementPolicyVersion,
                        "scaleEvidenceReason":trace.measurement.backgroundScale?.reason as Any? ?? NSNull(),
                        "scaleCorrespondences":trace.measurement.backgroundScale?.correspondences as Any? ?? NSNull(),
                        "scaleInliers":trace.measurement.backgroundScale?.inliers as Any? ?? NSNull(),
                        "backgroundPointSamplerVersion": BackgroundCameraPixels.policyVersion,
                        "backgroundPoints": trace.measurement.backgroundScale?.translations.map {
                            ["reference": [$0.referenceX, $0.referenceY], "current": [$0.currentX, $0.currentY],
                             "region": $0.region] as [String: Any]
                        } as Any? ?? NSNull(),
                        "diagnosticLegacyTranslations": trace.measurement.diagnosticLegacyTranslations?.map {
                            [$0.dxPixels, $0.dyPixels]
                        } as Any? ?? NSNull()]
                }
                if let bar = c.currentBar {
                    row["supportReferenceSourceSeconds"] = bar.sourceTime.seconds
                    row["supportEdge"] = try JSONSerialization.jsonObject(with:encoder.encode(bar.referenceEdge))
                }
                if let reason = c.liveSet.endReason { row["endReason"] = reason.rawValue }
                if let snapshot = c.liveSet.bodyDipSnapshot {
                    row["bodyDip"] = try JSONSerialization.jsonObject(with:encoder.encode(snapshot))
                }
                if let selection = c.dipAthleteDecision {
                    row["dipAthleteSelection"] = try JSONSerialization.jsonObject(with:encoder.encode(selection))
                }
                if let preparation = c.bodyDipPreparation {
                    row["privateDipPreparation"] = try JSONSerialization.jsonObject(with:encoder.encode(preparation))
                }
                if let snapshot = c.liveSet.bodyPullUpSnapshot {
                    row["bodyPullUp"] = try JSONSerialization.jsonObject(with:encoder.encode(snapshot))
                }
                if let record = c.activeWorkoutRecord { row["activeSetId"] = record.id }
                rows.append(row)
                if delivered.count % 600 == 0 {
                    print("LIVE_REPLAY \(clip.id): \(delivered.count) original frames, source \(clock.seconds)s, \(records.count) closed sets, live completed \(c.completedRepCount), scene \(c.sceneTranslation.state), ready \(c.sceneIsReady), evidence \(c.bodyCameraIndependentObservations), retries \(c.bodyCameraReacquisitions), dip \(c.bodyDipPreparation?.motion.reason ?? "none")")
                }
            }
            await reader.close()
            let sorted = records.values.sorted { ($0.startedAt ?? 0) < ($1.startedAt ?? 0) }
            #if targetEnvironment(simulator)
            let executionPlatform = "iOSSimulator"
            #elseif os(iOS)
            let executionPlatform = "iOSDevice"
            #else
            let executionPlatform = "otherApplePlatform"
            #endif
            var report:[String:Any] = ["scope":"Development video through actual iOS live controller. Original decoded pixels and real registration, authored fixed-phone attitude and serial delivery. Fresh body inference only when no observation transport is supplied. No live sensor/capture, watchdog, camera throughput or independent accuracy qualification.",
                "id":clip.id,"sha256":clip.sha256,"manifestSHA256":digest(manifestData),
                "executionPlatform":executionPlatform,
                "operatingSystem":ProcessInfo.processInfo.operatingSystemVersionString,
                "relativePathRoot":ProcessInfo.processInfo.environment["LIVE_CAMERA_VIDEO_ROOT"] == nil ? "appHome":"explicitTransportRoot",
                "thermalStateAtStart":thermalStateAtStart,
                "thermalStateAtEnd":ProcessInfo.processInfo.thermalState.rawValue,
                "delivery":delivery,
                "authoredPhoneRotationAt":clip.phoneRotationAt as Any? ?? NSNull(),
                "bodyObservationSource":clip.observations == nil ? "freshIOSVision":"sourceBoundMacVisionTransport",
                "observationsSHA256":clip.observationsSHA256 as Any? ?? NSNull(),
                "frames":seen.count,"sourceInventoryFrames":expected.count,"sourceClockVerified":seen == expectedSeen,
                "deliveredFrames":delivered.count,
                "diagnosticWindow":clip.window as Any? ?? NSNull(),
                "records":try sorted.map { try JSONSerialization.jsonObject(with:encoder.encode($0)) },
                "rows":rows]
            if c.liveSet.phase == .running, let active = c.activeWorkoutRecord {
                report[clip.window == nil ? "activeAtSourceEnd" : "activeAtWindowEnd"] = try JSONSerialization.jsonObject(with:encoder.encode(active))
            }
            try JSONSerialization.data(withJSONObject:report,options:[.sortedKeys])
                .write(to:output.appendingPathComponent(clip.id+".json"),options:.withoutOverwriting)
            #expect(seen == expectedSeen)
            let expectedDelivery = expected.filter { time in
                guard let window = clip.window else { return true }
                return (window[0]..<window[1]).contains(time.seconds)
            }
            #expect(delivered == expectedDelivery)
            #expect(try hash(source) == clip.sha256)
            print("Live controller video \(clip.id): \(seen.count) frames, \(sorted.count) closed sets")
        }
        #expect(try Data(contentsOf:manifestURL) == manifestData)
    }
    /// Device media/results live in the app container. Absolute Mac fixtures
    /// retain their existing meaning; a root override exercises relative I/O
    /// on the simulator without supplying any counting or pose evidence.
    private func evaluationURL(_ path:String) -> URL {
        if path.hasPrefix("/") { return URL(fileURLWithPath:path) }
        let root = ProcessInfo.processInfo.environment["LIVE_CAMERA_VIDEO_ROOT"] ?? NSHomeDirectory()
        return URL(fileURLWithPath:root,isDirectory:true).appendingPathComponent(path)
    }
    private func nextFrame(_ reader:VideoReplayReader) async throws -> ProcessedFrame? {
        do { return try await reader.nextFrame() }
        catch { await reader.close(); throw error }
    }
    private func inventory(_ source:URL) async throws -> [PresentationTime] {
        let asset = AVURLAsset(url:source)
        let track = try #require(try await asset.loadTracks(withMediaType:.video).first)
        let reader = try AVAssetReader(asset:asset)
        let output = AVAssetReaderTrackOutput(track:track,outputSettings:[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA])
        reader.add(output)
        #expect(reader.startReading())
        var result:[PresentationTime] = []
        while let sample = output.copyNextSampleBuffer() {
            let time = CMSampleBufferGetPresentationTimeStamp(sample)
            result.append(.init(value:time.value,timescale:time.timescale))
        }
        #expect(reader.status == .completed)
        #expect(!result.isEmpty)
        return result
    }
    private func digest(_ data:Data) -> String { SHA256.hash(data:data).map { String(format:"%02x",$0) }.joined() }
    private func hash(_ url:URL) throws -> String {
        let file = try FileHandle(forReadingFrom:url); defer { try? file.close() }
        var hash = SHA256()
        while let data = try file.read(upToCount:1024*1024), !data.isEmpty { hash.update(data:data) }
        return hash.finalize().map { String(format:"%02x",$0) }.joined()
    }
}
#endif
