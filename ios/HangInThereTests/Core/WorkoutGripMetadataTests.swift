import XCTest
import class WorkoutCore.WorkoutGripReviewBridge
@testable import HangInThere

final class WorkoutGripMetadataTests: XCTestCase {
    func testSetGripSurvivesCompactNativeInputAndSharedReview() throws {
        let text = """
        {"id":"set","exercise":"pullUp","startedAt":0,"endedAt":10,"endReason":"manual","reps":[],"backend":"test","modelRevision":1,"schemaVersion":1,"lifecycleVersion":1,"setGrip":{"setId":"set","orientation":"underhand","firstConfirmedAt":8,"lastConfirmedAt":9,"modelId":"test","inferenceBackend":"test","reason":"confirmedWithinSet","policyVersion":1}}
        """
        let record = try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(text.utf8))
        XCTAssertEqual(record.progressInput.setGrip, record.setGrip)
        XCTAssertTrue(try XCTUnwrap(record.setGrip).isConfirmed)
        let input = String(decoding: try JSONEncoder().encode(record.progressInput), as: UTF8.self)
        let output = try WorkoutGripReviewBridge().reportJson(recordJson: input, ledgerJson: nil)
        let report = try JSONDecoder().decode(WorkoutGripVariationReport.self, from: Data(output.utf8))
        XCTAssertEqual(report.setGrip, record.setGrip)
        XCTAssertEqual(report.setGrip?.title, "Underhand")
        XCTAssertEqual(record, try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONEncoder().encode(record)))
    }

    func testLegacyGripReportDecodesWithoutSetAnnotation() throws {
        let report = try JSONDecoder().decode(WorkoutGripVariationReport.self,
            from: Data("{\"reps\":[],\"totals\":[],\"policyVersion\":1}".utf8))
        XCTAssertNil(report.setGrip)
    }

    private func decode(_ extra: String = "", width: String = "shoulderWidth", ratio: Double = 1) throws -> WorkoutGripEstimate {
        let text = """
        {"left":"unknown","right":"unknown","width":"\(width)","projectedSpanRatio":\(ratio),"orientation":"unknown","policyVersion":4\(extra)}
        """
        return try JSONDecoder().decode(WorkoutGripEstimate.self, from: Data(text.utf8))
    }

    func testLegacyMetadataKeepsMeasuredRatioWithoutNewFields() throws {
        let value = try decode()
        XCTAssertNil(value.widthPolicyVersion)
        XCTAssertNil(value.widthReason)
        XCTAssertEqual(value.shoulderRatioLabel, "1.0× shoulders")
        XCTAssertEqual(value, try JSONDecoder().decode(WorkoutGripEstimate.self, from: JSONEncoder().encode(value)))
    }

    func testNewMetadataRoundTripsEvenWhenOrientationIsUnknown() throws {
        let value = try decode(",\"widthPolicyVersion\":1,\"widthReason\":\"variationCoverageLow\"", width: "wide", ratio: 1.6)
        XCTAssertEqual(value.orientation, "unknown")
        XCTAssertEqual(value.widthPolicyVersion, 1)
        XCTAssertEqual(value.widthReason, "variationCoverageLow")
        XCTAssertEqual(value.shoulderRatioLabel, "1.6× shoulders")
        XCTAssertEqual(value, try JSONDecoder().decode(WorkoutGripEstimate.self, from: JSONEncoder().encode(value)))
    }

    func testUnknownChangedOrInvalidWidthsDoNotDisplayAStableRatio() throws {
        XCTAssertNil(try decode(width: "varied").shoulderRatioLabel)
        XCTAssertNil(try decode(width: "unknown").shoulderRatioLabel)
        XCTAssertNil(try decode(ratio: 0).shoulderRatioLabel)
        XCTAssertNil(try decode(ratio: 5).shoulderRatioLabel)
    }
}
