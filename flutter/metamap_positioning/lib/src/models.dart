import 'package:flutter/foundation.dart';

/// The version of the running Flutter SDK.
const String metamapPositioningSdkVersion = '0.5.0';

/// The production origin for the map and the positioning manifest when no origin is specified.
final Uri metamapProductionBaseUrl = Uri(
  scheme: 'https',
  host: 'metamaps.jp',
);

/// Whether to use step-based pedestrian dead reckoning (PDR).
enum MotionPolicy {
  preferred('preferred'),
  disabled('disabled'),
  unknown('unknown');

  const MotionPolicy(this.wireValue);
  final String wireValue;

  static MotionPolicy fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

/// The current lifecycle state of the positioning client.
enum PositioningLifecycleStatus {
  unconfigured('unconfigured'),
  configured('configured'),
  authorized('authorized'),
  acquiring('acquiring'),
  tracking('tracking'),
  degraded('degraded'),
  coasting('coasting'),
  recovering('recovering'),
  lost('lost'),
  pausedBackground('paused_background'),
  stopped('stopped'),
  disposed('disposed'),
  unknown('unknown');

  const PositioningLifecycleStatus(this.wireValue);
  final String wireValue;

  static PositioningLifecycleStatus fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

/// The inputs and algorithm the estimator currently uses.
enum PositioningMode {
  blePdrPf('ble_pdr_pf'),
  blePf('ble_pf'),
  bleOnly('ble_only'),
  proximityOnly('proximity_only'),
  unavailable('unavailable'),
  unknown('unknown');

  const PositioningMode(this.wireValue);
  final String wireValue;

  static PositioningMode fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

/// The location permission state reported by the OS.
enum LocationAuthorizationState {
  notDetermined('notDetermined'),
  denied('denied'),
  restricted('restricted'),
  whenInUse('whenInUse'),
  always('always'),
  unknown('unknown');

  const LocationAuthorizationState(this.wireValue);
  final String wireValue;

  static LocationAuthorizationState fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

/// The motion permission state reported by the OS.
enum MotionAuthorizationState {
  notDetermined('notDetermined'),
  denied('denied'),
  granted('granted'),
  unavailable('unavailable'),
  unknown('unknown');

  const MotionAuthorizationState(this.wireValue);
  final String wireValue;

  static MotionAuthorizationState fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

/// The action the host app should offer the user to recover from an error.
enum PositioningUserAction {
  none('none'),
  checkConfiguration('checkConfiguration'),
  retry('retry'),
  requestPermission('requestPermission'),
  enableBluetooth('enableBluetooth'),
  enablePreciseLocation('enablePreciseLocation'),
  openAppSettings('openAppSettings'),
  selectLocationManually('selectLocationManually'),
  unknown('unknown');

  const PositioningUserAction(this.wireValue);
  final String wireValue;

  static PositioningUserAction fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

/// Stable identifiers for `MetamapPositioningError.code`.
enum MetamapPositioningErrorCode {
  configurationInvalid('configurationInvalid'),
  mapNotFound('mapNotFound'),
  mapAccessDenied('mapAccessDenied'),
  webContentLoadFailed('webContentLoadFailed'),
  mapOperationFailed('mapOperationFailed'),
  bridgeHandshakeFailed('bridgeHandshakeFailed'),
  bridgeUnsupported('bridgeUnsupported'),
  bridgeMapMismatch('bridgeMapMismatch'),
  manifestUnavailable('manifestUnavailable'),
  manifestExpired('manifestExpired'),
  manifestUnsupported('manifestUnsupported'),
  locationPermissionDenied('locationPermissionDenied'),
  preciseLocationRequired('preciseLocationRequired'),
  bluetoothPermissionDenied('bluetoothPermissionDenied'),
  bluetoothDisabled('bluetoothDisabled'),
  bleUnsupported('bleUnsupported'),
  motionPermissionDenied('motionPermissionDenied'),
  scanStartFailed('scanStartFailed'),
  scanRuntimeFailed('scanRuntimeFailed'),
  noRegisteredBeacons('noRegisteredBeacons'),
  insufficientSignals('insufficientSignals'),
  pausedBackground('pausedBackground'),
  positionLost('positionLost'),
  backgroundModeUnavailable('backgroundModeUnavailable'),
  backgroundStartNotAllowed('backgroundStartNotAllowed'),
  backgroundInterrupted('backgroundInterrupted'),
  internalInvariantViolation('internalInvariantViolation'),
  unknown('unknown');

