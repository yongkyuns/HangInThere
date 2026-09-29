import Foundation
import Testing
@testable import HangInThere

struct PhoneOrientationStabilityTests {
    @Test func quaternionSignRepresentsSameOrientation() {
        let q = quaternion(x: 0.1, y: 0.2, z: 0.3, w: 0.9)
        let negative = quaternion(x: -0.1, y: -0.2, z: -0.3, w: -0.9)
        #expect(q.angularDistanceDegrees(to: negative) < 1e-9)
    }

    @Test func smallOrientationNoiseStaysStable() {
        var monitor = PhoneOrientationStability()
        let calibrated = monitor.calibrate(quaternion(degrees: 0), timestamp: 10)
        #expect(calibrated)

        for (time, degrees) in [(10.1, 0.4), (10.2, 0.8), (10.3, 1.0), (10.4, 0.5)] {
            monitor.observe(quaternion(degrees: degrees), timestamp: time)
        }

        #expect(monitor.state == .stable)
        #expect(monitor.maximumDeltaDegrees < PhoneOrientationStability.movementThresholdDegrees)
    }

    @Test func briefThresholdCrossingDoesNotInvalidateCalibration() {
        var monitor = PhoneOrientationStability()
        let calibrated = monitor.calibrate(quaternion(degrees: 0), timestamp: 0)
        #expect(calibrated)

        monitor.observe(quaternion(degrees: 2.0), timestamp: 0.10)
        monitor.observe(quaternion(degrees: 2.0), timestamp: 0.20)
        #expect(monitor.state == .stable)

        monitor.observe(quaternion(degrees: 0.5), timestamp: 0.25)
        #expect(monitor.state == .stable)
    }

    @Test func sustainedRotationInvalidatesCalibration() {
        var monitor = PhoneOrientationStability()
        let calibrated = monitor.calibrate(quaternion(degrees: 0), timestamp: 0)
        #expect(calibrated)

        monitor.observe(quaternion(degrees: 2.0), timestamp: 0.10)
        monitor.observe(quaternion(degrees: 2.1), timestamp: 0.25)
        #expect(monitor.state == .stable)

        monitor.observe(quaternion(degrees: 2.2), timestamp: 0.36)
        #expect(monitor.state == .moved)
        #expect((monitor.latestDeltaDegrees ?? 0) > PhoneOrientationStability.movementThresholdDegrees)
    }

    @Test func recalibrationClearsMovedState() {
        var monitor = PhoneOrientationStability()
        var calibrated = monitor.calibrate(quaternion(degrees: 0), timestamp: 0)
        #expect(calibrated)

        monitor.observe(quaternion(degrees: 3), timestamp: 0.1)
        monitor.observe(quaternion(degrees: 3), timestamp: 0.4)
        #expect(monitor.state == .moved)

        calibrated = monitor.calibrate(quaternion(degrees: 8), timestamp: 1)
        #expect(calibrated)
        #expect(monitor.state == .stable)
        #expect(monitor.latestDeltaDegrees == 0)
    }

    @Test func staleMotionTimestampCannotAdvanceDwell() {
        var monitor = PhoneOrientationStability()
        let calibrated = monitor.calibrate(quaternion(degrees: 0), timestamp: 10)
        #expect(calibrated)

        monitor.observe(quaternion(degrees: 3), timestamp: 10.1)
        monitor.observe(quaternion(degrees: 3), timestamp: 9)
        monitor.observe(quaternion(degrees: 3), timestamp: 10.2)
        #expect(monitor.state == .stable)
    }

    private func quaternion(degrees: Double) -> PhoneOrientationStability.Quaternion {
        let half = degrees * .pi / 360
        return quaternion(x: 0, y: sin(half), z: 0, w: cos(half))
    }

    private func quaternion(
        x: Double,
        y: Double,
        z: Double,
        w: Double
    ) -> PhoneOrientationStability.Quaternion {
        PhoneOrientationStability.Quaternion(x: x, y: y, z: z, w: w)!
    }
}
