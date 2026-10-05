#if os(iOS)
import CoreLocation
import Foundation
import MetamapPositioningCore

/// Core Location iBeacon ranging used by `MetamapPositioningClient`. Exposed to Metamaps tooling only.
@_spi(MetamapInternal)
@MainActor
public final class CoreLocationBeaconAdapter: NSObject, BeaconRangingAdapter, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var authorizationContinuation: CheckedContinuation<Void, Error>?
    private var activeConstraints: [CLBeaconIdentityConstraint] = []
    private var onObservations: (@MainActor @Sendable ([BeaconObservation]) -> Void)?
    private var onError: (@MainActor @Sendable (MetamapPositioningError) -> Void)?

    override public init() {
        super.init()
        manager.delegate = self
    }

    public var snapshot: LocationSnapshot {
        let authorizationStatus = manager.authorizationStatus
        return .init(
            authorization: Self.authorization(authorizationStatus),
            precise: manager.accuracyAuthorization == .fullAccuracy,
            servicesEnabled: coreLocationServicesAreUsable(for: authorizationStatus),
            rangingAvailable: CLLocationManager.isRangingAvailable()
        )
    }

    public func requestAuthorization() async throws {
        guard let usage = Bundle.main.object(forInfoDictionaryKey: "NSLocationWhenInUseUsageDescription") as? String,
              !usage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw MetamapPositioningError.configurationInvalid(
                "NSLocationWhenInUseUsageDescription is missing from the host app Info.plist.")
        }
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            try ensurePreciseLocation()
        case .denied:
            throw MetamapPositioningError(code: .locationPermissionDenied, message: "Location permission was denied.",
                                          recoverable: false, userAction: .openAppSettings)
        case .restricted:
            throw MetamapPositioningError(code: .locationPermissionDenied, message: "Location permission is restricted.",
                                          recoverable: false, userAction: .selectLocationManually)
        case .notDetermined:
            try await withCheckedThrowingContinuation { continuation in
                authorizationContinuation = continuation
                manager.requestWhenInUseAuthorization()
            }
            try ensurePreciseLocation()
        @unknown default:
            throw MetamapPositioningError(code: .locationPermissionDenied, message: "Location authorization is unknown.",
                                          recoverable: true, userAction: .retry)
        }
    }

    public func start(
        constraints: [BeaconScanConstraint],
        onObservations: @escaping @MainActor @Sendable ([BeaconObservation]) -> Void,
        onError: @escaping @MainActor @Sendable (MetamapPositioningError) -> Void
    ) throws {
        let state = snapshot
        guard state.servicesEnabled else {
            throw MetamapPositioningError(code: .locationPermissionDenied, message: "Location Services are disabled.",
                                          recoverable: true, userAction: .openAppSettings)
        }
        guard state.rangingAvailable else {
            throw MetamapPositioningError(code: .bleUnsupported, message: "iBeacon ranging is unavailable.",
                                          recoverable: false, userAction: .selectLocationManually)
        }
        guard state.authorization == .whenInUse || state.authorization == .always else {
            throw MetamapPositioningError(code: .locationPermissionDenied, message: "Location permission is required before scanning.",
                                          recoverable: true, userAction: .requestPermission)
        }
        try ensurePreciseLocation()
        guard !constraints.isEmpty else {
            throw MetamapPositioningError(code: .noRegisteredBeacons, message: "There are no beacon constraints to scan.",
                                          recoverable: false, userAction: .selectLocationManually)
        }
        stop()
        self.onObservations = onObservations
        self.onError = onError
        activeConstraints = constraints.map { value in
            if let major = value.major { return CLBeaconIdentityConstraint(uuid: value.uuid, major: major) }
            return CLBeaconIdentityConstraint(uuid: value.uuid)
        }
        activeConstraints.forEach(manager.startRangingBeacons(satisfying:))
    }

    public func stop() {
        activeConstraints.forEach(manager.stopRangingBeacons(satisfying:))
        activeConstraints.removeAll()
        onObservations = nil
        onError = nil
    }

    // Core Location does not guarantee the delegate executor. Keep protocol entry points
    // nonisolated and cross to MainActor only after converting callback values.
    nonisolated public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor [weak self] in
            self?.handleAuthorizationChange(status)
        }
    }

    private func handleAuthorizationChange(_ status: CLAuthorizationStatus) {
        guard let continuation = authorizationContinuation, status != .notDetermined else { return }
        authorizationContinuation = nil
        switch status {
        case .authorizedWhenInUse, .authorizedAlways: continuation.resume()
        case .denied, .restricted:
            continuation.resume(throwing: MetamapPositioningError(
                code: .locationPermissionDenied, message: "Location permission was denied.",
                recoverable: false, userAction: .openAppSettings))
        default:
            continuation.resume(throwing: MetamapPositioningError(
                code: .locationPermissionDenied, message: "Location authorization did not complete.",
                recoverable: true, userAction: .retry))
        }
    }

    nonisolated public func locationManager(
        _ manager: CLLocationManager,
        didRange beacons: [CLBeacon],
        satisfying beaconConstraint: CLBeaconIdentityConstraint
    ) {
        let now = MonotonicClock.nowMilliseconds
        let observations = beacons.compactMap { beacon -> BeaconObservation? in
            guard beacon.rssi != 0 else { return nil }
            let uuid = beacon.uuid.uuidString.lowercased()
            let major = beacon.major.intValue
            let minor = beacon.minor.intValue
            return BeaconObservation(
                beaconKey: "\(uuid)/\(major)/\(minor)", uuid: uuid, major: major, minor: minor,
                rssiDbm: Double(beacon.rssi), windowStartMonotonicTimestampMs: now,
                monotonicTimestampMs: now, source: "core_location", duplicateCount: 1)
        }
        guard !observations.isEmpty else { return }
        Task { @MainActor [weak self, observations] in
            self?.onObservations?(observations)
        }
    }

    nonisolated public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let detail = String(describing: error)
        Task { @MainActor [weak self] in
            self?.onError?(.init(code: .scanRuntimeFailed, message: "iBeacon ranging failed.",
                                recoverable: true, userAction: .retry, debugDetail: detail))
        }
    }

    private func ensurePreciseLocation() throws {
        guard manager.accuracyAuthorization == .fullAccuracy else {
            throw MetamapPositioningError(code: .preciseLocationRequired, message: "Precise Location is required for beacon ranging.",
                                          recoverable: false, userAction: .enablePreciseLocation)
        }
    }

    private static func authorization(_ value: CLAuthorizationStatus) -> LocationAuthorizationState {
        switch value {
        case .notDetermined: .notDetermined
        case .restricted: .restricted
        case .denied: .denied
        case .authorizedWhenInUse: .whenInUse
        case .authorizedAlways: .always
        @unknown default: .denied
        }
    }
}

nonisolated func coreLocationServicesAreUsable(for authorizationStatus: CLAuthorizationStatus) -> Bool {
    switch authorizationStatus {
    case .notDetermined, .authorizedWhenInUse, .authorizedAlways:
        true
    case .restricted, .denied:
        false
    @unknown default:
        false
    }
}
#endif
