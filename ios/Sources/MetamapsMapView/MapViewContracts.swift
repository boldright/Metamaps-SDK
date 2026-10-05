import Foundation
@_spi(MetamapsInternal) import MetamapsPositioning
import MetamapsPositioningCore

/// Who handles a positioning start request from the web map.
public enum MapViewPositioningPolicy: String, Codable, Sendable {
    /// The SDK requests permissions and starts positioning from user actions in the web UI.
    case userInitiated
    /// Requests from the web UI are reported as events, and the host app handles them.
    case hostControlled
    /// Shows only the map, with positioning disabled.
    case disabled
}

/// When positioning starts.
///
/// `positioningPolicy` decides who handles a start request; this value only decides when a start request
/// happens.
public enum MapViewPositioningStartTrigger: String, Codable, CaseIterable, Sendable {
    /// Starts only when the user taps the location button in the web UI.
    case userAction
    /// Starts once the map is ready, requesting any required OS permission that has not been decided yet.
    case automatic
    /// Starts at the same moment, but only when no additional OS dialog is needed.
    /// Does nothing while a permission is undecided. A denied optional Motion permission does not prevent a BLE-only start.
    case automaticWhenAuthorized
}

/// The automatic retry policy for web map load failures.
public enum MetamapsMapViewRetryPolicy: String, Codable, Sendable {
    /// Retries network failures and HTTP 408, 429, and 5xx after 1, 2, and 4 seconds, then every 4 seconds.
    case automatic
    /// Returns the first failure as `MetamapsError`.
    case disabled
}

/// How to handle an external link requested by the web map.
public enum MetamapsExternalLinkDecision: Equatable, Sendable {
    /// Opens the link in the SDK's default Safari view controller or in the app that handles it.
    case openDefault
    /// The host app handles the link, and the SDK does not open it.
    case handled
}

struct MetamapsWebOrigin: Equatable, Sendable {
    let scheme: String
    let host: String
    let port: Int

    init(scheme: String, host: String, port: Int) {
        let normalizedScheme = scheme.lowercased()
        self.scheme = normalizedScheme
        self.host = host.lowercased()
        self.port = Self.normalizedPort(port, scheme: normalizedScheme)
    }

    init?(url: URL) {
        guard let scheme = url.scheme, let host = url.host else { return nil }
        self.init(scheme: scheme, host: host, port: url.port ?? 0)
    }

    private static func normalizedPort(_ port: Int, scheme: String) -> Int {
        guard port == 0 else { return port }
        switch scheme {
        case "https": return 443
        case "http": return 80
        default: return 0
        }
    }
}

func shouldAutomaticallyGrantMetamapsMicrophonePermission(
    baseURL: URL,
    requestingScheme: String,
    requestingHost: String,
    requestingPort: Int,
    isMainFrame: Bool,
    isMicrophoneOnly: Bool
) -> Bool {
    guard isMainFrame, isMicrophoneOnly,
          baseURL.scheme?.lowercased() == "https",
          let configuredOrigin = MetamapsWebOrigin(url: baseURL)
    else { return false }

    return configuredOrigin == MetamapsWebOrigin(
        scheme: requestingScheme,
        host: requestingHost,
        port: requestingPort)
}

func isValidMetamapsSpotStableKey(_ value: String) -> Bool {
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
    return !value.isEmpty && value.count <= 128
        && value.unicodeScalars.allSatisfy(allowed.contains)
}

