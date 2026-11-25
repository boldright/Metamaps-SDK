package jp.metamaps.positioning.android

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IBeaconRangingTests {
    private val uuid = UUID.fromString("fda50693-a4e2-4fb1-afcf-c6eb07647825")

    /**
     * The manufacturer data after Apple's company ID, as `ScanRecord.getManufacturerSpecificData(0x004C)` returns for
     * an iBeacon with [uuid] `fda50693-a4e2-4fb1-afcf-c6eb07647825`.
     */
    private fun iBeacon(major: Int, minor: Int): ByteArray =
        byteArrayOf(0x02, 0x15) +
            "fda50693a4e24fb1afcfc6eb07647825".chunked(2).map { it.toInt(16).toByte() } +
            byteArrayOf((major shr 8).toByte(), major.toByte(), (minor shr 8).toByte(), minor.toByte(), (-59).toByte())

    @Test
    fun readsTheUuidAndBigEndianMajorAndMinor() {
        val advertisement = IBeaconAdvertisement.parse(iBeacon(major = 0x0102, minor = 0xFFFE))

        assertEquals(IBeaconAdvertisement(uuid, 0x0102, 0xFFFE), advertisement)
    }

    @Test
    fun ignoresManufacturerDataThatIsNotAnIBeacon() {
        val valid = iBeacon(major = 1, minor = 2)

        assertNull(IBeaconAdvertisement.parse(null))
        assertNull(IBeaconAdvertisement.parse(valid.copyOf(valid.size - 1)))
        assertNull(IBeaconAdvertisement.parse(valid.copyOf().also { it[0] = 0x10 }))
        assertNull(IBeaconAdvertisement.parse(valid.copyOf().also { it[1] = 0x14 }))
    }

    @Test
    fun theScanFilterMatchesTheBytesThatParsingReads() {
        val prefix = IBeaconAdvertisement.filterPrefix(uuid)

        assertContentEquals(prefix, iBeacon(major = 7, minor = 8).copyOf(prefix.size))
    }

    @Test
    fun summarizesEachBeaconOncePerWindowWithItsLastRssiAndPacketCount() {
        val window = IBeaconRangingWindow(setOf("$uuid/100"))
        val beacon = IBeaconAdvertisement(uuid, 100, 1)

        window.add(beacon, -70)
        window.add(beacon, -65)
        window.add(IBeaconAdvertisement(uuid, 100, 2), -80)
        val observations = window.drain(windowStartMs = 1_000, nowMs = 2_100)

        assertEquals(listOf("$uuid/100/1", "$uuid/100/2"), observations.map { it.beaconKey })
        assertEquals(-65.0, observations[0].rssiDbm)
        assertEquals(2, observations[0].duplicateCount)
        assertEquals(1_000, observations[0].windowStartMonotonicTimestampMs)
        assertEquals(2_100, observations[0].monotonicTimestampMs)
        assertTrue(window.drain(windowStartMs = 2_100, nowMs = 3_200).isEmpty(), "a beacon not seen again is not repeated")
    }

    @Test
    fun countsOnlyRegisteredMajorsAndValidRssi() {
        val window = IBeaconRangingWindow(setOf("$uuid/100"))

        window.add(IBeaconAdvertisement(uuid, 101, 1), -60)
        window.add(IBeaconAdvertisement(UUID.randomUUID(), 100, 1), -60)
        window.add(IBeaconAdvertisement(uuid, 100, 1), 0)
        window.add(IBeaconAdvertisement(uuid, 100, 1), 127)

        assertTrue(window.drain(windowStartMs = 0, nowMs = 1_100).isEmpty())
    }
}
