# Metamaps Android SDK

A Kotlin SDK for Android API 24 or later. The standard `MetamapMapView` embeds a published Metamaps web map from a
`mapSlug` and connects it to BLE and pedestrian dead reckoning positioning from the same SDK. For apps with their
own map UI, the headless `MetamapPositioningClient` is also available.

The [Android developer guide](../docs/android-installation.md) walks through installation and setup step by step.

This build uses JDK 17, Gradle 9.8.0, Android Gradle Plugin 9.4.1, Kotlin 2.4.20, and compile and target SDK 37.
The libraries require minSdk 24 and compileSdk 36 or later in the host app.

## Artifacts

All artifacts use the group `jp.metamaps.positioning`.

- `metamap-mapview`: the standard map. It hosts an Android `WebView` that provides search, spots, floors, and
  routing, and bridges native positioning to the web map.
- `metamap-positioning`: iBeacon scanning with the Android Beacon Library, the Sensor Framework, manifest download
  and caching, and typed events and errors.
- `metamap-positioning-core`: the positioning engine, shipped as a prebuilt AAR in `maven-repository/`. The two
  libraries above depend on it, so you normally do not declare it yourself.

The SDK contains no client secret.

## Installation

1. Publish the SDK into a local Maven repository (`./gradlew publishSdkToBuildRepository` writes it to
   `build/maven-repository`), or unzip `MetamapSDK-Android-<version>.zip`, and copy the repository into your app,
   for example as `vendor/metamap-android/maven-repository`.
2. Add the repository to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri(rootDir.resolve("vendor/metamap-android/maven-repository"))
        }
    }
}
```

3. Add the map view to your app module. Reading the version from a `VERSION` file means an SDK update does not
   touch your build files:

```kotlin
val metamapVersion = rootDir.resolve("vendor/metamap-android/VERSION").readText().trim()

dependencies {
    implementation("jp.metamaps.positioning:metamap-mapview:$metamapVersion")
}
```

Do not use `flatDir` or `files(...)` references to AARs; they do not resolve transitive dependencies. For headless
positioning only, depend on `jp.metamaps.positioning:metamap-positioning:<version>`. The coroutines Android
dispatcher resolves transitively, so you do not need to add it. AndroidX, Kotlin coroutines, and the Android
Beacon Library come from Google Maven and Maven Central; in a fully offline environment, mirror these third-party
dependencies as well.

The library manifest declares the permissions it needs. Do not add `neverForLocation` to `BLUETOOTH_SCAN`; it
filters out the BLE advertisements that positioning relies on.

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

Android 12 and later require coarse and fine location to be requested together, so the SDK requests them in the
same prompt. If the user grants only approximate location, indoor positioning does not start and the SDK returns a
typed error that asks for precise location. `BLUETOOTH` and `BLUETOOTH_ADMIN` for Android 11 and earlier are also
merged with an API limit. The SDK does not request location or Bluetooth permissions from `load()` or
`configure()`; request them only from a user action, such as tapping a location button, or from an explicit call
in your app. If the user denies activity recognition, positioning continues with BLE only.

For the Google Play Data safety form: positioning is computed on the device, and the SDK does not send
coordinates or RSSI values to any server.

## Map view

```kotlin
import jp.metamaps.mapview.MapViewPositioningPolicy
import jp.metamaps.mapview.MetamapMapView
import jp.metamaps.mapview.MetamapMapViewConfiguration

val mapView = MetamapMapView(context).apply {
    configure(
        MetamapMapViewConfiguration(
            mapSlug = "example",
            language = "en",
            positioningPolicy = MapViewPositioningPolicy.USER_INITIATED,
        ),
    )
    eventListener = ::handleMapEvent
}
mapView.load()
```

For apps targeting API 33 or later, consumer lint can report `NotificationPermission` for the Android Beacon
Library's optional `BluetoothMedic`. The SDK never calls that feature, so do not add `POST_NOTIFICATIONS`. The
developer guide shows a `lint.xml` rule that ignores only this third-party class.

Only `mapSlug` is required; the production base URL is `https://metamaps.jp`. Optional settings include
`groupId`, `language`, `initialFloorId`, and `previewToken`, a short-lived token that shows an unpublished map. The
view provides `load()` / `reload()`, `requestPositioningAuthorization()`, `startPositioning()` /
`stopPositioning()`, `selectFloor()`, `showSpot()`, `setDestination()`, `setLanguage()`, the center reticle and
its candidate coordinate, `dispose()`, and typed events as a `SharedFlow`.

