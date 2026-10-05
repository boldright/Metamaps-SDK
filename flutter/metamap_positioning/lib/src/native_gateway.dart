import 'dart:async';


import 'messages.g.dart';
import 'models.dart';

abstract interface class NativeGateway {
  Future<int> createClient(PositioningConfiguration configuration);
  Future<void> configureClient(int clientId);
  Future<CapabilityReport> clientCapabilities(int clientId);
  Future<void> requestClientAuthorization(int clientId);
  Future<void> startClient(int clientId);
  Future<void> stopClient(int clientId);
  Future<void> resetClient(int clientId);
  Future<void> resetClientPedestrianRoute(int clientId);
  Future<void> disposeClient(int clientId);
  Future<void> loadMapView(int viewId);
  Future<void> reloadMapView(int viewId);
  Future<void> requestMapViewAuthorization(int viewId);
  Future<void> startMapViewPositioning(
    int viewId, {
    required bool requestAuthorization,
  });
  Future<void> stopMapViewPositioning(int viewId);
  Future<void> resetMapViewPedestrianRoute(int viewId);
  Future<void> selectMapViewFloor(int viewId, String? floorId);
  Future<void> showMapViewSpot(int viewId, String spotId);
  Future<void> setMapViewDestination(int viewId, String? spotId);
  Future<void> setMapViewLanguage(int viewId, String language);
  Future<void> setMapViewCenterReticleEnabled(int viewId, bool enabled);
  Future<MapCenterReticleCandidate?> requestMapViewCenterReticleCandidate(
    int viewId,
  );
  Future<void> openMapViewExternalLink(int viewId, String url);
}

final class PigeonNativeGateway implements NativeGateway {
  PigeonNativeGateway({MetamapHostApi? api}) : _api = api ?? MetamapHostApi();

  final MetamapHostApi _api;

  @override
  Future<int> createClient(PositioningConfiguration configuration) async {
    final result = await _api.createClient(
      NativePositioningConfiguration(
        baseUrl: configuration.baseUrl.toString(),
        mapSlug: configuration.mapSlug,
        groupId: configuration.groupId,
        maxOfflineAgeMs: configuration.maxOfflineAge.inMilliseconds,
        motionPolicy: configuration.motionPolicy.wireValue,
        beaconDiagnosticsEnabled: configuration.beaconDiagnosticsEnabled,
      ),
    );
    _throwIfError(result.error);
    return result.clientId ??
        (throw _invariant('Native createClient returned no client ID.'));
  }

  @override
  Future<void> configureClient(int clientId) =>
      _operation(_api.configureClient(clientId));

  @override
  Future<CapabilityReport> clientCapabilities(int clientId) async {
    final result = await _api.clientCapabilities(clientId);
    _throwIfError(result.error);
    final report = result.report;
    if (report == null) {
      throw _invariant('Native clientCapabilities returned no report.');
    }
    return mapCapabilityReport(report);
  }

  @override
  Future<void> requestClientAuthorization(int clientId) =>
      _operation(_api.requestClientAuthorization(clientId));

  @override
  Future<void> startClient(int clientId) =>
      _operation(_api.startClient(clientId));

  @override
  Future<void> stopClient(int clientId) =>
      _operation(_api.stopClient(clientId));

  @override
  Future<void> resetClient(int clientId) =>
      _operation(_api.resetClient(clientId));

  @override
  Future<void> resetClientPedestrianRoute(int clientId) =>
      _operation(_api.resetClientPedestrianRoute(clientId));

  @override
  Future<void> disposeClient(int clientId) =>
      _operation(_api.disposeClient(clientId));

  @override
  Future<void> loadMapView(int viewId) => _operation(_api.loadMapView(viewId));

  @override
  Future<void> reloadMapView(int viewId) =>
      _operation(_api.reloadMapView(viewId));

  @override
  Future<void> requestMapViewAuthorization(int viewId) =>
      _operation(_api.requestMapViewAuthorization(viewId));

  @override
  Future<void> startMapViewPositioning(
    int viewId, {
    required bool requestAuthorization,
  }) => _operation(_api.startMapViewPositioning(viewId, requestAuthorization));

  @override
  Future<void> stopMapViewPositioning(int viewId) =>
      _operation(_api.stopMapViewPositioning(viewId));

  @override
  Future<void> resetMapViewPedestrianRoute(int viewId) =>
      _operation(_api.resetMapViewPedestrianRoute(viewId));

