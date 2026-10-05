@file:OptIn(MetamapInternalApi::class)

package jp.metamaps.positioning.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import jp.metamaps.positioning.ReplayEvent
import kotlin.math.PI

internal data class SensorSubscriptionDecision(
    val subscribeToStep: Boolean,
    val subscribeToAttitude: Boolean,
) {
    val hasSubscriptions: Boolean
        get() = subscribeToStep || subscribeToAttitude
}

internal fun decideSensorSubscriptions(state: MotionAdapterSnapshot) = SensorSubscriptionDecision(
    subscribeToStep = state.authorization == MotionAuthorizationState.GRANTED && state.stepAvailable,
    subscribeToAttitude = state.rotationVectorAvailable && state.magnetometerAvailable,
)

/**
 * Which sensors to subscribe to when positioning starts. **This is the only place that decides.**
 *
 * Writing these conditions in both the adapter and its caller duplicated them and caused a regression where
 * attitude was not subscribed without `ACTIVITY_RECOGNITION`. The client decides once and the adapter follows.
 */
internal fun motionSubscriptions(
    motionPolicy: MotionPolicy,
    state: MotionAdapterSnapshot,
): SensorSubscriptionDecision =
    if (motionPolicy == MotionPolicy.PREFERRED) {
        decideSensorSubscriptions(state)
    } else {
        SensorSubscriptionDecision(subscribeToStep = false, subscribeToAttitude = false)
    }

/**
 * Decides whether to thin attitude events to an effective 10 Hz.
 *
 * The sampling period of `SensorManager.registerListener` is **a request, not a guarantee**. On a Pixel 8, the
 * rotation vector arrives every 60 ms (about 16.7 Hz) even when 100,000 us is requested, so the host event stream
 * would not match the 10 Hz of iOS Core Motion. The request alone cannot guarantee the period, so the adapter
 * thins events by time.
 *
 * The decision uses only the sensor's monotonic timestamps, never the wall clock or the framework's actual
 * delivery rate. An event whose timestamp goes backward becomes the new reference, so thinning never stops
 * permanently.
 */
internal fun shouldEmitAttitude(lastEmittedMs: Long?, timestampMs: Long): Boolean {
    val last = lastEmittedMs ?: return true
    if (timestampMs < last) return true
    return timestampMs - last >= ATTITUDE_MIN_INTERVAL_MS
}

/** The lower bound of the effective delivery period, matching iOS Core Motion's `deviceMotionUpdateInterval = 0.1`. */
internal const val ATTITUDE_MIN_INTERVAL_MS = 100L

