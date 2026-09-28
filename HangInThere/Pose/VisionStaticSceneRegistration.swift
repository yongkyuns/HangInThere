import CoreGraphics
import Foundation
import ImageIO
import Vision

struct StaticSceneRegistrationReference: Sendable {
    struct Patch: Sendable {
        let rect: CGRect
        let image: CGImage
    }

    let imageSize: ImageSize
    let patches: [Patch]
}

// Peripheral patches keep the moving athlete from dominating scene stability.
// Translation and local homographic scale are measured independently, then a
// framework-free policy decides whether enough patches agree.
actor VisionStaticSceneRegistrationWorker {
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
        guard width >= 64, height >= 64 else { return nil }

        let rects = cornerPatchRects(width: width, height: height)
        let patches = rects.compactMap { rect -> StaticSceneRegistrationReference.Patch? in
            guard let crop = image.cropping(to: rect) else { return nil }
            return .init(rect: rect, image: crop)
        }

        guard patches.count == 4 else { return nil }
        return StaticSceneRegistrationReference(
            imageSize: ImageSize(width: Double(width), height: Double(height)),
            patches: patches
        )
    }

    func measure(
        reference: StaticSceneRegistrationReference,
        image: CGImage
    ) throws -> [StaticSceneStability.PatchMotion] {
        guard reference.imageSize.isValid, reference.patches.count >= 2 else {
            throw RegistrationError.invalidGeometry
        }
        guard image.width == Int(reference.imageSize.width),
              image.height == Int(reference.imageSize.height)
        else {
            throw RegistrationError.incompatibleGeometry
        }

        var motions: [StaticSceneStability.PatchMotion] = []
        motions.reserveCapacity(reference.patches.count)

        for patch in reference.patches {
            guard let current = image.cropping(to: patch.rect) else { continue }

            let translation = translationMeasurement(
                reference: patch.image,
                current: current,
                patchRect: patch.rect
            )
            let scaleFraction = homographicScaleMeasurement(
                reference: patch.image,
                current: current
            )

            if translation != nil || scaleFraction != nil {
                motions.append(.init(
                    dxPixels: translation?.dx,
                    dyPixels: translation?.dy,
                    scaleFraction: scaleFraction
                ))
            }
        }

        return motions
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
        current: CGImage
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

        // Homographies are scale-equivalent, so normalize by h22 before
        // interpreting the local 2x2 area transform. The square root of the
        // absolute determinant is the isotropic area-equivalent local scale.
        let h22 = Double(matrix.columns.2.z)
        guard h22.isFinite, abs(h22) > 1e-9 else { return nil }

        let a = Double(matrix.columns.0.x) / h22
        let b = Double(matrix.columns.0.y) / h22
        let c = Double(matrix.columns.1.x) / h22
        let d = Double(matrix.columns.1.y) / h22
        let determinant = abs(a * d - b * c)
        guard determinant.isFinite, determinant > 0 else { return nil }

        let scale = sqrt(determinant)
        guard scale.isFinite,
              scale >= Self.minimumPlausibleHomographicScale,
              scale <= Self.maximumPlausibleHomographicScale
        else { return nil }

        let fraction = abs(scale - 1)
        return fraction.isFinite ? fraction : nil
    }

    private static func cornerPatchRects(
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
            CGRect(x: maxX, y: maxY, width: w, height: h)
        ]
    }
}