The web view rejects main-frame navigation and bridge messages from any origin other than the configured one, and
validates the document token, the bridge major version, the map and group, and a monotonically increasing sequence
number. Raw BLE and motion samples, long-lived tokens, and secrets are never passed to JavaScript. If positioning
fails to initialize, the web map keeps working. The IDs passed to `showSpot()` and `setDestination()` are the
stable keys of public spots, such as `6Y6SSBY5` (keys in the older `spot_` format are also accepted).

## Headless positioning

```kotlin
val client = MetamapPositioningClient(
    applicationContext,
    PositioningConfiguration(mapSlug = "example"),
)
client.configure()                       // Never shows a permission dialog.
client.requestAuthorization(activity)    // Call from a user action.
client.start()

lifecycleScope.launch {
    client.events.collect { event ->
        if (event is MetamapPositioningEvent.Position) {
            render(event.update)
        }
    }
}
```

The SDK validates the manifest with its ETag and a SHA-256 digest of its RFC 8785 canonical JSON, and caches it
atomically in the app sandbox. Without a network connection, it uses the last validated manifest for up to 7 days
by default. Unknown positioning algorithm versions are rejected. The Android Beacon Library is used only to parse
iBeacon packets and to manage the scan lifecycle; its distance estimator and long-window RSSI average are not
used. Positions are computed on the device by the Metamaps positioning engine.

To keep the time spent reading a description or walking to the start point out of the next segment, call
`client.resetPedestrianRoute()` (`mapView.resetPedestrianRoute()` with the map view) right before the segment
starts. It resets the step detector baseline, the step interval, and the estimator state, and keeps the manifest
and permissions. The Sensor Framework adapter reports the step detector, rotation vector, gyroscope, and
magnetometer in the device capabilities, and the attitude includes roll, pitch, heading accuracy, and magnetic
accuracy.

## Foreground only

This version supports foreground positioning. When the app moves to the background, the screen locks, or the user
switches apps, the SDK stops scanning, sensors, and timers and reports `paused_background` instead of continuing
to report a stale location. When the app returns to the foreground, it revalidates the manifest, resets the
estimator, and resumes from `recovering`.

The SDK does not request background location or foreground service permissions. The Android SDK does not use the
activity recognition API for stationary detection, so the estimator does not receive activity events on Android.

## Beacon diagnostics

To show the relationship between the device and the registered beacons on the device, set
`PositioningConfiguration(beaconDiagnosticsEnabled = true)` in headless mode or
`MetamapMapViewConfiguration(showsBeaconDiagnostics = true)` with the map view. The `BeaconSignals` event reports
each registered beacon's position, a short-term smoothed RSSI, the distance estimated from calibration values, and
the distance and residual to the device position on the same floor.

Diagnostics are off by default. The event is a latest-only derived snapshot, not raw BLE or motion data, and it is
never sent to telemetry or persistent storage.

## Samples

- `samples/view`: the map view with the Android View API.
- `samples/compose`: the map view inside Jetpack Compose through `AndroidView`.
- `samples/mapviewsample`: a test app that switches the map slug, base URL, extra query parameters, positioning
  start trigger, beacon diagnostics, and the WebView inspector from its settings screen.

The Compose BOM, Activity Compose, and Material 3 are sample-only dependencies and are not part of the published
AARs.

## Verification

```bash
./gradlew test \
  :metamap-positioning:assembleRelease \
  :metamap-mapview:assembleRelease \
  :samples:view:assembleDebug \
  :samples:compose:assembleDebug \
  :samples:mapviewsample:assembleDebug \
  lint \
  writeReleaseSbom \
  writeReleaseChecksums
```

A CycloneDX 1.6 SBOM is written to `build/reports/sbom`.

```bash
./gradlew publishSdkToBuildRepository
```

This publishes the two libraries (with their POMs and sources JARs) and copies the positioning engine into
`build/maven-repository`.

Real beacons, permission prompts, Bluetooth turned off, offline caching, foreground recovery, vendor-specific
scanning behavior, battery use, and the web map bridge need to be checked on physical Android devices.
