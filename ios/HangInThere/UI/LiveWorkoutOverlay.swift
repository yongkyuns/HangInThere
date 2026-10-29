import SwiftUI

struct LiveReadinessChecklistView: View {
    let checklist: LiveReadinessChecklist
    let showsDetails: Bool
    var onDetails: (() -> Void)?

    init(checklist: LiveReadinessChecklist, initiallyExpanded: Bool = false, onDetails: (() -> Void)? = nil) {
        self.checklist = checklist
        showsDetails = initiallyExpanded
        self.onDetails = onDetails
    }

    var body: some View {
        Group {
            if showsDetails {
                VStack(alignment: .leading, spacing: 12) {
                ForEach(checklist.checks) { check in
                    VStack(alignment: .leading, spacing: 4) {
                        Label(check.title + " · " + check.value, systemImage: symbol(check.status))
                            .font(.callout.weight(.semibold))
                            .foregroundStyle(color(check.status))
                        Text(check.detail).font(.callout).foregroundStyle(.white)
                    }
                    .accessibilityIdentifier("liveReadiness-\(check.id)")
                }
                Text(checklist.summary)
                    .font(.callout)
                    .foregroundStyle(checklist.allPassed ? .green : .white)
                    .accessibilityIdentifier("liveReadinessBlocker")
                }
                .padding(14)
                .background(.black.opacity(0.82), in: RoundedRectangle(cornerRadius: 16))
            } else {
                Button { onDetails?() } label: {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 8) {
                            Image(systemName: checklist.allPassed ? "checkmark.circle.fill" :
                                    checklist.checks.first(where: { $0.status == .blocked }).map { checkIcon($0.id) } ?? "hourglass")
                                .font(.system(size: 24, weight: .semibold))
                                .foregroundStyle(checklist.allPassed ? .green : .orange)
                            Text(compactTitle)
                                .font(.system(size: 21, weight: .heavy, design: .rounded))
                                .lineLimit(1).minimumScaleFactor(0.65)
                                .accessibilityIdentifier("liveReadinessAction")
                            Spacer(minLength: 0)
                            Image(systemName: "info.circle").font(.system(size: 16))
                                .foregroundStyle(.white.opacity(0.65))
                        }
                        HStack(spacing: 2) {
                            ForEach(checklist.checks) { check in
                                Image(systemName: checkIcon(check.id))
                                    .font(.system(size: 17, weight: .semibold))
                                    .foregroundStyle(color(check.status))
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 28)
                                    .overlay(alignment: .bottomTrailing) {
                                        Image(systemName: symbol(check.status))
                                            .font(.system(size: 9, weight: .bold))
                                            .foregroundStyle(color(check.status))
                                            .background(.black, in: Circle())
                                    }
                                    .accessibilityLabel("\(check.title): \(statusTitle(check.status)). \(check.value)")
                                    .accessibilityIdentifier("liveReadiness-\(check.id)")
                            }
                        }
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .frame(height: 96)
                    .background(.black.opacity(0.78), in: RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(checklist.allPassed ? .green : .orange.opacity(0.7)))
                }
                .buttonStyle(.plain)
                .accessibilityHint("Open workout details for the complete start checks. \(checklist.actionDetail)")
            }
        }
        .accessibilityIdentifier("liveReadinessChecklist")
    }

    private func checkIcon(_ id: String) -> String {
        switch id {
        case "feed": "camera.fill"
        case "placement": "level"
        case "arms": "figure.arms.open"
        case "exercise": "dumbbell.fill"
        case "rails": "viewfinder"
        case "phone": "iphone"
        case "scene": "photo"
        default: "figure.strengthtraining.traditional"
        }
    }

    private var compactTitle: String {
        switch checklist.actionTitle {
        case "READY TO START": "READY"
        case "LEVEL YOUR PHONE": "LEVEL PHONE"
        case "IDENTIFYING EXERCISE": "FINDING EXERCISE"
        case "SHOW START POSITION", "HOLD START POSITION": "HOLD START POSE"
        default: checklist.actionTitle
        }
    }

    private func symbol(_ status: LiveReadinessChecklist.Status) -> String {
        switch status {
        case .passed: "checkmark.circle.fill"
        case .waiting: "clock"
        case .blocked: "exclamationmark.circle.fill"
        }
    }
    private func color(_ status: LiveReadinessChecklist.Status) -> Color {
        switch status {
        case .passed: .green
        case .waiting: .white.opacity(0.65)
        case .blocked: .orange
        }
    }
    private func statusTitle(_ status: LiveReadinessChecklist.Status) -> String {
        switch status {
        case .passed: "Passed"
        case .waiting: "Waiting"
        case .blocked: "Needs attention"
        }
    }
}

