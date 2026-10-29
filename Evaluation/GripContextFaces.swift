// Offline source-bound actual face detection, separate from inferred body joints.
import Foundation
import Vision
import ImageIO
import CoreGraphics
import CryptoKit

let args=CommandLine.arguments
guard args.count==4 else { fatalError("GripContextFaces CONTEXT_JSONL OUTPUT_JSONL RECEIPT_JSON") }
let lines=try String(contentsOfFile:args[1],encoding:.utf8).split(separator:"\n")
FileManager.default.createFile(atPath:args[2],contents:nil)
let output=try FileHandle(forWritingTo:URL(fileURLWithPath:args[2]));defer {try? output.close()}
var counts:[String:Int]=[:]
for line in lines {
    try autoreleasepool {
        var row=try JSONSerialization.jsonObject(with:Data(line.utf8)) as! [String:Any]
        var context=row["context"] as! [String:Any]
        context["bodyPoseFaceCues"]=context["faceCues"]
        let pose=row["pose"] as! [String:Any]
        var cues=[Double](repeating:0,count:6)
        var detections:[[String:Any]]=[]
        var status="contextUnavailable"
        if context["status"] as? String == "ready" {
            cues[5]=1
            let people=pose["people"] as! [[String:Any]]
            let person=people[context["personIndex"] as! Int]
            let points=person["landmarks"] as! [[String:Any]]
            let shoulders=points.filter { ["leftShoulder","rightShoulder"].contains($0["joint"] as! String) }
            guard shoulders.count==2 else {fatalError("Missing shoulders")}
            let xy=shoulders.map {$0["position"] as! [String:Double]}
            let span=hypot(xy[0]["x"]!-xy[1]["x"]!,xy[0]["y"]!-xy[1]["y"]!)
            let cx=(xy[0]["x"]!+xy[1]["x"]!)/2,cy=(xy[0]["y"]!+xy[1]["y"]!)/2
            let path=URL(fileURLWithPath:row["framesDirectory"] as! String).appendingPathComponent(row["image"] as! String)
            let digest=SHA256.hash(data:try Data(contentsOf:path)).map {String(format:"%02x",$0)}.joined()
            guard digest==row["imageSHA256"] as? String else {fatalError("Changed source frame")}
            guard let source=CGImageSourceCreateWithURL(path as CFURL,nil),let image=CGImageSourceCreateImageAtIndex(source,0,nil) else {fatalError("Cannot decode source")}
            let request=VNDetectFaceRectanglesRequest();request.revision=3
            try VNImageRequestHandler(cgImage:image,orientation:.up,options:[:]).perform([request])
            let candidates=(request.results ?? []).filter { face in
                let x=Double(face.boundingBox.midX)*Double(image.width)
                let y=(1-Double(face.boundingBox.midY))*Double(image.height)
                return x>=cx-span*0.7 && x<=cx+span*0.7 && y>=cy-span*1.5 && y<=cy+span*0.15 &&
                    Double(face.boundingBox.width)*Double(image.width)>=span*0.1
            }
            for face in candidates {
                detections.append(["confidence":Double(face.confidence),
                    "box":[Double(face.boundingBox.minX)*Double(image.width),(1-Double(face.boundingBox.maxY))*Double(image.height),
                        Double(face.boundingBox.width)*Double(image.width),Double(face.boundingBox.height)*Double(image.height)],
                    "yaw":face.yaw?.doubleValue as Any? ?? NSNull(),"roll":face.roll?.doubleValue as Any? ?? NSNull()])
            }
            status=candidates.isEmpty ? "noVerifiedFace" : (candidates.count==1 ? "verifiedFace" : "ambiguousFaces")
            if candidates.count==1 {
                let face=candidates[0]
                cues[0]=Double(face.confidence)
                cues[1]=min(1,Double(face.boundingBox.width)*Double(image.width)/span)
                cues[2]=min(1,Double(face.boundingBox.height)*Double(image.height)/span)
                cues[3]=min(1,abs(face.yaw?.doubleValue ?? 0)/Double.pi)
                cues[4]=min(1,abs(face.roll?.doubleValue ?? 0)/Double.pi)
            }
        }
        counts[status,default:0]+=1
        context["faceCues"]=cues
        context["faceDetector"]=["backend":"Apple Vision face rectangles","requestRevision":3,"status":status,
            "detections":detections,"sourceId":row["recordingId"]!,"imageSHA256":row["imageSHA256"]!,"timestamp":pose["timestamp"]!]
        row["context"]=context
        try output.write(contentsOf:JSONSerialization.data(withJSONObject:row,options:[.sortedKeys]))
        try output.write(contentsOf:Data([10]))
    }
}
let receipt:[String:Any]=["rows":lines.count,"counts":counts,"cueOrder":["actualFaceConfidence","faceWidthPerShoulderSpan","faceHeightPerShoulderSpan","absoluteYawPerPi","absoluteRollPerPi","contextAvailable"],
    "ownership":"unique face centered in selected athlete head region; no face is missing evidence, not a forced view label"]
try JSONSerialization.data(withJSONObject:receipt,options:[.prettyPrinted,.sortedKeys]).write(to:URL(fileURLWithPath:args[3]))
