import Flutter
import Metamap
import MetamapPositioning
import UIKit

final class FlutterMapViewFactory: NSObject, FlutterPlatformViewFactory {
  private weak var owner: MetamapPositioningPlugin?

  init(owner: MetamapPositioningPlugin) {
    self.owner = owner
  }

  func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
    FlutterStandardMessageCodec.sharedInstance()
  }

  func create(
    withFrame frame: CGRect,
    viewIdentifier viewId: Int64,
    arguments args: Any?
  ) -> FlutterPlatformView {
    guard let owner,
          let parameters = args as? [String: Any],
          let configuration = try? parameters.configuration()
    else {
      return InvalidFlutterMapView(frame: frame)
    }
    return MainActor.assumeIsolated {
      let mapView = MetamapMapView(configuration: configuration)
      mapView.frame = frame
      let delegate = FlutterMapViewDelegate(viewId: viewId, owner: owner)
      mapView.delegate = delegate
      owner.registerMapView(mapView, viewId: viewId)
      return FlutterMapView(
        viewId: viewId,
        mapView: mapView,
        delegate: delegate,
        owner: owner
      )
    }
  }
}

private final class FlutterMapView: NSObject, FlutterPlatformView {
  private let viewId: Int64
  private let mapView: MetamapMapView
  private let delegate: FlutterMapViewDelegate
  private weak var owner: MetamapPositioningPlugin?

  init(
    viewId: Int64,
    mapView: MetamapMapView,
    delegate: FlutterMapViewDelegate,
    owner: MetamapPositioningPlugin
  ) {
    self.viewId = viewId
    self.mapView = mapView
    self.delegate = delegate
    self.owner = owner
  }

  func view() -> UIView {
    mapView
  }

  deinit {
    let id = viewId
    let view = mapView
    let plugin = owner
    Task { @MainActor in
      plugin?.unregisterMapView(view, viewId: id)
    }
  }
}

@MainActor
private final class FlutterMapViewDelegate: NSObject, MetamapMapViewDelegate {
  private let viewId: Int64
  private weak var owner: MetamapPositioningPlugin?

  init(viewId: Int64, owner: MetamapPositioningPlugin) {
    self.viewId = viewId
    self.owner = owner
  }

  func metamapMapView(
    _ mapView: MetamapMapView,
    didReceive event: MetamapMapViewEvent
  ) {
    owner?.emit(event.flutterValue(viewId: viewId))
  }

  func metamapMapView(
    _ mapView: MetamapMapView,
    decideExternalLink url: URL
  ) -> MetamapExternalLinkDecision {
    owner?.decideMapViewExternalLink(viewId: viewId, url: url, mapView: mapView)
    return .handled
  }
}

private final class InvalidFlutterMapView: NSObject, FlutterPlatformView {
  private let label: UILabel

  init(frame: CGRect) {
    label = UILabel(frame: frame)
    label.text = "Invalid MetamapMapView configuration"
    label.textAlignment = .center
    label.numberOfLines = 0
  }

  func view() -> UIView {
    label
  }
}

private extension Dictionary where Key == String, Value == Any {
  func configuration() throws -> MetamapMapViewConfiguration {
    guard let baseUrl = self["baseUrl"] as? String,
          let url = URL(string: baseUrl),
          let mapSlug = self["mapSlug"] as? String,
          !mapSlug.isEmpty,
          let policyValue = self["positioningPolicy"] as? String,
          let policy = MapViewPositioningPolicy(rawValue: policyValue)
    else {
      throw MetamapPositioningError.configurationInvalid(
        "Missing or invalid Flutter MapView creation parameters."
      )
    }
    let groupId = try optionalUuid("groupId")
    let initialFloorId = try optionalUuid("initialFloorId")
    let language = self["language"] as? String
    let previewToken = self["previewToken"] as? String
    let additionalQuery = self["additionalQuery"] as? [String: String] ?? [:]
    let retryPolicyValue = self["retryPolicy"] as? String ?? "automatic"
    guard let retryPolicy = MetamapMapViewRetryPolicy(rawValue: retryPolicyValue) else {
      throw MetamapPositioningError.configurationInvalid("Unsupported retryPolicy.")
    }
    // creationParams from older Dart code have no fields. Fall back to the default automatic start.
    let startTriggerValue = self["positioningStartTrigger"] as? String ?? "automaticWhenAuthorized"
    guard let startTrigger = MapViewPositioningStartTrigger(rawValue: startTriggerValue) else {
      throw MetamapPositioningError.configurationInvalid("Unsupported positioningStartTrigger.")
    }
    return MetamapMapViewConfiguration(
      mapSlug: mapSlug,
      baseURL: url,
      groupId: groupId,
      language: language,
      initialFloorId: initialFloorId,
      previewToken: previewToken,
      positioningPolicy: policy,
      positioningStartTrigger: startTrigger,
      showsBeaconDiagnostics: self["showsBeaconDiagnostics"] as? Bool == true,
      additionalQuery: additionalQuery,
      retryPolicy: retryPolicy,
      userAgentAppendix: self["userAgentAppendix"] as? String,
      isWebViewInspectable: self["isWebViewInspectable"] as? Bool == true
    )
  }

  func optionalUuid(_ key: String) throws -> UUID? {
    guard let value = self[key] as? String, !value.isEmpty else { return nil }
    guard let uuid = UUID(uuidString: value) else {
      throw MetamapPositioningError.configurationInvalid("\(key) is not a UUID.")
    }
    return uuid
  }
}
