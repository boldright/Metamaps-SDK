import Foundation
import MetamapsPositioningCore

struct PedometerEventAccumulator {
    private(set) var previousSteps = 0
    private(set) var previousPedometerDistanceM: Double? = 0
    private var previousPedometerEndDate: Date?

    mutating func reset() {
        previousSteps = 0
        previousPedometerDistanceM = 0
        previousPedometerEndDate = nil
    }

    mutating func consume(
        count: Int,
        distanceM: Double?,
        startDate: Date,
        endDate: Date,
        monotonicTimestampMs: Int64
    ) -> ReplayEvent? {
        let delta = min(PositioningEstimator.maxStepsPerEvent, max(0, count - previousSteps))
        previousSteps = count
        let distanceDeltaM = distanceM.flatMap { distance in
            previousPedometerDistanceM.flatMap { previousDistance in
                distance >= previousDistance ? distance - previousDistance : nil
            }
        }
        previousPedometerDistanceM = distanceM
        let intervalStartDate = previousPedometerEndDate ?? startDate
        previousPedometerEndDate = endDate
        guard delta > 0 else { return nil }
        // CMPedometer returns only interval totals (there is no timestamp per step). Without faking arrival times, pass
        // the step count and the observed interval as one event. This also keeps the diagnostic log monotonic.
        let intervalMs = Int64(max(0, endDate.timeIntervalSince(intervalStartDate)) * 1_000)
        return ReplayEvent(
            type: "step",
            monotonicTimestampMs: monotonicTimestampMs,
            pedometerDistanceM: distanceDeltaM,
            stepCount: delta,
            intervalMs: intervalMs > 0 ? intervalMs : nil
        )
    }
}

#if os(iOS)
import CoreMotion

@MainActor
final class CoreMotionObservationAdapter: MotionObservationAdapter {
    private let motionManager = CMMotionManager()
    private let pedometer = CMPedometer()
    private let activityManager = CMMotionActivityManager()
    private let queue: OperationQueue = .main
    private var pedometerEventAccumulator = PedometerEventAccumulator()
    private var pedometerGeneration = 0
    private var activityGeneration = 0
    private var activityEventCount = 0
    private var pedometerEventHandler: (@MainActor @Sendable (ReplayEvent) -> Void)?
    private var pedometerErrorHandler: (@MainActor @Sendable (MetamapsError) -> Void)?

    var snapshot: MotionSnapshot {
        let authorization: MotionAuthorizationState = switch CMMotionActivityManager.authorizationStatus() {
        case .notDetermined: .notDetermined
        case .restricted, .denied: .denied
        case .authorized: .granted
        @unknown default: .denied
        }
        return .init(
            authorization: authorization,
            stepAvailable: CMPedometer.isStepCountingAvailable(),
            activityAvailable: CMMotionActivityManager.isActivityAvailable(),
            attitudeAvailable: motionManager.isDeviceMotionAvailable,
            gyroscopeAvailable: motionManager.isGyroAvailable,
            magnetometerAvailable: motionManager.isMagnetometerAvailable,
            barometerAvailable: CMAltimeter.isRelativeAltitudeAvailable()
        )
    }

