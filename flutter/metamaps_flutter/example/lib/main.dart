import 'package:flutter/material.dart';
import 'package:metamaps_flutter/metamaps_flutter.dart';

const _mapSlug = String.fromEnvironment(
  'METAMAPS_MAP_SLUG',
  defaultValue: 'replace-with-your-map-slug',
);
const _baseUrl = String.fromEnvironment(
  'METAMAPS_BASE_URL',
  defaultValue: 'https://metamaps.jp',
);

void main() {
  runApp(const MetamapsExampleApp());
}

class MetamapsExampleApp extends StatelessWidget {
  const MetamapsExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff6741d9)),
        useMaterial3: true,
      ),
      home: const MapScreen(),
    );
  }
}

class MapScreen extends StatefulWidget {
  const MapScreen({super.key});

  @override
  State<MapScreen> createState() => _MapScreenState();
}

class _MapScreenState extends State<MapScreen> {
  MetamapsMapViewController? _controller;

  @override
  Widget build(BuildContext context) {
    // The map view is an ordinary widget. Here it fills the screen, but it can also sit in a tab, a sheet, or
    // part of a layout, as long as it gets a bounded size.
    return Scaffold(
      body: SafeArea(
        child: MetamapsMapView(
          configuration: MetamapsMapViewConfiguration(
            mapSlug: _mapSlug,
            baseUrl: Uri.parse(_baseUrl),
            additionalQuery: const <String, String>{
              'source': 'flutter-example',
            },
            retryPolicy: MetamapsMapViewRetryPolicy.automatic,
            userAgentAppendix: 'MetamapsFlutterExample/0.1',
          ),
          onViewCreated: (controller) => _controller = controller,
          onExternalLinkDecision: (url) =>
              MetamapsExternalLinkDecision.openDefault,
          onEvent: _handleMapEvent,
        ),
      ),
    );
  }

  void _handleMapEvent(MetamapsMapViewEvent event) {
    switch (event) {
      case MapReady():
        debugPrint('Map ready: ${event.event.mapId}');
      case MapViewErrorEvent():
        _showError(event.error);
      // This example does not use the other events, such as MapSpotSelected and MapFloorChanged.
      case _:
        break;
    }
  }

  void _showError(MetamapsError error) {
    if (!mounted) {
      return;
    }
    // Offer a reload only when the SDK suggests a retry. For example, mapOperationFailed leaves the map usable.
    final canReload = error.userAction == MetamapsUserAction.retry;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('${error.code.wireValue}: ${error.message}'),
        action: canReload
            ? SnackBarAction(label: 'Reload', onPressed: _reload)
            : null,
      ),
    );
  }

  Future<void> _reload() async {
    try {
      await _controller?.reload();
    } on MetamapsError catch (error) {
      _showError(error);
    }
  }
}
