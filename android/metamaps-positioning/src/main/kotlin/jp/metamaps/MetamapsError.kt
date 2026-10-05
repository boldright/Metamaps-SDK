package jp.metamaps

import java.net.URI

/** The host app or user action needed to recover from an error. */
enum class MetamapsUserAction(val wireValue: String) {
    NONE("none"),
    CHECK_CONFIGURATION("checkConfiguration"),
    RETRY("retry"),
    REQUEST_PERMISSION("requestPermission"),
    ENABLE_BLUETOOTH("enableBluetooth"),
    ENABLE_PRECISE_LOCATION("enablePreciseLocation"),
    OPEN_APP_SETTINGS("openAppSettings"),
    SELECT_LOCATION_MANUALLY("selectLocationManually"),
}

/** A classifiable error returned by the Metamaps SDK, for both the map view and positioning. */
data class MetamapsError(
    val code: Code,
    override val message: String,
    val recoverable: Boolean,
    val userAction: MetamapsUserAction,
    val debugDetail: String? = null,
) : RuntimeException(message) {
    enum class Code(val wireValue: String) {
        CONFIGURATION_INVALID("configurationInvalid"),
        MAP_NOT_FOUND("mapNotFound"),
        MAP_ACCESS_DENIED("mapAccessDenied"),
        WEB_CONTENT_LOAD_FAILED("webContentLoadFailed"),
        MAP_OPERATION_FAILED("mapOperationFailed"),
        BRIDGE_HANDSHAKE_FAILED("bridgeHandshakeFailed"),
        BRIDGE_UNSUPPORTED("bridgeUnsupported"),
        BRIDGE_MAP_MISMATCH("bridgeMapMismatch"),
        MANIFEST_UNAVAILABLE("manifestUnavailable"),
        MANIFEST_EXPIRED("manifestExpired"),
        MANIFEST_UNSUPPORTED("manifestUnsupported"),
        LOCATION_PERMISSION_DENIED("locationPermissionDenied"),
        PRECISE_LOCATION_REQUIRED("preciseLocationRequired"),
        BLUETOOTH_PERMISSION_DENIED("bluetoothPermissionDenied"),
        BLUETOOTH_DISABLED("bluetoothDisabled"),
        BLE_UNSUPPORTED("bleUnsupported"),
        MOTION_PERMISSION_DENIED("motionPermissionDenied"),
        SCAN_START_FAILED("scanStartFailed"),
        SCAN_RUNTIME_FAILED("scanRuntimeFailed"),
        NO_REGISTERED_BEACONS("noRegisteredBeacons"),
        INSUFFICIENT_SIGNALS("insufficientSignals"),
        PAUSED_BACKGROUND("pausedBackground"),
        POSITION_LOST("positionLost"),
        BACKGROUND_MODE_UNAVAILABLE("backgroundModeUnavailable"),
        BACKGROUND_START_NOT_ALLOWED("backgroundStartNotAllowed"),
        BACKGROUND_INTERRUPTED("backgroundInterrupted"),
        INTERNAL_INVARIANT_VIOLATION("internalInvariantViolation"),
    }

    companion object {
        fun invalidConfiguration(detail: String) = MetamapsError(
            Code.CONFIGURATION_INVALID,
            "The Metamaps SDK configuration is invalid.",
            false,
            MetamapsUserAction.CHECK_CONFIGURATION,
            detail,
        )
    }
}

/** Version and endpoint information of the bundled Android SDK. */
object MetamapsSdk {
    const val VERSION = "0.6.0"

    /** The production origin for the map and the positioning manifest when no origin is specified. */
    val PRODUCTION_BASE_URL: URI = URI.create("https://metamaps.jp")
}
