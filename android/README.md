# Metamaps Android SDK

A Kotlin SDK for Android API 24 or later that embeds maps built with Metamaps in your app. `MetamapsMapView`
shows the published Metamaps map for a `mapSlug`, with search, spots, floors, and routes. Facilities with
installed beacons can also show the user's indoor location on the same map.

The [Android developer guide](../docs/android-installation.md) walks through installation and setup step by step.

This build uses JDK 17, Gradle 9.8.0, Android Gradle Plugin 9.4.1, Kotlin 2.4.20, and compile and target SDK 37.
The libraries require minSdk 24 and compileSdk 36 or later in the host app.

## Artifacts

All artifacts use the group `jp.metamaps`.

- `metamaps-mapview`: the map view. It hosts an Android `WebView` with the Metamaps map and, for maps with indoor
  positioning, connects it to positioning on the device.
- `metamaps-positioning`: indoor positioning without the map view (`MetamapsPositioningClient`) and the shared
  types, such as `jp.metamaps.MetamapsError`. The map view depends on it.
- `metamaps-positioning-core`: the positioning engine, shipped as a prebuilt AAR in `maven-repository/`. The two
  libraries above depend on it, so you normally do not declare it yourself.

The SDK contains no client secret.

## Installation

1. Publish the SDK into a local Maven repository (`./gradlew publishSdkToBuildRepository` writes it to
   `build/maven-repository`), or unzip `MetamapsSDK-Android-<version>.zip`, and copy the repository into your app,
   for example as `vendor/metamaps-android/maven-repository`.
2. Add the repository to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri(rootDir.resolve("vendor/metamaps-android/maven-repository"))
        }
    }
}
```

3. Add the map view to your app module. Reading the version from a `VERSION` file means an SDK update does not
   touch your build files:

```kotlin
val metamapsVersion = rootDir.resolve("vendor/metamaps-android/VERSION").readText().trim()

dependencies {
    implementation("jp.metamaps:metamaps-mapview:$metamapsVersion")
}
```

Do not use `flatDir` or `files(...)` references to AARs; they do not resolve transitive dependencies. AndroidX,
Kotlin coroutines, and the Android Beacon Library come from Google Maven and Maven Central; in a fully offline
environment, mirror these third-party dependencies as well.

A map-only app needs no permission entries: the libraries add only `INTERNET` to the merged manifest. They also
remove `ACCESS_COARSE_LOCATION`, `BLUETOOTH`, and `BLUETOOTH_ADMIN`, which the Android Beacon Library declares. The
removal can also apply to the same permissions declared by other libraries, so if another library needs any of
them, declare it in your app's manifest; your app's own declarations always take priority.

## Map view

```kotlin
import jp.metamaps.mapview.MetamapsMapView
import jp.metamaps.mapview.MetamapsMapViewConfiguration

val mapView = MetamapsMapView(context).apply {
    configure(MetamapsMapViewConfiguration(mapSlug = "example", language = "en"))
    eventListener = ::handleMapEvent
}
mapView.load()
```

Only `mapSlug` is required; the production base URL is `https://metamaps.jp`. Optional settings include
`groupId`, `language`, `initialFloorId`, and `previewToken`, a short-lived token that shows an unpublished map. The
view provides `load()` / `reload()`, `selectFloor()`, `showSpot()`, `setDestination()`, `setLanguage()`, the
center reticle and its candidate coordinate, `dispose()`, and typed events as a `SharedFlow`.

The web view rejects main-frame navigation and bridge messages from any origin other than the configured one, and
validates the document token, the bridge major version, the map and group, and a monotonically increasing sequence
number. Long-lived tokens and secrets are never passed to JavaScript. The IDs passed to `showSpot()` and
`setDestination()` are the stable keys of public spots, such as `6Y6SSBY5` (keys in the older `spot_` format are
also accepted).

For apps targeting API 33 or later, consumer lint can report `NotificationPermission` for the Android Beacon
Library's optional `BluetoothMedic`. The SDK never calls that feature, so do not add `POST_NOTIFICATIONS`. The
developer guide shows a `lint.xml` rule that ignores only this third-party class.

