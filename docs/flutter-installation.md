# Metamaps Flutter SDK Developer Guide

This guide adds the Metamaps map view widget to a Flutter app from the SDK plugin, configures it, and shows the
basic calls an app makes. It requires Flutter 3.44.9 or later, Dart 3.12.2 or later, iOS 16 or later, and Android
API 24 or later. The SDK is tested with Flutter 3.44.9 and Dart 3.12.2.

## 1. Get the plugin

Place the plugin in a fixed directory inside your app repository, for example `vendor/metamap-flutter`.

From a release ZIP (`MetamapSDK-Flutter-<version>.zip`):

```bash
mkdir -p vendor
unzip MetamapSDK-Flutter-*.zip -d vendor
mv vendor/MetamapSDK-Flutter-* vendor/metamap-flutter
```

From the public repository, copy its `flutter/metamap_positioning` directory. It bundles the iOS and Android
libraries, just like the ZIP:

```bash
mkdir -p vendor
cp -R path/to/metamaps-sdk/flutter/metamap_positioning vendor/metamap-flutter
```

Because the directory name is fixed, an SDK update only replaces this directory; `pubspec.yaml` and
`build.gradle.kts` stay unchanged. Step 10 describes the update.

Add a path dependency to your app's `pubspec.yaml`:

```yaml
dependencies:
  flutter:
    sdk: flutter
  metamap_positioning:
    path: vendor/metamap-flutter
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
                "../vendor/metamap-flutter/android/maven-repository",
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

`MetamapMapView` is the widget you place in the widget tree. `MetamapMapViewController` is a separate object that
controls the displayed map; you receive it in `onViewCreated`. Map readiness and errors arrive in `onEvent`.

```dart
import 'package:flutter/material.dart';
import 'package:metamap_positioning/metamap_positioning.dart';

void main() => runApp(const MaterialApp(home: MapScreen()));

class MapScreen extends StatefulWidget {
  const MapScreen({super.key});

  @override
  State<MapScreen> createState() => _MapScreenState();
}

