import CoreGraphics
import Foundation
import Testing
@testable import HangInThere

struct StaticSceneRegistrationTests {
    @Test func peripheralTranslationAndGlobalScaleStaySeparated() async throws {
        let referenceImage = try #require(makePatternImage(shiftX: 0, shiftY: 0))
        let movedImage = try #require(makePatternImage(shiftX: 7, shiftY: -5))
        let reference = try #require(
            VisionStaticSceneRegistrationWorker.makeReference(image: referenceImage)
        )

        let worker = VisionStaticSceneRegistrationWorker()
        let measurement = try await worker.measure(
            reference: reference,
            image: movedImage
        )

        let magnitudes = measurement.translations.map {
            hypot($0.dxPixels, $0.dyPixels)
        }
        #expect(magnitudes.filter { $0 >= 5 && $0 <= 11 }.count >= 2)
        #expect((measurement.globalScaleFraction ?? 1) < 0.01)

        var policy = StaticSceneStability()
        #expect(policy.calibrate(imageShortSide: 400))
        policy.observe(
            translations: measurement.translations,
            globalScaleFraction: measurement.globalScaleFraction,
            timestamp: 0.10
        )
        policy.observe(
            translations: measurement.translations,
            globalScaleFraction: measurement.globalScaleFraction,
            timestamp: 0.40
        )
        #expect(policy.state == .moved)
        #expect(policy.movementKind == .translation)
    }

    @Test func identicalStaticSceneProducesNearZeroRegistration() async throws {
        let image = try #require(makePatternImage(shiftX: 0, shiftY: 0))
        let reference = try #require(
            VisionStaticSceneRegistrationWorker.makeReference(image: image)
        )

        let worker = VisionStaticSceneRegistrationWorker()
        let measurement = try await worker.measure(
            reference: reference,
            image: image
        )

        #expect(
            measurement.translations.filter {
                hypot($0.dxPixels, $0.dyPixels) < 1
            }.count >= 2
        )
        let scale = try #require(measurement.globalScaleFraction)
        #expect(scale < 0.005)

        var policy = StaticSceneStability()
        #expect(policy.calibrate(imageShortSide: 400))
        policy.observe(
            translations: measurement.translations,
            globalScaleFraction: scale,
            timestamp: 0.1
        )
        #expect(policy.state == .stable)
    }

    @Test func fullFrameHomographyRecoversSyntheticScaleSignal() async throws {
        let referenceImage = try #require(makePatternImage(shiftX: 0, shiftY: 0))
        let scaledImage = try #require(
            makePatternImage(shiftX: 0, shiftY: 0, scale: 1.05)
        )
        let reference = try #require(
            VisionStaticSceneRegistrationWorker.makeReference(image: referenceImage)
        )

        let worker = VisionStaticSceneRegistrationWorker()
        let measurement = try await worker.measure(
            reference: reference,
            image: scaledImage
        )

        let scale = try #require(measurement.globalScaleFraction)
        #expect(scale >= StaticSceneStability.scaleThresholdFraction)
        #expect(scale >= 0.03)
        #expect(scale <= 0.08)

        var policy = StaticSceneStability()
        #expect(policy.calibrate(imageSize: .init(width: 400, height: 400)))
        policy.observe(
            translations: measurement.translations,
            globalScaleFraction: scale,
            timestamp: 0.10
        )
        policy.observe(
            translations: measurement.translations,
            globalScaleFraction: scale,
            timestamp: 0.40
        )

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
        context.translateBy(
            x: CGFloat(width) / 2 + shiftX,
            y: CGFloat(height) / 2 + shiftY
        )
        context.scaleBy(x: scale, y: scale)
        context.translateBy(
            x: -CGFloat(width) / 2,
            y: -CGFloat(height) / 2
        )

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

                context.setStrokeColor(CGColor(
                    gray: 0.9 - value * 0.4,
                    alpha: 1
                ))
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
