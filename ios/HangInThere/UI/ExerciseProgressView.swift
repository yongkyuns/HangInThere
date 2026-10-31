import SwiftUI

// Both live and replay use the same counter snapshots and visual treatment.
struct ExerciseProgressView: View {
    let counter: ExerciseCounter
    var bodyDipSnapshot: BodyDipWorkoutSnapshot? = nil
    var bodyPullUpSnapshot: BodyPullUpWorkoutSnapshot? = nil
    var body: some View {
        if let bodyPullUpSnapshot {
            BodyPullUpProgressView(snapshot: bodyPullUpSnapshot)
        } else if let bodyDipSnapshot {
            BodyDipProgressView(snapshot: bodyDipSnapshot)
        } else if let progress = counter.pullUpProgress {
            PullUpProgressView(progress: progress)
        } else if let progress = counter.dipProgress {
            DipProgressView(progress: progress)
        }
    }
}

struct BodyPullUpProgressView: View {
    let snapshot: BodyPullUpWorkoutSnapshot
    private var motion: BodyPullUpWorkoutSnapshot.Motion { snapshot.motion }
    private var counted: Bool { motion.event?.outcome == "imageCycle" }
    private var paused: Bool {
        !motion.observationUsable || (motion.phase != "idle" && motion.targetReached != nil && motion.targetProgress == nil)
    }
    private var cue: String {
        if paused {
            return motion.reason == "cameraUnavailable" ? "Keep phone still · checking camera" : "Show both arms and hands clearly"
        }
        switch motion.phase {
        case "ready": return "Ready · pull up"
        case "outbound": return motion.progress > 0.5 ? "Lower to your starting position" : "Keep pulling"
        case "returning": return "Lower to your starting position"
        default: return "Show your starting hang with steady hands"
        }
    }
    var body: some View {
        RepProgressView(title: "Pull-up \(motion.cycles + (counted ? 0 : 1))", cue: counted ? "Rep counted" : cue,
            fraction: motion.targetProgress ?? (motion.targetReached == nil ? targetFraction(motion.progress) : 0), isStarting: motion.phase == "idle", isCounted: counted,
            isPaused: paused,
            isFinished: snapshot.endReason != nil, trackedArmCount: motion.degrees.count, targetReached: motion.targetReached ?? (motion.phase == "returning"), identifier: "bodyPullUpProgress")
    }
}

struct BodyDipProgressView: View {
    let snapshot: BodyDipWorkoutSnapshot
    private var motion: BodyDipWorkoutSnapshot.Motion { snapshot.motion }
    private var starting: Bool { motion.phase == "seekingTop" }
    private var counted: Bool { motion.event?.outcome == "imageCycle" }
    private var paused: Bool {
        !motion.observationUsable || (!starting && motion.targetReached != nil && motion.targetProgress == nil)
    }
    private var cue: String {
        if paused {
            return motion.reason == "cameraUnavailable" ? "Keep phone still · checking camera" : "Show both arms and hands clearly"
        }
        switch motion.phase {
        case "ready": return "Ready · lower"
        case "outbound": return motion.progress > 0.5 ? "Push back to your top support" : "Lower under control"
        case "returning": return "Push back to your top support"
        default: return "Hold extended top support with steady hands"
        }
    }
    var body: some View {
        RepProgressView(title: starting ? "Find your top support" : "Dip \(motion.cycles + (counted ? 0 : 1))",
            cue: counted ? "Dip counted" : cue, fraction: motion.targetProgress ?? (motion.targetReached == nil ? targetFraction(motion.progress) : 0),
            isStarting: starting, isCounted: counted,
            isPaused: paused,
            isFinished: snapshot.endReason != nil, trackedArmCount: motion.elbowDegrees.compactMap { $0 }.count,
            targetReached: motion.targetReached ?? (motion.phase == "returning"),
            outwardCue: "Lower", returnCue: "Press up", identifier: "bodyDipProgress")
    }
}

