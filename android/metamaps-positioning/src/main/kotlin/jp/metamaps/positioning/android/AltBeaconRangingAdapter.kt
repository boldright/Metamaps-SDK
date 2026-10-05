package jp.metamaps.positioning.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsUserAction
import jp.metamaps.positioning.BeaconObservation
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.BleNotAvailableException
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.RangeNotifier
import org.altbeacon.beacon.Region

/** iBeacon ranging backed by the Android Beacon Library. */
@MetamapsInternalApi
class AltBeaconRangingAdapter(
    context: Context,
) : BeaconRangingAdapter {
    private val appContext = context.applicationContext
    private val manager = BeaconManager.getInstanceForApplication(appContext)
    private val activeRegions = mutableListOf<Region>()
    private var activeKeys = emptySet<String>()
    private var notifier: RangeNotifier? = null

    override val snapshot: BeaconAdapterSnapshot
        get() {
            val supported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            val enabled = try {
                val bluetooth = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bluetooth?.adapter?.isEnabled == true
            } catch (_: SecurityException) {
                false
            }
            return BeaconAdapterSnapshot(
                authorization = PermissionRequirements.locationState(appContext),
                bluetoothScan = PermissionRequirements.bluetoothScanState(appContext),
                servicesEnabled = enabled,
                rangingAvailable = supported,
                bleSupported = supported,
            )
        }

    override fun start(
        constraints: List<BeaconScanConstraint>,
        onObservations: (List<BeaconObservation>) -> Unit,
        onError: (MetamapsError) -> Unit,
    ) {
        if (constraints.isEmpty()) {
            throw MetamapsError(
                MetamapsError.Code.NO_REGISTERED_BEACONS,
                "The manifest has no valid iBeacon scan constraints.",
                false,
                MetamapsUserAction.SELECT_LOCATION_MANUALLY,
            )
        }
        stop()
        val state = snapshot
        if (!state.bleSupported || !state.rangingAvailable) {
            throw MetamapsError(
                MetamapsError.Code.BLE_UNSUPPORTED,
                "Bluetooth LE is unavailable on this device.",
                false,
                MetamapsUserAction.SELECT_LOCATION_MANUALLY,
            )
        }
        if (!state.servicesEnabled) {
            throw MetamapsError(
                MetamapsError.Code.BLUETOOTH_DISABLED,
                "Bluetooth is disabled.",
                true,
                MetamapsUserAction.ENABLE_BLUETOOTH,
            )
        }
        if (!hasBlePermissions()) {
            throw MetamapsError(
                MetamapsError.Code.BLUETOOTH_PERMISSION_DENIED,
                "Bluetooth scan and precise location permissions are required.",
                true,
                MetamapsUserAction.REQUEST_PERMISSION,
            )
        }
        val scannerAvailable = try {
            val bluetooth = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetooth?.adapter?.bluetoothLeScanner != null
        } catch (error: SecurityException) {
            throw MetamapsError(
                MetamapsError.Code.BLUETOOTH_PERMISSION_DENIED,
                "Bluetooth scan permission was denied.",
                true,
                MetamapsUserAction.REQUEST_PERMISSION,
                error.message,
            )
        }
        if (!scannerAvailable) {
            throw MetamapsError(
                MetamapsError.Code.SCAN_START_FAILED,
                "Android did not provide a Bluetooth LE scanner.",
                true,
                MetamapsUserAction.RETRY,
                "bluetoothLeScanner=null",
            )
        }

        installIBeaconParser()
        @Suppress("DEPRECATION")
        manager.setEnableScheduledScanJobs(false)
        activeKeys = constraints.map { "${it.uuid.toString().lowercase()}/${it.major}" }.toSet()
        val rangeNotifier = RangeNotifier { beacons, region ->
            try {
                val now = MonotonicClock.nowMilliseconds()
                val windowStart = (now - manager.foregroundScanPeriod).coerceAtLeast(0)
                val observations = beacons.mapNotNull { beacon ->
                    val uuid = beacon.id1?.toString()?.lowercase() ?: return@mapNotNull null
                    val major = beacon.id2?.toInt() ?: return@mapNotNull null
                    val minor = beacon.id3?.toInt() ?: return@mapNotNull null
                    if ("$uuid/$major" !in activeKeys || beacon.rssi !in -127..-1) return@mapNotNull null
                    BeaconObservation(
                        beaconKey = "$uuid/$major/$minor",
                        uuid = uuid,
                        major = major,
                        minor = minor,
                        rssiDbm = beacon.rssi.toDouble(),
                        windowStartMonotonicTimestampMs = windowStart,
                        monotonicTimestampMs = now,
                        source = "android_beacon_library",
                        duplicateCount = beacon.packetCount.coerceAtLeast(1),
                        rssiSigmaDb = null,
                    )
                }
                if (observations.isNotEmpty()) onObservations(observations)
            } catch (error: Throwable) {
                onError(scanRuntimeError(error))
            }
        }
        notifier = rangeNotifier
        manager.addRangeNotifier(rangeNotifier)
        manager.foregroundScanPeriod = 1_100
        manager.foregroundBetweenScanPeriod = 0
        try {
            if (!manager.checkAvailability()) {
                throw MetamapsError(
                    MetamapsError.Code.BLUETOOTH_DISABLED,
                    "Bluetooth is disabled.",
                    true,
                    MetamapsUserAction.ENABLE_BLUETOOTH,
                )
            }
            constraints.distinct().forEach { constraint ->
                val region = Region(
                    "metamap:${constraint.uuid}:${constraint.major}",
                    Identifier.parse(constraint.uuid.toString()),
                    Identifier.fromInt(constraint.major),
                    null,
                )
                manager.startRangingBeacons(region)
                activeRegions += region
            }
        } catch (error: MetamapsError) {
            stop()
            throw error
        } catch (error: BleNotAvailableException) {
            stop()
            throw MetamapsError(
                MetamapsError.Code.BLE_UNSUPPORTED,
                "Bluetooth LE is unavailable on this device.",
                false,
                MetamapsUserAction.SELECT_LOCATION_MANUALLY,
                error.message,
            )
        } catch (error: SecurityException) {
            stop()
            throw MetamapsError(
                MetamapsError.Code.BLUETOOTH_PERMISSION_DENIED,
                "Bluetooth scan permission was denied.",
                true,
                MetamapsUserAction.REQUEST_PERMISSION,
                error.message,
            )
        } catch (error: Throwable) {
            stop()
            throw MetamapsError(
                MetamapsError.Code.SCAN_START_FAILED,
                "iBeacon ranging could not start.",
                true,
                MetamapsUserAction.RETRY,
                error.toString(),
            )
        }
    }

    override fun stop() {
        activeRegions.toList().forEach { region ->
            runCatching { manager.stopRangingBeacons(region) }
        }
        activeRegions.clear()
        notifier?.let(manager::removeRangeNotifier)
        notifier = null
        activeKeys = emptySet()
    }

    private fun installIBeaconParser() {
        if (manager.beaconParsers.none { it.layout == IBEACON_LAYOUT }) {
            manager.beaconParsers.add(BeaconParser(PARSER_ID).setBeaconLayout(IBEACON_LAYOUT))
        }
    }

    private fun hasBlePermissions(): Boolean {
        if (!PermissionRequirements.isGranted(appContext, Manifest.permission.ACCESS_FINE_LOCATION)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            PermissionRequirements.isGranted(appContext, Manifest.permission.BLUETOOTH_SCAN) &&
            PermissionRequirements.isGranted(appContext, Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun scanRuntimeError(error: Throwable) = MetamapsError(
        MetamapsError.Code.SCAN_RUNTIME_FAILED,
        "iBeacon ranging failed while running.",
        true,
        MetamapsUserAction.RETRY,
        error.toString(),
    )

    private companion object {
        const val PARSER_ID = "metamaps-ibeacon"
        const val IBEACON_LAYOUT = "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"
    }
}
