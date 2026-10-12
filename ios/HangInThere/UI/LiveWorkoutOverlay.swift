import SwiftUI

// Camera-independent chrome, shared by the live feed and render reviews. The
// owner supplies current session evidence and handles camera/presentation work.
struct LiveWorkoutOverlay: View {
    let session: LiveSetSession
    var latestCompletedSet: WorkoutSetRecord? = nil
    var showsSetStarted = false
    var activity: WorkoutActivity? = nil
    var exerciseTitle: String? = nil
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

    var body: some View {
        VStack(spacing: 0) {
            header
            Spacer(minLength: 16)
            footer
                .frame(maxWidth: 500)
                .frame(maxWidth: .infinity)
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
        .overlay {
            if showsSetStarted, session.phase == .running {
                VStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 40))
                    Text("SET STARTED")
                        .font(.system(size: 42, weight: .heavy, design: .rounded))
                        .minimumScaleFactor(0.65).lineLimit(1)
                    Text(session.trackingIssue == nil ? "Tracking your reps" : "Reacquiring your pose")
                        .font(.headline).foregroundStyle(session.trackingIssue == nil ? .white : .orange)
                }
                .foregroundStyle(.green)
                .padding(24)
                .frame(maxWidth: 420)
                .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 24))
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(.green.opacity(0.7), lineWidth: 2))
                .padding(.horizontal, 24)
                .allowsHitTesting(false)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(session.trackingIssue == nil ? "Set started. Tracking your reps." : "Set started. Reacquiring your pose.")
                .accessibilityIdentifier("liveSetStarted")
            } else if let countdown {
                VStack(spacing: 4) {
                    Text("Starting in").font(.headline)
                    Text("\(countdown)")
                        .font(.system(size: 76, weight: .bold, design: .rounded))
                        .monospacedDigit()
                    Text(countdownCue).font(.subheadline)
                }
                .foregroundStyle(.white)
                .padding(24)
                .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 24))
                .allowsHitTesting(false)
                .accessibilityLabel("Starting in \(countdown) seconds. \(countdownCue).")
            }
        }
    }

    private var countdownCue: String {
        session.exercise == .pullUp ? "Hold your hang" : "Hold your top support"
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 10) {
            iconButton("xmark", label: "Close live workout", action: onClose)
                .disabled(!canClose)
            VStack(alignment: .leading, spacing: 4) {
                Text(exerciseTitle ?? (session.exercise == .pullUp ? "Pull-up" : "Dips"))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(.white).lineLimit(1)
                if session.phase == .running {
                    Label(session.trackingIssue == nil ? "TRACKING" : "PAUSED", systemImage: "circle.fill")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(session.trackingIssue == nil ? .green : .orange)
                        .lineLimit(1)
                        .accessibilityIdentifier("liveTrackingStatus")
                }
            }
            Spacer(minLength: 4)
            if session.phase != .idle || latestCompletedSet != nil {
                VStack(spacing: 0) {
                    Text("\(session.phase == .idle ? latestCompletedSet?.acceptedCount ?? 0 : session.observedMovements)")
                        .font(.system(size: 40, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .contentTransition(.numericText())
                    Text(session.phase == .idle ? "last set" : "reps").font(.caption2.weight(.medium))
                }
                .foregroundStyle(.white)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(session.phase == .idle ? latestCompletedSet?.acceptedCount ?? 0 : session.observedMovements) counted reps\(session.phase == .idle ? " in the last set" : "")")
                .accessibilityIdentifier("liveMovementCount")
            }
            if isRecording {
                Circle().fill(.red).frame(width: 9, height: 9)
                    .accessibilityLabel("Debug video recording active")
            }
            iconButton("chart.xyaxis.line", label: "Workout analysis", action: onResults)
            iconButton("slider.horizontal.3", label: "Workout settings and details", action: onOptions)
                .accessibilityIdentifier("liveWorkoutOptions")
        }
    }

    @ViewBuilder
    private var footer: some View {
        VStack(spacing: 12) {
            switch session.phase {
            case .idle:
                if cameraReady, showsManualStart { statusChip }
                HStack {
                    if cameraReady {
                        if showsManualStart {
                            actionButton("Start set", symbol: "play.fill", primary: true, action: onStart)
                                .disabled(!canStart)
                                .opacity(canStart ? 1 : 0.45)
                                .accessibilityIdentifier("startLiveSet")
                        } else {
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
            case .running:
                if let span = activity?.phases.last, span.phase == "hang" || span.phase == "support" {
                    Text("\(span.phase == "hang" ? "Hang" : "Top support") · \(String(format: "%.1f s", span.observedSeconds))")
                        .font(.headline.monospacedDigit()).foregroundStyle(.mint)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(.black.opacity(0.7), in: Capsule())
                        .accessibilityIdentifier("liveHoldTime")
                }
                ExerciseProgressView(counter: session.counter)
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
            }
        }
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
