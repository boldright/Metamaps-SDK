import Foundation
import Testing
@testable import MetamapsPositioning

/// Pins the sign, projection, and gap handling of magnetometer-free relative yaw integration.
/// The sign matches the increasing direction of the local heading (`headingDeg`): counterclockwise seen from above.
struct GyroYawIntegratorTests {
    private static func sample(
        _ timestamp: TimeInterval,
        rate: (Double, Double, Double),
        gravity: (Double, Double, Double)
    ) -> GyroYawIntegrator.Sample {
        .init(
            timestamp: timestamp,
            rotationRateX: rate.0,
            rotationRateY: rate.1,
            rotationRateZ: rate.2,
            gravityX: gravity.0,
            gravityY: gravity.1,
            gravityZ: gravity.2)
    }

    /// Held flat with the screen up, rotating counterclockwise seen from above at 90°/s for 1 second gives +90°.
    @MainActor @Test func flatCounterClockwiseRotationIsPositive() {
        let integrator = GyroYawIntegrator()
        let flat = (0.0, 0.0, -1.0)
        var yaw = 0.0
        for step in 0...10 {
            yaw = integrator.integrate(Self.sample(
                Double(step) * 0.1, rate: (0, 0, .pi / 2), gravity: flat))
        }
        #expect(abs(yaw - 90) < 0.001)
    }

    /// Held upright (top edge up), world up is the device Y axis. Only rotation about Y integrates into yaw;
    /// rotation about the screen normal (Z axis) is about a horizontal axis and does not contribute.
    @MainActor @Test func projectionFollowsGravityAxis() {
        let upright = (0.0, -1.0, 0.0)
        let aroundY = GyroYawIntegrator()
        var yaw = 0.0
        for step in 0...10 {
            yaw = aroundY.integrate(Self.sample(
                Double(step) * 0.1, rate: (0, .pi / 4, 0), gravity: upright))
        }
        #expect(abs(yaw - 45) < 0.001)

        let aroundScreen = GyroYawIntegrator()
        var unchanged = 0.0
        for step in 0...10 {
            unchanged = aroundScreen.integrate(Self.sample(
                Double(step) * 0.1, rate: (0, 0, .pi / 4), gravity: upright))
        }
        #expect(abs(unchanged) < 0.001)
    }

    /// A huge dt (1 second or more), such as after a suspension, is not integrated because the rotation in between was not observed.
    @MainActor @Test func largeGapIsNotIntegrated() {
        let integrator = GyroYawIntegrator()
        let flat = (0.0, 0.0, -1.0)
        _ = integrator.integrate(Self.sample(0, rate: (0, 0, .pi / 2), gravity: flat))
        let afterGap = integrator.integrate(Self.sample(5, rate: (0, 0, .pi / 2), gravity: flat))
        #expect(abs(afterGap) < 0.001)
        // Integration resumes from the normal interval after recovery.
        let resumed = integrator.integrate(Self.sample(5.1, rate: (0, 0, .pi / 2), gravity: flat))
        #expect(abs(resumed - 9) < 0.001)
    }
}
