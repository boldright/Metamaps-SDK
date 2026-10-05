# Third-party notices

This source tree and the Android artifacts use the following third-party software.

| Component | Version | Scope | License | Project |
|---|---:|---|---|---|
| Android Beacon Library | 2.21.2 | `metamap-positioning` runtime | Apache License 2.0 | https://github.com/AltBeacon/android-beacon-library |
| Kotlin coroutines core | 1.11.0 | `metamap-positioning` public async API/runtime | Apache License 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX Browser | 1.10.0 | `metamap-mapview` runtime (Custom Tabs for external links) | Apache License 2.0 | https://developer.android.com/jetpack/androidx/releases/browser |
| Jetpack Compose BOM | 2026.09.00 | Compose sample only | Apache License 2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX Activity Compose | 1.13.0 | Compose sample only | Apache License 2.0 | https://developer.android.com/jetpack/androidx/releases/activity |
| Jetpack Compose UI / Material 3 | versions selected by BOM | Compose sample only | Apache License 2.0 | https://developer.android.com/jetpack/androidx |

The Android Beacon Library is used only for beacon packet parsing and scan lifecycle. Its distance estimator and long-window RSSI average are not used by Metamaps positioning.

The Apache License 2.0 text is available at <https://www.apache.org/licenses/LICENSE-2.0>. Distribution tooling must retain this notice and the license metadata from all resolved Maven artifacts. The generated dependency report/SBOM is the authoritative list for each release artifact.
