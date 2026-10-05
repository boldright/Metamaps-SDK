import Foundation

/// Integrates gyroscope relative yaw without the magnetometer (`relativeYawDeg`).
///
/// Projects the rotation rate (device coordinates, right-handed, rad/s) onto world up derived from gravity and
/// integrates it. Counterclockwise seen from above is positive, matching the increasing direction of `headingDeg`
/// (local heading). The reference is an arbitrary heading at subscription start. Because the magnetometer is never
/// used, relative rotation stays correct under magnetic disturbance.
@MainActor
final class GyroYawIntegrator {
    struct Sample: Sendable {
        let timestamp: TimeInterval
        let rotationRateX: Double
        let rotationRateY: Double
        let rotationRateZ: Double
        let gravityX: Double
        let gravityY: Double
        let gravityZ: Double
    }

    private var previousTimestamp: TimeInterval?
    private var cumulativeYawDeg: Double = 0

    func integrate(_ sample: Sample) -> Double {
        defer { previousTimestamp = sample.timestamp }
        guard let previousTimestamp else { return cumulativeYawDeg }
        let dt = sample.timestamp - previousTimestamp
        // A huge dt, such as after a suspension, is not integrated because the rotation in between was not observed.
        guard dt > 0, dt < 1 else { return cumulativeYawDeg }
        let norm = (sample.gravityX * sample.gravityX
            + sample.gravityY * sample.gravityY
            + sample.gravityZ * sample.gravityZ).squareRoot()
        guard norm > 0.5 else { return cumulativeYawDeg }
        // World up = −gravity/|gravity|. ω·up is counterclockwise (the increasing direction of the local heading).
        let yawRateRad = -(sample.rotationRateX * sample.gravityX
            + sample.rotationRateY * sample.gravityY
            + sample.rotationRateZ * sample.gravityZ) / norm
        cumulativeYawDeg += yawRateRad * dt * 180 / .pi
        return cumulativeYawDeg
    }
}
