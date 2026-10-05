import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/src/messages.g.dart',
    dartOptions: DartOptions(),
    kotlinOut: 'android/src/main/kotlin/jp/metamaps/flutter/Messages.g.kt',
    kotlinOptions: KotlinOptions(
      package: 'jp.metamaps.flutter',
      includeErrorClass: true,
    ),
    swiftOut: 'ios/metamaps_flutter/Sources/metamaps_flutter/Messages.g.swift',
    swiftOptions: SwiftOptions(includeErrorClass: true),
    dartPackageName: 'metamaps_flutter',
  ),
)
class NativePositioningConfiguration {
  NativePositioningConfiguration({
    required this.baseUrl,
    required this.mapSlug,
    required this.maxOfflineAgeMs,
    required this.motionPolicy,
    required this.beaconDiagnosticsEnabled,
    this.groupId,
  });

  String baseUrl;
  String mapSlug;
  String? groupId;
  int maxOfflineAgeMs;
  String motionPolicy;
  bool beaconDiagnosticsEnabled;
}

class NativeOperationResult {
  NativeOperationResult({this.error});

  NativeErrorMessage? error;
}

class NativeClientCreateResult {
  NativeClientCreateResult({this.clientId, this.error});

  int? clientId;
  NativeErrorMessage? error;
}

class NativeCapabilityResult {
  NativeCapabilityResult({this.report, this.error});

  NativeCapabilityReport? report;
  NativeErrorMessage? error;
}

class NativeMapCenterReticleCandidateResult {
  NativeMapCenterReticleCandidateResult({this.candidate, this.error});

  NativeMapCenterReticleCandidate? candidate;
  NativeErrorMessage? error;
}

class NativeMapCenterReticleCandidate {
  NativeMapCenterReticleCandidate({
    required this.floorId,
    required this.longitude,
    required this.latitude,
    this.local,
  });

  String floorId;
  double longitude;
  double latitude;
  NativeLocalPosition? local;
}

class NativeCapabilityReport {
  NativeCapabilityReport({
    required this.platform,
    required this.osVersion,
    required this.sdkVersion,
    required this.ble,
    required this.authorization,
    required this.sensors,
    required this.selectedProfile,
  });

  String platform;
  String osVersion;
  String sdkVersion;
  NativeBleCapabilities ble;
  NativeAuthorizationCapabilities authorization;
  NativeSensorCapabilities sensors;
  String selectedProfile;
}

class NativeBleCapabilities {
  NativeBleCapabilities({
    required this.supported,
    required this.enabled,
    required this.rangingAvailable,
    required this.foregroundScan,
    required this.backgroundScan,
    required this.directionFinding,
    required this.channelSounding,
  });

  bool supported;
  bool enabled;
  bool rangingAvailable;
  bool foregroundScan;
  String backgroundScan;
  String directionFinding;
  String channelSounding;
}

class NativeAuthorizationCapabilities {
  NativeAuthorizationCapabilities({
    required this.location,
    required this.preciseLocation,
    required this.bluetoothScan,
    required this.motion,
  });

  String location;
  bool preciseLocation;
  String bluetoothScan;
  String motion;
}

class NativeSensorCapabilities {
  NativeSensorCapabilities({
    required this.stepDetector,
    required this.rotationVector,
    required this.gyroscope,
    required this.magnetometer,
    required this.barometer,
  });

  bool stepDetector;
  bool rotationVector;
  bool gyroscope;
  bool magnetometer;
  bool barometer;
}

class NativeLocalPosition {
  NativeLocalPosition({required this.x, required this.y, required this.z});

  double x;
  double y;
  double z;
}

class NativeWgs84Position {
  NativeWgs84Position({
    required this.longitude,
    required this.latitude,
    required this.elevationM,
  });

  double longitude;
  double latitude;
  double elevationM;
}

class NativeMotionHeadingReading {
  NativeMotionHeadingReading({
    this.localHeadingDeg,
    this.magneticFieldAccuracy,
  });

  double? localHeadingDeg;
  String? magneticFieldAccuracy;
}

class NativeBeaconSignalReading {
  NativeBeaconSignalReading({
    required this.beaconId,
    required this.uuid,
    required this.major,
    required this.minor,
    required this.floorId,
    required this.rssiDbm,
    required this.smoothedRssiDbm,
    required this.radioDistanceM,
    required this.configuredPosition,
    required this.configuredWgs84,
    required this.monotonicTimestampMs,
    this.devicePosition,
    this.deviceWgs84,
    this.configuredDistanceM,
    this.distanceDeltaM,
  });

  String beaconId;
  String uuid;
  int major;
  int minor;
  String floorId;
  double rssiDbm;
  double smoothedRssiDbm;
  double radioDistanceM;
  NativeLocalPosition configuredPosition;
  NativeWgs84Position configuredWgs84;
  NativeLocalPosition? devicePosition;
  NativeWgs84Position? deviceWgs84;
  double? configuredDistanceM;
  double? distanceDeltaM;
  int monotonicTimestampMs;
}

class NativePositionEstimate {
  NativePositionEstimate({
    required this.sequence,
    required this.monotonicTimestampMs,
    required this.status,
    required this.mode,
    required this.floorProbability,
    required this.accuracyRadiusM,
    required this.speedMps,
    required this.freshBeaconCount,
    required this.manifestRevision,
    required this.algorithmVersion,
    required this.stale,
    required this.diagnosticFlags,
    this.floorId,
    this.local,
    this.headingDeg,
    this.headingAccuracyDeg,
    this.lastBleAgeMs,
  });

