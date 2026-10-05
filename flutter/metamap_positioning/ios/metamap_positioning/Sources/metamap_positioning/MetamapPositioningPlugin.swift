import Flutter
import Metamap
import MetamapPositioning
import UIKit

public final class MetamapPositioningPlugin: NSObject, FlutterPlugin, MetamapHostApi {
  private var nextClientId: Int64 = 1
  private var clients: [Int64: ClientEntry] = [:]
  private var mapViews: [Int64: MetamapMapView] = [:]
  private let events = FlutterEvents()
  private var flutterApi: MetamapFlutterApi!

  public static func register(with registrar: FlutterPluginRegistrar) {
    let instance = MetamapPositioningPlugin()
    instance.flutterApi = MetamapFlutterApi(binaryMessenger: registrar.messenger())
    MetamapHostApiSetup.setUp(binaryMessenger: registrar.messenger(), api: instance)
    EventsStreamHandler.register(with: registrar.messenger(), streamHandler: instance.events)
    registrar.register(
      FlutterMapViewFactory(owner: instance),
      withId: "jp.metamaps/metamap_map_view"
    )
    registrar.publish(instance)
  }

  deinit {
    for entry in clients.values {
      entry.eventTask.cancel()
      Task { @MainActor in entry.client.dispose() }
    }
    for mapView in mapViews.values {
      Task { @MainActor in mapView.dispose() }
    }
    events.close()
  }

