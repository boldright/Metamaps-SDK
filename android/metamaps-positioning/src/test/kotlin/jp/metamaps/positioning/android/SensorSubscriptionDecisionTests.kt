package jp.metamaps.positioning.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards against regressions where a combination of motion permission and sensor availability also loses the heading subscription.
 */
class SensorSubscriptionDecisionTests {
    @Test
    fun deniedMotionPermissionKeepsAttitudeWithoutStep() {
        val decision = decideSensorSubscriptions(snapshot(authorization = MotionAuthorizationState.DENIED))

        assertTrue(decision.subscribeToAttitude)
        assertFalse(decision.subscribeToStep)
    }

    @Test
    fun missingStepDetectorKeepsAttitudeWithoutStep() {
        val decision = decideSensorSubscriptions(
            snapshot(
                authorization = MotionAuthorizationState.GRANTED,
                stepAvailable = false,
            ),
        )

        assertTrue(decision.subscribeToAttitude)
        assertFalse(decision.subscribeToStep)
    }

    @Test
    fun missingRotationVectorDisablesAttitude() {
        val decision = decideSensorSubscriptions(snapshot(rotationVectorAvailable = false))

        assertFalse(decision.subscribeToAttitude)
    }

    @Test
    fun noAvailableSensorsCreatesNoSubscriptions() {
        val decision = decideSensorSubscriptions(
            snapshot(
                stepAvailable = false,
                rotationVectorAvailable = false,
                magnetometerAvailable = false,
            ),
        )

        assertFalse(decision.hasSubscriptions)
    }

    @Test
    fun missingMagnetometerDisablesAttitude() {
        val decision = decideSensorSubscriptions(snapshot(magnetometerAvailable = false))

        assertFalse(decision.subscribeToAttitude)
    }

    @Test
    fun clientStartsMotionAdapterWithoutActivityRecognition() {
        val decision = motionSubscriptions(
            MotionPolicy.PREFERRED,
            snapshot(authorization = MotionAuthorizationState.NOT_DETERMINED, stepAvailable = false),
        )

        assertTrue(decision.hasSubscriptions)
        assertTrue(decision.subscribeToAttitude)
        assertFalse(decision.subscribeToStep)
    }

    @Test
    fun clientSkipsMotionAdapterWhenNothingIsSubscribable() {
        val decision = motionSubscriptions(
            MotionPolicy.PREFERRED,
            snapshot(
                stepAvailable = false,
                rotationVectorAvailable = false,
                magnetometerAvailable = false,
            ),
        )

        assertFalse(decision.hasSubscriptions)
    }

    @Test
    fun clientHonoursDisabledMotionPolicy() {
        val decision = motionSubscriptions(MotionPolicy.DISABLED, snapshot())

        assertFalse(decision.hasSubscriptions)
        assertFalse(decision.subscribeToAttitude)
    }

    private fun snapshot(
        authorization: MotionAuthorizationState = MotionAuthorizationState.GRANTED,
        stepAvailable: Boolean = true,
        rotationVectorAvailable: Boolean = true,
        magnetometerAvailable: Boolean = true,
    ) = MotionAdapterSnapshot(
        authorization = authorization,
        stepAvailable = stepAvailable,
        rotationVectorAvailable = rotationVectorAvailable,
        gyroscopeAvailable = true,
        magnetometerAvailable = magnetometerAvailable,
        barometerAvailable = true,
    )
}
