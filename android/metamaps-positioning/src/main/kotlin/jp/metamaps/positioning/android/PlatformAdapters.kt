package jp.metamaps.positioning.android

import android.os.SystemClock
import java.util.UUID
import jp.metamaps.MetamapsError
import jp.metamaps.positioning.BeaconObservation
import jp.metamaps.positioning.ReplayEvent

/** One iBeacon ranging constraint (proximity UUID and major). */
@MetamapsInternalApi
data class BeaconScanConstraint(
    val uuid: UUID,
    val major: Int,
)

/** Location and Bluetooth scan authorization plus ranging availability. */
@MetamapsInternalApi
data class BeaconAdapterSnapshot(
    val authorization: LocationAuthorizationState,
    val bluetoothScan: String,
    val servicesEnabled: Boolean,
    val rangingAvailable: Boolean,
    val bleSupported: Boolean,
)

internal data class MotionAdapterSnapshot(
    val authorization: MotionAuthorizationState,
    val stepAvailable: Boolean,
    val rotationVectorAvailable: Boolean,
    val gyroscopeAvailable: Boolean,
    val magnetometerAvailable: Boolean,
    val barometerAvailable: Boolean,
)

/** iBeacon ranging source. */
@MetamapsInternalApi
interface BeaconRangingAdapter {
    val snapshot: BeaconAdapterSnapshot

    fun start(
        constraints: List<BeaconScanConstraint>,
        onObservations: (List<BeaconObservation>) -> Unit,
        onError: (MetamapsError) -> Unit,
    )

    fun stop()
}

internal interface MotionObservationAdapter {
    val snapshot: MotionAdapterSnapshot

    /**
     * `subscriptions` is the decision the client made once with `motionSubscriptions(...)`. The adapter follows it
     * instead of re-checking permissions itself, so the decision is not duplicated.
     */
    fun start(
        subscriptions: SensorSubscriptionDecision,
        onEvent: (ReplayEvent) -> Unit,
        onError: (MetamapsError) -> Unit,
    )

    fun stop()

    /** Clears adapter-side step timing at a known diagnostic route start. */
    fun resetStepBaseline()
}

/** The monotonic clock shared by every positioning event. */
@MetamapsInternalApi
object MonotonicClock {
    fun nowMilliseconds(): Long = SystemClock.elapsedRealtime()
}

internal object HeadingTransform {
    /** Converts compass degrees (0=north, clockwise) to Metamaps local heading (0=+Z south). */
    fun compassToLocal(degrees: Double): Double {
        val normalized = (180.0 - degrees) % 360.0
        return if (normalized < 0) normalized + 360.0 else normalized
    }
}