  func createClient(
    configuration: NativePositioningConfiguration,
    completion: @escaping (Result<NativeClientCreateResult, Error>) -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      let id = self.nextClientId
      self.nextClientId += 1
      do {
        let nativeConfiguration = try configuration.nativeValue()
        try nativeConfiguration.validate()
        let client = MetamapPositioningClient(configuration: nativeConfiguration)
        let stream = client.events
        let eventTask = Task { @MainActor [weak self] in
          for await event in stream {
            guard !Task.isCancelled else { break }
            self?.emit(event.flutterValue(clientId: id))
          }
        }
        self.clients[id] = ClientEntry(client: client, eventTask: eventTask)
        completion(.success(NativeClientCreateResult(clientId: id)))
      } catch {
        completion(.success(NativeClientCreateResult(error: error.flutterValue())))
      }
    }
  }

  func configureClient(
    clientId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    clientOperation(clientId, completion: completion) { try await $0.configure() }
  }

  func clientCapabilities(
    clientId: Int64,
    completion: @escaping (Result<NativeCapabilityResult, Error>) -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      guard let entry = self.clients[clientId] else {
        completion(.success(NativeCapabilityResult(error: self.missingOwner("client", clientId))))
        return
      }
      completion(.success(NativeCapabilityResult(report: entry.client.capabilities().flutterValue())))
    }
  }

  func requestClientAuthorization(
    clientId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    clientOperation(clientId, completion: completion) {
      try await $0.requestAuthorization(.foregroundNavigation)
    }
  }

  func startClient(
    clientId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    clientOperation(clientId, completion: completion) { try await $0.start() }
  }

  func stopClient(
    clientId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    clientOperation(clientId, completion: completion) {
      $0.stop()
    }
  }

  func resetClient(
    clientId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    clientOperation(clientId, completion: completion) { try $0.reset() }
  }

  func resetClientPedestrianRoute(
    clientId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    clientOperation(clientId, completion: completion) { client in
      try client.resetPedestrianRoute()
    }
  }

  func disposeClient(
    clientId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      guard let entry = self.clients.removeValue(forKey: clientId) else {
        completion(.success(NativeOperationResult(error: self.missingOwner("client", clientId))))
        return
      }
      entry.eventTask.cancel()
      entry.client.dispose()
      completion(.success(NativeOperationResult()))
    }
  }

  func loadMapView(
    viewId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewLoadOperation(viewId, completion: completion) { try await $0.load() }
  }

  func reloadMapView(
    viewId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewLoadOperation(viewId, completion: completion) { try await $0.reload() }
  }

  func requestMapViewAuthorization(
    viewId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      try await $0.requestPositioningAuthorization()
    }
  }

  func startMapViewPositioning(
    viewId: Int64,
    requestAuthorization: Bool,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      try await $0.startPositioning(requestAuthorization: requestAuthorization)
    }
  }

  func stopMapViewPositioning(
    viewId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      $0.stopPositioning()
    }
  }

  func resetMapViewPedestrianRoute(
    viewId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) { view in
      try view.resetPedestrianRoute()
    }
  }

  func selectMapViewFloor(
    viewId: Int64,
    floorId: String?,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      try $0.selectFloor(try floorId.map(Self.uuid))
    }
  }

  func showMapViewSpot(
    viewId: Int64,
    spotId: String,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      try $0.showSpot(spotId)
    }
  }

  func setMapViewDestination(
    viewId: Int64,
    spotId: String?,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      try $0.setDestination(spotId)
    }
  }

  func setMapViewLanguage(
    viewId: Int64,
    language: String,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      try $0.setLanguage(language)
    }
  }

  func setMapViewCenterReticleEnabled(
    viewId: Int64,
    enabled: Bool,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      try $0.setCenterReticleEnabled(enabled)
    }
  }

  func requestMapViewCenterReticleCandidate(
    viewId: Int64,
    completion: @escaping (Result<NativeMapCenterReticleCandidateResult, Error>) -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      guard let mapView = self.mapViews[viewId] else {
        completion(.success(.init(candidate: nil, error: self.missingOwner("mapView", viewId))))
        return
      }
      do {
        let candidate = try await mapView.requestCenterReticleCandidate()
        completion(.success(.init(
          candidate: candidate.map {
            NativeMapCenterReticleCandidate(
              floorId: $0.floorId.uuidString.lowercased(),
              longitude: $0.longitude,
              latitude: $0.latitude,
              local: $0.local.map { NativeLocalPosition(x: $0.x, y: $0.y, z: $0.z) })
          },
          error: nil)))
      } catch {
        completion(.success(.init(candidate: nil, error: error.flutterValue())))
      }
    }
  }

  func openMapViewExternalLink(
    viewId: Int64,
    url: String,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void
  ) {
    mapViewOperation(viewId, completion: completion) {
      guard let parsed = URL(string: url) else {
        throw MetamapPositioningError.configurationInvalid("External link URL is invalid.")
      }
      $0.openExternalLink(parsed)
    }
  }

  @MainActor
  func emit(_ event: NativeEvent) {
    events.emit(event)
  }

  @MainActor
  func registerMapView(_ mapView: MetamapMapView, viewId: Int64) {
    if let previous = mapViews.updateValue(mapView, forKey: viewId) {
      previous.dispose()
    }
  }

  @MainActor
  func unregisterMapView(_ mapView: MetamapMapView, viewId: Int64) {
    guard mapViews[viewId] === mapView else { return }
    mapViews.removeValue(forKey: viewId)
    mapView.dispose()
  }

  @MainActor
  func decideMapViewExternalLink(
    viewId: Int64,
    url: URL,
    mapView: MetamapMapView
  ) {
    flutterApi.decideMapViewExternalLinkOpensDefault(
      viewId: viewId,
      url: url.absoluteString
    ) { [weak self, weak mapView] result in
      guard case .success(true) = result else { return }
      Task { @MainActor [weak self, weak mapView] in
        guard let self, let mapView, self.mapViews[viewId] === mapView else { return }
        mapView.openExternalLink(url)
      }
    }
  }

  private func clientOperation(
    _ clientId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void,
    operation: @escaping @MainActor (MetamapPositioningClient) async throws -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      guard let entry = self.clients[clientId] else {
        completion(.success(NativeOperationResult(error: self.missingOwner("client", clientId))))
        return
      }
      do {
        try await operation(entry.client)
        completion(.success(NativeOperationResult()))
      } catch {
        completion(.success(NativeOperationResult(error: error.flutterValue())))
      }
    }
  }

  private func mapViewOperation(
    _ viewId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void,
    operation: @escaping @MainActor (MetamapMapView) async throws -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      guard let mapView = self.mapViews[viewId] else {
        completion(.success(NativeOperationResult(error: self.missingOwner("mapView", viewId))))
        return
      }
      do {
        try await operation(mapView)
        completion(.success(NativeOperationResult()))
      } catch {
        completion(.success(NativeOperationResult(error: error.flutterValue())))
      }
    }
  }

  private func mapViewLoadOperation(
    _ viewId: Int64,
    completion: @escaping (Result<NativeOperationResult, Error>) -> Void,
    operation: @escaping @MainActor (MetamapMapView) async throws -> Void
  ) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      guard let mapView = self.mapViews[viewId] else {
        completion(.success(NativeOperationResult(error: self.missingOwner("mapView", viewId))))
        return
      }
      do {
        try await operation(mapView)
        completion(.success(NativeOperationResult()))
      } catch is CancellationError {
        completion(.success(NativeOperationResult(error: self.supersededLoad())))
      } catch let error as MetamapPositioningError
        where error.code == .webContentLoadFailed
          && error.debugDetail?.localizedCaseInsensitiveContains("superseded") == true {
        completion(.success(NativeOperationResult(error: self.supersededLoad())))
      } catch {
        completion(.success(NativeOperationResult(error: error.flutterValue())))
      }
    }
  }

  private func missingOwner(_ type: String, _ id: Int64) -> NativeErrorMessage {
    NativeErrorMessage(
      code: "internalInvariantViolation",
      message: "The native \(type) is unavailable.",
      recoverable: false,
      userAction: "retry",
      debugDetail: "\(type) \(id) was not registered."
    )
  }

  private func supersededLoad() -> NativeErrorMessage {
    NativeErrorMessage(
      code: "webContentLoadFailed",
      message: "The MapView load was superseded.",
      recoverable: true,
      userAction: "retry",
      debugDetail: "superseded"
    )
  }

  private static func uuid(_ value: String) throws -> UUID {
    guard let uuid = UUID(uuidString: value) else {
      throw MetamapPositioningError.configurationInvalid("Expected a UUID.")
    }
    return uuid
  }

  private struct ClientEntry {
    let client: MetamapPositioningClient
    let eventTask: Task<Void, Never>
  }
}

