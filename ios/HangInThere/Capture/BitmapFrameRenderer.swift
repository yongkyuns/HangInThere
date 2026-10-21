import Accelerate
import CoreGraphics
import CoreVideo
import Foundation

enum FrameRenderingBackend: String, Sendable {
    case bitmap
    case coreImage
}

// Lossless spatial normalization for an opaque, unscaled SDR decoder surface.
// ColorSync converts its declared RGB space to the same sRGB output contract
// as Core Image. Resampling, HDR and unsupported surfaces retain the CI path.
// The returned image owns its pixels; it never borrows a decoder's next frame.
enum BitmapFrameRenderer {
    static func supports(_ buffer: CVPixelBuffer, geometry: OrientedGeometry,
                         colorSpace: CGColorSpace?, maximumDimension: Double,
                         isolation: isolated (any Actor)? = #isolation) -> Bool {
        guard maximumDimension.isFinite, maximumDimension > 0,
              CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA,
              !CVPixelBufferIsPlanar(buffer),
              geometry.sourceSize.width == Double(CVPixelBufferGetWidth(buffer)),
              geometry.sourceSize.height == Double(CVPixelBufferGetHeight(buffer)),
              max(geometry.outputSize.width, geometry.outputSize.height) <= maximumDimension,
              let colorSpace, colorSpace.model == .rgb,
              !CGColorSpaceUsesExtendedRange(colorSpace), !colorSpace.isHDR() else { return false }
        let transfer = CVBufferCopyAttachment(buffer, kCVImageBufferTransferFunctionKey, nil)
        if let transfer,
           CFEqual(transfer, kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ)
            || CFEqual(transfer, kCVImageBufferTransferFunction_ITU_R_2100_HLG) { return false }
        let t = geometry.coreImageTransform
        // Integer quarter-turns/reflections preserve pixel centers. Do not
        // approximate arbitrary track transforms or change interpolation.
        return [t.a, t.b, t.c, t.d].allSatisfy { [-1.0, 0, 1].contains($0) }
            && abs(t.a) + abs(t.c) == 1 && abs(t.b) + abs(t.d) == 1
            && abs(t.a) + abs(t.b) == 1 && abs(t.c) + abs(t.d) == 1
            && t.tx == t.tx.rounded() && t.ty == t.ty.rounded()
    }

    static func render(_ buffer: CVPixelBuffer, geometry: OrientedGeometry,
                       colorSpace: CGColorSpace?, maximumDimension: Double,
                       isolation: isolated (any Actor)? = #isolation) -> CGImage? {
        guard supports(buffer, geometry: geometry, colorSpace: colorSpace,
                       maximumDimension: maximumDimension), let colorSpace,
              let pixels = opaquePixels(buffer) else { return nil }
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        // Canonical sRGB needs no color conversion. Move whole BGRA pixels
        // instead of asking a bitmap context to rotate and convert channels.
        if colorSpace.name == CGColorSpace.sRGB,
           let oriented = orientedPixels(pixels, width: width, height: height,
                                         transform: geometry.topLeftTransform),
           let image = image(oriented, width: Int(geometry.outputSize.width),
                             height: Int(geometry.outputSize.height), colorSpace: colorSpace) { return image }
        guard let source = image(pixels, width: width, height: height, colorSpace: colorSpace) else { return nil }
        guard let outputSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: Int(geometry.outputSize.width),
                  height: Int(geometry.outputSize.height), bitsPerComponent: 8, bytesPerRow: 0,
                  space: outputSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.setBlendMode(.copy)
        context.interpolationQuality = .none
        context.setShouldAntialias(false)
        let t = geometry.coreImageTransform
        context.concatenate(CGAffineTransform(a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx, ty: t.ty))
        context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
        return context.makeImage()
    }