    func requestAuthorization() async throws {
        guard CMPedometer.isStepCountingAvailable() else { return }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let now = Date()
            pedometer.queryPedometerData(
                from: now.addingTimeInterval(-0.1),
                to: now,
                withHandler: CoreMotionCallbackBridge.authorization(continuation: continuation)
            )
        }
    }

    func start(
        subscriptions: MotionSubscriptionDecision,
        onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void,
        onError: @escaping @MainActor @Sendable (MetamapsError) -> Void
    ) {
        stop()
        pedometerEventAccumulator.reset()
        activityEventCount = 0
        // Steps and heading need different authorization. Even if Motion & Fitness is denied, subscribe to device
        // motion and stop only steps. Keeping a handler when the decision is not to subscribe to steps would let
        // `resetStepBaseline()` revive steps and emit `motionPermissionDenied` repeatedly, so the handler follows the
        // decision too.
        let decision = subscriptions
        if decision.subscribeToStep {
            pedometerEventHandler = onEvent
            pedometerErrorHandler = onError
        }
        if decision.subscribeToAttitude, motionManager.isDeviceMotionAvailable {
            motionManager.deviceMotionUpdateInterval = 0.1
            let frames = CMMotionManager.availableAttitudeReferenceFrames()
            if frames.contains(.xMagneticNorthZVertical) {
                motionManager.startDeviceMotionUpdates(
                    using: .xMagneticNorthZVertical,
                    to: queue,
                    withHandler: CoreMotionCallbackBridge.deviceMotion(onEvent: onEvent, onError: onError)
                )
            }
        }
        if decision.subscribeToStep {
            startPedometerUpdates()
        }
        if decision.subscribeToActivity, CMMotionActivityManager.isActivityAvailable() {
            let generation = activityGeneration
            activityManager.startActivityUpdates(
                to: queue,
                withHandler: CoreMotionCallbackBridge.activity(
                    owner: self,
                    generation: generation,
                    onEvent: onEvent
                )
            )
        }
    }

    func resetStepBaseline() {
        guard CMPedometer.isStepCountingAvailable(),
              pedometerEventHandler != nil,
              pedometerErrorHandler != nil else { return }
        pedometer.stopUpdates()
        startPedometerUpdates()
    }

    func stop() {
        pedometerGeneration += 1
        activityGeneration += 1
        motionManager.stopDeviceMotionUpdates()
        pedometer.stopUpdates()
        activityManager.stopActivityUpdates()
        pedometerEventHandler = nil
        pedometerErrorHandler = nil
    }

    private func startPedometerUpdates() {
        guard let onEvent = pedometerEventHandler,
              let onError = pedometerErrorHandler else { return }
        pedometerGeneration += 1
        let generation = pedometerGeneration
        pedometerEventAccumulator.reset()
        pedometer.startUpdates(
            from: Date(),
            withHandler: CoreMotionCallbackBridge.pedometer(
                owner: self,
                generation: generation,
                onEvent: onEvent,
                onError: onError
            )
        )
    }

    fileprivate func consumePedometer(
        count: Int?,
        distanceM: Double?,
        startDate: Date?,
        endDate: Date?,
        errorDetail: String?,
        generation: Int,
        onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void,
        onError: @escaping @MainActor @Sendable (MetamapsError) -> Void
    ) {
        guard generation == pedometerGeneration else { return }
        if let errorDetail {
            onError(.init(
                code: .motionPermissionDenied,
                message: "Pedometer updates failed.",
                recoverable: true,
                userAction: .openAppSettings,
                debugDetail: errorDetail
            ))
            return
        }
        guard let count, let startDate, let endDate else { return }
        if let event = pedometerEventAccumulator.consume(
            count: count,
            distanceM: distanceM,
            startDate: startDate,
            endDate: endDate,
            monotonicTimestampMs: MonotonicClock.nowMilliseconds
        ) {
            onEvent(event)
        }
    }

    fileprivate func consumeActivity(
        flags: ActivityDiagnosticFlags,
        confidence: String,
        activityStartDate: Date,
        callbackDate: Date,
        callbackMonotonicTimestampMs: Int64,
        generation: Int,
        onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void
    ) {
        guard generation == activityGeneration else { return }
        let initialSnapshot = activityEventCount == 0
        activityEventCount += 1
        onEvent(ReplayEvent(
            type: "activity",
            monotonicTimestampMs: callbackMonotonicTimestampMs,
            activity: ActivityClassification.label(flags: flags, confidence: confidence),
            activityConfidence: confidence,
            activityStartMonotonicTimestampMs:
                ActivityTiming.estimatedStartMonotonicTimestampMs(
                    callbackMonotonicTimestampMs: callbackMonotonicTimestampMs,
                    callbackDate: callbackDate,
                    activityStartDate: activityStartDate
                ),
            activityInitialSnapshot: initialSnapshot,
            activityFlags: flags
        ))
    }
}

/// Core Motion invokes pedometer handlers on a private queue. Building these closures inside the
/// `@MainActor` adapter would implicitly isolate the closures themselves to MainActor in Swift 6,
/// causing an executor precondition failure before their bodies can hop back to MainActor.
enum CoreMotionCallbackBridge {
    static func authorization(
        continuation: CheckedContinuation<Void, Error>
    ) -> CMPedometerHandler {
        { _, error in
            if CMMotionActivityManager.authorizationStatus() == .denied {
                continuation.resume(throwing: MetamapsError(
                    code: .motionPermissionDenied,
                    message: "Motion permission was denied.",
                    recoverable: true,
                    userAction: .openAppSettings,
                    debugDetail: error.map(String.init(describing:))
                ))
            } else {
                continuation.resume()
            }
        }
    }

