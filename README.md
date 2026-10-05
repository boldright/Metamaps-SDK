# Metamaps SDK

The Metamaps SDK embeds maps built with [Metamaps](https://metamaps.jp) in your iOS, Android, or Flutter app.

Pass one `mapSlug`, and your app shows the full Metamaps map: search, spots, floor switching, and routes,
kept up to date with what you publish in the Metamaps console. You do not rebuild your app when the map
changes.

Facilities that have installed Bluetooth beacons can also turn on **indoor positioning**, which shows the user's
current location on the map. Indoor positioning is optional. For maps without it, the SDK requests no location
or Bluetooth permissions and downloads no positioning data.

## Repository layout

| Path | Contents |
|---|---|
| [`ios/`](ios/) | Swift package with the `Metamaps` product (map view) and the `MetamapsPositioning` product (positioning without a map view) |
| [`android/`](android/) | Gradle build for the `metamaps-mapview` and `metamaps-positioning` libraries, with samples |
| [`flutter/metamaps_flutter/`](flutter/metamaps_flutter/) | Flutter plugin `metamaps_flutter`, which wraps the iOS and Android SDKs |
| [`docs/`](docs/) | Developer guides for each platform and the map query parameter reference |
| [`CHANGELOG.md`](CHANGELOG.md) | Changes in each version |

Indoor positioning uses a prebuilt engine: `ios/Artifacts/MetamapsPositioningCore.xcframework` for iOS and
`android/maven-repository/` for Android. The Flutter plugin bundles the same binaries. The source in this
repository builds against them, so you do not need any other download.

## Requirements

| Platform | Minimum | Toolchain used for this release |
|---|---|---|
| iOS | iOS 16 | Xcode 26 (Swift 6.2) or later |
| Android | minSdk 24, compileSdk 36 or later, JDK 17 | Gradle 9.8.0, Android Gradle Plugin 9.4.1, Kotlin 2.4.20 |
| Flutter | Flutter 3.44.9, Dart 3.12.2, plus the iOS and Android minimums above | Flutter 3.44.9 |

All three platforms share one version number, and each version has a tag in this repository, such as `v0.6.0`.

## Quick start

Get the SDK from this repository:

```bash
git clone https://github.com/boldright/Metamaps-SDK.git
```

Each developer guide covers installation, events, language changes, indoor positioning, and SDK updates step by
step:

- [iOS developer guide](docs/ios-installation.md)
- [Android developer guide](docs/android-installation.md)
- [Flutter developer guide](docs/flutter-installation.md)
- [Appendix: map view query parameters](docs/APPENDIX-QUERY-PARAMETERS.md)

### iOS

```swift
import Metamaps

let mapView = MetamapsMapView(configuration: .init(mapSlug: "example", language: "en"))
mapView.delegate = self
view.addSubview(mapView)
try await mapView.load()
```

### Android

```kotlin
val mapView = MetamapsMapView(context)
mapView.configure(MetamapsMapViewConfiguration(mapSlug = "example", language = "en"))
mapView.load()
```

### Flutter

```dart
MetamapsMapView(
  configuration: MetamapsMapViewConfiguration(mapSlug: 'example', language: 'en'),
  onEvent: _handleEvent,
)
```

`example` is a placeholder. Use the slug of the published map configured in the Metamaps console.

## What a map-only app needs

- **Android:** nothing beyond the dependency. The libraries add only the `INTERNET` permission to your app.
- **iOS:** four `Info.plist` purpose strings. The microphone and speech recognition keys are used by the map's
  voice input. The location and motion keys are checked by App Store Connect because the SDK links Core Location
  and Core Motion; the system never shows those prompts unless indoor positioning starts. The developer guide has
  the exact keys.

## Indoor positioning (optional)

Indoor positioning works only for maps whose facility has installed beacons and enabled positioning in
Metamaps. For those maps, the map view shows the current-location marker with its 95% accuracy circle and can
start routes from the current location. Beacon placements and calibration values come from Metamaps, so your app
stores no installation data.

To use it:

- **Android:** declare the positioning permissions in your app's manifest (location, Bluetooth, and activity
  recognition). The developer guide has the exact block. Without them, the permission request fails with a
  `configurationInvalid` error that lists the missing permissions.
- **iOS:** nothing beyond the purpose strings above.
- **Both:** `positioningStartTrigger` decides when positioning starts, and `positioningPolicy` decides whether the
  SDK or your app handles the start request.

If you draw your own map, the `MetamapsPositioning` library provides positioning without the map view
(`MetamapsPositioningClient`). Your app then draws the current-location marker, the accuracy circle, floor
following, and the route origin itself.

This version supports **foreground positioning** only:

- When the app moves to the background, the screen locks, or the user switches apps, positioning stops and the SDK
  reports `paused_background` instead of continuing to report a stale location.
