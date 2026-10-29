import SwiftUI
import Charts
import AVKit
import ImageIO

struct WorkoutTempoCard: View {
    let record: WorkoutSetRecord
    private var tempo: WorkoutTempo { WorkoutTempo(record: record) }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Rep rhythm", systemImage: "waveform.path").font(.title3.bold())
            if tempo.reps.isEmpty {
                Text("Completed rep timings will appear here.").foregroundStyle(.secondary)
            } else {
                HStack(spacing: 16) {
                    Label(record.exercise == .pullUp ? "Pull" : "Lower", systemImage: "circle.fill").foregroundStyle(.teal)
                    Label(record.exercise == .pullUp ? "Lower" : "Press", systemImage: "circle.fill").foregroundStyle(.orange)
                }.font(.caption.bold())
                Chart(tempo.reps) { rep in
                    BarMark(x: .value("Rep", String(rep.ordinal)), y: .value("Seconds", rep.outward))
                        .foregroundStyle(.teal)
                        .accessibilityLabel("Rep \(rep.ordinal), \(record.exercise == .pullUp ? "pull" : "lower")")
                        .accessibilityValue(String(format: "%.1f seconds", rep.outward))
                    BarMark(x: .value("Rep", String(rep.ordinal)), y: .value("Seconds", rep.returning))
                        .foregroundStyle(.orange.opacity(0.75))
                        .accessibilityLabel("Rep \(rep.ordinal), \(record.exercise == .pullUp ? "lower" : "press")")
                        .accessibilityValue(String(format: "%.1f seconds", rep.returning))
                }.chartYAxisLabel("Seconds").frame(height: 145)
                if let change = tempo.change {
                    Label(abs(change) < 0.05 ? "Similar lifting tempo" : String(format: "Lifting phase %.0f%% %@", abs(change) * 100, change > 0 ? "longer" : "shorter"),
                          systemImage: abs(change) < 0.05 ? "equal.circle" : change > 0 ? "arrow.up.right" : "arrow.down.right")
                        .font(.headline).foregroundStyle(change > 0.05 ? Color.orange : Color.primary)
                }
                Text(tempo.comparisonNote).font(.caption).foregroundStyle(.secondary)
            }
            Text("Observed timing · not a fatigue measurement").font(.caption2).foregroundStyle(.secondary)
        }.journeyCard()
    }
}

/// Exercise-specific anatomy illustration, never an activation heat map.
struct WorkoutMuscleCard: View {
    let exercise: ExerciseCounter.Exercise
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 20) { anatomy.frame(width: 112, height: 190); explanation }
            VStack(alignment: .leading, spacing: 16) { anatomy.frame(height: 190); explanation }
        }.journeyCard()
    }
    private var explanation: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Muscles involved").font(.title3.bold())
            Text(exercise == .pullUp ? "Back & arms" : "Chest & arms").font(.headline).foregroundStyle(exercise == .pullUp ? Color.teal : Color.orange)
            Text(exercise == .pullUp ? "Lats, upper back and elbow flexors" : "Chest, triceps and front shoulders").font(.subheadline)
            Text(exercise == .pullUp ? "Illustrated back view" : "Illustrated front view").font(.caption).foregroundStyle(.secondary)
            Text("An exercise guide. Shading doesn't measure your muscle activation.").font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private var anatomy: some View {
        Canvas { context, size in
            let sx = size.width / 100, sy = size.height / 180
            func point(_ x: Double, _ y: Double) -> CGPoint { CGPoint(x: x * sx, y: y * sy) }
            func shape(_ points: [(Double, Double)], _ color: Color) {
                guard points.count > 2 else { return }
                let vertices = points.map { point($0.0, $0.1) }
                func middle(_ a: CGPoint, _ b: CGPoint) -> CGPoint { CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2) }
                var p = Path(); p.move(to: middle(vertices.last!, vertices[0]))
                for i in vertices.indices { p.addQuadCurve(to: middle(vertices[i], vertices[(i + 1) % vertices.count]), control: vertices[i]) }
                p.closeSubpath()
                context.fill(p, with: .color(color))
            }
            let base = Color.primary.opacity(0.12)
            context.fill(Path(ellipseIn: CGRect(x: 41 * sx, y: 2 * sy, width: 18 * sx, height: 22 * sy)), with: .color(base))
            // A tapered torso, relaxed arms and standing legs keep the anatomy legible at small sizes.
            shape([(45,25),(55,25),(57,30),(70,34),(67,58),(61,89),(62,105),(38,105),(39,89),(33,58),(30,34),(43,30)], base)
            for mirror in [false, true] {
                func side(_ p: [(Double, Double)]) -> [(Double, Double)] { p.map { (mirror ? 100 - $0.0 : $0.0, $0.1) } }
                shape(side([(29,34),(35,39),(31,63),(26,82),(22,99),(17,98),(19,79),(22,59),(23,39)]), base)
                shape(side([(39,104),(49,106),(47,138),(45,170),(33,170),(35,137)]), base)
                let color = exercise == .pullUp ? Color.teal : Color.orange
                if exercise == .pullUp {
                    shape(side([(34,39),(48,35),(47,54),(39,70),(36,57)]), color.opacity(0.8))
                    shape(side([(39,59),(48,56),(48,87),(41,82),(36,62)]), color.opacity(0.6))
                } else {
                    var chest = Path()
                    chest.move(to: point(mirror ? 100-35 : 35,38))
                    chest.addQuadCurve(to: point(mirror ? 100-48 : 48,36), control: point(mirror ? 100-40 : 40,32))
                    chest.addLine(to: point(mirror ? 100-48 : 48,52))
                    chest.addQuadCurve(to: point(mirror ? 100-35 : 35,47), control: point(mirror ? 100-39 : 39,56))
                    chest.closeSubpath(); context.fill(chest, with: .color(color.opacity(0.8)))
                    shape(side([(26,36),(31,35),(33,42),(27,46),(24,44)]), color.opacity(0.55))
                }
                shape(side([(25,47),(31,46),(28,61),(24,68),(22,63)]), color.opacity(0.75))
            }
        }.aspectRatio(100.0 / 180.0, contentMode: .fit)
            .accessibilityLabel(exercise == .pullUp ? "Back and upper arm muscle groups highlighted" : "Chest, shoulder and upper arm muscle groups highlighted")
    }
}

