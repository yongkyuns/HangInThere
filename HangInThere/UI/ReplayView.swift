import SwiftUI
import UniformTypeIdentifiers

struct ReplayView: View {
    @State private var model = ReplayModel()
    @State private var importing = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text("P0 · recorded-video pose preview")
                    .font(.subheadline).foregroundStyle(.secondary)
                ZStack {
                    Color.black
                    if let frame = model.frame {
                        Image(decorative: frame.image, scale: 1, orientation: .up)
                            .resizable().scaledToFit()
                        SkeletonOverlay(pose: frame.pose)
                    } else if model.phase == .loading {
                        ProgressView("Reading video…").tint(.white).foregroundStyle(.white)
                    } else {
                        ContentUnavailableView("Import a workout video", systemImage: "video",
                            description: Text("Choose a local MOV or MP4. Processing stays on this device."))
                            .foregroundStyle(.white)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel("Video with pose overlay")
                .accessibilityIdentifier("replayPreview")

                if let frame = model.frame {
                    Text(frame.pose.status).font(.subheadline)
                    Text(String(format: "Frame %d · source %.3f s · %d landmarks",
                                frame.index, frame.pose.timestamp, frame.pose.landmarks.count))
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        .accessibilityIdentifier("frameStatus")
                }
                if model.phase == .finished { Text("Replay complete").font(.subheadline) }
                if let error = model.errorMessage {
                    Text(error).font(.callout).foregroundStyle(.red).accessibilityIdentifier("replayError")
                }
                HStack {
                    Button("Import", systemImage: "square.and.arrow.down") { importing = true }
                        .accessibilityIdentifier("importVideo")
                    if model.phase == .playing {
                        Button("Pause", systemImage: "pause.fill") { model.pause() }
                    } else {
                        Button("Play", systemImage: "play.fill") { model.play() }.disabled(!model.canPlay)
                    }
                    Button("Restart", systemImage: "arrow.counterclockwise") { model.restart() }
                        .disabled(!model.canRestart)
                }
                .buttonStyle(.bordered)
                Text("Apple Vision 2D · analysis-paced replay\nRep counting and form validation are not implemented yet.")
                    .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            .padding()
            .navigationTitle("HangInThere")
            .navigationBarTitleDisplayMode(.inline)
            .fileImporter(isPresented: $importing, allowedContentTypes: [.movie]) { result in
                switch result {
                case .success(let url): model.load(url)
                case .failure(let error): model.importFailed(error)
                }
            }
            .onChange(of: scenePhase) { _, value in if value != .active { model.pause() } }
            .onDisappear { model.shutdown() }
        }
    }
}

private struct SkeletonOverlay: View {
    let pose: PoseObservation
    private let displayConfidence: Float = 0.3 // Display gate, not a calibrated quality threshold.

    var body: some View {
        Canvas { context, size in
            guard let fit = PoseGeometry.aspectFit(image: pose.imageSize,
                viewport: ImageSize(width: size.width, height: size.height)) else { return }
            func point(_ joint: Joint) -> CGPoint? {
                guard let landmark = pose.landmarks[joint], landmark.confidence >= displayConfidence else { return nil }
                let p = fit.map(landmark.position, from: pose.imageSize)
                return CGPoint(x: p.x, y: p.y)
            }
            var bones = Path()
            for (a, b) in Self.bones {
                guard let start = point(a), let end = point(b) else { continue }
                bones.move(to: start)
                bones.addLine(to: end)
            }
            context.stroke(bones, with: .color(.green), style: StrokeStyle(lineWidth: 3, lineCap: .round))
            for joint in Joint.allCases {
                guard let p = point(joint) else { continue }
                context.fill(Path(ellipseIn: CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6)),
                             with: .color(.yellow))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private static let bones: [(Joint, Joint)] = [
        (.nose, .neck), (.neck, .leftShoulder), (.neck, .rightShoulder),
        (.leftShoulder, .leftElbow), (.leftElbow, .leftWrist),
        (.rightShoulder, .rightElbow), (.rightElbow, .rightWrist),
        (.leftShoulder, .leftHip), (.rightShoulder, .rightHip), (.leftHip, .rightHip),
        (.leftHip, .leftKnee), (.leftKnee, .leftAnkle),
        (.rightHip, .rightKnee), (.rightKnee, .rightAnkle)
    ]
}
