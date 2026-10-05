@file:OptIn(MetamapInternalApi::class)

package jp.metamaps.mapview

import jp.metamaps.positioning.android.MetamapInternalApi
import jp.metamaps.positioning.android.MetamapPositioningError
import jp.metamaps.positioning.android.MotionHeadingReading
import jp.metamaps.positioning.android.PositioningUserAction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import jp.metamaps.positioning.android.MetamapInternalOptions

class BridgeProtocolTests {
    private val mapId = UUID.fromString("2dc9584e-e695-41ac-9111-ca36514df0a1")
    private val groupId = UUID.fromString("13addae1-88c2-4a4f-9764-948687bf567d")

    @Test
    fun handshakePinsMapGroupAndSequence() {
        val validator = BridgeValidator(groupId)
        val ready = validator.validate(
            BridgeEnvelope(
                BridgeEnvelope.SCHEMA,
                "1.0",
                "map.ready",
                mapId,
                groupId,
                1,
                mapOf("configRevision" to "42", "manifestRevision" to 7),
            ),
        )
        assertEquals(mapId, ready?.mapId)
        assertEquals(groupId, ready?.groupId)
        assertEquals(7, ready?.manifestRevision)
        assertFalse(validator.handshakeComplete)
        assertTrue(validator.completeHandshake(requireNotNull(ready)))
        assertTrue(validator.handshakeComplete)

        validator.validate(
            BridgeEnvelope(
                BridgeEnvelope.SCHEMA,
                "1.1",
                "map.floorChanged",
                mapId,
                groupId,
                2,
                emptyMap(),
            ),
        )
        val failure = assertFailsWith<MetamapPositioningError> {
            validator.validate(
                BridgeEnvelope(
                    BridgeEnvelope.SCHEMA,
                    "1.1",
                    "map.floorChanged",
                    mapId,
                    groupId,
                    2,
                    emptyMap(),
                ),
            )
        }
        assertEquals(MetamapPositioningError.Code.BRIDGE_HANDSHAKE_FAILED, failure.code)
        validator.reset()
        assertFalse(validator.handshakeComplete)
    }

    @Test
    fun rejectsAnotherGroupAndMalformedVersion() {
        val otherGroup = UUID.fromString("f536d79f-cdf2-4981-8c0b-b40ec3984311")
        assertEquals(
            MetamapPositioningError.Code.BRIDGE_MAP_MISMATCH,
            assertFailsWith<MetamapPositioningError> {
                BridgeValidator(groupId).validate(
                    BridgeEnvelope(
                        BridgeEnvelope.SCHEMA,
                        "1.0",
                        "map.ready",
                        mapId,
                        otherGroup,
                        1,
                        emptyMap(),
                    ),
                )
            }.code,
        )
        assertEquals(
            MetamapPositioningError.Code.BRIDGE_UNSUPPORTED,
            assertFailsWith<MetamapPositioningError> {
                BridgeValidator(groupId).validate(
                    BridgeEnvelope(
                        BridgeEnvelope.SCHEMA,
                        "1",
                        "map.ready",
                        mapId,
                        groupId,
                        1,
                        emptyMap(),
                    ),
                )
            }.code,
        )
    }

    @Test
    fun bleTestModeAddsDedicatedUrlFlagWithoutChangingStandardUrls() {
        val standard = buildMetamapMapUrl(
            MetamapMapViewConfiguration(mapSlug = "office", language = "ja"),
        )
        val bleTest = buildMetamapMapUrl(
            MetamapMapViewConfiguration(mapSlug = "office", language = "ja")
                .withInternalOptions(MetamapInternalOptions(bleTest = true)),
        )
        assertFalse(standard.query.orEmpty().split("&").contains("bletest=1"))
        assertTrue(bleTest.query.orEmpty().split("&").contains("bletest=1"))
        assertFalse(
            MetamapMapViewConfiguration(mapSlug = "office")
                .positioningConfiguration
                .internalOptions
                .bleTest,
        )
        assertTrue(bleTestConfiguration().positioningConfiguration.internalOptions.bleTest)
        assertTrue(bleTestConfiguration().positioningConfiguration.beaconDiagnosticsEnabled)
    }

    @Test
    fun beaconSignalBridgePayloadUsesTheSchemaArrayShape() {
        val payload: List<Map<String, Any?>> = emptyList<jp.metamaps.positioning.android.BeaconSignalReading>()
            .toBridgePayload()
        assertTrue(payload.isEmpty())
    }

    @Test
    fun mapErrorIsARecoverableOperationFailureWithTheRuntimeCode() {
        fun mapError(payload: Map<String, Any?>?) =
            BridgeEnvelope(BridgeEnvelope.SCHEMA, "1.4", "map.error", null, null, 1, payload)

        // Not a load failure: a host that reloads on WEB_CONTENT_LOAD_FAILED must not reload for an unknown spot.
        val error = mapError(
            mapOf("code" to "spot_not_found", "message" to "The requested map operation could not be completed."),
        ).mapOperationError
        assertEquals(MetamapPositioningError.Code.MAP_OPERATION_FAILED, error.code)
        assertTrue(error.recoverable)
        assertEquals(PositioningUserAction.NONE, error.userAction)
        assertEquals("map.error: spot_not_found", error.debugDetail)
        assertEquals("unknown", mapError(mapOf("code" to "<b>x</b>")).mapErrorCode)
        assertEquals("unknown", mapError(null).mapErrorCode)
    }

    @Test
    fun motionHeadingBridgePayloadKeepsLocalHeadingAndAccuracy() {
        assertEquals(
            mapOf(
                "localHeadingDeg" to 62.0,
                "magneticFieldAccuracy" to "high",
            ),
            MotionHeadingReading(62.0, "high").toPayload(),
        )
    }

    private fun bleTestConfiguration() = MetamapMapViewConfiguration(mapSlug = "office")
        .withInternalOptions(MetamapInternalOptions(bleTest = true))
}
