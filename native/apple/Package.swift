// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ObservedRailPixels",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "ObservedRailPixels", targets: ["ObservedRailPixels"])],
    targets: [
        .binaryTarget(name: "opencv2",
            url: "https://github.com/yeatse/opencv-spm/releases/download/4.11.0/opencv2.xcframework.zip",
            checksum: "4b1e76f3d1ce19369ff7e151d1c564926b4d23b5515c62fc65517dfd395c3929"),
        .target(name: "CObservedRailPixels", dependencies: [
            .target(name: "opencv2", condition: .when(platforms: [.macOS]))
        ], exclude: ["Vendor/OpenCV453/LICENSE", "Vendor/OpenCV453/source-lock.json"], cxxSettings: [
            .headerSearchPath("Vendor/OpenCV453/include", .when(platforms: [.iOS]))
        ], linkerSettings: [
            .linkedFramework("MediaPipeTasksCommon", .when(platforms: [.iOS])),
            .linkedFramework("CoreGraphics"), .linkedFramework("AVFoundation"),
            .linkedFramework("CoreImage"), .linkedFramework("CoreMedia"),
            .linkedFramework("CoreVideo"), .linkedFramework("Accelerate"),
            .linkedFramework("OpenCL", .when(platforms: [.macOS])), .linkedLibrary("c++")]),
        .target(name: "ObservedRailPixels", dependencies: ["CObservedRailPixels"], resources: [.copy("Resources/OpenCV-LICENSE"), .copy("Resources/OpenCV-HEADER-NOTICES")]),
        .testTarget(name: "ObservedRailPixelsTests", dependencies: ["ObservedRailPixels"])
    ], cxxLanguageStandard: .cxx17
)
