// Diagnose detector misses separately from selected-athlete association rejects.
// Read-only audit: never changes context manifests or model inputs.
import Foundation
import Vision
import ImageIO
import CoreGraphics
import CryptoKit

let args = CommandLine.arguments
guard args.count == 3 else { fatalError("GripFaceDetectionAudit ROW_JSON OUTPUT_JSON") }
let row = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: args[1]))) as! [String: Any]
let context = row["context"] as! [String: Any]
guard context["status"] as? String == "ready" else { fatalError("Selected-athlete context required") }
let pose = row["pose"] as! [String: Any]
let people = pose["people"] as! [[String: Any]]
let person = people[context["personIndex"] as! Int]
let points = person["landmarks"] as! [[String: Any]]
let shoulders = points.filter { ["leftShoulder", "rightShoulder"].contains($0["joint"] as! String) }
guard shoulders.count == 2 else { fatalError("Missing shoulders") }
let xy = shoulders.map { $0["position"] as! [String: Double] }
let span = hypot(xy[0]["x"]! - xy[1]["x"]!, xy[0]["y"]! - xy[1]["y"]!)
let cx = (xy[0]["x"]! + xy[1]["x"]!) / 2
let cy = (xy[0]["y"]! + xy[1]["y"]!) / 2
let path = URL(fileURLWithPath: row["framesDirectory"] as! String).appendingPathComponent(row["image"] as! String)
let digest = SHA256.hash(data: try Data(contentsOf: path)).map { String(format: "%02x", $0) }.joined()
guard digest == row["imageSHA256"] as? String else { fatalError("Changed frame") }
guard let source = CGImageSourceCreateWithURL(path as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fatalError("Cannot decode frame") }
let request = VNDetectFaceRectanglesRequest()
request.revision = 3
try VNImageRequestHandler(cgImage: image, orientation: .up, options: [:]).perform([request])
var observations: [[String: Any]] = []
for face in request.results ?? [] {
    let x = Double(face.boundingBox.midX) * Double(image.width)
    let y = (1 - Double(face.boundingBox.midY)) * Double(image.height)
    let width = Double(face.boundingBox.width) * Double(image.width)
    let inRegion = x >= cx - span * 0.7 && x <= cx + span * 0.7 && y >= cy - span * 1.5 && y <= cy + span * 0.15
    observations.append([
        "confidence": Double(face.confidence),
        "box": [Double(face.boundingBox.minX) * Double(image.width),
                (1 - Double(face.boundingBox.maxY)) * Double(image.height), width,
                Double(face.boundingBox.height) * Double(image.height)],
        "insideAthleteHeadRegion": inRegion,
        "passesMinimumSize": width >= span * 0.1,
        "acceptedByOwnershipFilter": inRegion && width >= span * 0.1
    ])
}
let report: [String: Any] = [
    "caseId": row["caseId"]!, "imageSHA256": digest, "timestamp": pose["timestamp"]!,
    "backend": "Apple Vision face rectangles", "revision": 3,
    "imageSize": [image.width, image.height], "shoulderSpan": span,
    "athleteHeadRegion": [cx - span * 0.7, cy - span * 1.5, cx + span * 0.7, cy + span * 0.15],
    "originalStatus": (context["faceDetector"] as! [String: Any])["status"]!,
    "rawDetections": observations
]
try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
    .write(to: URL(fileURLWithPath: args[2]))
print("Raw faces: \(observations.count); accepted: \(observations.filter { $0["acceptedByOwnershipFilter"] as? Bool == true }.count)")