  const MetamapPositioningErrorCode(this.wireValue);
  final String wireValue;

  static MetamapPositioningErrorCode fromWire(String value) => values
      .firstWhere((item) => item.wireValue == value, orElse: () => unknown);
}

/// Who handles a positioning start request from the web map.
enum MapViewPositioningPolicy {
  userInitiated('userInitiated'),
  hostControlled('hostControlled'),
  disabled('disabled'),
  unknown('unknown');

  const MapViewPositioningPolicy(this.wireValue);
  final String wireValue;

  static MapViewPositioningPolicy fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

/// When positioning starts.
///
/// [MapViewPositioningPolicy] decides who handles a start request; this value only decides when a start
/// request happens.
enum MapViewPositioningStartTrigger {
  /// Starts only when the user taps the location button in the web UI.
  userAction('userAction'),

  /// Starts once the map is ready, requesting any required OS permission that has not been decided yet.
  automatic('automatic'),

  /// Starts at the same moment, but only when no additional OS dialog is needed. If a permission has not
  /// been decided, positioning does not start; a denied optional motion permission does not prevent a
  /// BLE-only start.
  automaticWhenAuthorized('automaticWhenAuthorized'),
  unknown('unknown');

  const MapViewPositioningStartTrigger(this.wireValue);
  final String wireValue;

  static MapViewPositioningStartTrigger fromWire(String value) =>
      values.firstWhere((item) => item.wireValue == value, orElse: () => unknown);
}

/// The automatic retry policy for web map load failures.
enum MetamapMapViewRetryPolicy {
  automatic('automatic'),
  disabled('disabled'),
  unknown('unknown');

  const MetamapMapViewRetryPolicy(this.wireValue);

  final String wireValue;

  static MetamapMapViewRetryPolicy fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

/// How to handle an external link requested by the web map.
enum MetamapExternalLinkDecision { openDefault, handled }

/// The load state of the embedded WebView's main frame.
enum MapViewLoadState {
  idle('idle'),
  loading('loading'),
  loaded('loaded'),
  failed('failed'),
  disposed('disposed'),
  unknown('unknown');

  const MapViewLoadState(this.wireValue);
  final String wireValue;

  static MapViewLoadState fromWire(String value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => unknown,
  );
}

@immutable
/// Configuration for the headless positioning client.
class PositioningConfiguration {
  PositioningConfiguration({
    required this.mapSlug,
    Uri? baseUrl,
    this.groupId,
    this.maxOfflineAge = const Duration(days: 7),
    this.motionPolicy = MotionPolicy.preferred,
    this.beaconDiagnosticsEnabled = false,
  }) : baseUrl = baseUrl ?? metamapProductionBaseUrl;

  /// The origin for the map and the manifest. Normally left unchanged.
  final Uri baseUrl;

  /// The public map slug configured in the Metamaps console.
  final String mapSlug;

  /// A UUID that pins a specific map group. When omitted, the active group is used.
  final String? groupId;

  /// The longest period a validated manifest cache can be used while offline.
  final Duration maxOfflineAge;

  /// The motion input policy for PDR.
  final MotionPolicy motionPolicy;

  /// Adds on-device diagnostics about registered beacons and the estimated position to the events.
  final bool beaconDiagnosticsEnabled;

