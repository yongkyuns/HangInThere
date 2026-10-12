import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct LiveSetupGuide: Equatable, Sendable {
    let exercise: ExerciseCounter.Exercise
    let side: ArmMeasurement.Side

    var selectedArmText: String {
        exercise == .pullUp ? "Both shoulders, elbows, and wrists; one arm can continue if obscured"
            : "Both shoulders, elbows, and wrists; the selected hand anchors its rail"
    }

    var apparatusText: String {
        switch exercise {
        case .pullUp:
            "Keep the gripping bar and both hands visible."
        case .dip:
            "Keep both dip grips and the rails beside your hands visible."
        }
    }

    var bodyText: String {
        switch exercise {
        case .pullUp:
            "Keep both arms, torso, hips, and as much of the legs as practical in frame."
        case .dip:
            "Use a front or oblique view with both arms, torso, hips, and grip rails clearly visible."
        }
    }
}

@MainActor
struct LiveSetupView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    private enum DetailsTab: String, CaseIterable {
        case workout = "Workout"
        case diagnostics = "Diagnostics"
    }
    @State private var showingAnalysis = false
    @State private var reviewRecord: WorkoutSetRecord?
    @State private var showingDetails = false
    @State private var detailsTab: DetailsTab = .workout
    @State private var camera = LiveCameraPreviewController()
    @State private var exerciseSelection: WorkoutExerciseSelection = .automatic
    @State private var side: ArmMeasurement.Side = .left
    @State private var barSetup: BarSetupFrame?
    @State private var qualificationDocument: QualificationReportDocument?
    @State private var qualificationExportFilename = "HangInThere-live-qualification"
    @State private var showingQualificationExporter = false
    @State private var showingQualificationExportError = false
    @State private var qualificationExportErrorMessage = ""
    @State private var debugSessionDocument: DebugSessionPackageDocument?
    @State private var debugSessionExportFilename = "HangInThere-debug-session"
    @State private var showingDebugSessionExporter = false
    @State private var showingDebugSessionExportError = false
    @State private var debugSessionExportErrorMessage = ""
    @State private var preparingDebugSessionExport = false

    private var exercise: ExerciseCounter.Exercise { camera.liveSet.exercise }
    private var isDetectingExercise: Bool {
        exerciseSelection == .automatic && camera.resolvedExercise == nil
    }

    // Auto needs both starting chains to identify the exercise. This is display
    // guidance only; an obscured arm may still fall back during a running set.
    private var automaticExerciseGuidance: String {
        if let pose = camera.latestPose, PullUpArmMeasurement(pose: pose).arms.count != 2 {
            return "Show both arms and hands clearly"
        }
        return "Hold a clear hang or top support · show both hands"
    }

    private var guide: LiveSetupGuide {
        LiveSetupGuide(exercise: exercise, side: side)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                cameraSurface.ignoresSafeArea()
                LiveWorkoutOverlay(
                    session: camera.liveSet,
                    latestCompletedSet: camera.latestCompletedWorkout,
                    showsSetStarted: camera.showsSetStarted,
                    activity: camera.activeWorkoutRecord?.activity,
                    exerciseTitle: camera.workoutTitle,
                    cameraReady: camera.isCameraReady,
                    status: overlayStatus,
                    statusSymbol: overlayStatusSymbol,
                    needsAttention: camera.liveSet.trackingIssue != nil || camera.automaticBarState == .ambiguous,
                    showsManualStart: !camera.handsFreeStartEnabled,
                    canStart: camera.setupReadiness.state.isReady,
                    canSwitchCamera: camera.canSwitchCamera,
                    nextCameraName: camera.cameraPosition == .rear ? "front" : "rear",
                    isRecording: camera.debugCaptureState.isActive,
                    countdown: countdownSeconds,
                    canClose: !preparingDebugSessionExport,
                    onClose: { dismiss() },
                    onOptions: { openDetails() },
                    onSwitchCamera: {
                        barSetup = nil
                        Task { await camera.selectCamera(camera.cameraPosition == .rear ? .front : .rear) }
                    },
                    onStart: { _ = camera.startSet() },
                    onFinish: { camera.stopSet() },
                    onNextSet: { camera.prepareNextSet() },
                    onResults: { showingAnalysis = true }
                )
            }
            .background(.black)
            .preferredColorScheme(.dark)
            .toolbar(.hidden, for: .navigationBar)
            .statusBarHidden(true)
            .sheet(isPresented: $showingDetails, onDismiss: {
                camera.setWorkoutDetailsPresented(false)
            }) {
                detailsPanel
            }
            .sheet(isPresented: $showingAnalysis) {
                NavigationStack {
                    WorkoutReviewView(sets: camera.workoutHistory, currentReps: camera.repAnalysis,
                                      storageError: camera.workoutStorageError, currentSet: reviewRecord)
                        .toolbar { Button("Done") { showingAnalysis = false } }
                }
            }
            .onChange(of: showingAnalysis) { _, visible in
                if visible { reviewRecord = camera.workoutRecordForReview() }
            }
            .task {
                await camera.loadWorkoutHistory()
                camera.configureWorkout(selection: exerciseSelection, side: side)
                await camera.start()
            }
            .onChange(of: exerciseSelection) { _, newSelection in
                camera.configureWorkout(selection: newSelection, side: side)
                barSetup = nil
            }
            .onChange(of: side) { _, newSide in
                camera.configureWorkout(selection: exerciseSelection, side: newSide)
                barSetup = nil
            }
            .onChange(of: scenePhase) { _, phase in
                switch phase {
                case .active:
                    if camera.isSuspended {
                        Task { await camera.resumeAfterInterruption() }
                    }
                case .inactive, .background:
                    barSetup = nil
                    camera.suspendForSceneLoss()
                @unknown default:
                    barSetup = nil
                    camera.suspendForSceneLoss()
                }
            }
            .onChange(of: camera.liveSet.phase) { _, phase in
                if phase == .running { showingDetails = false }
            }
            .onDisappear {
                camera.setWorkoutDetailsPresented(false)
                camera.stop()
            }
        }
    }

    private func openDetails(tab: DetailsTab? = nil) {
        if let tab { detailsTab = tab }
        camera.setWorkoutDetailsPresented(true)
        showingDetails = true
    }

    private var countdownSeconds: Int? {
        if case .countdown(let seconds) = camera.handsFreeStart.state { return seconds }
        return nil
    }

    private var overlayStatus: String {
        if camera.liveSet.phase == .running {
            if camera.liveSet.trackingIssue != nil { return liveTrackingTitle }
            switch camera.liveSet.counter.phase {
            case .seekingStart: return "Hold the starting position"
            case .ready: return "Ready"
            case .outbound: return "Lowering"
            case .returning: return "Return to the top"
            case .finished: return "Set complete"
            }
        }
        if !camera.framing.state.isReady { return camera.framing.title }
        if isDetectingExercise { return automaticExerciseGuidance }
        if camera.currentBar == nil {
            if exercise == .dip {
                switch camera.automaticBarState {
                case .lookingForGrip: return "Hold top support · show both hands"
                case .findingEdge: return "Finding dip support…"
                case .holding: return "Hold still · checking support"
                case .ambiguous: return "Support unclear · change the camera angle"
                case .locked: return "Dip support found"
                }
            }
            switch camera.automaticBarState {
            case .lookingForGrip: return "Grip the bar with both hands"
            case .findingEdge: return "Finding the bar…"
            case .holding: return "Hold still · locking the bar"
            case .ambiguous: return "Bar unclear · change the camera angle"
            case .locked: return "Bar found"
            }
        }
        if !camera.setupReadiness.state.isReady { return "Keep the phone still · checking setup" }
        if camera.handsFreeStartEnabled {
            return exercise == .pullUp ? "Show a full hang · tracking starts automatically" : "Show top support · tracking starts automatically"
        }
        return "Ready to start"
    }

    private var overlayStatusSymbol: String {
        if camera.liveSet.phase == .running { return liveTrackingSymbol }
        if !camera.framing.state.isReady { return framingStatusSymbol }
        return camera.setupReadiness.state.isReady ? "checkmark.circle.fill" : "viewfinder"
    }

    private var cameraSurface: some View {
        ZStack {
            Color.black
            if camera.isCameraReady {
                // Keep the feed and all geometry aspect-fitted to the same full
                // viewport; filling/cropping the camera would misalign the bar.
                LiveCameraPreviewSurface(session: camera.session)
                if let pose = camera.latestPose {
                    PoseOverlay(result: pose, selectedSide: side, usesBothArms: true)
                    BarOverlay(imageSize: pose.imageSize, bar: camera.currentBar)
                }
            } else {
                cameraStatus.padding(24)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(camera.cameraPosition.title) live camera feed")
    }

    private var detailsPanel: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Picker("Details", selection: $detailsTab) {
                        ForEach(DetailsTab.allCases, id: \.self) { tab in
                            Text(tab.rawValue).tag(tab)
                        }
                    }
                    .pickerStyle(.segmented)

                    switch detailsTab {
                    case .workout:
                        switch camera.liveSet.phase {
                        case .idle:
                            workoutSelection
                            readyStateCard
                            barCalibrationCard
                            DisclosureGroup("Framing and placement") { framingChecklist }
                        case .running:
                            liveSetCard
                        case .finished:
                            liveResultsCard
                        }
                    case .diagnostics:
                        qualificationDisclosure
                        scopeNote
                    }
                }
                .padding(16)
            }
            .toolbar(.visible, for: .navigationBar)
            .navigationTitle("Workout details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { showingDetails = false }
                        .disabled(preparingDebugSessionExport)
                }
            }
            .sheet(item: $barSetup, onDismiss: { camera.endBarSetup() }) { setup in
                BarSetupView(setup: setup) { bar in
                    camera.confirmBar(bar, for: setup)
                }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
            .fileExporter(
                isPresented: $showingQualificationExporter,
                document: qualificationDocument,
                contentType: .json,
                defaultFilename: qualificationExportFilename
            ) { result in
                switch result {
                case .success:
                    qualificationDocument = nil
                case .failure(let error):
                    qualificationDocument = nil
                    if let cocoaError = error as? CocoaError,
                       cocoaError.code == .userCancelled {
                        break
                    }
                    qualificationExportErrorMessage = error.localizedDescription
                    showingQualificationExportError = true
                }
            }
            .alert(
                "Couldn't export qualification report",
                isPresented: $showingQualificationExportError
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(qualificationExportErrorMessage)
            }
            .fileExporter(
                isPresented: $showingDebugSessionExporter,
                document: debugSessionDocument,
                contentType: .hangInThereDebugSession,
                defaultFilename: debugSessionExportFilename
            ) { result in
                debugSessionDocument = nil
                switch result {
                case .success:
                    camera.discardCompletedDebugCapture()
                case .failure(let error):
                    if let cocoaError = error as? CocoaError,
                       cocoaError.code == .userCancelled {
                        return
                    }
                    debugSessionExportErrorMessage = error.localizedDescription
                    showingDebugSessionExportError = true
                }
            }
            .alert(
                "Couldn't export debug session",
                isPresented: $showingDebugSessionExportError
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(debugSessionExportErrorMessage)
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .preferredColorScheme(.dark)
        .interactiveDismissDisabled(preparingDebugSessionExport)
    }

    private var cameraStatus: some View {
        VStack(spacing: 12) {
            if camera.state == .requestingPermission || camera.state == .starting {
                ProgressView()
                    .tint(.white)
            } else {
                Image(systemName: cameraStatusIcon)
                    .font(.system(size: 32, weight: .semibold))
                    .foregroundStyle(.white)
            }

            Text(camera.state.title)
                .font(.headline)
                .foregroundStyle(.white)

            Text(camera.state.detail)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.72))
                .multilineTextAlignment(.center)

            if camera.state == .denied {
                Button("Open Settings") { camera.openSettings() }
                    .buttonStyle(.borderedProminent)
            } else if camera.isSuspended {
                Button {
                    Task { await camera.resumeAfterInterruption() }
                } label: {
                    Label("Resume camera", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .frame(maxWidth: 420)
    }

    private var workoutSelection: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Workout")
                .font(.headline)

            Picker("Exercise", selection: $exerciseSelection) {
                ForEach(WorkoutExerciseSelection.allCases, id: \.rawValue) { selection in
                    Text(selection.title).tag(selection)
                }
            }
            .pickerStyle(.segmented)

            if exerciseSelection == .automatic {
                Text(camera.resolvedExercise == nil
                     ? "Hold a hang or top support with both hands visible. Tracking starts once the exercise, apparatus and camera are ready."
                     : "\(camera.workoutTitle). The exercise stays fixed throughout the set.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if exerciseSelection != .pullUp {
                Picker("Tracking side", selection: $side) {
                    ForEach(ArmMeasurement.Side.allCases, id: \.rawValue) { side in
                        Text(side.rawValue.capitalized).tag(side)
                    }
                }
                .pickerStyle(.segmented)
            }

            if camera.debugCaptureState.isActive {
                Text("Exercise and tracking side are locked while debug capture is active so the exported session has one unambiguous configuration.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .disabled(camera.debugCaptureState.isActive)
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var framingChecklist: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Framing")
                .font(.headline)

            setupRow(
                "\(camera.cameraPosition.title) camera active",
                detail: camera.isCameraReady ? "Ready" : camera.state.title,
                symbol: camera.isCameraReady ? "checkmark.circle.fill" : "circle"
            )
            setupRow(
                "Athlete + arms",
                detail: camera.framing.detail,
                symbol: framingStatusSymbol
            )
            setupRow("Tracked arms", detail: isDetectingExercise ? "Show both shoulders, elbows, and wrists to detect the starting pose." : guide.selectedArmText, symbol: "figure.arms.open")
            setupRow(
                "Apparatus",
                detail: isDetectingExercise ? "Keep the gripping bar or both dip rails visible beside your hands." : "Visual check only. " + guide.apparatusText,
                symbol: "line.diagonal"
            )
            setupRow("Body position", detail: isDetectingExercise ? "Hold an extended hang or top support. Use a front or oblique view with both arms visible." : guide.bodyText, symbol: "viewfinder")
            setupRow(
                "Phone orientation",
                detail: phoneStabilityDetail,
                symbol: phoneStabilitySymbol
            )
            setupRow(
                "Camera position",
                detail: sceneStabilityDetail,
                symbol: sceneStabilitySymbol
            )
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var barCalibrationCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Bar reference")
                        .font(.headline)
                    Text(barCalibrationDetail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Image(systemName: camera.currentBar == nil ? "line.diagonal" : "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(camera.currentBar == nil ? .orange : .green)
                    .accessibilityHidden(true)
            }

            Button {
                barSetup = camera.beginBarSetup()
            } label: {
                Label(
                    camera.currentBar == nil ? "Set bar manually" : "Adjust bar",
                    systemImage: camera.currentBar == nil ? "viewfinder" : "slider.horizontal.3"
                )
                .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(
                !camera.isCameraReady
                    || camera.resolvedExercise == nil
                    || !camera.framing.state.isReady
                    || !camera.motionSampleAvailable
                    || !camera.debugCaptureAllowsBarSetup
            )
            .accessibilityIdentifier("liveSetupBar")

            if !camera.debugCaptureAllowsBarSetup {
                Text("Waiting for the debug movie recorder and its first analyzed source frame before bar calibration.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if camera.currentBar != nil {
                Button("Clear bar reference", role: .destructive) {
                    camera.clearBar()
                }
                .font(.caption)
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var readyStateCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: camera.setupReadiness.state.isReady ? "checkmark.circle.fill" : "circle.dashed")
                    .font(.title2)
                    .foregroundStyle(readyStatusColor)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 4) {
                    Text(liveStartTitle)
                        .font(.headline)
                    Text(
                        camera.setupReadiness.state.isReady
                            ? liveStartHelp
                            : readyStateHelp
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)
            }

            Group {
                Toggle("Hands-free start", isOn: Binding(
                    get: { camera.handsFreeStartEnabled },
                    set: { camera.setHandsFreeStartEnabled($0) }
                ))
                .accessibilityIdentifier("handsFreeLiveStart")
                Text(camera.handsFreeStartEnabled
                     ? (isDetectingExercise
                        ? "Hold a hang or top support with both hands visible. Tracking starts once the exercise, apparatus and camera are ready."
                        : exercise == .pullUp
                        ? "Show a full hang after bar setup. Tracking begins automatically."
                        : "Show both grips and hold top support. Dip support is found automatically; keep your arms extended and hands steady. Tracking begins automatically. Crossed legs are optional.")
                     : "Tap Start set when ready, or enable automatic start.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if camera.setupReadiness.state.isReady,
               !camera.handsFreeStartEnabled {
                Button {
                    _ = camera.startSet()
                } label: {
                    Label("Start set", systemImage: "play.fill")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityIdentifier("startLiveSet")
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
        .accessibilityIdentifier("liveSetupReadiness")
    }

    private var liveSetCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Live set")
                        .font(.headline)
                    Text(liveTrackingTitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Text("\(camera.liveSet.observedMovements)")
                    .font(.system(size: 52, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .accessibilityIdentifier("liveMovementCount")
            }

            Label(liveTrackingDetail, systemImage: liveTrackingSymbol)
                .font(.caption)
                .foregroundStyle(liveTrackingColor)

            Button(role: .destructive) {
                camera.stopSet()
            } label: {
                Label("Stop set", systemImage: "stop.fill")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityIdentifier("stopLiveSet")
        }
        .padding(18)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private var liveResultsCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Label(liveResultTitle, systemImage: liveResultIcon)
                    .font(.headline)
                    .foregroundStyle(liveResultColor)

                Spacer()

                Text("MOVEMENT ONLY")
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(.thinMaterial, in: Capsule())
            }

            VStack(alignment: .leading, spacing: 2) {
                Text("\(camera.liveSet.observedMovements)")
                    .font(.system(size: 64, weight: .bold, design: .rounded))
                    .monospacedDigit()
                Text(camera.liveSet.observedMovements == 1 ? "observed movement" : "observed movements")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.secondary)
            }

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 24) {
                    liveResultMetric("Duration", formattedLiveDuration)
                    liveResultMetric("Tracking", formattedLiveCoverage)
                }

                VStack(alignment: .leading, spacing: 12) {
                    liveResultMetric("Duration", formattedLiveDuration)
                    liveResultMetric("Tracking", formattedLiveCoverage)
                }
            }

            if let message = liveResultInterruptionMessage {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            if !camera.liveSet.movementTimes.isEmpty {
                DisclosureGroup {
                    VStack(spacing: 0) {
                        ForEach(camera.liveSet.movementTimes.indices, id: \.self) { index in
                            HStack {
                                Text("Movement \(index + 1)")
                                Spacer()
                                Text(formatLiveTime(camera.liveSet.movementTimes[index]))
                                    .monospacedDigit()
                                    .foregroundStyle(.secondary)
                            }
                            .font(.subheadline)
                            .padding(.vertical, 8)
                        }
                    }
                    .padding(.top, 6)
                } label: {
                    Label("Movement timeline", systemImage: "list.bullet.rectangle")
                        .font(.subheadline.weight(.semibold))
                }
            }

            Text("Chin clearance, strict dip depth, and form quality are not verified.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Button {
                camera.prepareNextSet()
                showingDetails = false
            } label: {
                Label("New set", systemImage: "arrow.counterclockwise")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityIdentifier("newLiveSet")
        }
        .padding(18)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .accessibilityIdentifier("liveSetResults")
    }

    @ViewBuilder
    private func liveResultMetric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.headline)
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var cameraStatusIcon: String {
        switch camera.state {
        case .denied, .interrupted, .failed:
            return "video.slash.fill"
        case .unavailable:
            return "camera.fill"
        default:
            return "video.fill"
        }
    }

    private var liveResultTitle: String {
        if camera.liveSet.endReason?.isInterruption == true {
            return "Set interrupted"
        }
        return "Set complete"
    }

    private var liveResultIcon: String {
        camera.liveSet.endReason?.isInterruption == true
            ? "exclamationmark.triangle.fill"
            : "checkmark.circle.fill"
    }

    private var liveResultColor: Color {
        camera.liveSet.endReason?.isInterruption == true ? .orange : .green
    }

    private var liveResultInterruptionMessage: String? {
        guard let reason = camera.liveSet.endReason, reason.isInterruption else {
            return nil
        }
        switch reason {
        case .appInactive:
            return "The set ended when the app left the foreground. Camera framing and the bar reference must be checked again."
        case .cameraInterrupted:
            return "The set ended because the camera was interrupted. Re-check framing and the bar reference before another set."
        case .cameraFailure:
            return "The set ended because the camera session stopped unexpectedly."
        case .setupInvalidated:
            return "The set ended because the fixed bar reference became incompatible with the camera frames."
        case .phoneMoved:
            return "The set ended because the phone rotated after bar calibration. Re-check framing and set the bar again."
        case .sceneShifted:
            return "The set ended because static background structure translated relative to the bar-calibration frame. Re-check framing and set the bar again."
        case .sceneScaled:
            return "The set ended because the static background expanded or contracted relative to calibration, consistent with camera distance/zoom change."
        case .manual, .disengaged:
            return nil
        case .trackingLost:
            return "Tracking was unavailable for too long. The set was saved as interrupted."
        }
    }

    private var liveTrackingTitle: String {
        if let issue = camera.liveSet.trackingIssue {
            return liveIssueTitle(issue)
        }
        return camera.liveSet.counter.phase.title
    }

    private var liveTrackingDetail: String {
        if let issue = camera.liveSet.trackingIssue {
            return liveIssueDetail(issue)
        }
        switch camera.liveSet.counter.phase {
        case .seekingStart:
            return "Hold the extended starting position until tracking is ready."
        case .ready:
            return "Starting position acquired."
        case .outbound:
            return "Movement in progress."
        case .returning:
            return "Return to the extended position."
        case .finished:
            return "Set finished."
        }
    }

    private var liveTrackingColor: Color {
        camera.liveSet.trackingIssue == nil ? .secondary : .orange
    }

    private var liveTrackingSymbol: String {
        camera.liveSet.trackingIssue == nil ? "figure.strengthtraining.traditional" : "exclamationmark.triangle.fill"
    }

    private func liveIssueTitle(_ issue: String) -> String {
        switch issue {
        case "barReferenceUnavailable": "Bar reference lost"
        case "lowConfidence": exercise == .pullUp ? "Arms unclear" : "Selected arm unclear"
        case "missingJoint": exercise == .pullUp ? "Arms hidden" : "Selected arm hidden"
        case "armBaselineUnavailable": "Show an extended hang"
        case "dipSupportNeeded": "Hold top support"
        case "dipSupportUnclear": "Show your shoulder and grip"
        case "dipGripMoved": "Re-establish your grip"
        case "multiplePeople": "Keep one athlete in frame"
        case "sourceTimeGap": "Tracking interrupted"
        default: "Tracking paused"
        }
    }

    private func liveIssueDetail(_ issue: String) -> String {
        switch issue {
        case "barReferenceUnavailable":
            return "Stop the set and set the bar reference again."
        case "lowConfidence":
            return exercise == .pullUp ? "Keep your arms clear and well lit." : "Keep the selected arm clear and well lit."
        case "missingJoint":
            return exercise == .pullUp ? "Reposition so your shoulders, elbows, and wrists are visible."
                : "Reposition so the selected shoulder, elbow, and wrist are visible."
        case "armBaselineUnavailable":
            return "A newly visible arm needs its own starting position. Hold an extended hang to resume counting."
        case "dipSupportNeeded":
            return "Hold the top with extended arms and your hands at the grips."
        case "dipSupportUnclear":
            return "Keep the selected shoulder and hand visible beside the confirmed rail."
        case "dipGripMoved":
            return "Your hand moved away from the starting grip. Hold top support to resume counting."
        case "multiplePeople":
            return "The current live profile supports one athlete."
        case "sourceTimeGap":
            return "A camera timing gap interrupted the active attempt; re-establish the starting position."
        default:
            return "Re-establish the starting position before continuing."
        }
    }

    private var formattedLiveDuration: String {
        let seconds = max(0, camera.liveSet.durationSeconds)
        let minutes = Int(seconds) / 60
        let remainder = seconds - Double(minutes * 60)
        return minutes > 0 ? String(format: "%d:%04.1f", minutes, remainder) : String(format: "%.1fs", remainder)
    }

    private var formattedLiveCoverage: String {
        guard let coverage = camera.liveSet.trackingCoverage else { return "—" }
        return "\(Int((coverage * 100).rounded()))%"
    }

    private func formatLiveTime(_ seconds: Double) -> String {
        let safe = max(0, seconds)
        let minutes = Int(safe) / 60
        let remainder = safe - Double(minutes * 60)
        return minutes > 0 ? String(format: "%d:%04.1f", minutes, remainder) : String(format: "%.1fs", remainder)
    }

    private var readyStatusColor: Color {
        camera.setupReadiness.state.isReady ? .green : .secondary
    }

    private var liveStartTitle: String {
        if case .countdown(let seconds) = camera.handsFreeStart.state { return "Starting in \(seconds)…" }
        if camera.handsFreeStartEnabled {
            return camera.setupReadiness.state.isReady ? "Ready for hands-free start" : "Checking setup"
        }
        return camera.setupReadiness.state.isReady ? "Ready to start" : "Setup not complete"
    }

    private var liveStartHelp: String {
        if isDetectingExercise { return automaticExerciseGuidance }
        if camera.handsFreeStartEnabled {
            return exercise == .pullUp
                ? "Hang from the bar with extended arms. Tracking begins once setup is ready."
                : "Hold top support with extended arms and steady hands. Tracking begins once setup is ready. Crossed legs are optional."
        }
        return exercise == .pullUp ? "Camera, arms, and fixed bar reference are ready."
            : "Camera, selected arm, and fixed bar reference are ready."
    }

    private var barCalibrationDetail: String {
        if isDetectingExercise { return automaticExerciseGuidance }
        if let bar = camera.currentBar {
            return bar.method == .automaticContours
                ? "\(exercise == .dip ? "Dip support" : "Bar") found automatically. Keep the phone fixed. \(liveStartHelp)"
                : "\(camera.barRole.title) confirmed. Keep the phone fixed."
        }
        if !camera.framing.state.isReady {
            if camera.barRole != .pullUpGrip { return "Keep both arms and dip grips visible for automatic setup." }
            return "Keep one athlete and both raised hands visible for automatic pull-up setup, or mark the edge manually."
        }
        if !camera.motionSampleAvailable {
            return "Waiting for device-motion data before the bar can be calibrated."
        }
        if exercise == .dip {
            switch camera.automaticBarState {
            case .lookingForGrip: return "Hold top support with both hands visible. Setup is automatic."
            case .findingEdge: return "Looking for visible dip support near both hands. Manual setup is available if needed."
            case .ambiguous: return "Show the bar at both hands, or two clearly separated rails. Setup is automatic."
            case .holding: return "Checking that the support and both hands stay still…"
            case .locked: return "Dip support found automatically. \(liveStartHelp)"
            }
        }
        switch camera.automaticBarState {
        case .lookingForGrip: return "Grip the bar with both hands and hold still briefly. Setup is automatic."
        case .findingEdge: return "Looking for a visible bar near both hands. You can also set it manually."
        case .ambiguous: return "No clear gripping edge yet. Keep both hands visible or set the bar manually."
        case .holding: return "Checking that the bar and grip stay still…"
        case .locked: return "Bar found automatically. \(liveStartHelp)"
        }
    }

    private var readyStateHelp: String {
        if !camera.isCameraReady { return "Start the selected camera." }
        if !camera.framing.state.isReady { return camera.framing.detail }
        if camera.currentBar == nil { return barCalibrationDetail }
        if !camera.phoneOrientation.state.allowsLiveSet {
            return "Phone orientation is not stable against the calibration baseline."
        }
        if !camera.sceneTranslation.state.allowsLiveSet {
            return sceneStabilityDetail
        }
        return "Complete the remaining setup checks."
    }

    private var scopeNote: some View {
        Label(
            "Live sets use the same bar-relative movement counter as recorded review. Sustained phone rotation, background translation, or radial background scale change after calibration invalidates the set. These checks still do not constitute full camera-pose estimation.",
            systemImage: "info.circle"
        )
        .font(.footnote)
        .foregroundStyle(.secondary)
    }

    private var qualificationDisclosure: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 14) {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 20) {
                        qualificationMetric("Vision", formattedVisionLatency)
                        qualificationMetric("Scene reg", formattedSceneRegistrationLatency)
                        qualificationMetric("Analysis", formattedAnalysisRate)
                        qualificationMetric("Thermal", camera.qualificationThermalLevel.rawValue.capitalized)
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        qualificationMetric("Vision", formattedVisionLatency)
                        qualificationMetric("Scene registration", formattedSceneRegistrationLatency)
                        qualificationMetric("Analysis", formattedAnalysisRate)
                        qualificationMetric("Thermal", camera.qualificationThermalLevel.rawValue.capitalized)
                    }
                }

                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 20) {
                        qualificationMetric("Dropped", "\(camera.droppedFrames)")
                        qualificationMetric("Pose fail", "\(camera.analysisFailures)")
                        qualificationMetric("Scene fail", "\(camera.sceneRegistrationFailures)")
                        qualificationMetric("Samples", "\(camera.qualification.samples.count)")
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        qualificationMetric("Dropped frames", "\(camera.droppedFrames)")
                        qualificationMetric("Analysis failures", "\(camera.analysisFailures)")
                        qualificationMetric("Scene registration failures", "\(camera.sceneRegistrationFailures)")
                        qualificationMetric("Stored samples", "\(camera.qualification.samples.count)")
                    }
                }

                if let delta = camera.phoneOrientation.latestDeltaDegrees {
                    qualificationMetric("Orientation delta", String(format: "%.2f°", delta))
                }
                if let shift = camera.sceneTranslation.latestShiftFraction {
                    qualificationMetric("Background shift", String(format: "%.3f%%", shift * 100))
                }
                if let scale = camera.sceneTranslation.latestScaleFraction {
                    qualificationMetric("Global homography scale", String(format: "%.3f%%", scale * 100))
                }

                qualificationMetric(
                    "Scale measurement",
                    camera.sceneTranslation.latestScaleMeasurementAvailable ? "Available" : "Unavailable"
                )

                Button {
                    qualificationDocument = QualificationReportDocument(
                        text: camera.qualificationReportJSON()
                    )
                    qualificationExportFilename = makeQualificationFilename()
                    showingQualificationExporter = true
                } label: {
                    Label("Save JSON qualification report", systemImage: "doc.badge.arrow.up")
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("saveLiveQualificationReport")

                Divider()

                debugCaptureControls

                Text("The JSON report is content-free engineering evidence. Debug capture is separately opt-in and local-only; its exported package intentionally contains the recorded workout video for reproducible offline evaluation. No microphone audio is recorded.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 10)
        } label: {
            Label("Device qualification", systemImage: "gauge.with.dots.needle.50percent")
                .font(.subheadline.weight(.semibold))
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    @ViewBuilder
    private var debugCaptureControls: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Debug session capture")
                .font(.subheadline.weight(.semibold))

            switch camera.debugCaptureState {
            case .unavailable:
                Text("Debug movie capture is unavailable for this camera configuration.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

            case .idle:
                Button {
                    _ = camera.startDebugCapture()
                } label: {
                    Label("Start local debug capture", systemImage: "record.circle")
                }
                .buttonStyle(.bordered)
                .disabled(!camera.canStartDebugCapture)
                .accessibilityIdentifier("startDebugSessionCapture")

                if camera.currentBar != nil {
                    Text("Starting debug capture will reset the bar and reacquire it inside the recording.")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }

                Text("Start before bar calibration to preserve setup plus the full set. Exercise and tracking side stay fixed until recording stops. The capture stays on this device until you explicitly export it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

            case .starting:
                Label("Starting debug recording…", systemImage: "record.circle")
                    .font(.caption)
                    .foregroundStyle(.red)
                Button("Stop capture") { camera.stopDebugCapture() }
                    .buttonStyle(.bordered)

            case .recording:
                Label("Recording debug session", systemImage: "record.circle.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.red)
                Button("Stop capture") { camera.stopDebugCapture() }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("stopDebugSessionCapture")

            case .stopping:
                ProgressView("Finalizing debug movie…")
                    .font(.caption)

            case .ready:
                Button {
                    prepareDebugSessionExport()
                } label: {
                    if preparingDebugSessionExport {
                        ProgressView()
                    } else {
                        Label("Export debug session", systemImage: "archivebox")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(preparingDebugSessionExport)
                .accessibilityIdentifier("exportDebugSession")

                Button("Discard local capture", role: .destructive) {
                    camera.discardCompletedDebugCapture()
                }
                .font(.caption)
                .disabled(preparingDebugSessionExport)

            case .failed(let message):
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                Button("Discard and retry") {
                    camera.discardCompletedDebugCapture()
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private func prepareDebugSessionExport() {
        guard let capture = camera.completedDebugCapture,
              !preparingDebugSessionExport
        else { return }

        preparingDebugSessionExport = true
        let videoURL = capture.videoURL
        let sessionJSON = capture.sessionJSON
        let qualificationJSON = capture.qualificationJSON

        Task {
            do {
                let document = try await Task.detached(priority: .utility) {
                    try DebugSessionPackageDocument(
                        videoURL: videoURL,
                        sessionJSON: sessionJSON,
                        qualificationJSON: qualificationJSON
                    )
                }.value
                debugSessionDocument = document
                debugSessionExportFilename = makeDebugSessionFilename()
                preparingDebugSessionExport = false
                showingDebugSessionExporter = true
            } catch {
                preparingDebugSessionExport = false
                debugSessionExportErrorMessage = error.localizedDescription
                showingDebugSessionExportError = true
            }
        }
    }

    @ViewBuilder
    private func qualificationMetric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var formattedVisionLatency: String {
        guard let milliseconds = camera.lastProcessingMilliseconds else { return "—" }
        return String(format: "%.1f ms", milliseconds)
    }

    private var formattedSceneRegistrationLatency: String {
        guard let milliseconds = camera.lastSceneRegistrationMilliseconds else { return "—" }
        return String(format: "%.1f ms", milliseconds)
    }

    private var formattedAnalysisRate: String {
        guard let start = camera.qualification.startedUptimeSeconds else { return "—" }
        let duration = max(0, ProcessInfo.processInfo.systemUptime - start)
        guard duration > 0 else { return "—" }
        return String(format: "%.1f fps", Double(camera.analyzedFrames) / duration)
    }

    private func makeQualificationFilename() -> String {
        "HangInThere-live-qualification-\(exportTimestamp())"
    }

    private func makeDebugSessionFilename() -> String {
        "HangInThere-debug-session-\(exportTimestamp()).hangdebug"
    }

    private func exportTimestamp() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: Date())
    }

    private var phoneStabilityDetail: String {
        switch camera.phoneOrientation.state {
        case .unavailable:
            return "Device-motion monitoring is unavailable. Live set start is blocked."
        case .uncalibrated:
            return camera.motionSampleAvailable
                ? "Ready to lock orientation when the bar is calibrated."
                : "Waiting for device-motion data."
        case .stable:
            if let delta = camera.phoneOrientation.latestDeltaDegrees {
                return String(format: "Orientation monitored · %.1f° from calibration.", delta)
            }
            return "Orientation monitored from bar calibration."
        case .moved:
            return "Phone rotated after calibration. Set the bar again."
        }
    }

    private var phoneStabilitySymbol: String {
        switch camera.phoneOrientation.state {
        case .stable: "checkmark.circle.fill"
        case .moved: "exclamationmark.triangle.fill"
        case .unavailable: "xmark.circle.fill"
        case .uncalibrated: camera.motionSampleAvailable ? "iphone.gen3" : "hourglass"
        }
    }

    private var sceneStabilityDetail: String {
        switch camera.sceneTranslation.state {
        case .uncalibrated:
            return "Set the bar to capture a static-background reference."
        case .calibrating:
            if camera.sceneTranslation.latestConsensusPatches > 0 {
                return "Checking background alignment before Start."
            }
            return "Waiting for at least two peripheral background patches to agree."
        case .stable:
            let shift = (camera.sceneTranslation.latestShiftFraction ?? 0) * 100
            let scale = (camera.sceneTranslation.latestScaleFraction ?? 0) * 100
            return String(
                format: "Background aligned · shift %.2f%% · radial scale %.2f%%.",
                shift,
                scale
            )
        case .moved:
            if camera.sceneTranslation.movementKind == .scale {
                return "Background expanded/contracted after calibration. Set the bar again."
            }
            return "Static background translated after calibration. Set the bar again."
        }
    }

    private var sceneStabilitySymbol: String {
        switch camera.sceneTranslation.state {
        case .stable: "checkmark.circle.fill"
        case .moved: "exclamationmark.triangle.fill"
        case .calibrating: "viewfinder"
        case .uncalibrated: "camera.metering.center.weighted"
        }
    }

    private var framingStatusSymbol: String {
        switch camera.framing.state {
        case .ready: "checkmark.circle.fill"
        case .waitingForFrame: "hourglass"
        case .noPerson: "person.crop.circle.badge.questionmark"
        case .multiplePeople: "person.2.fill"
        case .selectedArmHidden, .selectedArmUnclear: "figure.arms.open"
        case .analysisUnavailable: "exclamationmark.triangle.fill"
        }
    }

    private func setupRow(_ title: String, detail: String, symbol: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .frame(width: 22)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}
