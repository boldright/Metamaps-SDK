package jp.metamaps.flutter

import jp.metamaps.MetamapsError
import jp.metamaps.mapview.FloorChangedEvent
import jp.metamaps.mapview.MapReadyEvent
import jp.metamaps.mapview.MapViewLoadState
import jp.metamaps.mapview.MetamapsMapViewEvent
import jp.metamaps.mapview.RouteChangedEvent
import jp.metamaps.mapview.SpotSelectedEvent
import jp.metamaps.positioning.LocalPosition
import jp.metamaps.positioning.PositionEstimate
import jp.metamaps.positioning.Wgs84Position
import jp.metamaps.positioning.android.BeaconSignalReading
import jp.metamaps.positioning.android.CapabilityReport
import jp.metamaps.positioning.android.MetamapsPositioningEvent
import jp.metamaps.positioning.android.MotionHeadingReading
import jp.metamaps.positioning.android.PositioningUpdate

internal fun CapabilityReport.toFlutter(): NativeCapabilityReport = NativeCapabilityReport(
    platform = platform,
    osVersion = osVersion,
    sdkVersion = sdkVersion,
    ble = NativeBleCapabilities(
        supported = ble.supported,
        enabled = ble.enabled,
        rangingAvailable = ble.rangingAvailable,
        foregroundScan = ble.foregroundScan,
        backgroundScan = ble.backgroundScan,
        directionFinding = ble.directionFinding,
        channelSounding = ble.channelSounding,
    ),
    authorization = NativeAuthorizationCapabilities(
        location = authorization.location.wireValue,
        preciseLocation = authorization.preciseLocation,
        bluetoothScan = authorization.bluetoothScan,
        motion = authorization.motion.wireValue,
    ),
    sensors = NativeSensorCapabilities(
        stepDetector = sensors.stepDetector,
        rotationVector = sensors.rotationVector,
        gyroscope = sensors.gyroscope,
        magnetometer = sensors.magnetometer,
        barometer = sensors.barometer,
    ),
    selectedProfile = selectedProfile,
)

internal fun PositioningUpdate.toFlutter(): NativePositioningUpdate = NativePositioningUpdate(
    mapId = mapId.toString().lowercase(),
    groupId = groupId.toString().lowercase(),
    estimate = estimate.toFlutter(),
    wgs84 = wgs84?.let {
        NativeWgs84Position(
            longitude = it.longitude,
            latitude = it.latitude,
            elevationM = it.elevationM,
        )
    },
)

private fun LocalPosition.toFlutter(): NativeLocalPosition = NativeLocalPosition(x, y, z)

private fun Wgs84Position.toFlutter(): NativeWgs84Position =
    NativeWgs84Position(longitude, latitude, elevationM)

private fun MotionHeadingReading.toFlutter(): NativeMotionHeadingReading =
    NativeMotionHeadingReading(localHeadingDeg, magneticFieldAccuracy)

private fun BeaconSignalReading.toFlutter(): NativeBeaconSignalReading = NativeBeaconSignalReading(
    beaconId = beaconId.toString().lowercase(),
    uuid = uuid,
    major = major.toLong(),
    minor = minor.toLong(),
    floorId = floorId.toString().lowercase(),
    rssiDbm = rssiDbm,
    smoothedRssiDbm = smoothedRssiDbm,
    radioDistanceM = radioDistanceM,
    configuredPosition = configuredPosition.toFlutter(),
    configuredWgs84 = configuredWgs84.toFlutter(),
    monotonicTimestampMs = monotonicTimestampMs,
    devicePosition = devicePosition?.toFlutter(),
    deviceWgs84 = deviceWgs84?.toFlutter(),
    configuredDistanceM = configuredDistanceM,
    distanceDeltaM = distanceDeltaM,
)

private fun PositionEstimate.toFlutter(): NativePositionEstimate = NativePositionEstimate(
    sequence = sequence,
    monotonicTimestampMs = monotonicTimestampMs,
    status = status,
    mode = mode,
    floorId = floorId,
    floorProbability = floorProbability,
    local = local?.let {
        NativeLocalPosition(
            x = it.x,
            y = it.y,
            z = it.z,
        )
    },
    accuracyRadiusM = accuracyRadiusM,
    headingDeg = headingDeg,
    headingAccuracyDeg = headingAccuracyDeg,
    speedMps = speedMps,
    freshBeaconCount = freshBeaconCount.toLong(),
    lastBleAgeMs = lastBleAgeMs,
    manifestRevision = manifestRevision.toLong(),
    algorithmVersion = algorithmVersion,
    stale = stale,
    diagnosticFlags = diagnosticFlags,
)

internal fun Throwable.toFlutterError(): NativeErrorMessage {
    // The Android SDK rejects invalid arguments, such as a malformed spot key or language tag, with
    // IllegalArgumentException. iOS reports the same case as configurationInvalid.
    val typed = this as? MetamapsError
        ?: (this as? IllegalArgumentException)?.let {
            MetamapsError.invalidConfiguration(it.message ?: it.toString())
        }
    return if (typed != null) {
        NativeErrorMessage(
            code = typed.code.wireValue,
            message = typed.message,
            recoverable = typed.recoverable,
            userAction = typed.userAction.wireValue,
            debugDetail = typed.debugDetail,
        )
    } else {
        NativeErrorMessage(
            code = "internalInvariantViolation",
            message = "The native positioning integration failed.",
            recoverable = false,
            userAction = "retry",
            debugDetail = toString(),
        )
    }
}

