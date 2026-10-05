package jp.metamaps.metamap_positioning

import jp.metamaps.positioning.PositionEstimate
import jp.metamaps.positioning.Wgs84Position
import jp.metamaps.positioning.android.BeaconSignalReading
import jp.metamaps.positioning.android.MetamapPositioningError
import jp.metamaps.positioning.android.MetamapPositioningEvent
import jp.metamaps.positioning.android.MotionHeadingReading
import jp.metamaps.positioning.android.PositioningUpdate
import jp.metamaps.positioning.android.PositioningUserAction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class NativeMappingsTest {
    @Test
    fun positioningUpdatePreservesCompleteEstimate() {
        val update = PositioningUpdate(
            mapId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            groupId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            estimate = PositionEstimate(
                sequence = 42,
                monotonicTimestampMs = 12_345,
                status = "tracking",
                mode = "ble_pdr_pf",
                floorId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                floorProbability = 0.94,
                local = null,
                accuracyRadiusM = 2.1,
                headingDeg = null,
                headingAccuracyDeg = null,
                speedMps = 1.2,
                freshBeaconCount = 7,
                lastBleAgeMs = 320,
                manifestRevision = 9,
                algorithmVersion = "1.0.0",
                stale = false,
                diagnosticFlags = listOf("motion_full"),
            ),
            wgs84 = null,
        ).toFlutter()

        assertEquals(42, update.estimate.sequence)
        assertEquals(12_345, update.estimate.monotonicTimestampMs)
        assertEquals("ble_pdr_pf", update.estimate.mode)
        assertEquals(7, update.estimate.freshBeaconCount)
        assertEquals(320, update.estimate.lastBleAgeMs)
        assertEquals(listOf("motion_full"), update.estimate.diagnosticFlags)
        assertNull(update.estimate.local)
        assertNull(update.wgs84)
    }

    @Test
    fun invalidArgumentMatchesTheIosConfigurationError() {
        val error = IllegalArgumentException("Invalid language tag").toFlutterError()

        assertEquals("configurationInvalid", error.code)
        assertEquals(false, error.recoverable)
        assertEquals("checkConfiguration", error.userAction)
        assertEquals("Invalid language tag", error.debugDetail)
    }

    @Test
    fun positioningErrorPreservesTypedRecoveryContract() {
        val error = MetamapPositioningError(
            code = MetamapPositioningError.Code.BLUETOOTH_DISABLED,
            message = "Bluetooth is disabled.",
            recoverable = true,
            userAction = PositioningUserAction.ENABLE_BLUETOOTH,
            debugDetail = "adapter=false",
        ).toFlutterError()

        assertEquals("bluetoothDisabled", error.code)
        assertEquals(true, error.recoverable)
        assertEquals("enableBluetooth", error.userAction)
        assertEquals("adapter=false", error.debugDetail)
    }

    @Test
    fun positionPreservesWgs84Elevation() {
        val update = PositioningUpdate(
            mapId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            groupId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            estimate = PositionEstimate(
                sequence = 1,
                monotonicTimestampMs = 2,
                status = "tracking",
                mode = "ble",
                floorId = null,
                floorProbability = 0.0,
                local = null,
                accuracyRadiusM = 10.0,
                headingDeg = null,
                headingAccuracyDeg = null,
                speedMps = 0.0,
                freshBeaconCount = 3,
                lastBleAgeMs = 50,
                manifestRevision = 4,
                algorithmVersion = "1.0.0",
                stale = false,
                diagnosticFlags = emptyList(),
            ),
            wgs84 = Wgs84Position(139.7, 35.6, 42.5),
        ).toFlutter()

        assertEquals(42.5, update.wgs84?.elevationM)
    }

    @Test
    fun diagnosticEventsPreserveMotionAndBeaconRelations() {
        val motion = MetamapPositioningEvent.MotionHeading(
            MotionHeadingReading(localHeadingDeg = 123.0, magneticFieldAccuracy = "high"),
        ).toFlutter(clientId = 8)
        assertEquals("motionHeading", motion.type)
        assertEquals(123.0, motion.motionHeading?.localHeadingDeg)
        assertEquals("high", motion.motionHeading?.magneticFieldAccuracy)

        val beaconId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val floorId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val beacon = MetamapPositioningEvent.BeaconSignals(
            listOf(
                BeaconSignalReading(
                    beaconId = beaconId,
                    uuid = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                    major = 1,
                    minor = 2,
                    floorId = floorId,
                    rssiDbm = -70.0,
                    smoothedRssiDbm = -71.0,
                    radioDistanceM = 2.5,
                    configuredPosition = jp.metamaps.positioning.LocalPosition(1.0, 2.0, 3.0),
                    configuredWgs84 = Wgs84Position(139.7, 35.6, 40.0),
                    devicePosition = null,
                    deviceWgs84 = null,
                    configuredDistanceM = 3.0,
                    distanceDeltaM = -0.5,
                    monotonicTimestampMs = 99,
                ),
            ),
        ).toFlutter(clientId = 8)

        assertEquals("beaconSignals", beacon.type)
        assertEquals(beaconId.toString(), beacon.beaconSignals?.single()?.beaconId)
        assertEquals(40.0, beacon.beaconSignals?.single()?.configuredWgs84?.elevationM)
    }
}