struct BilateralDipProgressView: View {
    let snapshot: DipWorkoutSnapshot
    private var motion: DipWorkoutSnapshot.Motion { snapshot.motion }
    private var starting: Bool { motion.phase == "seekingTop" }
    private var paused: Bool { !motion.hasCurrentObservation }
    private var cue: String {
        if let guidance = snapshot.observationGuidance { return guidance }
        if paused { return "Keep both hands and arms visible" }
        if starting { return "Hold your top support" }
        return motion.phase == "returning" ? "Return to your top support" :
            (motion.phase == "ready" ? "Ready · lower" : "Keep lowering")
    }
    var body: some View {
        RepProgressView(title: starting ? "Find your top support" : "Dip \(motion.imageCycles + (motion.event?.outcome == "imageCycle" ? 0 : 1))",
            cue: cue, fraction: targetFraction(motion.progress),
            isStarting: starting, isCounted: motion.event?.outcome == "imageCycle",
            isPaused: paused, isFinished: snapshot.endReason != nil,
            trackedArmCount: 2, targetReached: motion.phase == "returning",
            outwardCue: "Lower", returnCue: "Press up", identifier: "dipProgress")
    }
}

struct PullUpProgressView: View {
    let progress: PullUpProgress

    private var isStarting: Bool { progress.stage == .startingHang }
    private var isCounted: Bool { progress.stage == .counted }
    private var title: String {
        switch progress.stage {
        case .paused: "Tracking paused"
        case .startingHang: "Find your starting hang"
        case .finished: "Set finished"
        default: "Pull-up \(progress.repNumber)"
        }
    }
    private var cue: String {
        switch progress.cue {
        case .hang: "Extend your arms and hold"
        case .pull: "Ready · pull up"
        case .pullHigher: "Pull higher"
        case .finishPull: "Keep pulling"
        case .hold: "Hold here"
        case .lower: "Lower to reset for the next rep"
        case .showArms: "Keep your arms visible"
        case .reacquireHang: "Hold a new extended hang"
        case .setBar: "Set the bar reference"
        case .finished: "\(progress.repNumber) counted"
        }
    }

    var body: some View {
        RepProgressView(title: title, cue: cue,
            fraction: isStarting ? 0 : isCounted ? 1 - progress.resetFraction : normalizedProgress(progress.fraction / 0.9),
            isStarting: isStarting, isCounted: isCounted,
            isPaused: progress.stage == .paused, isFinished: progress.stage == .finished,
            trackedArmCount: progress.trackedArmCount,
            targetReached: isCounted, identifier: "pullUpProgress")
    }
}

struct DipProgressView: View {
    let progress: DipProgress
    private var title: String {
        switch progress.stage {
        case .startingSupport: "Find your top support"
        case .paused: "Tracking paused"
        case .finished: "Set finished"
        default: "Dip \(progress.repNumber)"
        }
    }
    private var cue: String {
        switch progress.cue {
        case .support: "Hold your top support"
        case .lower: "Ready · lower"
        case .lowerFurther: "Lower further"
        case .bendArms: "Keep lowering"
        case .rise: "Push back up"
        case .extendArms: "Extend your arms at the top"
        case .hold: progress.stage == .holdingBottom ? "Hold at depth" : "Hold at the top"
        case .readyNext: "Ready for the next dip"
        case .showArms: "Keep your shoulder and grip visible"
        case .reacquireSupport: "Hold a new top support"
        case .reestablishGrip: "Re-establish your grip"
        case .setRail: "Set the grip rail reference"
        case .finished: "\(progress.repNumber) counted"
        }
    }
    var body: some View {
        RepProgressView(title: title, cue: cue, fraction: targetFraction(progress.fraction),
            isStarting: progress.stage == .startingSupport, isCounted: progress.stage == .counted,
            isPaused: progress.stage == .paused, isFinished: progress.stage == .finished,
            trackedArmCount: progress.trackedArmCount, targetReached: [.rising, .holdingTop, .counted].contains(progress.stage),
            outwardCue: "Lower", returnCue: "Press up", identifier: "dipProgress")
    }
}

// Compatibility for older snapshots that exposed cycle progress only. Live body
// owners now supply targetProgress directly from their current measured geometry.
private func targetFraction(_ cycle: Double) -> Double {
    guard cycle.isFinite else { return 0 }
    return normalizedProgress(cycle < 0.5 ? cycle / 0.45 : (0.95 - cycle) / 0.45)
}

