import SwiftUI
import Charts
import WorkoutCore

/// Uses the same evidence policy as Android; percentages describe source time.
struct WorkoutEvidenceView: View {
    let assessment: WorkoutAttemptAssessmentRecord?
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let assessment {
                Text(assessment.title).font(.headline)
                Text(assessment.explanation).font(.subheadline)
                if let reason = assessment.reason { Text(reason).font(.caption).foregroundStyle(.secondary) }
                Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 4) {
                    GridRow { Text("Observed time"); Text(coverage(assessment.durationCoverage)) }
                    GridRow { Text("Left-arm time"); Text(coverage(assessment.leftCoverage)) }
                    GridRow { Text("Right-arm time"); Text(coverage(assessment.rightCoverage)) }
                }.font(.body).monospacedDigit()
                ForEach(assessment.notes, id: \.self) { Text($0).font(.caption).foregroundStyle(.secondary) }
                if !assessment.angleComparisonEligible {
                    Text("Range is excluded from progress comparisons.").font(.caption).foregroundStyle(.secondary)
                }
            } else {
                Text("Attempt evidence could not be loaded. No completion or form judgment is available.").font(.subheadline)
            }
        }.padding().frame(maxWidth: .infinity, alignment: .leading)
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
    }
    private func coverage(_ value: Double?) -> String {
        value.map { String(format: "%.0f%%", $0 * 100) } ?? "Unavailable"
    }
}