  @override
  Future<void> selectMapViewFloor(int viewId, String? floorId) =>
      _operation(_api.selectMapViewFloor(viewId, floorId));

  @override
  Future<void> showMapViewSpot(int viewId, String spotId) =>
      _operation(_api.showMapViewSpot(viewId, spotId));

  @override
  Future<void> setMapViewDestination(int viewId, String? spotId) =>
      _operation(_api.setMapViewDestination(viewId, spotId));

  @override
  Future<void> setMapViewLanguage(int viewId, String language) =>
      _operation(_api.setMapViewLanguage(viewId, language));

  @override
  Future<void> setMapViewCenterReticleEnabled(int viewId, bool enabled) =>
      _operation(_api.setMapViewCenterReticleEnabled(viewId, enabled));

  @override
  Future<MapCenterReticleCandidate?> requestMapViewCenterReticleCandidate(
    int viewId,
  ) async {
    final result = await _api.requestMapViewCenterReticleCandidate(viewId);
    _throwIfError(result.error);
    final candidate = result.candidate;
    if (candidate == null) {
      return null;
    }
    return MapCenterReticleCandidate(
      floorId: candidate.floorId,
      longitude: candidate.longitude,
      latitude: candidate.latitude,
      local: candidate.local == null
          ? null
          : LocalPosition(
              x: candidate.local!.x,
              y: candidate.local!.y,
              z: candidate.local!.z,
            ),
    );
  }

  @override
  Future<void> openMapViewExternalLink(int viewId, String url) =>
      _operation(_api.openMapViewExternalLink(viewId, url));

  Future<void> _operation(Future<NativeOperationResult> future) async {
    final result = await future;
    _throwIfError(result.error);
  }
}

final class NativeEventRouter implements MetamapFlutterApi {
  NativeEventRouter._();

  static final NativeEventRouter instance = NativeEventRouter._();

  final Map<int, void Function(MetamapPositioningEvent)> _clientHandlers = {};
  final Map<int, void Function(MetamapMapViewEvent)> _mapViewHandlers = {};
  final Map<int, bool Function(Uri)> _externalLinkDecisionHandlers = {};
  StreamSubscription<NativeEvent>? _subscription;
  bool _flutterApiInstalled = false;

  void registerClient(
    int clientId,
    void Function(MetamapPositioningEvent) handler,
  ) {
    _ensureStarted();
    _clientHandlers[clientId] = handler;
  }

  void unregisterClient(int clientId) {
    _clientHandlers.remove(clientId);
  }

  void registerMapView(
    int viewId,
    void Function(MetamapMapViewEvent) handler, {
    required bool Function(Uri) externalLinkDecisionHandler,
  }) {
    _ensureStarted();
    _mapViewHandlers[viewId] = handler;
    _externalLinkDecisionHandlers[viewId] = externalLinkDecisionHandler;
  }

  void unregisterMapView(int viewId) {
    _mapViewHandlers.remove(viewId);
    _externalLinkDecisionHandlers.remove(viewId);
  }

  void _ensureStarted() {
    _subscription ??= events().listen(
      _handleNativeEvent,
      onError: _handleEventChannelError,
    );
    if (!_flutterApiInstalled) {
      _flutterApiInstalled = true;
      MetamapFlutterApi.setUp(this);
    }
  }

  @override
  Future<bool> decideMapViewExternalLinkOpensDefault(
    int viewId,
    String url,
  ) async {
    final handler = _externalLinkDecisionHandlers[viewId];
    return handler?.call(Uri.parse(url)) ?? false;
  }

  void _handleNativeEvent(NativeEvent event) {
    switch (event) {
      case NativePositioningEvent():
        final handler = _clientHandlers[event.clientId];
        if (handler != null) {
          handler(mapPositioningEvent(event));
        }
      case NativeMapViewEvent():
        final handler = _mapViewHandlers[event.viewId];
        if (handler != null) {
          handler(mapMapViewEvent(event));
        }
    }
  }

  void _handleEventChannelError(Object error, StackTrace stackTrace) {
    final mapped = PositioningErrorEvent(
      _invariant('Native event channel failed: $error'),
    );
    for (final handler in _clientHandlers.values.toList(growable: false)) {
      handler(mapped);
    }
    final mapError = MapViewErrorEvent(mapped.error);
    for (final handler in _mapViewHandlers.values.toList(growable: false)) {
      handler(mapError);
    }
  }

}

