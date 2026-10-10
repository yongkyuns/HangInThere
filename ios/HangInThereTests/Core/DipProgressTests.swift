import Foundation
import Testing
@testable import HangInThere

struct DipProgressTests {
    private func feed(_ counter: inout ExerciseCounter, _ time: Double, _ degrees: Double = 170,
                      other: Double? = nil) {
        counter.consume(DipSupportTests.pose(time, degrees: degrees, otherDegrees: other),
                        referenceEdge: DipSupportTests.verticalRail)
    }
    private func armed(other: Double? = nil) -> ExerciseCounter {
        var counter = ExerciseCounter(exercise: .dip)
        feed(&counter, 0, other: other)
        feed(&counter, 0.15, other: other)
        return counter
    }
    private func bottom(other: Double? = nil) -> ExerciseCounter {
        var counter = armed(other: other.map { _ in 170 })
        feed(&counter, 0.3, 80, other: other)
        feed(&counter, 0.45, 80, other: other)
        return counter
    }
    private func counted() -> ExerciseCounter {
        var counter = bottom()
        feed(&counter, 0.6)
        feed(&counter, 0.75)
        return counter
    }

    @Test func acquisitionAndReadyDoNotImplyCompletedWork() {
        var c = ExerciseCounter(exercise: .dip)
        #expect(c.dipProgress?.stage == .startingSupport)
        feed(&c, 0)
        feed(&c, 0.06)
        #expect(c.dipProgress?.fraction == 0)
        feed(&c, 0.15)
        #expect(c.dipProgress?.stage == .ready)
        #expect(c.dipProgress?.repNumber == 1)
        #expect(c.dipProgress?.fraction == 0)
        #expect(c.observedMovements == 0)
    }

    @Test func descentAndReturnReserveCompletionForTheCountEvent() throws {
        var c = armed()
        feed(&c, 0.3, 110)
        let lowering = try #require(c.dipProgress)
        #expect(lowering.stage == .lowering)
        #expect(lowering.fraction > 0 && lowering.fraction < 0.45)
        feed(&c, 0.45, 80)
        #expect(c.dipProgress?.stage == .holdingBottom)
        #expect(c.dipProgress?.fraction == 0.45)
        feed(&c, 0.51, 80)
        #expect((c.dipProgress?.fraction ?? 0) > 0.45)
        #expect((c.dipProgress?.fraction ?? 1) < 0.5)
        feed(&c, 0.6, 80)
        #expect(c.dipProgress?.stage == .rising)
        #expect(c.dipProgress?.fraction == 0.5)
        feed(&c, 0.75, 110)
        #expect((c.dipProgress?.fraction ?? 0) > 0.5)
        #expect((c.dipProgress?.fraction ?? 1) < 0.9)
        feed(&c, 0.9)
        #expect(c.dipProgress?.stage == .holdingTop)
        #expect(c.dipProgress?.fraction == 0.9)
        feed(&c, 0.96)
        #expect((c.dipProgress?.fraction ?? 0) > 0.9)
        #expect((c.dipProgress?.fraction ?? 1) < 1)
        #expect(c.observedMovements == 0)
        feed(&c, 1.05)
        #expect(c.observedMovements == 1)
        #expect(c.dipProgress?.stage == .counted)
        #expect(c.dipProgress?.fraction == 1)
        #expect(c.dipProgress?.repNumber == 1)
    }

    @Test func nextDescentClearsEarnedConfirmationAndStartsNextRep() {
        var c = counted()
        feed(&c, 0.85)
        #expect(c.dipProgress?.stage == .counted)
        feed(&c, 1, 110)
        #expect(c.dipProgress?.stage == .lowering)
        #expect(c.dipProgress?.repNumber == 2)
        #expect((c.dipProgress?.fraction ?? 1) < 0.5)
        #expect(c.observedMovements == 1)
    }

    @Test func confirmationExpiresOnSourceFramesRatherThanDisplayReads() {
        var c = counted()
        let earned = c.dipProgress
        for _ in 0..<20 { #expect(c.dipProgress == earned) }
        for time in [0.9, 1.1, 1.3, 1.5] { feed(&c, time) }
        #expect(c.dipProgress?.stage == .ready)
        #expect(c.dipProgress?.repNumber == 2)
        #expect(c.dipProgress?.fraction == 0)
    }

    @Test func shallowReturnCannotEarnSecondHalfOrAdvanceRepNumber() {
        var c = armed()
        feed(&c, 0.3, 110)
        feed(&c, 0.45)
        #expect(c.dipProgress?.stage == .startingSupport)
        #expect(c.dipProgress?.fraction == 0)
        feed(&c, 0.6)
        #expect(c.partialAttempts == 1)
        #expect(c.dipProgress?.stage == .ready)
        #expect(c.dipProgress?.repNumber == 1)
        #expect(c.observedMovements == 0)
    }

    @Test func visibleOppositeArmConstrainsBothHalves() {
        var c = armed(other: 170)
        feed(&c, 0.3, 80, other: 170)
        feed(&c, 0.45, 80, other: 170)
        #expect(c.dipProgress?.fraction == 0)
        #expect(c.dipProgress?.trackedArmCount == 2)
        feed(&c, 0.6, 80, other: 80)
        feed(&c, 0.75, 80, other: 80)
        feed(&c, 0.9, other: 80)
        feed(&c, 1.05, other: 80)
        #expect(c.dipProgress?.fraction == 0.5)
        #expect(c.dipProgress?.cue == .extendArms)
        #expect(c.observedMovements == 0)
        feed(&c, 1.2, other: 170)
        feed(&c, 1.35, other: 170)
        #expect(c.dipProgress?.stage == .counted)
    }

    @Test func bendingWithoutSupportTravelCannotFillFirstHalf() {
        var c = armed()
        let top = DipSupportTests.pose(0, degrees: 170).people[0].landmarks
            .first { $0.joint == .leftShoulder }!.position
        // Independently articulated elbow, fixed shoulder and wrist: enough
        // bend alone cannot earn the required fixed-axis shoulder excursion.
        let base = DipSupportTests.pose(0.3)
        let landmarks = base.people[0].landmarks.map { landmark in
            Landmark(joint: landmark.joint,
                     position: landmark.joint == .leftElbow ? .init(x: 640, y: 380) : landmark.position,
                     confidence: landmark.confidence)
        }
        #expect(landmarks.first { $0.joint == .leftShoulder }?.position == top)
        let pose = PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                              people: [.init(landmarks: landmarks)], backend: base.backend,
                              requestRevision: base.requestRevision)
        c.consume(pose, referenceEdge: DipSupportTests.verticalRail)
        #expect(c.dipProgress?.fraction == 0)
        #expect(c.observedMovements == 0)
    }