  /// Validates the values and throws [MetamapPositioningError] if any is invalid.
  void validate() {
    _validateBaseUrl(baseUrl);
    if (!RegExp(r'^[A-Za-z0-9_-]{1,100}$').hasMatch(mapSlug)) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.configurationInvalid,
        message: 'mapSlug contains unsupported characters.',
        recoverable: false,
        userAction: PositioningUserAction.checkConfiguration,
      );
    }
    const maxAge = Duration(days: 30);
    if (maxOfflineAge.isNegative || maxOfflineAge > maxAge) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.configurationInvalid,
        message: 'maxOfflineAge must be between 0 and 30 days.',
        recoverable: false,
        userAction: PositioningUserAction.checkConfiguration,
      );
    }
    if (motionPolicy == MotionPolicy.unknown) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.configurationInvalid,
        message: 'Unknown motionPolicy cannot be used as input.',
        recoverable: false,
        userAction: PositioningUserAction.checkConfiguration,
      );
    }
    _validateOptionalUuid(groupId, 'groupId');
  }
}

@immutable
/// Immutable configuration used to create a [MetamapMapView].
class MetamapMapViewConfiguration {
  MetamapMapViewConfiguration({
    required this.mapSlug,
    Uri? baseUrl,
    this.groupId,
    this.language,
    this.initialFloorId,
    this.previewToken,
    this.positioningPolicy = MapViewPositioningPolicy.userInitiated,
    this.positioningStartTrigger =
        MapViewPositioningStartTrigger.automaticWhenAuthorized,
    this.showsBeaconDiagnostics = false,
    this.additionalQuery = const <String, String>{},
    this.retryPolicy = MetamapMapViewRetryPolicy.automatic,
    this.userAgentAppendix,
    this.isWebViewInspectable = false,
  }) : baseUrl = baseUrl ?? metamapProductionBaseUrl;

  /// The origin for the web map and the manifest. Normally left unchanged.
  final Uri baseUrl;

  /// The public map slug configured in the Metamaps console.
  final String mapSlug;

  /// A UUID that pins a specific map group.
  final String? groupId;

  /// The initial display language as a BCP 47 language tag.
  final String? language;

  /// The UUID of the floor shown first.
  final String? initialFloorId;

  /// A short-lived token that previews an unpublished map.
  final String? previewToken;

  /// The policy for positioning requests from the web UI.
  final MapViewPositioningPolicy positioningPolicy;

  /// When positioning starts. By default, it starts automatically only on devices that need no new OS dialog.
  final MapViewPositioningStartTrigger positioningStartTrigger;

  /// Enables the registered beacon diagnostics display and events.
  final bool showsBeaconDiagnostics;

  /// Public query parameters added to the initial map view.
  final Map<String, String> additionalQuery;

  /// The load retry policy after a network failure.
  final MetamapMapViewRetryPolicy retryPolicy;

  /// An app identifier appended after `Metamap/<version>`.
  final String? userAgentAppendix;

  /// Allows Web Inspector or DevTools. Use only in development builds.
  final bool isWebViewInspectable;

  /// Validates the values and throws [MetamapPositioningError] if any is invalid.
  void validate() {
    PositioningConfiguration(baseUrl: baseUrl, mapSlug: mapSlug).validate();
    _validateOptionalUuid(groupId, 'groupId');
    _validateOptionalUuid(initialFloorId, 'initialFloorId');
    final language = this.language;
    if (language != null &&
        !RegExp(r'^[A-Za-z0-9_-]{1,35}$').hasMatch(language)) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.configurationInvalid,
        message: 'language must be a BCP 47-style language tag.',
        recoverable: false,
        userAction: PositioningUserAction.checkConfiguration,
      );
    }
    if (positioningPolicy == MapViewPositioningPolicy.unknown) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.configurationInvalid,
        message: 'Unknown positioningPolicy cannot be used as input.',
        recoverable: false,
        userAction: PositioningUserAction.checkConfiguration,
      );
    }
    if (positioningStartTrigger == MapViewPositioningStartTrigger.unknown) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.configurationInvalid,
        message: 'Unknown positioningStartTrigger cannot be used as input.',
        recoverable: false,
        userAction: PositioningUserAction.checkConfiguration,
      );
    }
    if (retryPolicy == MetamapMapViewRetryPolicy.unknown) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.configurationInvalid,
        message: 'Unknown retryPolicy cannot be used as input.',
        recoverable: false,
        userAction: PositioningUserAction.checkConfiguration,
      );
    }
    final previewToken = this.previewToken;
    if (previewToken != null &&
        !RegExp(r'^[A-Za-z0-9._-]{1,2048}$').hasMatch(previewToken)) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.configurationInvalid,
        message: 'previewToken contains unsupported characters.',
        recoverable: false,
        userAction: PositioningUserAction.checkConfiguration,
      );
    }
  }
}

