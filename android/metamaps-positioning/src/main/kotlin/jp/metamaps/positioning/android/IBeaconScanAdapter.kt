package jp.metamaps.positioning.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsUserAction
import jp.metamaps.positioning.BeaconObservation

/**
 * iBeacon ranging on Android's `BluetoothLeScanner` (14 §13.3).
 *
 * - The scan filter matches only the iBeacons of the manifest's proximity UUIDs, so the app does not receive the
 *   advertisements of the other Bluetooth devices around it.
 * - The scan restarts every [SCAN_RESTART_INTERVAL_MS]. Android moves a scan that has run longer than the Bluetooth
 *   stack's `scan_timeout_millis` (5 minutes on recent devices) to a mode in which beacons barely arrive. A restart
 *   resets that timer, and it stays far from the limit of 5 scan starts in 30 seconds.
 * - [IBeaconRangingWindow] summarizes the packets every [WINDOW_MS].
 */
@MetamapsInternalApi
class IBeaconScanAdapter(
    context: Context,
) : BeaconRangingAdapter {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var session: ScanSession? = null

    override val snapshot: BeaconAdapterSnapshot
        get() {
            val supported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            val enabled = try {
                bluetoothAdapter()?.isEnabled == true
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

    // The whole start runs under the lock, so a concurrent stop() either runs first or stops the new scan; it never
    // leaves a scan or a receiver behind. A second start() replaces the first one completely.
    override fun start(
        constraints: List<BeaconScanConstraint>,
        onObservations: (List<BeaconObservation>) -> Unit,
        onError: (MetamapsError) -> Unit,
    ): Unit = synchronized(lock) {
        session?.stop()
        session = null
        if (constraints.isEmpty()) {
            throw MetamapsError(
                MetamapsError.Code.NO_REGISTERED_BEACONS,
                "The manifest has no valid iBeacon scan constraints.",
                false,
                MetamapsUserAction.SELECT_LOCATION_MANUALLY,
            )
        }
        val state = snapshot
        if (!state.bleSupported || !state.rangingAvailable) {
            throw MetamapsError(
                MetamapsError.Code.BLE_UNSUPPORTED,
                "Bluetooth LE is unavailable on this device.",
                false,
                MetamapsUserAction.SELECT_LOCATION_MANUALLY,
            )
        }
        if (!state.servicesEnabled) throw bluetoothDisabled()
        if (!hasBlePermissions()) throw bluetoothPermissionDenied(null)
        val scanner = try {
            bluetoothAdapter()?.bluetoothLeScanner
        } catch (error: SecurityException) {
            throw bluetoothPermissionDenied(error.message)
        } ?: throw MetamapsError(
            MetamapsError.Code.SCAN_START_FAILED,
            "Android did not provide a Bluetooth LE scanner.",
            true,
            MetamapsUserAction.RETRY,
            "bluetoothLeScanner=null",
        )
        val next = ScanSession(scanner, constraints.distinct(), onObservations, onError)
        try {
            next.start()
        } catch (error: SecurityException) {
            next.stop()
            throw bluetoothPermissionDenied(error.message)
        } catch (error: Throwable) {
            next.stop()
            throw MetamapsError(
                MetamapsError.Code.SCAN_START_FAILED,
                "iBeacon ranging could not start.",
                true,
                MetamapsUserAction.RETRY,
                error.toString(),
            )
        }
        session = next
    }

    override fun stop() {
        synchronized(lock) {
            session?.stop()
            session = null
        }
    }

    /**
     * One scan from [start] to [stop]. Scan results, the window and restart timers, and the Bluetooth state receiver
     * run on the main looper, while [start] and [stop] can come from any thread. Every step changes the state only
     * while holding [lock] and checks [active] first. Callbacks run after the lock is released, so the client's own
     * locks never wait on this one. A callback can still race with a concurrent [stop], as the client's own coroutine
     * hand-off already can.
     */
    private inner class ScanSession(
        private var scanner: BluetoothLeScanner,
        constraints: List<BeaconScanConstraint>,
        private val onObservations: (List<BeaconObservation>) -> Unit,
        private val onError: (MetamapsError) -> Unit,
    ) {
        private val filters = constraints.map { it.uuid }.distinct().map { uuid ->
            val prefix = IBeaconAdvertisement.filterPrefix(uuid)
            ScanFilter.Builder()
                .setManufacturerData(IBeaconAdvertisement.APPLE_COMPANY_ID, prefix, ByteArray(prefix.size) { -1 })
                .build()
        }
        private val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        private val window = IBeaconRangingWindow(
            constraints.map { "${it.uuid.toString().lowercase()}/${it.major}" }.toSet(),
        )
        private var windowStartMs = 0L
        private var active = false

        private val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)

            override fun onScanFailed(errorCode: Int) {
                if (isActive()) onError(scanRuntimeError("onScanFailed errorCode=$errorCode"))
            }
        }

        private val flush = object : Runnable {
            override fun run() {
                val observations = synchronized(lock) {
                    if (!active) return
                    val now = MonotonicClock.nowMilliseconds()
                    handler.postDelayed(this, WINDOW_MS)
                    window.drain(windowStartMs, now).also { windowStartMs = now }
                }
                if (observations.isNotEmpty()) onObservations(observations)
            }
        }

        private val restart = object : Runnable {
            override fun run() = restartScan()
        }

        /** Turning Bluetooth off ends the scan. Start it again as soon as Bluetooth is back. */
        private val bluetoothState = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                    handler.post(restart)
                }
            }
        }

        // Callers check the permissions with hasBlePermissions(), and a revoked permission throws SecurityException.
        // The caller holds the lock.
        @SuppressLint("MissingPermission")
        fun start() {
            active = true
            // Register first: a Bluetooth OFF→ON while the scan starts must not be missed.
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(bluetoothState, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(bluetoothState, filter)
            }
            scanner.startScan(filters, settings, callback)
            windowStartMs = MonotonicClock.nowMilliseconds()
            handler.postDelayed(flush, WINDOW_MS)
            handler.postDelayed(restart, SCAN_RESTART_INTERVAL_MS)
        }

        /** The caller holds the lock. */
        @SuppressLint("MissingPermission")
        fun stop() {
            active = false
            handler.removeCallbacks(flush)
            handler.removeCallbacks(restart)
            runCatching { appContext.unregisterReceiver(bluetoothState) }
            runCatching { scanner.stopScan(callback) }
        }

        private fun isActive(): Boolean = synchronized(lock) { active }

        private fun record(result: ScanResult) {
            val advertisement = try {
                IBeaconAdvertisement.parse(
                    result.scanRecord?.getManufacturerSpecificData(IBeaconAdvertisement.APPLE_COMPANY_ID),
                ) ?: return
            } catch (error: Throwable) {
                if (isActive()) onError(scanRuntimeError(error.toString()))
                return
            }
            synchronized(lock) {
                if (active) window.add(advertisement, result.rssi)
            }
        }

        /** Restarts the scan and the 4-minute timer, from the timer or when Bluetooth is back on. */
        @SuppressLint("MissingPermission")
        private fun restartScan() {
            val failure = synchronized(lock) {
                if (!active) return
                handler.removeCallbacks(restart)
                handler.postDelayed(restart, SCAN_RESTART_INTERVAL_MS)
                runCatching { scanner.stopScan(callback) }
                try {
                    // While Bluetooth is off there is no scanner. The state receiver restarts the scan when it is back.
                    val current = bluetoothAdapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
                    scanner = current
                    current.startScan(filters, settings, callback)
                    null
                } catch (error: Throwable) {
                    error
                }
            } ?: return
            onError(
                if (failure is SecurityException) {
                    bluetoothPermissionDenied(failure.message)
                } else {
                    scanRuntimeError(failure.toString())
                },
            )
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasBlePermissions(): Boolean {
        if (!PermissionRequirements.isGranted(appContext, Manifest.permission.ACCESS_FINE_LOCATION)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            PermissionRequirements.isGranted(appContext, Manifest.permission.BLUETOOTH_SCAN) &&
            PermissionRequirements.isGranted(appContext, Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun bluetoothDisabled() = MetamapsError(
        MetamapsError.Code.BLUETOOTH_DISABLED,
        "Bluetooth is disabled.",
        true,
        MetamapsUserAction.ENABLE_BLUETOOTH,
    )

    private fun bluetoothPermissionDenied(detail: String?) = MetamapsError(
        MetamapsError.Code.BLUETOOTH_PERMISSION_DENIED,
        "Bluetooth scan and precise location permissions are required.",
        true,
        MetamapsUserAction.REQUEST_PERMISSION,
        detail,
    )

    private fun scanRuntimeError(detail: String) = MetamapsError(
        MetamapsError.Code.SCAN_RUNTIME_FAILED,
        "iBeacon ranging failed while running.",
        true,
        MetamapsUserAction.RETRY,
        detail,
    )

    private companion object {
        /** The ranging window. It keeps the 1.1-second cycle that the Android Beacon Library ran before. */
        const val WINDOW_MS = 1_100L

        /** Below the shortest `scan_timeout_millis` seen on devices (5 minutes). The upstream library chose 4 minutes too. */
        const val SCAN_RESTART_INTERVAL_MS = 4 * 60 * 1_000L
    }
}
