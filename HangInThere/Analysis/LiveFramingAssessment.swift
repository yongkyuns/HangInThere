import Foundation

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

    init(state: State = .waitingForFrame, usesBothArms: Bool = false) {
        self.state = state
        self.usesBothArms = usesBothArms
    }

    init(pose: PoseResult, side: ArmMeasurement.Side, usesBothArms: Bool = false) {
        self.usesBothArms = usesBothArms
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
        let usable = usesBothArms ? !bilateral.arms.isEmpty : arm.estimate != nil
        guard usable else {
            switch usesBothArms ? bilateral.unavailableReason : arm.unavailableReason {
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
            usesBothArms ? "Both arms are used when measurable; one can continue if the other is obscured."
                : "One athlete and the selected shoulder, elbow, and wrist are measurable."
        case .noPerson:
            "Move into the camera view before continuing setup."
        case .multiplePeople:
            "The first live-workout profile supports one athlete at a time."
        case .selectedArmHidden:
            usesBothArms ? "Keep at least one shoulder, elbow, and wrist visible."
                : "Reposition the phone or athlete so the selected shoulder, elbow, and wrist are visible."
        case .selectedArmUnclear:
            usesBothArms ? "Move closer, improve lighting, or adjust the view so your arms can be measured reliably."
                : "Move closer, improve lighting, or adjust the view so the selected arm can be measured reliably."
        case .analysisUnavailable:
            "Live pose analysis could not produce a usable framing result."
        }
    }
}

struct LiveSetupReadiness: Equatable, Sendable {
    enum State: String, Equatable, Sendable {
        case cameraUnavailable
        case framingIncomplete
        case barReferenceNeeded
        case phoneStabilityNeeded
        case sceneStabilityNeeded
        case ready

        var isReady: Bool { self == .ready }
    }

    let state: State

    init(
        cameraReady: Bool,
        framing: LiveFramingAssessment.State,
        barConfirmed: Bool,
        phoneStable: Bool,
        sceneStable: Bool
    ) {
        if !cameraReady {
            state = .cameraUnavailable
        } else if !framing.isReady {
            state = .framingIncomplete
        } else if !barConfirmed {
            state = .barReferenceNeeded
        } else if !phoneStable {
            state = .phoneStabilityNeeded
        } else if !sceneStable {
            state = .sceneStabilityNeeded
        } else {
            state = .ready
        }
    }
}
