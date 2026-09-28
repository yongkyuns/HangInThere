import CoreGraphics
import Foundation
import Testing
@testable import HangInThere

struct StaticSceneRegistrationTests {
    @Test func peripheralVisionRegistrationRecoversKnownTranslation() async throws {
        let referenceImage = try #require(makePatternImage(shiftX: 0, shiftY: 0))
        let movedImage = try #require(makePatternImage(shiftX: 7, shiftY: -5))
        let reference = try #require(
            VisionStaticSceneRegistrationWorker.makeReference(image: referenceImage)
        )

        let worker = VisionStaticSceneRegistrationWorker()
        let motions = try await worker.measure(reference: reference, image: movedImage)

        let magnitudes = motions.compactMap { motion -> Double? in
            guard let dx = motion.dxPixels, let dy = motion.dyPixels else { return nil }
            return hypot(dx, dy)
        }
        let plausible = magnitudes.filter { $0 >= 5 && $0 <= 11 }
        #expect(plausible.count >= 2)

        let stableScales = motions.compactMap(\.scaleFraction).filter {
            $0 < StaticSceneStability.scaleThresholdFraction
        }
        #expect(stableScales.count >= 3)

        var policy = StaticSceneStability()
        let calibrated = policy.calibrate(imageShortSide: 400)
        #expect(calibrated)
        policy.observe(motions, timestamp: 0.10)
        policy.observe(motions, timestamp: 0.40)
        #expect(policy.state == .moved)
        #expect(policy.movementKind == .translation)
    }

    @Test func identicalStaticSceneProducesNearZeroConsensus() async throws {
        let image = try #require(makePatternImage(shiftX: 0, shiftY: 0))
        let reference = try #require(
            VisionStaticSceneRegistrationWorker.makeReference(image: image)
        )

        let worker = VisionStaticSceneRegistrationWorker()
        let motions = try await worker.measure(reference: reference, image: image)

        let nearZeroTranslations = motions.filter { motion in
            guard let dx = motion.dxPixels, let dy = motion.dyPixels else { return false }
            return hypot(dx, dy) < 1
        }
        #expect(nearZeroTranslations.count >= 2)

        let nearZeroScales = motions.compactMap(\.scaleFraction).filter { $0 < 0.005 }
        #expect(nearZeroScales.count >= 3)

        var policy = StaticSceneStability()
        _ = policy.calibrate(imageShortSide: 400)
        policy.observe(motions, timestamp: 0.1)
        #expect(policy.state == .stable)
    }

    @Test func peripheralHomographyRecoversSyntheticScaleSignal() async throws {
        let referenceImage = try #require(makePatternImage(shiftX: 0, shiftY: 0))
        let scaledImage = try #require(makePatternImage(shiftX: 0, shiftY: 0, scale: 1.05))
        let reference = try #require(
            VisionStaticSceneRegistrationWorker.makeReference(image: referenceImage)
        )

        let worker = VisionStaticSceneRegistrationWorker()
        let motions = try await worker.measure(reference: reference, image: scaledImage)

        let scaleMeasurements = motions.compactMap(\.scaleFraction)
        #expect(scaleMeasurements.count >= 3)
        #expect(
            scaleMeasurements.filter {
                $0 >= StaticSceneStability.scaleThresholdFraction
            }.count >= 3
        )

        var policy = StaticSceneStability()
        _ = policy.calibrate(imageSize: .init(width: 400, height: 400))
        policy.observe(motions, timestamp: 0.10)
        policy.observe(motions, timestamp: 0.40)

        #expect(policy.state == .moved)
        #expect(policy.movementKind == .scale)
        #expect((policy.latestScaleFraction ?? 0) >= StaticSceneStability.scaleThresholdFraction)
    }

    private func makePatternImage(
        shiftX: CGFloat,
        shiftY: CGFloat,
        scale: CGFloat = 1
    ) -> CGImage? {
        let width = 400
        let height = 400
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }

        context.setFillColor(CGColor(gray: 0.08, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.saveGState()
        context.translateBy(x: CGFloat(width) / 2 + shiftX, y: CGFloat(height) / 2 + shiftY)
        context.scaleBy(x: scale, y: scale)
        context.translateBy(x: -CGFloat(width) / 2, y: -CGFloat(height) / 2)

        for row in 0..<10 {
            for column in 0..<10 {
                let x = CGFloat(column * 40 + 6)
                let y = CGFloat(row * 40 + 7)
                let value = CGFloat(((row * 17 + column * 29) % 80) + 15) / 100
                context.setFillColor(CGColor(
                    red: value,
                    green: min(1, value + 0.16),
                    blue: max(0, value - 0.07),
                    alpha: 1
                ))
                context.fill(CGRect(
                    x: x,
                    y: y,
                    width: CGFloat(13 + (column % 4) * 3),
                    height: CGFloat(12 + (row % 5) * 2)
                ))

                context.setStrokeColor(CGColor(gray: 0.9 - value * 0.4, alpha: 1))
                context.setLineWidth(2)
                context.stroke(CGRect(
                    x: x + 18,
                    y: y + 11,
                    width: CGFloat(9 + row % 3),
                    height: CGFloat(8 + column % 5)
                ))
            }
        }

        context.restoreGState()
        return context.makeImage()
    }
}
