# Metamaps Flutter SDK

The `metamaps_flutter` plugin embeds maps built with Metamaps in Flutter apps. `MetamapsMapView` shows the
published Metamaps map for a `mapSlug`, with search, spots, floors, and routes. Facilities with installed beacons
can also show the user's indoor location on the same map; this is optional.

## Requirements

- Apps: Flutter 3.44.9 or later and Dart 3.12.2 or later
- SDK development and release verification: Flutter 3.44.9 and Dart 3.12.2
- iOS 16 or later (Swift Package Manager or CocoaPods)
- Android API 24 or later

The plugin is a thin layer over the iOS and Android SDKs, which implement the map view, the web bridge, and indoor
positioning. The Dart layer does not reimplement the WebView or positioning.

## Installation

The [Flutter developer guide](../../docs/flutter-installation.md) covers every step in detail.

A Flutter plugin's Dart code is compiled together with your app, so the plugin ships as source: the Dart API, the
native bridge, the iOS XCFrameworks, and a local Android Maven repository, all in one directory.

1. Copy this directory (or unzip `MetamapsSDK-Flutter-<version>.zip`) into your app repository, for example as
   `vendor/metamaps-flutter`.
2. Reference it from your app's `pubspec.yaml`:

```yaml
dependencies:
  metamaps_flutter:
    path: vendor/metamaps-flutter
```

3. Update `android/settings.gradle.kts` of the host app to Android Gradle Plugin 9.4.1 and Kotlin 2.4.20, and
   `android/gradle/wrapper/gradle-wrapper.properties` to Gradle 9.8.0. New Flutter 3.44.9 projects generate older
   versions, so do not skip this step.
4. Add a filesystem Maven repository that points to the plugin's `android/maven-repository` in the host's
   `android/build.gradle.kts`. Flutter fixes the host's repositories first, so the plugin cannot add this
   repository on its own.

```kotlin
allprojects {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri(rootProject.file(
                "../vendor/metamaps-flutter/android/maven-repository",
            ))
        }
    }
}
```

5. Run `flutter pub get`. On iOS, the plugin references `ios/metamaps_flutter/Artifacts/MetamapsSDK`
   automatically; you do not build the native SDKs separately.

On iOS, the plugin uses the Swift Package Manager integration that Flutter 3.44.9 enables by default. If an
existing app has it disabled, enable it with Flutter's migration guide. Existing apps that use CocoaPods load the
bundled XCFrameworks through the podspec's `vendored_frameworks`. Set the Runner target's Minimum Deployments to
iOS 16.0 or later.

Add these purpose strings to the host app's `Info.plist`, even if you only show maps:

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
Motion.

On Android, a map-only app needs no permission entries: the native libraries add only `INTERNET`. Apps that use
indoor positioning declare the location, Bluetooth, and activity recognition permissions in their own manifest; the
developer guide has the exact block. The SDK never shows a permission dialog from `load` or `configure`.

## Map view

```dart
MetamapsMapView(
  configuration: MetamapsMapViewConfiguration(
    mapSlug: 'example',
    language: 'en',
    positioningPolicy: MapViewPositioningPolicy.userInitiated,
    additionalQuery: const {'spot': '6Y6SSBY5'},
    retryPolicy: MetamapsMapViewRetryPolicy.automatic,
    userAgentAppendix: 'ExampleApp/3.2',
    isWebViewInspectable: false,
  ),
  onViewCreated: (controller) {
    mapController = controller;
  },
  onEvent: (event) {
    switch (event) {
      case MapReady():
        debugPrint('map=${event.event.mapId}');
      case MapViewErrorEvent():
        debugPrint(event.error.toString());
      default:
        break;
    }
  },
  onExternalLinkDecision: (url) {
    return MetamapsExternalLinkDecision.openDefault;
  },
)
```

`'example'` is a placeholder that only lets the app compile. Replace it with the slug of your map before running
the app.

Only `mapSlug` is required; the production base URL is `https://metamaps.jp`. Optional settings are `groupId`,
`language`, `initialFloorId`, `previewToken` (a short-lived token that shows an unpublished map), `additionalQuery`
for the query parameters the public map supports, `retryPolicy` for load retries, `userAgentAppendix`, and
`isWebViewInspectable` for development. When a fixed query parameter and `additionalQuery` collide,
`additionalQuery` wins. The supported parameters are listed in the
[query parameter appendix](../../docs/APPENDIX-QUERY-PARAMETERS.md).

The controller provides `load` / `reload`, floor selection, showing a spot, setting the destination, changing the
language, the center reticle and its candidate coordinate, and `openExternalLink`. For indoor positioning it also
provides permission requests, starting and stopping positioning, and resetting the walking baseline. The `load` / `reload` futures complete when the
native WebView finishes loading the main frame or fails with a typed error. A call replaced by a later call
completes with `webContentLoadFailed` (`debugDetail: superseded`).

For maps with indoor positioning, `positioningPolicy` takes one of these values:

- `userInitiated`: the location control in the web map starts native permission requests and positioning.
- `hostControlled`: requests from the web map arrive at your app as typed events.
- `disabled`: the map is shown without positioning.

Events are kept separate for each platform view ID. When the widget is disposed, the native map view and the
WebView are disposed too, and positioning stops if it was running.

Without `onExternalLinkDecision`, external links open with the native SDK's default handling. If the callback
returns `handled`, the link does not open, so you can show your own confirmation and then call
`controller.openExternalLink(url)`. If the Dart view registration is missing, the callback throws, or the reply
channel fails, the link does not open.

The IDs passed to `showSpot` and `setDestination` are the stable keys of public spots, such as `6Y6SSBY5` (keys in
the older `spot_` format are also accepted).

## Example app

`example/` is a map-only app: the map view fills the screen, and map errors appear in a snack bar with a reload
action when the SDK suggests a retry. It declares no positioning permissions on Android.

```bash
cd example
flutter run \
  --dart-define=METAMAPS_MAP_SLUG=replace-with-your-map-slug \
  --dart-define=METAMAPS_BASE_URL=https://metamaps.jp
```

## Development

`tool/flutter-sdk.version` is the single source of truth for the Flutter SDK used for development and release
verification. It is kept separate from `environment` in `pubspec.yaml`, which states the minimum version for apps.
Verify the toolchain first:

```bash
tool/verify_flutter_sdk.sh
```

The Pigeon schema in `pigeons/` defines the Dart, Swift, and Kotlin contract:

```bash
dart run tool/generate_messages.dart
dart format lib test example/lib pigeons
flutter analyze
flutter test
```

Commit the generated `messages.g.dart`, `Messages.g.kt`, and `Messages.g.swift`. The native iOS and Android SDKs
and the Flutter API share one version. The Pigeon contract and the native mapping tests check that both platforms
agree on the runtime bridge, the walking baseline reset, WGS 84 altitude, and the capability, motion heading, and
beacon events.