void _validateOptionalUuid(String? value, String name) {
  if (value == null) {
    return;
  }
  if (!RegExp(
    r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-'
    r'[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
  ).hasMatch(value)) {
    throw MetamapPositioningError(
      code: MetamapPositioningErrorCode.configurationInvalid,
      message: '$name must be a UUID.',
      recoverable: false,
      userAction: PositioningUserAction.checkConfiguration,
    );
  }
}

void _validateBaseUrl(Uri value) {
  final localHttp =
      value.scheme == 'http' &&
      const {
        'localhost',
        '127.0.0.1',
        '::1',
      }.contains(value.host.toLowerCase());
  final invalid =
      (!localHttp && value.scheme != 'https') ||
      !value.hasAuthority ||
      value.host.isEmpty ||
      value.userInfo.isNotEmpty ||
      value.hasQuery ||
      value.hasFragment;
  if (invalid) {
    throw const MetamapPositioningError(
      code: MetamapPositioningErrorCode.configurationInvalid,
      message:
          'baseUrl must be HTTPS without credentials, query, or fragment. '
          'HTTP is allowed only for localhost.',
      recoverable: false,
      userAction: PositioningUserAction.checkConfiguration,
    );
  }
}

@immutable
/// Availability of BLE scanning and the related radio features.
class BleCapabilities {
  const BleCapabilities({
    required this.supported,
    required this.enabled,
    required this.rangingAvailable,
    required this.foregroundScan,
    required this.backgroundScan,
    required this.directionFinding,
    required this.channelSounding,
  });

  final bool supported;
  final bool enabled;
  final bool rangingAvailable;
  final bool foregroundScan;
  final String backgroundScan;
  final String directionFinding;
  final String channelSounding;
}

@immutable
/// The state of the OS permissions positioning needs.
class AuthorizationCapabilities {
  const AuthorizationCapabilities({
    required this.location,
    required this.preciseLocation,
    required this.bluetoothScan,
    required this.motion,
  });

  final LocationAuthorizationState location;
  final bool preciseLocation;
  final String bluetoothScan;
  final MotionAuthorizationState motion;
}

@immutable
/// Availability of the device sensors used for PDR and diagnostics.
class SensorCapabilities {
  const SensorCapabilities({
    required this.stepDetector,
    required this.rotationVector,
    required this.gyroscope,
    required this.magnetometer,
    required this.barometer,
  });

  final bool stepDetector;
  final bool rotationVector;
  final bool gyroscope;
  final bool magnetometer;
  final bool barometer;
}

@immutable
/// The combined state of the OS, permissions, device sensors, and the selected positioning profile.
class CapabilityReport {
  const CapabilityReport({
    required this.platform,
    required this.osVersion,
    required this.sdkVersion,
    required this.ble,
    required this.authorization,
    required this.sensors,
    required this.selectedProfile,
  });

  final String platform;
  final String osVersion;
  final String sdkVersion;
  final BleCapabilities ble;
  final AuthorizationCapabilities authorization;
  final SensorCapabilities sensors;
  final PositioningMode selectedProfile;
}

@immutable
/// X, Y, and Z coordinates in meters in the Metamaps facility coordinate system.
class LocalPosition {
  const LocalPosition({required this.x, required this.y, required this.z});
  final double x;
  final double y;
  final double z;
}

@immutable
/// WGS 84 longitude and latitude, with altitude in meters.
class Wgs84Position {
  const Wgs84Position({
    required this.longitude,
    required this.latitude,
    required this.elevationM,
  });
  final double longitude;
  final double latitude;
  final double elevationM;
}

@immutable
/// The candidate coordinate on the selected floor directly under the center reticle.
class MapCenterReticleCandidate {
  const MapCenterReticleCandidate({
    required this.floorId,
    required this.longitude,
    required this.latitude,
    this.local,
  });

