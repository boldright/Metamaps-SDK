// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MetamapsSDK",
    platforms: [.iOS(.v16)],
    products: [
        .library(
            name: "MetamapsPositioningCore",
            targets: ["MetamapsPositioningCore"]
        ),
        .library(
            name: "MetamapsPositioning",
            targets: ["MetamapsPositioningCore", "MetamapsPositioning"]
        ),
        .library(
            name: "Metamaps",
            targets: ["MetamapsPositioningCore", "MetamapsPositioning", "Metamaps"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "MetamapsPositioningCore",
            path: "Artifacts/MetamapsPositioningCore.xcframework"
        ),
        .binaryTarget(
            name: "MetamapsPositioning",
            path: "Artifacts/MetamapsPositioning.xcframework"
        ),
        .binaryTarget(
            name: "Metamaps",
            path: "Artifacts/Metamaps.xcframework"
        ),
    ]
)
