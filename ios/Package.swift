// swift-tools-version: 6.2
import Foundation
import PackageDescription

// The positioning engine (`MetamapPositioningCore`) ships as a prebuilt XCFramework in `Artifacts/`.
var engineSourcePackage: URL?
let usesEngineSource = engineSourcePackage != nil

let engineDependency: Target.Dependency = usesEngineSource
    ? .product(name: "MetamapPositioningCore", package: "MetamapPositioningCore")
    : .target(name: "MetamapPositioningCore")
let engineTargets: [Target] = usesEngineSource
    ? []
    : [.binaryTarget(name: "MetamapPositioningCore", path: "Artifacts/MetamapPositioningCore.xcframework")]
let engineProductTargets = usesEngineSource ? [] : ["MetamapPositioningCore"]

let package = Package(
    name: "MetamapSDK",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "MetamapPositioning", targets: engineProductTargets + ["MetamapPositioning"]),
        .library(name: "Metamap", targets: engineProductTargets + ["MetamapPositioning", "Metamap"]),
    ],
    dependencies: engineSourcePackage.map { [.package(name: "MetamapPositioningCore", path: $0.path)] } ?? [],
    targets: engineTargets + [
        .target(
            name: "MetamapPositioning",
            dependencies: [engineDependency],
            resources: [.process("Resources")]
        ),
        .target(
            name: "Metamap",
            dependencies: ["MetamapPositioning", engineDependency],
            path: "Sources/MetamapMapView"
        ),
        .testTarget(
            name: "MetamapPositioningTests",
            dependencies: ["MetamapPositioning", engineDependency],
            resources: [.process("Resources")]
        ),
        .testTarget(name: "MetamapMapViewTests", dependencies: ["Metamap"]),
    ]
)
