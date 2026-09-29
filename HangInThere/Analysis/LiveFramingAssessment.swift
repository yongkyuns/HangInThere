import Foundation

// Live framing readiness is deliberately narrower than workout readiness.
// It verifies that exactly one athlete and the selected shoulder/elbow/wrist
// chain are measurable in the current frame. It does NOT verify apparatus,
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

    init(state: State = .waitingForFrame) {
        self.state = state
    }

    init(pose: PoseResult, side: ArmMeasurement.Side) {
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
        guard arm.estimate != nil else {
            switch arm.unavailableReason {
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
        case .ready: "Athlete and selected arm visible"
        case .noPerson: "Step into frame"
        case .multiplePeople: "Keep one athlete in frame"
        case .selectedArmHidden: "Show the selected arm"
        case .selectedArmUnclear: "Make the selected arm clearer"
        case .analysisUnavailable: "Framing check unavailable"
        }
    }

    var detail: String {
        switch state {
        case .waitingForFrame:
            "Waiting for the first analyzed camera frame."
        case .ready:
            "One athlete and the selected shoulder, elbow, and wrist are measurable."
        case .noPerson:
            "Move into the camera view before continuing setup."
        case .multiplePeople:
            "The first live-workout profile supports one athlete at a time."
        case .selectedArmHidden:
            "Reposition the phone or athlete so the selected shoulder, elbow, and wrist are visible."
        case .selectedArmUnclear:
            "Move closer, improve lighting, or adjust the view so the selected arm can be measured reliably."
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
