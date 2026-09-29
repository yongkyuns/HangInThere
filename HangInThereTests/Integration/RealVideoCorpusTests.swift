import Foundation
import Testing
@testable import HangInThere

@Suite(.serialized)
struct RealVideoCorpusTests {
    @Test func realVideoCorpusMeetsPreReviewedTrackingAndMotionFloors() async throws {
        let manifestURL = try VideoTestSupport.fixtureResource("corpus.json")
        let manifest = try JSONDecoder().decode(
            CorpusManifest.self,
            from: Data(contentsOf: manifestURL)
        )

        for testCase in manifest.cases {
            let url = try videoURL(for: testCase)
            let reader = VideoReplayReader()
            _ = try await reader.open(url)

            var frames = 0
            var personFrames = 0
            var armFrames = 0
            var multiplePeopleFrames = 0
            var rootPoints: [Point2D] = []

            while let frame = try await reader.nextFrame() {
                #expect(
                    frame.pose.backend == "Apple Vision 2D",
                    Comment(rawValue: "\(testCase.id): production backend changed.")
                )
                #expect(
                    frame.pose.requestRevision == 1,
                    Comment(rawValue: "\(testCase.id): unexpected Vision request revision.")
                )

                frames += 1
                if !frame.pose.people.isEmpty {
                    personFrames += 1
                }
                if frame.pose.people.count > 1 {
                    multiplePeopleFrames += 1
                    #expect(
                        ArmMeasurement(pose: frame.pose, side: .left).unavailableReason?.rawValue ==
                            "multiplePeople",
                        Comment(rawValue: "\(testCase.id): left arm unexpectedly selected a person.")
                    )
                    #expect(
                        ArmMeasurement(pose: frame.pose, side: .right).unavailableReason?.rawValue ==
                            "multiplePeople",
                        Comment(rawValue: "\(testCase.id): right arm unexpectedly selected a person.")
                    )
                }
                if VideoTestSupport.hasVisibleArm(frame.pose) {
                    armFrames += 1
                }

                if let root = strongestRoot(in: frame.pose) {
                    rootPoints.append(Point2D(
                        x: root.position.x / frame.pose.imageSize.width,
                        y: root.position.y / frame.pose.imageSize.height
                    ))
                }

                if let expectedOrientation = testCase.expectation.expectedOrientation {
                    switch expectedOrientation {
                    case "portrait":
                        #expect(
                            frame.pose.imageSize.height > frame.pose.imageSize.width,
                            Comment(rawValue: "\(testCase.id): prepared portrait geometry changed.")
                        )
                    case "landscape":
                        #expect(
                            frame.pose.imageSize.width > frame.pose.imageSize.height,
                            Comment(rawValue: "\(testCase.id): prepared landscape geometry changed.")
                        )
                    default:
                        Issue.record("\(testCase.id): unsupported expected_orientation \(expectedOrientation).")
                    }
                }
            }
            await reader.close()

            let expectedFrames = try expectedFrameCount(for: testCase)
            #expect(
                frames == expectedFrames,
                Comment(rawValue: "\(testCase.id): prepared frame count changed.")
            )
            let peopleFraction = frames > 0 ? Double(personFrames) / Double(frames) : 0
            let armFraction = frames > 0 ? Double(armFrames) / Double(frames) : 0
            #expect(
                peopleFraction >= testCase.expectation.minimumPeopleFraction,
                Comment(
                    rawValue:
                        "\(testCase.id): people coverage \(peopleFraction) < pre-reviewed floor " +
                        "\(testCase.expectation.minimumPeopleFraction)."
                )
            )
            #expect(
                armFraction >= testCase.expectation.minimumAnyArmFraction,
                Comment(
                    rawValue:
                        "\(testCase.id): arm coverage \(armFraction) < pre-reviewed floor " +
                        "\(testCase.expectation.minimumAnyArmFraction)."
                )
            )

            if let minimumMultiplePeopleFrames = testCase.expectation.minimumMultiplePeopleFrames {
                #expect(
                    multiplePeopleFrames >= minimumMultiplePeopleFrames,
                    Comment(
                        rawValue:
                            "\(testCase.id): only \(multiplePeopleFrames) multi-person frames < " +
                            "pre-reviewed minimum \(minimumMultiplePeopleFrames)."
                    )
                )
            }

            if let minimumMotion = testCase.expectation.minimumRootMotionFraction {
                let motion = rootMotionSpan(rootPoints)
                #expect(
                    motion >= minimumMotion,
                    Comment(
                        rawValue:
                            "\(testCase.id): inferred root motion \(motion) < pre-reviewed floor " +
                            "\(minimumMotion); stale/static pose output is not acceptable."
                    )
                )
            }

            print(
                "[Corpus] id=\(testCase.id); tier=\(testCase.tier); frames=\(frames); " +
                "peopleFraction=\(peopleFraction); armFraction=\(armFraction); " +
                "multiPersonFrames=\(multiplePeopleFrames); rootMotion=\(rootMotionSpan(rootPoints))"
            )
        }
    }

    @Test func countQualifiedCorpusCasesUseProductionMovementCounter() async throws {
        let manifestURL = try VideoTestSupport.fixtureResource("corpus.json")
        let manifest = try JSONDecoder().decode(
            CorpusManifest.self,
            from: Data(contentsOf: manifestURL)
        )

        let cases = manifest.cases.filter { $0.tier == "count-qualified" }
        #expect(cases.count >= 2, "The diversity corpus must retain at least two reviewed count-qualified views.")

        for testCase in cases {
            guard let count = testCase.countExpectation else {
                // The original Iwakuni fixture is already independently count-qualified
                // end-to-end in VisionSmokeTests/source.json.
                #expect(testCase.id == "iwakuni-standard-rear-oblique")
                continue
            }
            let side = try #require(testCase.side)
            let edgeValues = count.barReferenceEdge
            try #require(edgeValues.count == 4)

            let reference = BarSegment(
                a: Point2D(x: edgeValues[0], y: edgeValues[1]),
                b: Point2D(x: edgeValues[2], y: edgeValues[3])
            )
            let reader = VideoReplayReader()
            _ = try await reader.open(try videoURL(for: testCase))

            var session = LiveSetSession()
            session.start(exercise: .pullUp, side: side)

            while let frame = try await reader.nextFrame() {
                session.consume(frame.pose, referenceEdge: reference)
            }
            await reader.close()
            session.finish()

            #expect(
                session.observedMovements == count.expectedObservedMovements,
                Comment(
                    rawValue:
                        "\(testCase.id): movement count \(session.observedMovements) != " +
                        "visually reviewed \(count.expectedObservedMovements)."
                )
            )
            #expect(
                session.counter.interruptedAttempts == 0,
                Comment(rawValue: "\(testCase.id): count-qualified clip had an interrupted attempt.")
            )
            if testCase.id == "fitnessscape-standard-indoor" {
                #expect(
                    session.counter.partialAttempts == 0,
                    "The leading mid-rep footage must be ignored while seeking a valid extended start."
                )
            }
            print(
                "[Corpus count] id=\(testCase.id); movements=\(session.observedMovements); " +
                "trackingCoverage=\(session.trackingCoverage ?? -1)"
            )
        }
    }

    private func videoURL(for testCase: CorpusCase) throws -> URL {
        switch testCase.sourceKind {
        case "existing_fixture":
            return try VideoTestSupport.resource("pullup-smoke.mp4")
        case "existing_source", "download":
            return try VideoTestSupport.resource("corpus/\(testCase.id).mp4")
        default:
            throw FixtureError.failed(
                "\(testCase.id): unsupported corpus source_kind \(testCase.sourceKind)."
            )
        }
    }

    private func expectedFrameCount(for testCase: CorpusCase) throws -> Int {
        if let recipe = testCase.recipe {
            return recipe.expectedFrameCount
        }
        if testCase.id == "iwakuni-standard-rear-oblique" {
            return 40
        }
        throw FixtureError.failed("\(testCase.id): no expected frame count.")
    }

    private func strongestRoot(in pose: PoseResult) -> Landmark? {
        pose.people.compactMap { person in
            person.landmark(.root, minimumConfidence: 0.2)
        }.max { $0.confidence < $1.confidence }
    }

    private func rootMotionSpan(_ points: [Point2D]) -> Double {
        guard let first = points.first else { return 0 }
        var minX = first.x
        var maxX = first.x
        var minY = first.y
        var maxY = first.y
        for point in points.dropFirst() {
            minX = min(minX, point.x)
            maxX = max(maxX, point.x)
            minY = min(minY, point.y)
            maxY = max(maxY, point.y)
        }
        return hypot(maxX - minX, maxY - minY)
    }
}

