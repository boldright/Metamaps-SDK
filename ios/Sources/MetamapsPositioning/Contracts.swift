import Foundation
import MetamapsPositioningCore

/// Whether to use step-based pedestrian dead reckoning (PDR).
public enum MotionPolicy: String, Codable, Sendable {
    case preferred
    case disabled
}

/// The usage scenario for which OS permissions are requested.
public enum PositioningAuthorizationMode: String, Codable, Sendable {
    case foregroundNavigation = "foreground_navigation"
}

/// Configuration for the headless positioning client.
public struct PositioningConfiguration: Sendable {
    /// The production origin used when no `baseURL` is given.
    public static let productionBaseURL = MetamapsSDK.productionBaseURL

    /// Origin that serves the map and the positioning manifest. Leave the default unless Metamaps
    /// asks you to use another environment.
    public let baseURL: URL
    /// Public map slug configured in the Metamaps console.
    public let mapSlug: String
    /// Pins a specific map group. `nil` uses the map's active group.
    public let groupId: UUID?
    /// Emits device-local beacon relation snapshots (`MetamapsPositioningEvent.beaconSignals`).
    /// Disabled by default; enable it only for on-device diagnostics.
    public let beaconDiagnosticsEnabled: Bool
    /// How long, in seconds, a verified manifest cache may be used while offline.
    public let maxOfflineAge: TimeInterval
    /// Whether step counting (pedestrian dead reckoning) is used.
    public let motionPolicy: MotionPolicy
    /// Requests the map's BLE test draft manifest instead of the active revision.
    @_spi(MetamapsInternal) public let bleTest: Bool
    /// Receives every normalized BLE, motion, and lifecycle event before it reaches the estimator.
    /// The handler must return promptly.
    @_spi(MetamapsInternal) public let diagnosticEventHandler: (@Sendable (ReplayEvent) -> Void)?

    /// Creates a configuration. Only `mapSlug` is required.
    public init(
        baseURL: URL = Self.productionBaseURL,
        mapSlug: String,
        groupId: UUID? = nil,
        beaconDiagnosticsEnabled: Bool = false,
        maxOfflineAge: TimeInterval = 7 * 24 * 60 * 60,
        motionPolicy: MotionPolicy = .preferred
    ) {
        self.init(
            baseURL: baseURL,
            mapSlug: mapSlug,
            groupId: groupId,
            beaconDiagnosticsEnabled: beaconDiagnosticsEnabled,
            maxOfflineAge: maxOfflineAge,
            motionPolicy: motionPolicy,
            bleTest: false,
            diagnosticEventHandler: nil)
    }

    /// Creates a configuration for Metamaps tooling. BLE test mode always enables beacon diagnostics.
    @_spi(MetamapsInternal)
    public init(
        baseURL: URL = Self.productionBaseURL,
        mapSlug: String,
        groupId: UUID? = nil,
        beaconDiagnosticsEnabled: Bool = false,
        maxOfflineAge: TimeInterval = 7 * 24 * 60 * 60,
        motionPolicy: MotionPolicy = .preferred,
        bleTest: Bool,
        diagnosticEventHandler: (@Sendable (ReplayEvent) -> Void)? = nil
    ) {
        self.baseURL = baseURL
        self.mapSlug = mapSlug
        self.groupId = groupId
        self.beaconDiagnosticsEnabled = beaconDiagnosticsEnabled || bleTest
        self.maxOfflineAge = maxOfflineAge
        self.motionPolicy = motionPolicy
        self.bleTest = bleTest
        self.diagnosticEventHandler = diagnosticEventHandler
    }

    /// Validates the values and throws `MetamapsError` if any is invalid.
    public func validate() throws {
        let allowedLocalhost = baseURL.scheme == "http" && ["localhost", "127.0.0.1", "::1"].contains(baseURL.host)
        guard (baseURL.scheme == "https" || allowedLocalhost), baseURL.host != nil,
              baseURL.user == nil, baseURL.password == nil, baseURL.query == nil, baseURL.fragment == nil else {
            throw MetamapsError.configurationInvalid("baseURL must be HTTPS (HTTP is allowed only for localhost).")
        }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        guard !mapSlug.isEmpty, mapSlug.count <= 100,
              mapSlug.unicodeScalars.allSatisfy(allowed.contains) else {
            throw MetamapsError.configurationInvalid("mapSlug contains unsupported characters.")
        }
        guard maxOfflineAge >= 0, maxOfflineAge <= 30 * 24 * 60 * 60 else {
            throw MetamapsError.configurationInvalid("maxOfflineAge must be between 0 and 30 days.")
        }
    }
}

