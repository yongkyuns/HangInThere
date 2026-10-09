import SwiftUI

// Both live and replay use the same counter snapshots and visual treatment.
struct ExerciseProgressView: View {
    let counter: ExerciseCounter
    var body: some View {
        if let progress = counter.pullUpProgress {
            PullUpProgressView(progress: progress)
        } else if let progress = counter.dipProgress {
            DipProgressView(progress: progress)
        }
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
            fraction: isStarting ? progress.resetFraction : progress.fraction,
            resetFraction: isCounted ? progress.resetFraction : nil,
            isStarting: isStarting, isCounted: isCounted,
            isPaused: progress.stage == .paused, isFinished: progress.stage == .finished,
            trackedArmCount: progress.trackedArmCount, showsMidpoint: false,
            identifier: "pullUpProgress")
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
        RepProgressView(title: title, cue: cue, fraction: progress.fraction, resetFraction: nil,
            isStarting: progress.stage == .startingSupport, isCounted: progress.stage == .counted,
            isPaused: progress.stage == .paused, isFinished: progress.stage == .finished,
            trackedArmCount: progress.trackedArmCount, showsMidpoint: true, identifier: "dipProgress")
    }
}

// Kept at the video edge so the athlete and joint overlay remain visible.
private struct RepProgressView: View {
    let title: String
    let cue: String
    let fraction: Double
    let resetFraction: Double?
    let isStarting: Bool
    let isCounted: Bool
    let isPaused: Bool
    let isFinished: Bool
    let trackedArmCount: Int
    let showsMidpoint: Bool
    let identifier: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var color: Color { isPaused ? .orange : (isCounted ? .green : .cyan) }
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                if isCounted {
                    Label("Counted", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                } else if !isStarting, !isPaused, !isFinished {
                    Text("\(Int(fraction * 100))%")
                        .monospacedDigit()
                        .foregroundStyle(color)
                }
            }
            .font(.caption.weight(.semibold))

            meter(value: fraction, tint: color, height: 8, midpoint: showsMidpoint)

            HStack {
                Text(cue)
                Spacer(minLength: 8)
                if trackedArmCount == 1 {
                    Text("1 arm visible")
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            .font(.caption.weight(.medium))

            if let resetFraction {
                meter(value: resetFraction, tint: .white.opacity(0.8), height: 3)
                    .accessibilityHidden(true)
            }
        }
        .foregroundStyle(.white)
        .padding(12)
        .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 14))
        .overlay {
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(color.opacity(0.4), lineWidth: 1)
        }
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(isCounted ? "Counted. \(cue)"
            : (isStarting || isPaused ? cue : "\(cue). \(Int(fraction * 100)) percent"))
        .accessibilityIdentifier(identifier)
    }

    private func meter(value: Double, tint: Color, height: CGFloat, midpoint: Bool = false) -> some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.18))
                Capsule().fill(tint)
                    .frame(width: geometry.size.width * normalizedProgress(value))
                if midpoint {
                    Rectangle().fill(.black.opacity(0.7))
                        .frame(width: 2)
                        .offset(x: geometry.size.width / 2 - 1)
                }
            }
        }
        .frame(height: height)
        .animation(reduceMotion ? nil : .linear(duration: 0.08), value: value)
    }
}
