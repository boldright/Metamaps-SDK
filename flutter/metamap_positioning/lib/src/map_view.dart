import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'models.dart';
import 'native_gateway.dart';

const _mapViewType = 'jp.metamaps/metamap_map_view';

/// Receives the controller after the native iOS or Android map view is created.
typedef MetamapMapViewCreatedCallback =
    void Function(MetamapMapViewController controller);

/// Decides whether an external link opens with the SDK's default handling or is handled by the host app.
typedef MetamapExternalLinkDecisionCallback =
    MetamapExternalLinkDecision Function(Uri url);

/// Controls the displayed map.
///
/// Unusable after the widget is disposed. Call the control methods after receiving [MapReady].
final class MetamapMapViewController {
  MetamapMapViewController._(this.viewId, this._nativeGateway);

  final int viewId;
  final NativeGateway _nativeGateway;
  final StreamController<MetamapMapViewEvent> _eventsController =
      StreamController<MetamapMapViewEvent>.broadcast(sync: true);
  bool _disposed = false;

  /// A broadcast stream of the events this map view sends.
  Stream<MetamapMapViewEvent> get events => _eventsController.stream;

  /// Loads the main frame. Normally called once automatically when the widget is created.
  Future<void> load() {
    _ensureActive();
    return _nativeGateway.loadMapView(viewId);
  }

  /// Reloads the main frame with the current configuration.
  Future<void> reload() {
    _ensureActive();
    return _nativeGateway.reloadMapView(viewId);
  }

  /// Requests the OS permissions positioning needs.
  Future<void> requestPositioningAuthorization() {
    _ensureActive();
    return _nativeGateway.requestMapViewAuthorization(viewId);
  }

  /// Starts positioning.
  Future<void> startPositioning({bool requestAuthorization = true}) {
    _ensureActive();
    return _nativeGateway.startMapViewPositioning(
      viewId,
      requestAuthorization: requestAuthorization,
    );
  }

  /// Stops positioning.
  Future<void> stopPositioning() {
    _ensureActive();
    return _nativeGateway.stopMapViewPositioning(viewId);
  }

  /// Resets PDR and the estimator state at the next known route start point.
  Future<void> resetPedestrianRoute() {
    _ensureActive();
    return _nativeGateway.resetMapViewPedestrianRoute(viewId);
  }

  /// Selects the displayed floor by UUID. `null` clears the selection.
  Future<void> selectFloor(String? floorId) {
    _ensureActive();
    return _nativeGateway.selectMapViewFloor(viewId, floorId);
  }

  /// Shows the spot with the given public spot stable key.
  Future<void> showSpot(String spotId) {
    _ensureActive();
    return _nativeGateway.showMapViewSpot(viewId, spotId);
  }

  /// Sets the spot with the given public spot stable key as the route destination. `null` clears the route.
  Future<void> setDestination(String? spotId) {
    _ensureActive();
    return _nativeGateway.setMapViewDestination(viewId, spotId);
  }

  /// Changes the web map's display language to the given BCP 47 language tag.
  Future<void> setLanguage(String language) {
    _ensureActive();
    return _nativeGateway.setMapViewLanguage(viewId, language);
  }

  /// Shows or hides the center reticle. Hidden by default.
  Future<void> setCenterReticleEnabled(bool enabled) {
    _ensureActive();
    return _nativeGateway.setMapViewCenterReticleEnabled(viewId, enabled);
  }

  /// Gets the candidate on the selected floor under the reticle at the time of the call, once.
  /// Returns `null` when the reticle is hidden, all floors are shown, or the reticle does not hit a floor.
  Future<MapCenterReticleCandidate?> requestCenterReticleCandidate() {
    _ensureActive();
    return _nativeGateway.requestMapViewCenterReticleCandidate(viewId);
  }

  /// Opens a URL with an allowed scheme using the SDK's default handling.
  Future<void> openExternalLink(Uri url) {
    _ensureActive();
    return _nativeGateway.openMapViewExternalLink(viewId, url.toString());
  }

  void _addEvent(MetamapMapViewEvent event) {
    if (!_disposed && !_eventsController.isClosed) {
      _eventsController.add(event);
    }
  }

  void _dispose() {
    if (_disposed) {
      return;
    }
    _disposed = true;
    NativeEventRouter.instance.unregisterMapView(viewId);
    unawaited(_eventsController.close());
  }

  void _ensureActive() {
    if (_disposed) {
      throw const MetamapPositioningError(
        code: MetamapPositioningErrorCode.internalInvariantViolation,
        message: 'MetamapMapViewController was used after disposal.',
        recoverable: false,
        userAction: PositioningUserAction.retry,
      );
    }
  }
}

/// A Flutter widget that shows the web map for `mapSlug` and the user's location while the app is in use.
///
/// [configuration] cannot change after creation. To change it, recreate the widget with a new
/// [Key].
class MetamapMapView extends StatefulWidget {
  /// Creates the map view. Only [MetamapMapViewConfiguration.mapSlug] is required.
  MetamapMapView({
    required this.configuration,
    super.key,
    this.onEvent,
    this.onViewCreated,
    this.onExternalLinkDecision,
    @visibleForTesting NativeGateway? nativeGateway,
  }) : _nativeGateway = nativeGateway ?? PigeonNativeGateway() {
    configuration.validate();
  }