/// Immutable settings used to create a `MetamapsMapView`.
public struct MetamapsMapViewConfiguration: Sendable {
    /// Public map slug configured in the Metamaps console.
    public let mapSlug: String
    /// Origin that serves the web map and the positioning manifest. Leave the default unless
    /// Metamaps asks you to use another environment.
    public let baseURL: URL
    /// Pins a specific map group. `nil` uses the map's active group.
    public let groupId: UUID?
    /// Initial display language as a BCP 47-style language tag. `nil` follows the device language.
    public let language: String?
    /// Floor UUID shown first.
    public let initialFloorId: UUID?
    /// Short-lived token that previews an unpublished map.
    public let previewToken: String?
    /// Who handles positioning requests from the web UI.
    public let positioningPolicy: MapViewPositioningPolicy
    /// When positioning starts. The default starts automatically only on devices that can start
    /// without showing a new OS permission dialog.
    public let positioningStartTrigger: MapViewPositioningStartTrigger
    /// Shows registered-beacon diagnostics on the map and emits `beaconSignals` events.
    public let showsBeaconDiagnostics: Bool
    /// Extra public query parameters appended to the initial map URL.
    public let additionalQuery: [String: String]
    /// Automatic retry policy for failed map loads.
    public let retryPolicy: MetamapsMapViewRetryPolicy
    /// App identifier appended after `Metamaps/<version>` in the user agent.
    public let userAgentAppendix: String?
    /// Allows Safari Web Inspector. Enable only in development builds.
    public let isWebViewInspectable: Bool
    /// Opens the map in BLE test mode (`?bletest=1`) with the BLE test draft manifest.
    @_spi(MetamapsInternal) public let bleTest: Bool
    /// Receives every normalized positioning event before it reaches the estimator.
    @_spi(MetamapsInternal) public let diagnosticEventHandler: (@Sendable (ReplayEvent) -> Void)?

    /// Creates MapView settings. Only `mapSlug` is required.
    public init(
        mapSlug: String,
        baseURL: URL = MetamapsSDK.productionBaseURL,
        groupId: UUID? = nil,
        language: String? = nil,
        initialFloorId: UUID? = nil,
        previewToken: String? = nil,
        positioningPolicy: MapViewPositioningPolicy = .userInitiated,
        positioningStartTrigger: MapViewPositioningStartTrigger = .automaticWhenAuthorized,
        showsBeaconDiagnostics: Bool = false,
        additionalQuery: [String: String] = [:],
        retryPolicy: MetamapsMapViewRetryPolicy = .automatic,
        userAgentAppendix: String? = nil,
        isWebViewInspectable: Bool = false
    ) {
        self.init(
            mapSlug: mapSlug,
            baseURL: baseURL,
            groupId: groupId,
            language: language,
            initialFloorId: initialFloorId,
            previewToken: previewToken,
            positioningPolicy: positioningPolicy,
            positioningStartTrigger: positioningStartTrigger,
            showsBeaconDiagnostics: showsBeaconDiagnostics,
            additionalQuery: additionalQuery,
            retryPolicy: retryPolicy,
            userAgentAppendix: userAgentAppendix,
            isWebViewInspectable: isWebViewInspectable,
            bleTest: false,
            diagnosticEventHandler: nil)
    }

    /// Creates MapView settings for Metamaps tooling.
    @_spi(MetamapsInternal)
    public init(
        mapSlug: String,
        baseURL: URL = MetamapsSDK.productionBaseURL,
        groupId: UUID? = nil,
        language: String? = nil,
        initialFloorId: UUID? = nil,
        previewToken: String? = nil,
        positioningPolicy: MapViewPositioningPolicy = .userInitiated,
        positioningStartTrigger: MapViewPositioningStartTrigger = .automaticWhenAuthorized,
        showsBeaconDiagnostics: Bool = false,
        additionalQuery: [String: String] = [:],
        retryPolicy: MetamapsMapViewRetryPolicy = .automatic,
        userAgentAppendix: String? = nil,
        isWebViewInspectable: Bool = false,
        bleTest: Bool,
        diagnosticEventHandler: (@Sendable (ReplayEvent) -> Void)? = nil
    ) {
        self.mapSlug = mapSlug
        self.baseURL = baseURL
        self.groupId = groupId
        self.language = language
        self.initialFloorId = initialFloorId
        self.previewToken = previewToken
        self.positioningPolicy = positioningPolicy
        self.positioningStartTrigger = positioningStartTrigger
        self.showsBeaconDiagnostics = showsBeaconDiagnostics
        self.additionalQuery = additionalQuery
        self.retryPolicy = retryPolicy
        self.userAgentAppendix = userAgentAppendix
        self.isWebViewInspectable = isWebViewInspectable
        self.bleTest = bleTest
        self.diagnosticEventHandler = diagnosticEventHandler
    }

    var positioningConfiguration: PositioningConfiguration {
        .init(baseURL: baseURL, mapSlug: mapSlug, groupId: groupId,
              beaconDiagnosticsEnabled: effectiveBeaconDiagnostics,
              bleTest: bleTest,
              diagnosticEventHandler: diagnosticEventHandler)
    }