    private static func image(_ pixels: Data, width: Int, height: Int, colorSpace: CGColorSpace) -> CGImage? {
        guard let provider = CGDataProvider(data: pixels as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
            space: colorSpace, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue
                | CGBitmapInfo.byteOrder32Little.rawValue), provider: provider, decode: nil,
            shouldInterpolate: false, intent: .defaultIntent)
    }

    private enum PixelOperation {
        case rotate(UInt8), horizontal, vertical
    }

    private static func orientedPixels(_ pixels: Data, width: Int, height: Int, transform t: Affine2D) -> Data? {
        switch (t.a, t.b, t.c, t.d) {
        case (1, 0, 0, 1): return pixels
        case (0, 1, -1, 0):
            return transformPixels(pixels, width: width, height: height, operation: .rotate(UInt8(kRotate90DegreesClockwise)))
        case (-1, 0, 0, -1):
            return transformPixels(pixels, width: width, height: height, operation: .rotate(UInt8(kRotate180DegreesClockwise)))
        case (0, -1, 1, 0):
            return transformPixels(pixels, width: width, height: height, operation: .rotate(UInt8(kRotate270DegreesClockwise)))
        case (-1, 0, 0, 1): return transformPixels(pixels, width: width, height: height, operation: .horizontal)
        case (1, 0, 0, -1): return transformPixels(pixels, width: width, height: height, operation: .vertical)
        case (0, 1, 1, 0), (0, -1, -1, 0):
            guard let rotated = transformPixels(pixels, width: width, height: height,
                operation: .rotate(UInt8(kRotate90DegreesClockwise))) else { return nil }
            return transformPixels(rotated, width: height, height: width,
                                   operation: t.b == 1 ? .horizontal : .vertical)
        default: return nil
        }
    }

    private static func transformPixels(_ pixels: Data, width: Int, height: Int, operation: PixelOperation) -> Data? {
        let swapsAxes: Bool
        if case .rotate(let turns) = operation { swapsAxes = turns % 2 == 1 } else { swapsAxes = false }
        let outputWidth = swapsAxes ? height : width, outputHeight = swapsAxes ? width : height
        var output = Data(count: pixels.count)
        let succeeded = pixels.withUnsafeBytes { source in
            output.withUnsafeMutableBytes { destination in
                guard let input = source.baseAddress, let result = destination.baseAddress else { return false }
                // vImage's four-channel geometry operations also accept BGRA;
                // source bytes are read-only and every operation writes elsewhere.
                var src = vImage_Buffer(data: UnsafeMutableRawPointer(mutating: input),
                    height: vImagePixelCount(height), width: vImagePixelCount(width), rowBytes: width * 4)
                var dst = vImage_Buffer(data: result, height: vImagePixelCount(outputHeight),
                    width: vImagePixelCount(outputWidth), rowBytes: outputWidth * 4)
                let flags = vImage_Flags(kvImageNoFlags)
                let status: vImage_Error
                switch operation {
                case .rotate(let turns): status = vImageRotate90_ARGB8888(&src, &dst, turns, [0, 0, 0, 0], flags)
                case .horizontal: status = vImageHorizontalReflect_ARGB8888(&src, &dst, flags)
                case .vertical: status = vImageVerticalReflect_ARGB8888(&src, &dst, flags)
                }
                return status == kvImageNoError
            }
        }
        return succeeded ? output : nil
    }

    private static func opaquePixels(_ buffer: CVPixelBuffer) -> Data? {
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        let packed = width.multipliedReportingOverflow(by: 4)
        let count = height.multipliedReportingOverflow(by: packed.partialValue)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        let stored = height.multipliedReportingOverflow(by: stride)
        guard width > 0, height > 0, !packed.overflow, !count.overflow, !stored.overflow,
              stride >= packed.partialValue, CVPixelBufferGetDataSize(buffer) >= stored.partialValue,
              CVPixelBufferLockBaseAddress(buffer, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        var data = Data(count: count.partialValue)
        let opaque = data.withUnsafeMutableBytes { destination in
            guard let target = destination.baseAddress else { return false }
            for y in 0..<height {
                let row = base.advanced(by: y * stride)
                for x in 0..<width where row.load(fromByteOffset: x * 4 + 3, as: UInt8.self) != 255 {
                    return false
                }
                // Exclude decoder row padding from the owned image storage.
                target.advanced(by: y * packed.partialValue).copyMemory(from: row, byteCount: packed.partialValue)
            }
            return true
        }
        return opaque ? data : nil
    }
}