struct LiveGripCaptureCard: View {
    let feedback: WorkoutGripCaptureFeedback
    @State private var showsDiagnostics = false
    init(feedback: WorkoutGripCaptureFeedback, initiallyExpanded: Bool = false) {
        self.feedback = feedback
        _showsDiagnostics = State(initialValue: initiallyExpanded)
    }
    private var accent: Color { feedback.isGood ? .green : feedback.isChecking ? .cyan : .orange }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("GRIP · OPTIONAL", systemImage: feedback.isGood ? "checkmark.circle.fill" : "camera.viewfinder")
                .font(.system(size: 20, weight: .bold)).foregroundStyle(.cyan)
            Text("Start and reps don't wait for grip")
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("gripDoesNotBlockWorkout")
            Text(feedback.title)
                .font(.system(size: 30, weight: .heavy, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
                .foregroundStyle(accent)
                .accessibilityIdentifier("gripCaptureAction")
            Text(feedback.handsDiagnostic)
                .font(.system(size: 18, weight: .bold, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
            Button {
                showsDiagnostics.toggle()
            } label: {
                HStack {
                    Text(feedback.bodyDiagnostic).font(.system(size: 15, weight: .semibold).monospacedDigit())
                    Spacer(minLength: 4)
                    Image(systemName: showsDiagnostics ? "chevron.up" : "chevron.down")
                }
                .frame(minHeight: 32)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(showsDiagnostics ? "Hide" : "Show") grip capture diagnostics. \(feedback.bodyDiagnostic)")
            .accessibilityIdentifier("gripCaptureDiagnostics")
            if showsDiagnostics {
                Text(feedback.detail).font(.system(size: 15, weight: .medium))
                    .fixedSize(horizontal: false, vertical: true)
                Text(feedback.targetDiagnostic).font(.system(size: 14, weight: .medium))
                Text(feedback.contextDiagnostic).font(.system(size: 14, weight: .semibold))
                Text("Percent of the image's short side. Body size estimates distance; it does not measure hand detail.")
                    .font(.system(size: 13))
                if let age = feedback.observationAge {
                    Text(String(format: "Hand analysis age: %.2f s · Policy %d", age, feedback.policyVersion))
                        .font(.system(size: 13).monospacedDigit())
                }
            }
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color.black.opacity(0.92), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(accent, lineWidth: 3))
        .accessibilityIdentifier("liveGripCaptureCard")
    }
}

struct LiveCameraPlacementCard: View {
    let placement: LiveCameraPlacement
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "level").font(.system(size: 38))
                .foregroundStyle(placement.isUsable ? Color.green : .orange)
            VStack(alignment: .leading, spacing: 5) {
                Text("LEVEL YOUR PHONE")
                    .font(.system(size: 21, weight: .heavy, design: .rounded)).lineLimit(1).minimumScaleFactor(0.75)
                Text("Chest height · front or rear")
                    .font(.system(size: 14, weight: .medium))
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .frame(height: 96)
        .background(.black.opacity(0.78), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(placement.title). \(placement.instruction). \(placement.diagnostic)")
        .accessibilityIdentifier("liveCameraPlacementPrompt")
    }
}

// Camera-independent chrome, shared by the live feed and render reviews. The
// owner supplies current session evidence and handles camera/presentation work.
struct LiveWorkoutOverlay: View {
    let session: LiveSetSession
    let liveCompletedReps: Int
    var latestCompletedSet: WorkoutSetRecord? = nil
    var afterSetReport: WorkoutAfterSetReport? = nil
    var showsSetStarted = false
    var activity: WorkoutActivity? = nil
    var exerciseTitle: String? = nil
    var gripLabel: String? = nil
    var needsArmRepositioning = false
    var gripViewPrompt: String? = nil
    var gripCaptureFeedback: WorkoutGripCaptureFeedback? = nil
    var readiness: LiveReadinessChecklist? = nil
    var cameraPlacement: LiveCameraPlacement? = nil
    var onReadinessDetails: (() -> Void)? = nil
    let cameraReady: Bool
    let status: String
    let statusSymbol: String
    let needsAttention: Bool
    let showsManualStart: Bool
    let canStart: Bool
    let canSwitchCamera: Bool
    let nextCameraName: String
    let isRecording: Bool
    let countdown: Int?
    let canClose: Bool
    let onClose: () -> Void
    let onOptions: () -> Void
    let onSwitchCamera: () -> Void
    let onStart: () -> Void
    let onFinish: () -> Void
    let onNextSet: () -> Void
    let onResults: () -> Void

    var displayedCount: Int {
        if session.phase == .idle || session.phase == .finished {
            return afterSetReport?.measuredCompletedReps ?? latestCompletedSet?.completedCount ?? 0
        }
        return liveCompletedReps
    }

    private var needsCameraRepositioning: Bool { cameraPlacement?.isUsable == false }

    var body: some View {
        GeometryReader { available in
            let landscape = available.size.width > available.size.height
            VStack(spacing: 0) {
                header
                Spacer(minLength: 12)
                footer.frame(maxWidth: landscape ? 260 : 360)
                    .frame(maxWidth: .infinity, alignment: landscape ? .trailing : .center)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 12)
        .background {
            VStack(spacing: 0) {
                LinearGradient(colors: [.black.opacity(0.65), .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: 130)
                Spacer()
                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 200)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
        .overlay(alignment: .top) {
            if showsSetStarted, session.phase == .running, !needsArmRepositioning, !needsCameraRepositioning {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                    Text("SET STARTED").font(.system(size: 20, weight: .heavy, design: .rounded))
                }
                .foregroundStyle(.green)
                .frame(width: 210, height: 48)
                .background(.black.opacity(0.8), in: Capsule())
                .padding(.top, 84)
                .allowsHitTesting(false)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(session.trackingIssue == nil ? "Set started. Tracking your reps." : "Set started. Reacquiring your pose.")
                .accessibilityIdentifier("liveSetStarted")
            } else if let countdown {
                HStack(spacing: 8) {
                    Image(systemName: "timer")
                    Text("Starting in \(countdown)").font(.system(size: 22, weight: .bold, design: .rounded)).monospacedDigit()
                }
                .foregroundStyle(.white)
                .frame(width: 210, height: 48)
                .background(.black.opacity(0.8), in: Capsule())
                .padding(.top, 84)
                .allowsHitTesting(false)
                .accessibilityLabel("Starting in \(countdown) seconds. \(countdownCue).")
            }
        }
    }

    private var countdownCue: String {
        session.exercise == .pullUp ? "Hold your hang" : "Hold your top support"
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 6) {
            iconButton("xmark", label: "Close live workout", action: onClose)
                .disabled(!canClose)
            VStack(alignment: .leading, spacing: 4) {
                Text(exerciseTitle ?? (session.exercise == .pullUp ? "Pull-up" : "Dips"))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.white).lineLimit(1)
                if session.phase == .running {
                    Label(needsArmRepositioning || needsCameraRepositioning ? "CHECK VIEW" : session.trackingIssue == nil ? "TRACKING" : "PAUSED", systemImage: "circle.fill")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(session.trackingIssue == nil && !needsArmRepositioning && !needsCameraRepositioning ? .green : .orange)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .accessibilityIdentifier("liveTrackingStatus")
                }
            }
            Spacer(minLength: 4)
            if session.phase != .idle || latestCompletedSet != nil {
                VStack(spacing: 0) {
                    Text("\(displayedCount)")
                        .font(.system(size: 40, weight: .bold, design: .rounded))
                        .monospacedDigit().lineLimit(1).minimumScaleFactor(0.6)
                        .contentTransition(.numericText())
                    Text(session.phase == .idle || session.phase == .finished ? "completed cycles" : "reps").font(.caption2.weight(.medium))
                }
                .foregroundStyle(.white)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(displayedCount) \(session.phase == .idle || session.phase == .finished ? "measured completed cycles in the last set" : "measured completed cycles")")
                .accessibilityIdentifier("liveMovementCount")
            }
            if isRecording {
                Circle().fill(.red).frame(width: 9, height: 9)
                    .accessibilityLabel("Video recording active")
            }
            iconButton("chart.xyaxis.line", label: "Workout analysis", action: onResults)
            iconButton("slider.horizontal.3", label: "Workout settings and details", action: onOptions)
                .accessibilityIdentifier("liveWorkoutOptions")
        }
    }

    @ViewBuilder
    private var footer: some View {
        VStack(spacing: 12) {
            if session.phase == .idle, let readiness {
                LiveReadinessChecklistView(checklist: readiness, onDetails: onReadinessDetails ?? onOptions)
            } else if needsCameraRepositioning, let cameraPlacement, session.phase != .finished {
                LiveCameraPlacementCard(placement: cameraPlacement)
            } else if needsArmRepositioning, session.phase == .running {
                HStack(spacing: 14) {
                    Image(systemName: "figure.arms.open").font(.system(size: 38)).foregroundStyle(.orange)
                    VStack(alignment: .leading, spacing: 5) {
                        Text("SHOW BOTH ARMS").font(.system(size: 21, weight: .heavy, design: .rounded))
                            .lineLimit(1).minimumScaleFactor(0.75)
                        Text("Shoulders · elbows · hands").font(.system(size: 14, weight: .medium))
                    }
                    Spacer(minLength: 0)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity).frame(height: 96)
                .background(.black.opacity(0.78), in: RoundedRectangle(cornerRadius: 16))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(LiveArmVisibilityFeedback.title). \(LiveArmVisibilityFeedback.instruction)")
                .accessibilityIdentifier("liveArmRepositionPrompt")
            }
            switch session.phase {
            case .idle:
                if cameraReady, showsManualStart, readiness == nil { statusChip }
                HStack {
                    if cameraReady {
                        if showsManualStart {
                            actionButton("Start set", symbol: "play.fill", primary: true, action: onStart)
                                .disabled(!canStart)
                                .opacity(canStart ? 1 : 0.45)
                                .accessibilityIdentifier("startLiveSet")
                        } else if readiness == nil {
                            statusChip
                        }
                    }
                    Spacer()
                    iconButton("arrow.triangle.2.circlepath.camera", label: "Switch to \(nextCameraName) camera", action: onSwitchCamera)
                        .disabled(!canSwitchCamera)
                        .opacity(canSwitchCamera ? 1 : 0.4)
                        .accessibilityHint("Changing cameras clears bar setup.")
                        .accessibilityIdentifier("liveCameraSelection")
                }
                if latestCompletedSet != nil, let afterSetReport { afterSetSummary(afterSetReport) }
            case .running:
                if let gripLabel, session.exercise == .pullUp, !needsArmRepositioning, !needsCameraRepositioning {
                    Text("Grip · " + gripLabel).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                        .multilineTextAlignment(.center).lineLimit(1).minimumScaleFactor(0.8)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(.black.opacity(0.7), in: Capsule()).accessibilityIdentifier("liveGripLabel")
                }
                if let span = activity?.phases.last, span.phase == "hang" || span.phase == "support" {
                    Text("\(span.phase == "hang" ? "Hang" : "Top support") · \(String(format: "%.1f s", span.observedSeconds))")
                        .font(.headline.monospacedDigit()).foregroundStyle(.mint)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(.black.opacity(0.7), in: Capsule())
                        .accessibilityIdentifier("liveHoldTime")
                }
                if !needsArmRepositioning && !needsCameraRepositioning {
                    ExerciseProgressView(counter: session.counter, bodyDipSnapshot: session.bodyDipSnapshot,
                        bodyPullUpSnapshot: session.bodyPullUpSnapshot)
                }
                if gripLabel == nil, session.exercise == .pullUp, !needsArmRepositioning, !needsCameraRepositioning,
                   gripCaptureFeedback != nil || gripViewPrompt != nil {
                    Label("Grip unknown · optional", systemImage: "hand.raised")
                        .font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.7))
                        .accessibilityHint("Rep counting continues. Open Diagnostics for grip details.")
                }
                actionButton("Finish set", symbol: "stop.fill", action: onFinish)
                    .accessibilityIdentifier("stopLiveSet")
            case .finished:
                Label(session.endReason?.isInterruption == true ? "Set interrupted" : "Set complete",
                      systemImage: session.endReason?.isInterruption == true ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                    .font(.headline)
                    .foregroundStyle(session.endReason?.isInterruption == true ? .orange : .green)
                    .padding(12)
                    .background(.black.opacity(0.7), in: Capsule())
                HStack(spacing: 12) {
                    actionButton("Results", symbol: "list.bullet", action: onResults)
                    actionButton("New set", symbol: "arrow.counterclockwise", primary: true, action: onNextSet)
                        .accessibilityIdentifier("newLiveSet")
                }
                if let afterSetReport { afterSetSummary(afterSetReport) }
            }
        }
    }

    private func afterSetSummary(_ report: WorkoutAfterSetReport) -> some View {
        VStack(spacing: 6) {
            Text("\(report.measuredCompletedReps) completed cycles · \(report.practiceAttempts) practice attempts").font(.headline).foregroundStyle(.white)
            Text(report.observation).font(.caption).foregroundStyle(.white).multilineTextAlignment(.center)
            if let target = report.goalTarget, let value = report.goalValue {
                ProgressView(value: min(1, value / target)) { Text("Chosen goal: \(value.formatted()) / \(target.formatted()) \(report.goalMetric == "holdSeconds" ? "s" : "cycles")").font(.caption) }.tint(.mint)
            }
        }.padding(10).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
    }
    private var statusChip: some View {
        Label(status, systemImage: statusSymbol)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(needsAttention ? .orange : .white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(.black.opacity(0.6), in: Capsule())
    }

    private func iconButton(_ symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(.black.opacity(0.65), in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func actionButton(_ title: String, symbol: String, primary: Bool = false,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(primary ? .black : .white)
                .padding(.horizontal, 22)
                .frame(minHeight: 48)
                .background(primary ? Color.cyan : Color.black.opacity(0.72), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}