  int sequence;
  int monotonicTimestampMs;
  String status;
  String mode;
  String? floorId;
  double floorProbability;
  NativeLocalPosition? local;
  double accuracyRadiusM;
  double? headingDeg;
  double? headingAccuracyDeg;
  double speedMps;
  int freshBeaconCount;
  int? lastBleAgeMs;
  int manifestRevision;
  String algorithmVersion;
  bool stale;
  List<String> diagnosticFlags;
}

class NativePositioningUpdate {
  NativePositioningUpdate({
    required this.mapId,
    required this.groupId,
    required this.estimate,
    this.wgs84,
  });

  String mapId;
  String groupId;
  NativePositionEstimate estimate;
  NativeWgs84Position? wgs84;
}

class NativeErrorMessage {
  NativeErrorMessage({
    required this.code,
    required this.message,
    required this.recoverable,
    required this.userAction,
    this.debugDetail,
  });

  String code;
  String message;
  bool recoverable;
  String userAction;
  String? debugDetail;
}

class NativeMapReadyEvent {
  NativeMapReadyEvent({
    required this.mapId,
    required this.groupId,
    this.configRevision,
    this.manifestRevision,
  });

  String mapId;
  String groupId;
  String? configRevision;
  int? manifestRevision;
}

class NativeFloorChangedEvent {
  NativeFloorChangedEvent({this.floorId, this.source});

  String? floorId;
  String? source;
}

class NativeSpotSelectedEvent {
  NativeSpotSelectedEvent({this.spotId});

  String? spotId;
}

class NativeRouteChangedEvent {
  NativeRouteChangedEvent({required this.active, this.destinationSpotId});

  String? destinationSpotId;
  bool active;
}

sealed class NativeEvent {}

class NativePositioningEvent extends NativeEvent {
  NativePositioningEvent({
    required this.clientId,
    required this.type,
    this.update,
    this.status,
    this.capabilities,
    this.motionHeading,
    this.beaconSignals,
    this.error,
  });

  int clientId;
  String type;
  NativePositioningUpdate? update;
  String? status;
  NativeCapabilityReport? capabilities;
  NativeMotionHeadingReading? motionHeading;
  List<NativeBeaconSignalReading>? beaconSignals;
  NativeErrorMessage? error;
}

class NativeMapViewEvent extends NativeEvent {
  NativeMapViewEvent({
    required this.viewId,
    required this.type,
    this.ready,
    this.loadState,
    this.positioningStatus,
    this.position,
    this.capabilities,
    this.motionHeading,
    this.beaconSignals,
    this.floorChanged,
    this.spotSelected,
    this.routeChanged,
    this.externalUrl,
    this.error,
  });

  int viewId;
  String type;
  NativeMapReadyEvent? ready;
  String? loadState;
  String? positioningStatus;
  NativePositioningUpdate? position;
  NativeCapabilityReport? capabilities;
  NativeMotionHeadingReading? motionHeading;
  List<NativeBeaconSignalReading>? beaconSignals;
  NativeFloorChangedEvent? floorChanged;
  NativeSpotSelectedEvent? spotSelected;
  NativeRouteChangedEvent? routeChanged;
  String? externalUrl;
  NativeErrorMessage? error;
}

@HostApi()
abstract class MetamapsHostApi {
  @async
  NativeClientCreateResult createClient(
    NativePositioningConfiguration configuration,
  );

  @async
  NativeOperationResult configureClient(int clientId);

  @async
  NativeCapabilityResult clientCapabilities(int clientId);

  @async
  NativeOperationResult requestClientAuthorization(int clientId);

  @async
  NativeOperationResult startClient(int clientId);

  @async
  NativeOperationResult stopClient(int clientId);

  @async
  NativeOperationResult resetClient(int clientId);

  @async
  NativeOperationResult resetClientPedestrianRoute(int clientId);

  @async
  NativeOperationResult disposeClient(int clientId);

  @async
  NativeOperationResult loadMapView(int viewId);

  @async
  NativeOperationResult reloadMapView(int viewId);

  @async
  NativeOperationResult requestMapViewAuthorization(int viewId);

  @async
  NativeOperationResult startMapViewPositioning(
    int viewId,
    bool requestAuthorization,
  );

  @async
  NativeOperationResult stopMapViewPositioning(int viewId);

  @async
  NativeOperationResult resetMapViewPedestrianRoute(int viewId);

  @async
  NativeOperationResult selectMapViewFloor(int viewId, String? floorId);

  @async
  NativeOperationResult showMapViewSpot(int viewId, String spotId);

  @async
  NativeOperationResult setMapViewDestination(int viewId, String? spotId);

  @async
  NativeOperationResult setMapViewLanguage(int viewId, String language);

  @async
  NativeOperationResult setMapViewCenterReticleEnabled(
    int viewId,
    bool enabled,
  );

  @async
  NativeMapCenterReticleCandidateResult requestMapViewCenterReticleCandidate(
    int viewId,
  );

  @async
  NativeOperationResult openMapViewExternalLink(int viewId, String url);
}

@FlutterApi()
abstract class MetamapsFlutterApi {
  @async
  bool decideMapViewExternalLinkOpensDefault(int viewId, String url);
}

@EventChannelApi()
abstract class MetamapsEventChannel {
  NativeEvent events();
}
