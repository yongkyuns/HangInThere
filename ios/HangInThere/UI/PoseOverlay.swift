import SwiftUI
import WorkoutCore

/// Display-only easing. Missing points disappear immediately; gaps, source
/// changes, crowds and large jumps never borrow an earlier athlete's geometry.
struct PoseOverlaySmoothing {
    private(set) var pose: PoseResult?

    mutating func observe(_ input: PoseResult, enabled: Bool = true) {
        guard enabled, let old = pose, input.people.count == 1, old.people.count == 1,
              old.imageSize == input.imageSize, old.backend == input.backend,
              old.requestRevision == input.requestRevision else { pose = input; return }
        let dt = input.timestamp.elapsedSeconds(since: old.timestamp)
        guard dt > 0, dt <= 0.35 else { pose = input; return }
        let blend = 1 - exp(-dt / 0.055)
        let maximumStep = min(input.imageSize.width, input.imageSize.height) * 0.12
        let prior = old.people[0]
        let points = input.people[0].landmarks.map { current in
            guard current.isVisible(), let previous = prior.landmark(current.joint),
                  hypot(current.position.x - previous.position.x, current.position.y - previous.position.y) < maximumStep else { return current }
            return Landmark(joint: current.joint, position: Point2D(
                x: previous.position.x + (current.position.x - previous.position.x) * blend,
                y: previous.position.y + (current.position.y - previous.position.y) * blend), confidence: current.confidence)
        }
        pose = PoseResult(timestamp: input.timestamp, imageSize: input.imageSize,
            people: [PoseObservation(landmarks: points)], backend: input.backend, requestRevision: input.requestRevision)
    }
}

/// One visual language for every exercise. Raw pose and counter inputs are never
/// changed. Working arms use the existing relative phase-load estimate.
struct PoseOverlay: View {
    let result: PoseResult
    var selectedSide: ArmMeasurement.Side? = nil
    var usesBothArms = false
    var effort: Double? = nil
    var completedReps = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var smoothing = PoseOverlaySmoothing()
    @State private var pulseStarted: Date?

