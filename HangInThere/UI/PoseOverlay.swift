import SwiftUI

struct PoseOverlay: View {
    let result: PoseResult

    var body: some View {
        Canvas { context, size in
            guard let layout = AspectFit(image: result.imageSize,
                                         viewport: ImageSize(width: size.width, height: size.height)) else { return }
            for person in result.people {
                var bones = Path()
                for (start, end) in Self.bones {
                    guard let a = person.landmark(start), let b = person.landmark(end) else { continue }
                    let p = layout.displayPoint(a.position), q = layout.displayPoint(b.position)
                    bones.move(to: CGPoint(x: p.x, y: p.y))
                    bones.addLine(to: CGPoint(x: q.x, y: q.y))
                }
                context.stroke(bones, with: .color(.cyan), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                for landmark in person.landmarks where landmark.isVisible() {
                    let p = layout.displayPoint(landmark.position)
                    let circle = Path(ellipseIn: CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6))
                    context.fill(circle, with: .color(.white))
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private static let bones: [(PoseJoint, PoseJoint)] = [
        (.nose, .neck), (.neck, .leftShoulder), (.neck, .rightShoulder),
        (.leftShoulder, .leftElbow), (.leftElbow, .leftWrist),
        (.rightShoulder, .rightElbow), (.rightElbow, .rightWrist),
        (.neck, .root), (.root, .leftHip), (.root, .rightHip),
        (.leftHip, .leftKnee), (.leftKnee, .leftAnkle),
        (.rightHip, .rightKnee), (.rightKnee, .rightAnkle)
    ]
}