import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct LiveSetupGuide: Equatable, Sendable {
    let exercise: ExerciseCounter.Exercise
    let side: ArmMeasurement.Side

    var selectedArmText: String {
        "\(side.rawValue.capitalized) shoulder, elbow, and wrist"
    }

    var apparatusText: String {
        switch exercise {
        case .pullUp:
            "Keep the gripping bar visible across the selected hand."
        case .dip:
            "Keep the selected dip rail and selected hand visible."
        }
    }

    var bodyText: String {
        switch exercise {
        case .pullUp:
            "Keep the selected arm, torso, hips, and as much of the legs as practical in frame."
        case .dip:
            "Use a side-oriented view with the selected arm, torso, hips, and rail clearly visible."
        }
    }
}

@MainActor
struct LiveSetupView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @State private var camera = LiveCameraPreviewController()
    @State private var exercise: ExerciseCounter.Exercise = .pullUp
    @State private var side: ArmMeasurement.Side = .left
    @State private var barSetup: BarSetupFrame?
    @State private var qualificationDocument: QualificationReportDocument?
    @State private var qualificationExportFilename = "HangInThere-live-qualification"
    @State private var showingQualificationExporter = false
    @State private var showingQualificationExportError = false
    @State private var qualificationExportErrorMessage = ""

    private var guide: LiveSetupGuide {
        LiveSetupGuide(exercise: exercise, side: side)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    intro
                    cameraSurface

                    switch camera.liveSet.phase {
                    case .idle:
                        workoutSelection
                        framingChecklist
                        barCalibrationCard
                        readyStateCard
                    case .running:
                        liveSetCard
                    case .finished:
                        liveResultsCard
                    }

                    scopeNote
                    qualificationDisclosure
                }
                .padding()
            }
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .sheet(item: $barSetup) { setup in
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
            .task {
                camera.configureWorkout(exercise: exercise, side: side)
                await camera.start()
            }
            .onChange(of: exercise) { _, newExercise in
                camera.configureWorkout(exercise: newExercise, side: side)
                barSetup = nil
            }
            .onChange(of: side) { _, newSide in
                camera.configureWorkout(exercise: exercise, side: newSide)
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
            .onDisappear { camera.stop() }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Position the phone")
                .font(.title2.bold())
            Text("Use the rear camera and keep the phone fixed for the full set. Live setup does not use the microphone.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var cameraSurface: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20)
                .fill(.black)

            if camera.isCameraReady {
                LiveCameraPreviewSurface(session: camera.session)
                    .clipShape(RoundedRectangle(cornerRadius: 20))

                if let imageSize = camera.latestImageSize {
                    BarOverlay(imageSize: imageSize, bar: camera.currentBar)
                }

                framingGuide
            } else {
                cameraStatus
                    .padding(24)
            }
        }
        .aspectRatio(9.0 / 16.0, contentMode: .fit)
        .frame(maxWidth: 620)
        .frame(maxWidth: .infinity)
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .stroke(.white.opacity(0.08), lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Rear camera setup preview")
    }

    private var framingGuide: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 28)
                .strokeBorder(
                    .white.opacity(0.82),
                    style: StrokeStyle(lineWidth: 2, dash: [9, 7])
                )
                .padding(.horizontal, 34)
                .padding(.vertical, 26)

            VStack {
                HStack(alignment: .top) {
                    Label(exercise.title, systemImage: "figure.strengthtraining.traditional")
                    Spacer()
                    if camera.liveSet.phase == .running {
                        VStack(alignment: .trailing, spacing: 0) {
                            Text("\(camera.liveSet.observedMovements)")
                                .font(.system(size: 42, weight: .bold, design: .rounded))
                                .monospacedDigit()
                            Text("movements")
                                .font(.caption2.weight(.semibold))
                        }
                    } else {
                        Text(side.rawValue.capitalized + " arm")
                    }
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(10)
                .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 12))

                Spacer()

                Label(
                    camera.liveSet.phase == .running ? liveTrackingTitle : camera.framing.title,
                    systemImage: camera.liveSet.phase == .running ? liveTrackingSymbol : framingStatusSymbol
                )
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.black.opacity(0.55), in: Capsule())
            }
            .padding(12)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
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

            Picker("Exercise", selection: $exercise) {
                ForEach(ExerciseCounter.Exercise.allCases, id: \.rawValue) { exercise in
                    Text(exercise.title).tag(exercise)
                }
            }
            .pickerStyle(.segmented)

            Picker("Tracking side", selection: $side) {
                ForEach(ArmMeasurement.Side.allCases, id: \.rawValue) { side in
                    Text(side.rawValue.capitalized).tag(side)
                }
            }
            .pickerStyle(.segmented)
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var framingChecklist: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Framing")
                .font(.headline)

            setupRow(
                "Rear camera active",
                detail: camera.isCameraReady ? "Ready" : camera.state.title,
                symbol: camera.isCameraReady ? "checkmark.circle.fill" : "circle"
            )
            setupRow(
                "Athlete + selected arm",
                detail: camera.framing.detail,
                symbol: framingStatusSymbol
            )
            setupRow("Selected arm", detail: guide.selectedArmText, symbol: "figure.arms.open")
            setupRow(
                "Apparatus",
                detail: "Visual check only. " + guide.apparatusText,
                symbol: "line.diagonal"
            )
            setupRow("Body position", detail: guide.bodyText, symbol: "viewfinder")
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
                    camera.currentBar == nil ? "Set bar" : "Adjust bar",
                    systemImage: camera.currentBar == nil ? "viewfinder" : "slider.horizontal.3"
                )
                .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(
                !camera.isCameraReady
                    || !camera.framing.state.isReady
                    || !camera.motionSampleAvailable
            )
            .accessibilityIdentifier("liveSetupBar")

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
                    Text(camera.setupReadiness.state.isReady ? "Ready to start" : "Setup not complete")
                        .font(.headline)
                    Text(
                        camera.setupReadiness.state.isReady
                            ? "Camera, selected arm, and fixed bar reference are ready."
                            : readyStateHelp
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)
            }

            if camera.setupReadiness.state.isReady {
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
        case .manual:
            return nil
        }
    }

    private var navigationTitle: String {
        switch camera.liveSet.phase {
        case .idle: "Camera setup"
        case .running: "Live workout"
        case .finished: "Set results"
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
        case "lowConfidence": "Selected arm unclear"
        case "missingJoint": "Selected arm hidden"
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
            return "Keep the selected arm clear and well lit."
        case "missingJoint":
            return "Reposition so the selected shoulder, elbow, and wrist are visible."
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

    private var barCalibrationDetail: String {
        if camera.currentBar != nil {
            return "\(camera.barRole.title) confirmed from a frozen analyzed frame. Phone orientation and static background are monitored from that same calibration."
        }
        if !camera.framing.state.isReady {
            return "Make the selected arm measurable, then freeze a frame and mark the gripping edge."
        }
        if !camera.motionSampleAvailable {
            return "Waiting for device-motion data before the bar can be calibrated."
        }
        return "Freeze the current analyzed frame and confirm the gripping bar or selected dip rail."
    }

    private var readyStateHelp: String {
        if !camera.isCameraReady { return "Start the rear camera." }
        if !camera.framing.state.isReady { return camera.framing.detail }
        if camera.currentBar == nil { return "Confirm the fixed bar reference." }
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

                Text("The report is local engineering evidence only. It contains timing, counters, thermal state, and camera-stability metrics—no video, images, landmarks, filenames, location, or device identifiers.")
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
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return "HangInThere-live-qualification-\(formatter.string(from: Date()))"
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
