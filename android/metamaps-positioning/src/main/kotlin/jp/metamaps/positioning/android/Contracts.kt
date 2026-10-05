@file:OptIn(MetamapsInternalApi::class)

package jp.metamaps.positioning.android

import java.net.URI
import java.util.UUID
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsSdk
import jp.metamaps.positioning.LocalPosition
import jp.metamaps.positioning.PositionEstimate
import jp.metamaps.positioning.Wgs84Position

/** How step and heading sensors are used for positioning. */
enum class MotionPolicy {
    PREFERRED,
    DISABLED,
}

/** The scope of positioning permissions the host app requests. */
enum class PositioningAuthorizationMode(val wireValue: String) {
    FOREGROUND_NAVIGATION("foreground_navigation"),
}

/**
 * Configuration for the headless positioning client.
 *
 * @property baseUrl Origin that serves the map and the positioning manifest. Leave the default unless
 *   Metamaps asks you to use another environment.
 * @property mapSlug Public map slug configured in the Metamaps console.
 * @property groupId Pins a specific map group. `null` uses the map's active group.
 * @property beaconDiagnosticsEnabled Emits device-local beacon relation snapshots
 *   ([MetamapsPositioningEvent.BeaconSignals]). Disabled by default; enable it only for on-device
 *   diagnostics.
 * @property maxOfflineAgeMs How long, in milliseconds, a verified manifest cache may be used offline.
 * @property motionPolicy Whether step counting (pedestrian dead reckoning) is used.
 */
data class PositioningConfiguration(
    val baseUrl: URI = PRODUCTION_BASE_URL,
    val mapSlug: String,
    val groupId: UUID? = null,
    val beaconDiagnosticsEnabled: Boolean = false,
    val maxOfflineAgeMs: Long = 7L * 24 * 60 * 60 * 1_000,
    val motionPolicy: MotionPolicy = MotionPolicy.PREFERRED,
) {
    /** Options set by Metamaps tooling through [withInternalOptions]. */
    @MetamapsInternalApi
    var internalOptions: MetamapsInternalOptions = MetamapsInternalOptions()
        private set

    /** Returns a copy that carries [options]. */
    @MetamapsInternalApi
    fun withInternalOptions(options: MetamapsInternalOptions): PositioningConfiguration =
        copy().also { it.internalOptions = options }

    internal val effectiveBeaconDiagnostics: Boolean
        get() = beaconDiagnosticsEnabled || internalOptions.bleTest

    /** Validates the configuration and throws [IllegalArgumentException] for an invalid value. */
    fun validate() {
        validateBaseUrl(baseUrl, "baseUrl")
        require(SLUG.matches(mapSlug)) { "mapSlug contains unsupported characters" }
        require(maxOfflineAgeMs in 0..30L * 24 * 60 * 60 * 1_000) {
            "maxOfflineAgeMs must be between 0 and 30 days"
        }
    }

    companion object {
        val PRODUCTION_BASE_URL: URI = MetamapsSdk.PRODUCTION_BASE_URL
        private val SLUG = Regex("[A-Za-z0-9_-]{1,100}")

        /** Rejects non-HTTPS origins (HTTP is allowed only for localhost) and URLs with credentials. */
        @MetamapsInternalApi
        fun validateBaseUrl(value: URI, name: String) {
            val localHttp = value.scheme == "http" &&
                value.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1")
            require((value.scheme == "https" || localHttp) && !value.host.isNullOrBlank()) {
                "$name must be HTTPS (HTTP is allowed only for localhost)"
            }
            require(value.userInfo == null && value.query == null && value.fragment == null) {
                "$name must not contain credentials, query, or fragment"
            }
        }
    }
}

/** The lifecycle state of the positioning runtime. */
enum class PositioningLifecycleStatus(val wireValue: String) {
    UNCONFIGURED("unconfigured"),
    CONFIGURED("configured"),
    AUTHORIZED("authorized"),
    ACQUIRING("acquiring"),
    TRACKING("tracking"),
    DEGRADED("degraded"),
    COASTING("coasting"),
    RECOVERING("recovering"),
    LOST("lost"),
    PAUSED_BACKGROUND("paused_background"),
    STOPPED("stopped"),
    DISPOSED("disposed");

    companion object {
        fun fromWire(value: String): PositioningLifecycleStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** The location permission state reported by Android. */
enum class LocationAuthorizationState(val wireValue: String) {
    NOT_DETERMINED("notDetermined"),
    DENIED("denied"),
    RESTRICTED("restricted"),
    WHEN_IN_USE("whenInUse"),
    ALWAYS("always"),
}

/** The motion permission state reported by Android. */
enum class MotionAuthorizationState(val wireValue: String) {
    NOT_DETERMINED("notDetermined"),
    DENIED("denied"),
    GRANTED("granted"),
    UNAVAILABLE("unavailable"),
}

/** Capabilities of the device, permissions, sensors, and the selected positioning profile. */
data class CapabilityReport(
    val platform: String,
    val osVersion: String,
    val sdkVersion: String,
    val ble: Ble,
    val authorization: Authorization,
    val sensors: Sensors,
    val selectedProfile: String,
) {
    data class Ble(
        val supported: Boolean,
        val enabled: Boolean,
        val rangingAvailable: Boolean,
        val foregroundScan: Boolean,
        val backgroundScan: String,
        val directionFinding: String,
        val channelSounding: String,
    )

    data class Authorization(
        val location: LocationAuthorizationState,
        val preciseLocation: Boolean,
        val bluetoothScan: String,
        val motion: MotionAuthorizationState,
    )

    data class Sensors(
        val stepDetector: Boolean,
        val rotationVector: Boolean,
        val gyroscope: Boolean,
        val magnetometer: Boolean,
        val barometer: Boolean,
    )
}

/** The identity of the loaded positioning manifest. */
data class ManifestIdentity(
    val mapId: UUID,
    val groupId: UUID,
    val revision: Int,
)

/** A positioning update that combines the map identity with local and WGS 84 positions. */
data class PositioningUpdate(
    val mapId: UUID,
    val groupId: UUID,
    val estimate: PositionEstimate,
    val wgs84: Wgs84Position?,
)

/** Latest fused Android sensor heading delivered to native hosts. */
data class MotionHeadingReading(
    val localHeadingDeg: Double?,
    val magneticFieldAccuracy: String?,
)

/**
 * Device-local relation between a registered beacon placement, the latest radio observation,
 * and the estimator's device position.
 */
data class BeaconSignalReading(
    val beaconId: UUID,
    val uuid: String,
    val major: Int,
    val minor: Int,
    val floorId: UUID,
    val rssiDbm: Double,
    val smoothedRssiDbm: Double,
    val radioDistanceM: Double,
    val configuredPosition: LocalPosition,
    val configuredWgs84: Wgs84Position,
    val devicePosition: LocalPosition?,
    val deviceWgs84: Wgs84Position?,
    val configuredDistanceM: Double?,
    val distanceDeltaM: Double?,
    val monotonicTimestampMs: Long,
)

/** Events the positioning client sends to the host app. */
sealed interface MetamapsPositioningEvent {
    data class Position(val update: PositioningUpdate) : MetamapsPositioningEvent
    data class BeaconSignals(val readings: List<BeaconSignalReading>) : MetamapsPositioningEvent
    data class MotionHeading(val reading: MotionHeadingReading) : MetamapsPositioningEvent
    data class Status(val status: PositioningLifecycleStatus) : MetamapsPositioningEvent
    data class Capabilities(val report: CapabilityReport) : MetamapsPositioningEvent
    data class Error(val error: MetamapsError) : MetamapsPositioningEvent
}