    private struct FrameKey: Equatable {
        let time: PresentationTime
        let size: ImageSize
        let backend: String
        let revision: Int
        let people: [PoseObservation]
    }
    private var frameKey: FrameKey {
        FrameKey(time: result.timestamp, size: result.imageSize, backend: result.backend, revision: result.requestRevision, people: result.people)
    }
    private var trackedSides: Set<ArmMeasurement.Side> {
        guard result.people.count == 1 else { return [] }
        if usesBothArms {
            return Set(ArmMeasurement.Side.allCases.filter { ArmMeasurement(pose: result, side: $0).estimate != nil })
        }
        return selectedSide.map { [$0] } ?? []
    }
    private var effortColor: Color {
        PoseOverlayEffort.color(result.people.count == 1 && !trackedSides.isEmpty ? effort : nil)
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: pulseStarted == nil || reduceMotion)) { timeline in
            Canvas { context, size in
                let display = reduceMotion ? result : smoothing.pose ?? result
                guard let layout = AspectFit(image: display.imageSize,
                    viewport: ImageSize(width: size.width, height: size.height)) else { return }
                let base = max(2, min(display.imageSize.width, display.imageSize.height) * layout.scale * 0.012)
                let elapsed = pulseStarted.map { timeline.date.timeIntervalSince($0) } ?? 1
                let pulse = reduceMotion ? 0 : max(0, 1 - elapsed / 0.42)
                for person in display.people {
                    draw(person, context: &context, layout: layout, base: base,
                        imageSize: display.imageSize, sides: trackedSides, pulse: pulse)
                }
            }
        }
        .onChange(of: frameKey, initial: true) { old, new in
            let gap = new.time.elapsedSeconds(since: old.time)
            if old.backend != new.backend || old.revision != new.revision || old.size != new.size ||
                new.people.count != 1 || !gap.isFinite || gap <= 0 || gap > 0.35 { pulseStarted = nil }
            smoothing.observe(result, enabled: !reduceMotion)
        }
        .onChange(of: completedReps) { old, new in
            pulseStarted = !reduceMotion && new == old + 1 ? Date() : nil
        }
        .onChange(of: reduceMotion) { _, enabled in if enabled { pulseStarted = nil } }
        .task(id: pulseStarted) {
            guard pulseStarted != nil else { return }
            do { try await Task.sleep(for: .milliseconds(450)); pulseStarted = nil } catch { }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func draw(_ person: PoseObservation, context: inout GraphicsContext, layout: AspectFit,
                      base: CGFloat, imageSize: ImageSize, sides: Set<ArmMeasurement.Side>, pulse: Double) {
        func landmark(_ joint: PoseJoint) -> Landmark? {
            person.landmark(joint).flatMap { point in
                point.position.x >= 0 && point.position.x <= imageSize.width &&
                    point.position.y >= 0 && point.position.y <= imageSize.height ? point : nil
            }
        }
        func screen(_ point: Landmark) -> CGPoint {
            let p = layout.displayPoint(point.position)
            return CGPoint(x: p.x, y: p.y)
        }
        func clarity(_ confidence: Double) -> Double { 0.2 + 0.8 * min(1, max(0, (confidence - 0.3) / 0.45)) }
        let torso = [PoseJoint.leftShoulder, .rightShoulder, .rightHip, .leftHip].compactMap(landmark)
        if torso.count == 4 {
            var path = Path(); path.move(to: screen(torso[0]))
            torso.dropFirst().forEach { path.addLine(to: screen($0)) }; path.closeSubpath()
            context.fill(path, with: .color(Color.mint.opacity(0.055 * clarity(torso.map(\.confidence).min() ?? 0))))
        }
        for active in [false, true] {
            for (a, b, taper) in Self.bones {
                let working = sides.contains { $0.joints.contains(a) && $0.joints.contains(b) }
                guard working == active, let from = landmark(a), let to = landmark(b) else { continue }
                let p = screen(from), q = screen(to)
                let length = hypot(q.x - p.x, q.y - p.y)
                guard length > 1 else { continue }
                let radius = min(base * 2.1, max(base * 0.7, length * 0.075)) * (working ? 1 : 0.68)
                let path = Self.band(p, q, radius, radius * taper)
                let opacity = clarity(min(from.confidence, to.confidence))
                let color = working ? effortColor : Color(red: 0.65, green: 0.82, blue: 0.76)
                context.fill(path, with: .linearGradient(Gradient(colors: [color.opacity((working ? 0.4 : 0.13) * opacity),
                    color.opacity((working ? 0.66 : 0.23) * opacity)]), startPoint: p, endPoint: q))
                context.stroke(path, with: .color(color.opacity((working ? 0.65 : 0.2) * opacity)), lineWidth: 0.7)
            }
        }
        for joint in Self.joints {
            guard let point = landmark(joint) else { continue }
            let p = screen(point)
            let working = sides.contains { $0.joints.contains(joint) }
            let radius = base * (working ? 0.68 : 0.42)
            let color = working ? effortColor : Color(red: 0.65, green: 0.82, blue: 0.76)
            let opacity = clarity(point.confidence)
            if working && pulse > 0 {
                let halo = radius + (1 - pulse) * base * 3.5
                context.stroke(Path(ellipseIn: CGRect(x: p.x - halo, y: p.y - halo, width: halo * 2, height: halo * 2)),
                    with: .color(Color.mint.opacity(pulse * 0.65 * opacity)), lineWidth: 1.5)
            }
            context.fill(Path(ellipseIn: CGRect(x: p.x - radius, y: p.y - radius, width: radius * 2, height: radius * 2)),
                with: .color(color.opacity((working ? 0.95 : 0.45) * opacity)))
            if working {
                context.fill(Path(ellipseIn: CGRect(x: p.x - radius * 0.34, y: p.y - radius * 0.34,
                    width: radius * 0.68, height: radius * 0.68)), with: .color(.white.opacity(opacity * 0.8)))
            }
        }
    }

    private static func band(_ a: CGPoint, _ b: CGPoint, _ startRadius: CGFloat, _ endRadius: CGFloat) -> Path {
        let angle = atan2(b.y - a.y, b.x - a.x)
        let nx = -sin(angle), ny = cos(angle)
        var path = Path()
        path.move(to: CGPoint(x: a.x + nx * startRadius, y: a.y + ny * startRadius))
        path.addLine(to: CGPoint(x: b.x + nx * endRadius, y: b.y + ny * endRadius))
        path.addArc(center: b, radius: endRadius, startAngle: .radians(angle + .pi / 2), endAngle: .radians(angle - .pi / 2), clockwise: true)
        path.addLine(to: CGPoint(x: a.x - nx * startRadius, y: a.y - ny * startRadius))
        path.addArc(center: a, radius: startRadius, startAngle: .radians(angle - .pi / 2), endAngle: .radians(angle - 3 * .pi / 2), clockwise: true)
        path.closeSubpath()
        return path
    }
    private static let bones: [(PoseJoint, PoseJoint, CGFloat)] = [
        (.leftShoulder, .rightShoulder, 1), (.leftShoulder, .leftHip, 0.8),
        (.rightShoulder, .rightHip, 0.8), (.leftHip, .rightHip, 1),
        (.leftHip, .leftKnee, 0.8), (.leftKnee, .leftAnkle, 0.65),
        (.rightHip, .rightKnee, 0.8), (.rightKnee, .rightAnkle, 0.65),
        (.leftShoulder, .leftElbow, 0.85), (.leftElbow, .leftWrist, 0.65),
        (.rightShoulder, .rightElbow, 0.85), (.rightElbow, .rightWrist, 0.65)
    ]
    private static let joints: [PoseJoint] = [.leftShoulder, .rightShoulder, .leftElbow, .rightElbow, .leftWrist, .rightWrist,
        .leftHip, .rightHip, .leftKnee, .rightKnee, .leftAnkle, .rightAnkle]
}

