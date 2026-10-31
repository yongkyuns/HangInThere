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
/// changed. Default bands show endpoint progress; optional muscles show the
/// existing relative phase-load proxy, never measured activation.
struct PoseOverlay: View {
    let result: PoseResult
    var selectedSide: ArmMeasurement.Side? = nil
    var usesBothArms = false
    var progress: Double? = nil
    var completedReps = 0
    var muscleExercise: ExerciseCounter.Exercise? = nil
    var muscleActivity: WorkoutActivity? = nil
    var musclePreview: Bool? = nil
    @AppStorage("muscleOverlayPreview") private var prefersMuscles = false
    private var muscleLoad: MuscleLoadVisual? {
        guard musclePreview ?? prefersMuscles, let muscleExercise else { return nil }
        return MuscleLoadVisual(exercise: muscleExercise,
            activity: result.people.count == 1 && !trackedSides.isEmpty ? muscleActivity : nil, at: result.timestamp.seconds)
    }
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
    private var movementColor: Color {
        let amount = normalizedProgress(progress ?? 0)
        return Color(red: 0.26 + 0.74 * amount, green: 0.91 - 0.39 * amount, blue: 0.72 - 0.34 * amount)
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
        .overlay(alignment: .trailing) {
            if let muscleLoad { MuscleLoadLegend(load: muscleLoad).padding(.trailing, 12) }
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
        .accessibilityHidden(muscleLoad == nil)
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
                if muscleLoad != nil && working { continue }
                guard working == active, let from = landmark(a), let to = landmark(b) else { continue }
                let p = screen(from), q = screen(to)
                let length = hypot(q.x - p.x, q.y - p.y)
                guard length > 1 else { continue }
                let radius = min(base * 2.1, max(base * 0.7, length * 0.075)) * (working ? 1 : 0.68)
                let path = Self.band(p, q, radius, radius * taper)
                let opacity = clarity(min(from.confidence, to.confidence))
                let color = working ? movementColor : Color(red: 0.65, green: 0.82, blue: 0.76)
                context.fill(path, with: .linearGradient(Gradient(colors: [color.opacity((working ? 0.4 : 0.13) * opacity),
                    color.opacity((working ? 0.66 : 0.23) * opacity)]), startPoint: p, endPoint: q))
                context.stroke(path, with: .color(color.opacity((working ? 0.65 : 0.2) * opacity)), lineWidth: 0.7)
            }
        }
        if let load = muscleLoad {
            drawMuscles(person, context: &context, layout: layout, imageSize: imageSize, sides: sides, load: load)
            return
        }
        for joint in Self.joints {
            guard let point = landmark(joint) else { continue }
            let p = screen(point)
            let working = sides.contains { $0.joints.contains(joint) }
            let radius = base * (working ? 0.68 : 0.42)
            let color = working ? movementColor : Color(red: 0.65, green: 0.82, blue: 0.76)
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

    private func drawMuscles(_ person: PoseObservation, context: inout GraphicsContext, layout: AspectFit,
                             imageSize: ImageSize, sides: Set<ArmMeasurement.Side>, load: MuscleLoadVisual) {
        func screen(_ joint: PoseJoint) -> CGPoint? {
            guard let point = person.landmark(joint), point.position.x >= 0, point.position.x <= imageSize.width,
                  point.position.y >= 0, point.position.y <= imageSize.height else { return nil }
            let p = layout.displayPoint(point.position); return CGPoint(x: p.x,y: p.y)
        }
        func belly(_ a: CGPoint, _ b: CGPoint, width: CGFloat, offset: CGFloat, emphasis: Double) {
            let length = hypot(b.x-a.x,b.y-a.y); guard length > 2 else { return }
            let ux = (b.x-a.x)/length, uy = (b.y-a.y)/length, nx = -uy, ny = ux
            func p(_ t: CGFloat, _ w: CGFloat) -> CGPoint {
                CGPoint(x: a.x+(b.x-a.x)*t+nx*(w+offset),y: a.y+(b.y-a.y)*t+ny*(w+offset))
            }
            var path = Path(); path.move(to: p(0.13,-width*0.15))
            path.addCurve(to: p(0.86,-width*0.12),control1: p(0.25,-width*0.85),control2: p(0.66,-width*0.8))
            path.addQuadCurve(to: p(0.88,width*0.12),control: p(0.96,0))
            path.addCurve(to: p(0.13,width*0.15),control1: p(0.65,width*0.9),control2: p(0.30,width*0.8))
            path.addQuadCurve(to: p(0.13,-width*0.15),control: p(0.07,0))
            path.closeSubpath()
            let color = load.color(emphasis: emphasis)
            context.fill(path,with: .linearGradient(Gradient(colors: [color.opacity(0.42),color.opacity(0.88),color.opacity(0.5)]),startPoint: p(0.5,-width),endPoint: p(0.5,width)))
            context.stroke(path,with: .color(color.opacity(0.85)),lineWidth: 0.8)
            var fiber = Path(); fiber.move(to: p(0.20,0)); fiber.addQuadCurve(to: p(0.82,0),control: p(0.48,width*0.12))
            context.stroke(fiber,with: .color(.white.opacity(0.25)),lineWidth: 0.7)
        }
        for side in sides {
            let joints = side.joints
            guard let shoulder = screen(joints[0]), let elbow = screen(joints[1]), let wrist = screen(joints[2]) else { continue }
            let length = hypot(elbow.x-shoulder.x,elbow.y-shoulder.y)
            // Paired schematic bellies, not a guessed front/back arm surface.
            let w = length*0.105
            belly(shoulder,elbow,width: w,offset: -w*0.48,emphasis: load.exercise == .pullUp ? 1 : 0.35)
            belly(shoulder,elbow,width: w*0.92,offset: w*0.58,emphasis: load.exercise == .pullUp ? 0.35 : 1)
            belly(shoulder,CGPoint(x: shoulder.x+(elbow.x-shoulder.x)*0.42,y: shoulder.y+(elbow.y-shoulder.y)*0.42),width: w*1.5,offset: 0,emphasis: 0.65)
            belly(elbow,wrist,width: w*0.9,offset: 0,emphasis: load.exercise == .pullUp ? 0.65 : 0.4)
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

/// Display-only exercise emphasis. Phase weights reuse the existing shared
/// demand model; these colors are not measured activation or personal fatigue.
struct MuscleLoadVisual {
    let exercise: ExerciseCounter.Exercise
    let phase: String?
    let rate: Double?

    init(exercise: ExerciseCounter.Exercise, activity: WorkoutActivity?, at seconds: Double) {
        self.exercise = exercise
        let bridge = WorkoutSetPresentationBridge()
        let current = activity?.phases.last
        if exercise != .pushUp, seconds.isFinite, let current,
           current.observedSeconds > 0, current.endedAt.isFinite,
           seconds >= current.endedAt, seconds - current.endedAt <= 0.35,
           let weight = bridge.weight(phase: current.phase)?.doubleValue {
            phase = current.phase; rate = weight
        } else { phase = nil; rate = nil }

    }
    var phaseTitle: String {
        switch phase {
        case "pulling": "Pulling"
        case "rising": "Pressing"
        case "lowering": "Lowering"
        case "hang": "Hanging"
        case "support": "Supporting"
        case "peakHold", "bottomHold", "effortHold": "Holding"
        default: "Guide only"
        }
    }
    func color(emphasis: Double = 1) -> Color {
        guard let rate else { return Color(red: 0.56, green: 0.68, blue: 0.65) }
        return Self.swatch(rate * emphasis)
    }
}

/// A labeled schematic supplies chest/back context without guessing which
/// surface of the athlete is facing the camera.
private struct MuscleLoadLegend: View {
    let load: MuscleLoadVisual
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 8) {
                bodyMap.frame(width: 43, height: 80)
                VStack(alignment: .leading, spacing: 5) {
                    Text(load.exercise == .pullUp ? "BACK + ARMS" : "CHEST + ARMS")
                        .font(.system(size: 9, weight: .bold, design: .rounded)).foregroundStyle(.white.opacity(0.65))
                    Text(load.phaseTitle).font(.system(size: 13, weight: .bold, design: .rounded))
                    HStack(spacing: 3) {
                        ForEach(0..<5) { index in
                            Capsule().fill(load.rate == nil ? Color.white.opacity(0.16) :
                                MuscleLoadVisual.swatch(Double(index) / 4)).frame(width: 10, height: 4)
                        }
                    }
                    Text(load.rate == nil ? "Muscle guide" : "Estimated load")
                        .font(.system(size: 10, weight: .medium)).foregroundStyle(.white.opacity(0.7))
                }
            }
            Text("SCHEMATIC · PREVIEW").font(.system(size: 8, weight: .semibold)).tracking(0.6).foregroundStyle(.white.opacity(0.45))
        }.padding(10).foregroundStyle(.white)
            .background(.black.opacity(0.68), in: RoundedRectangle(cornerRadius: 16))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Muscle preview. \(load.exercise == .pullUp ? "Back and elbow flexors" : "Chest and triceps"). \(load.phaseTitle). Relative phase load, not measured activation.")
    }
    private var bodyMap: some View {
        Canvas { context, size in
            let sx = size.width / 100, sy = size.height / 180
            func point(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: x * sx, y: y * sy) }
            func shape(_ vertices: [(Double, Double)], _ color: Color) {
                var path = Path(); guard let first = vertices.first, let last = vertices.last else { return }
                func mid(_ a: CGPoint, _ b: CGPoint) -> CGPoint { CGPoint(x: (a.x+b.x)/2, y: (a.y+b.y)/2) }
                path.move(to: mid(point(first.0,first.1), point(last.0,last.1)))
                for i in vertices.indices {
                    let next = vertices[(i+1) % vertices.count]
                    path.addQuadCurve(to: mid(point(vertices[i].0,vertices[i].1), point(next.0,next.1)), control: point(vertices[i].0,vertices[i].1))
                }
                path.closeSubpath(); context.fill(path, with: .color(color))
            }
            let base = Color.white.opacity(0.17)
            context.fill(Path(ellipseIn: CGRect(x: 41*sx,y: 2*sy,width: 18*sx,height: 23*sy)),with: .color(base))
            shape([(44,25),(56,25),(60,31),(73,35),(68,58),(61,92),(63,105),(37,105),(39,92),(32,58),(27,35),(40,31)],base)
            for mirror in [false,true] {
                func side(_ p: [(Double,Double)]) -> [(Double,Double)] { p.map { (mirror ? 100-$0.0 : $0.0,$0.1) } }
                shape(side([(27,35),(34,39),(30,64),(24,87),(20,100),(16,97),(19,75),(21,53),(21,39)]),base)
                shape(side([(38,104),(49,106),(47,137),(46,174),(34,174),(35,137)]),base)
                let trunk = load.color()
                if load.exercise == .pullUp {
                    shape(side([(35,37),(49,35),(47,58),(40,82),(36,66)]),trunk)
                } else {
                    shape(side([(34,37),(48,34),(48,52),(38,55),(33,47)]),trunk)
                }
                shape(side([(25,36),(31,35),(34,43),(29,48),(23,44)]),load.color(emphasis: 0.65))
                shape(side([(23,49),(30,47),(28,64),(23,70),(20,64)]),load.color())
                shape(side([(21,73),(25,72),(22,90),(18,94)]),load.color(emphasis: 0.5))
            }
        }
    }
}

private extension MuscleLoadVisual {
    static func swatch(_ level: Double) -> Color {
        // Legend uses the exact same palette as the muscles.
        let t = min(1,max(0,level))
        return t < 0.5 ? Color(red: 0.35+1.16*t,green: 0.77-0.08*t,blue: 0.65-0.56*t) :
            Color(red: 0.93+0.10*(t-0.5),green: 0.73-0.60*(t-0.5),blue: 0.37+0.06*(t-0.5))
    }
}
