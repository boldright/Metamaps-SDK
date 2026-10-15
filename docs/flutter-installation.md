# Metamaps Flutter SDK Developer Guide

This guide adds the Metamaps map view widget to a Flutter app from the `metamaps_flutter` plugin, configures it,
and shows the basic calls an app makes. Indoor positioning is optional; step 9 covers it. The plugin requires
Flutter 3.44.9 or later, Dart 3.12.2 or later, iOS 16 or later, and Android API 24 or later. The SDK is tested with
Flutter 3.44.9 and Dart 3.12.2.

## 1. Get the plugin

Place the plugin in a fixed directory inside your app repository, for example `vendor/metamaps-flutter`.

Clone the [public repository](https://github.com/boldright/Metamaps-SDK) and copy its `flutter/metamaps_flutter`
directory (run from your app repository). The directory bundles the iOS and Android libraries:

```bash
git clone https://github.com/boldright/Metamaps-SDK.git ../Metamaps-SDK
mkdir -p vendor
cp -R ../Metamaps-SDK/flutter/metamaps_flutter vendor/metamaps-flutter
```

To get a specific version, check out its tag first, for example `git -C ../Metamaps-SDK checkout v0.7.0`.

Because the directory name is fixed, an SDK update only replaces this directory; `pubspec.yaml` and
`build.gradle.kts` stay unchanged. Step 11 describes the update.

Add a path dependency to your app's `pubspec.yaml`:

```yaml
dependencies:
  flutter:
    sdk: flutter
  metamaps_flutter:
    path: vendor/metamaps-flutter
```

## 2. Register the bundled Android repository

Android builds use Gradle 9.8.0, Android Gradle Plugin 9.4.1, and Kotlin 2.4.20. New Flutter 3.44.9 projects
generate older versions, so update these two lines in `android/settings.gradle.kts`:

```kotlin
plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
```

Update the distribution URL in `android/gradle/wrapper/gradle-wrapper.properties` as well:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.0-all.zip
```

In some setups, a repository added by the plugin arrives too late for the app's repository configuration. For new
and existing apps alike, declare the plugin's bundled repository in `allprojects.repositories` of
`android/build.gradle.kts`:

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

You do not add the plugin's own Maven coordinates to the app; this repository only resolves the Android libraries
the plugin uses. `flatDir` or direct AAR references do not resolve transitive dependencies.

## 3. Configure the iOS project

In Xcode, set **Minimum Deployments** of the Runner target to iOS 16.0 or later, then regenerate the configuration:

```bash
flutter build ios --config-only
```

Flutter 3.44.9 enables Swift Package Manager integration by default. If an existing app has it disabled, follow
Flutter's [migration guide](https://docs.flutter.dev/packages-and-plugins/swift-package-manager/for-app-developers)
to enable it. Existing apps that stay on CocoaPods can use the bundled podspec; CocoaPods must be installed.

The plugin's Swift package and podspec reference the iOS libraries automatically, so you do not add XCFrameworks
yourself.

## 4. Add the widget

`MetamapsMapView` is the widget you place in the widget tree. `MetamapsMapViewController` is a separate object that
controls the displayed map; you receive it in `onViewCreated`. Map readiness and errors arrive in `onEvent`.

```dart
import 'package:flutter/material.dart';
import 'package:metamaps_flutter/metamaps_flutter.dart';

void main() => runApp(const MaterialApp(home: MapScreen()));

class MapScreen extends StatefulWidget {
  const MapScreen({super.key});

  @override
  State<MapScreen> createState() => _MapScreenState();
}

class _MapScreenState extends State<MapScreen> {
  MetamapsMapViewController? _mapController;
  bool _mapReady = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: MetamapsMapView(
          configuration: MetamapsMapViewConfiguration(
            mapSlug: 'example',
            language: 'en',
          ),
          onViewCreated: (controller) => _mapController = controller,
          onEvent: (event) {
            if (event is MapReady) {
              _updateMapReady(true);
            } else if (event is MapLoadStateChanged &&
                event.state != MapViewLoadState.loaded) {
              // Commands fail again from a new page load, a failure, or disposal until the next MapReady.
              _updateMapReady(false);
            } else if (event case MapViewErrorEvent()) {
              // Show your recovery UI. After MapLoadStateChanged(failed), call reload() to recover.
            } else {
              // Keep this branch: SDK updates may add events.
            }
          },
        ),
      ),
    );
  }

  void _updateMapReady(bool value) {
    if (!mounted || _mapReady == value) return;
    setState(() => _mapReady = value);
  }

  @override
  void dispose() {
    _mapController = null;
    super.dispose();
  }
}
```

`_mapReady` tracks whether the map accepts commands; step 7 uses it.

Set `mapSlug` to the URL identifier of the public map configured in the Metamaps console. `'example'` is a
placeholder that only lets the app build; replace it with the slug you were given before running the app.

When the widget is disposed, the plugin disposes the native map view and the WebView, and stops positioning if it was running.

## 5. Permissions

On Android, a map-only app needs no permission entries. The plugin adds only `INTERNET` to your merged manifest;
it does not add location, Bluetooth, or activity recognition permissions. The SDK also removes
`ACCESS_COARSE_LOCATION`, `BLUETOOTH`, and `BLUETOOTH_ADMIN`, which the Android Beacon Library it uses declares.
The removal can also apply to the same permissions declared by other libraries in your app, so if another library
needs any of them, declare it in your app's manifest; your app's own declarations always take priority.

On Android, maps that show the user's location with GPS need precise location permission. The map view lets the
map read the device location only when your app has already been granted `ACCESS_FINE_LOCATION`, and it never shows
a permission dialog for this. To use GPS location in the map, declare `ACCESS_FINE_LOCATION` and
`ACCESS_COARSE_LOCATION` in your manifest and request them from your app. Without the permission, the map tells the
user that location is not available.

The plugin has no API for these Android permissions. Request them with your app's own permission flow, for example
the `permission_handler` package.

On Android, voice input in the map's search field and AI chat needs microphone permission. The map view lets the
map use the microphone only when your app has already been granted `RECORD_AUDIO`, and it never shows a permission
dialog for this. To offer voice input, declare `RECORD_AUDIO` in `android/app/src/main/AndroidManifest.xml` and
request it from your app. Without the permission, or on a device without a speech recognition service, the map
hides its microphone button; after the user grants the permission, the button appears without reloading the map.
Voice input uses the device's speech recognition service, which can send the audio to a server, so cover the
microphone in your privacy policy. The SDK adds a `<queries>` entry for `android.speech.RecognitionService` to your
merged manifest so that the map can reach that service on Android 11 and later. The map's text does not follow the
system font size on either platform.

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

On iOS, add these four keys to `ios/Runner/Info.plist`, even if you only show maps:

```xml
<key>NSMicrophoneUsageDescription</key>
<string>The microphone lets you search the map and ask questions by voice.</string>
<key>NSSpeechRecognitionUsageDescription</key>
<string>Speech recognition turns your voice into map searches and questions.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Your location is used to show where you are on the map and to find routes.</string>
<key>NSMotionUsageDescription</key>
<string>Motion data keeps your location up to date while you walk.</string>
```

The microphone and speech recognition keys are used by the map's voice input. The location key is used when the
map shows the user's location: by GPS location on maps that enable it, and by indoor positioning (step 9). On a map
with GPS location enabled, the system asks for location permission the first time the user taps the location button
in the map, even in an app that only shows maps. The motion key is used only by indoor positioning, and the system
asks for it only when positioning starts. App Store Connect checks for the location and motion keys because the SDK
links Core Location and Core Motion.

Your IDE's hover documentation, or `dart doc` run in the plugin directory, shows the public API documentation.

## 6. Receive events

`MetamapsMapView` sends map readiness and errors to `onEvent` as `MetamapsMapViewEvent`. The main events are:

- `MapReady`: the web map accepts `setLanguage()`, `selectFloor()`, `showSpot()`, and `setDestination()` on
  `MetamapsMapViewController`.
- `MapViewErrorEvent`: loading the map or a configuration value failed, or the web map could not complete an
  operation. For the last case the code is `mapOperationFailed`; the map stays usable, so do not reload it.

`onViewCreated` reports that the controller exists, but the web map may not be ready yet. Wait for `MapReady`
before calling controller methods that control the displayed map.

### Back button

When the user opens a spot, the map adds a step to its history, as the web map does in a browser. Wrap the map's
screen in `PopScope` so that back closes that step first and leaves the screen only when the map has nothing to
close. `MetamapsMapViewController.canGoBack` is `true` while the map has such a step, `MapCanGoBackChanged` reports
its changes, and `goBack()` closes the step:

```dart
import 'dart:async'; // For unawaited.