/// Relative phase-load display only; never measured force or personal fatigue.
/// Unknown or stale evidence stays neutral instead of borrowing a previous hue.
enum PoseOverlayEffort {
    static func rate(activity: WorkoutActivity?, at seconds: Double, observedAt: Double?) -> Double? {
        guard seconds.isFinite, let observedAt, observedAt.isFinite,
              seconds >= observedAt, seconds - observedAt <= 0.35, let current = activity?.phases.last,
              current.observedSeconds.isFinite, current.observedSeconds > 0,
              current.endedAt.isFinite, observedAt >= current.endedAt else { return nil }
        return WorkoutSetPresentationBridge().weight(phase: current.phase)?.doubleValue
    }
    static func pushUpRate(phase: String?, setStarted: Bool, usable: Bool) -> Double? {
        guard setStarted, usable else { return nil }
        let observedPhase: String
        switch phase {
        case "ready": observedPhase = "support"
        case "lowering": observedPhase = "lowering"
        case "lowered": observedPhase = "bottomHold" // Bent-arm hold and press share the ×1 weight.
        default: return nil
        }
        return WorkoutSetPresentationBridge().weight(phase: observedPhase)?.doubleValue
    }
    static func color(_ rate: Double?) -> Color {
        guard let rate, rate.isFinite else { return Color(red: 0.56, green: 0.68, blue: 0.65) }
        let t = min(1,max(0,rate))
        return t < 0.5 ? Color(red: 0.35+1.16*t,green: 0.77-0.08*t,blue: 0.65-0.56*t) :
            Color(red: 0.93+0.10*(t-0.5),green: 0.73-0.60*(t-0.5),blue: 0.37+0.06*(t-0.5))
    }
}
