import Foundation
import Testing
import class WorkoutCore.WorkoutGripCaptureBridge
@testable import HangInThere

struct LiveFramingAssessmentTests {
    @Test func absoluteCameraTiltHandlesBothCamerasAndRejectsMissingInvalidGravity() {
        for level in [SIMD3<Double>(0,-1,0), .init(1,0,0), .init(0,1,0)] {
            #expect(LiveCameraPlacement(gravity:level).isUsable)
        }
        #expect(LiveCameraPlacement(tiltDegrees:20).isUsable)
        #expect(!LiveCameraPlacement(tiltDegrees:20.01).isUsable)
        for sign in [-1.0,1.0] {
            let actual = LiveCameraPlacement(gravity:.init(0,-sqrt(0.5),sign*sqrt(0.5)))
            #expect(abs((actual.tiltDegrees ?? 0)-45) < 0.001)
            #expect(!actual.isUsable)
        }
        for gravity: SIMD3<Double>? in [nil, .zero, .init(0,0,3), .init(.nan,0,0), .init(0,.infinity,0)] {
            #expect(LiveCameraPlacement(gravity:gravity).tiltDegrees == nil)
        }
        for value in [Double.nan, .infinity, -1,91] {
            #expect(LiveCameraPlacement(tiltDegrees:value).tiltDegrees == nil)
        }
        #expect(LiveSetupReadiness(cameraReady:true,framing:.ready,barConfirmed:true,
            phoneStable:true,sceneStable:true,placementReady:false).state == .cameraPlacementNeeded)
        let checks = LiveReadinessChecklist(cameraReady:true,frameFresh:true,framing:.init(state:.ready),
            exerciseTitle:"Pull-up",armDescription:"Both measurable",barConfirmed:true,acquisition:.locked,
            motionAvailable:true,phoneStable:true,sceneStable:true,startingPose:true,paused:false,handsFree:true,
            setupBusy:false,placement:.init(tiltDegrees:45))
        #expect(!checks.allPassed)
        #expect(checks.actionTitle == "LEVEL YOUR PHONE")
        #expect(checks.actionDetail.contains("chest / shoulder height"))
    }

    @Test func railFreeDipRequiresBothArmsButDoesNotNeedABarReference() {
        let one = makePose(side:.left)
        #expect(LiveFramingAssessment(pose:one,side:.left,usesBothArms:true).state == .ready)
        #expect(LiveFramingAssessment(pose:one,side:.left,usesBothArms:true,requiresBothArms:true).state == .selectedArmHidden)
        #expect(LiveSetupReadiness(cameraReady:true,framing:.ready,barConfirmed:false,
            phoneStable:true,sceneStable:true,bodyCameraCalibrated:false).state == .cameraCalibrationNeeded)
        #expect(LiveSetupReadiness(cameraReady:true,framing:.ready,barConfirmed:false,
            phoneStable:true,sceneStable:true,bodyCameraCalibrated:true).state.isReady)
        #expect(!LiveSetupReadiness(cameraReady:true,framing:.ready,barConfirmed:false,
            phoneStable:true,sceneStable:false,bodyCameraCalibrated:true).state.isReady)
    }
    @Test func sustainedUnmeasurableArmsShowRepositioningGuidance() {
        var feedback = LiveArmVisibilityFeedback()
        for tick in 0...4 {
            feedback.observe(visibilityPose(seconds: Double(tick) / 4), running: true,
                side: .left, usesBothArms: true)
            #expect(feedback.needsRepositioning == (tick == 4))
        }
    }

    @Test func eitherUsableArmClearsWarningAndBriefLossDoesNotTriggerIt() {
        var feedback = LiveArmVisibilityFeedback()
        for tick in 0...4 {
            feedback.observe(visibilityPose(seconds: Double(tick) / 4), running: true,
                side: .left, usesBothArms: true)
        }
        #expect(feedback.needsRepositioning)
        feedback.observe(visibilityPose(seconds: 1.25, side: .right), running: true,
            side: .left, usesBothArms: true)
        #expect(!feedback.needsRepositioning)
        for tick in 6...9 {
            feedback.observe(visibilityPose(seconds: Double(tick) / 4), running: true,
                side: .left, usesBothArms: true)
            #expect(!feedback.needsRepositioning)
        }
    }

    @Test func gapsAndRepeatedOrRegressingFramesCannotEarnDuration() {
        for interruption in [0.75, 0.5, 2.0] {
            var feedback = LiveArmVisibilityFeedback()
            for time in [0.0, 0.25, 0.5, 0.75, interruption, 2.25, 2.5, 2.75] {
                feedback.observe(visibilityPose(seconds: time), running: true,
                    side: .left, usesBothArms: true)
                #expect(!feedback.needsRepositioning)
            }
        }
    }

    @Test func nonrunningAndAmbiguousOrInvalidFramesClearArmWarning() {
        let base = visibilityPose(seconds: 1.25)
        let invalid = PoseResult(timestamp: base.timestamp, imageSize: .init(width: 0, height: 0),
            people: base.people, backend: "test", requestRevision: 0)
        let absent = PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
            people: [], backend: "test", requestRevision: 0)
        let ambiguous = PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
            people: base.people + base.people, backend: "test", requestRevision: 0)
        for (pose, running) in [(base, false), (invalid, true), (absent, true), (ambiguous, true)] {
            var feedback = LiveArmVisibilityFeedback()
            for tick in 0...4 {
                feedback.observe(visibilityPose(seconds: Double(tick) / 4), running: true,
                    side: .left, usesBothArms: true)
            }
            #expect(feedback.needsRepositioning)
            feedback.observe(pose, running: running, side: .left, usesBothArms: true)
            #expect(!feedback.needsRepositioning)
        }
    }

    @Test func dipGuidanceStillUsesTheSelectedArm() {
        var feedback = LiveArmVisibilityFeedback()
        for tick in 0...4 {
            feedback.observe(visibilityPose(seconds: Double(tick) / 4, side: .right), running: true,
                side: .left, usesBothArms: false)
        }
        #expect(feedback.needsRepositioning)
        feedback.observe(visibilityPose(seconds: 1.25, side: .left), running: true,
            side: .left, usesBothArms: false)
        #expect(!feedback.needsRepositioning)
    }

    @Test func sustainedLowConfidenceAlsoPromptsWithoutClaimingPhysicalOcclusion() {
        var feedback = LiveArmVisibilityFeedback()
        for tick in 0...4 {
            let base = makePose(side: .left, confidence: 0.1)
            let pose = PoseResult(timestamp: .init(value: Int64(tick * 250), timescale: 1000),
                imageSize: base.imageSize, people: base.people, backend: "test", requestRevision: 0)
            feedback.observe(pose, running: true, side: .left, usesBothArms: true)
        }
        #expect(feedback.needsRepositioning)
        #expect(LiveArmVisibilityFeedback.detail.contains("tracking is unclear"))
    }

    private func visibilityPose(seconds: Double, side: ArmMeasurement.Side? = nil) -> PoseResult {
        let landmarks = side.map { makePose(side: $0).people[0].landmarks } ?? []
        return PoseResult(timestamp: .init(value: Int64((seconds * 1000).rounded()), timescale: 1000),
            imageSize: .init(width: 720, height: 1280), people: [.init(landmarks: landmarks)],
            backend: "test", requestRevision: 0)
    }

    @Test func captureDiagnosticDecodesSharedBridgeAndKeepsHandFailuresDistinct() throws {
        let raw = """
        {"state":"moveCloser","shoulderFraction":0.12,"torsoFraction":0.22,"leftHand":"waiting","rightHand":"waiting","minimumShoulderFraction":0.18,"minimumTorsoFraction":0.28,"policyVersion":1}
        """
        let far = try JSONDecoder().decode(WorkoutGripCaptureFeedback.self, from: Data(raw.utf8))
        #expect(far.title == "MOVE PHONE CLOSER")
        #expect(far.bodyDiagnostic.contains("12%"))
        #expect(far.bodyDiagnostic.contains("22%"))
        #expect(far.targetDiagnostic.contains("18%"))
        #expect(!far.isGood)
        let missing = WorkoutGripCaptureFeedback(state: "showHands", leftHand: "detected", rightHand: "notDetected")
        #expect(missing.title == "SHOW BOTH HANDS")
        #expect(missing.handsDiagnostic == "L: Seen  ·  R: Not seen")
        #expect(!missing.detail.contains("closer"))
        let seen = WorkoutGripCaptureFeedback(state: "handsDetected", leftHand: "detected", rightHand: "detected")
        #expect(seen.isGood)
        #expect(seen.detail.contains("separately"))
        let pose = makePose(side: .left)
        let shared = try WorkoutGripCaptureBridge().assessJson(poseJson: String(decoding: JSONEncoder().encode(pose), as: UTF8.self))
        let decoded = try JSONDecoder().decode(WorkoutGripCaptureFeedback.self, from: Data(shared.utf8))
        #expect(decoded.state == "showUpperBody")
    }
    @Test func gripRepositionPromptRequiresFreshAssociatedTorsoEvidence() {
        func sample(_ sideways: Bool, seconds: Double = 1) -> PoseResult {
            let tuples: [(String, Double, Double, Double, Double)] = [
                ("leftShoulder",600,300,0.2,-0.2),("rightShoulder",400,300,-0.2,-0.2),
                ("leftHip",600,700,0.2,0.2),("rightHip",400,700,-0.2,0.2),
                ("leftWrist",700,100,0.3,-0.4),("rightWrist",300,100,-0.3,-0.4)]
            let body = tuples.map { joint,x,y,wx,wy in
                WorkoutGripBodyPoint(joint: joint, position: .init(x:x,y:y),
                    world: .init(x: sideways ? 0 : wx,y:wy,z:sideways ? -wx : 0),score:0.9)
            }
            let observation = WorkoutGripObservation(seconds:seconds,imageSize:.init(width:1000,height:1000),
                trackedBody:body.map { .init(joint:$0.joint,position:$0.position,score:$0.score) },
                body:body,hands:[],modelId:"test",backend:"test")
            return PoseResult(timestamp:.init(value:1000,timescale:1000),imageSize:observation.imageSize,
                people:[PoseObservation(landmarks:tuples.map { Landmark(joint:PoseJoint(rawValue:$0.0)!,position:.init(x:$0.1,y:$0.2),confidence:0.9) })],
                backend:"test",requestRevision:1,gripObservation:observation)
        }
        #expect(WorkoutGripViewFeedback(pose:sample(true)).prompt == WorkoutGripViewFeedback.repositionPrompt)
        #expect(WorkoutGripViewFeedback(pose:sample(false)).prompt == nil)
        #expect(WorkoutGripViewFeedback(pose:sample(true,seconds:0)).prompt == nil)
        #expect(WorkoutGripViewFeedback(pose:sample(true,seconds:2)).prompt == nil)
        #expect(WorkoutGripViewFeedback(pose:nil).prompt == nil)
        let base=sample(true)
        let ambiguous=PoseResult(timestamp:base.timestamp,imageSize:base.imageSize,people:base.people+base.people,
            backend:base.backend,requestRevision:base.requestRevision,gripObservation:base.gripObservation)
        #expect(WorkoutGripViewFeedback(pose:ambiguous).prompt == nil)
    }
    @Test func oneVisibleSelectedArmIsReady() {
        let pose = makePose(side: .left)
        let result = LiveFramingAssessment(pose: pose, side: .left)
        #expect(result.state == .ready)
        #expect(result.state.isReady)
    }

    @Test func pullUpFramingAcceptsEitherUsableArm() {
        let pose = makePose(side: .right)
        let result = LiveFramingAssessment(pose: pose, side: .left, usesBothArms: true)
        #expect(result.state == .ready)
        #expect(result.title == "Athlete and usable arms visible")
    }

    @Test func oppositeArmCannotSatisfySelectedSide() {
        let pose = makePose(side: .right)
        let result = LiveFramingAssessment(pose: pose, side: .left)
        #expect(result.state == .selectedArmHidden)
    }

    @Test func noPersonAndMultiplePeopleStayDistinct() {
        let empty = PoseResult(
            timestamp: PresentationTime(value: 0, timescale: 30),
            imageSize: ImageSize(width: 720, height: 1280),
            people: [],
            backend: "test",
            requestRevision: 0
        )
        #expect(LiveFramingAssessment(pose: empty, side: .left).state == .noPerson)

        let person = makePose(side: .left).people[0]
        let multiple = PoseResult(
            timestamp: PresentationTime(value: 0, timescale: 30),
            imageSize: ImageSize(width: 720, height: 1280),
            people: [person, person],
            backend: "test",
            requestRevision: 0
        )
        #expect(LiveFramingAssessment(pose: multiple, side: .left).state == .multiplePeople)
    }

    @Test func lowConfidenceArmIsNotReady() {
        let pose = makePose(side: .left, confidence: 0.1)
        let result = LiveFramingAssessment(pose: pose, side: .left)
        #expect(result.state == .selectedArmUnclear)
    }

    @Test func invalidImageGeometryDoesNotBecomeReady() {
        let base = makePose(side: .left)
        let pose = PoseResult(
            timestamp: base.timestamp,
            imageSize: ImageSize(width: 0, height: 1280),
            people: base.people,
            backend: base.backend,
            requestRevision: base.requestRevision
        )
        #expect(LiveFramingAssessment(pose: pose, side: .left).state == .analysisUnavailable)
    }

    @Test func setupReadinessRequiresCameraFramingAndBar() {
        #expect(
            LiveSetupReadiness(
                cameraReady: false,
                framing: .ready,
                barConfirmed: true,
                phoneStable: true,
                sceneStable: true
            ).state == .cameraUnavailable
        )
        #expect(
            LiveSetupReadiness(
                cameraReady: true,
                framing: .selectedArmHidden,
                barConfirmed: true,
                phoneStable: true,
                sceneStable: true
            ).state == .framingIncomplete
        )
        #expect(
            LiveSetupReadiness(
                cameraReady: true,
                framing: .ready,
                barConfirmed: false,
                phoneStable: true,
                sceneStable: true
            ).state == .barReferenceNeeded
        )
        #expect(
            LiveSetupReadiness(
                cameraReady: true,
                framing: .ready,
                barConfirmed: true,
                phoneStable: false,
                sceneStable: true
            ).state == .phoneStabilityNeeded
        )
        #expect(
            LiveSetupReadiness(
                cameraReady: true,
                framing: .ready,
                barConfirmed: true,
                phoneStable: true,
                sceneStable: false
            ).state == .sceneStabilityNeeded
        )
        #expect(
            LiveSetupReadiness(
                cameraReady: true,
                framing: .ready,
                barConfirmed: true,
                phoneStable: true,
                sceneStable: true
            ).state == .ready
        )
    }

    @Test func missingReferenceAndStartPoseAreNotReportedAsHiddenArms() {
        func assessment(bar:Bool, pose:Bool, phone:Bool = true, scene:Bool = true) -> LiveSetupReadiness {
            .init(cameraReady:true,framing:.ready,barConfirmed:bar,phoneStable:phone,
                  sceneStable:scene,startingPose:pose)
        }
        #expect(assessment(bar:false,pose:false).state == .barReferenceNeeded)
        #expect(assessment(bar:true,pose:false).state == .startingPoseNeeded)
        #expect(assessment(bar:true,pose:false,phone:false).state == .phoneStabilityNeeded)
        #expect(assessment(bar:true,pose:false,scene:false).state == .sceneStabilityNeeded)
        #expect(!assessment(bar:true,pose:false).state.isReady)
        #expect(assessment(bar:true,pose:true).state.isReady)
    }

    @Test func unknownIdentityIsReportedBeforeBarOrStartingPose() {
        for bar in [false,true] {
            let result = LiveSetupReadiness(cameraReady:true,framing:.ready,barConfirmed:bar,
                phoneStable:true,sceneStable:true,startingPose:false,identityResolved:false)
            #expect(result.state == .exerciseIdentityNeeded)
            #expect(!result.state.isReady)
        }
    }

    private func makePose(
        side: ArmMeasurement.Side,
        confidence: Double = 0.9
    ) -> PoseResult {
        let joints = side.joints
        let landmarks = [
            Landmark(joint: joints[0], position: Point2D(x: 300, y: 450), confidence: confidence),
            Landmark(joint: joints[1], position: Point2D(x: 340, y: 350), confidence: confidence),
            Landmark(joint: joints[2], position: Point2D(x: 310, y: 250), confidence: confidence)
        ]
        return PoseResult(
            timestamp: PresentationTime(value: 0, timescale: 30),
            imageSize: ImageSize(width: 720, height: 1280),
            people: [PoseObservation(landmarks: landmarks)],
            backend: "test",
            requestRevision: 0
        )
    }
}

