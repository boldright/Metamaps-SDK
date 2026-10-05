/// Embeds maps built with Metamaps in Flutter apps, with optional indoor positioning.
library;

export 'src/client.dart' show MetamapsPositioningClient;
export 'src/map_view.dart'
    show
        MetamapsMapView,
        MetamapsMapViewController,
        MetamapsMapViewCreatedCallback,
        MetamapsExternalLinkDecisionCallback;
export 'src/models.dart';
