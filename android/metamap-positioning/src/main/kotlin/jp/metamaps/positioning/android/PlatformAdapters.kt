package jp.metamaps.positioning.android

import android.os.SystemClock
import jp.metamaps.positioning.BeaconObservation
import jp.metamaps.positioning.ReplayEvent
import java.util.UUID

/** One iBeacon ranging constraint (proximity UUID and major). */
@MetamapInternalApi
data class BeaconScanConstraint(
    val uuid: UUID,
    val major: Int,
)

/** Location and Bluetooth scan authorization plus ranging availability. */
@MetamapInternalApi
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
@MetamapInternalApi
interface BeaconRangingAdapter {
    val snapshot: BeaconAdapterSnapshot

    fun start(
        constraints: List<BeaconScanConstraint>,
        onObservations: (List<BeaconObservation>) -> Unit,
        onError: (MetamapPositioningError) -> Unit,
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
        onError: (MetamapPositioningError) -> Unit,
    )

    fun stop()

    /** Clears adapter-side step timing at a known diagnostic route start. */
    fun resetStepBaseline()
}

/** The monotonic clock shared by every positioning event. */
@MetamapInternalApi
object MonotonicClock {
    fun nowMilliseconds(): Long = SystemClock.elapsedRealtime()
}

internal object HeadingTransform {
    /** Converts compass degrees (0=north, clockwise) to Metamap local heading (0=+Z south). */
    fun compassToLocal(degrees: Double): Double {
        val normalized = (180.0 - degrees) % 360.0
        return if (normalized < 0) normalized + 360.0 else normalized
    }
}