struct LiveReadinessChecklistTests {
    @Test func observedBarDoesNotMasqueradeAsMissingWhileIdentityIsUnknown() {
        let result = LiveReadinessChecklist(cameraReady:true,frameFresh:true,
            framing:.init(state:.ready,usesBothArms:true),exerciseTitle:nil,
            armDescription:"Both measurable",barConfirmed:true,acquisition:.locked,
            motionAvailable:true,phoneStable:true,sceneStable:true,startingPose:false,
            paused:false,handsFree:true,setupBusy:false)
        #expect(result.checks.first { $0.id == "rails" }?.status == .passed)
        #expect(result.checks.first { $0.id == "exercise" }?.status == .blocked)
        #expect(result.actionTitle == "IDENTIFYING EXERCISE")
        #expect(!result.allPassed)
    }

    @Test func missingRailsDoesNotReportCameraOrPostureFailure() {
        let result = snapshot(bar: false)
        #expect(result.checks.first { $0.id == "rails" }?.status == .blocked)
        for id in ["phone", "scene", "pose"] {
            #expect(result.checks.first { $0.id == id }?.status == .waiting)
        }
        #expect(result.summary.contains("usable bar or rail edge"))
        #expect(!result.allPassed)
    }

    @Test func staleFramesCannotDisplayRetainedPasses() {
        let result = snapshot(fresh: false)
        #expect(result.checks.allSatisfy { $0.status != .passed })
        #expect(result.summary.contains("fresh analyzed camera frame"))
        #expect(!result.allPassed)
    }