/// The current lifecycle state of the positioning client.
public enum PositioningLifecycleStatus: String, Codable, Sendable {
    case unconfigured
    case configured
    case authorized
    case acquiring
    case tracking
    case degraded
    case coasting
    case recovering
    case lost
    case pausedBackground = "paused_background"
    case stopped
    case disposed
}

/// The location permission state reported by the OS.
public enum LocationAuthorizationState: String, Codable, Sendable {
    case notDetermined, denied, restricted, whenInUse, always
}

/// The motion permission state reported by the OS.
public enum MotionAuthorizationState: String, Codable, Sendable {
    case notDetermined, denied, granted, unavailable
}

/// A snapshot of the OS, permissions, sensors, and the selected positioning profile.
public struct CapabilityReport: Codable, Equatable, Sendable {
    /// Availability of BLE scanning and the related radio features.
    public struct Ble: Codable, Equatable, Sendable {
        public let supported: Bool
        public let enabled: Bool
        public let rangingAvailable: Bool
        public let foregroundScan: Bool
        public let backgroundScan: String
        public let directionFinding: String
        public let channelSounding: String
    }

    /// A snapshot of the OS permissions positioning needs.
    public struct Authorization: Codable, Equatable, Sendable {
        public let location: LocationAuthorizationState
        public let preciseLocation: Bool
        public let bluetoothScan: String
        public let motion: MotionAuthorizationState
    }

    /// Availability of the device sensors used for PDR and diagnostics.
    public struct Sensors: Codable, Equatable, Sendable {
        public let stepDetector: Bool
        public let rotationVector: Bool
        public let gyroscope: Bool
        public let magnetometer: Bool
        public let barometer: Bool
    }

    public let platform: String
    public let osVersion: String
    public let sdkVersion: String
    public let ble: Ble
    public let authorization: Authorization
    public let sensors: Sensors
    public let selectedProfile: String
}

/// The map, group, and revision identifiers of the loaded positioning manifest.
public struct ManifestIdentity: Codable, Equatable, Sendable {
    public let mapId: UUID
    public let groupId: UUID
    public let revision: Int

    public init(mapId: UUID, groupId: UUID, revision: Int) {
        self.mapId = mapId
        self.groupId = groupId
        self.revision = revision
    }
}

/// An update that combines the map identifier, the estimate, and an optional WGS 84 position.
public struct PositioningUpdate: Codable, Equatable, Sendable {
    public let mapId: UUID
    public let groupId: UUID
    public let estimate: PositionEstimate
    public let wgs84: Wgs84Position?

    public init(mapId: UUID, groupId: UUID, estimate: PositionEstimate, wgs84: Wgs84Position?) {
        self.mapId = mapId
        self.groupId = groupId
        self.estimate = estimate
        self.wgs84 = wgs84
    }
}

/// Latest fused Core Motion heading delivered to native hosts. `localHeadingDeg` uses the
/// positioning-core local convention; hosts can convert it to a magnetic compass bearing.
public struct MotionHeadingReading: Codable, Equatable, Sendable {
    public let localHeadingDeg: Double?
    public let magneticFieldAccuracy: String?

    public init(localHeadingDeg: Double?, magneticFieldAccuracy: String?) {
        self.localHeadingDeg = localHeadingDeg
        self.magneticFieldAccuracy = magneticFieldAccuracy
    }
}

/// A device-local relation between a registered beacon placement, the latest radio observation,
/// and the estimator's device position. BLE test mode enables it automatically; production hosts
/// must explicitly set `beaconDiagnosticsEnabled`.
public struct BeaconSignalReading: Codable, Equatable, Sendable {
    public let beaconId: UUID
    public let uuid: String
    public let major: Int
    public let minor: Int
    public let floorId: UUID
    /// The latest Core Location ranging-cycle RSSI.
    public let rssiDbm: Double
    /// A short exponential smoothing window used only for the approximate radio range.
    public let smoothedRssiDbm: Double
    /// Log-distance estimate from the manifest calibration. This is not a precise physical range.
    public let radioDistanceM: Double
    /// Registered placement converted into the manifest-local X/Y/Z frame.
    public let configuredPosition: LocalPosition
    /// Registered placement in the map coordinate system used by the embedded runtime.
    public let configuredWgs84: Wgs84Position
    /// Latest device position from the estimator, when available on the same floor.
    public let devicePosition: LocalPosition?
    /// Latest device position in the map coordinate system, when available on the same floor.
    public let deviceWgs84: Wgs84Position?
    /// Horizontal X/Z separation between the registered beacon and latest device estimate.
    public let configuredDistanceM: Double?
    /// `radioDistanceM - configuredDistanceM`, when the device and beacon are on the same floor.
    public let distanceDeltaM: Double?
    public let monotonicTimestampMs: Int64

