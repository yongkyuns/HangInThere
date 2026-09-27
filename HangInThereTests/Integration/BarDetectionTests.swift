import Foundation
import CoreGraphics
import Testing
@testable import HangInThere

// Real Apple contour requests on original analytic pixels, independent of the
// human-pose model and its known simulator asset failure. Not real-bar accuracy.
@Suite struct BarDetectionTests {
    @Test(arguments: [false, true])
    func actualContoursLocalizeBothPolaritiesInOffsetCrop(bright: Bool) async throws {
        let image = try makeImage(width:640,height:360,bright:bright) { x,y in
            x >= 80 && x <= 550 && y >= 95 && y <= 107
        }
        let region = BarRegion(Point2D(x:40,y:60),Point2D(x:600,y:160))
        let candidates = try await VisionBarDetector().detect(image:image,region:region)
        let candidate = try #require(candidates.first)
        #expect(abs(candidate.centerline.midpoint.y-101) < 4)
        #expect(candidate.centerline.length > 440)
        #expect(abs(try #require(candidate.upperImageEdge).midpoint.y-95) < 4)
    }
    @Test func actualContoursPreserveObliqueImageGeometry() async throws {
        let image = try makeImage(width:640,height:360) { x,y in
            x >= 90 && x <= 540 && Double(y) >= 65+0.2*Double(x) && Double(y) <= 77+0.2*Double(x)
        }
        let candidates = try await VisionBarDetector().detect(image:image,
            region:BarRegion(Point2D(x:50,y:50),Point2D(x:600,y:230)))
        let candidate = try #require(candidates.first)
        #expect(abs(candidate.centerline.midpoint.y - (71+0.2*candidate.centerline.midpoint.x)) < 4)
        #expect(candidate.centerline.length > 420)
    }
    @Test func barCanContinueThroughBothCropBorders() async throws {
        let image = try makeImage(width:640,height:360) { _,y in y >= 95 && y <= 107 }
        let candidates = try await VisionBarDetector().detect(image:image,
            region:BarRegion(Point2D(x:70,y:60),Point2D(x:230,y:150)))
        let candidate = try #require(candidates.first)
        #expect(abs(candidate.centerline.midpoint.y-101) < 4)
        #expect(candidate.centerline.length > 150)
    }
    @Test func blankImageDoesNotProduceBar() async throws {
        let image = try makeImage(width:160,height:100) { _,_ in false }
        #expect(try await VisionBarDetector().detect(image:image,
            region:BarRegion(Point2D(x:10,y:10),Point2D(x:150,y:90))).isEmpty)
    }
    private func makeImage(width:Int,height:Int,bright:Bool=false, inside:(Int,Int)->Bool) throws -> CGImage {
        var bytes = [UInt8](repeating:255,count:width*height*4)
        for y in 0..<height { for x in 0..<width {
            let v:UInt8 = inside(x,y) != bright ? 0 : 255
            let i = (y*width+x)*4
            bytes[i]=v;bytes[i+1]=v;bytes[i+2]=v
        } }
        let provider = try #require(CGDataProvider(data:Data(bytes) as CFData))
        return try #require(CGImage(width:width,height:height,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:width*4,
            space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.noneSkipLast.rawValue),
            provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent))
    }
}