@override
Widget build(BuildContext context) {
  return PopScope(
    canPop: !(_mapController?.canGoBack ?? false),
    onPopInvokedWithResult: (didPop, _) {
      // goBack() fails only when the native view is already gone; there is nothing left to close then.
      if (!didPop) unawaited(_mapController?.goBack().catchError((Object _) => false));
    },
    child: MetamapsMapView(
      configuration: MetamapsMapViewConfiguration(mapSlug: 'example'),
      onViewCreated: (controller) => _mapController = controller,
      onEvent: (event) {
        // Rebuild so that PopScope reads the new canGoBack.
        if (event is MapCanGoBackChanged) setState(() {});
      },
    ),
  );
}
```

On iOS, Flutter also disables the back swipe while `canPop` is `false`; the swipe works again once the map has
nothing to close.

### Sharing

The map's share buttons open the system share sheet on both platforms. Your app needs no code for this.

## 7. Change the display language

Set the initial language with `MetamapsMapViewConfiguration.language`, using a BCP 47 language tag such as `ja`,
`en`, or `zh-Hans`. To change the language of a displayed map, call `MetamapsMapViewController.setLanguage()`. You
do not need to recreate the widget or the native map view; the web map reloads in the selected language.

Keep the controller from `onViewCreated` and call it after `onEvent` receives `MapReady`. Add this method to
`_MapScreenState`:

```dart
  Future<void> changeMapLanguage(String language) async {
    if (!_mapReady) return;
    await _mapController?.setLanguage(language);
  }