    var effectiveBeaconDiagnostics: Bool {
        bleTest || showsBeaconDiagnostics
    }

    /// Accepts the same ASCII tags as the web map and the Android SDK.
    static func validateLanguage(_ language: String) throws {
        // `\z` instead of `$`: ICU's `$` also matches before a trailing line break, so "en\n" would pass.
        guard language.range(of: #"^[A-Za-z0-9_-]{1,35}\z"#, options: .regularExpression) != nil else {
            throw MetamapsError.configurationInvalid("language must be a BCP 47-style language tag.")
        }
    }

    func validate() throws {
        try positioningConfiguration.validate()
        if let language { try Self.validateLanguage(language) }
        if let previewToken {
            let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_."))
            guard !previewToken.isEmpty, previewToken.count <= 2048,
                  previewToken.unicodeScalars.allSatisfy(allowed.contains) else {
                throw MetamapsError.configurationInvalid("previewToken contains unsupported characters.")
            }
        }
    }
}

func makeMetamapsMapURL(configuration: MetamapsMapViewConfiguration) throws -> URL {
    var url = configuration.baseURL
    url.append(path: "d")
    url.append(path: configuration.mapSlug)
    guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
        throw MetamapsError.configurationInvalid("Unable to construct map URL.")
    }
    let fixedValues: [(String, String?)] = [
        ("group", configuration.groupId?.uuidString.lowercased()),
        ("lang", configuration.language),
        ("floor", configuration.initialFloorId?.uuidString.lowercased()),
        ("preview", configuration.previewToken),
        ("bletest", configuration.bleTest ? "1" : nil),
    ]
    let fixedKeys = Set(fixedValues.map(\.0))
    let fixedItems = fixedValues.compactMap { name, configuredValue -> URLQueryItem? in
        guard let value = configuration.additionalQuery[name] ?? configuredValue else { return nil }
        return URLQueryItem(name: name, value: value)
    }
    let items = fixedItems
        + configuration.additionalQuery.keys.filter { !fixedKeys.contains($0) }.sorted().map {
            URLQueryItem(name: $0, value: configuration.additionalQuery[$0])
        }
    components.queryItems = items.isEmpty ? nil : items
    guard let result = components.url else {
        throw MetamapsError.configurationInvalid("Unable to construct map URL.")
    }
    return result
}

struct MapViewLoadFailureClassification: Equatable, Sendable {
    let code: MetamapsError.Code
    let recoverable: Bool
    let userAction: MetamapsUserAction
    let shouldRetryAutomatically: Bool

    static func httpStatus(_ statusCode: Int) -> Self? {
        guard statusCode >= 400 else { return nil }
        switch statusCode {
        case 404:
            return .init(code: .mapNotFound, recoverable: false,
                         userAction: .checkConfiguration, shouldRetryAutomatically: false)
        case 401, 403:
            return .init(code: .mapAccessDenied, recoverable: true,
                         userAction: .retry, shouldRetryAutomatically: false)
        case 408, 429, 500...599:
            return .init(code: .webContentLoadFailed, recoverable: true,
                         userAction: .retry, shouldRetryAutomatically: true)
        default:
            return .init(code: .webContentLoadFailed, recoverable: true,
                         userAction: .retry, shouldRetryAutomatically: false)
        }
    }

    static let connection = Self(
        code: .webContentLoadFailed, recoverable: true,
        userAction: .retry, shouldRetryAutomatically: true)
    static let tls = Self(
        code: .webContentLoadFailed, recoverable: false,
        userAction: .checkConfiguration, shouldRetryAutomatically: false)
    static let otherNavigation = Self(
        code: .webContentLoadFailed, recoverable: true,
        userAction: .retry, shouldRetryAutomatically: false)
}

enum MapViewRetryPlan {
    static func delaySeconds(afterFailure failureNumber: Int) -> Int {
        switch failureNumber {
        case ...1: 1
        case 2: 2
        default: 4
        }
    }
}

func isCancelledMetamapsNavigationError(_ error: Error) -> Bool {
    let error = error as NSError
    return (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled)
        || (error.domain == "WKErrorDomain" && error.code == 102)
}

private let metamapsExternalLinkSchemes: Set<String> = [
    "http", "https", "tel", "mailto", "sms", "geo",
]

