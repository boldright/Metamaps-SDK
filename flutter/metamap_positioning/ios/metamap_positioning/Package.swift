// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription
import Foundation

// Flutter exposes this package through a symlink in the host app's generated
// Swift package. Resolve the checked-out package before locating the native SDK
// so the repository layout works from both locations.
let checkedOutPackageDirectory = URL(fileURLWithPath: #filePath)
    .resolvingSymlinksInPath()
    .deletingLastPathComponent()
let bundledSdk = checkedOutPackageDirectory
    .appendingPathComponent("Artifacts/MetamapSDK")
    .standardizedFileURL
let monorepoSdk = checkedOutPackageDirectory
    .appendingPathComponent("../../../../ios")
    .standardizedFileURL
let usesBundledSdk = FileManager.default.fileExists(
    atPath: bundledSdk.appendingPathComponent("Package.swift").path
)
let nativeSdkPath = (usesBundledSdk ? bundledSdk : monorepoSdk).path
let nativeSdkPackageName = "MetamapSDK"

let package = Package(
    name: "metamap_positioning",
    platforms: [
        .iOS("16.0")
    ],
    products: [
        .library(name: "metamap-positioning", targets: ["metamap_positioning"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        .package(
            name: nativeSdkPackageName,
            path: nativeSdkPath
        ),
    ],
    targets: [
        .target(
            name: "metamap_positioning",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "MetamapPositioning", package: nativeSdkPackageName),
                .product(name: "Metamap", package: nativeSdkPackageName),
            ],
            resources: [
                .process("PrivacyInfo.xcprivacy"),
            ]
        )
    ]
)
