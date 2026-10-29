import WorkoutCore

/// Actual Swift/Kotlin transport of observed rectangle geometry and source dwell.
/// These authored observations establish no athlete identity or repetition.
enum PersonInferenceRegionBridgeCheck {
    static func run() -> Int {
        let size = WorkoutCore.ImageSize(width: 1000, height: 1000)
        var checks = 0
        func check(_ condition: Bool) { precondition(condition); checks += 1 }
        let raw = PersonInferenceRegion(left: 300, top: -2, width: 300, height: 602)
        let visible = raw.clippedTo(source: size)
        check(!raw.isValidFor(source: size))
        check(visible?.left == 300 && visible?.top == 0 && visible?.width == 300 && visible?.height == 600)
        check(raw.top == -2 && raw.height == 602)
        check(PersonInferenceRegion(left: 1000, top: 0, width: 20, height: 20).clippedTo(source: size) == nil)
        check(PersonInferenceRegion(left: 0, top: 0, width: -1, height: 20).clippedTo(source: size) == nil)
        check(raw.clippedTo(source: WorkoutCore.ImageSize(width: 1000.5, height: 1000)) == nil)
        let huge = PersonInferenceRegion(left: -100, top: -100, width: Int32.max, height: Int32.max)
        let entireImage = huge.clippedTo(source: size)
        check(entireImage?.left == 0 && entireImage?.top == 0 && entireImage?.width == 1000 && entireImage?.height == 1000)

        let source = ForegroundAssociation.Source(generation: 1, imageSize: size, backend: "authored bridge rectangles", revision: 2)
        let tracker = ForegroundAssociation(initialSource: source)
        let spectator = PersonInferenceRegion(left: 20, top: 300, width: 80, height: 200)
        func observe(_ ticks: Int64, _ candidates: [PersonInferenceRegion]) -> ForegroundAssociation.Decision {
            tracker.observe(frameSource: source, time: WorkoutCore.PresentationTime(value: ticks, timescale: 1000), candidates: candidates)
        }
        for ticks in [Int64(0), 200, 400] {
            let decision = observe(ticks, [spectator, raw])
            check(decision.status == .acquiring && decision.candidateIndex == nil && decision.region == nil)
        }
        let acquired = observe(600, [spectator, raw])
        check(acquired.status == .associated && acquired.candidateIndex?.intValue == 1 && acquired.policyVersion == 2)
        check(acquired.region == visible && acquired.timestamp?.value == 600 && acquired.generation == 1)
        let other = PersonInferenceRegion(left: 300, top: -20, width: 300, height: 620)
        let competing = observe(700, [raw, other])
        check(competing.status == .ambiguous && competing.candidateIndex == nil && competing.region == nil)
        let resumed = observe(800, [raw, spectator])
        check(resumed.status == .associated && resumed.candidateIndex?.intValue == 0 && resumed.region == visible)
        let malformed = observe(900, [raw, PersonInferenceRegion(left: 0, top: 0, width: 0, height: 20)])
        check(malformed.reason == .invalidcandidate && malformed.candidateIndex == nil && malformed.region == nil)
        return checks
    }
}
