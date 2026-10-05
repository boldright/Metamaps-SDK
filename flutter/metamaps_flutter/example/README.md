# Metamaps Flutter example

A map-only example app. The map view fills the screen and uses extra query parameters, automatic retry, and a
User-Agent suffix. External links open with the default handling, and map errors appear in a snack bar with a
reload action when the SDK suggests a retry. The map view is an ordinary widget, so your app can place it anywhere
with a bounded size.

The Android manifest declares no positioning permissions. To add indoor positioning, follow the "Indoor
positioning (optional)" step of the Flutter developer guide.

```bash
flutter run \
  --dart-define=METAMAPS_MAP_SLUG=replace-with-your-map-slug \
  --dart-define=METAMAPS_BASE_URL=https://metamaps.jp
```

Replace `replace-with-your-map-slug` with the slug of a published map from the Metamaps console.

The plugin bundles its Android libraries in `android/maven-repository`, and the example's Android build uses
them. Without that directory, for example when you build the SDK from source, run
`./gradlew publishSdkToBuildRepository` in the repository's `android/` directory first.

On physical iOS and Android devices, check the map, search, spots, floors, and routes. A wrong slug, an
inaccessible map, a bridge version mismatch, and WebView recovery should keep the map visible or end in a typed
error.