internal class AndroidSensorAdapter(
    context: Context,
) : MotionObservationAdapter, SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var onEvent: ((ReplayEvent) -> Unit)? = null
    private var onError: ((MetamapPositioningError) -> Unit)? = null
    private var thread: HandlerThread? = null
    @Volatile
    private var lastStepTimestampMs: Long? = null
    @Volatile
    private var lastAttitudeEmittedMs: Long? = null
    private var magneticFieldAccuracy = "uncalibrated"

    /**
     * Yaw from GAME_ROTATION_VECTOR, which does not use the magnetometer (local heading convention, arbitrary reference).
     * Collected to detect and isolate magnetic disturbances, and carried along with attitude events.
     */
    private var latestRelativeYawDeg: Double? = null

    /** Calibrated magnetic field strength (µT), carried along with attitude events. */
    private var latestMagneticFieldMicroTesla: Double? = null

    /** Calibrated magnetic field components in device coordinates (µT), without OS attitude fusion, carried along with attitude events. */
    private var latestMagneticFieldXyzMicroTesla: DoubleArray? = null

    override val snapshot: MotionAdapterSnapshot
        get() = MotionAdapterSnapshot(
            authorization = PermissionRequirements.motionState(appContext),
            stepAvailable = sensor(Sensor.TYPE_STEP_DETECTOR) != null,
            rotationVectorAvailable = sensor(Sensor.TYPE_ROTATION_VECTOR) != null,
            gyroscopeAvailable = sensor(Sensor.TYPE_GYROSCOPE) != null,
            magnetometerAvailable = sensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
            barometerAvailable = sensor(Sensor.TYPE_PRESSURE) != null,
        )

    override fun start(
        subscriptions: SensorSubscriptionDecision,
        onEvent: (ReplayEvent) -> Unit,
        onError: (MetamapPositioningError) -> Unit,
    ) {
        stop()
        // Only `MetamapPositioningClient.requestAuthorization` emits `MOTION_PERMISSION_DENIED`. Emitting it here would
        // repeat the same error on every `startHardware()` after returning to the foreground, unlike iOS.
        if (!subscriptions.hasSubscriptions) return
        this.onEvent = onEvent
        this.onError = onError
        resetStepBaseline()
        lastAttitudeEmittedMs = null
        magneticFieldAccuracy = "uncalibrated"
        latestRelativeYawDeg = null
        latestMagneticFieldMicroTesla = null
        latestMagneticFieldXyzMicroTesla = null
        val worker = HandlerThread("MetamapPositioningSensors").also { it.start() }
        thread = worker
        val handler = Handler(worker.looper)
        val register = { type: Int, samplingPeriodUs: Int ->
            sensor(type)?.let {
                sensorManager.registerListener(this@AndroidSensorAdapter, it, samplingPeriodUs, handler)
            } == true
        }
        val stepRegistered = subscriptions.subscribeToStep &&
            register(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_GAME)
        // Heading is independent of steps. Dropping heading when only the step registration fails would defeat the
        // reason this adapter keeps heading separate.
        val attitudeRegistered = subscriptions.subscribeToAttitude &&
            register(Sensor.TYPE_ROTATION_VECTOR, ATTITUDE_SAMPLING_PERIOD_US) &&
            register(Sensor.TYPE_MAGNETIC_FIELD, ATTITUDE_SAMPLING_PERIOD_US)
        // Magnetometer-free yaw is an optional subscription for data collection. Heading still works if the sensor is
        // missing or registration fails.
        if (attitudeRegistered) {
            register(Sensor.TYPE_GAME_ROTATION_VECTOR, ATTITUDE_SAMPLING_PERIOD_US)
        }
        if (subscriptions.subscribeToStep && !stepRegistered) {
            sensor(Sensor.TYPE_STEP_DETECTOR)?.let { sensorManager.unregisterListener(this, it) }
        }
        if (subscriptions.subscribeToAttitude && !attitudeRegistered) {
            sensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensorManager.unregisterListener(this, it) }
            sensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensorManager.unregisterListener(this, it) }
            sensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sensorManager.unregisterListener(this, it) }
        }
        if (!stepRegistered && !attitudeRegistered) {
            stop()
        }
        val requestedButFailed = (subscriptions.subscribeToStep && !stepRegistered) ||
            (subscriptions.subscribeToAttitude && !attitudeRegistered)
        if (requestedButFailed) {
            onError(
                MetamapPositioningError(
                    MetamapPositioningError.Code.SCAN_RUNTIME_FAILED,
                    "Requested Android motion sensor subscriptions could not start.",
                    true,
                    PositioningUserAction.RETRY,
                ),
            )
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
        onEvent = null
        onError = null
        thread?.quitSafely()
        thread = null
        resetStepBaseline()
    }

    override fun resetStepBaseline() {
        lastStepTimestampMs = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val timestamp = event.timestamp / 1_000_000
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                if (event.values.firstOrNull()?.let { it > 0 } == true) {
                    val intervalMs = lastStepTimestampMs
                        ?.let { (timestamp - it).takeIf { elapsed -> elapsed > 0 } }
                    lastStepTimestampMs = timestamp
                    onEvent?.invoke(
                        ReplayEvent(
                            type = "step",
                            monotonicTimestampMs = timestamp,
                            observations = null,
                            headingDeg = null,
                            headingAccuracyDeg = null,
                            stepLengthM = null,
                            stepCount = 1,
                            intervalMs = intervalMs,
                            activity = null,
                            state = null,
                        ),
                    )
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (!shouldEmitAttitude(lastAttitudeEmittedMs, timestamp)) return
                lastAttitudeEmittedMs = timestamp
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val compassDegrees = ((orientation[0] * 180.0 / PI) + 360.0) % 360.0
                val headingAccuracyDeg = event.values.getOrNull(4)
                    ?.takeIf { it >= 0 && it.isFinite() }
                    ?.let { it * 180.0 / PI }
                onEvent?.invoke(
                    ReplayEvent(
                        type = "attitude",
                        monotonicTimestampMs = timestamp,
                        observations = null,
                        headingDeg = HeadingTransform.compassToLocal(compassDegrees),
                        headingAccuracyDeg = headingAccuracyDeg,
                        rollDeg = orientation[2] * 180.0 / PI,
                        pitchDeg = orientation[1] * 180.0 / PI,
                        magneticFieldAccuracy = magneticFieldAccuracy,
                        relativeYawDeg = latestRelativeYawDeg,
                        magneticFieldMicroTesla = latestMagneticFieldMicroTesla,
                        magneticFieldXMicroTesla = latestMagneticFieldXyzMicroTesla?.get(0),
                        magneticFieldYMicroTesla = latestMagneticFieldXyzMicroTesla?.get(1),
                        magneticFieldZMicroTesla = latestMagneticFieldXyzMicroTesla?.get(2),
                        stepLengthM = null,
                        activity = null,
                        state = null,
                    ),
                )
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                // Yaw without the magnetometer. The reference is arbitrary, but the sign convention matches headingDeg (local heading).
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val azimuthDegrees = ((orientation[0] * 180.0 / PI) + 360.0) % 360.0
                latestRelativeYawDeg = HeadingTransform.compassToLocal(azimuthDegrees)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                latestMagneticFieldMicroTesla = kotlin.math.sqrt(x * x + y * y + z * z)
                latestMagneticFieldXyzMicroTesla = doubleArrayOf(x, y, z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_MAGNETIC_FIELD) return
        magneticFieldAccuracy = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
            else -> "uncalibrated"
        }
    }

    private fun sensor(type: Int): Sensor? = sensorManager.getDefaultSensor(type)

    private companion object {
        /**
         * A requested period equivalent to 10 Hz. The framework does not treat it as a lower bound and delivers faster on
         * some devices (60 ms measured on a Pixel 8). [shouldEmitAttitude] thins events to an effective 10 Hz.
         */
        const val ATTITUDE_SAMPLING_PERIOD_US = 100_000
    }
}