struct WorkoutVideoReview: View {
    let data: WorkoutVideoReviewData
    @State private var player: AVPlayer
    init(data: WorkoutVideoReviewData) { self.data = data; _player = State(initialValue: AVPlayer(url: data.url)) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VideoPlayer(player: player).frame(height: 330).clipShape(RoundedRectangle(cornerRadius: 20))
                WorkoutKeyframeStrip(data: data) { marker in
                    player.pause()
                    let seconds = min(marker.clipSeconds, max(0, (data.clipDuration ?? marker.clipSeconds + 1 / 30) - 1 / 30))
                    player.seek(to: CMTime(seconds: seconds, preferredTimescale: 60_000), toleranceBefore: .zero, toleranceAfter: CMTime(value: 1, timescale: 30))
                }
                if let error = data.thumbnailError { Text(error).font(.caption).foregroundStyle(.orange) }
                if let record = data.record { WorkoutTempoCard(record: record); WorkoutMuscleCard(exercise: record.exercise) }
            }.padding()
        }.navigationTitle("Set review")
            .onDisappear { player.pause() }
    }
}
private struct WorkoutKeyframeThumbnail: View {
    let url: URL?
    @State private var image: CGImage?
    var body: some View {
        ZStack {
            Color.primary.opacity(0.06)
            if let image { Image(decorative: image, scale: 1).resizable().scaledToFill() }
            else { Image(systemName: "photo").foregroundStyle(.secondary) }
        }.task(id: url) {
            image = nil
            guard let url, let bytes = await Task.detached(priority: .utility, operation: { try? Data(contentsOf: url) }).value,
                  let source = CGImageSourceCreateWithData(bytes as CFData, nil) else { return }
            image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        }
    }
}

struct WorkoutKeyframeStrip: View {
    let data: WorkoutVideoReviewData
    var onSeek: (WorkoutKeyframeMarker) -> Void
    @State private var selected: String?
    @ScaledMetric(relativeTo: .caption) private var labelHeight = 76.0
    var body: some View {
        if !data.markers.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Rep moments").font(.title3.bold())
                Text("Tap a frame to jump into the video.").font(.subheadline).foregroundStyle(.secondary)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 12) {
                        ForEach(data.markers) { marker in
                            Button {
                                selected = marker.id; onSeek(marker)
                            } label: {
                                VStack(alignment: .leading, spacing: 5) {
                                    WorkoutKeyframeThumbnail(url: data.thumbnailDirectory?.appendingPathComponent(marker.filename))
                                        .frame(width: 92, height: 132).clipped().clipShape(RoundedRectangle(cornerRadius: 12))
                                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected == marker.id ? Color.teal : Color.clear, lineWidth: 3))
                                    Text("Rep \(marker.ordinal)").font(.caption.bold())
                                    Text(marker.phase).font(.caption).foregroundStyle(.secondary)
                                }
                            }.buttonStyle(.plain).accessibilityLabel("Rep \(marker.ordinal), \(marker.phase). Seek video")
                        }
                    }.padding(3)
                }.scrollIndicators(.hidden).frame(height: 132 + labelHeight)
                if (data.record?.completedCount ?? 0) > 60 { Text("Keyframes show the first 60 reps.").font(.caption).foregroundStyle(.secondary) }
            }
        } else {
            Text("This video has no saved rep-to-video alignment. Playback is available; rep thumbnails can't be placed accurately.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}