private extension NativePositioningConfiguration {
  func nativeValue() throws -> PositioningConfiguration {
    guard let url = URL(string: baseUrl) else {
      throw MetamapPositioningError.configurationInvalid("baseUrl is invalid.")
    }
    let group: UUID?
    if let groupId {
      guard let parsed = UUID(uuidString: groupId) else {
        throw MetamapPositioningError.configurationInvalid("groupId is not a UUID.")
      }
      group = parsed
    } else {
      group = nil
    }
    guard let policy = MotionPolicy(rawValue: motionPolicy) else {
      throw MetamapPositioningError.configurationInvalid("motionPolicy is unsupported.")
    }
    return PositioningConfiguration(
      baseURL: url,
      mapSlug: mapSlug,
      groupId: group,
      beaconDiagnosticsEnabled: beaconDiagnosticsEnabled,
      maxOfflineAge: TimeInterval(maxOfflineAgeMs) / 1000,
      motionPolicy: policy
    )
  }
}

private final class FlutterEvents: EventsStreamHandler {
  private var sink: PigeonEventSink<NativeEvent>?
  private var pending: [NativeEvent] = []

  override func onListen(
    withArguments arguments: Any?,
    sink: PigeonEventSink<NativeEvent>
  ) {
    self.sink = sink
    for event in pending {
      sink.success(event)
    }
    pending.removeAll(keepingCapacity: true)
  }

  override func onCancel(withArguments arguments: Any?) {
    sink = nil
  }

  @MainActor
  func emit(_ event: NativeEvent) {
    if let sink {
      sink.success(event)
    } else {
      if pending.count == 64 {
        pending.removeFirst()
      }
      pending.append(event)
    }
  }

  func close() {
    Task { @MainActor [weak self] in
      self?.sink?.endOfStream()
      self?.sink = nil
      self?.pending.removeAll()
    }
  }
}
