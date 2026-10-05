# Metamaps iOS SDK

A Swift package for iOS 16 or later. The standard `MetamapMapView` embeds a published Metamaps web map from a
`mapSlug` and connects it to BLE and pedestrian dead reckoning positioning from the same SDK. For apps with their
own map UI, the headless `MetamapPositioningClient` is also available.

The [iOS developer guide](../docs/ios-installation.md) walks through installation and setup step by step.

## Products

- `Metamap`: the standard map module. `MetamapMapView` hosts a `WKWebView` that provides search, spots, floors,
  and routing, and bridges native positioning to the web map.
- `MetamapPositioning`: Core Location iBeacon ranging, Core Motion, manifest download and caching, and typed
  events and errors.

Both products link the positioning engine, `Artifacts/MetamapPositioningCore.xcframework`. Keep `Package.swift`
and `Artifacts/` side by side. The SDK contains no client secret.

## Installation

1. Copy this directory (or unzip `MetamapSDK-iOS-<version>.zip`) into your app repository, for example as
   `Vendor/metamap-ios`.
2. In Xcode, choose `File > Add Package Dependencies... > Add Local...` and select that directory.
3. Add the `Metamap` product to your app target. For headless positioning only, add `MetamapPositioning`.

If your app is itself a Swift package, use a relative path:

```swift
dependencies: [
    .package(path: "../Vendor/metamap-ios"),
]
```

Use the local Swift package rather than dragging the XCFramework into your project. The package manages linking
and embedding for you.

Add only the usage descriptions your app needs to `Info.plist`:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Nearby beacons are used to show your location and routes inside the building.</string>
<key>NSMotionUsageDescription</key>
<string>Motion data keeps your location up to date while you walk.</string>
<key>NSMicrophoneUsageDescription</key>
<string>The microphone lets you search the map and ask questions by voice.</string>
<key>NSSpeechRecognitionUsageDescription</key>
<string>Speech recognition turns your voice into map searches and questions.</string>
```

The microphone and speech recognition keys are needed only for voice input in the embedded map view. The SDK does
not use Core Bluetooth directly, so it does not need `NSBluetoothAlwaysUsageDescription`. Without Motion
permission or its usage description, positioning continues with BLE only. The SDK does not request location
permission from `load()` or `configure()`; request it only from a user action, such as tapping a location button,
or from an explicit call in your app.

## Map view

```swift
import Metamap

let mapView = MetamapMapView(configuration: .init(
    mapSlug: "example",
    language: "en",
    positioningPolicy: .userInitiated
))
mapView.delegate = self
view.addSubview(mapView)
try await mapView.load()
```

Only `mapSlug` is required; the production base URL is `https://metamaps.jp`. Optional settings include
`groupId`, `language`, `initialFloorId`, and `previewToken`, a short-lived token that shows an unpublished map. The
view provides `load()` / `reload()`, `requestPositioningAuthorization()`, `startPositioning()` /
`stopPositioning()`, `selectFloor(_:)`, `showSpot(_:)`, `setDestination(_:)`, `setLanguage(_:)`, the center
reticle and its candidate coordinate, `dispose()`, and typed events.

`dispose()` is a `@MainActor` API. Call it from UIKit's `viewDidDisappear` or from an explicit screen-closing
action in SwiftUI, not from a nonisolated `deinit`. The developer guide has a minimal example with cancellation and
dismissal checks.

`resetPedestrianRoute()` discards the step count, step interval, speed, and estimator state that built up while
the user was, for example, reading a description or walking to the start point. It keeps the latest sensor
heading, the manifest, and permissions, and starts the next walking segment. The delegate receives `position`
even before the bridge is ready, as well as `capabilities`, `motionHeading`, and, when opted in, `beaconSignals`.

The web view rejects navigation and bridge messages from any origin other than the configured one, and validates
the bridge major version, the map and group, and a monotonically increasing sequence number. Raw BLE and motion
samples, long-lived tokens, and secrets are never passed to JavaScript. The IDs passed to `showSpot(_:)` and
`setDestination(_:)` are the stable keys of public spots, such as `6Y6SSBY5` (keys in the older `spot_` format are
also accepted).

## Headless positioning

```swift
import MetamapPositioning

let client = MetamapPositioningClient(configuration: .init(mapSlug: "example"))
try await client.configure()                 // Never shows a permission dialog.
try await client.requestAuthorization()      // Call from a user action.
try await client.start()

for await event in client.events {
    if case .position(let update) = event {
        print(update.estimate, update.wgs84 as Any)
    }
}
```

The SDK validates the manifest with its ETag and a SHA-256 digest of its RFC 8785 canonical JSON, and caches it
atomically with Data Protection. Without a network connection, it uses the last validated manifest for up to 7
days by default. Positions are computed on the device, and the SDK does not send coordinates or RSSI values to any
server. The monotonic clock (system boot time) is used only to measure timer and observation intervals; this is
the reason declared in the privacy manifest.

## Foreground only

This version supports foreground positioning. When the app moves to the background, the screen locks, or the user
switches apps, the SDK stops scanning, sensors, and timers and reports `pausedBackground` instead of continuing to
report a stale location. When the app returns to the foreground, it revalidates the manifest, resets the
estimator, and resumes from `recovering`.

The SDK does not request Always location permission or the location background mode.

## Beacon diagnostics

To check the relationship between the device and the registered beacons on the device itself, set
`PositioningConfiguration(beaconDiagnosticsEnabled: true)` and subscribe to `MetamapPositioningEvent.beaconSignals`
in headless mode. With the map view, `MetamapMapViewConfiguration(showsBeaconDiagnostics: true)` draws the
registered beacon positions, rings at the RSSI-estimated distance, and residual lines to the estimated device
position in the embedded 3D map.

Diagnostics are off by default. The snapshot stays on the device and is never sent to a server, telemetry, or
persistent storage. The RSSI distance is an estimate from calibration values and a path-loss model, not a second
measured position of the beacon.

## Samples

- `Samples/UIKit`: a UIKit view controller that shows the map view.
- `Samples/SwiftUI`: a SwiftUI screen that wraps the map view.

## Verification

```bash
swift test
xcodebuild -scheme Metamap \
  -destination 'platform=macOS,variant=Mac Catalyst' build
```

Real beacons, permission prompts, background and foreground transitions, battery use, and the web map bridge
need to be checked on a physical iPhone.