  /// The configuration used when the map view is created.
  final MetamapMapViewConfiguration configuration;

  /// Receives the events this view sends.
  final ValueChanged<MetamapMapViewEvent>? onEvent;

  /// Receives the controller that controls the displayed map.
  final MetamapMapViewCreatedCallback? onViewCreated;

  /// Called to decide on an external link before the SDK's default handling opens it.
  final MetamapExternalLinkDecisionCallback? onExternalLinkDecision;
  final NativeGateway _nativeGateway;

  @override
  State<MetamapMapView> createState() => _MetamapMapViewState();
}

class _MetamapMapViewState extends State<MetamapMapView> {
  MetamapMapViewController? _controller;

  @override
  void didUpdateWidget(covariant MetamapMapView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_sameConfiguration(oldWidget.configuration, widget.configuration)) {
      _emitLocalError(
        'MetamapMapView configuration is immutable. Recreate the widget with '
        'a new Key when changing any configuration field.',
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final creationParams = _creationParams(widget.configuration);
    return switch (defaultTargetPlatform) {
      TargetPlatform.android => AndroidView(
        viewType: _mapViewType,
        creationParams: creationParams,
        creationParamsCodec: const StandardMessageCodec(),
        onPlatformViewCreated: _onPlatformViewCreated,
      ),
      TargetPlatform.iOS => UiKitView(
        viewType: _mapViewType,
        creationParams: creationParams,
        creationParamsCodec: const StandardMessageCodec(),
        onPlatformViewCreated: _onPlatformViewCreated,
      ),
      _ => ErrorWidget('MetamapMapView supports only Android and iOS.'),
    };
  }

  void _onPlatformViewCreated(int viewId) {
    if (!mounted) {
      return;
    }
    final controller = MetamapMapViewController._(
      viewId,
      widget._nativeGateway,
    );
    _controller = controller;
    NativeEventRouter.instance.registerMapView(
      viewId,
      _handleEvent,
      externalLinkDecisionHandler: (url) =>
          mapViewExternalLinkOpensDefault(widget.onExternalLinkDecision, url),
    );
    widget.onViewCreated?.call(controller);
    unawaited(
      controller.load().catchError((Object error, StackTrace stackTrace) {
        _emitLocalError('Native MapView load failed.', error);
      }),
    );
  }

  void _handleEvent(MetamapMapViewEvent event) {
    if (!mounted) {
      return;
    }
    _controller?._addEvent(event);
    widget.onEvent?.call(event);
  }

  void _emitLocalError(String detail, [Object? cause]) {
    final event = MapViewErrorEvent(
      cause is MetamapPositioningError
          ? cause
          : MetamapPositioningError(
              code: MetamapPositioningErrorCode.internalInvariantViolation,
              message: 'The Flutter MapView integration failed.',
              recoverable: false,
              userAction: PositioningUserAction.retry,
              debugDetail: cause == null ? detail : '$detail $cause',
            ),
    );
    _controller?._addEvent(event);
    widget.onEvent?.call(event);
  }

  @override
  void dispose() {
    _controller?._dispose();
    _controller = null;
    super.dispose();
  }
}

Map<String, Object?> _creationParams(
  MetamapMapViewConfiguration configuration,
) {
  return <String, Object?>{
    'baseUrl': configuration.baseUrl.toString(),
    'mapSlug': configuration.mapSlug,
    'groupId': configuration.groupId,
    'language': configuration.language,
    'initialFloorId': configuration.initialFloorId,
    'previewToken': configuration.previewToken,
    'positioningPolicy': configuration.positioningPolicy.wireValue,
    'positioningStartTrigger': configuration.positioningStartTrigger.wireValue,
    'showsBeaconDiagnostics': configuration.showsBeaconDiagnostics,
    'additionalQuery': configuration.additionalQuery,
    'retryPolicy': configuration.retryPolicy.wireValue,
    'userAgentAppendix': configuration.userAgentAppendix,
    'isWebViewInspectable': configuration.isWebViewInspectable,
  };
}

@visibleForTesting
Map<String, Object?> mapViewCreationParamsForTesting(
  MetamapMapViewConfiguration configuration,
) => _creationParams(configuration);

@visibleForTesting
bool mapViewExternalLinkOpensDefault(
  MetamapExternalLinkDecisionCallback? callback,
  Uri url,
) =>
    (callback?.call(url) ?? MetamapExternalLinkDecision.openDefault) ==
    MetamapExternalLinkDecision.openDefault;

bool _sameConfiguration(
  MetamapMapViewConfiguration left,
  MetamapMapViewConfiguration right,
) {
  return left.baseUrl == right.baseUrl &&
      left.mapSlug == right.mapSlug &&
      left.groupId == right.groupId &&
      left.language == right.language &&
      left.initialFloorId == right.initialFloorId &&
      left.previewToken == right.previewToken &&
      left.positioningPolicy == right.positioningPolicy &&
      left.positioningStartTrigger == right.positioningStartTrigger &&
      left.showsBeaconDiagnostics == right.showsBeaconDiagnostics &&
      mapEquals(left.additionalQuery, right.additionalQuery) &&
      left.retryPolicy == right.retryPolicy &&
      left.userAgentAppendix == right.userAgentAppendix &&
      left.isWebViewInspectable == right.isWebViewInspectable;
}
