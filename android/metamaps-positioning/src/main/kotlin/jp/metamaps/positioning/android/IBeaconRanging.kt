package jp.metamaps.positioning.android

import java.nio.ByteBuffer
import java.util.UUID
import jp.metamaps.positioning.BeaconObservation

/**
 * An iBeacon advertisement read from Apple's manufacturer-specific data (company ID `0x004C`).
 *
 * The data after the company ID is `0x02` (iBeacon), `0x15` (21 bytes follow), the 16-byte proximity UUID, the
 * big-endian major and minor, and the measured power.
 */
internal data class IBeaconAdvertisement(val uuid: UUID, val major: Int, val minor: Int) {
    companion object {
        const val APPLE_COMPANY_ID = 0x004C
        private const val TYPE = 0x02
        private const val REMAINING_LENGTH = 0x15
        private const val UUID_OFFSET = 2
        private const val MAJOR_OFFSET = 18
        private const val MINOR_OFFSET = 20
        private const val DATA_LENGTH = 23

        /** Returns `null` for manufacturer data that is not an iBeacon. */
        fun parse(manufacturerData: ByteArray?): IBeaconAdvertisement? {
            val data = manufacturerData ?: return null
            if (data.size < DATA_LENGTH || data.unsigned(0) != TYPE || data.unsigned(1) != REMAINING_LENGTH) return null
            val uuid = ByteBuffer.wrap(data, UUID_OFFSET, 16).let { UUID(it.long, it.long) }
            return IBeaconAdvertisement(uuid, data.uint16(MAJOR_OFFSET), data.uint16(MINOR_OFFSET))
        }

        /**
         * The manufacturer data that a scan filter matches for iBeacons of [uuid]: the type, the length, and the UUID.
         * Use it with an all-ones mask of the same length.
         */
        fun filterPrefix(uuid: UUID): ByteArray =
            ByteBuffer.allocate(UUID_OFFSET + 16)
                .put(TYPE.toByte())
                .put(REMAINING_LENGTH.toByte())
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()

        private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xFF

        private fun ByteArray.uint16(index: Int): Int = (unsigned(index) shl 8) or unsigned(index + 1)
    }
}

/**
 * Collects the iBeacon packets of one ranging window and summarizes each beacon once (14 §6.1).
 *
 * A beacon seen in the window reports the RSSI of its last packet and the number of packets, as the Android Beacon
 * Library's ranging cycle did. Beacons outside [activeKeys] (`uuid/major`, lowercase) and RSSI values outside
 * -127..-1 dBm are not counted.
 */
internal class IBeaconRangingWindow(private val activeKeys: Set<String>) {
    private class Sample(val advertisement: IBeaconAdvertisement, var rssiDbm: Int, var packets: Int)

    private val samples = LinkedHashMap<String, Sample>()

    fun add(advertisement: IBeaconAdvertisement, rssiDbm: Int) {
        val uuid = advertisement.uuid.toString().lowercase()
        if ("$uuid/${advertisement.major}" !in activeKeys || rssiDbm !in -127..-1) return
        val key = "$uuid/${advertisement.major}/${advertisement.minor}"
        val sample = samples.getOrPut(key) { Sample(advertisement, rssiDbm, 0) }
        sample.rssiDbm = rssiDbm
        sample.packets += 1
    }

    /** Returns the observations of the window from [windowStartMs] to [nowMs] and starts the next window. */
    fun drain(windowStartMs: Long, nowMs: Long): List<BeaconObservation> {
        val observations = samples.map { (key, sample) ->
            BeaconObservation(
                beaconKey = key,
                uuid = sample.advertisement.uuid.toString().lowercase(),
                major = sample.advertisement.major,
                minor = sample.advertisement.minor,
                rssiDbm = sample.rssiDbm.toDouble(),
                windowStartMonotonicTimestampMs = windowStartMs,
                monotonicTimestampMs = nowMs,
                source = OBSERVATION_SOURCE,
                duplicateCount = sample.packets,
                rssiSigmaDb = null,
            )
        }
        samples.clear()
        return observations
    }

    companion object {
        const val OBSERVATION_SOURCE = "android_ble_scanner"
    }
}