/// Practice phases are useful even when a set has zero completed reps.
struct WorkoutPracticeView: View {
    let set: WorkoutSetRecord
    private func title(_ phase: String) -> String { WorkoutProgressBridge().phaseTitle(phase: phase) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Set phases").font(.title2.bold())
            Text("\(set.exercise.title) · \(set.completedCount) completed reps · \(set.reps.count - set.completedCount) incomplete attempts")
                .font(.subheadline).foregroundStyle(.secondary)
            if let activity = set.activity {
                let hold = set.exercise == .pullUp ? "hang" : "support"
                let longest = set.exercise == .pullUp ? activity.longestHangSeconds : activity.longestSupportSeconds
                HStack(alignment: .top, spacing: 24) {
                    metric("Longest \(title(hold).lowercased())", longest)
                    metric("Total \(title(hold).lowercased())", activity.totals.first { $0.phase == hold }?.observedSeconds ?? 0)
                }
                if !activity.phases.isEmpty {
                    Chart(activity.phases.filter { $0.endedAt > $0.startedAt }) { span in
                        BarMark(xStart: .value("Seconds", span.startedAt - (set.startedAt ?? span.startedAt)),
                            xEnd: .value("Seconds", span.endedAt - (set.startedAt ?? span.startedAt)),
                            y: .value("Phase", title(span.phase)), height: .fixed(14))
                            .foregroundStyle(phaseColor(span.phase))
                            .accessibilityLabel(title(span.phase))
                            .accessibilityValue(String(format: "%.1f observed seconds", span.observedSeconds))
                    }.chartXAxisLabel("Seconds from set start")
                        .frame(height: CGFloat(Set(activity.phases.map(\.phase)).count) * 30 + 60)
                    if activity.timelineTruncated {
                        Text("The timeline shows recent phases. Hold totals include all observed intervals.").font(.caption)
                    }
                }
                Text("Only fresh observations add hold time. Gaps and hidden arms split a continuous hold.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Text("Phase measurements are unavailable for this older set. Its rep summary is preserved.")
                    .font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
    private func phaseColor(_ phase: String) -> Color {
        switch phase {
        case "hang", "support": .mint
        case "lowering": .orange
        case "peakHold", "bottomHold", "effortHold": .yellow
        case "release", "unobserved", "acquiring": .gray
        default: .cyan
        }
    }
    private func metric(_ label: String, _ value: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(format: "%.1f s", value)).font(.title2.bold()).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct WorkoutProgressView: View {
    let report: WorkoutProgressReport
    var onEditGoal: (() -> Void)? = nil
    @State private var chartMetric = "holdSeconds"
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Your progress").font(.title2.bold())
            Text(report.coaching).font(.subheadline)
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(report.bestHoldSeconds.map { String(format: "%.1f s", $0) } ?? "—").font(.title2.bold())
                    Text("Best \(report.holdTitle.lowercased())").font(.caption).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(report.completedReps)").font(.title2.bold())
                    Text("Completed reps").font(.caption).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            if let range = report.bestElbowExcursionDegrees {
                Text(String(format: "Best observed both-arm range: %.0f°", range)).font(.subheadline)
            }
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(report.goal.createdAtEpochMilliseconds == nil ? "Suggested next target" : report.goalAchieved ? "Goal reached" : "Current goal").font(.headline)
                    Spacer()
                    if let onEditGoal { Button("Edit", action: onEditGoal) }
                }
                Text(report.goal.metric == "holdSeconds" ?
                     String(format: "%.0f s continuous %@", report.goal.target, report.holdTitle.lowercased()) :
                     "\(Int(report.goal.target)) completed reps in a set")
                ProgressView(value: report.goalFraction ?? 0).tint(report.goalAchieved ? .green : .cyan)
                Text(report.goalBest.map { report.goal.metric == "holdSeconds" ? String(format: report.goal.createdAtEpochMilliseconds == nil ? "%.1f s baseline" : "%.1f s since goal was set", $0) : "\(Int($0)) reps \(report.goal.createdAtEpochMilliseconds == nil ? "baseline" : "since goal was set")" } ?? "Track a new set to work toward this goal.")
                    .font(.caption).foregroundStyle(.secondary)
            }.padding().background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
            if !report.trend.isEmpty {
                Picker("Progress metric", selection: $chartMetric) {
                    Text(report.holdTitle).tag("holdSeconds")
                    Text("Reps").tag("completedReps")
                }.pickerStyle(.segmented)
                Chart(report.trend) { point in
                    if let value = chartMetric == "holdSeconds" ? point.holdSeconds : Double(point.completedReps) {
                        PointMark(x: .value("Date", Date(timeIntervalSince1970: Double(point.trackedAtEpochMilliseconds)/1000)),
                            y: .value(chartMetric == "holdSeconds" ? "Seconds" : "Reps", value))
                            .foregroundStyle(.mint)
                            .accessibilityLabel(chartMetric == "holdSeconds" ? report.holdTitle : "Completed reps")
                            .accessibilityValue(String(format: "%.1f", value))
                    }
                }.chartYScale(domain: .automatic(includesZero: true))
                    .chartYAxisLabel(chartMetric == "holdSeconds" ? "Longest hold (s)" : "Completed reps")
                    .frame(height: 180)
            }
            DisclosureGroup("Milestones · \(report.milestones.filter(\.achieved).count) reached") {
                ForEach(report.milestones) { milestone in
                    VStack(alignment: .leading, spacing: 3) {
                        Label(milestone.title, systemImage: milestone.achieved ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(milestone.achieved ? .green : .secondary)
                        if let date = milestone.achievedAtEpochMilliseconds {
                            Text(Date(timeIntervalSince1970: Double(date)/1000).formatted(date: .abbreviated,time: .omitted))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 3)
                }
            }
        }
    }
}

struct WorkoutGoalEditor: View {
    @Environment(\.dismiss) private var dismiss
    let exercise: ExerciseCounter.Exercise
    let holdTitle: String
    let onSave: (String, Double) throws -> Void
    @State private var metric: String
    @State private var target: Double
    @State private var error: String?
    init(report: WorkoutProgressReport, onSave: @escaping (String, Double) throws -> Void) {
        exercise = report.exercise; holdTitle = report.holdTitle; self.onSave = onSave
        _metric = State(initialValue: report.goal.metric); _target = State(initialValue: report.goal.target)
    }
    var body: some View {
        NavigationStack {
            Form {
                Text("Choose a target for future \(exercise.title.lowercased()) sets. You can adjust it whenever you need.")
                Picker("Measure", selection: $metric) {
                    Text("\(holdTitle) seconds").tag("holdSeconds")
                    Text("Completed reps").tag("completedReps")
                }
                TextField("Target", value: $target, format: .number)
                if let error { Text(error).foregroundStyle(.orange) }
            }.navigationTitle("Workout goal")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            do { try onSave(metric, target); dismiss() }
                            catch { self.error = "Enter whole reps (1–1000) or hold seconds (1–3600)." }
                        }
                    }
                }
        }
    }
}
