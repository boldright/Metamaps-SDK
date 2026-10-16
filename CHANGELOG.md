# Changelog

## 0.8.0

GPS location and voice input in the map now ask your app for permission when the user taps them, instead of failing
silently. On Android, the map no longer adds the system bars to its safe area twice.

### Breaking changes

- Android / Flutter: `MetamapsError.Code` (Android) and `MetamapsErrorCode` (Flutter) have a new case,
  `MICROPHONE_PERMISSION_DENIED` / `microphonePermissionDenied`. A `when` or `switch` without a default branch no
  longer compiles. Add a default branch.
- Android / Flutter (Android): The map view now emits `MetamapsMapViewEvent.Error` (Flutter: `MapViewErrorEvent`)
  when the map's request for location or the microphone is denied, also when your app sets no handler. The map stays
  usable. If your app shows a recovery UI or calls `reload()` for every error event, handle these codes separately.
- Android: The map shows its microphone button when your app declares `RECORD_AUDIO`, even before the permission is
  granted. A denied permission no longer hides it. Tapping it asks your app through `permissionRequestHandler`
  (Flutter: `onPermissionRequest`); without a handler, the map view denies the request and emits
  `MICROPHONE_PERMISSION_DENIED`. Set a handler, or remove the `RECORD_AUDIO` declaration to hide the button.
- iOS: The map hides its microphone button when your app's `Info.plist` lacks `NSMicrophoneUsageDescription` or
  `NSSpeechRecognitionUsageDescription`. WebKit refused to start voice input without them.

### Changes

- Android: `MetamapsMapView.permissionRequestHandler` receives a `MetamapsMapPermissionRequest` when GPS location or
  voice input in the map needs a permission your app declares but has not been granted. Call `request(activity)`
  to show the OS dialog, `finish()` after requesting `androidPermissions` yourself, or `decline()` to leave the
  permission as it is. The map waits for the answer and then reads the result from the OS. The view passes you one
  request at a time, and the SDK still never shows these dialogs on its own.
- Android: GPS location in the map needs `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` in your app's manifest,
  and voice input needs `RECORD_AUDIO`. If your app does not declare them, the map view denies the request without
  calling your handler, and the error's `userAction` is `NONE`.
- Android: When such a permission stays denied, the map view emits `MetamapsMapViewEvent.Error` with
  `LOCATION_PERMISSION_DENIED`, `PRECISE_LOCATION_REQUIRED` (only approximate location is granted), or
  `MICROPHONE_PERMISSION_DENIED`, and a `userAction` of `REQUEST_PERMISSION`, `ENABLE_PRECISE_LOCATION`, or
  `OPEN_APP_SETTINGS`. Show your guidance from it; the map itself no longer points the user to the browser settings.
- iOS: After the user denies location or microphone permission, the map asks the user to allow it in the device
  settings instead of the browser settings. WebKit does not report the denial, so the map view cannot pass it to your
  app.
- Flutter: `MetamapsMapView.onPermissionRequest` answers the same requests on Android with a `MapPermissionAnswer`:
  `showDialog` lets the plugin show the OS dialog, `requestedByApp` follows your own request, and `decline` leaves
  the permission as it is. The request tells which feature asks (`MapPermission.location` or
  `MapPermission.microphone`). Without the callback, the map's request is denied.
- Android 11 and later: The map's safe area (`env(safe-area-inset-*)`) gets only the system bars and display cutout
  that overlap the map view. In a host that pads the map's parent, such as a Flutter app with `SafeArea`, the search
  bar and the Metamaps logo no longer leave the system bar height as extra space. Hosts that draw the map edge to
  edge or consume the insets see no change. The map view updates the safe area when the map moves on the screen,
  for example when a header appears above it.
- The developer guides describe how to answer the map's permission requests on Android and Flutter, what each
  `userAction` means, and the `Info.plist` keys for voice input on iOS.

### Changes in the hosted map

These apply to every SDK version, including 0.7.0, because the map is served by Metamaps:

- After the user denies location or microphone permission, the map no longer points to the browser settings inside
  a map view. On Android, it says that the feature is not available and leaves the guidance to your app; SDK 0.8.0
  passes the denial to your app as an error event. On iOS, it asks the user to allow it in the device settings.
- In a map view, tapping the location button again after a denial asks for the permission again instead of only
  showing a message.
- The map no longer hides its microphone button after voice input is denied. It hides it only when the map view
  reports that voice input is unavailable.
- When voice input fails outside the AI chat, the whole message now appears as a notice. It was cut off in the
  search field.
- GPS location no longer fails while the user is still answering the location permission prompt. The 8-second limit
  for the first location starts once the permission is granted.
- On Android, the system back gesture no longer places a share pin at the edge of the map where the gesture started.
- In the voice guide, dragging or tapping the seek bar during playback no longer shows the loading icon on top of
  the pause icon.
- In the expanded AI chat, buttons at the bottom of the latest answer, such as Route guidance, can be tapped again.
  The drag area at the bottom of the chat covered them.
- On phones, the location button stays clear of the home indicator and the navigation bar when no sheet is open, as
  the Metamaps logo does.

## 0.7.0

The embedded map now behaves like the web map in a browser: back navigation, the share sheet, voice input, GPS
location, and embedded videos. This version also fixes several WebView lifecycle issues. Playing the voice guide in
the background is not part of this version.

### Breaking changes

- All platforms: Map view events have a new case for back navigation: Android
  `MetamapsMapViewEvent.CanGoBackChanged`, iOS `.canGoBackChanged`, and Flutter `MapCanGoBackChanged`. A `when` or
  `switch` without a default branch no longer compiles. Add a default branch, as the guides show.
- Android: The map's text no longer follows the system font size. The map view fixes the WebView text zoom at 100%,
  as iOS already did.
- Android: `MetamapsMapView.webView` is replaced with a new instance after the WebView renderer process ends. Read the
  property when you need it instead of keeping a reference. The property stays source- and binary-compatible.
- Android: `requestCenterReticleCandidate()` now fails with `MetamapsError` (`BRIDGE_HANDSHAKE_FAILED`) instead of
  `CancellationException` when the page changes or the view is disposed, as on iOS.
- Android: Passing `null` as the Activity to `requestPositioningAuthorization()` or `startPositioning()` now falls back
  to the view's own Activity instead of failing with `LOCATION_PERMISSION_DENIED`.
- iOS: `dispose()` during a load now ends `load()` with `CancellationError` instead of `webContentLoadFailed`.

### Changes

- All platforms: Back navigation follows the web map. `canGoBack` (Android `StateFlow<Boolean>`, an iOS property, and
  `MetamapsMapViewController.canGoBack` in Flutter) tells whether back should close a step of the map, such as an
  open spot, and `goBack()` closes it. Connect them to an `OnBackPressedCallback` on Android, `PopScope` in Flutter,
  or your own back control on iOS. Without this, Android's back button closes your screen even while a spot is open.
- Android: The map's share buttons open the Android share sheet. Your app needs no code for this.
- Android: GPS location in the map works when your app holds `ACCESS_FINE_LOCATION`. The map view grants the map's
  location request only for the configured HTTPS origin and never shows a permission dialog itself.
- Android: Voice input in the map's search field and AI chat works when your app holds `RECORD_AUDIO`. The map hides
  its microphone button while voice input is unavailable. The library's manifest adds a `<queries>` entry for
  `android.speech.RecognitionService`; it adds no permissions.
- iOS 27 and later: The map view grants the map's location request for the configured HTTPS origin, so WebKit no
  longer shows its own per-site prompt. iOS still asks for location permission the first time.
- iOS: Embedded HTTPS content in spot descriptions, such as YouTube videos, now loads instead of staying blank. The
  page itself stays on the configured origin, and only the map's page can use the bridge.
