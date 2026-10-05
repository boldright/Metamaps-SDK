import 'package:flutter/material.dart';
import 'package:metamap_positioning/metamap_positioning.dart';

const _mapSlug = String.fromEnvironment(
  'METAMAP_MAP_SLUG',
  defaultValue: 'replace-with-your-map-slug',
);
const _baseUrl = String.fromEnvironment(
  'METAMAP_BASE_URL',
  defaultValue: 'https://metamaps.jp',
);

void main() {
  runApp(const MetamapExampleApp());
}

class MetamapExampleApp extends StatelessWidget {
  const MetamapExampleApp({super.key});

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
  MetamapMapViewController? _controller;
  String _status = 'Initializing the map view';
  bool _operationPending = false;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Metamaps Flutter SDK'),
        actions: [
          IconButton(
            tooltip: 'Reload',
            onPressed: _operationPending ? null : () => _run(_reload),
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: MetamapMapView(
              configuration: MetamapMapViewConfiguration(
                mapSlug: _mapSlug,
                baseUrl: Uri.parse(_baseUrl),
                positioningPolicy: MapViewPositioningPolicy.hostControlled,
                additionalQuery: const <String, String>{
                  'source': 'flutter-example',
                },
                retryPolicy: MetamapMapViewRetryPolicy.automatic,
                userAgentAppendix: 'MetamapFlutterExample/0.1',
                isWebViewInspectable: false,
              ),
              onViewCreated: (controller) {
                _controller = controller;
                if (mounted) {
                  setState(() => _status = 'Loading the map view');
                }
              },
              onExternalLinkDecision: (url) {
                _setStatus('External link requested: $url');
                return MetamapExternalLinkDecision.openDefault;
              },
              onEvent: _handleMapEvent,
            ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    _status,
                    key: const ValueKey('status'),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: FilledButton.icon(
                          onPressed: _operationPending
                              ? null
                              : () => _run(_requestAuthorization),
                          icon: const Icon(Icons.my_location),
                          label: const Text('Allow permissions'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: FilledButton.tonalIcon(
                          onPressed: _operationPending
                              ? null
                              : () => _run(_startPositioning),
                          icon: const Icon(Icons.play_arrow),
                          label: const Text('Start positioning'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filledTonal(
                        tooltip: 'Stop positioning',
                        onPressed: _operationPending
                            ? null
                            : () => _run(_stopPositioning),
                        icon: const Icon(Icons.stop),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _requestAuthorization() async {
    await _requiredController().requestPositioningAuthorization();
    _setStatus('Location and Bluetooth permissions checked');
  }

  Future<void> _startPositioning() async {
    await _requiredController().startPositioning(requestAuthorization: false);
    _setStatus('Positioning started');
  }

  Future<void> _stopPositioning() async {
    await _requiredController().stopPositioning();
    _setStatus('Positioning stopped');
  }

  Future<void> _reload() async {
    await _requiredController().reload();
    _setStatus('Reloading the map view');
  }

  MetamapMapViewController _requiredController() {
    return _controller ??
        (throw const MetamapPositioningError(
          code: MetamapPositioningErrorCode.internalInvariantViolation,
          message: 'MapView is not ready.',
          recoverable: true,
          userAction: PositioningUserAction.retry,
        ));
  }

  Future<void> _run(Future<void> Function() operation) async {
    if (_operationPending) {
      return;
    }
    setState(() => _operationPending = true);
    try {
      await operation();
    } on MetamapPositioningError catch (error) {
      _setStatus('${error.code.wireValue}: ${error.message}');
    } catch (error) {
      _setStatus('Unexpected error: $error');
    } finally {
      if (mounted) {
        setState(() => _operationPending = false);
      }
    }
  }

  void _handleMapEvent(MetamapMapViewEvent event) {
    switch (event) {
      case MapReady():
        _setStatus('Map ready: ${event.event.mapId}');
      case MapLoadStateChanged():
        _setStatus('Map: ${event.state.wireValue}');
      case MapPositioningStatusChanged():
        _setStatus('Positioning: ${event.status.wireValue}');
      case MapPositionChanged():
        _setStatus(
          'Position: ${event.update.estimate.mode.wireValue} '
          '±${event.update.estimate.accuracyRadiusM.toStringAsFixed(1)}m',
        );
      case MapCapabilitiesChanged():
        _setStatus('Profile: ${event.report.selectedProfile.wireValue}');
      case MapMotionHeadingChanged():
        _setStatus('Heading: ${event.reading.localHeadingDeg ?? '-'}');
      case MapBeaconSignalsChanged():
        _setStatus('BLE: ${event.readings.length}');
      case MapPositioningAuthorizationRequested():
        _setStatus('The web UI requested location permission');
      case MapPositioningStartRequested():
        _setStatus('The web UI requested to start positioning');
      case MapFloorChanged():
        _setStatus('Floor: ${event.event.floorId ?? 'ALL'}');
      case MapSpotSelected():
        _setStatus('Spot: ${event.event.spotId ?? '-'}');
      case MapRouteChanged():
        _setStatus('Route active: ${event.event.active}');
      case MapExternalLinkRequested():
        _setStatus('External link: ${event.uri}');
      case MapViewErrorEvent():
        _setStatus('${event.error.code.wireValue}: ${event.error.message}');
      // Minor updates add cases to MetamapMapViewEvent. This branch is unreachable while every current
      // case is listed, but without it a new case would break the host app's build.
      // ignore: unreachable_switch_case
      case _:
        break;
    }
  }

  void _setStatus(String value) {
    if (mounted) {
      setState(() => _status = value);
    }
  }
}
