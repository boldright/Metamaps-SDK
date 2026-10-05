import 'dart:async';

import 'models.dart';
import 'native_gateway.dart';

/// A headless client that controls the native SDK's foreground BLE and PDR positioning.
///
/// Calls are serialized in call order. Using the client after [dispose] fails with
/// [MetamapsErrorCode.internalInvariantViolation].
final class MetamapsPositioningClient {
  /// Creates a headless client with a validated [configuration].
  MetamapsPositioningClient(this.configuration, {NativeGateway? nativeGateway})
    : _nativeGateway = nativeGateway ?? PigeonNativeGateway() {
    configuration.validate();
  }

  final PositioningConfiguration configuration;
  final NativeGateway _nativeGateway;
  final StreamController<MetamapsPositioningEvent> _eventsController =
      StreamController<MetamapsPositioningEvent>.broadcast(sync: true);

  Future<int>? _clientCreation;
  Future<void> _operationQueue = Future<void>.value();
  int? _clientId;
  bool _disposed = false;

  PositioningLifecycleStatus _status = PositioningLifecycleStatus.unconfigured;
  PositioningUpdate? _latestUpdate;
  CapabilityReport? _latestCapabilities;

  /// A broadcast stream of position, status, capability, diagnostic, and error events.
  Stream<MetamapsPositioningEvent> get events => _eventsController.stream;

  /// The most recently reported lifecycle state.
  PositioningLifecycleStatus get status => _status;

  /// The most recently reported position, or `null` if none has been received.
  PositioningUpdate? get latestUpdate => _latestUpdate;

  /// The most recently received device capability snapshot, or `null` if none has been received.
  CapabilityReport? get latestCapabilities => _latestCapabilities;

  /// Downloads and validates the manifest and configures the client without showing a permission dialog.
  Future<void> configure() => _enqueue(() async {
    final clientId = await _ensureClient();
    await _nativeGateway.configureClient(clientId);
  });

  /// Returns the current OS permissions, sensor capabilities, and the selected profile.
  Future<CapabilityReport> capabilities() => _enqueue(() async {
    final clientId = await _ensureClient();
    final report = await _nativeGateway.clientCapabilities(clientId);
    _latestCapabilities = report;
    return report;
  });

  /// Shows the OS permission dialogs. Always call this from an explicit user action.
  Future<void> requestAuthorization() => _enqueue(() async {
    final clientId = await _ensureClient();
    await _nativeGateway.requestClientAuthorization(clientId);
  });

  /// Starts foreground positioning. The native side configures the client first if needed.
  Future<void> start() => _enqueue(() async {
    final clientId = await _ensureClient();
    await _nativeGateway.startClient(clientId);
  });

  /// Stops hardware subscriptions and keeps the client reusable.
  Future<void> stop() => _enqueue(() async {
    final clientId = _clientId;
    if (clientId != null) {
      await _nativeGateway.stopClient(clientId);
    }
  });

  /// Resets the estimator and the latest result while keeping the manifest.
  Future<void> reset() => _enqueue(() async {
    final clientId = await _ensureClient();
    await _nativeGateway.resetClient(clientId);
  });

  /// Resets PDR step timing and the estimator state at a known route start point.
  Future<void> resetPedestrianRoute() => _enqueue(() async {
    final clientId = await _ensureClient();
    await _nativeGateway.resetClientPedestrianRoute(clientId);
  });

  /// Releases native resources and the event stream. Safe to call more than once.
  Future<void> dispose() => _enqueue(() async {
    if (_disposed) {
      return;
    }
    _disposed = true;
    final clientId = _clientId;
    if (clientId != null) {
      await _nativeGateway.disposeClient(clientId);
      NativeEventRouter.instance.unregisterClient(clientId);
    }
    _status = PositioningLifecycleStatus.disposed;
    await _eventsController.close();
  }, allowDisposed: true);

  Future<int> _ensureClient() {
    if (_disposed) {
      throw _invariant('Client used after dispose.');
    }
    return _clientCreation ??= _createClient();
  }

  Future<int> _createClient() async {
    final clientId = await _nativeGateway.createClient(configuration);
    if (_disposed) {
      await _nativeGateway.disposeClient(clientId);
      throw _invariant('Client disposed while native creation was pending.');
    }
    _clientId = clientId;
    NativeEventRouter.instance.registerClient(clientId, _handleEvent);
    return clientId;
  }

  void _handleEvent(MetamapsPositioningEvent event) {
    if (_disposed || _eventsController.isClosed) {
      return;
    }
    switch (event) {
      case PositionEvent():
        _latestUpdate = event.update;
        _status = event.update.estimate.status;
      case BeaconSignalsEvent() || MotionHeadingEvent():
        break;
      case PositioningStatusEvent():
        _status = event.status;
      case CapabilitiesEvent():
        _latestCapabilities = event.report;
      case PositioningErrorEvent():
        break;
    }
    _eventsController.add(event);
  }

  Future<T> _enqueue<T>(
    Future<T> Function() action, {
    bool allowDisposed = false,
  }) {
    final completer = Completer<T>();
    _operationQueue = _operationQueue.then((_) async {
      if (_disposed && !allowDisposed) {
        completer.completeError(_invariant('Client used after dispose.'));
        return;
      }
      try {
        completer.complete(await action());
      } catch (error, stackTrace) {
        completer.completeError(error, stackTrace);
      }
    });
    return completer.future;
  }

  static MetamapsError _invariant(String detail) {
    return MetamapsError(
      code: MetamapsErrorCode.internalInvariantViolation,
      message: 'Indoor positioning entered an invalid state.',
      recoverable: false,
      userAction: MetamapsUserAction.retry,
      debugDetail: detail,
    );
  }
}
