#if os(iOS)
import AVFoundation
import Testing
@testable import HangInThere

@MainActor @Suite(.serialized)
struct LiveCameraSelectionTests {
    @Test func automaticIdentityChangesCounterAndRailRoleWithoutStartingOrInventingApparatus() async {
        let controller = LiveCameraPreviewController()
        controller.configureWorkout(selection: .automatic, side: .right)
        #expect(controller.resolvedExercise == nil)
        #expect(controller.workoutTitle == "Auto · detecting")
        for t in [0.0, 0.2, 0.4, 0.7] {
            controller.observeExerciseSelection(AutomaticDipRailsTests().pose(t))
        }
        #expect(controller.resolvedExercise == .dip)
        #expect(controller.liveSet.exercise == .dip)
        #expect(controller.barRole == .rightDipRail)
        #expect(controller.workoutTitle == "Dips detected")
        #expect(controller.currentBar == nil)
        #expect(!controller.startSet())
        #expect(controller.liveSet.phase == .idle)
        #expect(controller.handsFreeStart.state == .waiting)
        controller.observeExerciseSelection(AutomaticPullUpBarTests().pose(0.8))
        #expect(controller.resolvedExercise == nil)
        for t in [1.0, 1.2, 1.4, 1.7] {
            controller.observeExerciseSelection(AutomaticPullUpBarTests().pose(t))
        }
        #expect(controller.resolvedExercise == .pullUp)
        #expect(controller.liveSet.exercise == .pullUp)
        #expect(controller.barRole == .pullUpGrip)
        #expect(!controller.session.isRunning)
        #expect(await controller.selectCamera(.front))
        #expect(controller.resolvedExercise == nil)
        #expect(controller.exerciseSelection == .automatic)
    }

    @Test func manualExerciseSelectionOverridesPoseAndReturningToAutoRequiresNewEvidence() {
        let controller = LiveCameraPreviewController()
        controller.configureWorkout(exercise: .pullUp, side: .left)
        for t in [0.0, 0.2, 0.4, 0.7] {
            controller.observeExerciseSelection(AutomaticDipRailsTests().pose(t))
        }
        #expect(controller.resolvedExercise == .pullUp)
        #expect(controller.exerciseSelection == .pullUp)
        controller.configureWorkout(selection: .automatic, side: .left)
        #expect(controller.resolvedExercise == nil)
        for t in [1.0, 1.2, 1.4, 1.7] {
            controller.observeExerciseSelection(AutomaticDipRailsTests().pose(t))
        }
        #expect(controller.resolvedExercise == .dip)
        controller.configureWorkout(selection: .automatic, side: .right)
        #expect(controller.resolvedExercise == nil)
        #expect(controller.liveSet.side == .right)
    }

    @Test func detailsPauseSurvivesSetupChangesWithoutChangingHandsFreePreference() async {
        let controller = LiveCameraPreviewController()
        controller.setWorkoutDetailsPresented(true)
        #expect(controller.workoutDetailsPresented)
        #expect(controller.handsFreeStartEnabled)
        controller.setHandsFreeStartEnabled(false)
        #expect(await controller.selectCamera(.front))
        controller.configureWorkout(exercise: .dip, side: .right)
        controller.configureWorkout(exercise: .pullUp, side: .left)
        controller.endBarSetup()
        #expect(controller.workoutDetailsPresented)
        #expect(controller.handsFreeStart.state == .waiting)
        controller.setWorkoutDetailsPresented(false)
        #expect(!controller.workoutDetailsPresented)
        #expect(!controller.handsFreeStartEnabled)
        #expect(controller.liveSet.phase == .idle)
    }

    @Test func handsFreePreferenceSurvivesCameraAndExerciseChanges() async {
        let controller = LiveCameraPreviewController()
        #expect(controller.handsFreeStartEnabled)
        #expect(controller.handsFreeStart.state == .waiting)
        #expect(!controller.startSet(), "Hands-free mode still requires valid camera/bar setup.")
        controller.setHandsFreeStartEnabled(false)
        #expect(await controller.selectCamera(.front))
        #expect(!controller.handsFreeStartEnabled)
        controller.configureWorkout(exercise: .dip, side: .right)
        controller.configureWorkout(exercise: .pullUp, side: .left)
        #expect(!controller.handsFreeStartEnabled)
        controller.endBarSetup()
        #expect(controller.handsFreeStart.state == .waiting)
        #expect(!controller.handsFreeStartEnabled)
        controller.setHandsFreeStartEnabled(true)
        #expect(controller.handsFreeStartEnabled)
        #expect(controller.liveSet.phase == .idle)
        #expect(!controller.session.isRunning)
    }

    @Test func selectingFrontBeforeStartDoesNotStartOrAuthorizeCapture() async {
        let controller = LiveCameraPreviewController()
        let authorization = AVCaptureDevice.authorizationStatus(for: .video)
        #expect(controller.cameraPosition == .rear)
        #expect(await controller.selectCamera(.front))
        #expect(controller.cameraPosition.capturePosition == .front)
        #expect(controller.state == .idle)
        #expect(!controller.session.isRunning)
        #expect(controller.session.inputs.isEmpty)
        #expect(controller.session.outputs.isEmpty)
        #expect(controller.currentBar == nil)
        #expect(controller.latestImageSize == nil)
        #expect(!controller.setupReadiness.state.isReady)
        #expect(AVCaptureDevice.authorizationStatus(for: .video) == authorization)
    }

    @Test func cameraSelectionRoundTripRetainsWorkoutConfiguration() async {
        let controller = LiveCameraPreviewController()
        controller.configureWorkout(exercise: .dip, side: .right)
        #expect(await controller.selectCamera(.front))
        #expect(await controller.selectCamera(.rear))
        #expect(controller.cameraPosition.capturePosition == .back)
        #expect(controller.liveSet.exercise == .dip)
        #expect(controller.liveSet.side == .right)
        #expect(controller.liveSet.phase == .idle)
        #expect(controller.barRole == .rightDipRail)
        #expect(controller.canSwitchCamera)
        #expect(!controller.isSwitchingCamera)
        #expect(controller.session.inputs.isEmpty)
    }

    @Test func selectingSameCameraPreservesSessionConfiguration() async {
        let controller = LiveCameraPreviewController()
        let output = AVCaptureVideoDataOutput()
        // An unchanged selection must not tear down a session.
        controller.session.addOutput(output)
        #expect(await controller.selectCamera(.rear))
        #expect(controller.session.outputs.contains { $0 === output })
        // A changed selection removes stale outputs before a later start.
        #expect(await controller.selectCamera(.front))
        #expect(controller.session.outputs.isEmpty)
        #expect(controller.state == .idle)
    }

    @Test func portraitConnectionDisablesAutomaticMirroring() throws {
        let connection = AVCaptureConnection(
            inputPorts: [], output: AVCaptureVideoDataOutput()
        )
        connection.automaticallyAdjustsVideoMirroring = true
        try LiveCameraConnectionConfiguration.apply(to: connection)
        #expect(connection.videoRotationAngle == 90)
        #expect(!connection.automaticallyAdjustsVideoMirroring)
        if connection.isVideoMirroringSupported {
            #expect(!connection.isVideoMirrored)
        }
    }
}
#endif