- iOS / Android: Links that open a new window on the map's own origin, such as the privacy policy link, now open
  outside the map view through `decideExternalLink` / `externalLinkHandler`. Previously nothing happened. On iOS, new
  windows to other origins that the map does not route itself also open outside.
- Flutter: `MetamapsMapViewController.canGoBack`, `goBack()`, and `MapCanGoBackChanged`. The example connects them to
  `PopScope`.
- Samples: The Android view sample uses `OnBackPressedCallback` from `androidx.activity`, and the Compose sample uses
  `BackHandler`.
- The developer guides cover the permissions each feature needs, back navigation, recovery with `reload()` after a
  failure, and the iOS navigation-bar back button.

### Bug fixes

- Android: A TLS error on a subresource, such as an image, no longer fails the whole map.
- Android: After the WebView renderer process ends, `reload()` recovers the map.
- Android: Fixed a leak, and a possible crash, of the temporary WebView used for links that open a new window.
- Android: Fixed a possible crash when the page URL contains characters that `java.net.URI` rejects.
- Android: Calling `configure()` again on a loaded view no longer leaves `canGoBack` set or requests waiting.
- Android: `dispose()` detaches the WebView before destroying it.
- iOS / Android: A positioning manifest from an older map revision no longer blocks the map. Host commands and back
  navigation work. Positioning stops until the map view prepares the manifest of the current revision, without an
  error for every position update.
- iOS: After `reload()`, a message from the previous page no longer completes the handshake of the new page.
- iOS: Fixed a possible crash on a bridge message that is not a JSON object.
- iOS: The map view now completes its handshake even when the map announces itself before the page finishes loading.
- iOS: After the web content process ends, host commands fail until `reload()` instead of being dropped.
- iOS: Messages from embedded frames no longer produce `.error` events.
- iOS: `dispose()` pauses the page's audio.
- Flutter (Android): `requestCenterReticleCandidate()` and the positioning calls always complete. Some could wait
  forever after the page changed.
- Flutter: `canGoBack` becomes `false` when the widget is disposed.

### Changes in the hosted map

These apply to every SDK version, including 0.6.0, because the map is served by Metamaps:

- The voice guide pauses when your app moves to the background or the screen locks, and does not resume on its own.
  Playing it in the background is planned for a later SDK version.
- On Android map views from SDK 0.6.0 and earlier, the map hides its microphone button, which could not work there.

## 0.6.0

The Metamaps SDK is a map SDK. Indoor positioning is an optional feature for facilities with installed beacons.
This version renames the public API to Metamaps and changes the defaults so that an app that only shows maps never
touches positioning. The bridge protocol and the hosted map are unchanged.

### Breaking changes

Public names now use **Metamaps**, and names that only made sense for positioning are gone from the map API.

| Platform | 0.5.0 | 0.6.0 |
|---|---|---|
| Flutter | package `metamap_positioning`, `import 'package:metamap_positioning/metamap_positioning.dart'` | package `metamaps_flutter`, `import 'package:metamaps_flutter/metamaps_flutter.dart'` |
| Android | Maven group `jp.metamaps.positioning` | Maven group `jp.metamaps` |
| Android | artifacts `metamap-mapview`, `metamap-positioning`, `metamap-positioning-core` | `metamaps-mapview`, `metamaps-positioning`, `metamaps-positioning-core` |
| iOS | Swift package `MetamapSDK`, products `Metamap` and `MetamapPositioning` | `MetamapsSDK`, products `Metamaps` and `MetamapsPositioning` |
| iOS | `MetamapPositioningCore.xcframework` | `MetamapsPositioningCore.xcframework` |
| All | `MetamapMapView`, `MetamapMapViewConfiguration`, `MetamapMapViewEvent`, `MetamapPositioningClient`, `MetamapPositioningEvent`, and every other `Metamap*` type | `MetamapsMapView`, `MetamapsMapViewConfiguration`, `MetamapsMapViewEvent`, `MetamapsPositioningClient`, `MetamapsPositioningEvent`, and so on |
| All | `MetamapPositioningError` (Flutter: `MetamapPositioningError`, `MetamapPositioningErrorCode`) | `MetamapsError` (Flutter: `MetamapsError`, `MetamapsErrorCode`) |
| All | `PositioningUserAction` | `MetamapsUserAction` |
| iOS / Android / Flutter | `MetamapPositioningSDK.version` / `MetamapPositioningSdk.VERSION` / `metamapPositioningSdkVersion` | `MetamapsSDK.version` / `MetamapsSdk.VERSION` / `metamapsSdkVersion` |
| Flutter | `metamapProductionBaseUrl` | `metamapsProductionBaseUrl` |
| All | release archives `MetamapSDK-{iOS,Android,Flutter}-<version>.zip` | `MetamapsSDK-{iOS,Android,Flutter}-<version>.zip` |
| All | user agent tokens `Metamap/<version>` and `MetamapPositioning/<version>` | `Metamaps/<version>` and `MetamapsPositioning/<version>` |