```

The available languages depend on the map's publishing settings. A language the map does not publish produces a
`MapViewErrorEvent` with `mapOperationFailed`. Pass a specific published language tag to `setLanguage()`. To return
to automatic selection by device language, recreate the widget with `language: null` or `language: 'auto'` and a new
`Key`.

## 8. Query parameters

[Appendix: map view query parameters](APPENDIX-QUERY-PARAMETERS.md) lists the public parameters for the initial
view, their value ranges, and how to change them after the map is displayed. For example, to open a specific spot,
pass it in `additionalQuery`:

```dart
final configuration = MetamapsMapViewConfiguration(
  mapSlug: 'example',
  language: 'en',
  additionalQuery: const <String, String>{
    'spot': '6Y6SSBY5',
  },
);
```

## 9. Indoor positioning (optional)

Indoor positioning shows the user's current location on maps whose facility has installed beacons and enabled
positioning in Metamaps. For other maps, the map view requests no permissions and downloads no positioning data,
so map-only apps can skip this step.

On Android, add this block to `android/app/src/main/AndroidManifest.xml`. When a required permission is missing,
the permission request fails with a `configurationInvalid` error that lists it.

```xml
<uses-feature
    android:name="android.hardware.bluetooth_le"
    android:required="false" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission
    android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission
    android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />
```

Location (and, on Android 12 and later, Bluetooth scanning) is required for positioning. Step counting uses
`ACTIVITY_RECOGNITION` on Android and Motion & Fitness on iOS; if the user denies it, positioning continues without
steps. On iOS, the keys from step 5 are all that positioning needs.

### When positioning starts

`positioningStartTrigger` decides when positioning starts. If a required permission has not been granted when
positioning starts, the OS shows its permission dialog.

| Value | Behavior |
|---|---|
| `userAction` | Starts when the user taps the location button in the map |
| `automatic` | Starts automatically once the map is ready |
| `automaticWhenAuthorized` (default) | Starts automatically once the map is ready, but only if every required permission is already granted |

```dart
MetamapsMapViewConfiguration(
  mapSlug: 'example',
  positioningStartTrigger: MapViewPositioningStartTrigger.userAction,
)
```

To use your own location button, call `_mapController?.startPositioning()` after `MapReady`, from an explicit user action.

`positioningStartTrigger` only decides **when** a start request happens. `positioningPolicy` decides whether the
SDK handles the request or passes it to your app. The default `userInitiated` lets the SDK request permissions and
start positioning, `hostControlled` only sends the `MapPositioningStartRequested` event to your app, and `disabled`
ignores start requests. With `positioningPolicy: MapViewPositioningPolicy.hostControlled`, your app keeps control
of when and how permissions are requested even when automatic start is enabled.

To show positioning with your own map instead of the map view, use `MetamapsPositioningClient`.

## 10. Build

Run the app from Android Studio or Visual Studio Code on the device of your choice. From a terminal, run:

```bash
flutter pub get
flutter analyze
flutter build apk --debug
flutter build ios --simulator --debug
```

## 11. Update the SDK

Check out the new version in your clone of the public repository and replace the whole `vendor/metamaps-flutter/`
directory. Replacing the directory needs no change to your app's `pubspec.yaml`, `android/build.gradle.kts`, or
`android/settings.gradle.kts`. Read the changelog for anything a new version asks of your app, such as new
permissions in `AndroidManifest.xml` or `Info.plist`, or the `PopScope` handling for back.

```bash
git -C ../Metamaps-SDK fetch --tags
git -C ../Metamaps-SDK checkout v<version>
rm -rf vendor/metamaps-flutter
cp -R ../Metamaps-SDK/flutter/metamaps_flutter vendor/metamaps-flutter
```

Replace `<version>` with the version to install, for example `0.7.0`.

**Do not skip `rm -rf`.** If `vendor/metamaps-flutter` still exists, `cp -R` copies the new directory **into** it.
Flutter then keeps reading the old `vendor/metamaps-flutter/pubspec.yaml`, `android/`, and `ios/`, and the build
succeeds with the old SDK without any error or warning.

After replacing the directory, rebuild the dependencies and the native configuration:

```bash
flutter clean
flutter pub get
flutter build ios --config-only
```

Apps that stay on CocoaPods must also run `cd ios && pod install`. The plugin's podspec carries a version, so
without this step the app keeps building with the old version.

To check the installed version, read `metamapsSdkVersion` at run time:

```dart
import 'package:metamaps_flutter/metamaps_flutter.dart';

debugPrint(metamapsSdkVersion);
```

Read `CHANGELOG.md` for the changes in each version. A version that changes a default value can change your app's
behavior even if you do not change your code. `CHANGELOG.md` also announces when the Gradle, Android Gradle Plugin,
Kotlin, Flutter, or Dart requirements in step 2 increase.
