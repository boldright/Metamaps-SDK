import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'models.dart';
import 'native_gateway.dart';

const _mapViewType = 'jp.metamaps/metamaps_map_view';

/// Receives the controller after the native iOS or Android map view is created.
typedef MetamapsMapViewCreatedCallback =
    void Function(MetamapsMapViewController controller);

/// Decides whether an external link opens with the SDK's default handling or is handled by the host app.
typedef MetamapsExternalLinkDecisionCallback =
    MetamapsExternalLinkDecision Function(Uri url);

/// Answers the map's permission request. Android only.
///
/// The map asks when it needs a permission that your app declares but has not been granted, such as when the user
/// taps the location button or the microphone. Return [MapPermissionAnswer.showDialog] to let the plugin show the OS
/// dialog, for example after your own explanation, [MapPermissionAnswer.requestedByApp] after requesting the
/// permission yourself, or [MapPermissionAnswer.decline] to leave it as it is. In every case, the map reads the
/// permission state afterwards, and a denial arrives as [MapViewErrorEvent].
typedef MetamapsPermissionRequestCallback =
    Future<MapPermissionAnswer> Function(MapPermissionRequest request);

/// Controls the displayed map.
///
/// Unusable after the widget is disposed: the control methods then throw a [MetamapsError] synchronously. Call
/// the control methods after receiving [MapReady].
final class MetamapsMapViewController {
  MetamapsMapViewController._(this.viewId, this._nativeGateway);

  /// The platform view ID of this map view. It is not a Metamaps map ID.
  final int viewId;
  final NativeGateway _nativeGateway;
  final StreamController<MetamapsMapViewEvent> _eventsController =
      StreamController<MetamapsMapViewEvent>.broadcast(sync: true);
  bool _disposed = false;
  bool _canGoBack = false;

  /// A broadcast stream of the events this map view sends.
  Stream<MetamapsMapViewEvent> get events => _eventsController.stream;

  /// Whether the web map has a step that going back closes, such as an open spot.
  ///
  /// Reading it does not rebuild your widget. Call `setState` when [MapCanGoBackChanged] arrives so that
  /// `PopScope` reads the new value. It becomes `false` when the widget is disposed.
  ///
  /// Changes arrive as [MapCanGoBackChanged]. Pass the inverse to `PopScope.canPop` and call [goBack] from
  /// `PopScope.onPopInvokedWithResult` when the pop did not happen. `false` until the map is ready, and again
  /// whenever the web view loads a new page.
  bool get canGoBack => _canGoBack;

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

  /// Requests the OS permissions indoor positioning needs. It shows OS permission dialogs, so call it from a user
  /// action. It never requests `RECORD_AUDIO` for voice input, and on Android it requests location only for maps
  /// with indoor positioning; request permissions for voice input and GPS location in the map yourself.
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

  /// Closes the web map's last step, as the browser's back button does on the web, such as an open spot.
  ///
  /// Completes with `false` and does nothing when [canGoBack] is `false`, including before [MapReady], so that the
  /// app can handle back itself. `true` means the command was sent; the change arrives as [MapCanGoBackChanged].
  Future<bool> goBack() {
    _ensureActive();
    return _nativeGateway.goBackMapView(viewId);
  }

  /// Opens a URL with an allowed scheme using the SDK's default handling.
  Future<void> openExternalLink(Uri url) {
    _ensureActive();
    return _nativeGateway.openMapViewExternalLink(viewId, url.toString());
  }

  void _addEvent(MetamapsMapViewEvent event) {
    if (event is MapCanGoBackChanged) {
      _canGoBack = event.canGoBack;
    }
    if (!_disposed && !_eventsController.isClosed) {
      _eventsController.add(event);
    }
  }

  void _dispose() {
    if (_disposed) {
      return;
    }
    _disposed = true;
    // The native view can no longer report `false`, and a PopScope that still reads `true` would swallow back.
    _canGoBack = false;
    NativeEventRouter.instance.unregisterMapView(viewId);
    unawaited(_eventsController.close());
  }

  void _ensureActive() {
    if (_disposed) {
      throw const MetamapsError(
        code: MetamapsErrorCode.internalInvariantViolation,
        message: 'MetamapsMapViewController was used after disposal.',
        recoverable: false,
        userAction: MetamapsUserAction.retry,
      );
    }
  }
}

