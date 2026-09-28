import Foundation
import Testing
@testable import HangInThere

struct PhoneOrientationStabilityTests {
    @Test func quaternionSignRepresentsSameOrientation() throws {
        let q = try #require(PhoneOrientationStability.Quaternion(x: 0.1, y: 0.2, z: 0.3, w: 0.9))
        let negative = try #require(PhoneOrientationStability.Quaternion(x: -0.1, y: -0.2, z: -0.3, w: -0.9))
        #expect(q.angularDistanceDegrees(to: negative) < 1e-9)
    }

    @Test func smallOrientationNoiseStaysStable() throws {
        var monitor = PhoneOrientationStability()
        let baseline = try #require(quaternion(degrees: 0))
        #expect(monitor.calibrate(baseline, timestamp: 10))

        for (time, degrees) in [(10.1, 0.4), (10.2, 0.8), (10.3, 1.0), (10.4, 0.5)] {
            monitor.observe(try #require(quaternion(degrees: degrees)), timestamp: time)
        }

        #expect(monitor.state == .stable)
        #expect((monitor.maximumDeltaDegrees) < PhoneOrientationStability.movementThresholdDegrees)
    }

    @Test func briefThresholdCrossingDoesNotInvalidateCalibration() throws {
        var monitor = PhoneOrientationStability()
        #expect(monitor.calibrate(try #require(quaternion(degrees: 0)), timestamp: 0))

        monitor.observe(try #require(quaternion(degrees: 2.0)), timestamp: 0.10)
        monitor.observe(try #require(quaternion(degrees: 2.0)), timestamp: 0.20)
        #expect(monitor.state == .stable)

        monitor.observe(try #require(quaternion(degrees: 0.5)), timestamp: 0.25)
        #expect(monitor.state == .stable)
    }

    @Test func sustainedRotationInvalidatesCalibration() throws {
        var monitor = PhoneOrientationStability()
        #expect(monitor.calibrate(try #require(quaternion(degrees: 0)), timestamp: 0))

        monitor.observe(try #require(quaternion(degrees: 2.0)), timestamp: 0.10)
        monitor.observe(try #require(quaternion(degrees: 2.1)), timestamp: 0.25)
        #expect(monitor.state == .stable)

        monitor.observe(try #require(quaternion(degrees: 2.2)), timestamp: 0.36)
        #expect(monitor.state == .moved)
        #expect((monitor.latestDeltaDegrees ?? 0) > PhoneOrientationStability.movementThresholdDegrees)
    }

    @Test func recalibrationClearsMovedState() throws {
        var monitor = PhoneOrientationStability()
        #expect(monitor.calibrate(try #require(quaternion(degrees: 0)), timestamp: 0))
        monitor.observe(try #require(quaternion(degrees: 3)), timestamp: 0.1)
        monitor.observe(try #require(quaternion(degrees: 3)), timestamp: 0.4)
        #expect(monitor.state == .moved)

        #expect(monitor.calibrate(try #require(quaternion(degrees: 8)), timestamp: 1))
        #expect(monitor.state == .stable)
        #expect(monitor.latestDeltaDegrees == 0)
    }

    @Test func staleMotionTimestampCannotAdvanceDwell() throws {
        var monitor = PhoneOrientationStability()
        #expect(monitor.calibrate(try #require(quaternion(degrees: 0)), timestamp: 10))
        monitor.observe(try #require(quaternion(degrees: 3)), timestamp: 10.1)
        monitor.observe(try #require(quaternion(degrees: 3)), timestamp: 9)
        monitor.observe(try #require(quaternion(degrees: 3)), timestamp: 10.2)
        #expect(monitor.state == .stable)
    }

    private func quaternion(degrees: Double) -> PhoneOrientationStability.Quaternion? {
        let half = degrees * .pi / 360
        return PhoneOrientationStability.Quaternion(
            x: 0,
            y: sin(half),
            z: 0,
            w: cos(half)
        )
    }
}