  final String floorId;
  final double longitude;
  final double latitude;
  /// Manifest-local coordinates, when the validated positioning manifest contains the same floor.
  final LocalPosition? local;
}

@immutable
/// The device-local heading from the device sensors.
class MotionHeadingReading {
  const MotionHeadingReading({
    this.localHeadingDeg,
    this.magneticFieldAccuracy,
  });

  final double? localHeadingDeg;
  final String? magneticFieldAccuracy;
}

@immutable
/// On-device diagnostics about registered beacons and the estimated device position.
class BeaconSignalReading {
  const BeaconSignalReading({
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
    required this.monotonicTimestamp,
    this.devicePosition,
    this.deviceWgs84,
    this.configuredDistanceM,
    this.distanceDeltaM,
  });

  final String beaconId;
  final String uuid;
  final int major;
  final int minor;
  final String floorId;
  final double rssiDbm;
  final double smoothedRssiDbm;
  final double radioDistanceM;
  final LocalPosition configuredPosition;
  final Wgs84Position configuredWgs84;
  final LocalPosition? devicePosition;
  final Wgs84Position? deviceWgs84;
  final double? configuredDistanceM;
  final double? distanceDeltaM;
  final Duration monotonicTimestamp;
}

@immutable
/// One position estimate and its quality.
class PositionEstimate {
  const PositionEstimate({
    required this.sequence,
    required this.monotonicTimestamp,
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
    this.lastBleAge,
  });

  final int sequence;
  final Duration monotonicTimestamp;
  final PositioningLifecycleStatus status;
  final PositioningMode mode;
  final String? floorId;
  final double floorProbability;
  final LocalPosition? local;
  final double accuracyRadiusM;
  final double? headingDeg;
  final double? headingAccuracyDeg;
  final double speedMps;
  final int freshBeaconCount;
  final Duration? lastBleAge;
  final int manifestRevision;
  final String algorithmVersion;
  final bool stale;
  final List<String> diagnosticFlags;
}

@immutable
/// An update that combines the map identifier, the estimate, and an optional WGS 84 position.
class PositioningUpdate {
  const PositioningUpdate({
    required this.mapId,
    required this.groupId,
    required this.estimate,
    this.wgs84,
  });

  final String mapId;
  final String groupId;
  final PositionEstimate estimate;
  final Wgs84Position? wgs84;
}

@immutable
/// An SDK exception with a stable code, a recoverability flag, and a recommended action.
class MetamapPositioningError implements Exception {
  const MetamapPositioningError({
    required this.code,
    required this.message,
    required this.recoverable,
    required this.userAction,
    this.debugDetail,
  });

  final MetamapPositioningErrorCode code;
  final String message;
  final bool recoverable;
  final PositioningUserAction userAction;
  final String? debugDetail;

  @override
  String toString() => 'MetamapPositioningError(${code.wireValue}): $message';
}

/// The base type of events from the positioning client without a map view.
sealed class MetamapPositioningEvent {
  const MetamapPositioningEvent();
}

/// Reports a new position.
final class PositionEvent extends MetamapPositioningEvent {
  const PositionEvent(this.update);
  final PositioningUpdate update;
}

/// Reports beacon diagnostics, when enabled.
final class BeaconSignalsEvent extends MetamapPositioningEvent {
  const BeaconSignalsEvent(this.readings);
  final List<BeaconSignalReading> readings;
}

/// Reports an update of the device-local heading.
final class MotionHeadingEvent extends MetamapPositioningEvent {
  const MotionHeadingEvent(this.reading);
  final MotionHeadingReading reading;
}

/// Reports a change in the positioning lifecycle state.
final class PositioningStatusEvent extends MetamapPositioningEvent {
  const PositioningStatusEvent(this.status);
  final PositioningLifecycleStatus status;
}

/// Reports the availability of OS permissions and device sensors.
final class CapabilitiesEvent extends MetamapPositioningEvent {
  const CapabilitiesEvent(this.report);
  final CapabilityReport report;
}