private struct CorpusManifest: Decodable {
    let cases: [CorpusCase]
}

private struct CorpusCase: Decodable {
    struct Recipe: Decodable {
        let expectedFrameCount: Int

        enum CodingKeys: String, CodingKey {
            case expectedFrameCount = "expected_frame_count"
        }
    }

    struct Expectation: Decodable {
        let minimumPeopleFraction: Double
        let minimumAnyArmFraction: Double
        let minimumRootMotionFraction: Double?
        let minimumMultiplePeopleFrames: Int?
        let expectedOrientation: String?

        enum CodingKeys: String, CodingKey {
            case minimumPeopleFraction = "minimum_people_fraction"
            case minimumAnyArmFraction = "minimum_any_arm_fraction"
            case minimumRootMotionFraction = "minimum_root_motion_fraction"
            case minimumMultiplePeopleFrames = "minimum_multiple_people_frames"
            case expectedOrientation = "expected_orientation"
        }
    }

    struct CountExpectation: Decodable {
        let barReferenceEdge: [Double]
        let expectedObservedMovements: Int

        enum CodingKeys: String, CodingKey {
            case barReferenceEdge = "bar_reference_edge"
            case expectedObservedMovements = "expected_observed_movements"
        }
    }

    let id: String
    let tier: String
    let sourceKind: String
    let side: ArmMeasurement.Side?
    let recipe: Recipe?
    let expectation: Expectation
    let countExpectation: CountExpectation?

    enum CodingKeys: String, CodingKey {
        case id, tier, side, recipe, expectation
        case sourceKind = "source_kind"
        case countExpectation = "count_expectation"
    }
}
