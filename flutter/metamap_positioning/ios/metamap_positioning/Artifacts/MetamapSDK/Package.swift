// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MetamapSDK",
    platforms: [.iOS(.v16)],
    products: [
        .library(
            name: "MetamapPositioningCore",
            targets: ["MetamapPositioningCore"]
        ),
        .library(
            name: "MetamapPositioning",
            targets: ["MetamapPositioningCore", "MetamapPositioning"]
        ),
        .library(
            name: "Metamap",
            targets: ["MetamapPositioningCore", "MetamapPositioning", "Metamap"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "MetamapPositioningCore",
            path: "Artifacts/MetamapPositioningCore.xcframework"
        ),
        .binaryTarget(
            name: "MetamapPositioning",
            path: "Artifacts/MetamapPositioning.xcframework"
        ),
        .binaryTarget(
            name: "Metamap",
            path: "Artifacts/Metamap.xcframework"
        ),
    ]
)
