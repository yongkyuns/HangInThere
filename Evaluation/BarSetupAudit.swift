import Foundation
import CoreGraphics
import ImageIO
import CryptoKit

// Calls the exact setup detector on already-permitted, upright pilot images.
// Annotated guides are input guidance, not automatic full-frame detection.
@main struct BarSetupAudit {
    struct Reference: Decodable {
        let id: String, image: String, sha256: String
        let region: [Double]
        let upperEdgePoints: [Point2D]
    }
    struct Row: Encodable {
        let id: String, imageSHA256: String
        let candidates: [BarLineCandidate]
        let referenceCount: Int
        let firstProposalErrorsPixels: [Double]?
        let status: String
    }
    static func main() async throws {
        let args = CommandLine.arguments
        guard args.count == 4 else { throw NSError(domain:"BarAuditUsage",code:1) }
        let recipe = URL(fileURLWithPath:args[1])
        let root = URL(fileURLWithPath:args[2]).standardizedFileURL.resolvingSymlinksInPath()
        let output = URL(fileURLWithPath:args[3])
        guard !FileManager.default.fileExists(atPath:output.path) else { throw NSError(domain:"BarAuditOutputExists",code:1) }
        let recipes = try JSONDecoder().decode([Reference].self,from:Data(contentsOf:recipe))
        // Require each image to belong to the existing explicitly public pilot manifest.
        let manifest = try JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("manifest.json"))) as? [String:Any]
        let clips = manifest?["clips"] as? [[String:Any]] ?? []
        let allowed = clips.filter {
            guard let rights = $0["rights"] as? [String:Any] else { return false }
            return rights["status"] as? String == "approved" && rights["public_outputs"] as? Bool == true
        }.flatMap { ($0["media"] as? [String:Any])?["files"] as? [[String:String]] ?? [] }
        let detector = VisionBarDetector()
        var rows:[Row] = []
        for reference in recipes {
            let url = root.appendingPathComponent(reference.image).standardizedFileURL.resolvingSymlinksInPath()
            guard url.path.hasPrefix(root.path+"/"), reference.region.count == 4,
                  allowed.contains(where: { $0["path"] == reference.image && $0["sha256"] == reference.sha256 }) else {
                throw NSError(domain:"BarAuditUnapprovedInput",code:1)
            }
            let bytes = try Data(contentsOf:url)
            let hash = SHA256.hash(data:bytes).map { String(format:"%02x",$0) }.joined()
            guard hash == reference.sha256,
                  let source = CGImageSourceCreateWithData(bytes as CFData,nil),
                  let image = CGImageSourceCreateImageAtIndex(source,0,nil) else { throw NSError(domain:"BarAuditInvalidImage",code:1) }
            let props = CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any]
            guard (props?[kCGImagePropertyOrientation] as? Int ?? 1) == 1 else { throw NSError(domain:"BarAuditNonUpright",code:1) }
            let r = reference.region
            let candidates = try await detector.detect(image:image,region:BarRegion(Point2D(x:r[0],y:r[1]),Point2D(x:r[2],y:r[3])))
            let errors = candidates.first.map { candidate in reference.upperEdgePoints.map { candidate.edge.distance(to:$0) } }
            rows.append(Row(id:reference.id,imageSHA256:hash,candidates:candidates,
                referenceCount:reference.upperEdgePoints.count,firstProposalErrorsPixels:errors,
                status:candidates.isEmpty ? "no_candidate" : "proposals_require_confirmation"))
        }
        let encoder = JSONEncoder();encoder.outputFormatting = [.prettyPrinted,.sortedKeys]
        try FileManager.default.createDirectory(at:output.deletingLastPathComponent(),withIntermediateDirectories:true)
        try encoder.encode(rows).write(to:output,options:.withoutOverwriting)
        print("Bar setup audit processed \(rows.count) images. Candidate presence is not automatic bar-identity accuracy.")
    }
}
