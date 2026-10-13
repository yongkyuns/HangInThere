import SwiftUI
import Charts
import WorkoutCore

/// Read-only presentation: opening this view never pauses or resets the camera.
struct WorkoutReviewView: View {
    let sets: [WorkoutSetRecord]
    let currentReps: [WorkoutRepRecord]
    let storageError: String?
    let currentSet: WorkoutSetRecord?
    let goalsEnabled: Bool
    @State private var selectedSet: String?
    @State private var selectedAttempt: String?
    @State private var progressExercise = ExerciseCounter.Exercise.pullUp
    @State private var progressReport: WorkoutProgressReport?
    @State private var progressError: String?
    @State private var editingGoal = false
    @State private var goalRevision = 0
    @State private var selectedRep: String?
    init(sets: [WorkoutSetRecord], currentReps: [WorkoutRepRecord], storageError: String?, selectedRepId: String? = nil, currentSet: WorkoutSetRecord? = nil, goalsEnabled: Bool = true) {
        self.sets = sets; self.currentReps = currentReps; self.storageError = storageError
        self.currentSet = currentSet; self.goalsEnabled = goalsEnabled
        _progressExercise = State(initialValue: currentSet?.exercise ?? sets.first?.exercise ?? .pullUp)
        _selectedRep = State(initialValue: selectedRepId)
    }
    private var progressKey: String { progressExercise.rawValue + ":" + String(goalRevision) + ":" + sets.map { $0.id + $0.endReason }.joined(separator: ",") }
    private var practiceSets: [WorkoutSetRecord] { sets.filter(\.hasPractice) }
    private var practice: WorkoutSetRecord? { practiceSets.first { $0.id == selectedSet } ?? currentSet ?? practiceSets.first }
    private var attempts: [WorkoutRepRecord] { (practice?.reps ?? currentReps).filter { !$0.isCompleteForAnalytics } }
    private var completedSets: [WorkoutSetRecord] { sets.filter { $0.completedCount > 0 } }
    private var completedCurrent: [WorkoutRepRecord] { currentReps.filter(\.isCompleteForAnalytics) }
    private var reps: [WorkoutRepRecord] { (selectedSet != nil || currentSet != nil || currentReps.isEmpty) ? practice?.completedReps ?? [] : completedCurrent }
    private var selected: WorkoutRepRecord? { (completedCurrent + completedSets.flatMap(\.completedReps)).first { $0.id == selectedRep } ?? reps.last }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                if let storageError { Text(storageError).foregroundStyle(.orange) }
                if let practice {
                    WorkoutPracticeView(set: practice)
                    if practice.exercise == .pullUp { WorkoutGripView(set: practice, allowsCorrections: goalsEnabled) }
                }
                if reps.isEmpty {
                    if selected == nil && practice == nil && currentReps.isEmpty {
                        ContentUnavailableView("Your motion, rep by rep", systemImage: "chart.xyaxis.line",
                            description: Text("Hangs, support holds and partial attempts are practice. Completed cycles appear in rep timing."))
                    }
                } else {
                    Text("Rep timing").font(.title2.bold())
                    Text("Tap a rep to inspect it. Only completed reps are included; unfinished attempts are excluded.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    timingChart
                }
                if let selected { repDetails(selected) }
                if !attempts.isEmpty {
                    DisclosureGroup("Practice attempts · \(attempts.count)") {
                        Text("Review what was observed and why a completed cycle was not recorded. These attempts are excluded from completed-rep statistics.").font(.caption)
                        ForEach(attempts) { attempt in
                            Button("Attempt \(attempt.ordinal) · \((try? attempt.evidenceAssessment(setEndReason: endReason(for: attempt)))?.title ?? "Evidence unavailable")") { selectedAttempt = attempt.id }
                                .padding(.vertical, 4)
                        }
                        if let attempt = attempts.first(where: { $0.id == selectedAttempt }) { repDetails(attempt) }
                    }
                }
                Picker("Exercise progress", selection: $progressExercise) {
                    Text("Pull-up").tag(ExerciseCounter.Exercise.pullUp)
                    Text("Dip").tag(ExerciseCounter.Exercise.dip)
                }.pickerStyle(.segmented)
                if let progressReport {
                    WorkoutProgressView(report: progressReport, onEditGoal: goalsEnabled ? { editingGoal = true } : nil)
                }
                if let progressError { Text(progressError).foregroundStyle(.orange) }
                else if progressReport == nil { ProgressView("Loading progress history…") }
                if !practiceSets.isEmpty {
                    Text("Saved sets").font(.title2.bold())
                    ForEach(practiceSets) { set in
                        VStack(alignment: .leading, spacing: 4) {
                            Button("\(set.exercise.title) · \(set.completedCount) \(set.completedCount == 1 ? "rep" : "reps")") {
                                selectedSet = set.id; selectedAttempt = nil; selectedRep = set.completedReps.last?.id
                            }.font(.headline).buttonStyle(.plain)
                            if let time = set.trackedAtEpochMilliseconds {
                                Text(Date(timeIntervalSince1970: Double(time) / 1000).formatted(date: .abbreviated, time: .shortened))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Text(set.endReason == "disengaged" ? "Automatically completed" :
                                 set.endReason == "manual" ? "Ended manually" : "Interrupted")
                                .font(.caption).foregroundStyle(.secondary)
                            ForEach(set.completedReps) { rep in
                                Button("Rep \(rep.ordinal) · \(seconds(rep.outboundSeconds)) outward / \(seconds(rep.returnSeconds)) return") {
                                    // History is shown below, without reconfiguring active tracking.
                                    selectedRep = rep.id; selectedSet = set.id
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
            .task(id: progressKey) { await refreshProgress() }
            .sheet(isPresented: $editingGoal) {
                if let progressReport {
                    WorkoutGoalEditor(report: progressReport) { metric, target in
                        try WorkoutGoalStore.shared.setGoal(exercise: progressReport.exercise, metric: metric, target: target)
                        goalRevision += 1
                    }
                }
            }
    }
    private func refreshProgress() async {
        let exercise = progressExercise
        progressReport = nil
        do {
            if goalsEnabled {
                let archive = try await WorkoutRecordStore.shared.progressEvidence(excludingSetId: currentSet?.endReason == "running" ? currentSet?.id : nil)
                let bridge = WorkoutProgressBridge()
                let recent = try sets.map { record in
                    let input = String(decoding: try JSONEncoder().encode(record.progressInput), as: UTF8.self)
                    return try JSONDecoder().decode(WorkoutProgressEvidence.self, from: Data(try bridge.evidenceJson(recordJson: input).utf8))
                }
                guard !Task.isCancelled, exercise == progressExercise else { return }
                progressReport = try WorkoutGoalStore.shared.report(evidence: recent + archive.filter { archived in !recent.contains { $0.id == archived.id } }, exercise: exercise)
            } else {
                let history = String(decoding: try JSONEncoder().encode(sets.map(\.progressInput)), as: UTF8.self)
                let json = try WorkoutProgressBridge().reportJson(historyJson: history, exercise: progressExercise.rawValue, goalJson: nil)
                progressReport = try JSONDecoder().decode(WorkoutProgressReport.self, from: Data(json.utf8))
            }
            progressError = nil
        } catch { progressError = "Progress could not be loaded: \(error.localizedDescription)" }
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
    private func endReason(for rep: WorkoutRepRecord) -> String? {
        if currentSet?.id == rep.setId { return currentSet?.endReason }
        return sets.first { $0.id == rep.setId }?.endReason
    }
    private func repDetails(_ rep: WorkoutRepRecord) -> some View {
        let assessment = try? rep.evidenceAssessment(setEndReason: endReason(for: rep))
        return VStack(alignment: .leading, spacing: 12) {
            Text(rep.isCompleteForAnalytics ? "Rep \(rep.ordinal) · \(rep.outcome)" : "Attempt \(rep.ordinal) · incomplete").font(.title3.bold())
            WorkoutEvidenceView(assessment: assessment)
            if rep.samples.isEmpty {
                Text("Detailed motion is unavailable. The rep summary is preserved.").foregroundStyle(.secondary)
            } else { angleChart(rep) }
            Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 8) {
                GridRow { Text("Time to extreme"); Text(seconds(rep.outboundSeconds)).monospacedDigit() }
                GridRow { Text("Return"); Text(seconds(rep.returnSeconds)).monospacedDigit() }
                GridRow { Text("Left mean / range"); Text(angleText(rep.left)).monospacedDigit() }
                GridRow { Text("Right mean / range"); Text(angleText(rep.right)).monospacedDigit() }
            }.font(.subheadline)
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
