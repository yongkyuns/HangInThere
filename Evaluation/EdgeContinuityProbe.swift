import Foundation

// Experimental comparison only; production policies and thresholds are unchanged.
@main struct EdgeContinuityProbe {
    struct Row: Encodable {
        let seconds: Double
        let baselineState: String
        let baselineDiagnostic: AutomaticReferenceStability.Diagnostic?
        let probeDiagnostic: AutomaticReferenceStability.Diagnostic?
        let freshMatchingEdges: Int
        let selectionChanged: Bool
    }
    struct Report: Encodable {
        let frames: Int
        let baselineLockSeconds: Double?
        let probeLockSeconds: Double?
        let samples: [Row]
    }
    static func main() async throws {
        guard CommandLine.arguments.count == 3 else { fatalError("Usage: edge-continuity-probe VIDEO OUTPUT.json") }
        let output = URL(fileURLWithPath: CommandLine.arguments[2])
        guard !FileManager.default.fileExists(atPath: output.path) else { fatalError("Output already exists") }
        let reader = VideoReplayReader(), detector = VisionBarDetector()
        _ = try await reader.open(URL(fileURLWithPath: CommandLine.arguments[1]))
        var baseline = AutomaticPullUpBar(), continuity = AutomaticReferenceStability()
        var witness: BarSegment?, witnessHands: [Point2D] = [], witnessSize: ImageSize?
        var previousTime: PresentationTime?, baselineLock: Double?, probeLock: Double?
        var lastSample = -Double.infinity, rows: [Row] = [], frames = 0
        while let frame = try await reader.nextFrame() {
            frames += 1
            let pose = frame.pose, seconds = pose.timestamp.seconds
            guard seconds - lastSample >= AutomaticPullUpBar.samplingInterval else { continue }
            lastSample = seconds
            let grip = AutomaticPullUpBar.grip(in: pose)
            let candidates: [BarLineCandidate]
            if let grip { candidates = try await detector.detect(image: frame.image, region: grip.region) }
            else { candidates = [] }
            if let bar = baseline.observe(pose: pose, candidates: candidates) { baselineLock = bar.sourceTime.seconds }
            var matches: [BarSegment] = [], changed = false
            var diagnostic: AutomaticReferenceStability.Diagnostic?
            if probeLock == nil {
                if let grip, let upper = AutomaticPullUpBar.select(candidates: candidates, grip: grip, size: pose.imageSize) {
                    let hands = [grip.left, grip.right], short = min(pose.imageSize.width, pose.imageSize.height)
                    let gap = previousTime.map { pose.timestamp.elapsedSeconds(since: $0) }
                    // These are the existing context/time/hand gates; no cached edge is an observation.
                    if let witness, witnessSize == pose.imageSize, let gap, gap.isFinite, gap > 0,
                       gap <= AutomaticReferenceStability.maximumFrameGap,
                       zip(witnessHands, hands).allSatisfy({ hypot($0.x-$1.x, $0.y-$1.y) <= short * 0.02 }) {
                        matches = candidates.compactMap { AutomaticPullUpBar.select(candidates: [$0], grip: grip, size: pose.imageSize) }
                            .filter { AutomaticReferenceStability.sameLine(witness, $0, tolerance: short * 0.008) }
                            .sorted { a, b in
                                let da = witness.perpendicularDistance(to: a.midpoint), db = witness.perpendicularDistance(to: b.midpoint)
                                return da == db ? a.length > b.length : da < db
                            }
                    }
                    let selected = matches.first ?? upper
                    changed = selected != upper
                    if continuity.observe(edges: [selected], grips: hands, pose: pose) { probeLock = seconds }
                    diagnostic = continuity.diagnostic
                    if continuity.diagnostic.observations == 1 {
                        witness = selected; witnessHands = hands; witnessSize = pose.imageSize
                    } else if continuity.diagnostic.observations == 0 {
                        witness = nil; witnessHands = []; witnessSize = nil
                    }
                    previousTime = pose.timestamp
                } else {
                    continuity.reset(); witness = nil; witnessHands = []; witnessSize = nil; previousTime = nil
                }
            }
            rows.append(Row(seconds: seconds, baselineState: String(describing: baseline.state),
                baselineDiagnostic: baseline.referenceStabilityDiagnostic, probeDiagnostic: diagnostic,
                freshMatchingEdges: matches.count, selectionChanged: changed))
        }
        await reader.close()
        let report = Report(frames: frames, baselineLockSeconds: baselineLock, probeLockSeconds: probeLock, samples: rows)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(report).write(to: output, options: .withoutOverwriting)
        print("Frames: \(frames); baseline lock: \(String(describing: baselineLock)); continuity probe lock: \(String(describing: probeLock))")
    }
}