MetamapPositioningEvent mapPositioningEvent(NativePositioningEvent event) {
  return switch (event.type) {
    'position' when event.update != null => PositionEvent(
      mapPositioningUpdate(event.update!),
    ),
    'beaconSignals' when event.beaconSignals != null => BeaconSignalsEvent(
      event.beaconSignals!.map(mapBeaconSignalReading).toList(growable: false),
    ),
    'motionHeading' when event.motionHeading != null => MotionHeadingEvent(
      mapMotionHeadingReading(event.motionHeading!),
    ),
    'status' when event.status != null => PositioningStatusEvent(
      PositioningLifecycleStatus.fromWire(event.status!),
    ),
    'capabilities' when event.capabilities != null => CapabilitiesEvent(
      mapCapabilityReport(event.capabilities!),
    ),
    'error' when event.error != null => PositioningErrorEvent(
      mapNativeError(event.error!),
    ),
    _ => PositioningErrorEvent(
      _invariant('Malformed native positioning event: ${event.type}.'),
    ),
  };
}

MetamapMapViewEvent mapMapViewEvent(NativeMapViewEvent event) {
  return switch (event.type) {
    'ready' when event.ready != null => MapReady(
      MapReadyEvent(
        mapId: event.ready!.mapId,
        groupId: event.ready!.groupId,
        configRevision: event.ready!.configRevision,
        manifestRevision: event.ready!.manifestRevision,
      ),
    ),
    'loadState' when event.loadState != null => MapLoadStateChanged(
      MapViewLoadState.fromWire(event.loadState!),
    ),
    'positioningStatus' when event.positioningStatus != null =>
      MapPositioningStatusChanged(
        PositioningLifecycleStatus.fromWire(event.positioningStatus!),
      ),
    'position' when event.position != null => MapPositionChanged(
      mapPositioningUpdate(event.position!),
    ),
    'capabilities' when event.capabilities != null => MapCapabilitiesChanged(
      mapCapabilityReport(event.capabilities!),
    ),
    'motionHeading' when event.motionHeading != null => MapMotionHeadingChanged(
      mapMotionHeadingReading(event.motionHeading!),
    ),
    'beaconSignals' when event.beaconSignals != null => MapBeaconSignalsChanged(
      event.beaconSignals!.map(mapBeaconSignalReading).toList(growable: false),
    ),
    'positioningAuthorizationRequested' =>
      const MapPositioningAuthorizationRequested(),
    'positioningStartRequested' => const MapPositioningStartRequested(),
    'floorChanged' when event.floorChanged != null => MapFloorChanged(
      FloorChangedEvent(
        floorId: event.floorChanged!.floorId,
        source: event.floorChanged!.source,
      ),
    ),
    'spotSelected' when event.spotSelected != null => MapSpotSelected(
      SpotSelectedEvent(spotId: event.spotSelected!.spotId),
    ),
    'routeChanged' when event.routeChanged != null => MapRouteChanged(
      RouteChangedEvent(
        active: event.routeChanged!.active,
        destinationSpotId: event.routeChanged!.destinationSpotId,
      ),
    ),
    'externalLinkRequested' when event.externalUrl != null =>
      MapExternalLinkRequested(Uri.parse(event.externalUrl!)),
    'error' when event.error != null => MapViewErrorEvent(
      mapNativeError(event.error!),
    ),
    _ => MapViewErrorEvent(
      _invariant('Malformed native map event: ${event.type}.'),
    ),
  };
}

PositioningUpdate mapPositioningUpdate(NativePositioningUpdate update) {
  final estimate = update.estimate;
  return PositioningUpdate(
    mapId: update.mapId,
    groupId: update.groupId,
    estimate: PositionEstimate(
      sequence: estimate.sequence,
      monotonicTimestamp: Duration(milliseconds: estimate.monotonicTimestampMs),
      status: PositioningLifecycleStatus.fromWire(estimate.status),
      mode: PositioningMode.fromWire(estimate.mode),
      floorId: estimate.floorId,
      floorProbability: estimate.floorProbability,
      local: estimate.local == null
          ? null
          : LocalPosition(
              x: estimate.local!.x,
              y: estimate.local!.y,
              z: estimate.local!.z,
            ),
      accuracyRadiusM: estimate.accuracyRadiusM,
      headingDeg: estimate.headingDeg,
      headingAccuracyDeg: estimate.headingAccuracyDeg,
      speedMps: estimate.speedMps,
      freshBeaconCount: estimate.freshBeaconCount,
      lastBleAge: estimate.lastBleAgeMs == null
          ? null
          : Duration(milliseconds: estimate.lastBleAgeMs!),
      manifestRevision: estimate.manifestRevision,
      algorithmVersion: estimate.algorithmVersion,
      stale: estimate.stale,
      diagnosticFlags: List.unmodifiable(estimate.diagnosticFlags),
    ),
    wgs84: update.wgs84 == null
        ? null
        : Wgs84Position(
            longitude: update.wgs84!.longitude,
            latitude: update.wgs84!.latitude,
            elevationM: update.wgs84!.elevationM,
          ),
  );
}

