import AVFoundation
import CoreImage
import Testing
@testable import HangInThere

struct BitmapFrameRendererTests {
    private static let transforms: [Affine2D] = [
        .init(), .init(a: 0, b: 1, c: -1, d: 0), .init(a: -1, d: -1),
        .init(a: 0, b: -1, c: 1, d: 0), .init(a: -1), .init(d: -1),
        .init(a: 0, b: 1, c: 1, d: 0), .init(a: 0, b: -1, c: -1, d: 0)
    ]

    @Test(arguments: 0..<8)
    func everyQuarterTurnAndReflectionPreservesPixelCenters(index: Int) throws {
        let buffer = try makeBuffer(width: 13, height: 7)
        #expect(CVPixelBufferGetBytesPerRow(buffer) > 13 * 4, "Exercise decoder row padding.")
        let geometry = try #require(OrientedGeometry(sourceSize: .init(width: 13, height: 7),
            preferredTransform: Self.transforms[index]))
        let image = try #require(BitmapFrameRenderer.render(buffer, geometry: geometry,
            colorSpace: CGColorSpace(name: CGColorSpace.sRGB), maximumDimension: 1280))
        #expect(image.width == Int(geometry.outputSize.width))
        #expect(image.height == Int(geometry.outputSize.height))
        let pixels = try rgba(image)
        for y in 0..<7 { for x in 0..<13 {
            let p = geometry.topLeftTransform.apply(.init(x: Double(x) + 0.5, y: Double(y) + 0.5))
            let offset = (Int(p.y) * image.width + Int(p.x)) * 4
            #expect(Array(pixels[offset..<offset + 4]) == color(x: x, y: y))
        } }
    }

    @Test(arguments: ["sRGB", "rec709", "p3"])
    func sdrColorConversionMatchesTheExistingRenderWithinOneLevel(profile: String) throws {
        let name = profile == "p3" ? CGColorSpace.displayP3 : profile == "rec709" ? CGColorSpace.itur_709 : CGColorSpace.sRGB
        let space = try #require(CGColorSpace(name: name))
        let buffer = try makeBuffer(width: 256, height: 64)
        CVBufferSetAttachment(buffer, kCVImageBufferCGColorSpaceKey, space, .shouldPropagate)
        let geometry = try #require(OrientedGeometry(sourceSize: .init(width: 256, height: 64), preferredTransform: .init()))
        let source = CIImage(cvPixelBuffer: buffer)
        let bitmap = try #require(BitmapFrameRenderer.render(buffer, geometry: geometry,
            colorSpace: source.colorSpace, maximumDimension: 1280))
        let legacy = try #require(CIContext(options: [.cacheIntermediates: false]).createCGImage(source, from: source.extent))
        let left = try rgba(bitmap), right = try rgba(legacy)
        #expect(zip(left, right).allSatisfy { abs(Int($0) - Int($1)) <= 1 })
        if profile == "sRGB" { #expect(left == right) }
    }

    @Test func renderedImageOwnsPixelsAcrossDecoderBufferReuse() throws {
        let buffer = try makeBuffer(width: 13, height: 7)
        let geometry = try #require(OrientedGeometry(sourceSize: .init(width: 13, height: 7), preferredTransform: .init()))
        let image = try #require(BitmapFrameRenderer.render(buffer, geometry: geometry,
            colorSpace: CGColorSpace(name: CGColorSpace.sRGB), maximumDimension: 1280))
        let before = try rgba(image)
        try #require(CVPixelBufferLockBaseAddress(buffer, []) == kCVReturnSuccess)
        let base = try #require(CVPixelBufferGetBaseAddress(buffer))
        base.initializeMemory(as: UInt8.self, repeating: 0, count: CVPixelBufferGetDataSize(buffer))
        CVPixelBufferUnlockBaseAddress(buffer, [])
        #expect(try rgba(image) == before)
    }

    @Test func nativeDecodedSDRPixelsMatchTheExistingColorPipeline() async throws {
        let url = try VideoTestSupport.fixtureResource("decoder/quadrants-h264-vfr-portrait.mp4")
        let asset = AVURLAsset(url: url)
        let track = try #require(try await asset.loadTracks(withMediaType: .video).first)
        let preferred = try await track.load(.preferredTransform)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ])
        reader.add(output)
        try #require(reader.startReading())
        defer { reader.cancelReading() }
        let legacy = CIContext(options: [.cacheIntermediates: false])
        var frames = 0
        while let sample = output.copyNextSampleBuffer() {
            let buffer = try #require(CMSampleBufferGetImageBuffer(sample))
            let geometry = try #require(OrientedGeometry(sourceSize: .init(
                width: Double(CVPixelBufferGetWidth(buffer)), height: Double(CVPixelBufferGetHeight(buffer))),
                preferredTransform: .init(a: preferred.a, b: preferred.b, c: preferred.c,
                    d: preferred.d, tx: preferred.tx, ty: preferred.ty)))
            let source = CIImage(cvPixelBuffer: buffer), t = geometry.coreImageTransform
            let bitmap = try #require(BitmapFrameRenderer.render(buffer, geometry: geometry,
                colorSpace: source.colorSpace, maximumDimension: 1280))
            let normalized = source.transformed(by: .init(a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx, ty: t.ty))
            let reference = try #require(legacy.createCGImage(normalized, from: CGRect(x: 0, y: 0,
                width: geometry.outputSize.width, height: geometry.outputSize.height)))
            #expect(zip(try rgba(bitmap), try rgba(reference)).allSatisfy { abs(Int($0) - Int($1)) <= 1 })
            frames += 1
        }
        #expect(reader.status == .completed)
        #expect(frames == 4)
    }

    @Test func resamplingArbitraryTransformsAndInvalidLimitsRetainTheExistingPath() throws {
        let buffer = try makeBuffer(width: 13, height: 7)
        let space = try #require(CGColorSpace(name: CGColorSpace.sRGB))
        for t in [Affine2D(a: 2, d: 2), .init(c: 0.25), .init(a: 0.8, b: 0.6, c: -0.6, d: 0.8)] {
            let geometry = try #require(OrientedGeometry(sourceSize: .init(width: 13, height: 7), preferredTransform: t))
            #expect(BitmapFrameRenderer.render(buffer, geometry: geometry, colorSpace: space, maximumDimension: 1280) == nil)
        }
        let geometry = try #require(OrientedGeometry(sourceSize: .init(width: 13, height: 7), preferredTransform: .init()))
        for limit in [8.0, 0, -1, .infinity, .nan] {
            #expect(BitmapFrameRenderer.render(buffer, geometry: geometry, colorSpace: space, maximumDimension: limit) == nil)
        }
    }

    @Test func hdrExtendedAndUnknownColorProfilesKeepTheExistingRenderer() throws {
        let buffer = try makeBuffer(width: 13, height: 7)
        let geometry = try #require(OrientedGeometry(sourceSize: .init(width: 13, height: 7), preferredTransform: .init()))
        for space in [nil, CGColorSpace(name: CGColorSpace.itur_2100_PQ),
                      CGColorSpace(name: CGColorSpace.extendedSRGB), CGColorSpaceCreateDeviceGray()] {
            #expect(BitmapFrameRenderer.render(buffer, geometry: geometry, colorSpace: space, maximumDimension: 1280) == nil)
        }
        for transfer in [kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ, kCVImageBufferTransferFunction_ITU_R_2100_HLG] {
            CVBufferSetAttachment(buffer, kCVImageBufferTransferFunctionKey, transfer, .shouldPropagate)
            #expect(BitmapFrameRenderer.render(buffer, geometry: geometry,
                colorSpace: CGColorSpace(name: CGColorSpace.sRGB), maximumDimension: 1280) == nil)
        }
    }

    @Test func transparentOrNonBGRASurfacesKeepTheExistingRenderer() throws {
        let buffer = try makeBuffer(width: 13, height: 7)
        let geometry = try #require(OrientedGeometry(sourceSize: .init(width: 13, height: 7), preferredTransform: .init()))
        try #require(CVPixelBufferLockBaseAddress(buffer, []) == kCVReturnSuccess)
        let base = try #require(CVPixelBufferGetBaseAddress(buffer))
        base.storeBytes(of: UInt8(128), toByteOffset: 12 * 4 + 3, as: UInt8.self)
        CVPixelBufferUnlockBaseAddress(buffer, [])
        let space = try #require(CGColorSpace(name: CGColorSpace.sRGB))
        #expect(BitmapFrameRenderer.render(buffer, geometry: geometry, colorSpace: space, maximumDimension: 1280) == nil)
        var other: CVPixelBuffer?
        try #require(CVPixelBufferCreate(nil, 13, 7, kCVPixelFormatType_32ARGB, nil, &other) == kCVReturnSuccess)
        #expect(BitmapFrameRenderer.render(try #require(other), geometry: geometry, colorSpace: space, maximumDimension: 1280) == nil)
    }

    private func color(x: Int, y: Int) -> [UInt8] {
        [UInt8((x * 17) % 256), UInt8((y * 29) % 256), UInt8(((x + y) * 7) % 256), 255]
    }

    private func makeBuffer(width: Int, height: Int) throws -> CVPixelBuffer {
        var result: CVPixelBuffer?
        try #require(CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA,
            [kCVPixelBufferBytesPerRowAlignmentKey: 64] as CFDictionary, &result) == kCVReturnSuccess)
        let buffer = try #require(result)
        try #require(CVPixelBufferLockBaseAddress(buffer, []) == kCVReturnSuccess)
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        let base = try #require(CVPixelBufferGetBaseAddress(buffer))
        base.initializeMemory(as: UInt8.self, repeating: 85, count: CVPixelBufferGetDataSize(buffer))
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        for y in 0..<height { for x in 0..<width {
            let p = color(x: x, y: y), offset = y * stride + x * 4
            for (channel, value) in [p[2], p[1], p[0], p[3]].enumerated() {
                base.storeBytes(of: value, toByteOffset: offset + channel, as: UInt8.self)
            }
        } }
        return buffer
    }

    private func rgba(_ image: CGImage) throws -> [UInt8] {
        var result = [UInt8](repeating: 0, count: image.width * image.height * 4)
        try result.withUnsafeMutableBytes { bytes in
            let context = try #require(CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
                bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        }
        return result
    }
}