class _MapScreenState extends State<MapScreen> {
  MetamapMapViewController? _mapController;
  bool _mapReady = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: MetamapMapView(
          configuration: MetamapMapViewConfiguration(
            mapSlug: 'example',
            language: 'en',
          ),
          onViewCreated: (controller) => _mapController = controller,
          onEvent: (event) {
            if (event is MapReady) {
              _updateMapReady(true);
            } else if (event is MapLoadStateChanged &&
                event.state == MapViewLoadState.loading) {
              _updateMapReady(false);
            } else if (event case MapViewErrorEvent()) {
              // Show your recovery UI.
            } else {
              // Keep this branch: SDK updates may add events.
            }
          },
        ),
      ),
      floatingActionButton: FloatingActionButton(
        // Request permissions only from an explicit user action like this one.
        onPressed: _mapReady
            ? () {
                _mapController?.startPositioning();
              }
            : null,
        child: const Icon(Icons.my_location),
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

Disabling the location button until the map is ready tells the user when the map accepts input.

Set `mapSlug` to the URL identifier of the public map configured in the Metamaps console. `'example'` is a
placeholder that only lets the app build; replace it with the slug you were given before running the app.

When the widget is disposed, the plugin disposes the native map view, positioning, and the WebView.

## 5. Permissions

On Android, the plugin merges these eight permissions into your app's manifest. You do not need to add them to your
`AndroidManifest.xml`.

| Permission | Purpose | Runtime dialog |
|---|---|---|
| `INTERNET` | Loading the map | No |
| `ACCESS_FINE_LOCATION` | Beacon scanning and positioning | Yes |
| `ACCESS_COARSE_LOCATION` | Requested together with precise location on Android 12 and later | Yes |
| `BLUETOOTH_SCAN` | Beacon scanning | Yes (Android 12 and later) |
| `BLUETOOTH_CONNECT` | Required before a scan can start | Yes (Android 12 and later) |
| `ACTIVITY_RECOGNITION` | Step counting | Yes (Android 10 and later) |
| `BLUETOOTH` | Compatibility declaration for Android 11 and earlier, `maxSdkVersion="30"` | No |
| `BLUETOOTH_ADMIN` | Compatibility declaration for Android 11 and earlier, `maxSdkVersion="30"` | No |

Positioning on Android requires `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`, plus `BLUETOOTH_SCAN` and
`BLUETOOTH_CONNECT` on Android 12 and later. `ACTIVITY_RECOGNITION` is optional and is used only for step
counting; if the user denies it, positioning continues without steps.

On iOS, add these four keys to `ios/Runner/Info.plist`:

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

Location permission is required for positioning on iOS. Motion & Fitness is optional and is used only for step
counting; if the user denies it, positioning continues without steps. The SDK does not use Core Bluetooth
directly, so it needs neither `NSBluetoothAlwaysUsageDescription` nor the background location mode.

### Starting positioning

`positioningStartTrigger` decides when positioning starts. If a required permission has not been granted when
positioning starts, the OS shows its permission dialog.

| Value | Behavior |
|---|---|
| `userAction` | Starts when the user taps the location button in the map |
| `automatic` | Starts automatically once the map is ready |
| `automaticWhenAuthorized` (default) | Starts automatically once the map is ready, but only if every required permission is already granted |

```dart
MetamapMapViewConfiguration(
  mapSlug: 'example',
  positioningStartTrigger: MapViewPositioningStartTrigger.userAction,
)
```

To use your own location button, call `MetamapMapViewController.startPositioning()` as in the example above.

`positioningStartTrigger` only decides **when** a start request happens. `positioningPolicy` decides whether the
SDK handles the request or passes it to your app. The default `userInitiated` lets the SDK request permissions and
start positioning, `hostControlled` only sends the `MapPositioningStartRequested` event to your app, and `disabled`
ignores start requests. With `positioningPolicy: MapViewPositioningPolicy.hostControlled`, your app keeps control
of when and how permissions are requested even when automatic start is enabled.

Your IDE's hover documentation, or `dart doc` run in the plugin directory, shows the public API documentation.

## 6. Receive events

`MetamapMapView` sends map readiness and errors to `onEvent` as `MetamapMapViewEvent`. The main events are:

- `MapReady`: the web map accepts `setLanguage()`, `selectFloor()`, `showSpot()`, and `setDestination()` on
  `MetamapMapViewController`.
- `MapViewErrorEvent`: loading the map or a configuration value failed, or the web map could not complete an
  operation. For the last case the code is `mapOperationFailed`; the map stays usable, so do not reload it.

`onViewCreated` reports that the controller exists, but the web map may not be ready yet. Wait for `MapReady`
before calling controller methods that control the displayed map.

## 7. Change the display language

Set the initial language with `MetamapMapViewConfiguration.language`, using a BCP 47 language tag such as `ja`,
`en`, or `zh-Hans`. To change the language of a displayed map, call `MetamapMapViewController.setLanguage()`. You
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
final configuration = MetamapMapViewConfiguration(
  mapSlug: 'example',
  language: 'en',
  additionalQuery: const <String, String>{
    'spot': '6Y6SSBY5',
  },
);
```

## 9. Build

Run the app from Android Studio or Visual Studio Code on the device of your choice. From a terminal, run:

```bash
flutter pub get
flutter analyze
flutter build apk --debug
flutter build ios --simulator --debug
```

## 10. Update the SDK

Replace the whole `vendor/metamap-flutter/` directory with the new version. Do not change your app's
`pubspec.yaml`, `android/build.gradle.kts`, `android/settings.gradle.kts`, `Info.plist`, `AndroidManifest.xml`, or
your code.

```bash
rm -rf vendor/metamap-flutter
unzip MetamapSDK-Flutter-*.zip -d vendor
mv vendor/MetamapSDK-Flutter-* vendor/metamap-flutter
```

**Do not skip `rm -rf`.** If `vendor/metamap-flutter` still exists, `mv` moves the new directory **into** it.
Flutter then keeps reading the old `vendor/metamap-flutter/pubspec.yaml`, `android/`, and `ios/`, and the build
succeeds with the old SDK without any error or warning.

After replacing the directory, rebuild the dependencies and the native configuration:

```bash
flutter clean
flutter pub get
flutter build ios --config-only
```

Apps that stay on CocoaPods must also run `cd ios && pod install`. The plugin's podspec carries a version, so
without this step the app keeps building with the old version.

A release ZIP records its version in `vendor/metamap-flutter/VERSION`. At run time, read
`metamapPositioningSdkVersion`:

```dart
import 'package:metamap_positioning/metamap_positioning.dart';

debugPrint(metamapPositioningSdkVersion);
```

Read `CHANGELOG.md` for the changes in each version. A version that changes a default value can change your app's
behavior even if you do not change your code. `CHANGELOG.md` also announces when the Gradle, Android Gradle Plugin,
Kotlin, Flutter, or Dart requirements in step 2 increase.
