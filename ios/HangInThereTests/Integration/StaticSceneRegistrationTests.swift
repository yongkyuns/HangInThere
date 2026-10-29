import CoreGraphics
import Foundation
import Testing
import WorkoutCore
import simd
import CryptoKit
import ImageIO
@testable import HangInThere

struct StaticSceneRegistrationTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["CAMERA_POINT_PIXEL_MANIFEST"] != nil))
    func nativePointTranslationsPreserveObservedPixelsAndRejectCameraMovement() async throws {
        struct Point: Decodable { let reference: [Double]; let current: [Double]; let region: Int }
        struct Control: Decodable {
            let id: String; let truth: String; let reference: String; let current: String
            let referenceSHA256: String; let currentSHA256: String
            let expectedReason: String; let raster: [Double]; let expectedPoints: [Point]
        }
        let path = try #require(ProcessInfo.processInfo.environment["CAMERA_POINT_PIXEL_MANIFEST"])
        let controls = try JSONDecoder().decode([Control].self, from: Data(contentsOf: URL(fileURLWithPath: path)))
        #expect(controls.count == 107)
        func image(_ path: String, expected: String) throws -> CGImage {
            let data = try Data(contentsOf: URL(fileURLWithPath: path))
            #expect(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() == expected)
            let source = try #require(CGImageSourceCreateWithData(data as CFData, nil))
            return try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        }
        let worker = VisionStaticSceneRegistrationWorker()
        var rows: [[String: Any]] = []
        for control in controls {
            let referenceImage = try image(control.reference, expected: control.referenceSHA256)
            let current = try image(control.current, expected: control.currentSHA256)
            let reference = try #require(VisionStaticSceneRegistrationWorker.makeReference(image: referenceImage))
            let measurement = try await worker.measure(reference: reference, image: current)
            let pixels = try #require(measurement.backgroundScale)
            #expect(pixels.reason == control.expectedReason, "\(control.id)")
            #expect(pixels.translations.count == control.expectedPoints.count, "\(control.id)")
            #expect(measurement.translations.count == pixels.translations.count)
            #expect(Set(pixels.translations.map(\.region)).count == pixels.translations.count)
            for point in pixels.translations {
                let expected = try #require(control.expectedPoints.first { $0.region == point.region })
                let sx = Double(current.width) / control.raster[0]
                let sy = Double(current.height) / control.raster[1]
                #expect(abs(point.referenceX - expected.reference[0] * sx) < 1e-6)
                #expect(abs(point.referenceY - expected.reference[1] * sy) < 1e-6)
                #expect(abs(point.currentX - expected.current[0] * sx) < 1e-6)
                #expect(abs(point.currentY - expected.current[1] * sy) < 1e-6)
            }
            for (translation, point) in zip(measurement.translations, pixels.translations) {
                #expect(translation.dxPixels == point.currentX - point.referenceX)
                #expect(translation.dyPixels == point.currentY - point.referenceY)
            }
            let shared = bridge(measurement, times: [0.1, 0.4],
                width: Double(current.width), height: Double(current.height))
            if control.truth == "stationary" && control.expectedReason == "supported" {
                #expect(shared.isReady(seconds: 0.4), "\(control.id)")
            } else {
                #expect(!shared.isReady(seconds: 0.4), "\(control.id)")
            }
            rows.append(["id": control.id, "truth": control.truth, "reason": pixels.reason,
                "scale": measurement.globalScaleFraction as Any? ?? NSNull(),
                "points": pixels.translations.map {
                    ["reference": [$0.referenceX, $0.referenceY], "current": [$0.currentX, $0.currentY],
                     "region": $0.region] as [String: Any]
                }, "sceneState": shared.state, "movementKind": shared.movementKind as Any? ?? NSNull(),
                "sceneReady": shared.isReady(seconds: 0.4)])
        }
        if let output = ProcessInfo.processInfo.environment["CAMERA_POINT_PIXEL_REPORT"] {
            try JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys, .prettyPrinted])
                .write(to: URL(fileURLWithPath: output), options: .atomic)
        }
    }

    @Test func blankBackgroundCannotSupplyZeroScaleEvidence() async throws {
        let space = try #require(CGColorSpace(name: CGColorSpace.sRGB))
        let context = try #require(CGContext(data: nil, width: 400, height: 400,
            bitsPerComponent: 8, bytesPerRow: 0, space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray: 0.5, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 400, height: 400))
        let image = try #require(context.makeImage())
        let reference = try #require(VisionStaticSceneRegistrationWorker.makeReference(image: image))
        let measurement = try await VisionStaticSceneRegistrationWorker().measure(reference: reference, image: image)
        #expect(measurement.globalScaleFraction == nil)
        #expect(measurement.backgroundScale?.reason == "insufficientCorrespondences")
        #expect(measurement.backgroundScale?.correspondences == 0)
        #expect(measurement.translations.isEmpty)
        #expect(measurement.backgroundScale?.translations.isEmpty == true)
        let shared = bridge(measurement, times: [0.1, 0.4])
        #expect(!shared.isReady(seconds: 0.4))
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["CAMERA_SCALE_PIXEL_MANIFEST"] != nil))
    func pinnedStationarySourceDoesNotTriggerFalseScaleStop() async throws {
        struct Pair: Decodable { let reference: String; let current: String; let referenceSHA256: String; let currentSHA256: String }
        let path = try #require(ProcessInfo.processInfo.environment["CAMERA_SCALE_PIXEL_MANIFEST"])
        let pairs = try JSONDecoder().decode([Pair].self, from: Data(contentsOf: URL(fileURLWithPath: path)))
        #expect(!pairs.isEmpty)
        func image(_ path: String, expected: String) throws -> CGImage {
            let data = try Data(contentsOf: URL(fileURLWithPath: path))
            #expect(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() == expected)
            let source = try #require(CGImageSourceCreateWithData(data as CFData, nil))
            return try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        }
        let worker = VisionStaticSceneRegistrationWorker(includeDiagnosticHomography: true)
        for pair in pairs {
            let referenceImage = try image(pair.reference, expected: pair.referenceSHA256)
            let current = try image(pair.current, expected: pair.currentSHA256)
            let reference = try #require(VisionStaticSceneRegistrationWorker.makeReference(image: referenceImage))
            let measurement = try await worker.measure(reference: reference, image: current)
            #expect(try #require(measurement.globalScaleFraction) < 0.005)
            #expect(measurement.backgroundScale?.reason == "supported")
            #expect((measurement.backgroundScale?.inliers ?? 0) >= 8)
            let points = try #require(measurement.backgroundScale?.translations)
            #expect((3...6).contains(points.count))
            #expect(Set(points.map(\.region)).count == points.count)
            #expect(measurement.translations.count == points.count)
            for (patch, point) in zip(measurement.translations, points) {
                #expect(patch.dxPixels == point.currentX - point.referenceX)
                #expect(patch.dyPixels == point.currentY - point.referenceY)
            }
            let shared = bridge(measurement, times: [0.1, 0.4])
            #expect(shared.state == "stable" && shared.isReady(seconds: 0.4))
        }
    }

    @Test func cameraReferenceSamplesSixDisjointPeripheralRegions() throws {
        let image = try #require(makePatternImage(shiftX:0,shiftY:0))
        let reference = try #require(VisionStaticSceneRegistrationWorker.makeReference(image:image))
        #expect(reference.patches.count == 6)
        for i in reference.patches.indices {
            for j in reference.patches.indices where j > i {
                #expect(reference.patches[i].rect.intersection(reference.patches[j].rect).isEmpty)
            }
        }
    }

    @Test func visionMatrixTransportPreservesPerspectiveAndPixelCoordinates() throws {
        let width = 960.0, height = 540.0, zoom = 1.05
        let x = width / 2, y = height / 2, p = 0.00002, q = -0.00007
        let offset = p * x + q * y
        let matrix = simd_float3x3(columns: (
            SIMD3(Float(zoom + x*p), Float(y*p), Float(p)),
            SIMD3(Float(x*q), Float(zoom + y*q), Float(q)),
            SIMD3(Float(x*(1-zoom-offset)), Float(y*(1-zoom-offset)), Float(1-offset))
        ))
        let scale = try #require(VisionStaticSceneRegistrationWorker.imageCenterAreaScale(
            matrix: matrix, width: width, height: height))
        #expect(abs(scale - zoom) < 1e-6)
        let invalid = simd_float3x3(columns: (SIMD3(-1, 0, 0), SIMD3(0, 1, 0), SIMD3(0, 0, 1)))
        #expect(VisionStaticSceneRegistrationWorker.imageCenterAreaScale(
            matrix: invalid, width: width, height: height) == nil)
    }

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

        var policy = HangInThere.StaticSceneStability()
        let calibrated = policy.calibrate(imageShortSide: 400)
        #expect(calibrated)
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
        let shared = bridge(measurement, times: [0.1, 0.4])
        #expect(shared.state == "moved" && shared.movementKind == "translation")
        #expect(!shared.isReady(seconds: 0.4))
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

        var policy = HangInThere.StaticSceneStability()
        let calibrated = policy.calibrate(imageShortSide: 400)
        #expect(calibrated)
        policy.observe(
            translations: measurement.translations,
            globalScaleFraction: scale,
            timestamp: 0.1
        )
        #expect(policy.state == .stable)
        #expect(measurement.diagnosticHomography == nil, "Shipping registration does not retain diagnostic matrices.")
        #expect(measurement.diagnosticLegacyTranslations == nil)
        let shared = bridge(measurement, times: [0.1])
        #expect(shared.state == "stable" && shared.isReady(seconds: 0.1))
        #expect(!shared.isReady(seconds: 0.91), "Queries cannot freshen the registration clock.")
    }

    @Test func fullFrameHomographyRecoversSyntheticScaleSignal() async throws {
        let referenceImage = try #require(makePatternImage(shiftX: 0, shiftY: 0))
        let scaledImage = try #require(
            makePatternImage(shiftX: 0, shiftY: 0, scale: 1.05)
        )
        let reference = try #require(
            VisionStaticSceneRegistrationWorker.makeReference(image: referenceImage)
        )

        let worker = VisionStaticSceneRegistrationWorker(includeDiagnosticHomography: true)
        let measurement = try await worker.measure(
            reference: reference,
            image: scaledImage
        )

        let matrix = try #require(measurement.diagnosticHomography)
        #expect(matrix.count == 9 && matrix.allSatisfy(\.isFinite))

        let scale = try #require(measurement.globalScaleFraction)
        #expect(scale >= HangInThere.StaticSceneStability.scaleThresholdFraction)
        #expect(scale >= 0.03)
        #expect(scale <= 0.08)

        var policy = HangInThere.StaticSceneStability()
        let calibrated = policy.calibrate(imageSize: .init(width: 400, height: 400))
        #expect(calibrated)
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
        // Actual peripheral points move during zoom as well. The unchanged
        // scene policy reports translation first when both measured limits
        // are exceeded; scale must still be present and above its own limit.
        #expect(policy.movementKind == .translation)
        #expect((policy.latestScaleFraction ?? 0) >= HangInThere.StaticSceneStability.scaleThresholdFraction)
        let shared = bridge(measurement, times: [0.1, 0.4])
        #expect(shared.state == "moved" && shared.movementKind == "translation")
        #expect(!shared.isReady(seconds: 0.4))
    }

    @Test func movingForegroundAndCornerGraphicKeepStationaryBackgroundQualified() async throws {
        let image = try #require(makePatternImage(shiftX: 0, shiftY: 0, subjectOffset: 0))
        let reference = try #require(VisionStaticSceneRegistrationWorker.makeReference(image: image))
        let worker = VisionStaticSceneRegistrationWorker()
        for offset in [-18.0, 12.0, 25.0] {
            let current = try #require(makePatternImage(shiftX: 0, shiftY: 0, subjectOffset: offset))
            let measurement = try await worker.measure(reference: reference, image: current)
            #expect(measurement.translations.filter { hypot($0.dxPixels, $0.dyPixels) < 1 }.count >= 2)
            #expect(try #require(measurement.globalScaleFraction) < HangInThere.StaticSceneStability.scaleThresholdFraction)
            let shared = bridge(measurement, times: [0.1, 0.4])
            #expect(shared.state == "stable" && shared.isReady(seconds: 0.4))
        }
    }

    private func bridge(_ measurement: StaticSceneRegistrationMeasurement, times: [Double],
                        width: Double = 400, height: Double = 400) -> WorkoutSceneBridge {
        let shared = WorkoutSceneBridge()
        #expect(shared.calibrate(width: width, height: height))
        let values = KotlinDoubleArray(size: Int32(measurement.translations.count * 2))
        for (index, patch) in measurement.translations.enumerated() {
            values.set(index: Int32(index * 2), value: patch.dxPixels)
            values.set(index: Int32(index * 2 + 1), value: patch.dyPixels)
        }
        for time in times {
            _ = shared.observe(seconds: time, translations: values, scaleFraction: measurement.globalScaleFraction ?? .nan)
        }
        return shared
    }

    private func makePatternImage(
        shiftX: CGFloat,
        shiftY: CGFloat,
        scale: CGFloat = 1,
        subjectOffset: CGFloat? = nil
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

        if let subjectOffset {
            // A moving central subject and animated lower corner graphic leave
            // the textured background fixed. They are not camera ground truth.
            context.setFillColor(CGColor(gray: 0.02, alpha: 1))
            context.fill(CGRect(x: 145 + subjectOffset, y: 70, width: 110, height: 260))
            context.setFillColor(CGColor(gray: 0.85, alpha: 1))
            context.fillEllipse(in: CGRect(x: 172 + subjectOffset, y: 300, width: 55, height: 55))
            context.setFillColor(CGColor(gray: 1, alpha: 1))
            context.fill(CGRect(x: 285 + subjectOffset / 2, y: 18, width: 105, height: 42))
            context.setFillColor(CGColor(gray: 0, alpha: 1))
            for index in 0..<5 {
                context.fill(CGRect(x: 292 + subjectOffset / 2 + CGFloat(index * 16), y: 28,
                    width: 8, height: 20))
            }
        }
        context.restoreGState()
        return context.makeImage()
    }
}
