// Setup parity reference: compiles unchanged production Swift policies.
// Inputs are original analytic poses and observed contour segments, never private video.
import Foundation

private struct Attitude: Codable {
    let x: Double, y: Double, z: Double, w: Double
    var quaternion: PhoneOrientationStability.Quaternion? { .init(x: x, y: y, z: z, w: w) }
}
private struct Translation: Codable {
    let dxPixels: Double, dyPixels: Double
    var patch: StaticSceneStability.PatchTranslation { .init(dxPixels: dxPixels, dyPixels: dyPixels) }
}
private struct Step: Codable {
    let pose: PoseResult
    let candidates: [[BarLineCandidate]]
    let attitude: Attitude?
    let translations: [Translation]
    let globalScaleFraction: Double?
    let eligible: Bool
    let paused: Bool
}
private struct Case: Codable {
    let id: String
    let exercise: ExerciseCounter.Exercise
    let side: ArmMeasurement.Side
    let expectedAcquired: Bool
    let expectedStarted: Bool
    let steps: [Step]
}
private struct Snapshot: Encodable {
    let exercise: ExerciseCounter.Exercise?
    let apparatusCandidate: ExerciseCounter.Exercise?
    let identityLocked: Bool
    let acquisitionState: String
    let referenceEdge: BarSegment?
    let role: String?
    let countdownState: String
    let countdownSeconds: Int?
    let began: Bool
    let phoneState: String
    let phoneDelta: Double?
    let sceneState: String
    let movementKind: String?
    let shiftFraction: Double?
    let scaleFraction: Double?
    let consensusPatches: Int
    let scaleAvailable: Bool
}
private struct Result: Encodable { let input: Case; let snapshots: [Snapshot] }
private struct Region: Codable {
    let minX: Double, minY: Double, maxX: Double, maxY: Double
    var value: BarRegion { .init(.init(x: minX, y: minY), .init(x: maxX, y: maxY)) }
}
private struct GeometryCase: Codable {
    let id: String
    let contours: [[Point2D]]
    let region: Region
    let size: ImageSize
}
private struct GeometryResult: Encodable {
    let input: GeometryCase
    let lines: [BarLineCandidate]?
    let pairs: [BarCandidate]?
    let lineError: String?
    let pairError: String?
}
private struct Report: Encodable {
    let scope = "production Swift setup policies; original analytic input; per-frame functional parity"
    let cases: [Result]
    let geometries: [GeometryResult]
}
@main private enum SetupOracle {
    static func state(_ state: AutomaticPullUpBar.State) -> String {
        switch state { case .lookingForGrip: "lookingForGrip"; case .findingEdge: "findingEdge";
            case .ambiguous: "ambiguous"; case .holding: "holding"; case .locked: "locked" }
    }
    static func run(_ input: Case) -> Result {
        var identity = AutomaticExerciseDetection()
        var apparatus = AutomaticApparatusReference()
        var countdown = HandsFreeSetStart()
        var phone = PhoneOrientationStability()
        var scene = StaticSceneStability()
        var bar: ConfirmedBar?
        var everAcquired = false, everStarted = false
        var snapshots: [Snapshot] = []
        for step in input.steps {
            let pose = step.pose, seconds = pose.timestamp.seconds
            let previous = identity.exercise, previousApparatus = identity.apparatusCandidate
            _ = identity.observe(pose)
            if previousApparatus != identity.apparatusCandidate || (previous != nil && identity.exercise == nil) {
                apparatus.reset(); countdown.reset(); phone.reset(); scene.reset(); bar = nil
            }
            var acquired = false
            if bar == nil, identity.apparatusCandidate == input.exercise, step.attitude?.quaternion != nil,
               !step.candidates.isEmpty {
                if let candidate = apparatus.observe(pose: pose, exercise: input.exercise, side: input.side, candidates: step.candidates),
                   phone.calibrate(step.attitude?.quaternion, timestamp: seconds), scene.calibrate(imageSize: pose.imageSize) {
                    bar = candidate; acquired = true; everAcquired = true
                }
            }
            if bar != nil {
                _ = phone.observe(step.attitude?.quaternion, timestamp: seconds)
                if !acquired { _ = scene.observe(translations: step.translations.map(\.patch),
                    globalScaleFraction: step.globalScaleFraction, timestamp: seconds) }
            }
            let eligible = step.eligible && identity.exercise == input.exercise && bar != nil
                && phone.state.allowsLiveSet && scene.state.allowsLiveSet
            let began: Bool
            if input.exercise == .dip {
                began = countdown.observeDip(pose: pose, side: input.side, referenceEdge: bar?.referenceEdge,
                    eligible: eligible, paused: step.paused)
            } else {
                began = countdown.observe(timestamp: seconds,
                    eligible: eligible && bar.map { HandsFreeSetStart.isStartingPosition(pose: pose, referenceEdge: $0.referenceEdge) } == true,
                    paused: step.paused)
            }
            if began { precondition(identity.lock()); everStarted = true }
            let countdownState: String, remaining: Int?
            switch countdown.state {
            case .waiting: countdownState = "waiting"; remaining = nil
            case .countdown(let n): countdownState = "countdown"; remaining = n
            case .started: countdownState = "started"; remaining = nil
            }
            snapshots.append(Snapshot(exercise: identity.exercise, apparatusCandidate: identity.apparatusCandidate,
                identityLocked: identity.isLocked, acquisitionState: state(apparatus.state),
                referenceEdge: bar?.referenceEdge, role: bar?.role.rawValue,
                countdownState: countdownState, countdownSeconds: remaining, began: began,
                phoneState: phone.state.rawValue, phoneDelta: phone.latestDeltaDegrees,
                sceneState: scene.state.rawValue, movementKind: scene.movementKind?.rawValue,
                shiftFraction: scene.latestShiftFraction, scaleFraction: scene.latestScaleFraction,
                consensusPatches: scene.latestConsensusPatches, scaleAvailable: scene.latestScaleMeasurementAvailable))
        }
        precondition(everAcquired == input.expectedAcquired, "Acquisition ground truth mismatch: \(input.id)")
        precondition(everStarted == input.expectedStarted, "Countdown ground truth mismatch: \(input.id)")
        return Result(input: input, snapshots: snapshots)
    }
    static func geometry(_ input: GeometryCase) -> GeometryResult {
        var lines: [BarLineCandidate]?, pairs: [BarCandidate]?
        var lineError: String?, pairError: String?
        do { lines = try BarLineFitter.candidates(contours: input.contours, region: input.region.value, size: input.size) }
        catch { lineError = String(describing: error) }
        do { pairs = try BarFitter.candidates(contours: input.contours, region: input.region.value, size: input.size) }
        catch { pairError = String(describing: error) }
        return GeometryResult(input: input, lines: lines, pairs: pairs, lineError: lineError, pairError: pairError)
    }
    static func main() throws {
        let inputs = try JSONDecoder().decode([Case].self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        precondition(inputs.count >= 15)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let geometries = try JSONDecoder().decode([GeometryCase].self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2])))
        precondition(geometries.count >= 8)
        print(String(decoding: try encoder.encode(Report(cases: inputs.map(run), geometries: geometries.map(geometry))), as: UTF8.self))
    }
}
