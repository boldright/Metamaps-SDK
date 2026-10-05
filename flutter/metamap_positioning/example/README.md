# Metamaps Flutter example

An example app that shows the slug-based map view, extra query parameters, retry and User-Agent settings,
reloading, external link decisions in the host, permission requests from a user action, starting and stopping
foreground positioning, and typed map and positioning events.

```bash
flutter run \
  --dart-define=METAMAP_MAP_SLUG=replace-with-your-map-slug \
  --dart-define=METAMAP_BASE_URL=https://metamaps.jp
```

Replace `replace-with-your-map-slug` with the slug of a published map from the Metamaps console.

The plugin bundles its Android libraries in `android/maven-repository`, and the example's Android build uses
them. Without that directory, for example when you build the SDK from source, run
`./gradlew publishSdkToBuildRepository` in the repository's `android/` directory first.

On physical iOS and Android devices, check the map, search, spots, floors, routes, permission requests from a
user action, the current-location marker, the 95% accuracy circle, and routes that start from the current
location. A wrong slug, an inaccessible map, a bridge version mismatch, and WebView recovery should keep the map
visible or end in a typed error. Raw BLE and motion samples are never exposed to Dart.
