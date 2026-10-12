import SwiftUI
import Charts

/// Read-only presentation: opening this view never pauses or resets the camera.
struct WorkoutReviewView: View {
    let sets: [WorkoutSetRecord]
    let currentReps: [WorkoutRepRecord]
    let storageError: String?
    @State private var selectedRep: String?
    private var reps: [WorkoutRepRecord] { currentReps.isEmpty ? sets.first?.reps ?? [] : currentReps }
    private var selected: WorkoutRepRecord? { (currentReps + sets.flatMap(\.reps)).first { $0.id == selectedRep } ?? reps.last }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                if let storageError { Text(storageError).foregroundStyle(.orange) }
                if reps.isEmpty {
                    ContentUnavailableView("Your motion, rep by rep", systemImage: "chart.xyaxis.line",
                        description: Text("Complete a pull-up or dip to see its timing and angle measurements here."))
                } else {
                    Text("Rep timing").font(.title2.bold())
                    Text("Tap a rep to inspect it. A count may be accepted before the return motion finishes.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    timingChart
                    if let selected { repDetails(selected) }
                }
                if !sets.isEmpty {
                    Text("Saved sets").font(.title2.bold())
                    ForEach(sets) { set in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(set.exercise.title) · \(set.acceptedCount) \(set.acceptedCount == 1 ? "rep" : "reps")").font(.headline)
                            Text(set.endReason == "disengaged" ? "Automatically completed" :
                                 set.endReason == "manual" ? "Manually completed" : "Interrupted · \(set.endReason)")
                                .font(.caption).foregroundStyle(.secondary)
                            ForEach(set.reps) { rep in
                                Button("Rep \(rep.ordinal) · \(seconds(rep.outboundSeconds)) outward / \(seconds(rep.returnSeconds)) return") {
                                    // History is shown below, without reconfiguring active tracking.
                                    selectedRep = rep.id
                                }.buttonStyle(.plain)
                            }
                        }.padding().frame(maxWidth: .infinity, alignment: .leading)
                            .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
                    }
                }
                Text("Angles are projected camera measurements. Counts use observed movement criteria; depth, lockout and exercise form are not verified.")
                    .font(.footnote).foregroundStyle(.secondary)
            }.padding()
        }.navigationTitle("Workout analysis")
    }
    private var timingChart: some View {
        Chart(Array(reps.suffix(12))) { rep in
            if let outward = rep.outboundSeconds {
                BarMark(xStart: .value("Seconds", 0), xEnd: .value("Seconds", outward),
                        y: .value("Rep", "\(rep.ordinal)"), height: .fixed(18))
                    .foregroundStyle(.cyan).accessibilityLabel("Rep \(rep.ordinal) outward")
                    .accessibilityValue(seconds(outward))
                if let back = rep.returnSeconds {
                    BarMark(xStart: .value("Seconds", outward), xEnd: .value("Seconds", outward + back),
                            y: .value("Rep", "\(rep.ordinal)"), height: .fixed(18))
                        .foregroundStyle(.orange).accessibilityLabel("Rep \(rep.ordinal) return")
                        .accessibilityValue(seconds(back))
                }
            }
        }
        .chartXAxisLabel("Seconds · outward (blue), return (orange)")
        .frame(minHeight: max(100, CGFloat(min(12, reps.count)) * 32 + 56), maxHeight: 400)
        .chartOverlay { proxy in
            GeometryReader { geometry in
                Rectangle().fill(.clear).contentShape(Rectangle()).onTapGesture { point in
                    if let frame = proxy.plotFrame,
                       let ordinal: String = proxy.value(atY: point.y - geometry[frame].minY) {
                        selectedRep = reps.first { "\($0.ordinal)" == ordinal }?.id
                    }
                }
            }
        }
    }
    private func repDetails(_ rep: WorkoutRepRecord) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Rep \(rep.ordinal) · \(rep.outcome)").font(.title3.bold())
            if rep.samples.isEmpty {
                Text("Detailed motion is unavailable. The rep summary is preserved.").foregroundStyle(.secondary)
            } else { angleChart(rep) }
            Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 8) {
                GridRow { Text("Time to extreme"); Text(seconds(rep.outboundSeconds)).monospacedDigit() }
                GridRow { Text("Return"); Text(seconds(rep.returnSeconds)).monospacedDigit() }
                GridRow { Text("Left mean / range"); Text(angleText(rep.left)).monospacedDigit() }
                GridRow { Text("Right mean / range"); Text(angleText(rep.right)).monospacedDigit() }
                GridRow { Text("Observed duration"); Text(rep.durationCoverage.map { "\(Int($0 * 100))%" } ?? "Unavailable") }
            }.font(.subheadline)
            if rep.returnedAt == nil { Text("The complete return was not observed.").font(.caption).foregroundStyle(.orange) }
            if rep.curveTruncated && !rep.samples.isEmpty { Text("The chart shows the most recent motion; averages include all observed intervals.").font(.caption) }
        }
    }
    private struct AnglePoint: Identifiable {
        let id: Int; let time: Double; let value: Double; let arm: String; let segment: String
    }
    private func points(_ rep: WorkoutRepRecord) -> [AnglePoint] {
        var result: [AnglePoint] = []
        for arm in ["Left", "Right"] {
            var segment = 0; var previous: Double?
            for sample in rep.samples {
                let angle = arm == "Left" ? sample.leftDegrees : sample.rightDegrees
                guard sample.usable, let angle else { segment += 1; previous = nil; continue }
                if let previous, sample.seconds - previous > ExerciseCounter.maximumGapSeconds { segment += 1 }
                result.append(AnglePoint(id: result.count, time: sample.seconds - rep.startedAt,
                    value: angle, arm: arm, segment: "\(arm)-\(segment)"))
                previous = sample.seconds
            }
        }
        return result
    }
    private func angleChart(_ rep: WorkoutRepRecord) -> some View {
        Chart(points(rep)) { point in
            LineMark(x: .value("Seconds", point.time), y: .value("Elbow angle", point.value),
                series: .value("Segment", point.segment))
                .foregroundStyle(by: .value("Arm", point.arm))
                .accessibilityLabel("\(point.arm) elbow").accessibilityValue("\(Int(point.value)) degrees")
        }
        .chartYScale(domain: 0...180).chartXAxisLabel("Seconds from observed start")
        .chartYAxisLabel("Elbow angle (°)")
        .chartForegroundStyleScale(["Left": Color.cyan, "Right": Color.orange])
        .frame(minHeight: 220, maxHeight: 320)
    }
    private func seconds(_ value: Double?) -> String { value.map { String(format: "%.2f s", $0) } ?? "Unavailable" }
    private func angleText(_ metric: WorkoutAngleMetrics) -> String {
        guard let mean = metric.mean, let min = metric.minimum, let max = metric.maximum else { return "Unavailable" }
        return String(format: "%.0f° / %.0f–%.0f°", mean, min, max)
    }
}
