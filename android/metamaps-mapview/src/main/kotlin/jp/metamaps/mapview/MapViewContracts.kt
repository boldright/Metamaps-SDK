@file:OptIn(MetamapsInternalApi::class)

package jp.metamaps.mapview

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsSdk
import jp.metamaps.positioning.LocalPosition
import jp.metamaps.positioning.android.BeaconSignalReading
import jp.metamaps.positioning.android.CapabilityReport
import jp.metamaps.positioning.android.MetamapsInternalApi
import jp.metamaps.positioning.android.MetamapsInternalOptions
import jp.metamaps.positioning.android.MotionHeadingReading
import jp.metamaps.positioning.android.PositioningConfiguration
import jp.metamaps.positioning.android.PositioningLifecycleStatus
import jp.metamaps.positioning.android.PositioningUpdate

/** Who starts positioning from the map UI. */
enum class MapViewPositioningPolicy {
    USER_INITIATED,
    HOST_CONTROLLED,
    DISABLED,
}

/**
 * When positioning starts.
 *
 * [MapViewPositioningPolicy] decides who handles a start request; this value only decides when a start
 * request happens.
 */
enum class MapViewPositioningStartTrigger {
    /** Starts only when the user taps the location button in the web UI. */
    USER_ACTION,

    /** Starts once the map is ready, requesting any required OS permission that has not been decided yet. */
    AUTOMATIC,

    /**
     * Starts at the same moment, but only when no additional OS dialog is needed. If a permission has not been
     * decided, positioning does not start; a denied optional motion permission does not prevent a BLE-only start.
     */
    AUTOMATIC_WHEN_AUTHORIZED,
}

/** How to retry when the map fails to load. */
enum class MetamapsMapViewRetryPolicy {
    AUTOMATIC,
    DISABLED,
}

/** Whether an external link goes to the SDK's default handling or is handled by the host app. */
enum class MetamapsExternalLinkDecision {
    OPEN_DEFAULT,
    HANDLED,
}

/**
 * Settings for [MetamapsMapView].
 *
 * @property mapSlug Public map slug configured in the Metamaps console.
 * @property baseUrl Origin that serves the web map and the positioning manifest. Leave the default
 *   unless Metamaps asks you to use another environment.
 * @property groupId Pins a specific map group. `null` uses the map's active group.
 * @property language Initial display language as a BCP 47-style tag. `null` follows the device.
 * @property initialFloorId Floor UUID shown first.
 * @property previewToken Short-lived token that previews an unpublished map.
 * @property positioningPolicy Who handles positioning requests from the web UI.
 * @property positioningStartTrigger When positioning starts. The default starts automatically only on
 *   devices that can start without showing a new OS permission dialog.
 * @property showsBeaconDiagnostics Shows registered-beacon diagnostics on the map and emits
 *   [MetamapsMapViewEvent.BeaconSignals].
 * @property additionalQuery Extra public query parameters appended to the initial map URL.
 * @property retryPolicy Automatic retry policy for failed map loads.
 * @property userAgentAppendix App identifier appended after `Metamaps/<version>` in the user agent.
 * @property isWebViewInspectable Allows Chrome DevTools to inspect the WebView. Enable only in
 *   development builds.
 */
data class MetamapsMapViewConfiguration(
    val mapSlug: String,
    val baseUrl: URI = MetamapsSdk.PRODUCTION_BASE_URL,
    val groupId: UUID? = null,
    val language: String? = null,
    val initialFloorId: UUID? = null,
    val previewToken: String? = null,
    val positioningPolicy: MapViewPositioningPolicy = MapViewPositioningPolicy.USER_INITIATED,
    val positioningStartTrigger: MapViewPositioningStartTrigger =
        MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED,
    val showsBeaconDiagnostics: Boolean = false,
    val additionalQuery: Map<String, String> = emptyMap(),
    val retryPolicy: MetamapsMapViewRetryPolicy = MetamapsMapViewRetryPolicy.AUTOMATIC,
    val userAgentAppendix: String? = null,
    val isWebViewInspectable: Boolean = false,
) {
    /** Options set by Metamaps tooling through [withInternalOptions]. */
    @MetamapsInternalApi
    var internalOptions: MetamapsInternalOptions = MetamapsInternalOptions()
        private set

    /** Returns a copy that carries [options]. */
    @MetamapsInternalApi
    fun withInternalOptions(options: MetamapsInternalOptions): MetamapsMapViewConfiguration =
        copy().also { it.internalOptions = options }

    internal val positioningConfiguration: PositioningConfiguration
        get() = PositioningConfiguration(
            baseUrl = baseUrl,
            mapSlug = mapSlug,
            groupId = groupId,
            beaconDiagnosticsEnabled = effectiveBeaconDiagnostics,
        ).withInternalOptions(internalOptions)

    internal val effectiveBeaconDiagnostics: Boolean
        get() = showsBeaconDiagnostics || internalOptions.bleTest

    /** Validates the configuration and throws [IllegalArgumentException] for an invalid value. */
    fun validate() {
        positioningConfiguration.validate()
        language?.let {
            require(Regex("[A-Za-z0-9_-]{1,35}").matches(it)) {
                "language must be a BCP 47-style language tag"
            }
        }
        previewToken?.let {
            require(it.isNotEmpty() && it.length <= 2048 && Regex("[A-Za-z0-9._-]+").matches(it)) {
                "previewToken contains unsupported characters"
            }
        }
    }
}

