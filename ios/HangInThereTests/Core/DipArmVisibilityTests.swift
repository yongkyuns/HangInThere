import Testing
@testable import HangInThere

struct DipArmVisibilityTests {
    private func frame(_ time: Double, _ selected: Double, _ other: Double?, _ side: ArmMeasurement.Side,
                       selectedElbowVisible: Bool = true) -> PoseResult {
        let opposite: ArmMeasurement.Side = side == .left ? .right : .left
        var points = ExerciseCounterTests.pose(time, degrees: selected, exercise: .dip, side: side,
            wristX: side == .left ? 300 : 700).people[0].landmarks
        if let other {
            points += ExerciseCounterTests.pose(time, degrees: other, exercise: .dip, side: opposite,
                wristX: opposite == .left ? 300 : 700).people[0].landmarks
        }
        if !selectedElbowVisible {
            points = points.map { Landmark(joint: $0.joint, position: $0.position,
                confidence: $0.joint == side.joints[1] ? 0.1 : $0.confidence) }
        }
        return PoseResult(timestamp: PresentationTime(value: Int64((time * 1000).rounded()), timescale: 1000),
            imageSize: ImageSize(width: 1000, height: 1000), people: [PoseObservation(landmarks: points)],
            backend: "authored arm visibility fixture", requestRevision: 0)
    }
    private func rail(_ side: ArmMeasurement.Side) -> BarSegment {
        let x = side == .left ? 300.0 : 700.0
        return BarSegment(a: Point2D(x: x, y: 500), b: Point2D(x: x, y: 620))
    }
    @Test func newlyVisibleArmNeedsFreshBaselineBeforeAnyCompletedDip() {
        for side in ArmMeasurement.Side.allCases {
            for scenario in ["outbound", "return", "readyProjected", "readyExtended", "elbowOutage"] {
                var c = ExerciseCounter(exercise: .dip, side: side)
                let top = scenario == "readyProjected" ? 140.0 : 170.0
                let samples: [(Double, Double, Double?)]
                switch scenario {
                case "return": samples = [(0.3,80,nil),(0.45,80,nil),(0.6,170,80),(0.75,170,80)]
                case "readyProjected", "readyExtended":
                    samples = [(0.2,top,170),(0.35,110,170),(0.5,80,170),(0.65,top,170),(0.8,top,170)]
                default: samples = [(0.3,110,nil),(0.45,80,170),(0.6,80,170),(0.75,170,170),(0.9,170,170)]
                }
                for time in [0.0, 0.15] { c.consume(frame(time, top, nil, side), referenceEdge: rail(side)) }
                for (time, selected, other) in samples {
                    c.consume(frame(time, selected, other, side,
                        selectedElbowVisible: scenario != "elbowOutage" || time != 0.45), referenceEdge: rail(side))
                    #expect(c.observedMovements == 0, "\(side)/\(scenario) at \(time)")
                }
                for (time, angle) in [(1.2,170.0),(1.35,170),(1.5,80),(1.65,80),(1.8,170),(1.95,170)] {
                    c.consume(frame(time, angle, angle, side), referenceEdge: rail(side))
                }
                #expect(c.observedMovements == 1)
                #expect(c.phase == .ready)
            }
        }
    }
    @Test func newlyVisibleArmCannotBorrowInitialSupportDwell() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            let samples: [(Double, Double?)] = [(0,nil),(0.1,nil),(0.15,170),(0.25,170)]
            for (time, other) in samples {
                c.consume(frame(time, 170, other, side), referenceEdge: rail(side))
                #expect(c.phase == .seekingStart)
            }
            c.consume(frame(0.3, 170, 170, side), referenceEdge: rail(side))
            #expect(c.phase == .ready)
        }
    }
    @Test func witnessedArmCanBrieflyDisappearWithoutLosingBaseline() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            let samples: [(Double, Double, Double?)] = [(0,170,170),(0.15,170,170),(0.3,110,nil),
                (0.45,80,nil),(0.6,80,80),(0.75,170,nil),(0.9,170,170)]
            for (time, selected, other) in samples { c.consume(frame(time, selected, other, side), referenceEdge: rail(side)) }
            #expect(c.observedMovements == 1)
            #expect(c.interruptedAttempts == 0)
        }
    }
    @Test func firstActualBilateralReturnSeedsFreshDwellWithoutCompletingOldAttempt() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            let samples: [(Double,Double,Double?)] = [(0,170,nil),(0.15,170,nil),(0.3,80,nil),
                (0.45,80,nil),(0.6,170,170),(0.75,170,170)]
            for (time,selected,other) in samples { c.consume(frame(time,selected,other,side),referenceEdge: rail(side)) }
            #expect(c.observedMovements == 0)
            #expect(c.interruptedAttempts == 1)
            #expect(c.phase == .ready)
            for (time,angle) in [(0.9,80.0),(1.05,80),(1.2,170),(1.35,170)] {
                c.consume(frame(time,angle,angle,side),referenceEdge: rail(side))
            }
            #expect(c.observedMovements == 1)
        }
    }
    @Test func newlyVisibleBentArmMustEstablishActualUpperSupport() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            let samples: [(Double,Double,Double?)] = [(0,170,nil),(0.05,170,80),(0.2,170,80),
                (0.35,80,80),(0.5,80,80),(0.65,170,170)]
            for (time,selected,other) in samples {
                c.consume(frame(time,selected,other,side),referenceEdge: rail(side))
                #expect(c.phase == .seekingStart)
                #expect(c.observedMovements == 0)
            }
            c.consume(frame(0.8,170,170,side),referenceEdge: rail(side))
            #expect(c.phase == .ready)
            #expect(c.observedMovements == 0)
            for (time,angle) in [(0.95,80.0),(1.1,80),(1.25,170),(1.4,170)] {
                c.consume(frame(time,angle,angle,side),referenceEdge: rail(side))
            }
            #expect(c.observedMovements == 1)
        }
    }
}
