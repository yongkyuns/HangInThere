import CoreGraphics
import Foundation
import ImageIO
import Vision
import WorkoutCore
import simd
import ObservedRailPixels

struct StaticSceneRegistrationReference: Sendable {
    struct Patch: Sendable {
        let rect: CGRect
        let image: CGImage
    }

    let imageSize: ImageSize
    let image: CGImage
    let patches: [Patch]
}

struct StaticSceneRegistrationMeasurement: Sendable {
    let translations: [StaticSceneStability.PatchTranslation]
    let globalScaleFraction: Double?
    // Optional evaluation evidence, in column-major order. Never used by policy.
    let diagnosticHomography: [Double]?
    let diagnosticLegacyTranslations: [StaticSceneStability.PatchTranslation]?
    let backgroundScale: BackgroundCameraMeasurement?

    init(translations: [StaticSceneStability.PatchTranslation], globalScaleFraction: Double?,
         diagnosticHomography: [Double]? = nil, backgroundScale: BackgroundCameraMeasurement? = nil,
         diagnosticLegacyTranslations: [StaticSceneStability.PatchTranslation]? = nil) {
        self.translations = translations
        self.globalScaleFraction = globalScaleFraction
        self.diagnosticHomography = diagnosticHomography
        self.backgroundScale = backgroundScale
        self.diagnosticLegacyTranslations = diagnosticLegacyTranslations
    }
}

protocol StaticSceneRegistering: Sendable {
    func measure(reference: StaticSceneRegistrationReference, image: CGImage) async throws
        -> StaticSceneRegistrationMeasurement
}

