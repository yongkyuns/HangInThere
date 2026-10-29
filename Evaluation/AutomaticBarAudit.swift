import Foundation

// Explicitly supplied local video only. Runs the production decoder, pose and
// contour backends and automatic policy, then counts from the start with one
// fixed reference. Output includes geometry, so keep private footage audits local.
@main struct AutomaticBarAudit {
    struct SceneMotion: Encodable, Sendable {
        let referenceSeconds: Double
        let consensusPatches: Int
        let shiftFraction: Double?
        let scaleFraction: Double?
    }
    struct SceneReference: Sendable {
        let image: StaticSceneRegistrationReference
        let seconds: Double
    }
    struct Sample: Encodable {
        let seconds: Double
        let candidates: [BarLineCandidate]
        let state: String
        let detectionError: String?
        let referenceStability: AutomaticReferenceStability.Diagnostic?
        let adjacentSceneMotion: SceneMotion?
        let dwellSceneMotion: SceneMotion?
        let adjacentRegistrationFailed: Bool
        let dwellRegistrationFailed: Bool
    }
    struct Count: Encodable {
        let side: ArmMeasurement.Side
        let movements: Int
        let events: [ExerciseCounter.Event]
    }
    struct Report: Encodable {
        let referenceEdge: BarSegment?
        let lockedAtSeconds: Double?
        let analyzedFrames: Int
        let samples: [Sample]
        let counts: [Count]
    }
    static func measureScene(reference: SceneReference?, frame: ProcessedFrame,
                             worker: VisionStaticSceneRegistrationWorker) async -> (motion: SceneMotion?, failed: Bool) {
        guard let reference else { return (nil, false) }
        do {
            let measurement = try await worker.measure(reference: reference.image, image: frame.image)
            var consensus = StaticSceneStability()
            guard consensus.calibrate(imageSize: frame.pose.imageSize) else { return (nil, true) }
            consensus.observe(translations: measurement.translations,
                globalScaleFraction: measurement.globalScaleFraction, timestamp: frame.pose.timestamp.seconds)
            return (SceneMotion(referenceSeconds: reference.seconds,
                consensusPatches: consensus.latestConsensusPatches,
                shiftFraction: consensus.latestShiftFraction,
                scaleFraction: consensus.latestScaleFraction), false)
        } catch { return (nil, true) }
    }
    static func main() async throws {
        guard CommandLine.arguments.count == 3 else {
            throw NSError(domain: "Usage: automatic-bar-audit VIDEO OUTPUT.json", code: 1)
        }
        let output = URL(fileURLWithPath: CommandLine.arguments[2])
        guard !FileManager.default.fileExists(atPath: output.path) else {
            throw NSError(domain: "Automatic bar audit output already exists", code: 1)
        }
        let reader = VideoReplayReader()
        let detector = VisionBarDetector()
        let sceneWorker = VisionStaticSceneRegistrationWorker()
        _ = try await reader.open(URL(fileURLWithPath: CommandLine.arguments[1]))
        var policy = AutomaticPullUpBar()
        var poses: [PoseResult] = []
        var samples: [Sample] = []
        var lastSample = -Double.infinity
        var previousScene: SceneReference?
        var dwellScene: SceneReference?
        var bar: ConfirmedBar?
        do {
            while let frame = try await reader.nextFrame() {
                poses.append(frame.pose)
                let seconds = frame.pose.timestamp.seconds
                guard bar == nil, seconds - lastSample >= AutomaticPullUpBar.samplingInterval else { continue }
                lastSample = seconds
                var candidates: [BarLineCandidate] = []
                var detectionError: String?
                if let grip = AutomaticPullUpBar.grip(in: frame.pose) {
                    do { candidates = try await detector.detect(image: frame.image, region: grip.region) }
                    catch { detectionError = String(describing: error) }
                }
                bar = policy.observe(pose: frame.pose, candidates: candidates)
                // Independent diagnostic only. Use actual peripheral registration
                // and the production consensus; it never changes bar qualification.
                let diagnostic = policy.referenceStabilityDiagnostic
                let adjacent = await measureScene(reference: previousScene, frame: frame, worker: sceneWorker)
                let dwell: (motion: SceneMotion?, failed: Bool)
                if diagnostic == nil { dwell = (nil, false) }
                else if let previousScene, let dwellScene, previousScene.seconds == dwellScene.seconds {
                    dwell = adjacent
                } else { dwell = await measureScene(reference: dwellScene, frame: frame, worker: sceneWorker) }
                let current = VisionStaticSceneRegistrationWorker.makeReference(image: frame.image).map {
                    SceneReference(image: $0, seconds: seconds)
                }
                // Compare against the old dwell anchor before replacing it on a
                // reset. Adjacent-only drift would hide cumulative camera motion.
                if diagnostic == nil { dwellScene = nil }
                else if diagnostic?.observations == 1 { dwellScene = current }
                previousScene = current
                samples.append(Sample(seconds: seconds, candidates: candidates, state: String(describing: policy.state),
                    detectionError: detectionError, referenceStability: policy.referenceStabilityDiagnostic,
                    adjacentSceneMotion: adjacent.motion, dwellSceneMotion: dwell.motion,
                    adjacentRegistrationFailed: adjacent.failed, dwellRegistrationFailed: dwell.failed))
            }
        } catch {
            await reader.close()
            throw error
        }
        await reader.close()
        let counts = ArmMeasurement.Side.allCases.map { side in
            var counter = ExerciseCounter(exercise: .pullUp, side: side)
            for pose in poses { counter.consume(pose, referenceEdge: bar?.referenceEdge) }
            counter.finish()
            return Count(side: side, movements: counter.observedMovements, events: counter.events)
        }
        let report = Report(referenceEdge: bar?.referenceEdge, lockedAtSeconds: bar?.sourceTime.seconds,
                            analyzedFrames: poses.count, samples: samples, counts: counts)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(report).write(to: output, options: .withoutOverwriting)
        print("Frames: \(poses.count); automatic lock: \(bar?.sourceTime.seconds.description ?? "none"); counts: \(counts.map(\.movements))")
    }
}
