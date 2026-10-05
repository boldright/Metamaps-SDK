# Metamaps iOS SDK Developer Guide

This guide adds the Metamaps map view to an iOS app from a local Swift package, configures it, and shows the basic
calls an app makes. Indoor positioning is optional; step 8 covers it. The SDK requires iOS 16 or later and Xcode 26
or later.

## 1. Get the SDK package

Place the SDK package in a fixed directory inside your app repository, for example `Vendor/metamaps-ios`. The
directory must keep `Package.swift` and `Artifacts/` side by side.

From a release ZIP (`MetamapsSDK-iOS-<version>.zip`):

```bash
mkdir -p Vendor
unzip MetamapsSDK-iOS-*.zip -d Vendor
mv Vendor/MetamapsSDK-iOS-* Vendor/metamaps-ios
```

From the [public repository](https://github.com/boldright/Metamaps-SDK), copy its `ios/` directory instead
(run from your app repository):

```bash
git clone https://github.com/boldright/Metamaps-SDK.git ../Metamaps-SDK
mkdir -p Vendor
cp -R ../Metamaps-SDK/ios Vendor/metamaps-ios
```

To get a specific version, check out its tag, for example `git -C ../Metamaps-SDK checkout v0.6.0`.

Keeping the directory name fixed means Xcode's package reference survives SDK updates. Step 10 describes how to
replace the package.

## 2. Add the package in Xcode

1. Choose `File > Add Package Dependencies... > Add Local...`.
2. Select the directory that contains `Package.swift`.
3. Add the `Metamaps` product to your app target.

If your app is itself a Swift package, add the dependency with a relative path:

```swift
dependencies: [
    .package(path: "../Vendor/metamaps-ios"),
]
```

## 3. Add the view with UIKit

Use `MetamapsMapView` on the main actor. Call `dispose()` on the main actor when the screen closes, not from
`deinit`. `MetamapsMapViewDelegate` receives map readiness and error events. In this example,
`MapViewController` implements the delegate itself.

```swift
import Metamaps
import UIKit

@MainActor
final class MapViewController: UIViewController, MetamapsMapViewDelegate {
    private var metamapsView: MetamapsMapView?
    private var loadTask: Task<Void, Never>?
    private var isMapReady = false

    override func viewDidLoad() {
        super.viewDidLoad()

        let mapView = MetamapsMapView(configuration: .init(
            mapSlug: "example",
            language: "en"
        ))
        mapView.delegate = self
        mapView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(mapView)
        NSLayoutConstraint.activate([
            mapView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            mapView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            mapView.topAnchor.constraint(equalTo: view.topAnchor),
            mapView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        metamapsView = mapView

        loadTask = Task { @MainActor in
            do {
                try await mapView.load()
            } catch is CancellationError {
                // The screen closed while loading.
            } catch {
                // Show your recovery UI.
            }
        }
    }

    func metamapsMapView(_ mapView: MetamapsMapView, didReceive event: MetamapsMapViewEvent) {
        switch event {
        case .ready:
            isMapReady = true
        case .loadState(.loading):
            isMapReady = false
        case .error(let error):
            // Show your recovery UI.
            print(error.localizedDescription)
        default:
            // Keep a default branch: SDK updates may add events.
            break
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        guard isBeingDismissed || isMovingFromParent || navigationController?.isBeingDismissed == true else {
            return
        }
        loadTask?.cancel()
        metamapsView?.dispose()
        metamapsView = nil
        isMapReady = false
    }
}
```

Set `mapSlug` to the URL identifier of the public map configured in the Metamaps console. `"example"` is a
placeholder that only lets the app build; replace it with the slug you were given before running the app.

Keep `MetamapsMapView` as a property of the view controller that shows it. You do not need to call `dispose()`
when the app moves to the background or another screen temporarily covers the map. Cancel the loading task and
call `dispose()` on the main actor only when the screen closes and the view controller is released.

## 4. Add the Info.plist keys

Add these four keys to your app's `Info.plist`, even if you only show maps:

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

- The microphone and speech recognition keys are used by the map's voice input.
- The location and motion keys are used only by indoor positioning (step 8). App Store Connect still checks for
  them because the SDK links Core Location and Core Motion. The system never shows these prompts unless
  positioning starts.

The SDK does not use Core Bluetooth directly, so it needs neither `NSBluetoothAlwaysUsageDescription` nor the
background location mode.

## 5. Receive events

`MetamapsMapView` sends map readiness and errors to the delegate as `MetamapsMapViewEvent`. The main events are:

- `.ready`: the web map accepts `setLanguage(_:)`, `selectFloor(_:)`, `showSpot(_:)`, and `setDestination(_:)`.
- `.error`: loading the map or a configuration value failed, or the web map could not complete an operation. For the
  last case the code is `mapOperationFailed`; the map stays usable, so do not reload it.

`load()` finishes when the web content has loaded, which is earlier than `.ready`. As in the example above, wait
for `.ready` before calling methods that control the displayed map.

## 6. Change the display language

Set the initial language with `MetamapsMapViewConfiguration.language`, using a BCP 47 language tag such as `ja`,
`en`, or `zh-Hans`. To change the language of a displayed map, call `MetamapsMapView.setLanguage(_:)`. You do not
need to recreate the view; the web map reloads in the selected language.

Add this method to `MapViewController`:

```swift
@MainActor
func changeMapLanguage(to language: String) {
    guard isMapReady else { return }
    do {
        try metamapsView?.setLanguage(language)
    } catch {
        // Show your recovery UI.
    }
}
```

The available languages depend on the map's publishing settings. A language the map does not publish produces an
`.error` event with `mapOperationFailed`. Pass a specific published language tag to `setLanguage(_:)`. To return to
automatic selection by device language, create a new map view with `language: nil` or `language: "auto"`.

## 7. Query parameters

[Appendix: map view query parameters](APPENDIX-QUERY-PARAMETERS.md) lists the public parameters for the initial
view, their value ranges, and how to change them after the map is displayed. For example, to open a specific spot,
pass it in `additionalQuery`:

```swift
let configuration = MetamapsMapViewConfiguration(
    mapSlug: "example",
    language: "en",
    additionalQuery: [
        "spot": "6Y6SSBY5",
    ]
)
```

## 8. Indoor positioning (optional)

Indoor positioning shows the user's current location on maps whose facility has installed beacons and enabled
positioning in Metamaps. For other maps, the map view requests no permissions and downloads no positioning data,
so map-only apps can skip this step.

Location permission is required for positioning. Motion & Fitness is optional and is used only for step
counting; if the user denies it, positioning continues without steps.

### When positioning starts

`positioningStartTrigger` decides when positioning starts. If a required permission has not been granted when
positioning starts, the OS shows its permission dialog.

| Value | Behavior |
|---|---|
| `.userAction` | Starts when the user taps the location button in the map |
| `.automatic` | Starts automatically once the map is ready |
| `.automaticWhenAuthorized` (default) | Starts automatically once the map is ready, but only if every required permission is already granted |

```swift
let configuration = MetamapsMapViewConfiguration(
    mapSlug: "example",
    positioningStartTrigger: .userAction
)
```

To use your own location button, call `requestPositioningAuthorization()` and `startPositioning()` from the app.
Xcode Quick Help documents every public type.

`positioningStartTrigger` only decides **when** a start request happens. `positioningPolicy` decides whether the
SDK handles the request or passes it to your app. The default `.userInitiated` lets the SDK request permissions
and start positioning, `.hostControlled` only sends the `.positioningStartRequested` event to your app, and
`.disabled` ignores start requests. With `.hostControlled`, your app keeps control of when and how permissions
are requested even when automatic start is enabled.


To show positioning with your own map instead of the map view, use the `MetamapsPositioning` product and
`MetamapsPositioningClient`.

## 9. Build

In Xcode, select your app scheme and an iOS Simulator, then choose `Product > Build` or `Product > Run`. To check
the same setup from a terminal, run:

```bash
xcodebuild \
  -project YourApp.xcodeproj \
  -scheme YourApp \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ./DerivedData \
  clean build
```

## 10. Update the SDK

Replace the whole `Vendor/metamaps-ios/` directory with the new version. Do not change Xcode's package reference,
`Info.plist`, or your code.

```bash
rm -rf Vendor/metamaps-ios
unzip MetamapsSDK-iOS-*.zip -d Vendor
mv Vendor/MetamapsSDK-iOS-* Vendor/metamaps-ios
```

**Do not skip `rm -rf`.** If `Vendor/metamaps-ios` still exists, `mv` moves the new directory **into** it. Xcode
then keeps reading the old `Vendor/metamaps-ios/Package.swift` and `Artifacts/`, and the build succeeds with the
old SDK without any error or warning.

After replacing the package, choose `Product > Clean Build Folder` in Xcode and build again. The local package
resolves by the fixed directory name from step 1, so you do not need to re-add the package. If Xcode keeps
showing old content, choose `File > Packages > Reset Package Caches`.

A release ZIP records its version in `Vendor/metamaps-ios/VERSION`. At run time, read
`MetamapsSDK.version`:

```swift
import Metamaps

print(MetamapsSDK.version)
```

Read `CHANGELOG.md` for the changes in each version. A version that changes a default value can change your app's
behavior even if you do not change your code.