/// Reports an error from positioning.
final class PositioningErrorEvent extends MetamapPositioningEvent {
  const PositioningErrorEvent(this.error);
  final MetamapPositioningError error;
}

@immutable
/// Information sent when the web map starts accepting map view commands.
class MapReadyEvent {
  const MapReadyEvent({
    required this.mapId,
    required this.groupId,
    this.configRevision,
    this.manifestRevision,
  });

  final String mapId;
  final String groupId;
  final String? configRevision;
  final int? manifestRevision;
}

@immutable
/// A change of the floor selected in the web map.
class FloorChangedEvent {
  const FloorChangedEvent({this.floorId, this.source});
  final String? floorId;
  final String? source;
}

@immutable
/// The stable key of the public spot selected in the web map.
class SpotSelectedEvent {
  const SpotSelectedEvent({this.spotId});
  final String? spotId;
}

@immutable
/// The route state of the web map and the stable key of the optional destination spot.
class RouteChangedEvent {
  const RouteChangedEvent({required this.active, this.destinationSpotId});
  final String? destinationSpotId;
  final bool active;
}

/// The base type of events from [MetamapMapView].
sealed class MetamapMapViewEvent {
  const MetamapMapViewEvent();
}

/// Reports that the web map accepts map view commands.
final class MapReady extends MetamapMapViewEvent {
  const MapReady(this.event);
  final MapReadyEvent event;
}

/// Reports a change in the main-frame load state.
final class MapLoadStateChanged extends MetamapMapViewEvent {
  const MapLoadStateChanged(this.state);
  final MapViewLoadState state;
}

/// Reports the lifecycle state of the map view's built-in positioning.
final class MapPositioningStatusChanged extends MetamapMapViewEvent {
  const MapPositioningStatusChanged(this.status);
  final PositioningLifecycleStatus status;
}

/// Reports a new position from the map view's built-in positioning.
final class MapPositionChanged extends MetamapMapViewEvent {
  const MapPositionChanged(this.update);
  final PositioningUpdate update;
}

/// Reports the OS permissions and device sensors available to the map view's built-in positioning.
final class MapCapabilitiesChanged extends MetamapMapViewEvent {
  const MapCapabilitiesChanged(this.report);
  final CapabilityReport report;
}

/// Reports the device-local heading from the map view's built-in positioning.
final class MapMotionHeadingChanged extends MetamapMapViewEvent {
  const MapMotionHeadingChanged(this.reading);
  final MotionHeadingReading reading;
}

/// Reports beacon diagnostics, when enabled.
final class MapBeaconSignalsChanged extends MetamapMapViewEvent {
  const MapBeaconSignalsChanged(this.readings);
  final List<BeaconSignalReading> readings;
}

/// Reports that the web UI handed a positioning permission request to the host app.
final class MapPositioningAuthorizationRequested extends MetamapMapViewEvent {
  const MapPositioningAuthorizationRequested();
}

/// Reports that the web UI handed a positioning start request to the host app.
final class MapPositioningStartRequested extends MetamapMapViewEvent {
  const MapPositioningStartRequested();
}

/// Reports a change of the floor selected in the web map.
final class MapFloorChanged extends MetamapMapViewEvent {
  const MapFloorChanged(this.event);
  final FloorChangedEvent event;
}

/// Reports a spot selection in the web map.
final class MapSpotSelected extends MetamapMapViewEvent {
  const MapSpotSelected(this.event);
  final SpotSelectedEvent event;
}

/// Reports a change in the web map's route state.
final class MapRouteChanged extends MetamapMapViewEvent {
  const MapRouteChanged(this.event);
  final RouteChangedEvent event;
}

/// Reports an external link request that passed the allowlist.
final class MapExternalLinkRequested extends MetamapMapViewEvent {
  const MapExternalLinkRequested(this.uri);
  final Uri uri;
}

/// Reports an error from map view loading, web map integration, or positioning.
final class MapViewErrorEvent extends MetamapMapViewEvent {
  const MapViewErrorEvent(this.error);
  final MetamapPositioningError error;
}