    @Test func missingEvidenceClearsUnearnedAndEarnedDisplay() {
        for initial in [bottom(), counted()] {
            var c = initial
            c.consume(ExerciseCounterTests.noPersonPose(0.85), referenceEdge: DipSupportTests.verticalRail)
            #expect(c.dipProgress?.stage == .paused)
            #expect(c.dipProgress?.fraction == 0)
            #expect(c.dipProgress?.trackedArmCount == 0)
            #expect(c.observedMovements == initial.observedMovements)
        }
    }

    @Test func lowConfidenceAnglesCannotShowUnobservedTopCompletion() {
        var c = bottom()
        c.consume(ExerciseCounterTests.poseWithLowConfidenceElbow(0.6, degrees: 170,
            exercise: .dip, side: .left), referenceEdge: DipSupportTests.verticalRail)
        #expect(c.dipProgress?.stage == .paused)
        #expect(c.dipProgress?.fraction == 0)
        #expect(c.observedMovements == 0)
    }

    @Test func witnessedTopCanDisplayItsEarnedCountThroughBriefElbowLoss() {
        var c = bottom()
        feed(&c, 0.6)
        c.consume(ExerciseCounterTests.poseWithLowConfidenceElbow(0.75, degrees: 170,
            exercise: .dip, side: .left), referenceEdge: DipSupportTests.verticalRail)
        #expect(c.observedMovements == 1)
        #expect(c.dipProgress?.stage == .counted)
        #expect(c.dipProgress?.fraction == 1)
        #expect(c.dipProgress?.repNumber == 1)
    }

    @Test func selectedSideAndImageScalePreserveProgress() throws {
        for side in ArmMeasurement.Side.allCases {
            for scale in [0.5, 1.0, 1.5] {
                var c = ExerciseCounter(exercise: .dip, side: side)
                let rail = BarSegment(a: .init(x: 500 * scale, y: 480 * scale),
                                      b: .init(x: 500 * scale, y: 620 * scale))
                for (time, degrees) in [(0.0,170.0),(0.15,170),(0.3,110),(0.45,80),
                                        (0.6,80),(0.75,170),(0.9,170)] {
                    let base = DipSupportTests.pose(time, degrees: degrees, scale: scale)
                    let landmarks = base.people[0].landmarks.map { landmark in
                        let index = ArmMeasurement.Side.left.joints.firstIndex(of: landmark.joint)!
                        return Landmark(joint: side.joints[index], position: landmark.position,
                                        confidence: landmark.confidence)
                    }
                    c.consume(PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                        people: [.init(landmarks: landmarks)], backend: base.backend,
                        requestRevision: base.requestRevision), referenceEdge: rail)
                    let progress = try #require(c.dipProgress)
                    if time == 0.3 { #expect(progress.fraction > 0 && progress.fraction < 0.45) }
                    if time == 0.6 { #expect(progress.fraction == 0.5) }
                }
                #expect(c.dipProgress?.stage == .counted)
                #expect(c.dipProgress?.fraction == 1)
            }
        }
    }

    @Test func invalidReferenceTimeAndGripClearProgress() {
        var c = bottom()
        c.consume(DipSupportTests.pose(0.6), referenceEdge: nil)
        #expect(c.dipProgress?.cue == .setRail)
        #expect(c.dipProgress?.fraction == 0)
        c = bottom()
        feed(&c, 1, 80)
        #expect(c.dipProgress?.cue == .reacquireSupport)
        #expect(c.dipProgress?.fraction == 0)
        c = bottom()
        feed(&c, 0.45, 80)
        #expect(c.dipProgress?.cue == .reacquireSupport)
        c = bottom()
        c.consume(DipSupportTests.pose(0.6, shift: .init(x: 45, y: 0)), referenceEdge: DipSupportTests.verticalRail)
        #expect(c.dipProgress?.cue == .reestablishGrip)
        #expect(c.dipProgress?.fraction == 0)
        #expect(c.observedMovements == 0)
    }

    @Test func finishedResetAndOtherExerciseCannotRetainDipCompletion() {
        var c = counted()
        c.finish()
        #expect(c.dipProgress?.stage == .finished)
        #expect(c.dipProgress?.fraction == 0)
        #expect(c.dipProgress?.repNumber == 1)
        c.reset()
        #expect(c.dipProgress?.stage == .startingSupport)
        #expect(c.dipProgress?.repNumber == 1)
        #expect(ExerciseCounter().dipProgress == nil)
    }
}
