import Foundation
import WorkoutCore

// Display guidance only. Never supplies joints, pauses the counter or changes
// rep validity. Use source time so repeated frames cannot earn a warning.
struct LiveArmVisibilityFeedback: Equatable, Sendable {
    static let sustainedSeconds = 1.0
    static let title = "SHOW YOUR ARMS"
    static let instruction = "Move camera so both arms are visible"
    static let detail = "Keep shoulders, elbows and wrists clear of your head and torso. Some reps may be missed while arm tracking is unclear."
    private var lastSeconds: Double?
    private var unavailableSince: Double?
    private(set) var needsRepositioning = false

    mutating func reset() { self = Self() }

    mutating func observe(_ pose: PoseResult, running: Bool, side: ArmMeasurement.Side,
                          usesBothArms: Bool, requiresBothArms: Bool = false) {
        guard running, pose.imageSize.isValid, pose.people.count == 1 else {
            reset()
            return
        }
        let seconds = pose.timestamp.seconds
        guard seconds.isFinite, lastSeconds.map({ seconds > $0 }) ?? true else {
            unavailableSince = nil
            needsRepositioning = false
            return
        }
        if let lastSeconds, seconds - lastSeconds > ExerciseCounter.maximumGapSeconds {
            unavailableSince = nil
            needsRepositioning = false
        }
        lastSeconds = seconds
        let framing = LiveFramingAssessment(pose: pose, side: side, usesBothArms: usesBothArms, requiresBothArms: requiresBothArms)
        guard framing.state == .selectedArmHidden || framing.state == .selectedArmUnclear else {
            unavailableSince = nil
            needsRepositioning = false
            return
        }
        if unavailableSince == nil { unavailableSince = seconds }
        needsRepositioning = seconds - unavailableSince! >= Self.sustainedSeconds
    }
}

// Live framing readiness is deliberately narrower than workout readiness.
// It verifies exactly one athlete and usable shoulder/elbow/wrist chains
// (both when available for pull-ups; the selected arm for dips). It does NOT verify apparatus,
// camera stability, exercise identity, rep validity, or form.
struct LiveFramingAssessment: Equatable, Sendable {
    enum State: String, Equatable, Sendable {
        case waitingForFrame
        case ready
        case noPerson
        case multiplePeople
        case selectedArmHidden
        case selectedArmUnclear
        case analysisUnavailable

        var isReady: Bool { self == .ready }
    }

    let state: State
    let usesBothArms: Bool
    let requiresBothArms: Bool

    init(state: State = .waitingForFrame, usesBothArms: Bool = false) {
        self.state = state
        self.usesBothArms = usesBothArms
        self.requiresBothArms = false
    }

    init(pose: PoseResult, side: ArmMeasurement.Side, usesBothArms: Bool = false, requiresBothArms: Bool = false) {
        self.usesBothArms = usesBothArms || requiresBothArms
        self.requiresBothArms = requiresBothArms
        guard pose.imageSize.isValid else {
            state = .analysisUnavailable
            return
        }
        guard !pose.people.isEmpty else {
            state = .noPerson
            return
        }
        guard pose.people.count == 1 else {
            state = .multiplePeople
            return
        }

        let arm = ArmMeasurement(pose: pose, side: side)
        let bilateral = PullUpArmMeasurement(pose: pose)
        if requiresBothArms, bilateral.arms.count == 1 { state = .selectedArmHidden; return }
        let usable = requiresBothArms ? bilateral.arms.count == 2 : usesBothArms ? !bilateral.arms.isEmpty : arm.estimate != nil
        guard usable else {
            switch usesBothArms || requiresBothArms ? bilateral.unavailableReason : arm.unavailableReason {
            case .missingJoint, .noPerson:
                state = .selectedArmHidden
            case .lowConfidence, .shortProjectedSegment:
                state = .selectedArmUnclear
            case .multiplePeople:
                state = .multiplePeople
            case .invalidImageSize, .duplicateJoint, .invalidJoint, nil:
                state = .analysisUnavailable
            }
            return
        }

        state = .ready
    }

    var title: String {
        switch state {
        case .waitingForFrame: "Checking framing"
        case .ready: usesBothArms ? "Athlete and usable arms visible" : "Athlete and selected arm visible"
        case .noPerson: "Step into frame"
        case .multiplePeople: "Keep one athlete in frame"
        case .selectedArmHidden: usesBothArms ? "Show your arms" : "Show the selected arm"
        case .selectedArmUnclear: usesBothArms ? "Make your arms clearer" : "Make the selected arm clearer"
        case .analysisUnavailable: "Framing check unavailable"
        }
    }