/// A Flutter widget that shows the published Metamaps map for `mapSlug`.
///
/// For maps with indoor positioning, it can also show the user's location while the app is in use.
///
/// [configuration] cannot change after creation. To change it, recreate the widget with a new
/// [Key].
class MetamapsMapView extends StatefulWidget {
  /// Creates the map view. Only [MetamapsMapViewConfiguration.mapSlug] is required.
  MetamapsMapView({
    required this.configuration,
    super.key,
    this.onEvent,
    this.onViewCreated,
    this.onExternalLinkDecision,
    this.onPermissionRequest,
    @visibleForTesting NativeGateway? nativeGateway,
  }) : _nativeGateway = nativeGateway ?? PigeonNativeGateway() {
    configuration.validate();
  }

  /// The configuration used when the map view is created.
  final MetamapsMapViewConfiguration configuration;

  /// Receives the events this view sends.
  final ValueChanged<MetamapsMapViewEvent>? onEvent;

  /// Receives the controller that controls the displayed map.
  final MetamapsMapViewCreatedCallback? onViewCreated;

  /// Called to decide on an external link before the SDK's default handling opens it.
  final MetamapsExternalLinkDecisionCallback? onExternalLinkDecision;

  /// Called on Android when the map needs a permission that your app has not been granted. Without it, the map's
  /// request is denied at once.
  final MetamapsPermissionRequestCallback? onPermissionRequest;
  final NativeGateway _nativeGateway;

  @override
  State<MetamapsMapView> createState() => _MetamapsMapViewState();
}

class _MetamapsMapViewState extends State<MetamapsMapView> {
  MetamapsMapViewController? _controller;

  @override
  void didUpdateWidget(covariant MetamapsMapView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_sameConfiguration(oldWidget.configuration, widget.configuration)) {
      _emitLocalError(
        'MetamapsMapView configuration is immutable. Recreate the widget with '
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
      _ => ErrorWidget('MetamapsMapView supports only Android and iOS.'),
    };
  }

  void _onPlatformViewCreated(int viewId) {
    if (!mounted) {
      return;
    }
    final controller = MetamapsMapViewController._(
      viewId,
      widget._nativeGateway,
    );
    _controller = controller;
    NativeEventRouter.instance.registerMapView(
      viewId,
      _handleEvent,
      externalLinkDecisionHandler: (url) =>
          mapViewExternalLinkOpensDefault(widget.onExternalLinkDecision, url),
      permissionRequestHandler: (permission) =>
          mapViewPermissionAnswer(widget.onPermissionRequest, permission),
    );
    widget.onViewCreated?.call(controller);
    unawaited(
      controller.load().catchError((Object error, StackTrace stackTrace) {
        _emitLocalError('Native MapView load failed.', error);
      }),
    );
  }

  void _handleEvent(MetamapsMapViewEvent event) {
    if (!mounted) {
      return;
    }
    _controller?._addEvent(event);
    widget.onEvent?.call(event);
  }

  void _emitLocalError(String detail, [Object? cause]) {
    final event = MapViewErrorEvent(
      cause is MetamapsError
          ? cause
          : MetamapsError(
              code: MetamapsErrorCode.internalInvariantViolation,
              message: 'The Flutter MapView integration failed.',
              recoverable: false,
              userAction: MetamapsUserAction.retry,
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
  MetamapsMapViewConfiguration configuration,
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
  MetamapsMapViewConfiguration configuration,
) => _creationParams(configuration);

@visibleForTesting
bool mapViewExternalLinkOpensDefault(
  MetamapsExternalLinkDecisionCallback? callback,
  Uri url,
) =>
    (callback?.call(url) ?? MetamapsExternalLinkDecision.openDefault) ==
    MetamapsExternalLinkDecision.openDefault;

@visibleForTesting
Future<MapPermissionAnswer> mapViewPermissionAnswer(
  MetamapsPermissionRequestCallback? callback,
  MapPermission permission,
) async =>
    await callback?.call(MapPermissionRequest(permission)) ??
    MapPermissionAnswer.decline;

bool _sameConfiguration(
  MetamapsMapViewConfiguration left,
  MetamapsMapViewConfiguration right,
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