func isAllowedMetamapsExternalLink(_ url: URL) -> Bool {
    guard let scheme = url.scheme?.lowercased() else { return false }
    return metamapsExternalLinkSchemes.contains(scheme)
}

func defaultMetamapsExternalLinkDecision(for url: URL) -> MetamapsExternalLinkDecision? {
    isAllowedMetamapsExternalLink(url) ? .openDefault : nil
}

func makeMetamapsUserAgent(applicationVersion: String, appendix: String?) -> String {
    let base = "Metamaps/\(applicationVersion)"
    guard let appendix, !appendix.isEmpty else { return base }
    return "\(base) \(appendix)"
}

/// The load state of the embedded WebView's main frame.
public enum MapViewLoadState: String, Codable, Sendable {
    case idle, loading, loaded, failed, disposed
}

/// Information sent when the web map starts accepting map view commands.
public struct MapReadyEvent: Codable, Equatable, Sendable {
    public let mapId: UUID
    public let groupId: UUID
    public let configRevision: String?
    public let manifestRevision: Int?
}

/// A change of the floor selected in the web map.
public struct FloorChangedEvent: Codable, Equatable, Sendable {
    public let floorId: UUID?
    public let source: String?
}

/// The candidate coordinate on the selected floor directly under the center reticle.
public struct MapCenterReticleCandidate: Equatable, Sendable {
    public let floorId: UUID
    public let longitude: Double
    public let latitude: Double
    /// Manifest-local coordinates, when the validated positioning manifest contains the same floor.
    public let local: LocalPosition?

    public init(floorId: UUID, longitude: Double, latitude: Double, local: LocalPosition?) {
        self.floorId = floorId
        self.longitude = longitude
        self.latitude = latitude
        self.local = local
    }
}

/// The public spot selected in the web map.
public struct SpotSelectedEvent: Codable, Equatable, Sendable {
    /// The stable key of the public spot, such as 6Y6SSBY5 (keys in the older spot_ format are also accepted). Not a database UUID.
    public let spotId: String?
}

/// The route state of the web map and the optional destination spot.
public struct RouteChangedEvent: Codable, Equatable, Sendable {
    /// The stable key of the public spot.
    public let destinationSpotId: String?
    public let active: Bool
}

/// Events from `MetamapsMapView`.
///
/// Minor updates add cases, so include a `default` branch in a switch.
public enum MetamapsMapViewEvent: Sendable {
    case ready(MapReadyEvent)
    case loadState(MapViewLoadState)
    case positioningStatus(PositioningLifecycleStatus)
    /// Availability of OS permissions and device sensors.
    case capabilities(CapabilityReport)
    /// The device heading from Core Motion. The standard SDK UI does not show a compass.
    case motionHeading(MotionHeadingReading)
    /// The latest position. Check `mode`, `accuracyRadiusM`, and `diagnosticFlags` for the positioning state.
    /// The value stays on the device and is not sent to any server.
    case position(PositioningUpdate)
    /// On-device diagnostics about registered beacons and the estimated position. Not sent to any server.
    case beaconSignals([BeaconSignalReading])
    case positioningAuthorizationRequested
    case positioningStartRequested
    case floorChanged(FloorChangedEvent)
    case spotSelected(SpotSelectedEvent)
    case routeChanged(RouteChangedEvent)
    case externalLinkRequested(URL)
    case error(MetamapsError)
}

#if os(iOS)
import UIKit

/// The delegate that receives `MetamapsMapView` events and decides how to handle external links.
@MainActor
public protocol MetamapsMapViewDelegate: AnyObject {
    /// Called when the map view sends an event.
    func metamapsMapView(_ mapView: MetamapsMapView, didReceive event: MetamapsMapViewEvent)
    /// Returns whether an external link opens with the SDK's default handling or is handled by the host app.
    func metamapsMapView(
        _ mapView: MetamapsMapView,
        decideExternalLink url: URL
    ) -> MetamapsExternalLinkDecision
}

public extension MetamapsMapViewDelegate {
    func metamapsMapView(
        _ mapView: MetamapsMapView,
        decideExternalLink url: URL
    ) -> MetamapsExternalLinkDecision {
        .openDefault
    }
}
#endif
