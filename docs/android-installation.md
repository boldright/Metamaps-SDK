# Metamaps Android SDK Developer Guide

This guide adds the standard Metamaps map view to an Android app from a local Maven repository, configures it,
and shows the basic calls an app makes. It requires minSdk 24, compileSdk 36 or later, and JDK 17. The SDK is
built and tested with Gradle 9.8.0, Android Gradle Plugin 9.4.1, and Kotlin 2.4.20.

## 1. Get the local Maven repository

Place the SDK in a fixed directory inside your app repository, for example `vendor/metamap-android`. The rest of
this guide reads `vendor/metamap-android/maven-repository` and `vendor/metamap-android/VERSION`.

From a release ZIP (`MetamapSDK-Android-<version>.zip`):

```bash
mkdir -p vendor
unzip MetamapSDK-Android-*.zip -d vendor
mv vendor/MetamapSDK-Android-* vendor/metamap-android
```

From the public repository, publish the SDK into a local Maven repository first, then copy it and record its
version (run from your app repository):

```bash
(cd path/to/metamaps-sdk/android && ./gradlew publishSdkToBuildRepository)
mkdir -p vendor/metamap-android
cp -R path/to/metamaps-sdk/android/build/maven-repository vendor/metamap-android/
sed -n 's/^version = "\(.*\)"/\1/p' path/to/metamaps-sdk/android/build.gradle.kts > vendor/metamap-android/VERSION
```

Because the directory name is fixed and dependency versions are read from `VERSION`, an SDK update only replaces
this directory; `settings.gradle.kts` and `build.gradle.kts` stay unchanged. Step 10 describes the update.

## 2. Configure Kotlin, repositories, and dependencies

### 2.1 Use Kotlin 2.4.20 or later in your app

The SDK is built with Kotlin 2.4.20, and its public classes carry Kotlin metadata version 2.4.0. A Kotlin
compiler that cannot read that metadata fails in `compileDebugKotlin` with:

> Class 'jp.metamaps.mapview.MetamapMapView' was compiled with an incompatible version of Kotlin.
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
            url = uri(rootDir.resolve("vendor/metamap-android/maven-repository"))
        }
    }
}
```

Add the standard map view to the app module's `build.gradle.kts`. The positioning library, the coroutines Android
dispatcher, the Android Beacon Library, and AndroidX Browser resolve transitively from the Maven metadata. The
version is read from `VERSION`:

```kotlin
val metamapVersion = rootDir.resolve("vendor/metamap-android/VERSION").readText().trim()

dependencies {
    implementation("jp.metamaps.positioning:metamap-mapview:$metamapVersion")
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
import jp.metamaps.mapview.MetamapMapView
import jp.metamaps.mapview.MetamapMapViewConfiguration
import jp.metamaps.mapview.MetamapMapViewEvent
import jp.metamaps.mapview.MapViewLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mapView: MetamapMapView
    private var isMapReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapView = MetamapMapView(this).apply {
            configure(MetamapMapViewConfiguration(mapSlug = "example", language = "en"))
            eventListener = { event ->
                when (event) {
                    is MetamapMapViewEvent.Ready -> isMapReady = true
                    is MetamapMapViewEvent.LoadState -> {
                        if (event.state == MapViewLoadState.LOADING) isMapReady = false
                    }
                    is MetamapMapViewEvent.Error -> {
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

The SDK's manifest merges these eight permissions into your app. You do not need to add them to your
`AndroidManifest.xml`, but cover all of them in your store listing and privacy policy.

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

`BLUETOOTH_SCAN` is used to derive location, so it does not carry `neverForLocation`. Positioning requires
`ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`, plus `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` on Android 12
and later. `ACTIVITY_RECOGNITION` is optional and is used only for step counting; if the user denies it,
positioning continues without steps.

### Starting positioning

`positioningStartTrigger` decides when positioning starts. If a required permission has not been granted when
positioning starts, the OS shows its permission dialog.

| Value | Behavior |
|---|---|
| `USER_ACTION` | Starts when the user taps the location button in the map |
| `AUTOMATIC` | Starts automatically once the map is ready |
| `AUTOMATIC_WHEN_AUTHORIZED` (default) | Starts automatically once the map is ready, but only if every required permission is already granted |

```kotlin
MetamapMapViewConfiguration(
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

Android Studio's Quick Documentation shows the public API documentation from the bundled sources JARs.

## 5. Receive events

`MetamapMapView` sends map readiness and errors as `MetamapMapViewEvent` to `eventListener` and to the `events`
flow. The main events are:

- `MetamapMapViewEvent.Ready`: the web map accepts `setLanguage()`, `selectFloor()`, `showSpot()`, and
  `setDestination()`.
- `MetamapMapViewEvent.Error`: loading the map or a configuration value failed, or the web map could not complete an
  operation. For the last case the code is `MAP_OPERATION_FAILED`; the map stays usable, so do not reload it.

`load()` finishes when the web content has loaded, which is earlier than `Ready`. As in the example above, wait for
`Ready` before calling methods that control the displayed map.

## 6. Change the display language

Set the initial language with `MetamapMapViewConfiguration.language`, using a BCP 47 language tag such as `ja`,
`en`, or `zh-Hans`. To change the language of a displayed map, call `MetamapMapView.setLanguage()`. You do not need
to recreate the view; the web map reloads in the selected language.

Add this method to `MainActivity`:

```kotlin
fun changeMapLanguage(language: String) {
    if (!isMapReady) return
    mapView.setLanguage(language)
}
```

The available languages depend on the map's publishing settings. A language the map does not publish produces a
`MetamapMapViewEvent.Error` with `MAP_OPERATION_FAILED`. Pass a specific published language tag to `setLanguage()`.
To return to automatic selection by device language, configure the map view again with `language = null` or
`language = "auto"` and call `load()` again.

## 7. Query parameters

[Appendix: map view query parameters](APPENDIX-QUERY-PARAMETERS.md) lists the public parameters for the initial
view, their value ranges, and how to change them after the map is displayed. For example, to open a specific spot,
pass it in `additionalQuery`:

```kotlin
val configuration = MetamapMapViewConfiguration(
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

## 9. Build

Open the app in Android Studio and choose `Build > Make Project` or `Run`. From a terminal, run:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

The APK is usually written to `app/build/outputs/apk/debug/app-debug.apk`.

## 10. Update the SDK

Replace the whole `vendor/metamap-android/` directory with the new version. Do not change
`settings.gradle.kts`, `build.gradle.kts`, `lint.xml`, `AndroidManifest.xml`, or your code; the dependency version
comes from `VERSION`.

```bash
rm -rf vendor/metamap-android
unzip MetamapSDK-Android-*.zip -d vendor
mv vendor/MetamapSDK-Android-* vendor/metamap-android
```

**Do not skip `rm -rf`.** If `vendor/metamap-android` still exists, `mv` moves the new directory **into** it.
Gradle then keeps reading the old `vendor/metamap-android/maven-repository` and `VERSION`, and the build succeeds
with the old SDK without any error or warning.

Build as usual after replacing the directory:

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

If the build still uses the old SDK, make Gradle ignore its dependency cache:

```bash
./gradlew --refresh-dependencies :app:assembleDebug
```

`vendor/metamap-android/VERSION` shows the installed version. At run time, read
`jp.metamaps.positioning.android.MetamapPositioningSdk.VERSION`.

Read `CHANGELOG.md` for the changes in each version. A version that changes a default value can change your app's
behavior even if you do not change your code. `CHANGELOG.md` also announces when the Kotlin or Android Gradle
Plugin requirements in step 2.1 increase.
