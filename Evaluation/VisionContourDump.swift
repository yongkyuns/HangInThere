import Foundation
import CoreGraphics
import ImageIO
import Vision
import CryptoKit

private func sha256(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

private func image(at url: URL) throws -> CGImage {
    let data = try Data(contentsOf: url)
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        throw NSError(domain: "VisionContourDump", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "Cannot decode \(url.path)"])
    }
    return image
}

private func contours(image: CGImage, region: [Double]) throws -> [[[Double]]] {
    guard region.count == 4 else { throw NSError(domain:"VisionContourDump", code:2) }
    let rect = CGRect(x: floor(region[0]), y: floor(region[1]),
                      width: ceil(region[2]) - floor(region[0]),
                      height: ceil(region[3]) - floor(region[1]))
    guard rect.width > 0, rect.height > 0, let crop = image.cropping(to: rect) else {
        throw NSError(domain:"VisionContourDump", code:3)
    }
    var result: [[[Double]]] = []
    for darkOnLight in [true, false] {
        let request = VNDetectContoursRequest()
        request.revision = VNDetectContourRequestRevision1
        request.maximumImageDimension = 1024
        request.detectsDarkOnLight = darkOnLight
        try VNImageRequestHandler(cgImage: crop, orientation: .up, options: [:]).perform([request])
        for observation in request.results ?? [] {
            for index in 0..<observation.contourCount {
                let contour = try observation.contour(at: index)
                let path = contour.normalizedPoints.map { point -> [Double] in
                    // Vision contour coordinates are normalized from the lower-left
                    // of the crop. Convert to the upright image's top-left pixel frame.
                    let x = rect.minX + Double(point.x) * rect.width
                    let y = rect.minY + (1.0 - Double(point.y)) * rect.height
                    return [x, y]
                }
                if path.count >= 2 { result.append(path) }
            }
        }
    }
    return result
}

@main
struct VisionContourDump {
    static func main() throws {
        guard CommandLine.arguments.count == 4 else {
            fputs("usage: VisionContourDump <source-root> <spec.json> <output.json>\n", stderr)
            exit(2)
        }
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let specURL = URL(fileURLWithPath: CommandLine.arguments[2])
        let outputURL = URL(fileURLWithPath: CommandLine.arguments[3])
        let spec = try JSONSerialization.jsonObject(with: Data(contentsOf: specURL)) as! [String: Any]
        let staticSpec = spec["pullup_video_static"] as! [String: Any]
        let staticRegion = staticSpec["region"] as! [Double]
        let frames = staticSpec["frames"] as! [String]
        var rows: [[String: Any]] = []

        for (index, relative) in frames.enumerated() {
            let url = root.appendingPathComponent(relative)
            let bytes = try Data(contentsOf: url)
            let cg = try image(at: url)
            rows.append([
                "id": "pullup_video_static",
                "frame": index,
                "image": relative,
                "imageSHA256": sha256(bytes),
                "region": staticRegion,
                "contours": try contours(image: cg, region: staticRegion),
            ])
        }

        for still in spec["stills"] as! [[String: Any]] {
            let relative = still["image"] as! String
            let region = still["region"] as! [Double]
            let url = root.appendingPathComponent(relative)
            let bytes = try Data(contentsOf: url)
            let cg = try image(at: url)
            rows.append([
                "id": still["id"]!,
                "frame": 0,
                "image": relative,
                "imageSHA256": sha256(bytes),
                "region": region,
                "contours": try contours(image: cg, region: region),
            ])
        }

        let payload: [String: Any] = [
            "schema_version": 1,
            "source": "VNDetectContoursRequestRevision1",
            "rows": rows,
        ]
        let encoded = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
        try FileManager.default.createDirectory(at: outputURL.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try encoded.write(to: outputURL, options: .atomic)
        print("dumped \(rows.count) contour observations")
    }
}