internal fun buildMetamapsMapUrl(config: MetamapsMapViewConfiguration): URI {
    val root = config.baseUrl.toString().trimEnd('/')
    val encodedSlug = URLEncoder.encode(config.mapSlug, StandardCharsets.UTF_8.name()).replace("+", "%20")
    val fixedValues = mapOf(
        "group" to config.groupId?.toString()?.lowercase(),
        "lang" to config.language,
        "floor" to config.initialFloorId?.toString()?.lowercase(),
        "preview" to config.previewToken,
        "bletest" to if (config.internalOptions.bleTest) "1" else null,
    )
    val fixedKeys = listOf("group", "lang", "floor", "preview", "bletest")
    val query = buildList {
        fixedKeys.forEach { key ->
            (config.additionalQuery[key] ?: fixedValues[key])?.let { add(key to it) }
        }
        config.additionalQuery.keys
            .filterNot(fixedKeys::contains)
            .sorted()
            .forEach { key -> add(key to config.additionalQuery.getValue(key)) }
    }.joinToString("&") { (key, value) ->
        "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
    }
    return URI.create("$root/d/$encodedSlug${if (query.isEmpty()) "" else "?$query"}")
}

internal fun buildMetamapsUserAgent(
    defaultUserAgent: String,
    sdkVersion: String,
    appendix: String?,
): String = buildList {
    add(defaultUserAgent)
    add("Metamaps/$sdkVersion")
    appendix?.takeIf(String::isNotBlank)?.let(::add)
}.joinToString(" ")

internal fun isAllowedExternalLinkScheme(uri: URI): Boolean =
    uri.scheme?.lowercase() in setOf("http", "https", "tel", "mailto", "sms", "geo")

internal fun isValidMetamapsSpotStableKey(value: String): Boolean =
    Regex("[A-Za-z0-9_-]{1,128}").matches(value)

private fun encodeQueryComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

/** The current load state of the embedded map. */
enum class MapViewLoadState {
    IDLE,
    LOADING,
    LOADED,
    FAILED,
    DISPOSED,
}

/** Information sent when the web map starts accepting map view commands. */
data class MapReadyEvent(
    val mapId: UUID,
    val groupId: UUID,
    val configRevision: String?,
    val manifestRevision: Int?,
)

/** Information about a change of the displayed floor. */
data class FloorChangedEvent(
    val floorId: UUID?,
    val source: String?,
)

/** The candidate coordinate on the selected floor directly under the center reticle. */
data class MapCenterReticleCandidate(
    val floorId: UUID,
    val longitude: Double,
    val latitude: Double,
    /** Manifest-local coordinates, when the validated positioning manifest contains the same floor. */
    val local: LocalPosition?,
)

/** Information about a spot selected on the map. */
data class SpotSelectedEvent(
    /** Public spots.v2 StableKey (for example 6Y6SSBY5; issued spot_ values remain valid), not the database UUID. */
    val spotId: String?,
)

/** Information about a change in the route destination and whether routing is active. */
data class RouteChangedEvent(
    /** Public spots.v2 StableKey. */
    val destinationSpotId: String?,
    val active: Boolean,
)

/** Events MetamapsMapView sends to the host app. */
sealed interface MetamapsMapViewEvent {
    data class Ready(val event: MapReadyEvent) : MetamapsMapViewEvent
    data class LoadState(val state: MapViewLoadState) : MetamapsMapViewEvent
    data class PositioningStatus(val status: PositioningLifecycleStatus) : MetamapsMapViewEvent
    data class Capabilities(val report: CapabilityReport) : MetamapsMapViewEvent
    data class MotionHeading(val reading: MotionHeadingReading) : MetamapsMapViewEvent
    data class Position(val update: PositioningUpdate) : MetamapsMapViewEvent
    data class BeaconSignals(val readings: List<BeaconSignalReading>) : MetamapsMapViewEvent
    data object PositioningAuthorizationRequested : MetamapsMapViewEvent
    data object PositioningStartRequested : MetamapsMapViewEvent
    data class FloorChanged(val event: FloorChangedEvent) : MetamapsMapViewEvent
    data class SpotSelected(val event: SpotSelectedEvent) : MetamapsMapViewEvent
    data class RouteChanged(val event: RouteChangedEvent) : MetamapsMapViewEvent
    data class ExternalLinkRequested(val uri: URI) : MetamapsMapViewEvent
    data class Error(val error: MetamapsError) : MetamapsMapViewEvent
}
