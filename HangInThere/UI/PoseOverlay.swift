import SwiftUI

struct PoseOverlay: View {
    let result: PoseResult
    var selectedSide: ArmMeasurement.Side? = nil

    var body: some View {
        Canvas { context, size in
            guard let layout = AspectFit(
                image: result.imageSize,
                viewport: ImageSize(width: size.width, height: size.height)
            ) else { return }

            for person in result.people {
                for (start, end) in Self.bones {
                    guard let a = person.landmark(start), let b = person.landmark(end) else { continue }
                    let p = layout.displayPoint(a.position)
                    let q = layout.displayPoint(b.position)
                    var path = Path()
                    path.move(to: CGPoint(x: p.x, y: p.y))
                    path.addLine(to: CGPoint(x: q.x, y: q.y))

                    let emphasized = Self.isSelectedBone(start, end, side: selectedSide)
                    context.stroke(
                        path,
                        with: .color(emphasized ? .cyan : .white.opacity(0.35)),
                        style: StrokeStyle(
                            lineWidth: emphasized ? 4 : 2,
                            lineCap: .round,
                            lineJoin: .round
                        )
                    )
                }

                for landmark in person.landmarks where landmark.isVisible() {
                    let p = layout.displayPoint(landmark.position)
                    let emphasized = Self.isSelectedJoint(landmark.joint, side: selectedSide)
                    let radius: CGFloat = emphasized ? 4.5 : 3
                    let circle = Path(
                        ellipseIn: CGRect(
                            x: p.x - radius,
                            y: p.y - radius,
                            width: radius * 2,
                            height: radius * 2
                        )
                    )
                    context.fill(
                        circle,
                        with: .color(emphasized ? .cyan : .white.opacity(0.8))
                    )
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private static func isSelectedJoint(
        _ joint: PoseJoint,
        side: ArmMeasurement.Side?
    ) -> Bool {
        guard let side else { return false }
        return side.joints.contains(joint)
    }

    private static func isSelectedBone(
        _ start: PoseJoint,
        _ end: PoseJoint,
        side: ArmMeasurement.Side?
    ) -> Bool {
        guard let side else { return false }
        let joints = side.joints
        return joints.contains(start) && joints.contains(end)
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
