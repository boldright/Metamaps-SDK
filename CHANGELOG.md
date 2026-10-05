# Changelog

## 0.5.0

### Breaking changes

- Removed `bootstrapTokenProvider` from iOS, Android, and Flutter. The Metamaps server never verified the
  token, so the option did not make private maps available. Private map support will return together with
  server-side verification.
- Removed APIs that only Metamaps tooling used: the BLE test display mode (`displayMode`,
  `MetamapMapViewDisplayMode`, `PositioningConfiguration.bleTest`), the measurement grid
  (`setMeasurementGrid`), the diagnostic overlay and marker (`setDiagnosticOverlay`, `setDiagnosticMarker`),
  `diagnosticEventHandler`, `PilotDiagnosticRecorder`, and `MetamapBeaconSurveyClient`.
- Removed the error codes `surveySessionExpired` and `surveyUploadFailed`, which only Metamaps tooling reported.
- Added the error code `mapOperationFailed` (recoverable, `userAction` `none`). A map view command the web map
  cannot complete, such as `showSpot` with an unknown spot, now reports it instead of `webContentLoadFailed`. The map
  stays usable, so do not reload it for this error. Update `switch` statements and `when` expressions that list
  every code.
- iOS: Xcode 26 or later is required (previously Xcode 16).
- Flutter: Flutter 3.44.9 or later is required (previously 3.44.8). Dart 3.12.2 or later is unchanged.

### Changes

- The positioning engine ships as a prebuilt binary: `MetamapPositioningCore.xcframework` on iOS and the
  `jp.metamaps.positioning:metamap-positioning-core` AAR on Android. The Android engine artifact is now an AAR
  instead of a JAR; its Maven coordinates are unchanged and it still resolves transitively.
- Android: built with Gradle 9.8.0, Android Gradle Plugin 9.4.1, Kotlin 2.4.20, and compile and target SDK 37.
  Host apps still need only compileSdk 36 or later and minSdk 24.
- iOS: The privacy manifest no longer declares collected data. The SDK sends no data it collects; it still declares
  the system boot time API, which measures timer and observation intervals.
- The README files, developer guides, and source documentation are now in English.
- Added `LICENSE` (Apache License 2.0) for the SDK source code and `LICENSE-ENGINE.md` for the positioning engine
  binaries. The engine may be used only while a Metamaps service agreement is in effect.

### Bug fixes

- iOS / Android: A map view command the web map cannot complete, such as `showSpot` with an unknown spot, no longer
  clears the current-location marker. The `mapOperationFailed` error event's `debugDetail` carries the web map's
  code, for example `map.error: spot_not_found`.
- iOS: `setLanguage(_:)` rejects a malformed language tag with `configurationInvalid`, as Android already did.
  Language tags, including `MetamapMapViewConfiguration.language`, accept only ASCII letters, digits, `-`, and `_`,
  the same as Android, Flutter, and the web map.
- Flutter (Android): Invalid arguments, such as a malformed spot key or language tag, report `configurationInvalid`
  instead of `internalInvariantViolation`, matching iOS.
- Android: A cached positioning manifest past its `expiresAt` is used offline, and after an HTTP 304, for up to
  `maxOfflineAgeMs` since it was last verified, as documented. Previously these starts failed with `manifestExpired`.
  An expired manifest received over the network is still rejected.

## 0.4.0

### Improvements

- Positions are corrected so they are less likely to drift into atriums, outside the floor, or into restricted
  areas. A position lost outside the walkable area recovers from BLE observations.
- Floors of 3D objects marked as walkable, and the running surfaces of stairs and escalators, are now part of the
  area where positions can be placed.
- On one-way escalators and moving walkways, positions advance along the direction of travel. Two-way equipment
  is treated as an ordinary walkable area.

### Bug fixes

- iOS: Fixed valid positioning settings from the server being rejected with a checksum error, which prevented
  positioning from starting.
- iOS / Android: Fixed seams between walkable areas being treated as walls, which stopped the position from
  moving.

## 0.3.0

### New features

- Added `positioningStartTrigger`, which decides when positioning starts.
  - `automatic`: starts when the map is shown, requesting any permission that has not been decided yet.
  - `automaticWhenAuthorized` (default): starts when the map is shown, but only on devices that already have every
    required permission.
  - `userAction`: starts only when the user taps the location button. This matches the behavior up to 0.2.0.

### Breaking changes

- Changed the Android and Flutter package names and the Maven group to `jp.metamaps`. Android apps need to update
  their dependency coordinates and imports. The iOS module names and the Flutter Dart package name are unchanged.
- Changed the default production base URL to `https://metamaps.jp`.

### Bug fixes

- iOS: Fixed the lost-position error being sent repeatedly while the device stays out of beacon range.
- iOS: Fixed the Motion & Fitness permission dialog appearing when a client that never started positioning was
  released.
- Android: Fixed permissions that were already granted being treated as denied, which prevented positioning from
  starting.
- iOS / Android / Flutter: Fixed the reported SDK version not matching the released version.

### Developer guides

- Added an "Update the SDK" section that names the directory to replace and shows how to check the version after
  the update.
- Reorganized the permission descriptions by required or optional and by when they are requested.

## 0.2.0

- No public API changes. App code written for 0.1.0 works unchanged.
- Removed the release ZIP version number from the installation steps. The extracted directory name is fixed, and
  dependency versions are read from the bundled `VERSION` file.
- Release ZIPs no longer include checksums, because Gradle does not read them from a filesystem repository.
- Release ZIPs now include this changelog, so the changes can be followed from the release alone.
- Corrected `floor` in the query parameter appendix from "floor UUID" to "floor label" to match the
  implementation.
- Noted in the query parameter appendix that the `map` and `gps` values of `from` and `to` are not values an app
  passes.

## 0.1.0

- Aligned the Flutter Android adapter with the native SDK on Gradle 9.6.1, Android Gradle Plugin 9.3.1, and
  Kotlin 2.4.10.
- Set the minimum and release verification versions to Flutter 3.44.8 and Dart 3.12.2.
- Exposed extra query parameters, the retry policy, the User-Agent appendix, and the WebView inspection setting
  of the slug map view.
- `load` / `reload` now complete when the native asynchronous load finishes, and a load replaced by another
  returns a typed error.
- Added asynchronous external link decisions in the host and a controller API that hands a link back to the
  default handling after confirmation.

## 0.1.0-alpha.2

- Synchronized the Dart API and both native bridges with the current iOS SDK contract.
- Added pedestrian-route reset for headless clients and embedded map controllers.
- Added typed motion-heading and opt-in registered-beacon relation events.
- Added BLE test and beacon-diagnostics configuration parity across iOS and Android.
- Preserved WGS84 elevation through Dart, Swift, and Kotlin mappings.

## 0.1.0-alpha.1

- Added a typed Flutter wrapper over the Metamaps iOS and Android positioning SDKs.
- Added `MetamapPositioningClient` with lifecycle, capability, position, and error streams.
- Added `MetamapMapView`, a Slug-based PlatformView with typed map events and controls.
- Added per-instance short-lived bootstrap token callbacks without embedding secrets.
- Added an iOS/Android example and parity tests for nullable numeric fields and unknown enums.