MotionHeadingReading mapMotionHeadingReading(
  NativeMotionHeadingReading reading,
) {
  return MotionHeadingReading(
    localHeadingDeg: reading.localHeadingDeg,
    magneticFieldAccuracy: reading.magneticFieldAccuracy,
  );
}

BeaconSignalReading mapBeaconSignalReading(NativeBeaconSignalReading reading) {
  LocalPosition local(NativeLocalPosition value) =>
      LocalPosition(x: value.x, y: value.y, z: value.z);
  Wgs84Position wgs84(NativeWgs84Position value) => Wgs84Position(
    longitude: value.longitude,
    latitude: value.latitude,
    elevationM: value.elevationM,
  );

  return BeaconSignalReading(
    beaconId: reading.beaconId,
    uuid: reading.uuid,
    major: reading.major,
    minor: reading.minor,
    floorId: reading.floorId,
    rssiDbm: reading.rssiDbm,
    smoothedRssiDbm: reading.smoothedRssiDbm,
    radioDistanceM: reading.radioDistanceM,
    configuredPosition: local(reading.configuredPosition),
    configuredWgs84: wgs84(reading.configuredWgs84),
    devicePosition: reading.devicePosition == null
        ? null
        : local(reading.devicePosition!),
    deviceWgs84: reading.deviceWgs84 == null
        ? null
        : wgs84(reading.deviceWgs84!),
    configuredDistanceM: reading.configuredDistanceM,
    distanceDeltaM: reading.distanceDeltaM,
    monotonicTimestamp: Duration(milliseconds: reading.monotonicTimestampMs),
  );
}

CapabilityReport mapCapabilityReport(NativeCapabilityReport report) {
  return CapabilityReport(
    platform: report.platform,
    osVersion: report.osVersion,
    sdkVersion: report.sdkVersion,
    ble: BleCapabilities(
      supported: report.ble.supported,
      enabled: report.ble.enabled,
      rangingAvailable: report.ble.rangingAvailable,
      foregroundScan: report.ble.foregroundScan,
      backgroundScan: report.ble.backgroundScan,
      directionFinding: report.ble.directionFinding,
      channelSounding: report.ble.channelSounding,
    ),
    authorization: AuthorizationCapabilities(
      location: LocationAuthorizationState.fromWire(
        report.authorization.location,
      ),
      preciseLocation: report.authorization.preciseLocation,
      bluetoothScan: report.authorization.bluetoothScan,
      motion: MotionAuthorizationState.fromWire(report.authorization.motion),
    ),
    sensors: SensorCapabilities(
      stepDetector: report.sensors.stepDetector,
      rotationVector: report.sensors.rotationVector,
      gyroscope: report.sensors.gyroscope,
      magnetometer: report.sensors.magnetometer,
      barometer: report.sensors.barometer,
    ),
    selectedProfile: PositioningMode.fromWire(report.selectedProfile),
  );
}

MetamapPositioningError mapNativeError(NativeErrorMessage error) {
  return MetamapPositioningError(
    code: MetamapPositioningErrorCode.fromWire(error.code),
    message: error.message,
    recoverable: error.recoverable,
    userAction: PositioningUserAction.fromWire(error.userAction),
    debugDetail: error.debugDetail,
  );
}

void _throwIfError(NativeErrorMessage? error) {
  if (error != null) {
    throw mapNativeError(error);
  }
}

MetamapPositioningError _invariant(String detail) {
  return MetamapPositioningError(
    code: MetamapPositioningErrorCode.internalInvariantViolation,
    message: 'The positioning SDK entered an invalid state.',
    recoverable: false,
    userAction: PositioningUserAction.retry,
    debugDetail: detail,
  );
}
