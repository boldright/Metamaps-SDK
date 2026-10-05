@file:OptIn(MetamapInternalApi::class)

package jp.metamaps.positioning.android

import jp.metamaps.positioning.BeaconObservation
import jp.metamaps.positioning.CoordinateFrame
import jp.metamaps.positioning.LocalPosition
import jp.metamaps.positioning.ManifestChecksum
import jp.metamaps.positioning.PositioningCoordinateTransform
import jp.metamaps.positioning.PositioningEstimator
import jp.metamaps.positioning.PositioningManifest
import jp.metamaps.positioning.ReplayEvent
import jp.metamaps.positioning.Wgs84Position
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractsAndSurveyTests {
    /**
     * The attitude host event stream stays at the same effective 10 Hz as iOS Core Motion. The requested sampling
     * period alone cannot guarantee this (60 ms / 16.7 Hz measured on a Pixel 8), so this pins the effective
     * period regardless of how fast the device delivers events.
     */
    @Test
    fun attitudeIsThrottledToTenHertzRegardlessOfDeviceCallbackRate() {
        // Reproduce the 60 ms interval measured on a Pixel 8 walk recording.
        val deviceIntervalMs = 60L
        val durationMs = 10_000L
        var lastEmitted: Long? = null
        val emitted = mutableListOf<Long>()
        var timestamp = 1_000L
        while (timestamp <= 1_000L + durationMs) {
            if (shouldEmitAttitude(lastEmitted, timestamp)) {
                lastEmitted = timestamp
                emitted += timestamp
            }
            timestamp += deviceIntervalMs
        }

        val intervals = emitted.zipWithNext { previous, next -> next - previous }
        assertTrue(
            intervals.all { it >= ATTITUDE_MIN_INTERVAL_MS },
            "attitude interval below 100ms: ${intervals.filter { it < ATTITUDE_MIN_INTERVAL_MS }}",
        )
        // With 60 ms steps, events never land exactly on 100 ms, so the interval becomes 120 ms, the closest value that does not exceed 10 Hz.
        val hertz = emitted.size * 1000.0 / durationMs
        assertTrue(hertz <= 10.0, "effective rate exceeded 10Hz: $hertz")
        assertTrue(hertz >= 8.0, "effective rate dropped too far below 10Hz: $hertz")
    }

    @Test
    fun attitudeThrottleAdmitsFirstEventAndRecoversFromBackwardTimestamps() {
        assertTrue(shouldEmitAttitude(null, 0))
        assertFalse(shouldEmitAttitude(1_000, 1_099))
        assertTrue(shouldEmitAttitude(1_000, 1_100))
        // A timestamp that goes backward is accepted as the new reference, so events are never dropped forever with the heading frozen.
        assertTrue(shouldEmitAttitude(1_000, 500))
    }

    @Test
    fun productionConfigurationRequiresSafeSlugAndOrigin() {
        PositioningConfiguration(mapSlug = "office-1").validate()
        assertFailsWith<IllegalArgumentException> {
            PositioningConfiguration(baseUrl = URI.create("http://example.com"), mapSlug = "office").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            PositioningConfiguration(mapSlug = "../office").validate()
        }
    }

    @Test
    fun bleTestEnablesAnIsolatedDiagnosticManifestMode() {
        val standard = PositioningConfiguration(mapSlug = "office")
        val explicit = PositioningConfiguration(
            mapSlug = "office",
            beaconDiagnosticsEnabled = true,
        )
        val bleTest = PositioningConfiguration(mapSlug = "office")
            .withInternalOptions(MetamapInternalOptions(bleTest = true))

        assertEquals(false, standard.effectiveBeaconDiagnostics)
        assertEquals(true, explicit.effectiveBeaconDiagnostics)
        assertEquals(true, bleTest.effectiveBeaconDiagnostics)
    }

    @Test
    fun compassHeadingUsesXEastZSouthFrame() {
        assertEquals(180.0, HeadingTransform.compassToLocal(0.0))
        assertEquals(90.0, HeadingTransform.compassToLocal(90.0))
        assertEquals(0.0, HeadingTransform.compassToLocal(180.0))
        assertEquals(270.0, HeadingTransform.compassToLocal(270.0))
    }

    @Test
    fun localOriginReturnsManifestWgs84Origin() {
        val position = PositioningCoordinateTransform.localToWgs84(
            LocalPosition(0.0, 3.5, 0.0),
            CoordinateFrame(listOf(139.0, 35.0), "x-east-y-up-z-south"),
        )
        assertEquals(139.0, position.longitude)
        assertEquals(35.0, position.latitude)
        assertEquals(3.5, position.elevationM)
    }

    @Test
    fun coordinateTransformPreservesElevationInBothDirections() {
        val frame = CoordinateFrame(listOf(139.0, 35.0), "x-east-y-up-z-south")
        val source = Wgs84Position(139.0001, 34.9999, 3.5)
        val local = PositioningCoordinateTransform.wgs84ToLocal(source, frame)
        val restored = PositioningCoordinateTransform.localToWgs84(local, frame)

        assertEquals(source.longitude, restored.longitude, 1e-10)
        assertEquals(source.latitude, restored.latitude, 1e-10)
        assertEquals(source.elevationM, restored.elevationM)
    }

    @Test
    fun beaconDiagnosticsUsesManifestPlacementAndCalibration() {
        val manifest = PositioningManifest.parse(
            signedManifest(
                generatedAt = "2026-07-23T00:00:00Z",
                expiresAt = "2027-07-23T00:00:00Z",
            ),
        )
        val observation = BeaconObservation(
            beaconKey = "fda50693-a4e2-4fb1-afcf-c6eb07647825/100/1",
            uuid = "fda50693-a4e2-4fb1-afcf-c6eb07647825",
            major = 100,
            minor = 1,
            rssiDbm = -59.0,
            windowStartMonotonicTimestampMs = 900,
            monotonicTimestampMs = 1_000,
            source = "android_beacon_library",
            duplicateCount = 1,
            rssiSigmaDb = null,
        )

        val reading = BeaconSignalDiagnostics()
            .readings(listOf(observation), manifest, latestUpdate = null)
            .single()

        assertEquals(1.0, reading.radioDistanceM, 1e-9)
        assertEquals(0.0, reading.configuredPosition.x, 1e-9)
        assertEquals(0.0, reading.configuredPosition.z, 1e-9)
        assertEquals(2.4, reading.configuredWgs84.elevationM)
        assertEquals(null, reading.configuredDistanceM)
    }

    @Test
    fun manifestValidationAcceptsAspNetOffsetTimestamp() {
        val repository = ManifestRepository(
            File("build/test-cache/manifest-offset"),
            nowMillis = { 1_784_764_800_000 },
        )
        val manifest = signedManifest(
            generatedAt = "2026-07-23T00:00:00+00:00",
            expiresAt = "2027-07-23T00:00:00+00:00",
        )

        val parsed = repository.validate(manifest, PositioningConfiguration(mapSlug = "office"))

        assertEquals("2026-07-23T00:00:00+00:00", parsed.generatedAt)
        PositioningEstimator(parsed, 1u)
    }

    @Test
    fun expiredManifestIsRejectedWithTypedError() {
        val repository = ManifestRepository(
            File("build/test-cache/manifest-expired"),
            nowMillis = { 1_784_764_800_000 },
        )
        val manifest = signedManifest(
            generatedAt = "2025-07-23T00:00:00Z",
            expiresAt = "2026-07-22T23:59:59Z",
        )

        val error = assertFailsWith<MetamapPositioningError> {
            repository.validate(manifest, PositioningConfiguration(mapSlug = "office"))
        }

        assertEquals(MetamapPositioningError.Code.MANIFEST_EXPIRED, error.code)
    }

    @Test
    fun expiredCacheIsStillUsedOfflineWithinMaxOfflineAge() {
        val now = 1_784_764_800_000L
        val directory = File("build/test-cache/manifest-offline-expired").apply {
            deleteRecursively()
            mkdirs()
        }
        File(directory, "office-active-active.json").writeText(
            signedManifest(generatedAt = "2026-07-14T00:00:00Z", expiresAt = "2026-07-21T00:00:00Z"),
        )
        File(directory, "office-active-active.properties").writeText("verifiedAtMillis=${now - 86_400_000L}\n")
        val repository = ManifestRepository(directory, nowMillis = { now }) { uri ->
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = throw IOException("offline")
                override fun getResponseCode(): Int = throw IOException("offline")
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        }

        val loaded = runBlocking { repository.load(PositioningConfiguration(mapSlug = "office")) }

        assertEquals(LoadedManifest.Source.OFFLINE_CACHE, loaded.source)
    }

    @Test
    fun manifestNotFoundBodyPreservesMapNotFound() {
        assertEquals(
            MetamapPositioningError.Code.MAP_NOT_FOUND,
            classifyManifestNotFound("map_not_found").code,
        )
    }

    @Test
    fun positioningManifestNotFoundBodyIsManifestUnavailable() {
        val classification = classifyManifestNotFound("positioning_manifest_not_found")

        assertEquals(MetamapPositioningError.Code.MANIFEST_UNAVAILABLE, classification.code)
        assertTrue("positioning_manifest_not_found" in classification.debugDetail)
    }

    @Test
    fun unparsableManifestNotFoundBodyIsManifestUnavailable() {
        val classification = classifyManifestNotFound(null)

        assertEquals(MetamapPositioningError.Code.MANIFEST_UNAVAILABLE, classification.code)
        assertTrue("unparsable body" in classification.debugDetail)
    }

    @Test
    fun replayEventDecodesPedometerDistance() {
        val event = ReplayEvent.parse(
            """{"type":"step","monotonicTimestampMs":100,"pedometerDistanceM":1.46}""",
        )

        assertEquals(1.46, event.pedometerDistanceM)
    }

    private fun signedManifest(generatedAt: String, expiresAt: String): String {
        val unsigned = """
            {
              "schema":"metamap.positioning-manifest.v1",
              "mapId":"11111111-1111-4111-8111-111111111111",
              "groupId":"22222222-2222-4222-8222-222222222222",
              "revision":1,
              "generatedAt":"$generatedAt",
              "expiresAt":"$expiresAt",
              "algorithm":{
                "version":"1.0.0",
                "parameters":{
                  "particleCount":128,
                  "defaultCalibratedRssiAt1mDbm":-59,
                  "defaultPathLossExponent":2,
                  "defaultRssiSigmaDb":5,
                  "receiverHeightM":1.2,
                  "stepLengthM":0.72,
                  "coastTimeoutMs":4000,
                  "outsideSoftMarginM":0.5,
                  "accuracyFloorM":1.2
                }
              },
              "coordinateFrame":{"origin":[139.7,35.68],"axis":"x-east-y-up-z-south"},
              "floors":[
                {"id":"33333333-3333-4333-8333-333333333333","label":"1F","elevationM":0}
              ],
              "connectors":[],
              "walkableGeometry":{
                "floors":[
                  {
                    "floorId":"33333333-3333-4333-8333-333333333333",
                    "walkablePolygons":[[[0,0],[10,0],[10,10],[0,10]]],
                    "walls":[],
                    "corridorCenterlines":[]
                  }
                ]
              },
              "beacons":[
                {
                  "id":"44444444-4444-4444-8444-444444444444",
                  "protocol":"ibeacon",
                  "proximityUuid":"fda50693-a4e2-4fb1-afcf-c6eb07647825",
                  "major":100,
                  "minor":1,
                  "floorId":"33333333-3333-4333-8333-333333333333",
                  "position":[139.7,35.68,2.4],
                  "mountingHeightM":2.4,
                  "powerMode":"battery_continuous",
                  "advertisingIntervalMs":500,
                  "advertisedMeasuredPowerDbm":-59,
                  "calibratedRssiAt1mDbm":-59,
                  "pathLossExponent":2,
                  "rssiSigmaDb":4.5,
                  "isEnabled":true
                }
              ],
              "checksum":"pending"
            }
        """.trimIndent()
        return unsigned.replace("\"pending\"", "\"${ManifestChecksum.compute(unsigned)}\"")
    }
}
