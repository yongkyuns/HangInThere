import CoreGraphics
import CreateML
import Foundation

private struct AnnotationRow: Decodable {
    let imagefilename: String
}

private struct MetricReport: Codable {
    let variedIoU: Double
    let iou50: Double
    let averagePrecisionVariedIoU: [String: Double]
    let averagePrecisionIoU50: [String: Double]
}

private struct PredictedObject: Codable {
    let label: String
    let confidence: Double
    let x: Double
    let y: Double
    let width: Double
    let height: Double
}

private struct ImagePrediction: Codable {
    let image: String
    let objects: [PredictedObject]
}

private struct ExperimentReport: Codable {
    let schemaVersion: Int
    let algorithm: String
    let iterations: Int
    let batchSize: Int
    let trainingSeconds: Double
    let modelBytes: UInt64
    let training: MetricReport
    let test: MetricReport
    let testImageCount: Int
    let poseInputs: Bool
    let qualification: String
}

private func report(_ metrics: MLObjectDetectorMetrics) -> MetricReport {
    let mean = metrics.meanAveragePrecision
    let ap = metrics.averagePrecision
    return MetricReport(
        variedIoU: mean.variedIoU,
        iou50: mean.IoU50,
        averagePrecisionVariedIoU: ap.variedIoU,
        averagePrecisionIoU50: ap.IoU50
    )
}

private func bytes(at url: URL) throws -> UInt64 {
    let values = try url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
    if values.isDirectory == true {
        let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        )
        var total: UInt64 = 0
        while let child = enumerator?.nextObject() as? URL {
            let childValues = try child.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
            if childValues.isRegularFile == true, let size = childValues.fileSize {
                total += UInt64(size)
            }
        }
        return total
    }
    return UInt64(values.fileSize ?? 0)
}

private func testImageNames(in directory: URL) throws -> [String] {
    let annotationURL = directory.appendingPathComponent("annotations.json")
    let rows = try JSONDecoder().decode([AnnotationRow].self, from: Data(contentsOf: annotationURL))
    return rows.map(\.imagefilename).sorted()
}

@main
struct CreateMLBarObjectExperiment {
    static func main() throws {
        guard CommandLine.arguments.count == 5,
              let iterations = Int(CommandLine.arguments[4]),
              iterations > 0 else {
            FileHandle.standardError.write(Data(
                "usage: CreateMLBarObjectExperiment TRAIN_DIR TEST_DIR OUTPUT_DIR ITERATIONS\n".utf8
            ))
            exit(2)
        }

        let trainURL = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let testURL = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
        let outputURL = URL(fileURLWithPath: CommandLine.arguments[3], isDirectory: true)
        try FileManager.default.createDirectory(at: outputURL, withIntermediateDirectories: true)

        let training = MLObjectDetector.DataSource.directoryWithImagesAndJsonAnnotation(at: trainURL)
        let testing = MLObjectDetector.DataSource.directoryWithImagesAndJsonAnnotation(at: testURL)
        let annotationType = MLObjectDetector.AnnotationType.boundingBox(
            units: .pixel,
            origin: .topLeft,
            anchor: .center
        )
        let batchSize = 8
        let parameters = MLObjectDetector.ModelParameters(
            validation: .none,
            batchSize: batchSize,
            maxIterations: iterations,
            gridSize: CGSize(width: 13, height: 13),
            algorithm: .transferLearning(.objectPrint(revision: 1))
        )

        let started = Date()
        let detector = try MLObjectDetector(
            trainingData: training,
            parameters: parameters,
            annotationType: annotationType
        )
        let trainingSeconds = Date().timeIntervalSince(started)
        let testMetrics = detector.evaluation(on: testing)

        let modelURL = outputURL.appendingPathComponent("BarObjectPrototype.mlmodel")
        try detector.write(to: modelURL, metadata: nil)

        let names = try testImageNames(in: testURL)
        var predictions: [ImagePrediction] = []
        for name in names {
            let imageURL = testURL.appendingPathComponent(name)
            let objects = try detector.prediction(from: imageURL).map { object in
                PredictedObject(
                    label: object.label,
                    confidence: object.confidence,
                    x: Double(object.boundingBox.origin.x),
                    y: Double(object.boundingBox.origin.y),
                    width: Double(object.boundingBox.width),
                    height: Double(object.boundingBox.height)
                )
            }
            predictions.append(ImagePrediction(image: name, objects: objects))
        }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let predictionData = try encoder.encode(predictions)
        try predictionData.write(to: outputURL.appendingPathComponent("predictions.json"))

        let result = ExperimentReport(
            schemaVersion: 1,
            algorithm: "CreateML transferLearning(objectPrint revision 1)",
            iterations: iterations,
            batchSize: batchSize,
            trainingSeconds: trainingSeconds,
            modelBytes: try bytes(at: modelURL),
            training: report(detector.trainingMetrics),
            test: report(testMetrics),
            testImageCount: names.count,
            poseInputs: false,
            qualification: "prototype source-separated detector screen; not production accuracy qualification"
        )
        let reportData = try encoder.encode(result)
        try reportData.write(to: outputURL.appendingPathComponent("report.json"))

        print(String(data: reportData, encoding: .utf8)!)
        print(String(data: predictionData, encoding: .utf8)!)
    }
}