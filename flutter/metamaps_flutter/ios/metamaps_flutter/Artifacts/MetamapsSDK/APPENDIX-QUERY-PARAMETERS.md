# Appendix: map view query parameters

These public query parameters adjust the initial view of `MetamapsMapView`. They are the same on iOS, Android, and
Flutter. To change the floor, spot, or destination after the map is displayed, use the map view methods instead of
rebuilding the URL.

## How to pass parameters

Pass the parameters in this list as strings in `additionalQuery` of the map view configuration.

`floor` takes the floor **label** registered in the facility settings, such as `2F`, `B1`, or `GF`. It is
case-insensitive. The "URL parameters" page of the Metamaps console shows the values: choosing a floor from the
drop-down generates a public URL that contains `floor`. The value is the same as the "Label" field in the floor
editor of the facility settings.

## Public parameters

| Parameter | Value | Behavior |
|---|---|---|
| `floor` | Floor label, for example `2F` | Sets the initial floor |
| `zoom` | `0` to `24` | Sets the initial zoom |
| `pitch` | `0` to `85` | Sets the initial pitch in degrees |
| `bearing` | Number | Sets the initial bearing; the value is normalized to 0–360 degrees |
| `center` | `latitude,longitude` | Sets the initial center |
| `spot` | Public spot ID (stable key) | Moves to the spot's floor, selects the spot, and opens its details |
| `list` | `1` | Opens the spot list at startup |
| `from` | Stable key or `gps` | Sets the route origin; `gps` means the current location |
| `to` | Stable key | Sets the route destination |
| `pin` | `latitude,longitude` | Shows a shared location; on multi-floor facilities, use it together with `floor` |

The parameters in this appendix are the full public contract.

With `from=gps`, the origin is filled in once positioning has a fix. To leave the origin unset, omit `from`.

## Changing the view after it is displayed

| Operation | iOS | Android | Flutter |
|---|---|---|---|
| Language | `MetamapsMapView.setLanguage(_:)` | `MetamapsMapView.setLanguage()` | `MetamapsMapViewController.setLanguage()` |
| Floor | `MetamapsMapView.selectFloor(_:)` | `MetamapsMapView.selectFloor()` | `MetamapsMapViewController.selectFloor()` |
| Spot | `MetamapsMapView.showSpot(_:)` | `MetamapsMapView.showSpot()` | `MetamapsMapViewController.showSpot()` |
| Destination | `MetamapsMapView.setDestination(_:)` | `MetamapsMapView.setDestination()` | `MetamapsMapViewController.setDestination()` |

Call these methods after the event that says the web map accepts commands: `.ready` on iOS,
`MetamapsMapViewEvent.Ready` on Android, and `MapReady` on Flutter. Each platform's developer guide shows how to
receive events.
