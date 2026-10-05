package jp.metamaps.positioning.android

import jp.metamaps.positioning.BeaconObservation
import jp.metamaps.positioning.LocalPosition
import jp.metamaps.positioning.PositioningCoordinateTransform
import jp.metamaps.positioning.PositioningManifest
import jp.metamaps.positioning.Wgs84Position
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.pow

internal class BeaconSignalDiagnostics {
    private val smoothedRssiByKey = mutableMapOf<String, Double>()
    private val latestByBeaconId = mutableMapOf<UUID, BeaconSignalReading>()

    fun reset() {
        smoothedRssiByKey.clear()
        latestByBeaconId.clear()
    }

    fun readings(
        observations: List<BeaconObservation>,
        manifest: PositioningManifest,
        latestUpdate: PositioningUpdate?,
    ): List<BeaconSignalReading> {
        val beaconsByKey = manifest.beacons
            .asSequence()
            .filter { it.isEnabled }
            .associateBy { it.key }
        observations.asSequence()
            .filter { it.rssiDbm.isFinite() && it.rssiDbm < 0 }
            .forEach { observation ->
                val beacon = beaconsByKey[observation.beaconKey.lowercase()] ?: return@forEach
                if (beacon.position.size < 3) return@forEach
                val beaconId = runCatching { UUID.fromString(beacon.id) }.getOrNull() ?: return@forEach
                val floorId = runCatching { UUID.fromString(beacon.floorId) }.getOrNull() ?: return@forEach
                val previous = smoothedRssiByKey[beacon.key] ?: observation.rssiDbm
                val smoothed = previous + SMOOTHING_ALPHA * (observation.rssiDbm - previous)
                smoothedRssiByKey[beacon.key] = smoothed
                val calibratedRssi = beacon.calibratedRssiAt1mDbm
                    ?: beacon.advertisedMeasuredPowerDbm
                    ?: manifest.parameters.defaultCalibratedRssiAt1mDbm
                val pathLoss = beacon.pathLossExponent
                    ?: manifest.parameters.defaultPathLossExponent
                val radioDistance = radioDistanceM(smoothed, calibratedRssi, pathLoss)
                val configuredWgs84 = Wgs84Position(
                    longitude = beacon.position[0],
                    latitude = beacon.position[1],
                    elevationM = beacon.position[2],
                )
                val configured = PositioningCoordinateTransform.wgs84ToLocal(
                    configuredWgs84,
                    manifest.coordinateFrame,
                )
                val sameFloor = latestUpdate?.estimate?.floorId == beacon.floorId
                val devicePosition = latestUpdate?.estimate?.local.takeIf { sameFloor }
                val deviceWgs84 = latestUpdate?.wgs84.takeIf { sameFloor }
                val configuredDistance = devicePosition?.horizontalDistanceTo(configured)
                latestByBeaconId[beaconId] = BeaconSignalReading(
                    beaconId = beaconId,
                    uuid = observation.uuid.lowercase(),
                    major = observation.major,
                    minor = observation.minor,
                    floorId = floorId,
                    rssiDbm = observation.rssiDbm,
                    smoothedRssiDbm = smoothed,
                    radioDistanceM = radioDistance,
                    configuredPosition = configured,
                    configuredWgs84 = configuredWgs84,
                    devicePosition = devicePosition,
                    deviceWgs84 = deviceWgs84,
                    configuredDistanceM = configuredDistance,
                    distanceDeltaM = configuredDistance?.let { radioDistance - it },
                    monotonicTimestampMs = observation.monotonicTimestampMs,
                )
            }

        val now = observations.maxOfOrNull { it.monotonicTimestampMs }
            ?: latestByBeaconId.values.maxOfOrNull { it.monotonicTimestampMs }
            ?: 0
        latestByBeaconId.entries.removeAll { now - it.value.monotonicTimestampMs > SNAPSHOT_RETENTION_MS }
        return latestByBeaconId.values.sortedWith(
            compareByDescending<BeaconSignalReading> { it.rssiDbm }
                .thenBy { it.major }
                .thenBy { it.minor },
        )
    }

    companion object {
        private const val SNAPSHOT_RETENTION_MS = 15_000L
        private const val SMOOTHING_ALPHA = 0.35

        internal fun radioDistanceM(
            rssiDbm: Double,
            calibratedRssiAt1mDbm: Double,
            pathLossExponent: Double,
        ): Double {
            if (!rssiDbm.isFinite() || !calibratedRssiAt1mDbm.isFinite() ||
                !pathLossExponent.isFinite() || pathLossExponent <= 0
            ) {
                return 100.0
            }
            return 10.0.pow(
                (calibratedRssiAt1mDbm - rssiDbm) / (10 * pathLossExponent),
            ).coerceIn(0.1, 100.0)
        }
    }
}

private fun LocalPosition.horizontalDistanceTo(other: LocalPosition): Double =
    hypot(x - other.x, z - other.z)
