import Foundation
import MetamapsPositioningCore

/// One iBeacon ranging constraint (proximity UUID and optional major). Exposed to Metamaps tooling only.
@_spi(MetamapsInternal)
public struct BeaconScanConstraint: Hashable, Sendable {
    public let uuid: UUID
    public let major: UInt16?

    public init(uuid: UUID, major: UInt16?) {
        self.uuid = uuid
        self.major = major
    }
}

/// Location authorization and ranging availability. Exposed to Metamaps tooling only.
@_spi(MetamapsInternal)
public struct LocationSnapshot: Equatable, Sendable {
    public let authorization: LocationAuthorizationState
    public let precise: Bool
    public let servicesEnabled: Bool
    public let rangingAvailable: Bool

    public init(authorization: LocationAuthorizationState, precise: Bool, servicesEnabled: Bool, rangingAvailable: Bool) {
        self.authorization = authorization
        self.precise = precise
        self.servicesEnabled = servicesEnabled
        self.rangingAvailable = rangingAvailable
    }
}

struct MotionSnapshot: Equatable, Sendable {
    /// Authorization for `CMPedometer` step counts (Motion & Fitness). Not a precondition for attitude.
    let authorization: MotionAuthorizationState
    let stepAvailable: Bool
    let activityAvailable: Bool
    let attitudeAvailable: Bool
    let gyroscopeAvailable: Bool
    let magnetometerAvailable: Bool
    let barometerAvailable: Bool
}

/// Which motion sensors to subscribe to.
///
/// Steps and heading need different permissions. `CMPedometer` requires Motion & Fitness authorization, but
/// device motion from `CMMotionManager` does not. Stopping both with a single `authorization` would lose the
/// heading display just because the user declined step counting.
struct MotionSubscriptionDecision: Equatable, Sendable {
    let subscribeToStep: Bool
    let subscribeToActivity: Bool
    let subscribeToAttitude: Bool

    var hasSubscriptions: Bool { subscribeToStep || subscribeToActivity || subscribeToAttitude }
}

func decideMotionSubscriptions(_ snapshot: MotionSnapshot) -> MotionSubscriptionDecision {
    // Activity (`CMMotionActivityManager`) needs the same Motion & Fitness authorization as steps.
    .init(
        subscribeToStep: snapshot.authorization == .granted && snapshot.stepAvailable,
        subscribeToActivity: snapshot.authorization == .granted && snapshot.activityAvailable,
        subscribeToAttitude: snapshot.attitudeAvailable && snapshot.magnetometerAvailable
    )
}

/// Which motion sensors to subscribe to when positioning starts. **This is the only place that decides.**
///
/// Writing these conditions in both the adapter and its caller duplicated them and caused a regression where
/// attitude was not subscribed without step authorization. The client decides once and the adapter follows.
/// `NSMotionUsageDescription` is required by `CMPedometer`, so it is a condition for steps only.
func motionSubscriptions(
    motionPolicy: MotionPolicy,
    snapshot: MotionSnapshot,
    motionUsageDescriptionPresent: Bool
) -> MotionSubscriptionDecision {
    guard motionPolicy == .preferred else {
        return .init(subscribeToStep: false, subscribeToActivity: false, subscribeToAttitude: false)
    }
    let sensors = decideMotionSubscriptions(snapshot)
    return .init(
        subscribeToStep: sensors.subscribeToStep && motionUsageDescriptionPresent,
        subscribeToActivity: sensors.subscribeToActivity && motionUsageDescriptionPresent,
        subscribeToAttitude: sensors.subscribeToAttitude
    )
}

/// iBeacon ranging source. Exposed to Metamaps tooling only.
@_spi(MetamapsInternal)
@MainActor
public protocol BeaconRangingAdapter: AnyObject {
    var snapshot: LocationSnapshot { get }
    func requestAuthorization() async throws
    func start(
        constraints: [BeaconScanConstraint],
        onObservations: @escaping @MainActor @Sendable ([BeaconObservation]) -> Void,
        onError: @escaping @MainActor @Sendable (MetamapsError) -> Void
    ) throws
    func stop()
}