## Indoor positioning (optional)

Indoor positioning works for maps whose facility has installed beacons and enabled positioning in Metamaps. For
other maps, the map view downloads no positioning data and reports no positioning errors.

To use it, declare the positioning permissions in your app's manifest. The developer guide has the exact block,
including `BLUETOOTH` and `BLUETOOTH_ADMIN` for Android 11 and earlier. Do not add `neverForLocation` to
`BLUETOOTH_SCAN`; it filters out the beacon advertisements that positioning relies on. When a permission is
missing, the permission request fails with a `configurationInvalid` error that lists it.

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

Android 12 and later require coarse and fine location to be requested together, so the SDK requests them in the
same prompt. If the user grants only approximate location, indoor positioning does not start and the SDK returns a
typed error that asks for precise location. If the user denies activity recognition, positioning continues
without step counting. The SDK never requests permissions from `load()` or `configure()`; the map view requests
them when the user taps the location control in the map, or when your app calls
`requestPositioningAuthorization()` / `startPositioning()`.

For the Google Play Data safety form: positions are computed on the device, and the SDK does not send coordinates
or signal strengths to any server.

### Positioning without the map view

```kotlin
val client = MetamapsPositioningClient(
    applicationContext,
    PositioningConfiguration(mapSlug = "example"),
)
client.configure()                       // Never shows a permission dialog.
client.requestAuthorization(activity)    // Call from a user action.
client.start()

lifecycleScope.launch {
    client.events.collect { event ->
        if (event is MetamapsPositioningEvent.Position) {
            render(event.update)
        }
    }
}
```

Depend on `jp.metamaps:metamaps-positioning:<version>` for this. The SDK validates the positioning data (the
manifest) with its ETag and a SHA-256 digest of its RFC 8785 canonical JSON, and caches it atomically in the app
sandbox. Without a network connection, it uses the last validated manifest for up to 7 days by default. The
Android Beacon Library is used only to parse iBeacon packets and to manage the scan lifecycle; its distance
estimator and long-window signal average are not used. Positions are computed on the device by the Metamaps
positioning engine.

To keep the time spent reading a description or walking to the start point out of the next segment, call
`client.resetPedestrianRoute()` (`mapView.resetPedestrianRoute()` with the map view) right before the segment
starts. It resets the step detector baseline, the step interval, and the estimator state, and keeps the manifest
and permissions.

### Foreground only

This version supports foreground positioning. When the app moves to the background, the screen locks, or the user
switches apps, the SDK stops scanning, sensors, and timers and reports `paused_background` instead of continuing
to report a stale location. When the app returns to the foreground, it revalidates the manifest, resets the
estimator, and resumes from `recovering`. The SDK does not request background location or foreground service
permissions, and does not use the activity recognition API for stationary detection.

### Beacon diagnostics

To show the relationship between the device and the registered beacons on the device, set
`PositioningConfiguration(beaconDiagnosticsEnabled = true)` with `MetamapsPositioningClient`, or
`MetamapsMapViewConfiguration(showsBeaconDiagnostics = true)` with the map view. The `BeaconSignals` event reports
each registered beacon's position, a short-term smoothed signal strength, the distance estimated from calibration
values, and the distance and residual to the device position on the same floor.

Diagnostics are off by default. The event is a latest-only derived snapshot, not raw Bluetooth or motion data, and
it is never sent to telemetry or persistent storage.

## Samples

- `samples/view`: a minimal map-only app with the Android View API. It declares no positioning permissions.
- `samples/compose`: a minimal map-only app with Jetpack Compose through `AndroidView`.

The Compose BOM, Activity Compose, and Material 3 are sample-only dependencies and are not part of the published
AARs.

## Verification

```bash
./gradlew test \
  :metamaps-positioning:assembleRelease \
  :metamaps-mapview:assembleRelease \
  :samples:view:assembleDebug \
  :samples:compose:assembleDebug \
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
scanning behavior, battery use, and the map bridge need to be checked on physical Android devices.