    public init(
        beaconId: UUID,
        uuid: String,
        major: Int,
        minor: Int,
        floorId: UUID,
        rssiDbm: Double,
        smoothedRssiDbm: Double,
        radioDistanceM: Double,
        configuredPosition: LocalPosition,
        configuredWgs84: Wgs84Position,
        devicePosition: LocalPosition?,
        deviceWgs84: Wgs84Position?,
        configuredDistanceM: Double?,
        distanceDeltaM: Double?,
        monotonicTimestampMs: Int64
    ) {
        self.beaconId = beaconId
        self.uuid = uuid
        self.major = major
        self.minor = minor
        self.floorId = floorId
        self.rssiDbm = rssiDbm
        self.smoothedRssiDbm = smoothedRssiDbm
        self.radioDistanceM = radioDistanceM
        self.configuredPosition = configuredPosition
        self.configuredWgs84 = configuredWgs84
        self.devicePosition = devicePosition
        self.deviceWgs84 = deviceWgs84
        self.configuredDistanceM = configuredDistanceM
        self.distanceDeltaM = distanceDeltaM
        self.monotonicTimestampMs = monotonicTimestampMs
    }
}

/// The action the host app should offer the user to recover from an error.
public enum MetamapsUserAction: String, Codable, Sendable {
    case none
    case checkConfiguration
    case retry
    case requestPermission
    case enableBluetooth
    case enablePreciseLocation
    case openAppSettings
    case selectLocationManually
}

/// An SDK error with a stable code, a recoverability flag, and a recommended action.
public struct MetamapsError: Error, LocalizedError, Codable, Equatable, Sendable {
    /// Stable identifiers for SDK errors.
    public enum Code: String, Codable, Sendable {
        case configurationInvalid
        case mapNotFound
        case mapAccessDenied
        case webContentLoadFailed
        case mapOperationFailed
        case bridgeHandshakeFailed
        case bridgeUnsupported
        case bridgeMapMismatch
        case manifestUnavailable
        case manifestExpired
        case manifestUnsupported
        case locationPermissionDenied
        case preciseLocationRequired
        case bluetoothPermissionDenied
        case bluetoothDisabled
        case bleUnsupported
        case motionPermissionDenied
        case scanStartFailed
        case scanRuntimeFailed
        case noRegisteredBeacons
        case insufficientSignals
        case pausedBackground
        case positionLost
        case backgroundModeUnavailable
        case backgroundStartNotAllowed
        case backgroundInterrupted
        case internalInvariantViolation
    }

    public let code: Code
    public let message: String
    public let recoverable: Bool
    public let userAction: MetamapsUserAction
    public let debugDetail: String?

    public init(
        code: Code,
        message: String,
        recoverable: Bool,
        userAction: MetamapsUserAction,
        debugDetail: String? = nil
    ) {
        self.code = code
        self.message = message
        self.recoverable = recoverable
        self.userAction = userAction
        self.debugDetail = debugDetail
    }

    public static func configurationInvalid(_ detail: String) -> Self {
        .init(code: .configurationInvalid, message: "The Metamaps SDK configuration is invalid.",
              recoverable: false, userAction: .checkConfiguration, debugDetail: detail)
    }

    public var errorDescription: String? { message }
}

/// Typed events from the headless positioning client.
///
/// Minor updates add cases, so include a `default` branch in a switch.
public enum MetamapsPositioningEvent: Sendable {
    case position(PositioningUpdate)
    case beaconSignals([BeaconSignalReading])
    case motionHeading(MotionHeadingReading)
    case status(PositioningLifecycleStatus)
    case capabilities(CapabilityReport)
    case error(MetamapsError)
}

/// Version information of the Metamaps iOS SDK.
public enum MetamapsSDK {
    public static let version = "0.6.0"

    /// The production origin for the map and the positioning manifest when no origin is specified.
    public static let productionBaseURL = URL(string: "https://metamaps.jp")!
}
