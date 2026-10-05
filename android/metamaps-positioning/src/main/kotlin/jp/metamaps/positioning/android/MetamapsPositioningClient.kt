@file:OptIn(MetamapsInternalApi::class)

package jp.metamaps.positioning.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import java.security.SecureRandom
import java.util.UUID
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsSdk
import jp.metamaps.MetamapsUserAction
import jp.metamaps.positioning.LocalPosition
import jp.metamaps.positioning.PositionEstimate
import jp.metamaps.positioning.PositioningCoordinateTransform
import jp.metamaps.positioning.PositioningEstimator
import jp.metamaps.positioning.PositioningManifest
import jp.metamaps.positioning.ReplayEvent
import jp.metamaps.positioning.Wgs84Position
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An Android client that manages the manifest download, permissions, BLE and motion sensors, and position estimation.
 *
 * Call [configure], [requestAuthorization], and [start] in that order, and call [dispose] when no longer needed.
 */
class MetamapsPositioningClient(
    context: Context,
    val configuration: PositioningConfiguration,
) {
    private val appContext = context.applicationContext
    private val application = appContext as? Application
        ?: throw MetamapsError.invalidConfiguration("applicationContext is not an Application")
    private val repository = ManifestRepository(appContext.cacheDir.resolve("metamaps-positioning"))
    private val beaconAdapter: BeaconRangingAdapter = AltBeaconRangingAdapter(appContext)
    private val motionAdapter: MotionObservationAdapter = AndroidSensorAdapter(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val estimatorMutex = Mutex()
    private val diagnosticsMutex = Mutex()
    private val foregroundRecovery = ForegroundRecoveryCoordinator(scope)
    private val mutableEvents = MutableSharedFlow<MetamapsPositioningEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    private val startedActivities = mutableSetOf<Activity>()
    private val lifecycleCallbacks = ClientLifecycleCallbacks()
    private var manifest: PositioningManifest? = null
    private var estimator: PositioningEstimator? = null
    private var tickJob: Job? = null
    private var running = false
    private var resumeAfterForeground = false
    private var foregroundGeneration = 0L
    private var lostErrorEmitted = false
    private val signalDiagnostics = BeaconSignalDiagnostics()

    /** A SharedFlow of position, capability, state, and error events. */
    val events: SharedFlow<MetamapsPositioningEvent> = mutableEvents.asSharedFlow()

    /** The current positioning lifecycle state. */
    @Volatile
    var status: PositioningLifecycleStatus = PositioningLifecycleStatus.UNCONFIGURED
        private set

    /** The identity of the loaded manifest. */
    @Volatile
    var manifestIdentity: ManifestIdentity? = null
        private set

    /** The latest position update, or `null` if nothing has been estimated yet. */
    @Volatile
    var latestUpdate: PositioningUpdate? = null
        private set

    /**
     * Whether at least one beacon registered in the manifest was received since the last [start].
     *
     * The map view's automatic positioning uses it to decide whether the device is in the beacon area. Scan
     * constraints come from the manifest's active beacons, so any received observation is a registered beacon.
     * Raw observations and RSSI values never leave through this property.
     */
    @Volatile
    var hasObservedRegisteredBeaconSinceStart: Boolean = false
        private set

    init {
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    /** Downloads and validates the manifest and prepares the estimator. */
    suspend fun configure() {
        ensureNotDisposed("configure")
        val loaded = withContext(Dispatchers.IO) { repository.load(configuration) }
        val parsed = loaded.manifest
        val identity = ManifestIdentity(
            UUID.fromString(parsed.mapId),
            UUID.fromString(parsed.groupId),
            parsed.revision,
        )
        estimatorMutex.withLock {
            val changed = manifestIdentity != identity
            val nextEstimator = if (changed || estimator == null) {
                createEstimator(parsed)
            } else {
                estimator
            }
            manifest = parsed
            manifestIdentity = identity
            if (changed || estimator == null) {
                estimator = nextEstimator
                latestUpdate = null
            }
        }
        diagnosticsMutex.withLock { signalDiagnostics.reset() }
        transition(PositioningLifecycleStatus.CONFIGURED)
        emit(MetamapsPositioningEvent.Capabilities(capabilities()))
    }

    /** Returns the current device, permission, and sensor capabilities. */
    fun capabilities(): CapabilityReport {
        val beacon = beaconAdapter.snapshot
        val motion = motionAdapter.snapshot
        val fullMotion = motion.authorization == MotionAuthorizationState.GRANTED &&
            motion.stepAvailable && motion.rotationVectorAvailable && motion.magnetometerAvailable
        val bleUsable = beacon.bleSupported && beacon.rangingAvailable && beacon.servicesEnabled &&
            beacon.authorization == LocationAuthorizationState.WHEN_IN_USE &&
            beacon.bluetoothScan in setOf("notRequired", "granted")
        val selected = when {
            !bleUsable -> "unavailable"
            fullMotion && manifest?.geometry?.isNotEmpty() == true -> "ble_pdr_pf"
            manifest?.geometry?.isNotEmpty() == true -> "ble_pf"
            else -> "ble_only"
        }
        return CapabilityReport(
            platform = "android",
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            sdkVersion = MetamapsSdk.VERSION,
            ble = CapabilityReport.Ble(
                supported = beacon.bleSupported,
                enabled = beacon.servicesEnabled,
                rangingAvailable = beacon.rangingAvailable,
                foregroundScan = beacon.bleSupported,
                backgroundScan = "limited",
                directionFinding = "unknown",
                channelSounding = "unknown",
            ),
            authorization = CapabilityReport.Authorization(
                location = beacon.authorization,
                preciseLocation = PermissionRequirements.isGranted(
                    appContext,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                bluetoothScan = beacon.bluetoothScan,
                motion = motion.authorization,
            ),
            sensors = CapabilityReport.Sensors(
                stepDetector = motion.stepAvailable,
                rotationVector = motion.rotationVectorAvailable,
                gyroscope = motion.gyroscopeAvailable,
                magnetometer = motion.magnetometerAvailable,
                barometer = motion.barometerAvailable,
            ),
            selectedProfile = selected,
        )
    }

    /** Requests Android runtime permissions, starting from a user action. */
    suspend fun requestAuthorization(
        activity: Activity,
        mode: PositioningAuthorizationMode = PositioningAuthorizationMode.FOREGROUND_NAVIGATION,
    ) {
        ensureNotDisposed("requestAuthorization")
        if (mode != PositioningAuthorizationMode.FOREGROUND_NAVIGATION) {
            throw invariant("unsupported authorization mode")
        }
        if (manifest == null) throw invariant("configure must complete before requesting authorization")
        PermissionRequirements.ensureDeclared(appContext, configuration.motionPolicy)
        val blePermissions = PermissionRequirements.bleRuntime()
        val bleResults = ActivityPermissionRequester.request(activity, blePermissions)
        val deniedBle = blePermissions.filter { bleResults[it] != true }
        if (deniedBle.isNotEmpty()) {
            val permanent = deniedBle.any { PermissionRequirements.isPermanentlyDenied(activity, it) }
            val code = if (Manifest.permission.ACCESS_FINE_LOCATION in deniedBle) {
                MetamapsError.Code.PRECISE_LOCATION_REQUIRED
            } else {
                MetamapsError.Code.BLUETOOTH_PERMISSION_DENIED
            }
            throw MetamapsError(
                code,
                "Bluetooth scan and precise location permissions are required for indoor positioning.",
                !permanent,
                if (permanent) MetamapsUserAction.OPEN_APP_SETTINGS else MetamapsUserAction.REQUEST_PERMISSION,
                deniedBle.joinToString(),
            )
        }
        // Devices without a step detector cannot count steps, so do not ask for the permission (matching the iOS
        // `CMPedometer.isStepCountingAvailable()` guard). Heading does not depend on the permission.
        if (configuration.motionPolicy == MotionPolicy.PREFERRED && motionAdapter.snapshot.stepAvailable) {
            val motionPermissions = PermissionRequirements.motionRuntime()
            val motionResults = ActivityPermissionRequester.request(activity, motionPermissions)
            if (motionPermissions.any { motionResults[it] != true }) {
                emit(
                    MetamapsPositioningEvent.Error(
                        MetamapsError(
                            MetamapsError.Code.MOTION_PERMISSION_DENIED,
                            "Activity recognition permission was denied; step counting (PDR) is disabled while heading stays available.",
                            true,
                            MetamapsUserAction.REQUEST_PERMISSION,
                        ),
                    ),
                )
            }
        }
        transition(PositioningLifecycleStatus.AUTHORIZED)
        emit(MetamapsPositioningEvent.Capabilities(capabilities()))
    }

    /** Loads the configuration if needed and starts positioning with BLE and motion sensors. */
    suspend fun start() {
        ensureNotDisposed("start")
        if (recoverFromBackgroundIfNeeded()) return
        if (manifest == null) configure()
        if (running) return
        val beacon = beaconAdapter.snapshot
        if (beacon.authorization != LocationAuthorizationState.WHEN_IN_USE) {
            throw MetamapsError(
                MetamapsError.Code.LOCATION_PERMISSION_DENIED,
                "Call requestAuthorization from a user action before start.",
                true,
                MetamapsUserAction.REQUEST_PERMISSION,
            )
        }
        if (beacon.bluetoothScan !in setOf("notRequired", "granted")) {
            throw MetamapsError(
                MetamapsError.Code.BLUETOOTH_PERMISSION_DENIED,
                "Bluetooth scan permission is required.",
                true,
                MetamapsUserAction.REQUEST_PERMISSION,
            )
        }
        startHardware()
    }

    /** Stops positioning. [start] can be called again. */
    fun stop() {
        if (status == PositioningLifecycleStatus.DISPOSED) return
        resumeAfterForeground = false
        foregroundRecovery.cancel()
        stopHardware()
        transition(PositioningLifecycleStatus.STOPPED)
    }

    /** Resets the estimator state while keeping the current manifest. */
    suspend fun reset() {
        ensureNotDisposed("reset")
        val current = manifest ?: throw invariant("configure must complete before reset")
        estimatorMutex.withLock {
            // Go through the same typed error path as configure. Constructing directly would leak the core's
            // IllegalArgumentException into the public API, where callers cannot branch on MetamapsError's code
            // or userAction.
            estimator = createEstimator(current)
            latestUpdate = null
            lostErrorEmitted = false
        }
        diagnosticsMutex.withLock { signalDiagnostics.reset() }
        if (running) transition(PositioningLifecycleStatus.ACQUIRING)
    }

    /** Resets PDR and the estimator state at the start point of a walking route. */
    suspend fun resetPedestrianRoute() {
        ensureNotDisposed("resetPedestrianRoute")
        if (!running) throw invariant("positioning must be running before route reset")
        motionAdapter.resetStepBaseline()
        latestUpdate = null
        lostErrorEmitted = false
        ingest(
            ReplayEvent(
                type = "lifecycle",
                monotonicTimestampMs = MonotonicClock.nowMilliseconds(),
                observations = null,
                headingDeg = null,
                headingAccuracyDeg = null,
                stepLengthM = null,
                activity = null,
                state = "route_reset",
            ),
        )
        transition(PositioningLifecycleStatus.ACQUIRING)
    }

    /**
     * Converts WGS 84 coordinates to manifest-local coordinates using the floor elevations of the same validated
     * manifest. Returns `null` before configuration or for a floor outside the manifest.
     */
    fun manifestLocalPosition(
        longitude: Double,
        latitude: Double,
        floorId: UUID,
    ): LocalPosition? {
        val current = manifest ?: return null
        val floor = current.floors.firstOrNull { UUID.fromString(it.id) == floorId } ?: return null
        return PositioningCoordinateTransform.wgs84ToLocal(
            Wgs84Position(longitude, latitude, floor.elevationM),
            current.coordinateFrame,
        )
    }

    /** Ends positioning and subscriptions and releases held resources. */
    fun dispose() {
        if (status == PositioningLifecycleStatus.DISPOSED) return
        resumeAfterForeground = false
        foregroundRecovery.cancel()
        stopHardware()
        application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        transition(PositioningLifecycleStatus.DISPOSED)
        scope.cancel()
    }

    private fun startHardware() {
        val current = manifest ?: throw invariant("manifest missing")
        // On returning to the foreground, the client's own background recovery and a start request from the host or
        // map view can run from the same trigger. Do nothing if already running, so adapters are not subscribed twice.
        if (running) return
        // Restart the wait for signals on every start, so an earlier reception is not taken as being in the area.
        hasObservedRegisteredBeaconSinceStart = false
        val constraints = current.beacons.asSequence()
            .filter { it.isEnabled && it.major in 0..65_535 }
            .mapNotNull {
                runCatching { BeaconScanConstraint(UUID.fromString(it.proximityUuid), it.major) }.getOrNull()
            }
            .distinct()
            .sortedWith(compareBy<BeaconScanConstraint> { it.uuid.toString() }.thenBy { it.major })
            .toList()
        beaconAdapter.start(
            constraints,
            onObservations = { values ->
                if (values.isNotEmpty()) {
                    scope.launch { ingestBle(values) }
                }
            },
            onError = { emit(MetamapsPositioningEvent.Error(it)) },
        )
        // Heading (rotation vector and magnetometer) needs no runtime permission. ACTIVITY_RECOGNITION is needed only
        // for steps, so the permission and step availability are not preconditions for heading. The subscription
        // decision uses the same `decideSensorSubscriptions` as the adapter, so the conditions are written only once.
        val motionDecision = motionSubscriptions(configuration.motionPolicy, motionAdapter.snapshot)
        if (motionDecision.hasSubscriptions) {
            motionAdapter.start(
                subscriptions = motionDecision,
                onEvent = { value -> scope.launch { ingest(value) } },
                onError = { emit(MetamapsPositioningEvent.Error(it)) },
            )
        }
        running = true
        transition(PositioningLifecycleStatus.ACQUIRING)
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && running) {
                delay(250)
                tick()
            }
        }
    }

    private fun stopHardware() {
        running = false
        tickJob?.cancel()
        tickJob = null
        beaconAdapter.stop()
        motionAdapter.stop()
    }

    private suspend fun ingest(event: ReplayEvent) {
        if (event.type == "attitude") {
            emit(
                MetamapsPositioningEvent.MotionHeading(
                    MotionHeadingReading(event.headingDeg, event.magneticFieldAccuracy),
                ),
            )
        }
        estimatorMutex.withLock {
            // Keep the capture order identical to the estimator's serialized input order so the
            // exported fixture can reproduce the physical run even when sensor callbacks race.
            runCatching { configuration.internalOptions.diagnosticEventHandler?.invoke(event) }
            try {
                estimator?.process(event)
            } catch (error: Throwable) {
                emit(MetamapsPositioningEvent.Error(invariant("estimator input failed", error)))
            }
        }
    }

    private suspend fun ingestBle(values: List<jp.metamaps.positioning.BeaconObservation>) {
        hasObservedRegisteredBeaconSinceStart = true
        ingest(
            ReplayEvent(
                type = "ble",
                monotonicTimestampMs = values.maxOf { it.monotonicTimestampMs },
                observations = values,
                headingDeg = null,
                headingAccuracyDeg = null,
                stepLengthM = null,
                activity = null,
                state = null,
            ),
        )
        if (!configuration.effectiveBeaconDiagnostics) return
        val currentManifest = manifest ?: return
        val readings = try {
            diagnosticsMutex.withLock {
                signalDiagnostics.readings(values, currentManifest, latestUpdate)
            }
        } catch (error: Throwable) {
            emit(MetamapsPositioningEvent.Error(invariant("BLE diagnostic conversion failed", error)))
            return
        }
        emit(MetamapsPositioningEvent.BeaconSignals(readings))
    }

    private suspend fun tick() {
        val event = ReplayEvent(
            type = "tick",
            monotonicTimestampMs = MonotonicClock.nowMilliseconds(),
            observations = null,
            headingDeg = null,
            headingAccuracyDeg = null,
            stepLengthM = null,
            activity = null,
            state = null,
        )
        val update = estimatorMutex.withLock {
            val currentEstimator = estimator ?: return@withLock null
            val currentManifest = manifest ?: return@withLock null
            runCatching { configuration.internalOptions.diagnosticEventHandler?.invoke(event) }
            try {
                val estimate = currentEstimator.process(event) ?: return@withLock null
                positionUpdate(currentManifest, estimate)
            } catch (error: Throwable) {
                emit(MetamapsPositioningEvent.Error(invariant("estimator tick failed", error)))
                null
            }
        } ?: return
        latestUpdate = update
        lifecycleTransitionFromEstimate(status, update.estimate.status)?.let(::transition)
        emit(MetamapsPositioningEvent.Position(update))
        if (update.estimate.status == "lost" && !lostErrorEmitted) {
            lostErrorEmitted = true
            emit(
                MetamapsPositioningEvent.Error(
                    MetamapsError(
                        MetamapsError.Code.POSITION_LOST,
                        "Registered beacon signals were lost.",
                        true,
                        MetamapsUserAction.SELECT_LOCATION_MANUALLY,
                    ),
                ),
            )
        } else if (update.estimate.status != "lost") {
            lostErrorEmitted = false
        }
    }

    private fun positionUpdate(
        manifest: PositioningManifest,
        estimate: PositionEstimate,
    ): PositioningUpdate {
        val wgs84 = estimate.local?.let {
            PositioningCoordinateTransform.localToWgs84(it, manifest.coordinateFrame)
        }
        return PositioningUpdate(
            UUID.fromString(manifest.mapId),
            UUID.fromString(manifest.groupId),
            estimate,
            wgs84,
        )
    }

    private fun transition(value: PositioningLifecycleStatus) {
        if (status == value) return
        status = value
        emit(MetamapsPositioningEvent.Status(value))
    }

    private fun emit(event: MetamapsPositioningEvent) {
        mutableEvents.tryEmit(event)
    }

    private fun pauseForBackground() {
        if (!running) return
        recordLifecycle("background")
        resumeAfterForeground = true
        stopHardware()
        transition(PositioningLifecycleStatus.PAUSED_BACKGROUND)
        emit(
            MetamapsPositioningEvent.Error(
                MetamapsError(
                    MetamapsError.Code.PAUSED_BACKGROUND,
                    "Foreground positioning paused in the background.",
                    true,
                    MetamapsUserAction.NONE,
                ),
            ),
        )
    }

    private suspend fun resumeFromBackground() {
        try {
            recoverFromBackgroundIfNeeded()
        } catch (error: MetamapsError) {
            emit(MetamapsPositioningEvent.Error(error))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            emit(MetamapsPositioningEvent.Error(invariant("foreground recovery failed", error)))
        }
    }

    /**
     * Serializes map view start requests and application lifecycle recovery, so each return to the foreground
     * refreshes the manifest, resets the estimator, and starts scanning exactly once.
     */
    private suspend fun recoverFromBackgroundIfNeeded(): Boolean = foregroundRecovery.recoverIfNeeded(
        isRequired = { resumeAfterForeground && status != PositioningLifecycleStatus.DISPOSED },
        prepare = {
            recordLifecycle("foreground")
            configure()
            reset()
        },
        complete = {
            startHardware()
            transition(PositioningLifecycleStatus.RECOVERING)
        },
        markRecovered = { resumeAfterForeground = false },
    )

    private fun ensureNotDisposed(action: String) {
        if (status == PositioningLifecycleStatus.DISPOSED) throw invariant("$action called after dispose")
    }

    private fun recordLifecycle(state: String) {
        runCatching {
            configuration.internalOptions.diagnosticEventHandler?.invoke(
                ReplayEvent(
                    type = "lifecycle",
                    monotonicTimestampMs = MonotonicClock.nowMilliseconds(),
                    observations = null,
                    headingDeg = null,
                    headingAccuracyDeg = null,
                    stepLengthM = null,
                    activity = null,
                    state = state,
                ),
            )
        }
    }

    private fun invariant(detail: String, underlying: Throwable? = null) = MetamapsError(
        MetamapsError.Code.INTERNAL_INVARIANT_VIOLATION,
        "Indoor positioning entered an invalid state.",
        false,
        MetamapsUserAction.RETRY,
        listOfNotNull(detail, underlying?.toString()).joinToString(": "),
    )

    private fun randomSeed(): ULong = SecureRandom().nextLong().toULong()

    private fun createEstimator(value: PositioningManifest): PositioningEstimator = try {
        PositioningEstimator(value, randomSeed())
    } catch (error: Throwable) {
        throw MetamapsError(
            MetamapsError.Code.MANIFEST_UNSUPPORTED,
            "The positioning manifest requests an unsupported estimator configuration.",
            false,
            MetamapsUserAction.RETRY,
            error.toString(),
        )
    }

    private inner class ClientLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            synchronized(startedActivities) {
                startedActivities += activity
                foregroundGeneration++
            }
            if (resumeAfterForeground) scope.launch { resumeFromBackground() }
        }

        override fun onActivityStopped(activity: Activity) {
            val generation = synchronized(startedActivities) {
                startedActivities -= activity
                ++foregroundGeneration
            }
            scope.launch {
                delay(300)
                val background = synchronized(startedActivities) {
                    startedActivities.isEmpty() && foregroundGeneration == generation
                }
                if (background) pauseForBackground()
            }
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

/**
 * On returning to the foreground, send `recovering` after the `acquiring` of the scan start. Do not report
 * the `acquiring` that the new estimator returns before it initializes, so one scan start does not look like two.
 */
internal fun lifecycleTransitionFromEstimate(
    current: PositioningLifecycleStatus,
    estimateStatus: String,
): PositioningLifecycleStatus? {
    val mapped = PositioningLifecycleStatus.fromWire(estimateStatus) ?: return null
    return mapped.takeUnless {
        current == PositioningLifecycleStatus.RECOVERING && it == PositioningLifecycleStatus.ACQUIRING
    }
}
