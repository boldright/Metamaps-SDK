// swift-tools-version: 6.2
import Foundation
import PackageDescription

// The positioning engine (`MetamapsPositioningCore`) ships as a prebuilt XCFramework in `Artifacts/`.
var engineSourcePackage: URL?
let usesEngineSource = engineSourcePackage != nil

let engineDependency: Target.Dependency = usesEngineSource
    ? .product(name: "MetamapsPositioningCore", package: "MetamapsPositioningCore")
    : .target(name: "MetamapsPositioningCore")
let engineTargets: [Target] = usesEngineSource
    ? []
    : [.binaryTarget(name: "MetamapsPositioningCore", path: "Artifacts/MetamapsPositioningCore.xcframework")]
let engineProductTargets = usesEngineSource ? [] : ["MetamapsPositioningCore"]

let package = Package(
    name: "MetamapsSDK",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "Metamaps", targets: engineProductTargets + ["MetamapsPositioning", "Metamaps"]),
        .library(name: "MetamapsPositioning", targets: engineProductTargets + ["MetamapsPositioning"]),
    ],
    dependencies: engineSourcePackage.map { [.package(name: "MetamapsPositioningCore", path: $0.path)] } ?? [],
    targets: engineTargets + [
        .target(
            name: "MetamapsPositioning",
            dependencies: [engineDependency],
            resources: [.process("Resources")]
        ),
        .target(
            name: "Metamaps",
            dependencies: ["MetamapsPositioning", engineDependency],
            path: "Sources/MetamapsMapView"
        ),
        .testTarget(
            name: "MetamapsPositioningTests",
            dependencies: ["MetamapsPositioning", engineDependency],
            resources: [.process("Resources")]
        ),
        .testTarget(name: "MetamapsMapViewTests", dependencies: ["Metamaps"]),
    ]
)