    var detail: String {
        switch state {
        case .waitingForFrame:
            "Waiting for the first analyzed camera frame."
        case .ready:
            requiresBothArms ? "Both shoulders, elbows and wrists must remain measurable for dip counting."
                : usesBothArms ? "Both arms are used when measurable; one can continue if the other is obscured."
                : "One athlete and the selected shoulder, elbow, and wrist are measurable."
        case .noPerson:
            "Move into the camera view before continuing setup."
        case .multiplePeople:
            "The first live-workout profile supports one athlete at a time."
        case .selectedArmHidden:
            requiresBothArms ? "Keep both shoulders, elbows and wrists visible."
                : usesBothArms ? "Keep at least one shoulder, elbow, and wrist visible."
                : "Reposition the phone or athlete so the selected shoulder, elbow, and wrist are visible."
        case .selectedArmUnclear:
            usesBothArms ? "Move closer, improve lighting, or adjust the view so your arms can be measured reliably."
                : "Move closer, improve lighting, or adjust the view so the selected arm can be measured reliably."
        case .analysisUnavailable:
            "Live pose analysis could not produce a usable framing result."
        }
    }
}

/// Prospective controlled-capture profile. Gravity measures optical-axis tilt,
/// not camera height, athlete orientation or visibility. Those need placement
/// guidance and separate framing checks. The 20-degree limit is an initial
/// engineering constraint, not a field-validated accuracy boundary.
struct LiveCameraPlacement: Equatable, Sendable {
    static let policyVersion = 1
    static let maximumTiltDegrees = 20.0
    let tiltDegrees: Double?

    init(tiltDegrees: Double? = nil) {
        self.tiltDegrees = tiltDegrees.flatMap { $0.isFinite && (0...90).contains($0) ? $0 : nil }
    }

    init(gravity: SIMD3<Double>?) {
        guard let gravity else { self.init(tiltDegrees: nil); return }
        self.init(tiltDegrees: WorkoutCameraPlacementBridge().tiltDegrees(x: gravity.x, y: gravity.y, z: gravity.z)?.doubleValue)
    }

    var isUsable: Bool { tiltDegrees.map { WorkoutCameraPlacementBridge().isUsable(tiltDegrees: $0) } ?? false }
    var title: String { tiltDegrees == nil ? "CHECK PHONE ANGLE" : isUsable ? "PHONE PLACEMENT" : "LEVEL YOUR PHONE" }
    var instruction: String { "Place at chest / shoulder height. Aim level from the front or rear." }
    var diagnostic: String {
        tiltDegrees.map { String(format: "Camera tilt %.0f° · limit %.0f°", $0, Self.maximumTiltDegrees) }
            ?? "Waiting for a fresh phone angle measurement"
    }
}

struct LiveSetupReadiness: Equatable, Sendable {
    enum State: String, Equatable, Sendable {
        case cameraUnavailable
        case cameraPlacementNeeded
        case framingIncomplete
        case exerciseIdentityNeeded
        case barReferenceNeeded
        case cameraCalibrationNeeded
        case phoneStabilityNeeded
        case sceneStabilityNeeded
        case startingPoseNeeded
        case ready

        var isReady: Bool { self == .ready }
    }

    let state: State

    init(
        cameraReady: Bool,
        framing: LiveFramingAssessment.State,
        barConfirmed: Bool,
        phoneStable: Bool,
        sceneStable: Bool,
        bodyCameraCalibrated: Bool? = nil,
        startingPose: Bool = true,
        placementReady: Bool = true,
        identityResolved: Bool = true
    ) {
        if !cameraReady {
            state = .cameraUnavailable
        } else if !placementReady {
            state = .cameraPlacementNeeded
        } else if !framing.isReady {
            state = .framingIncomplete
        } else if !identityResolved {
            state = .exerciseIdentityNeeded
        } else if bodyCameraCalibrated == false {
            state = .cameraCalibrationNeeded
        } else if bodyCameraCalibrated == nil && !barConfirmed {
            state = .barReferenceNeeded
        } else if !phoneStable {
            state = .phoneStabilityNeeded
        } else if !sceneStable {
            state = .sceneStabilityNeeded
        } else if !startingPose {
            state = .startingPoseNeeded
        } else {
            state = .ready
        }
    }
}

// Presentation of existing live gates. This never earns readiness or changes
// acquisition/counting policy. Dependent checks stay pending until measurable.
struct LiveReadinessChecklist: Equatable, Sendable {
    enum Status: Equatable, Sendable { case passed, waiting, blocked }
    struct Check: Equatable, Sendable, Identifiable {
        let id: String
        let title: String
        let status: Status
        let value: String
        let detail: String
    }
    let checks: [Check]
    let summary: String
    let allPassed: Bool
    let movementEnvironmentReady: Bool
    func showsPreparedMovement(phase: String, usable: Bool, sourceAccepted: Bool) -> Bool {
        movementEnvironmentReady && usable && sourceAccepted &&
            (phase == "ready" ? allPassed : ["outbound", "returning"].contains(phase))
    }
    let actionTitle: String
    let actionDetail: String