// Translation and scale come from measured reciprocal peripheral background
// correspondences. Translation uses actual matched points, not a fitted warp. A
// full-frame SDK homography can follow a moving athlete and remains diagnostic
// only. Missing/ambiguous image evidence never becomes a zero-scale reading.
actor VisionStaticSceneRegistrationWorker: StaticSceneRegistering {
    static let measurementPolicyVersion = 3
    private let includeDiagnosticHomography: Bool

    init(includeDiagnosticHomography: Bool = false) {
        self.includeDiagnosticHomography = includeDiagnosticHomography
    }

    enum RegistrationError: LocalizedError {
        case invalidGeometry
        case incompatibleGeometry

        var errorDescription: String? {
            switch self {
            case .invalidGeometry:
                "The calibration image could not produce usable background patches."
            case .incompatibleGeometry:
                "The current frame geometry no longer matches the calibration frame."
            }
        }
    }

    static let patchFraction = 0.25
    static let maximumPatchShiftFraction = 0.45
    static let minimumPlausibleHomographicScale = 0.5
    static let maximumPlausibleHomographicScale = 2.0

    static func makeReference(
        image: CGImage
    ) -> StaticSceneRegistrationReference? {
        let width = image.width
        let height = image.height
        guard width >= 64, height >= 96 else { return nil }

        let rects = peripheralPatchRects(width: width, height: height)
        let patches = rects.compactMap { rect -> StaticSceneRegistrationReference.Patch? in
            guard let crop = image.cropping(to: rect) else { return nil }
            return .init(rect: rect, image: crop)
        }

        guard patches.count == StaticSceneStability.maximumRegistrationPatches else { return nil }
        return StaticSceneRegistrationReference(
            imageSize: ImageSize(width: Double(width), height: Double(height)),
            image: image,
            patches: patches
        )
    }

    func measure(
        reference: StaticSceneRegistrationReference,
        image: CGImage
    ) throws -> StaticSceneRegistrationMeasurement {
        guard reference.imageSize.isValid, reference.patches.count >= 2 else {
            throw RegistrationError.invalidGeometry
        }
        guard image.width == Int(reference.imageSize.width),
              image.height == Int(reference.imageSize.height)
        else {
            throw RegistrationError.incompatibleGeometry
        }

        var diagnosticHomography: [Double]?
        var diagnosticLegacyTranslations: [StaticSceneStability.PatchTranslation]?
        let backgroundScale = try BackgroundCameraPixels.measure(reference: reference.image, current: image)
        if includeDiagnosticHomography {
            diagnosticLegacyTranslations = reference.patches.compactMap { patch in
                guard let current = image.cropping(to: patch.rect),
                      let translation = translationMeasurement(reference: patch.image, current: current,
                          patchRect: patch.rect) else { return nil }
                return .init(dxPixels: translation.dx, dyPixels: translation.dy)
            }
            _ = homographicScaleMeasurement(reference: reference.image, current: image,
                diagnosticHomography: &diagnosticHomography)
        }

        return StaticSceneRegistrationMeasurement(
            translations: backgroundScale.translations.map { .init(dxPixels: $0.dxPixels, dyPixels: $0.dyPixels) },
            globalScaleFraction: backgroundScale.scaleFraction,
            diagnosticHomography: diagnosticHomography,
            backgroundScale: backgroundScale,
            diagnosticLegacyTranslations: diagnosticLegacyTranslations
        )
    }

    private func translationMeasurement(
        reference: CGImage,
        current: CGImage,
        patchRect: CGRect
    ) -> (dx: Double, dy: Double)? {
        let request = VNTranslationalImageRegistrationRequest(
            targetedCGImage: reference,
            orientation: .up,
            options: [:]
        )
        let handler = VNImageRequestHandler(
            cgImage: current,
            orientation: .up,
            options: [:]
        )
        do {
            try handler.perform([request])
        } catch {
            return nil
        }

        guard let observation = request.results?.first else { return nil }
        let transform = observation.alignmentTransform
        let dx = Double(transform.tx)
        let dy = Double(transform.ty)
        guard dx.isFinite, dy.isFinite else { return nil }

        let maxX = patchRect.width * Self.maximumPatchShiftFraction
        let maxY = patchRect.height * Self.maximumPatchShiftFraction
        guard abs(dx) <= maxX, abs(dy) <= maxY else { return nil }
        return (dx, dy)
    }

    private func homographicScaleMeasurement(
        reference: CGImage,
        current: CGImage,
        diagnosticHomography: inout [Double]?
    ) -> Double? {
        let request = VNHomographicImageRegistrationRequest(
            targetedCGImage: reference,
            orientation: .up,
            options: [:]
        )
        let handler = VNImageRequestHandler(
            cgImage: current,
            orientation: .up,
            options: [:]
        )
        do {
            try handler.perform([request])
        } catch {
            return nil
        }

        guard let observation = request.results?.first else { return nil }
        let matrix = observation.warpTransform
        if includeDiagnosticHomography {
            diagnosticHomography = Self.columnMajorElements(matrix)
        }
        guard let scale = Self.imageCenterAreaScale(matrix: matrix,
            width: Double(current.width), height: Double(current.height)),
              scale >= Self.minimumPlausibleHomographicScale,
              scale <= Self.maximumPlausibleHomographicScale
        else { return nil }

        let fraction = abs(scale - 1)
        return fraction.isFinite ? fraction : nil
    }

    // Vision's warp acts on homogeneous image-pixel coordinates. Keep the SDK
    // matrix boundary here and the perspective differentiation in shared code.
    static func imageCenterAreaScale(matrix: simd_float3x3, width: Double, height: Double) -> Double? {
        let elements = columnMajorElements(matrix)
        let values = KotlinDoubleArray(size: 9)
        for (index, value) in elements.enumerated() { values.set(index: Int32(index), value: value) }
        return ImageRegistrationGeometry.shared.areaScaleAtImageCenter(
            columnMajor: values, width: width, height: height)?.doubleValue
    }

    private static func columnMajorElements(_ matrix: simd_float3x3) -> [Double] {
        [matrix.columns.0.x, matrix.columns.0.y, matrix.columns.0.z,
         matrix.columns.1.x, matrix.columns.1.y, matrix.columns.1.z,
         matrix.columns.2.x, matrix.columns.2.y, matrix.columns.2.z].map(Double.init)
    }

    private static func peripheralPatchRects(
        width: Int,
        height: Int
    ) -> [CGRect] {
        let w = max(32, Int((Double(width) * patchFraction).rounded(.down)))
        let h = max(32, Int((Double(height) * patchFraction).rounded(.down)))
        let maxX = width - w
        let maxY = height - h

        return [
            CGRect(x: 0, y: 0, width: w, height: h),
            CGRect(x: maxX, y: 0, width: w, height: h),
            CGRect(x: 0, y: maxY, width: w, height: h),
            CGRect(x: maxX, y: maxY, width: w, height: h),
            // Middle-side regions provide independent background texture when
            // upper foliage or lower moving shadows defeat corner alignment.
            CGRect(x: 0, y: (height-h)/2, width: w, height: h),
            CGRect(x: maxX, y: (height-h)/2, width: w, height: h)
        ]
    }
}