- When the app returns to the foreground, the SDK revalidates the positioning data, resets the estimator, and
  resumes from `recovering`.
- The SDK does not request Always location permission, the background location mode, or a foreground service.
- Not supported: navigation while the screen is off, entry and exit detection, always-on tracking, and, on Android,
  stationary detection from the activity recognition API.

## API documentation

Every public type, method, and property has a documentation comment. After adding the SDK, read them in Xcode
Quick Help, Android Studio Quick Documentation, or your Dart IDE's hover (or run `dart doc` in the plugin
directory). In the source, start from:

- iOS: [`MetamapsMapView.swift`](ios/Sources/MetamapsMapView/MetamapsMapView.swift) and
  [`MapViewContracts.swift`](ios/Sources/MetamapsMapView/MapViewContracts.swift)
- Android: [`MetamapsMapView.kt`](android/metamaps-mapview/src/main/kotlin/jp/metamaps/mapview/MetamapsMapView.kt) and
  [`MapViewContracts.kt`](android/metamaps-mapview/src/main/kotlin/jp/metamaps/mapview/MapViewContracts.kt)
- Flutter: [`map_view.dart`](flutter/metamaps_flutter/lib/src/map_view.dart) and
  [`models.dart`](flutter/metamaps_flutter/lib/src/models.dart)

## Features shared by all platforms

iOS, Android, and Flutter expose the same public contract. The Flutter plugin is a thin layer over the Swift and
Kotlin SDKs.

| Feature | iOS | Android | Flutter |
|---|---|---|---|
| Show a spot, choose a floor, set a destination, change the language | `showSpot` / `selectFloor` / `setDestination` / `setLanguage` | Same names | Same names |
| Map view web settings | `additionalQuery` / `retryPolicy` / `userAgentAppendix` / `isWebViewInspectable` | Same names | Same names |
| External link handling | Delegate and `openExternalLink(_:)` | Handler and `openExternalLink()` | Callback and `openExternalLink()` |
| Errors | `MetamapsError` | `MetamapsError` | `MetamapsError` |
| Indoor positioning: reset the walking baseline | `resetPedestrianRoute()` | `resetPedestrianRoute()` | `resetPedestrianRoute()` |
| Indoor positioning: device heading | `motionHeading` | `MotionHeading` | `MotionHeadingEvent` / `MapMotionHeadingChanged` |
| Indoor positioning: beacon diagnostics opt-in | `beaconDiagnosticsEnabled` / `showsBeaconDiagnostics` | Same names | Same names |
| WGS 84 altitude | `Wgs84Position.elevationM` | Same name | Same name |

`resetPedestrianRoute()` discards the step count, step interval, speed, and estimator state that built up while
the user was, for example, reading a description or walking to the start point. It keeps the latest sensor
heading and starts the next walking segment.

The beacon diagnostics snapshot is meant for on-device checks and is available only when you opt in. It never
contains raw Bluetooth or motion samples. Web content that needs the device heading can listen for the
`metamap:motion-heading` custom event.

## Privacy and security

- The SDK contains no client secret. Published maps load by slug. To show an unpublished map, pass a short-lived
  preview token issued by Metamaps as `previewToken`.
- The map view only navigates within the configured origin and validates every bridge message (bridge version,
  map and group, and a monotonically increasing sequence number).
- Indoor positions are computed on the device. The SDK does not send coordinates or signal strengths to any
  server, and never passes raw Bluetooth or motion samples to the map's JavaScript.
- Beacon diagnostics are off by default. When enabled, the snapshot stays on the device.

## Versioning

- Versions follow semantic versioning, and the three platforms release the same version number at the same time.
- Before 1.0, a minor version can contain breaking changes. `CHANGELOG.md` lists them under **Breaking changes**.
- The SDK talks to the hosted map through a versioned bridge. While the bridge major version matches, map updates
  reach your app without an SDK update. If it does not match, the map stays visible and indoor positioning is
  turned off.

## License

- The source code in this repository is licensed under the [Apache License 2.0](LICENSE).
- The positioning engine binaries (`MetamapsPositioningCore.xcframework` and the
  `jp.metamaps:metamaps-positioning-core` AAR) are licensed under [separate terms](LICENSE-ENGINE.md).
- Third-party software is listed in [`NOTICE`](NOTICE) and
  [`android/THIRD_PARTY_NOTICES.md`](android/THIRD_PARTY_NOTICES.md).

## Reporting issues

Open an issue at <https://github.com/boldright/Metamaps-SDK/issues>. Include the SDK version, the OS version, the
device model, the `mapSlug`, steps to reproduce, and the `code` and `debugDetail` of the `MetamapsError` you
received. Do not attach raw coordinates or signal strength logs.