@MainActor
protocol MotionObservationAdapter: AnyObject {
    var snapshot: MotionSnapshot { get }
    func requestAuthorization() async throws
    /// `subscriptions` is the decision the client made once with `motionSubscriptions(...)`. The adapter follows it
    /// instead of re-checking authorization itself, so the decision is not duplicated.
    func start(
        subscriptions: MotionSubscriptionDecision,
        onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void,
        onError: @escaping @MainActor @Sendable (MetamapsError) -> Void
    )
    func resetStepBaseline()
    func stop()
}

/// The monotonic clock shared by every positioning event. Exposed to Metamaps tooling only.
@_spi(MetamapsInternal)
public enum MonotonicClock {
    public static var nowMilliseconds: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1_000) }
}

enum HeadingTransform {
    /// Converts compass degrees (0=north, clockwise) to Metamaps local heading (0=+Z south).
    static func compassToLocal(_ degrees: Double) -> Double {
        (180 - degrees).truncatingRemainder(dividingBy: 360) + (degrees > 180 ? 360 : 0)
    }
}

enum ActivityClassification {
    /// Maps the OS activity classification to the four diagnostic values. Core Motion flags are not exclusive, so
    /// an observation that does not fit a single class stays `unknown` instead of being treated as `stationary`.
    static func label(flags: ActivityDiagnosticFlags, confidence: String) -> String {
        let activeFlagCount = [
            flags.stationary,
            flags.walking,
            flags.running,
            flags.automotive,
            flags.cycling,
            flags.unknown,
        ].count(where: { $0 })
        guard activeFlagCount == 1 else { return "unknown" }
        if flags.stationary {
            return confidence == "medium" || confidence == "high" ? "stationary" : "unknown"
        }
        if flags.walking { return "walking" }
        if flags.running { return "running" }
        return "unknown"
    }
}

enum ActivityTiming {
    /// Maps Core Motion's wall-clock `startDate` onto the same monotonic axis as diagnostic events when the callback
    /// arrives. In the first snapshot it can be the start of a state that began before the subscription, so the
    /// caller also records `activityInitialSnapshot`.
    static func estimatedStartMonotonicTimestampMs(
        callbackMonotonicTimestampMs: Int64,
        callbackDate: Date,
        activityStartDate: Date
    ) -> Int64 {
        let ageMs = callbackDate.timeIntervalSince(activityStartDate) * 1_000
        guard ageMs.isFinite, ageMs > 0 else { return callbackMonotonicTimestampMs }
        guard ageMs < Double(Int64.max) else { return 0 }
        return max(0, callbackMonotonicTimestampMs - Int64(ageMs.rounded()))
    }
}

@_spi(MetamapsInternal)
@MainActor
public final class UnsupportedBeaconRangingAdapter: BeaconRangingAdapter {
    public init() {}
    public var snapshot: LocationSnapshot {
        .init(authorization: .denied, precise: false, servicesEnabled: false, rangingAvailable: false)
    }
    public func requestAuthorization() async throws {
        throw MetamapsError(code: .bleUnsupported, message: "iBeacon ranging is unavailable on this platform.",
                                      recoverable: false, userAction: .selectLocationManually)
    }
    public func start(constraints: [BeaconScanConstraint],
               onObservations: @escaping @MainActor @Sendable ([BeaconObservation]) -> Void,
               onError: @escaping @MainActor @Sendable (MetamapsError) -> Void) throws {
        throw MetamapsError(code: .bleUnsupported, message: "iBeacon ranging is unavailable on this platform.",
                                      recoverable: false, userAction: .selectLocationManually)
    }
    public func stop() {}
}

@MainActor
final class UnsupportedMotionAdapter: MotionObservationAdapter {
    var snapshot: MotionSnapshot {
        .init(authorization: .unavailable, stepAvailable: false, activityAvailable: false,
              attitudeAvailable: false, gyroscopeAvailable: false, magnetometerAvailable: false,
              barometerAvailable: false)
    }
    func requestAuthorization() async throws {}
    func start(subscriptions: MotionSubscriptionDecision,
               onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void,
               onError: @escaping @MainActor @Sendable (MetamapsError) -> Void) {}
    func resetStepBaseline() {}
    func stop() {}
}