    @Test func cameraOffCannotDisplayRetainedPasses() {
        let result = snapshot(camera: false)
        #expect(result.checks.allSatisfy { $0.status != .passed })
        #expect(result.summary.contains("resume the camera"))
    }

    @Test func measuredArmsDoNotImplyStartingPosture() {
        let result = snapshot(starting: false)
        #expect(result.checks.first { $0.id == "arms" }?.status == .passed)
        #expect(result.checks.first { $0.id == "pose" }?.status == .blocked)
        #expect(result.summary.contains("extended top support"))
    }

    @Test func unavailableMotionIsVisibleBeforeRailAcquisition() {
        let result = snapshot(bar: false, motion: false)
        #expect(result.checks.first { $0.id == "phone" }?.status == .blocked)
        #expect(result.checks.first { $0.id == "phone" }?.value == "Motion unavailable")
        #expect(result.checks.first { $0.id == "rails" }?.status == .waiting)
        #expect(result.summary.contains("motion measurements"))
    }

    @Test func qualifiedGeometryDoesNotHidePausedStart() {
        let result = snapshot(paused: true)
        #expect(result.checks.allSatisfy { $0.status == .passed })
        #expect(!result.allPassed)
        #expect(result.summary.contains("settings are open"))
        let preparing = snapshot(busy: true)
        #expect(!preparing.allPassed)
        #expect(preparing.summary.contains("preparation"))
    }

