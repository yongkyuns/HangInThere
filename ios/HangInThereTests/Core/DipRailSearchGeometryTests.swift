import Foundation
import Testing
@testable import HangInThere

@Suite struct DipRailSearchGeometryTests {
    private let size = ImageSize(width: 960, height: 540)
    // Actual native primary body-wrist geometry, dip_side426. No MCP substitution.
    private func arm(shoulder: Point2D = .init(x: 602.1220779418945, y: 143.5483020544052),
                     wrist: Point2D = .init(x: 741.2384033203125, y: 348.49893450737),
                     upper: Double = 169.32094225849218) -> PullUpArmMeasurement.Arm {
        .init(side: .left, estimate: .init(elbowDegrees: 150, upperArmPixels: upper,
            forearmPixels: 100, minimumJointConfidence: 0.9), shoulder: shoulder, wrist: wrist)
    }
    @Test func measuredBodyWristSearchRetainsOutwardRailInBothDirections() throws {
        let original = arm()
        let a = try #require(AutomaticDipRails.contactRegion(arm: original, size: size))
        #expect(abs(a.minX - 676.4384033203125) < 1e-9)
        #expect(abs(a.maxX - 910.5593455788047) < 1e-9)
        #expect(abs(a.minY - 305.29893450737) < 1e-9)
        #expect(abs(a.maxY - 456.49893450737) < 1e-9)
        let mirrored = arm(shoulder: .init(x: size.width - original.shoulder.x, y: original.shoulder.y),
                           wrist: .init(x: size.width - original.wrist.x, y: original.wrist.y))
        let b = try #require(AutomaticDipRails.contactRegion(arm: mirrored, size: size))
        #expect(abs(b.minX - (size.width - a.maxX)) < 1e-9)
        #expect(abs(b.maxX - (size.width - a.minX)) < 1e-9)
        #expect(b.minY == a.minY && b.maxY == a.maxY)
        #expect(AutomaticDipRails.contactSearchPolicyVersion == 2)
    }
    @Test func sourceBoundsAndUnresolvedOutwardDirectionRemainBounded() throws {
        let bounded = try #require(AutomaticDipRails.contactRegion(arm: arm(wrist: .init(x: 940, y: 348)), size: size))
        #expect(bounded.maxX == size.width)
        let aligned = try #require(AutomaticDipRails.contactRegion(arm: arm(shoulder: .init(x: 740, y: 144)), size: size))
        #expect(abs(aligned.maxX - (arm().wrist.x + 540 * 0.12)) < 1e-9)
        let small = try #require(AutomaticDipRails.contactRegion(arm: arm(upper: 1), size: size))
        #expect(small == aligned)
    }
    @Test func invalidGeometryCannotFallBackToAnUnvalidatedProductionCrop() {
        for radius in [Double.nan, .infinity, 0, -1] {
            #expect(AutomaticDipRails.contactRegion(arm: arm(upper: radius), size: size) == nil)
        }
        for point in [Point2D(x: -1, y: 100), .init(x: .nan, y: 100), .init(x: 500, y: 541)] {
            #expect(AutomaticDipRails.contactRegion(arm: arm(shoulder: point), size: size) == nil)
            #expect(AutomaticDipRails.contactRegion(arm: arm(wrist: point), size: size) == nil)
        }
        #expect(AutomaticDipRails.contactRegion(arm: arm(), size: .init(width: 0, height: 540)) == nil)
    }
}
