# Metamaps SDK

The Metamaps SDK embeds a map built with [Metamaps](https://metamaps.jp) in an iOS, Android, or Flutter app and
shows the user's indoor location using existing iBeacons and the phone's motion sensors.

You pass one `mapSlug`, and the app gets the full web map: search, spots, floor switching, routing, the
current-location marker with its 95% accuracy circle, and routes that start from the current location. Beacon
placements and calibration values come from a positioning manifest that Metamaps serves, so your app does not
store any installation data.

## Repository layout

| Path | Contents |
|---|---|
| [`ios/`](ios/) | Swift package with the `Metamap` (map view) and `MetamapPositioning` (headless positioning) products |
| [`android/`](android/) | Gradle build for the `metamap-mapview` and `metamap-positioning` libraries, with samples |
| [`flutter/metamap_positioning/`](flutter/metamap_positioning/) | Flutter plugin that wraps the iOS and Android SDKs |
| [`docs/`](docs/) | Developer guides for each platform and the map query parameter reference |
| [`CHANGELOG.md`](CHANGELOG.md) | Changes in each version |

The positioning engine ships as a prebuilt binary: `ios/Artifacts/MetamapPositioningCore.xcframework` for iOS
and `android/maven-repository/` for Android. The Flutter plugin bundles the same binaries. The wrapper source in
this repository builds against them; you do not need any other download.

## Requirements

| Platform | Minimum | Toolchain used for this release |
|---|---|---|
| iOS | iOS 16 | Xcode 26 (Swift 6.2) or later |
| Android | minSdk 24, compileSdk 36 or later, JDK 17 | Gradle 9.8.0, Android Gradle Plugin 9.4.1, Kotlin 2.4.20 |
| Flutter | Flutter 3.44.9, Dart 3.12.2, plus the iOS and Android minimums above | Flutter 3.44.9 |

All three platforms share one version number. Release ZIPs record it in a `VERSION` file.

## Two integration modes

| Mode | Your app provides | The SDK provides |
|---|---|---|
| **Embedded map view** (recommended) | The view's position in your layout and a `mapSlug` | The whole map UI, the WebView lifecycle, BLE and pedestrian dead reckoning positioning, and typed events |
| Headless positioning | Your own map, UI, and route display | BLE and pedestrian dead reckoning positioning, a stream of position estimates, manifest download and caching, and typed errors |

Use the embedded map view unless you need your own map rendering. With headless positioning, your app has to
draw the current-location marker, the accuracy circle, floor following, and the route origin itself.

## Quick start

Each developer guide covers installation, permissions, events, language changes, and SDK updates step by step:

- [iOS developer guide](docs/ios-installation.md)
- [Android developer guide](docs/android-installation.md)
- [Flutter developer guide](docs/flutter-installation.md)
- [Appendix: map view query parameters](docs/APPENDIX-QUERY-PARAMETERS.md)

### iOS

```swift
import Metamap

let mapView = MetamapMapView(configuration: .init(mapSlug: "example", language: "en"))
mapView.delegate = self
view.addSubview(mapView)
try await mapView.load()
```

### Android

```kotlin
val mapView = MetamapMapView(context)
mapView.configure(MetamapMapViewConfiguration(mapSlug = "example", language = "en"))
mapView.load()
```

### Flutter

```dart
MetamapMapView(
  configuration: MetamapMapViewConfiguration(mapSlug: 'example', language: 'en'),
  onEvent: _handleEvent,
)
```

`example` is a placeholder. Use the slug of the public map configured in the Metamaps console.

## API documentation

Every public type, method, and property has a documentation comment. After adding the SDK, read them in Xcode
Quick Help, Android Studio Quick Documentation, or your Dart IDE's hover (or run `dart doc` in the plugin
directory). In the source, start from:

- iOS: [`MetamapMapView.swift`](ios/Sources/MetamapMapView/MetamapMapView.swift) and
  [`MapViewContracts.swift`](ios/Sources/MetamapMapView/MapViewContracts.swift)
- Android: [`MetamapMapView.kt`](android/metamap-mapview/src/main/kotlin/jp/metamaps/mapview/MetamapMapView.kt) and
  [`MapViewContracts.kt`](android/metamap-mapview/src/main/kotlin/jp/metamaps/mapview/MapViewContracts.kt)
- Flutter: [`map_view.dart`](flutter/metamap_positioning/lib/src/map_view.dart) and
  [`models.dart`](flutter/metamap_positioning/lib/src/models.dart)

## Features shared by all platforms

iOS, Android, and Flutter expose the same public contract. The Flutter plugin is a thin layer over the Swift and
Kotlin SDKs and does not contain its own estimator.

