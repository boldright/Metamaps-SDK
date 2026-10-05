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
    .appendingPathComponent("Artifacts/MetamapsSDK")
    .standardizedFileURL
let monorepoSdk = checkedOutPackageDirectory
    .appendingPathComponent("../../../../ios")
    .standardizedFileURL
let usesBundledSdk = FileManager.default.fileExists(
    atPath: bundledSdk.appendingPathComponent("Package.swift").path
)
let nativeSdkPath = (usesBundledSdk ? bundledSdk : monorepoSdk).path
let nativeSdkPackageName = "MetamapsSDK"

let package = Package(
    name: "metamaps_flutter",
    platforms: [
        .iOS("16.0")
    ],
    products: [
        .library(name: "metamaps-flutter", targets: ["metamaps_flutter"])
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
            name: "metamaps_flutter",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "MetamapsPositioning", package: nativeSdkPackageName),
                .product(name: "Metamaps", package: nativeSdkPackageName),
            ],
            resources: [
                .process("PrivacyInfo.xcprivacy"),
            ]
        )
    ]
)