    static func deviceMotion(
        onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void,
        onError: @escaping @MainActor @Sendable (MetamapsError) -> Void
    ) -> CMDeviceMotionHandler {
        let yawIntegrator = GyroYawIntegrator()
        return { motion, error in
            let detail = error.map(String.init(describing:))
            let hasMotion = motion != nil
            let compassHeading = motion.flatMap { $0.heading >= 0 ? $0.heading : nil }
            let rollDeg = motion.map { $0.attitude.roll * 180 / .pi }
            let pitchDeg = motion.map { $0.attitude.pitch * 180 / .pi }
            let magneticFieldAccuracy = motion.map {
                switch $0.magneticField.accuracy {
                case .uncalibrated: "uncalibrated"
                case .low: "low"
                case .medium: "medium"
                case .high: "high"
                @unknown default: "uncalibrated"
                }
            }
            let yawSample = motion.map { m in
                GyroYawIntegrator.Sample(
                    timestamp: m.timestamp,
                    rotationRateX: m.rotationRate.x,
                    rotationRateY: m.rotationRate.y,
                    rotationRateZ: m.rotationRate.z,
                    gravityX: m.gravity.x,
                    gravityY: m.gravity.y,
                    gravityZ: m.gravity.z)
            }
            // The calibrated magnetic field vector in device coordinates, without OS attitude fusion. The magnitude detects
            // deviation from the field, and the components are collected as per-location magnetic heading samples
            // (tilt compensation is computed later).
            let magneticField = motion.map { m in
                (x: m.magneticField.field.x, y: m.magneticField.field.y, z: m.magneticField.field.z)
            }
            let magneticFieldMicroTesla = magneticField.map {
                ($0.x * $0.x + $0.y * $0.y + $0.z * $0.z).squareRoot()
            }
            Task { @MainActor in
                if let detail {
                    onError(.init(
                        code: .scanRuntimeFailed,
                        message: "Core Motion attitude updates failed.",
                        recoverable: true,
                        userAction: .retry,
                        debugDetail: detail
                    ))
                    return
                }
                guard hasMotion else { return }
                // With an uncalibrated magnetometer, heading arrives negative (no heading). Calibration guidance is
                // needed in exactly this state, so emit attitude even without a heading to deliver magneticFieldAccuracy.
                // The estimator does not update the heading while headingDeg is nil.
                onEvent(ReplayEvent(
                    type: "attitude",
                    monotonicTimestampMs: MonotonicClock.nowMilliseconds,
                    headingDeg: compassHeading.map(HeadingTransform.compassToLocal),
                    rollDeg: rollDeg,
                    pitchDeg: pitchDeg,
                    magneticFieldAccuracy: magneticFieldAccuracy,
                    relativeYawDeg: yawSample.map { yawIntegrator.integrate($0) },
                    magneticFieldMicroTesla: magneticFieldMicroTesla,
                    magneticFieldXMicroTesla: magneticField?.x,
                    magneticFieldYMicroTesla: magneticField?.y,
                    magneticFieldZMicroTesla: magneticField?.z
                ))
            }
        }
    }

    static func activity(
        owner: CoreMotionObservationAdapter,
        generation: Int,
        onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void
    ) -> CMMotionActivityHandler {
        { [weak owner] activity in
            guard let activity else { return }
            let flags = ActivityDiagnosticFlags(
                stationary: activity.stationary,
                walking: activity.walking,
                running: activity.running,
                automotive: activity.automotive,
                cycling: activity.cycling,
                unknown: activity.unknown
            )
            let confidence = switch activity.confidence {
            case .low: "low"
            case .medium: "medium"
            case .high: "high"
            @unknown default: "unknown"
            }
            let activityStartDate = activity.startDate
            // Store the reception time before hopping to the main actor, so queue waits do not count as sensor latency.
            let callbackDate = Date()
            let callbackMonotonicTimestampMs = MonotonicClock.nowMilliseconds
            Task { @MainActor [weak owner] in
                guard let owner else { return }
                owner.consumeActivity(
                    flags: flags,
                    confidence: confidence,
                    activityStartDate: activityStartDate,
                    callbackDate: callbackDate,
                    callbackMonotonicTimestampMs: callbackMonotonicTimestampMs,
                    generation: generation,
                    onEvent: onEvent
                )
            }
        }
    }

    static func pedometer(
        owner: CoreMotionObservationAdapter,
        generation: Int,
        onEvent: @escaping @MainActor @Sendable (ReplayEvent) -> Void,
        onError: @escaping @MainActor @Sendable (MetamapsError) -> Void
    ) -> CMPedometerHandler {
        { [weak owner] data, error in
            let detail = error.map(String.init(describing:))
            let count = data?.numberOfSteps.intValue
            let distanceM = data?.distance?.doubleValue
            let startDate = data?.startDate
            let endDate = data?.endDate
            Task { @MainActor [weak owner] in
                guard let owner else { return }
                owner.consumePedometer(
                    count: count,
                    distanceM: distanceM,
                    startDate: startDate,
                    endDate: endDate,
                    errorDetail: detail,
                    generation: generation,
                    onEvent: onEvent,
                    onError: onError
                )
            }
        }
    }
}
#endif