// A compact position gauge at the video edge, independent of completed-rep count.
private struct RepProgressView: View {
    let title: String
    let cue: String
    let fraction: Double
    let isStarting: Bool
    let isCounted: Bool
    let isPaused: Bool
    let isFinished: Bool
    let trackedArmCount: Int
    var targetReached = false
    var outwardCue = "Pull up"
    var returnCue = "Lower"
    let identifier: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var value: Double { isStarting || isPaused || isFinished ? 0 : normalizedProgress(fraction) }
    private var reached: Bool { targetReached && !isPaused && !isStarting && !isFinished }
    private var color: Color { isPaused ? .orange : reached || isCounted ? .mint : .cyan }
    private var prompt: String {
        if isPaused { return "Paused" }
        if isStarting { return "Get ready" }
        if isCounted { return "Counted" }
        if isFinished { return "Finished" }
        if reached { return returnCue }
        return value >= 0.999 ? "Hold" : outwardCue
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(prompt).font(.system(size: 17, weight: .bold, design: .rounded))
                .foregroundStyle(color).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 9) {
                GeometryReader { geometry in
                    ZStack(alignment: .bottom) {
                        Capsule().fill(.white.opacity(0.16))
                        Capsule().fill(color).frame(height: geometry.size.height * value)
                    }
                    .overlay(alignment: .top) {
                        RoundedRectangle(cornerRadius: 2).fill(reached ? Color.mint : Color.white)
                            .frame(width: 30, height: 3).offset(y: -1)
                    }
                }.frame(width: 20, height: 136)
                    .animation(reduceMotion || isPaused ? nil : .linear(duration: 0.08), value: value)
                VStack(alignment: .leading) {
                    if reached {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.mint).font(.title3)
                    } else { Text("Target").font(.caption.weight(.semibold)) }
                    Spacer()
                    Text("Start").font(.caption).foregroundStyle(.white.opacity(0.7))
                }.frame(height: 136)
            }
            if isPaused { Image(systemName: "pause.fill").foregroundStyle(.orange) }
        }
        .foregroundStyle(.white).padding(12).frame(width: 112, alignment: .leading)
        .background(.black.opacity(0.66), in: RoundedRectangle(cornerRadius: 16))
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(isPaused || isStarting ? cue :
            isCounted ? "Rep counted" : "\(Int(value * 100)) percent of movement target. \(reached ? returnCue : cue)\(trackedArmCount == 1 ? ". One arm visible" : "")")
        .accessibilityIdentifier(identifier)
    }
}

/// Reuses the same measured endpoint progress as the vertical rep indicator.
/// Unknown/currently unusable geometry has no color progression.
func poseOverlayProgress(counter: ExerciseCounter, bodyDip: BodyDipWorkoutSnapshot? = nil,
                         bodyPullUp: BodyPullUpWorkoutSnapshot? = nil, bilateralDip: DipWorkoutSnapshot? = nil) -> Double? {
    if let motion = bodyPullUp?.motion {
        guard motion.observationUsable, motion.sourceAccepted else { return nil }
        return motion.targetProgress ?? (motion.targetReached == nil ? targetFraction(motion.progress) : nil)
    }
    if let motion = bodyDip?.motion {
        guard motion.observationUsable, motion.sourceAccepted else { return nil }
        return motion.targetProgress ?? (motion.targetReached == nil ? targetFraction(motion.progress) : nil)
    }
    if let motion = bilateralDip?.motion {
        return motion.hasCurrentObservation ? targetFraction(motion.progress) : nil
    }
    if let progress = counter.pullUpProgress {
        guard ![PullUpProgress.Stage.startingHang, .paused, .finished].contains(progress.stage) else { return nil }
        return progress.stage == .counted ? 1 - progress.resetFraction : normalizedProgress(progress.fraction / 0.9)
    }
    if let progress = counter.dipProgress {
        guard ![DipProgress.Stage.startingSupport, .paused, .finished].contains(progress.stage) else { return nil }
        return targetFraction(progress.fraction)
    }
    return nil
}
