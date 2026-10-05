# Metamaps Android SDK Developer Guide

This guide adds the Metamaps map view to an Android app from a local Maven repository, configures it, and shows the
basic calls an app makes. Indoor positioning is optional; step 9 covers it. The SDK requires minSdk 24, compileSdk
36 or later, and JDK 17. The SDK is built and tested with Gradle 9.8.0, Android Gradle Plugin 9.4.1, and Kotlin
2.4.20.

## 1. Get the local Maven repository

Place the SDK in a fixed directory inside your app repository, for example `vendor/metamaps-android`. The rest of
this guide reads `vendor/metamaps-android/maven-repository` and `vendor/metamaps-android/VERSION`.

From a release ZIP (`MetamapsSDK-Android-<version>.zip`):

```bash
mkdir -p vendor
unzip MetamapsSDK-Android-*.zip -d vendor
mv vendor/MetamapsSDK-Android-* vendor/metamaps-android
```

From the [public repository](https://github.com/boldright/Metamaps-SDK), publish the SDK into a local Maven
repository first, then copy it and record its version (run from your app repository):

```bash
git clone https://github.com/boldright/Metamaps-SDK.git ../Metamaps-SDK
(cd ../Metamaps-SDK/android && ./gradlew publishSdkToBuildRepository)
mkdir -p vendor/metamaps-android
cp -R ../Metamaps-SDK/android/build/maven-repository vendor/metamaps-android/
sed -n 's/^version = "\(.*\)"/\1/p' ../Metamaps-SDK/android/build.gradle.kts > vendor/metamaps-android/VERSION
```

To get a specific version, check out its tag before publishing, for example
`git -C ../Metamaps-SDK checkout v0.6.0`.

Because the directory name is fixed and dependency versions are read from `VERSION`, an SDK update only replaces
this directory; `settings.gradle.kts` and `build.gradle.kts` stay unchanged. Step 11 describes the update.

## 2. Configure Kotlin, repositories, and dependencies

### 2.1 Use Kotlin 2.4.20 or later in your app

The SDK is built with Kotlin 2.4.20, and its public classes carry Kotlin metadata version 2.4.0. A Kotlin
compiler that cannot read that metadata fails in `compileDebugKotlin` with:

> Class 'jp.metamaps.mapview.MetamapsMapView' was compiled with an incompatible version of Kotlin.
> The actual metadata version is 2.4.0, but the compiler version 2.2.0 can read versions up to 2.3.0.

Android Gradle Plugin 9 has built-in Kotlin support whose default compiler is 2.2.0, so the default setup always
fails with the error above. Put Kotlin Gradle Plugin 2.4.20 or later on the build classpath in the root
`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
```

Keep `apply false`: the declaration only puts the plugin on the classpath. Applying
`org.jetbrains.kotlin.android` in the app module's `plugins` block fails with
`The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0`.

### 2.2 Add the Maven repository and the dependency

Add the local repository to your app's `settings.gradle.kts`:

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

Add the map view to the app module's `build.gradle.kts`. Its dependencies, including the coroutines Android
dispatcher, AndroidX Browser, and the libraries used by indoor positioning, resolve transitively from the Maven
metadata. The version is read from `VERSION`:

```kotlin
val metamapsVersion = rootDir.resolve("vendor/metamaps-android/VERSION").readText().trim()

dependencies {
    implementation("jp.metamaps:metamaps-mapview:$metamapsVersion")
}
```

`flatDir` or direct `files(...)` references to the AARs do not resolve transitive dependencies. Third-party
dependencies come from Google Maven and Maven Central; in a fully offline environment, your internal Maven mirror
must provide them too.

## 3. Add the view

`eventListener` receives map readiness and error events. In this example, the same `Activity` receives them.

```kotlin
package com.example.app

import android.app.Activity
import android.os.Bundle
import jp.metamaps.mapview.MetamapsMapView
import jp.metamaps.mapview.MetamapsMapViewConfiguration
import jp.metamaps.mapview.MetamapsMapViewEvent
import jp.metamaps.mapview.MapViewLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mapView: MetamapsMapView
    private var isMapReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapView = MetamapsMapView(this).apply {
            configure(MetamapsMapViewConfiguration(mapSlug = "example", language = "en"))
            eventListener = { event ->
                when (event) {
                    is MetamapsMapViewEvent.Ready -> isMapReady = true
                    is MetamapsMapViewEvent.LoadState -> {
                        if (event.state == MapViewLoadState.LOADING) isMapReady = false
                    }
                    is MetamapsMapViewEvent.Error -> {
                        // Show your recovery UI.
                    }
                    else -> Unit
                }
            }
        }
        setContentView(mapView)
        scope.launch {
            try {
                mapView.load()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Show your recovery UI.
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mapView.dispose()
        isMapReady = false
        super.onDestroy()
    }
}
```

Set `mapSlug` to the URL identifier of the public map configured in the Metamaps console. `"example"` is a
placeholder that only lets the app build; replace it with the slug you were given before running the app.

## 4. Permissions

A map-only app needs no permission entries. The SDK adds only `INTERNET` to your merged manifest; it does not
add location, Bluetooth, or activity recognition permissions. Step 9 lists the permissions that indoor
positioning needs.

The SDK also removes `ACCESS_COARSE_LOCATION`, `BLUETOOTH`, and `BLUETOOTH_ADMIN`, which the Android Beacon
Library it uses declares. The removal can also apply to the same permissions declared by other libraries in your
app, so if another library needs any of them, declare it in your app's manifest; your app's own declarations always
take priority.

Android Studio's Quick Documentation shows the public API documentation from the bundled sources JARs.

## 5. Receive events

`MetamapsMapView` sends map readiness and errors as `MetamapsMapViewEvent` to `eventListener` and to the `events`
flow. The main events are:

- `MetamapsMapViewEvent.Ready`: the web map accepts `setLanguage()`, `selectFloor()`, `showSpot()`, and
  `setDestination()`.
- `MetamapsMapViewEvent.Error`: loading the map or a configuration value failed, or the web map could not complete an
  operation. For the last case the code is `MAP_OPERATION_FAILED`; the map stays usable, so do not reload it.

`load()` finishes when the web content has loaded, which is earlier than `Ready`. As in the example above, wait for
`Ready` before calling methods that control the displayed map.

## 6. Change the display language

Set the initial language with `MetamapsMapViewConfiguration.language`, using a BCP 47 language tag such as `ja`,
`en`, or `zh-Hans`. To change the language of a displayed map, call `MetamapsMapView.setLanguage()`. You do not need
to recreate the view; the web map reloads in the selected language.

Add this method to `MainActivity`:

```kotlin
fun changeMapLanguage(language: String) {
    if (!isMapReady) return
    mapView.setLanguage(language)
}
```

The available languages depend on the map's publishing settings. A language the map does not publish produces a
`MetamapsMapViewEvent.Error` with `MAP_OPERATION_FAILED`. Pass a specific published language tag to `setLanguage()`.
To return to automatic selection by device language, configure the map view again with `language = null` or
`language = "auto"` and call `load()` again.

## 7. Query parameters

[Appendix: map view query parameters](APPENDIX-QUERY-PARAMETERS.md) lists the public parameters for the initial
view, their value ranges, and how to change them after the map is displayed. For example, to open a specific spot,
pass it in `additionalQuery`:

```kotlin
val configuration = MetamapsMapViewConfiguration(
    mapSlug = "example",
    language = "en",
    additionalQuery = mapOf(
        "spot" to "6Y6SSBY5",
    ),
)
```

## 8. Android Lint

The transitive Android Beacon Library contains the optional `BluetoothMedic` feature, so Lint may report
`NotificationPermission` for apps targeting API 33 or later. The SDK never calls that feature and never posts
notifications. Do not add `POST_NOTIFICATIONS`; instead, ignore only this caller in the app module's `lint.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<lint>
    <issue id="NotificationPermission">
        <ignore regexp="usage from org\.altbeacon\.bluetooth\.BluetoothMedic" />
    </issue>
</lint>
```

`NotificationPermission` findings for notifications your own app posts do not match this pattern and are still
reported.

## 9. Indoor positioning (optional)

Indoor positioning shows the user's current location on maps whose facility has installed beacons and enabled
positioning in Metamaps. For other maps, the map view requests no permissions and downloads no positioning data,
so map-only apps can skip this step.

### Declare the permissions

Add this block to your app's `AndroidManifest.xml`, and cover these permissions in your store listing and privacy
policy:

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

| Permission | Purpose | Runtime dialog |
|---|---|---|
| `ACCESS_FINE_LOCATION` | Beacon scanning and positioning | Yes |
| `ACCESS_COARSE_LOCATION` | Requested together with precise location on Android 12 and later | Yes |
| `BLUETOOTH_SCAN` | Beacon scanning | Yes (Android 12 and later) |
| `BLUETOOTH_CONNECT` | Required before a scan can start | Yes (Android 12 and later) |
| `ACTIVITY_RECOGNITION` | Step counting | Yes (Android 10 and later) |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | Beacon scanning on Android 11 and earlier | No |

`BLUETOOTH_SCAN` is used to derive location, so do not add `neverForLocation` to it. Declare every permission in
the block. `ACTIVITY_RECOGNITION` is used only for step counting: if the user denies it at run time, positioning
continues without steps. When a required permission is missing from your manifest, the permission request fails
with a `configurationInvalid` error that lists it.

### When positioning starts

`positioningStartTrigger` decides when positioning starts. If a required permission has not been granted when
positioning starts, the OS shows its permission dialog.

| Value | Behavior |
|---|---|
| `USER_ACTION` | Starts when the user taps the location button in the map |
| `AUTOMATIC` | Starts automatically once the map is ready |
| `AUTOMATIC_WHEN_AUTHORIZED` (default) | Starts automatically once the map is ready, but only if every required permission is already granted |

```kotlin
MetamapsMapViewConfiguration(
    mapSlug = "example",
    positioningStartTrigger = MapViewPositioningStartTrigger.USER_ACTION,
)
```

To use your own location button, call `requestPositioningAuthorization()` and `startPositioning()` from the app.

`positioningStartTrigger` only decides **when** a start request happens. `positioningPolicy` decides whether the
SDK handles the request or passes it to your app. The default `USER_INITIATED` lets the SDK request permissions and
start positioning, `HOST_CONTROLLED` only sends the `PositioningStartRequested` event to your app, and `DISABLED`
ignores start requests. With `positioningPolicy = MapViewPositioningPolicy.HOST_CONTROLLED`, your app keeps
control of when and how permissions are requested even when automatic start is enabled.

To show positioning with your own map instead of the map view, depend on
`jp.metamaps:metamaps-positioning` and use `MetamapsPositioningClient`.

## 10. Build

Open the app in Android Studio and choose `Build > Make Project` or `Run`. From a terminal, run:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

The APK is usually written to `app/build/outputs/apk/debug/app-debug.apk`.

## 11. Update the SDK

Replace the whole `vendor/metamaps-android/` directory with the new version. Do not change
`settings.gradle.kts`, `build.gradle.kts`, `lint.xml`, `AndroidManifest.xml`, or your code; the dependency version
comes from `VERSION`.

```bash
rm -rf vendor/metamaps-android
unzip MetamapsSDK-Android-*.zip -d vendor
mv vendor/MetamapsSDK-Android-* vendor/metamaps-android
```

**Do not skip `rm -rf`.** If `vendor/metamaps-android` still exists, `mv` moves the new directory **into** it.
Gradle then keeps reading the old `vendor/metamaps-android/maven-repository` and `VERSION`, and the build succeeds
with the old SDK without any error or warning.

Build as usual after replacing the directory:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

If the build still uses the old SDK, make Gradle ignore its dependency cache:

```bash
./gradlew --refresh-dependencies :app:assembleDebug
```

`vendor/metamaps-android/VERSION` shows the installed version. At run time, read
`jp.metamaps.MetamapsSdk.VERSION`.

Read `CHANGELOG.md` for the changes in each version. A version that changes a default value can change your app's
behavior even if you do not change your code. `CHANGELOG.md` also announces when the Kotlin or Android Gradle
Plugin requirements in step 2.1 increase.