- Android: `MetamapsError`, `MetamapsUserAction`, and `MetamapsSdk` moved to the package `jp.metamaps`. The map view
  stays in `jp.metamaps.mapview`, and positioning in `jp.metamaps.positioning.android`.
- Android: The libraries no longer add location, Bluetooth, or activity recognition permissions to your app. They
  also remove `ACCESS_COARSE_LOCATION`, `BLUETOOTH`, and `BLUETOOTH_ADMIN`, which the Android Beacon Library
  declares. An app that only shows maps gets only `INTERNET`. **If your app uses indoor positioning, add the
  permission block from the [Android developer guide](docs/android-installation.md) to your app's manifest.**
  Without it, the permission request fails with `configurationInvalid` and lists the missing permissions.
  `BLUETOOTH` and `BLUETOOTH_ADMIN` are now checked as well on Android 11 and earlier. The removal can also apply
  to the same permissions declared by other libraries in your app; if another library needs any of them, declare
  it in your app's manifest.
- Flutter (Android): The Gradle property and the environment variable that point the plugin to another Maven
  repository are now `metamapsSdkRepository` and `METAMAPS_MAVEN_URL` (previously `metamapSdkRepository` and
  `METAMAP_MAVEN_URL`). The example's `--dart-define` names are now `METAMAPS_MAP_SLUG` and `METAMAPS_BASE_URL`.

To migrate, update the dependency (Flutter package, Maven coordinates, or Swift package product), then replace the
old names in your imports and code. The wire values, such as error codes and event names, are unchanged.

### Changes

- iOS / Android / Flutter: The map view downloads positioning data only after the map loads, and only for maps with
  indoor positioning enabled. For other maps it downloads nothing and no longer reports a `manifestUnavailable`
  error event on every load. Calls your app makes, such as `startPositioning()`, work as before.
- iOS: `import Metamaps` also gives you the shared types, such as `MetamapsError` and `MetamapsSDK`. A map-only app
  no longer imports `MetamapsPositioning`.
- iOS / Android: Added `MetamapsSDK.productionBaseURL` / `MetamapsSdk.PRODUCTION_BASE_URL`, the default origin of the
  map view. `PositioningConfiguration.productionBaseURL` / `PRODUCTION_BASE_URL` remains with the same value.
- Android: The Gradle properties for building against another engine repository are now
  `metamaps.engineRepository` and `metamaps.engine`.
- The README files and developer guides now start with embedding maps, and describe indoor positioning as an
  optional feature. The guides get the SDK from <https://github.com/boldright/Metamaps-SDK>, and issues go to its
  issue tracker.
- Samples: the Flutter example and the Android samples `samples/view` and `samples/compose` show maps only and
  declare no positioning permissions. The iOS samples no longer set `positioningPolicy`.
- Android: Removed `samples/mapviewsample`, a test app for Metamaps development that was not meant for SDK users.
- iOS: `Package.swift` lists the `Metamaps` product first.

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