    init(cameraReady: Bool, frameFresh: Bool, framing: LiveFramingAssessment,
         exerciseTitle: String?, armDescription: String, barConfirmed: Bool,
         acquisition: AutomaticPullUpBar.State, stability: AutomaticReferenceStability.Diagnostic? = nil,
         motionAvailable: Bool, phoneStable: Bool, sceneStable: Bool,
         startingPose: Bool, paused: Bool, handsFree: Bool, setupBusy: Bool,
         bodyCameraCalibrated: Bool? = nil, placement: LiveCameraPlacement? = nil, pullUpMotion: Bool = false) {
        let bodyCamera = bodyCameraCalibrated != nil
        let bodyDip = bodyCamera && !pullUpMotion
        let referenceReady = bodyCameraCalibrated ?? barConfirmed
        let current = cameraReady && frameFresh
        func check(_ id: String, _ title: String, _ status: Status, _ value: String, _ detail: String) -> Check {
            Check(id: id, title: title, status: status, value: value, detail: detail)
        }
        let feed = check("feed", "Camera / pose feed", current ? .passed : .blocked,
            current ? "Live" : cameraReady ? "Waiting for pose" : "Unavailable",
            cameraReady ? "Waiting for a fresh analyzed camera frame." : "Open or resume the camera to check setup.")
        let arms = check("arms", "Athlete / arms", !current ? .waiting : framing.state.isReady ? .passed : .blocked,
            !current ? "Waiting for frame" : framing.state.isReady ? armDescription : framing.title,
            framing.detail)
        let identity = check("exercise", "Exercise", !current ? .waiting : exerciseTitle != nil ? .passed : .blocked,
            !current ? "Waiting for frame" : exerciseTitle ?? "Not identified",
            "Keep both arms visible and your hands steady at the support, or select the exercise in settings.")
        let railStatus: Status = !current || (!referenceReady && !motionAvailable) ? .waiting : referenceReady ? .passed : .blocked
        let railValue: String
        let railDetail: String
        if bodyCamera {
            railValue = referenceReady ? "Camera calibrated" : "Waiting for calibration"
            railDetail = "Camera calibration uses the background and phone motion. Apparatus detection is not required."
        } else if barConfirmed {
            railValue = "Reference set"; railDetail = "The bar or rail reference has been acquired."
        } else {
            switch acquisition {
            case .lookingForGrip:
                railValue = "Need grip position"; railDetail = "Keep your wrists and the bar or rails beside them visible."
            case .findingEdge:
                railValue = "Looking for edges"; railDetail = "The app has not found a usable bar or rail edge beside your hands."
            case .ambiguous:
                railValue = "Support unclear"; railDetail = "The app cannot choose a clear support reference. Keep the grips and nearby rails visible."
            case .holding:
                railValue = "Checking reference"
                if let stability {
                    railDetail = String(format: "Reference check: %d observations, %.1f / %.1f seconds. Keep your hands and phone steady.",
                        stability.observations, stability.stableSeconds, AutomaticReferenceStability.minimumStableSeconds)
                } else { railDetail = "A support candidate was found. Keep your hands and phone steady while it is checked." }
            case .locked:
                railValue = "Reference unavailable"; railDetail = "The previous support reference is no longer usable. Reacquire the bar or rails."
            }
        }
        let rails = check("rails", bodyCamera ? "Camera calibration" : "Bar / rails", railStatus,
            !current ? "Waiting for frame" : !referenceReady && !motionAvailable ? "Needs phone motion" : railValue,
            !referenceReady && !motionAvailable ? "Calibration is waiting for phone motion measurements." : railDetail)
        let phoneStatus: Status = !current ? .waiting : !motionAvailable ? .blocked : !referenceReady ? .waiting : phoneStable ? .passed : .blocked
        let phone = check("phone", "Phone stability", phoneStatus,
            !current ? "Waiting for frame" : !motionAvailable ? "Motion unavailable" : !referenceReady ? "Needs calibration" : phoneStable ? "Stable" : "Not stable",
            motionAvailable ? "Keep the phone fixed after camera calibration." : "Phone motion measurements are unavailable. Check camera setup.")
        let background = check("scene", "Background stability", !current || !referenceReady ? .waiting : sceneStable ? .passed : .blocked,
            !current ? "Waiting for frame" : !referenceReady ? "Needs calibration" : sceneStable ? "Stable" : "Not confirmed",
            "Waiting for a current background stability measurement. Keep the phone fixed.")
        let posture = check("pose", "Starting position", !current || !referenceReady || exerciseTitle == nil ? .waiting : startingPose ? .passed : .blocked,
            !current ? "Waiting for frame" : !referenceReady ? "Needs calibration" : exerciseTitle == nil ? "Needs exercise" : startingPose ? "Qualified" : "Hold start pose",
            bodyDip ? "Hold extended top support with both arms visible and hands steady." : pullUpMotion ? "Show both arms below your hands with a steady overhead grip. Begin pulling when ready." : "Show a full hang for pull-ups or extended top support for dips, with wrists near the acquired support.")
        let placementChecks = placement.map { value in
            [check("placement", "Phone placement", !current ? .waiting : value.isUsable ? .passed : .blocked,
                value.diagnostic, value.instruction + " Keep both arms and hands in frame. Camera height is set by you.")]
        } ?? []
        checks = [feed] + placementChecks + [arms, identity, rails, phone, background, posture]
        allPassed = checks.allSatisfy { $0.status == .passed } && !paused && !setupBusy
        // Once the source has witnessed a starting posture, its first excursion
        // must remain visible while it leaves that posture. Every other gate stays.
        movementEnvironmentReady = checks.filter { $0.id != "pose" }.allSatisfy { $0.status == .passed } && !paused && !setupBusy
        if !current { summary = feed.detail }
        else if paused { summary = "Automatic start paused while workout settings are open." }
        else if setupBusy { summary = "Automatic start is waiting for setup or recording preparation to finish." }
        else if let first = checks.first(where: { $0.status == .blocked }) { summary = first.detail }
        else if allPassed { summary = handsFree ? (bodyDip ? "All checks pass. Lower to begin your set." : pullUpMotion ? "All checks pass. Pull up to begin your set." : "All checks pass. Hold your position for automatic start.") : "All checks pass. Tap Start set." }
        else { summary = "Waiting for the remaining setup checks." }

        if !current {
            actionTitle = cameraReady ? "WAITING FOR POSE" : "CHECK CAMERA"
            actionDetail = cameraReady ? "Keep yourself in view while camera analysis resumes." : "Open or resume the camera."
        } else if paused {
            actionTitle = "START PAUSED"; actionDetail = "Close workout settings to resume automatic start."
        } else if setupBusy {
            actionTitle = "SETTING UP"; actionDetail = "Wait for setup or recording preparation to finish."
        } else if let first = checks.first(where: { $0.status == .blocked }) {
            switch first.id {
            case "placement":
                actionTitle = placement?.title ?? "CHECK PHONE ANGLE"
                actionDetail = placement?.instruction ?? "Place the phone at chest / shoulder height and aim level."
            case "arms":
                actionTitle = framing.state == .noPerson ? "STEP INTO FRAME" : framing.state == .multiplePeople ? "ONE ATHLETE IN VIEW" : "SHOW YOUR ARMS"
                actionDetail = framing.state == .multiplePeople ? "Keep other people outside the camera view." : "Keep your shoulders, elbows and wrists clearly visible."
            case "exercise":
                actionTitle = "IDENTIFYING EXERCISE"; actionDetail = pullUpMotion && referenceReady ? "Keep your hands steady overhead. A controlled pull will begin your set." : "Keep both arms visible and your hands steady at the support."
            case "rails":
                actionTitle = bodyCamera ? "KEEP PHONE STILL" : acquisition == .holding ? "HOLD STEADY" : acquisition == .ambiguous ? "SUPPORT UNCLEAR" : "SHOW THE BAR / RAILS"
                actionDetail = bodyCamera ? "Show both arms while the camera calibrates against the background." : acquisition == .holding ? "Keep your hands and phone still while the support is checked." : "Keep your hands and the nearby bar or rails clearly visible."
            case "phone":
                actionTitle = motionAvailable ? "KEEP PHONE STILL" : "MOTION UNAVAILABLE"
                actionDetail = motionAvailable ? "Keep the phone fixed after setup." : "Phone motion measurements are unavailable. Check camera setup."
            case "scene":
                actionTitle = "KEEP PHONE STILL"; actionDetail = "Keep the phone fixed while the background is checked."
            case "pose":
                actionTitle = pullUpMotion ? "SHOW START POSITION" : "HOLD START POSITION"
                actionDetail = pullUpMotion ? "Show both arms below your hands with a steady overhead grip." : "Hold a full hang or extended top support, with hands at the support."
            default:
                actionTitle = "CHECK SETUP"; actionDetail = summary
            }
        } else {
            actionTitle = allPassed ? "READY TO START" : "CHECKING SETUP"
            actionDetail = allPassed ? (handsFree ? (bodyDip ? "Ready. Lower to begin your set. Crossed legs are optional." : pullUpMotion ? "Pull up when ready. Movement begins your set automatically." : "Hold your position. Tracking starts automatically.") : "Tap Start set when ready.") : summary
        }
    }
}
