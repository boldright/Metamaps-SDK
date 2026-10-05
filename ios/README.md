# Metamaps iOS SDK

A Swift package for iOS 16 or later that embeds maps built with Metamaps in your app. `MetamapsMapView` shows the
published Metamaps map for a `mapSlug`, with search, spots, floors, routes, and voice input. Facilities with
installed beacons can also show the user's indoor location on the same map.

The [iOS developer guide](../docs/ios-installation.md) walks through installation and setup step by step.

## Products

- `Metamaps`: the map view. `MetamapsMapView` hosts a `WKWebView` with the Metamaps map and, for maps with indoor
  positioning, connects it to positioning on the device. `import Metamaps` also gives you the shared types, such as
  `MetamapsError` and `MetamapsSDK`.
- `MetamapsPositioning`: indoor positioning without the map view (`MetamapsPositioningClient`), for apps that draw
  their own map.

Both products link the positioning engine, `Artifacts/MetamapsPositioningCore.xcframework`. Keep `Package.swift`
and `Artifacts/` side by side. The SDK contains no client secret.

## Installation

1. Copy this directory (or unzip `MetamapsSDK-iOS-<version>.zip`) into your app repository, for example as
   `Vendor/metamaps-ios`.
2. In Xcode, choose `File > Add Package Dependencies... > Add Local...` and select that directory.
3. Add the `Metamaps` product to your app target. For positioning without the map view, add
   `MetamapsPositioning` instead.

If your app is itself a Swift package, use a relative path:

```swift
dependencies: [
    .package(path: "../Vendor/metamaps-ios"),
]
```

Use the local Swift package rather than dragging the XCFramework into your project. The package manages linking
and embedding for you.

Add these purpose strings to `Info.plist`, even if you only show maps:

```xml
<key>NSMicrophoneUsageDescription</key>
<string>The microphone lets you search the map and ask questions by voice.</string>
<key>NSSpeechRecognitionUsageDescription</key>
<string>Speech recognition turns your voice into map searches and questions.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Nearby beacons are used to show your location and routes inside the building.</string>
<key>NSMotionUsageDescription</key>
<string>Motion data keeps your location up to date while you walk.</string>
```

The microphone and speech recognition keys are used by the map's voice input. The location and motion keys are
used only by indoor positioning; App Store Connect checks for them because the SDK links Core Location and Core
Motion, and the system never shows those prompts unless positioning starts. The SDK does not use Core Bluetooth
directly, so it does not need `NSBluetoothAlwaysUsageDescription`.

## Map view

```swift
import Metamaps

let mapView = MetamapsMapView(configuration: .init(mapSlug: "example", language: "en"))
mapView.delegate = self
view.addSubview(mapView)
try await mapView.load()
```

Only `mapSlug` is required; the production base URL is `https://metamaps.jp`. Optional settings include
`groupId`, `language`, `initialFloorId`, and `previewToken`, a short-lived token that shows an unpublished map. The
view provides `load()` / `reload()`, `selectFloor(_:)`, `showSpot(_:)`, `setDestination(_:)`, `setLanguage(_:)`,
the center reticle and its candidate coordinate, `dispose()`, and typed events.

`dispose()` is a `@MainActor` API. Call it from UIKit's `viewDidDisappear` or from an explicit screen-closing
action in SwiftUI, not from a nonisolated `deinit`. The developer guide has a minimal example with cancellation and
dismissal checks.

The web view rejects navigation and bridge messages from any origin other than the configured one, and validates
the bridge major version, the map and group, and a monotonically increasing sequence number. Long-lived tokens
and secrets are never passed to JavaScript. The IDs passed to `showSpot(_:)` and `setDestination(_:)` are the
stable keys of public spots, such as `6Y6SSBY5` (keys in the older `spot_` format are also accepted).

## Indoor positioning (optional)

Indoor positioning works for maps whose facility has installed beacons and enabled positioning in Metamaps. For
other maps, the map view downloads no positioning data and reports no positioning errors.

The map view starts positioning according to `positioningStartTrigger` and `positioningPolicy`. Your app can also
call `requestPositioningAuthorization()` and `startPositioning()` / `stopPositioning()` from its own location
button. The delegate then receives `position`, `capabilities`, `motionHeading`, and, when opted in,
`beaconSignals`. Without Motion & Fitness permission, positioning continues without step counting.

`resetPedestrianRoute()` discards the step count, step interval, speed, and estimator state that built up while
the user was, for example, reading a description or walking to the start point. It keeps the latest sensor
heading, the positioning data, and permissions, and starts the next walking segment.

### Positioning without the map view

```swift
import MetamapsPositioning

let client = MetamapsPositioningClient(configuration: .init(mapSlug: "example"))
try await client.configure()                 // Never shows a permission dialog.
try await client.requestAuthorization()      // Call from a user action.
try await client.start()

for await event in client.events {
    if case .position(let update) = event {
        print(update.estimate, update.wgs84 as Any)
    }
}
```

The SDK validates the positioning data (the manifest) with its ETag and a SHA-256 digest of its RFC 8785 canonical
JSON, and caches it atomically with Data Protection. Without a network connection, it uses the last validated
manifest for up to 7 days by default. Positions are computed on the device, and the SDK does not send coordinates
or signal strengths to any server. Raw Bluetooth and motion samples are never passed to JavaScript. The monotonic
clock (system boot time) is used only to measure timer and observation intervals; this is the reason declared in
the privacy manifest.

### Foreground only

This version supports foreground positioning. When the app moves to the background, the screen locks, or the user
switches apps, the SDK stops scanning, sensors, and timers and reports `pausedBackground` instead of continuing to
report a stale location. When the app returns to the foreground, it revalidates the manifest, resets the
estimator, and resumes from `recovering`. The SDK does not request Always location permission or the location
background mode.

### Beacon diagnostics

To check the relationship between the device and the registered beacons on the device itself, set
`PositioningConfiguration(beaconDiagnosticsEnabled: true)` and subscribe to `MetamapsPositioningEvent.beaconSignals`
with `MetamapsPositioningClient`. With the map view, `MetamapsMapViewConfiguration(showsBeaconDiagnostics: true)`
draws the registered beacon positions, rings at the signal-estimated distance, and residual lines to the estimated
device position in the map.

Diagnostics are off by default. The snapshot stays on the device and is never sent to a server, telemetry, or
persistent storage. The estimated distance comes from calibration values and a path-loss model; it is not a second
measured position of the beacon.

## Samples

- `Samples/UIKit`: a UIKit view controller that shows the map view.
- `Samples/SwiftUI`: a SwiftUI screen that wraps the map view.

## Verification

```bash
swift test
xcodebuild -scheme Metamaps \
  -destination 'platform=macOS,variant=Mac Catalyst' build
```

Real beacons, permission prompts, background and foreground transitions, battery use, and the map bridge need to
be checked on a physical iPhone.