    @Test func allChecksPassUsesTheConfiguredStartMode() {
        #expect(snapshot().allPassed)
        #expect(snapshot().summary.contains("automatic start"))
        #expect(snapshot(handsFree: false).summary.contains("Tap Start set"))
    }

    @Test func distanceInstructionsIdentifyTheBlockingStage() {
        #expect(snapshot().actionTitle == "READY TO START")
        #expect(snapshot(bar: false).actionTitle == "SHOW THE BAR / RAILS")
        #expect(snapshot(bar: false, motion: false).actionTitle == "MOTION UNAVAILABLE")
        #expect(snapshot(starting: false).actionTitle == "HOLD START POSITION")
        #expect(snapshot(paused: true).actionTitle == "START PAUSED")
        #expect(snapshot(busy: true).actionTitle == "SETTING UP")
    }

    @Test func staleTrackingCannotKeepAnOldDistanceInstruction() {
        let stale = snapshot(fresh: false, bar: false)
        #expect(stale.actionTitle == "WAITING FOR POSE")
        #expect(!stale.actionDetail.contains("rails"))
    }

    private func snapshot(camera: Bool = true, fresh: Bool = true, bar: Bool = true,
                          motion: Bool = true, starting: Bool = true, paused: Bool = false,
                          handsFree: Bool = true, busy: Bool = false) -> LiveReadinessChecklist {
        LiveReadinessChecklist(cameraReady: camera, frameFresh: fresh,
            framing: LiveFramingAssessment(state: .ready, usesBothArms: true),
            exerciseTitle: "Dips", armDescription: "Both measurable", barConfirmed: bar,
            acquisition: .findingEdge, motionAvailable: motion, phoneStable: true,
            sceneStable: true, startingPose: starting, paused: paused,
            handsFree: handsFree, setupBusy: busy)
    }
}