| Feature | iOS | Android | Flutter |
|---|---|---|---|
| Reset the walking baseline | `resetPedestrianRoute()` | `resetPedestrianRoute()` | `resetPedestrianRoute()` |
| Device heading | `motionHeading` | `MotionHeading` | `MotionHeadingEvent` / `MapMotionHeadingChanged` |
| Nearby registered beacons | `beaconSignals` | `BeaconSignals` | `BeaconSignalsEvent` / `MapBeaconSignalsChanged` |
| Beacon diagnostics opt-in | `beaconDiagnosticsEnabled` / `showsBeaconDiagnostics` | Same names | Same names |
| Map view web settings | `additionalQuery` / `retryPolicy` / `userAgentAppendix` / `isWebViewInspectable` | Same names | Same names |
| External link handling | Delegate and `openExternalLink(_:)` | Handler and `openExternalLink()` | Callback and `openExternalLink()` |
| WGS 84 altitude | `Wgs84Position.elevationM` | Same name | Same name |

`resetPedestrianRoute()` discards the step count, step interval, speed, and estimator state that built up while
the user was, for example, reading a description or walking to the start point. It keeps the latest sensor
heading and starts the next walking segment. It is different from a general reset, which discards the map, the
manifest, and permissions.

The beacon snapshot is meant for on-device diagnostics and is available only when you opt in. It never contains
raw BLE or motion samples. The standard map view has no heading HUD; web content that needs the heading can listen
for the `metamap:motion-heading` custom event, which carries the heading the native map view sends.

## Permissions

Declare only the permissions your app uses.

| Platform | Permission | Purpose |
|---|---|---|
| iOS | `NSLocationWhenInUseUsageDescription` | iBeacon ranging (When In Use) |
| iOS | `NSMotionUsageDescription` | Step counting; without it, positioning continues with BLE only |
| iOS, embedded map view | `NSMicrophoneUsageDescription` | Voice input in the web map |
| iOS, embedded map view | `NSSpeechRecognitionUsageDescription` | Converting voice input to text with the Speech framework |
| Android | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | BLE scanning and precise location |
| Android | `ACTIVITY_RECOGNITION` | Step counting; without it, positioning continues with BLE only |

The Android libraries merge these permissions into your manifest. Headless positioning has no voice input, so it
does not need the microphone and speech recognition keys. The SDK does not use Core Bluetooth directly, so iOS
needs neither `NSBluetoothAlwaysUsageDescription` nor the background location mode.

The SDK never shows a permission dialog from `load` or `configure`. Request permissions only from an explicit user
action or an explicit call from your app. On iOS, the embedded map view grants microphone requests only to the
HTTPS main frame of the configured `baseURL`, so the user does not see a per-site microphone prompt. The
app-level microphone and speech recognition prompts still appear.

## Scope

This version supports **foreground positioning** only.

- When the app moves to the background, the screen locks, or the user switches apps, positioning stops and the SDK
  reports `paused_background` instead of continuing to report a stale location.
- When the app returns to the foreground, the SDK revalidates the manifest, resets the estimator, and resumes from
  `recovering`.
- The SDK does not request Always location permission, the background location mode, or a foreground service.

The following are out of scope for this version:

- Navigation that continues while the screen is off (active background navigation)
- Entry and exit detection (presence monitoring) and always-on tracking
- Fully offline map packages; the map needs a network connection the first time it is shown
- Stationary detection from the Android activity recognition API; on Android, the estimator does not receive
  activity events, so stationary damping that depends on them does not apply

## Privacy and security

- Positions are computed on the device. The SDK does not send coordinates or RSSI values to any server.
- The SDK contains no client secret. Published maps load by slug. To show an unpublished map, pass a short-lived
  preview token issued by Metamaps as `previewToken`.
- Raw BLE and motion samples, long-lived tokens, and secrets are never passed to the web map's JavaScript.
- The map view only navigates within the configured origin and validates every bridge message (bridge version,
  map and group, and a monotonically increasing sequence number).
- Beacon diagnostics are off by default. When enabled, the snapshot stays on the device and is not sent to any
  server or written to persistent storage.

## Versioning

- Versions follow semantic versioning, and the three platforms release the same version number at the same time.
- Before 1.0, a minor version can contain breaking changes. `CHANGELOG.md` lists them under **Breaking changes**.
- The SDK talks to the hosted web map through a versioned bridge. While the bridge major version matches, web map
  updates reach your app without an SDK update. If it does not match, the map stays visible and positioning
  integration is turned off.

## License

- The wrapper source code in this repository is licensed under the [Apache License 2.0](LICENSE).
- The positioning engine binaries (`MetamapPositioningCore.xcframework` and the
  `jp.metamaps.positioning:metamap-positioning-core` AAR) are licensed under [separate terms](LICENSE-ENGINE.md).
- Third-party software is listed in [`NOTICE`](NOTICE) and
  [`android/THIRD_PARTY_NOTICES.md`](android/THIRD_PARTY_NOTICES.md).

## Reporting issues

Include the SDK version, the OS version, the device model, the `mapSlug`, steps to reproduce, and the `code` and
`debugDetail` of the `MetamapPositioningError` you received. Do not attach raw coordinates or RSSI logs.
