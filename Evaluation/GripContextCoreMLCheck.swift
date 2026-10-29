// Development-only native pixel inference check. Does not modify the app or videos.
import Foundation
import CoreML
import CoreGraphics
import ImageIO
import CoreVideo

func buffer(_ path: String) throws -> CVPixelBuffer {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath:path) as CFURL,nil),
          let image = CGImageSourceCreateImageAtIndex(source,0,nil),image.width == 128,image.height == 128 else {
        throw NSError(domain:"GripPatch",code:1,userInfo:[NSLocalizedDescriptionKey:"Expected original 128x128 patch"])
    }
    var output:CVPixelBuffer?
    let attributes = [kCVPixelBufferCGImageCompatibilityKey:true,kCVPixelBufferCGBitmapContextCompatibilityKey:true] as CFDictionary
    guard CVPixelBufferCreate(kCFAllocatorDefault,128,128,kCVPixelFormatType_32BGRA,attributes,&output) == kCVReturnSuccess,
          let pixelBuffer = output else { throw NSError(domain:"GripPatch",code:2) }
    CVPixelBufferLockBaseAddress(pixelBuffer,[])
    defer { CVPixelBufferUnlockBaseAddress(pixelBuffer,[]) }
    let bitmap = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue
    guard let context=CGContext(data:CVPixelBufferGetBaseAddress(pixelBuffer),width:128,height:128,bitsPerComponent:8,
        bytesPerRow:CVPixelBufferGetBytesPerRow(pixelBuffer),space:CGColorSpace(name:CGColorSpace.sRGB)!,bitmapInfo:bitmap)
        else { throw NSError(domain:"GripPatch",code:3) }
    context.draw(image,in:CGRect(x:0,y:0,width:128,height:128))
    if let dump=ProcessInfo.processInfo.environment["GRIP_PATCH_PIXEL_DUMP"] {
        context.flush()
        try Data(bytes:CVPixelBufferGetBaseAddress(pixelBuffer)!,count:CVPixelBufferGetBytesPerRow(pixelBuffer)*128)
            .write(to:URL(fileURLWithPath:dump))
    }
    return pixelBuffer
}

let args=CommandLine.arguments
guard args.count == 4 else { fatalError("GripContextCoreMLCheck MODEL_PACKAGE PREDICTIONS_JSONL OUTPUT_JSONL") }
let configuration=MLModelConfiguration();configuration.computeUnits = .cpuOnly
let compiled=try MLModel.compileModel(at:URL(fileURLWithPath:args[1]))
defer { try? FileManager.default.removeItem(at:compiled) }
let model=try MLModel(contentsOf:compiled,configuration:configuration)
let lines=try String(contentsOfFile:args[2],encoding:.utf8).split(separator:"\n")
FileManager.default.createFile(atPath:args[3],contents:nil)
let output=try FileHandle(forWritingTo:URL(fileURLWithPath:args[3]));defer { try? output.close() }
for line in lines {
    let row=try JSONSerialization.jsonObject(with:Data(line.utf8)) as! [String:Any]
    guard let path=row["patch"] as? String else { continue }
    let pixels=try buffer(path)
    let contextRow=row["context"] as! [String:Any]
    let contextPixels:CVPixelBuffer
    if contextRow["status"] as? String == "ready" {
        contextPixels=try buffer(contextRow["image"] as! String)
    } else {
        var blank:CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault,128,128,kCVPixelFormatType_32BGRA,nil,&blank)
        contextPixels=blank!
        CVPixelBufferLockBaseAddress(contextPixels,[])
        memset(CVPixelBufferGetBaseAddress(contextPixels),0,CVPixelBufferGetBytesPerRow(contextPixels)*128)
        CVPixelBufferUnlockBaseAddress(contextPixels,[])
    }
    let cues=try MLMultiArray(shape:[1,6],dataType:.float32)
    for (i,v) in (contextRow["faceCues"] as! [Double]).enumerated() { cues[i]=NSNumber(value:v) }
    let input=try MLDictionaryFeatureProvider(dictionary:["hand":MLFeatureValue(pixelBuffer:pixels),
        "context":MLFeatureValue(pixelBuffer:contextPixels),"faceCues":MLFeatureValue(multiArray:cues)])
    let start=ProcessInfo.processInfo.systemUptime
    let prediction=try model.prediction(from:input)
    let elapsed=ProcessInfo.processInfo.systemUptime-start
    guard let scores=prediction.featureValue(for:"scores")?.multiArrayValue,scores.count == 2 else {fatalError("Invalid output")}
    guard let orientation=prediction.featureValue(for:"orientation")?.multiArrayValue,orientation.count == 3 else {fatalError("Invalid orientation")}
    let result:[String:Any] = ["patch":path,"scores":[scores[0].doubleValue,scores[1].doubleValue],
        "orientation":(0..<3).map {orientation[$0].doubleValue},"hostCpuSeconds":elapsed]
    try output.write(contentsOf:JSONSerialization.data(withJSONObject:result,options:[.sortedKeys]))
    try output.write(contentsOf:Data([10]))
}