internal fun MetamapsPositioningEvent.toFlutter(clientId: Long): NativePositioningEvent = when (this) {
    is MetamapsPositioningEvent.Position -> NativePositioningEvent(
        clientId = clientId,
        type = "position",
        update = update.toFlutter(),
    )
    is MetamapsPositioningEvent.BeaconSignals -> NativePositioningEvent(
        clientId = clientId,
        type = "beaconSignals",
        beaconSignals = readings.map { it.toFlutter() },
    )
    is MetamapsPositioningEvent.MotionHeading -> NativePositioningEvent(
        clientId = clientId,
        type = "motionHeading",
        motionHeading = reading.toFlutter(),
    )
    is MetamapsPositioningEvent.Status -> NativePositioningEvent(
        clientId = clientId,
        type = "status",
        status = status.wireValue,
    )
    is MetamapsPositioningEvent.Capabilities -> NativePositioningEvent(
        clientId = clientId,
        type = "capabilities",
        capabilities = report.toFlutter(),
    )
    is MetamapsPositioningEvent.Error -> NativePositioningEvent(
        clientId = clientId,
        type = "error",
        error = error.toFlutterError(),
    )
}

internal fun MetamapsMapViewEvent.toFlutter(viewId: Long): NativeMapViewEvent = when (this) {
    is MetamapsMapViewEvent.Ready -> NativeMapViewEvent(
        viewId = viewId,
        type = "ready",
        ready = event.toFlutter(),
    )
    is MetamapsMapViewEvent.LoadState -> NativeMapViewEvent(
        viewId = viewId,
        type = "loadState",
        loadState = state.wireValue,
    )
    is MetamapsMapViewEvent.PositioningStatus -> NativeMapViewEvent(
        viewId = viewId,
        type = "positioningStatus",
        positioningStatus = status.wireValue,
    )
    is MetamapsMapViewEvent.Capabilities -> NativeMapViewEvent(
        viewId = viewId,
        type = "capabilities",
        capabilities = report.toFlutter(),
    )
    is MetamapsMapViewEvent.MotionHeading -> NativeMapViewEvent(
        viewId = viewId,
        type = "motionHeading",
        motionHeading = reading.toFlutter(),
    )
    is MetamapsMapViewEvent.Position -> NativeMapViewEvent(
        viewId = viewId,
        type = "position",
        position = update.toFlutter(),
    )
    is MetamapsMapViewEvent.BeaconSignals -> NativeMapViewEvent(
        viewId = viewId,
        type = "beaconSignals",
        beaconSignals = readings.map { it.toFlutter() },
    )
    MetamapsMapViewEvent.PositioningAuthorizationRequested -> NativeMapViewEvent(
        viewId = viewId,
        type = "positioningAuthorizationRequested",
    )
    MetamapsMapViewEvent.PositioningStartRequested -> NativeMapViewEvent(
        viewId = viewId,
        type = "positioningStartRequested",
    )
    is MetamapsMapViewEvent.FloorChanged -> NativeMapViewEvent(
        viewId = viewId,
        type = "floorChanged",
        floorChanged = event.toFlutter(),
    )
    is MetamapsMapViewEvent.SpotSelected -> NativeMapViewEvent(
        viewId = viewId,
        type = "spotSelected",
        spotSelected = event.toFlutter(),
    )
    is MetamapsMapViewEvent.RouteChanged -> NativeMapViewEvent(
        viewId = viewId,
        type = "routeChanged",
        routeChanged = event.toFlutter(),
    )
    is MetamapsMapViewEvent.ExternalLinkRequested -> NativeMapViewEvent(
        viewId = viewId,
        type = "externalLinkRequested",
        externalUrl = uri.toString(),
    )
    is MetamapsMapViewEvent.Error -> NativeMapViewEvent(
        viewId = viewId,
        type = "error",
        error = error.toFlutterError(),
    )
}

private val MapViewLoadState.wireValue: String
    get() = name.lowercase()

private fun MapReadyEvent.toFlutter(): NativeMapReadyEvent = NativeMapReadyEvent(
    mapId = mapId.toString().lowercase(),
    groupId = groupId.toString().lowercase(),
    configRevision = configRevision,
    manifestRevision = manifestRevision?.toLong(),
)

private fun FloorChangedEvent.toFlutter(): NativeFloorChangedEvent = NativeFloorChangedEvent(
    floorId = floorId?.toString()?.lowercase(),
    source = source,
)

private fun SpotSelectedEvent.toFlutter(): NativeSpotSelectedEvent = NativeSpotSelectedEvent(
    spotId = spotId,
)

private fun RouteChangedEvent.toFlutter(): NativeRouteChangedEvent = NativeRouteChangedEvent(
    destinationSpotId = destinationSpotId,
    active = active,
)
