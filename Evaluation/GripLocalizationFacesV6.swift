// Source-bound face rectangles for hand ownership, never grip/orientation labels.
import Foundation
import Vision
import ImageIO
import CryptoKit

let args = CommandLine.arguments
guard args.count == 3 else { fatalError("GripLocalizationFacesV6 INPUT_JSONL OUTPUT_JSONL") }
guard !FileManager.default.fileExists(atPath: args[2]) else { fatalError("Preserve prior output") }
let lines = try String(contentsOfFile: args[1], encoding: .utf8).split(separator: "\n")
FileManager.default.createFile(atPath: args[2], contents: nil)
let output = try FileHandle(forWritingTo: URL(fileURLWithPath: args[2]))
defer { try? output.close() }
for line in lines {
    try autoreleasepool {
        var row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
        let path = URL(fileURLWithPath: row["framesDirectory"] as! String).appendingPathComponent(row["image"] as! String)
        let digest = SHA256.hash(data: try Data(contentsOf: path)).map { String(format: "%02x", $0) }.joined()
        guard digest == row["imageSHA256"] as? String,
              let source = CGImageSourceCreateWithURL(path as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fatalError("Changed/unreadable frame") }
        let request = VNDetectFaceRectanglesRequest()
        request.revision = 3
        try VNImageRequestHandler(cgImage: image, orientation: .up, options: [:]).perform([request])
        let faces = (request.results ?? []).map { face -> [String: Any] in
            let box = face.boundingBox
            return ["confidence": Double(face.confidence),
                    "box": [Double(box.minX)*Double(image.width), (1-Double(box.maxY))*Double(image.height),
                            Double(box.maxX)*Double(image.width), (1-Double(box.minY))*Double(image.height)]]
        }
        row["localizationFaces"] = ["backend": "Apple Vision face rectangles", "revision": 3,
            "sourceSHA256": row["recordingId"]!, "imageSHA256": digest, "faces": faces]
        try output.write(contentsOf: JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]))
        try output.write(contentsOf: Data([10]))
    }
}
