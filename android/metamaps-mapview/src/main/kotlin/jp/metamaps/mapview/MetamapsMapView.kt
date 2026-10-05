@file:OptIn(MetamapsInternalApi::class)

package jp.metamaps.mapview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URI
import java.util.UUID
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsSdk
import jp.metamaps.MetamapsUserAction
import jp.metamaps.positioning.PositionEstimate
import jp.metamaps.positioning.android.BeaconSignalReading
import jp.metamaps.positioning.android.CapabilityReport
import jp.metamaps.positioning.android.LocationAuthorizationState
import jp.metamaps.positioning.android.MetamapsInternalApi
import jp.metamaps.positioning.android.MetamapsPositioningClient
import jp.metamaps.positioning.android.MetamapsPositioningEvent
import jp.metamaps.positioning.android.MotionHeadingReading
import jp.metamaps.positioning.android.PositioningLifecycleStatus
import jp.metamaps.positioning.android.PositioningUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * An Android view that shows the published Metamaps map for `mapSlug`.
 *
 * For maps with indoor positioning, it can also show the user's location while the app is in use.
 *
 * Call [load] after [configure], and call [dispose] when the view is no longer needed.
 */
@SuppressLint("SetJavaScriptEnabled")
class MetamapsMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    /** The WebView that shows the map. Do not control it directly; use the public commands. */
    val webView: WebView = WebView(context)
    private val defaultUserAgent = webView.settings.userAgentString.orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableEvents = MutableSharedFlow<MetamapsMapViewEvent>(extraBufferCapacity = 64)
    private val nativeBridge = NativeBridge()
    private var configuration: MetamapsMapViewConfiguration? = null
    private var positioningClient: MetamapsPositioningClient? = null
    private var positioningEventsJob: Job? = null
    private var bridgeValidator = BridgeValidator(null)
    private var outboundSequence = 0L
    private var pageToken = UUID.randomUUID().toString()
    private var disposed = false
    private var loadGeneration = 0L
    private var positioningPreparedGeneration: Long? = null
    private var activeLoadSession: LoadSession? = null
    private var activeLoadAttempt: LoadAttempt? = null
    private var currentPageFailed = false
    private var pendingBeaconSignals: List<BeaconSignalReading>? = null
    /** Waits for beacon signals and retries automatic positioning. Never advances in a `USER_ACTION` map view. */
    private val automaticLoop = AutomaticPositioningLoop()
    private var automaticTimerJob: Job? = null
    /** `capabilities` re-evaluated on returning to the foreground. Sent again only when they differ from the last value. */
    private var lastSentCapabilities: CapabilityReport? = null
    /** The automatic positioning configuration last announced in `bridge.hello`, used to decide whether a correction is needed. */
    private var lastSentAutomaticPositioning: Boolean? = null
    private var windowVisible = false
    private val pendingCenterReticleRequests = mutableMapOf<UUID, CompletableDeferred<MapCenterReticleCandidate?>>()
    private var beaconSignalPresentationScheduled = false
    private val beaconSignalPresentation = Runnable {
        beaconSignalPresentationScheduled = false
        val next = pendingBeaconSignals
        pendingBeaconSignals = null
        if (!disposed && next != null) {
            emit(MetamapsMapViewEvent.BeaconSignals(next))
            if (bridgeValidator.handshakeComplete) {
                send("positioning.beaconSignals", next.toBridgePayload())
            }
        }
    }

    /** A SharedFlow of map, positioning, and error events. */
    val events: SharedFlow<MetamapsMapViewEvent> = mutableEvents.asSharedFlow()

    /** The current map load state. */
    @Volatile
    var loadState: MapViewLoadState = MapViewLoadState.IDLE
        private set

    /** A listener for receiving events without the SharedFlow. */
    var eventListener: ((MetamapsMapViewEvent) -> Unit)? = null

    /** A handler that lets the host app handle external links with allowed schemes. */
    var externalLinkHandler: ((URI) -> MetamapsExternalLinkDecision)? = null

    init {
        configureWebView()
        addView(
            webView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** Validates and applies the map and positioning configuration. */
    fun configure(value: MetamapsMapViewConfiguration) {
        checkMainThread()
        ensureNotDisposed("configure")
        try {
            value.validate()
        } catch (error: IllegalArgumentException) {
            throw MetamapsError.invalidConfiguration(error.message ?: "Invalid MapView configuration")
        }
        positioningEventsJob?.cancel()
        positioningClient?.dispose()
        clearBeaconSignalPresentation()
        configuration = value
        webView.settings.userAgentString = buildMetamapsUserAgent(
            defaultUserAgent,
            MetamapsSdk.VERSION,
            value.userAgentAppendix,
        )
        if (value.isWebViewInspectable) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        bridgeValidator = BridgeValidator(value.groupId)
        positioningClient = MetamapsPositioningClient(context.applicationContext, value.positioningConfiguration)
        val client = positioningClient ?: return
        positioningEventsJob = scope.launch {
            client.events.collect { event ->
                post { handlePositioningEvent(event) }
            }
        }
    }

    /** Loads the web map and waits for a successful HTTP response and the end of loading. Does not wait for `Ready`. */
    suspend fun load() {
        checkMainThread()
        ensureNotDisposed("load")
        val config = requireConfiguration()
        cancelActiveLoad()
        val session = LoadSession(++loadGeneration, Job(currentCoroutineContext()[Job]))
        activeLoadSession = session
        resetPageBridge()
        emit(MetamapsMapViewEvent.LoadState(MapViewLoadState.LOADING))
        try {
            withContext(session.job + Dispatchers.Main.immediate) {
                var retryIndex = 0
                while (true) {
                    when (val result = runLoadAttempt(session, config)) {
                        LoadAttemptResult.Success -> return@withContext
                        is LoadAttemptResult.Failure -> {
                            val shouldRetry = result.failure.retryable &&
                                config.retryPolicy == MetamapsMapViewRetryPolicy.AUTOMATIC
                            if (!shouldRetry) {
                                failLoad(result.failure.error)
                                throw result.failure.error
                            }
                            emit(MetamapsMapViewEvent.Error(result.failure.error))
                            delay(mapLoadRetryDelayMillis(retryIndex))
                            retryIndex++
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            if (activeLoadSession === session) {
                activeLoadSession = null
                activeLoadAttempt = null
                currentPageFailed = true
                webView.stopLoading()
                if (!disposed) emit(MetamapsMapViewEvent.LoadState(MapViewLoadState.IDLE))
            }
            throw error
        } finally {
            if (activeLoadSession === session) {
                activeLoadSession = null
                activeLoadAttempt = null
            }
        }
    }

    /** Reloads the map with the current configuration. */
    suspend fun reload() = load()

    /** Requests Android positioning permissions, starting from a user action. */
    suspend fun requestPositioningAuthorization(activity: Activity? = context.findActivity()) {
        ensureNotDisposed("requestPositioningAuthorization")
        val client = positioningClient ?: throw invariant("MapView is not configured")
        if (client.status == PositioningLifecycleStatus.UNCONFIGURED) client.configure()
        validateManifestIdentityIfReady()
        val host = activity ?: throw MetamapsError(
            MetamapsError.Code.LOCATION_PERMISSION_DENIED,
            "An Activity is required to request Android runtime permissions.",
            true,
            MetamapsUserAction.REQUEST_PERMISSION,
        )
        client.requestAuthorization(host)
    }

    /**
     * Requests permissions if needed and starts indoor positioning.
     *
     * In an automatic positioning configuration, a direct call from the host also joins the wait-for-signals and
     * retry cycle that starts after scanning begins. Failures are thrown to the caller as before.
     */
    suspend fun startPositioning(
        activity: Activity? = context.findActivity(),
        requestAuthorization: Boolean = true,
    ) {
        ensureNotDisposed("startPositioning")
        val client = positioningClient ?: throw invariant("MapView is not configured")
        if (client.status == PositioningLifecycleStatus.UNCONFIGURED) client.configure()
        validateManifestIdentityIfReady()
        if (requestAuthorization) requestPositioningAuthorization(activity)
        client.start()
        if (isAutomaticPositioningEnabled()) {
            withContext(Dispatchers.Main) {
                if (!disposed) runAutomaticEffects(automaticLoop.positioningStarted())
            }
        }
    }

    /** Stops indoor positioning, including the automatic positioning retry cycle. */
    fun stopPositioning() {
        automaticTimerJob?.cancel()
        automaticTimerJob = null
        automaticLoop.stop()
        positioningClient?.stop()
    }

    /** Resets PDR and the estimator state at the start point of a walking route. */
    suspend fun resetPedestrianRoute() {
        ensureNotDisposed("resetPedestrianRoute")
        val client = positioningClient ?: throw invariant("MapView is not configured")
        client.resetPedestrianRoute()
    }

    /** Sets the floor shown in the map. `null` returns to automatic selection. */
    fun selectFloor(floorId: UUID?) {
        sendHostCommand("map.selectFloor", mapOf("floorId" to floorId?.toString()?.lowercase()))
    }

    /** Shows the spot with the given public spot stable key on the map. */
    fun showSpot(spotId: String) {
        require(isValidMetamapsSpotStableKey(spotId)) { "spotId must be a public spots.v2 stable key" }
        sendHostCommand("map.showSpot", mapOf("spotId" to spotId))
    }

    /** Sets the route destination. `null` clears the destination. */
    fun setDestination(spotId: String?) {
        require(spotId == null || isValidMetamapsSpotStableKey(spotId)) { "spotId must be a public spots.v2 stable key" }
        sendHostCommand("map.setDestination", mapOf("spotId" to spotId))
    }

    /** Changes the web map's display language. */
    fun setLanguage(language: String) {
        require(Regex("[A-Za-z0-9_-]{1,35}").matches(language)) { "Invalid language tag" }
        sendHostCommand("map.setLanguage", mapOf("language" to language))
    }

    /** Shows or hides the center reticle. Hidden by default. */
    fun setCenterReticleEnabled(enabled: Boolean) {
        sendHostCommand("map.setCenterReticle", mapOf("enabled" to enabled))
    }

    /**
     * Sends a host command used only by Metamaps tooling, such as the BLE test diagnostic overlay.
     * The web runtime rejects these commands outside BLE test mode.
     */
    @MetamapsInternalApi
    fun sendInternalBridgeCommand(type: String, payload: Map<String, Any?>) {
        sendHostCommand(type, payload)
    }

    /**
     * Gets the candidate on the selected floor under the reticle at the time of the call, once.
     * Returns `null` when the reticle is hidden, all floors are shown, or the reticle does not hit a floor.
     */
    suspend fun requestCenterReticleCandidate(): MapCenterReticleCandidate? {
        ensureNotDisposed("requestCenterReticleCandidate")
        checkMainThread()
        if (!bridgeValidator.handshakeComplete) {
            throw bridgeError(MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED, "MapView is not ready")
        }
        val requestId = UUID.randomUUID()
        val result = CompletableDeferred<MapCenterReticleCandidate?>()
        pendingCenterReticleRequests[requestId] = result
        return try {
            sendHostCommand(
                "map.requestCenterReticleCandidate",
                mapOf("requestId" to requestId.toString().lowercase()),
            )
            withTimeout(CENTER_RETICLE_REQUEST_TIMEOUT_MS) { result.await() }
        } catch (error: TimeoutCancellationException) {
            throw bridgeError(
                MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED,
                "Center reticle candidate request timed out",
            )
        } finally {
            pendingCenterReticleRequests.remove(requestId)
        }
    }

    /** Opens a URI with an SDK-allowed scheme in an external app. */
    fun openExternalLink(uri: URI) {
        if (disposed || !isAllowedExternalLinkScheme(uri)) return
        checkMainThread()
        val androidUri = Uri.parse(uri.toString())
        if (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)
        ) {
            val customTabsPackage = CustomTabsClient.getPackageName(context, null)
            if (customTabsPackage != null) {
                try {
                    val customTabs = CustomTabsIntent.Builder().build()
                    customTabs.intent.setPackage(customTabsPackage)
                    if (context.findActivity() == null) {
                        customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    customTabs.launchUrl(context, androidUri)
                    return
                } catch (_: ActivityNotFoundException) {
                    // Fall through to the platform URL handler.
                }
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, androidUri)
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Unsupported external schemes are intentionally ignored.
        }
    }

    /** Ends positioning, the WebView, and subscriptions, and releases held resources. */
    fun dispose() {
        checkMainThread()
        if (disposed) return
        disposed = true
        cancelActiveLoad()
        automaticTimerJob?.cancel()
        automaticTimerJob = null
        positioningEventsJob?.cancel()
        positioningEventsJob = null
        positioningClient?.dispose()
        positioningClient = null
        failPendingCenterReticleRequests(CancellationException("MapView disposed during center reticle request"))
        clearBeaconSignalPresentation()
        webView.stopLoading()
        webView.removeJavascriptInterface(JS_INTERFACE)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
        emit(MetamapsMapViewEvent.LoadState(MapViewLoadState.DISPOSED))
        eventListener = null
        externalLinkHandler = null
        scope.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        positioningClient?.stop()
    }

    private suspend fun runLoadAttempt(
        session: LoadSession,
        config: MetamapsMapViewConfiguration,
    ): LoadAttemptResult {
        val attempt = LoadAttempt(
            generation = session.generation,
            completion = CompletableDeferred(session.job),
        )
        activeLoadAttempt = attempt
        webView.loadUrl(mapUrl(config).toString())
        return try {
            coroutineScope {
                val watchdog = launch {
                    delay(INITIAL_DOCUMENT_TIMEOUT_MS)
                    if (activeLoadAttempt === attempt &&
                        !attempt.settled &&
                        shouldInitialDocumentRequestTimeout(attempt.responseObserved)
                    ) {
                        attempt.settled = true
                        currentPageFailed = true
                        webView.stopLoading()
                        attempt.completion.complete(
                            LoadAttemptResult.Failure(
                                retryableWebLoadFailure(
                                    "Initial document request timed out after 30 seconds",
                                ),
                            ),
                        )
                    }
                }
                try {
                    attempt.completion.await()
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            if (activeLoadAttempt === attempt) activeLoadAttempt = null
        }
    }

    private fun cancelActiveLoad() {
        val session = activeLoadSession ?: return
        activeLoadSession = null
        activeLoadAttempt?.settled = true
        activeLoadAttempt = null
        currentPageFailed = true
        webView.stopLoading()
        session.job.cancel(CancellationException("MapView load was superseded"))
    }

    private fun configurePositioningWithoutFailingMap() {
        val config = configuration ?: return
        if (config.positioningPolicy == MapViewPositioningPolicy.DISABLED) return
        scope.launch {
            try {
                positioningClient?.configure()
                validateManifestIdentityIfReady()
                refreshAutomaticPositioningAnnouncement()
            } catch (error: MetamapsError) {
                emit(MetamapsMapViewEvent.Error(error))
                send("positioning.error", error.toPayload())
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val typed = MetamapsError(
                    MetamapsError.Code.MANIFEST_UNAVAILABLE,
                    "A verified positioning manifest is unavailable.",
                    true,
                    MetamapsUserAction.RETRY,
                    error.toString(),
                )
                emit(MetamapsMapViewEvent.Error(typed))
                send("positioning.error", typed.toPayload())
            }
        }
    }

    private fun handlePositioningEvent(event: MetamapsPositioningEvent) {
        when (event) {
            is MetamapsPositioningEvent.Position -> {
                emit(MetamapsMapViewEvent.Position(event.update))
                if (!bridgeValidator.handshakeComplete) return
                try {
                    validateManifestIdentityIfReady()
                    send("positioning.position", event.update.toPayload())
                } catch (error: MetamapsError) {
                    emit(MetamapsMapViewEvent.Error(error))
                }
            }
            is MetamapsPositioningEvent.BeaconSignals -> {
                if (configuration?.effectiveBeaconDiagnostics != true) return
                scheduleBeaconSignalPresentation(event.readings)
            }
            is MetamapsPositioningEvent.MotionHeading -> {
                emit(MetamapsMapViewEvent.MotionHeading(event.reading))
                if (bridgeValidator.handshakeComplete) {
                    send("positioning.motionHeading", event.reading.toPayload())
                }
            }
            is MetamapsPositioningEvent.Status -> {
                emit(MetamapsMapViewEvent.PositioningStatus(event.status))
                send("positioning.status", mapOf("status" to event.status.wireValue))
            }
            is MetamapsPositioningEvent.Capabilities -> {
                lastSentCapabilities = event.report
                emit(MetamapsMapViewEvent.Capabilities(event.report))
                send("positioning.capabilities", event.report.toPayload())
                // Finishing configure or settling permissions can change the automatic positioning configuration.
                // Correct the hello announcement.
                refreshAutomaticPositioningAnnouncement()
            }
            is MetamapsPositioningEvent.Error -> {
                emit(MetamapsMapViewEvent.Error(event.error))
                send("positioning.error", event.error.toPayload())
            }
        }
    }

    /** Coalesces adjacent region callbacks so host UI and WebGL receive at most five snapshots/s. */
    private fun scheduleBeaconSignalPresentation(readings: List<BeaconSignalReading>) {
        pendingBeaconSignals = readings
        if (beaconSignalPresentationScheduled) return
        beaconSignalPresentationScheduled = true
        postDelayed(beaconSignalPresentation, BEACON_SIGNAL_PRESENTATION_INTERVAL_MS)
    }

    private fun clearBeaconSignalPresentation() {
        pendingBeaconSignals = null
        beaconSignalPresentationScheduled = false
        removeCallbacks(beaconSignalPresentation)
    }

    private fun receive(message: String, token: String) {
        if (disposed || token != pageToken || !isAllowed(webView.url?.let(URI::create))) {
            emit(MetamapsMapViewEvent.Error(bridgeError(
                MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED,
                "Rejected bridge message origin or document token",
            )))
            return
        }
        try {
            val envelope = BridgeEnvelope.parse(message)
            val ready = bridgeValidator.validate(envelope)
            if (ready != null) {
                validateManifestIdentityIfReady()
                completeHandshake(ready)
                return
            }
            handleInbound(envelope)
        } catch (error: MetamapsError) {
            emit(MetamapsMapViewEvent.Error(error))
            send("positioning.error", error.toPayload())
        } catch (error: Throwable) {
            emit(MetamapsMapViewEvent.Error(bridgeError(
                MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED,
                error.toString(),
            )))
        }
    }

    private fun handleInbound(envelope: BridgeEnvelope) {
        val config = requireConfiguration()
        when (envelope.type) {
            "positioning.requestAuthorization" -> when (config.positioningPolicy) {
                MapViewPositioningPolicy.DISABLED ->
                    throw bridgeError(MetamapsError.Code.BLE_UNSUPPORTED, "Positioning is disabled")
                MapViewPositioningPolicy.HOST_CONTROLLED ->
                    emit(MetamapsMapViewEvent.PositioningAuthorizationRequested)
                MapViewPositioningPolicy.USER_INITIATED -> scope.launch {
                    runPositioningAction { requestPositioningAuthorization() }
                }
            }
            // In an automatic positioning configuration, start and stop go through the bounded retry loop.
            // Starts from a button tap also arrive as `positioning.start`, so branch on the map view's
            // configuration, not on where the request came from.
            "positioning.start" -> when (
                resolveAutomaticPositioningStartAction(
                    config.positioningPolicy,
                    isAutomaticPositioningEnabled(),
                )
            ) {
                AutomaticPositioningStartAction.REJECT ->
                    throw bridgeError(MetamapsError.Code.BLE_UNSUPPORTED, "Positioning is disabled")
                AutomaticPositioningStartAction.NOTIFY_HOST ->
                    emit(MetamapsMapViewEvent.PositioningStartRequested)
                AutomaticPositioningStartAction.RUN_AUTOMATIC_LOOP ->
                    runAutomaticEffects(automaticLoop.start())
                AutomaticPositioningStartAction.START_DIRECTLY ->
                    scope.launch { runPositioningAction { startPositioning() } }
            }
            // Stop the retry cycle as well as any running positioning. Stop requests are not distinguished by kind.
            "positioning.stop" -> stopPositioning()
            "map.floorChanged" -> emit(MetamapsMapViewEvent.FloorChanged(
                FloorChangedEvent(envelope.payload.uuid("floorId"), envelope.payload?.get("source") as? String),
            ))
            "map.spotSelected" -> emit(MetamapsMapViewEvent.SpotSelected(
                SpotSelectedEvent(envelope.payload?.get("spotId") as? String),
            ))
            "map.routeChanged" -> emit(MetamapsMapViewEvent.RouteChanged(
                RouteChangedEvent(
                    envelope.payload?.get("destinationSpotId") as? String,
                    envelope.payload?.get("active") as? Boolean ?: false,
                ),
            ))
            "map.centerReticleCandidate" -> handleCenterReticleCandidate(envelope.payload)
            "map.externalLinkRequested" -> {
                val value = envelope.payload?.get("url") as? String
                if (value != null) {
                    runCatching { URI.create(value) }.getOrNull()?.let(::handleExternalLink)
                }
            }
            // The web map could not complete a host command, such as showing an unknown spot. Report it only to
            // the host: sending it back as positioning.error would make the web map drop the current location.
            "map.error" -> emit(MetamapsMapViewEvent.Error(envelope.mapOperationError))
            else -> Unit
        }
    }

    private fun handleCenterReticleCandidate(payload: Map<String, Any?>?) {
        val requestId = runCatching {
            UUID.fromString(payload?.get("requestId") as? String)
        }.getOrNull() ?: return
        val pending = pendingCenterReticleRequests.remove(requestId) ?: return
        try {
            if (payload?.containsKey("candidate") != true) {
                throw bridgeError(
                    MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED,
                    "Center reticle response has no candidate field",
                )
            }
            val wire = payload["candidate"]
            if (wire == null) {
                pending.complete(null)
                return
            }
            val candidate = wire as? Map<*, *> ?: throw bridgeError(
                MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED,
                "Center reticle candidate is not an object",
            )
            val floorId = UUID.fromString(candidate["floorId"] as? String)
            val longitude = (candidate["longitude"] as? Number)?.toDouble()
            val latitude = (candidate["latitude"] as? Number)?.toDouble()
            if (longitude == null || !longitude.isFinite() || longitude !in -180.0..180.0 ||
                latitude == null || !latitude.isFinite() || latitude !in -90.0..90.0
            ) {
                throw bridgeError(
                    MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED,
                    "Center reticle candidate coordinates are out of range",
                )
            }
            val local = positioningClient?.manifestLocalPosition(longitude, latitude, floorId)
            pending.complete(MapCenterReticleCandidate(floorId, longitude, latitude, local))
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
        }
    }

    private fun failPendingCenterReticleRequests(error: Throwable) {
        val pending = pendingCenterReticleRequests.values.toList()
        pendingCenterReticleRequests.clear()
        pending.forEach { it.completeExceptionally(error) }
    }

    /** Returns whether the start succeeded. The bounded automatic positioning loop uses the result to schedule retries. */
    private suspend fun runPositioningAction(block: suspend () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (error: MetamapsError) {
            emit(MetamapsMapViewEvent.Error(error))
            send("positioning.error", error.toPayload())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val typed = bridgeError(MetamapsError.Code.SCAN_START_FAILED, error.toString())
            emit(MetamapsMapViewEvent.Error(typed))
            send("positioning.error", typed.toPayload())
        }
        return false
    }

    private fun validateManifestIdentityIfReady() {
        val ready = bridgeValidator.ready ?: return
        val identity = positioningClient?.manifestIdentity ?: return
        if (ready.mapId != identity.mapId || ready.groupId != identity.groupId) {
            throw bridgeError(
                MetamapsError.Code.BRIDGE_MAP_MISMATCH,
                "Web map and positioning manifest identities differ",
            )
        }
        if (ready.manifestRevision != null && ready.manifestRevision != identity.revision) {
            throw bridgeError(
                MetamapsError.Code.BRIDGE_MAP_MISMATCH,
                "Web map and positioning manifest revisions differ",
            )
        }
    }

    private fun completeHandshake(ready: BridgeReadyContext) {
        sendHello { delivered ->
            if (!delivered || disposed || !bridgeValidator.completeHandshake(ready)) return@sendHello
            emit(MetamapsMapViewEvent.Ready(
                MapReadyEvent(
                    ready.mapId,
                    ready.groupId,
                    ready.configRevision,
                    ready.manifestRevision,
                ),
            ))
            positioningClient?.latestUpdate?.let { send("positioning.position", it.toPayload()) }
            preparePositioningIfNeeded(ready)
        }
    }

    /** Prepares positioning once per load, after `map.ready` shows that the map has indoor positioning. */
    private fun preparePositioningIfNeeded(ready: BridgeReadyContext) {
        val config = configuration ?: return
        val verifiedRevision = positioningClient?.manifestIdentity
            ?.takeIf { it.mapId == ready.mapId && it.groupId == ready.groupId }
            ?.revision
        val prepares = shouldPreparePositioning(
            config.positioningPolicy,
            ready.manifestRevision,
            config.internalOptions.bleTest,
            verifiedRevision,
        )
        if (!prepares) return
        if (positioningPreparedGeneration == loadGeneration) return
        positioningPreparedGeneration = loadGeneration
        configurePositioningWithoutFailingMap()
    }

    private fun sendHello(onDelivered: ((Boolean) -> Unit)? = null) {
        val client = positioningClient
        if (client == null) {
            onDelivered?.invoke(false)
            return
        }
        val capabilities = client.capabilities()
        val automaticPositioning = isAutomaticPositioningEnabled()
        lastSentCapabilities = capabilities
        lastSentAutomaticPositioning = automaticPositioning
        send(
            "bridge.hello",
            mapOf(
                "sdkVersion" to MetamapsSdk.VERSION,
                "platform" to "android",
                "capabilities" to capabilities.toPayload(),
                // Backward-compatible addition in bridge 1.4. True only for map views configured for automatic positioning.
                "automaticPositioning" to automaticPositioning,
            ),
            onDelivered,
        )
    }

    /** Whether the map view runs automatic positioning (`bridge.hello.payload.automaticPositioning`). */
    private fun isAutomaticPositioningEnabled(): Boolean {
        val config = configuration ?: return false
        val client = positioningClient ?: return false
        return isAutomaticPositioningConfiguration(
            trigger = config.positioningStartTrigger,
            policy = config.positioningPolicy,
            bleTest = config.internalOptions.bleTest,
            canStartWithoutNewPrompt = canStartPositioningWithoutNewPrompt(
                client.capabilities(),
                config.positioningConfiguration.motionPolicy,
            ),
            hasVerifiedManifest = client.manifestIdentity != null,
        )
    }

    /**
     * If the manifest download or permission decision finishes after the handshake, send the same `bridge.hello`
     * again to correct the automatic positioning configuration in the runtime. If the first announcement said
     * false and is never corrected, automatic positioning never starts for that page's lifetime.
     */
    private fun refreshAutomaticPositioningAnnouncement() {
        if (disposed || !bridgeValidator.handshakeComplete) return
        if (isAutomaticPositioningEnabled() == lastSentAutomaticPositioning) return
        sendHello()
    }

    // ── Automatic positioning retry loop ─────────────────────────────
    private fun runAutomaticEffects(effects: List<AutomaticPositioningEffect>) {
        for (effect in effects) {
            when (effect) {
                AutomaticPositioningEffect.Start -> startPositioningForAutomaticLoop()
                AutomaticPositioningEffect.Stop -> positioningClient?.stop()
                is AutomaticPositioningEffect.ScheduleTimer -> scheduleAutomaticTimer(effect.delayMs)
                AutomaticPositioningEffect.CancelTimer -> {
                    automaticTimerJob?.cancel()
                    automaticTimerJob = null
                }
            }
        }
    }

    /**
     * The 10-second wait for signals starts when scanning starts. Counting the time spent on permission dialogs
     * or the manifest download as waiting would report the user as out of range before scanning even ran.
     */
    private fun startPositioningForAutomaticLoop() {
        scope.launch {
            // `positioningStarted` on success is emitted by `startPositioning`.
            val started = runPositioningAction { startPositioning() }
            if (!started) {
                withContext(Dispatchers.Main) {
                    if (!disposed) runAutomaticEffects(automaticLoop.startFailed())
                }
            }
        }
    }

    private fun scheduleAutomaticTimer(delayMs: Long) {
        automaticTimerJob?.cancel()
        automaticTimerJob = scope.launch {
            delay(delayMs)
            withContext(Dispatchers.Main) {
                if (disposed) return@withContext
                automaticTimerJob = null
                val observed = positioningClient?.hasObservedRegisteredBeaconSinceStart == true
                runAutomaticEffects(automaticLoop.timerFired(observed))
            }
        }
    }

    /** Returned to the foreground: re-evaluate `capabilities` and retry automatic positioning immediately. */
    private fun handleReturnToForeground() {
        if (disposed) return
        val client = positioningClient ?: return
        val capabilities = client.capabilities()
        if (capabilities != lastSentCapabilities && bridgeValidator.handshakeComplete) {
            lastSentCapabilities = capabilities
            emit(MetamapsMapViewEvent.Capabilities(capabilities))
            send("positioning.capabilities", capabilities.toPayload())
        }
        refreshAutomaticPositioningAnnouncement()
        runAutomaticEffects(automaticLoop.returnToForeground())
    }

    /**
     * Treats window visibility changes as foreground and background. Retries run only in the foreground, so the
     * timer stops as soon as the window becomes invisible. The positioning client stops and resumes positioning
     * according to the activity lifecycle.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val visible = visibility == VISIBLE
        val changed = visible != windowVisible
        windowVisible = visible
        if (!changed) return
        if (visible) handleReturnToForeground() else runAutomaticEffects(automaticLoop.enterBackground())
    }

    private fun sendHostCommand(type: String, payload: Map<String, Any?>) {
        ensureNotDisposed(type)
        if (!bridgeValidator.handshakeComplete) {
            throw bridgeError(
                MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED,
                "MapView handshake is not complete",
            )
        }
        send(type, payload)
    }

    private fun send(type: String, payload: Any?, onDelivered: ((Boolean) -> Unit)? = null) {
        post {
            if (loadState != MapViewLoadState.LOADED || disposed ||
                (type != "bridge.hello" && !bridgeValidator.handshakeComplete)
            ) {
                onDelivered?.invoke(false)
                return@post
            }
            outboundSequence++
            val ready = bridgeValidator.ready
            val json = BridgeJson.envelope(type, outboundSequence, ready?.mapId, ready?.groupId, payload)
            val script = """
                (() => {
                  const receiver = window.__metamapsReceiveNativeMessage;
                  if (typeof receiver !== 'function') return false;
                  receiver(JSON.parse(${JSONObject.quote(json)}));
                  return true;
                })();
            """.trimIndent()
            webView.evaluateJavascript(script) { result ->
                onDelivered?.invoke(result == "true")
            }
        }
    }

    private fun resetPageBridge() {
        failPendingCenterReticleRequests(CancellationException("Map document changed during center reticle request"))
        bridgeValidator.reset()
        outboundSequence = 0
        pageToken = UUID.randomUUID().toString()
    }

    private fun injectBridge() {
        val token = JSONObject.quote(pageToken)
        val script = """
            (() => {
              const token = $token;
              const native = window.$JS_INTERFACE;
              if (!native) return;
              Object.defineProperty(window, 'MetamapNativeBridge', {
                value: Object.freeze({
                  postMessage(message) {
                    native.postMessage(JSON.stringify(message), token);
                  }
                }),
                configurable: false,
                writable: false
              });
              window.__metamapsReceiveNativeMessage = function(message) {
                window.dispatchEvent(new CustomEvent('metamap:native-message', { detail: message }));
              };
              window.dispatchEvent(new CustomEvent('metamap:native-ready'));
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) {
            if (!disposed) sendHello()
        }
    }

    private fun requireConfiguration(): MetamapsMapViewConfiguration =
        configuration ?: throw MetamapsError.invalidConfiguration(
            "Call configure before using MetamapsMapView",
        )

    private fun mapUrl(config: MetamapsMapViewConfiguration): URI {
        return buildMetamapsMapUrl(config)
    }

    private fun isAllowed(uri: URI?): Boolean {
        val config = configuration ?: return false
        if (uri == null) return false
        if (uri.scheme == "about") return true
        return uri.scheme.equals(config.baseUrl.scheme, ignoreCase = true) &&
            uri.host.equals(config.baseUrl.host, ignoreCase = true) &&
            normalizedPort(uri) == normalizedPort(config.baseUrl)
    }

    private fun normalizedPort(uri: URI): Int =
        if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80

    private fun handleExternalLink(uri: URI) {
        if (disposed || isAllowed(uri) || !isAllowedExternalLinkScheme(uri)) return
        requestExternalLink(uri)
    }

    private fun handleDownload(uri: URI) {
        if (disposed || !isAllowedExternalLinkScheme(uri)) return
        requestExternalLink(uri)
    }

    private fun requestExternalLink(uri: URI) {
        emit(MetamapsMapViewEvent.ExternalLinkRequested(uri))
        if ((externalLinkHandler?.invoke(uri) ?: MetamapsExternalLinkDecision.OPEN_DEFAULT) ==
            MetamapsExternalLinkDecision.OPEN_DEFAULT
        ) {
            openExternalLink(uri)
        }
    }

    private fun handleLoadFailure(failure: MapLoadFailure) {
        val attempt = activeLoadAttempt
        if (attempt != null &&
            attempt.generation == activeLoadSession?.generation &&
            attempt.started &&
            !attempt.settled
        ) {
            attempt.settled = true
            currentPageFailed = true
            webView.stopLoading()
            attempt.completion.complete(LoadAttemptResult.Failure(failure))
        } else if (activeLoadSession == null) {
            currentPageFailed = true
            webView.stopLoading()
            failLoad(failure.error)
        }
    }

    private fun completeLoadAttempt() {
        val attempt = activeLoadAttempt
        if (attempt == null) {
            if (activeLoadSession == null) {
                emit(MetamapsMapViewEvent.LoadState(MapViewLoadState.LOADED))
            }
            return
        }
        if (attempt.generation != activeLoadSession?.generation || attempt.settled) return
        if (!attempt.started) return
        attempt.settled = true
        emit(MetamapsMapViewEvent.LoadState(MapViewLoadState.LOADED))
        attempt.completion.complete(LoadAttemptResult.Success)
    }

    private fun emit(event: MetamapsMapViewEvent) {
        if (event is MetamapsMapViewEvent.LoadState) loadState = event.state
        mutableEvents.tryEmit(event)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            eventListener?.invoke(event)
        } else {
            post { eventListener?.invoke(event) }
        }
    }

    private fun failLoad(error: MetamapsError) {
        emit(MetamapsMapViewEvent.LoadState(MapViewLoadState.FAILED))
        emit(MetamapsMapViewEvent.Error(error))
    }

    private fun bridgeError(
        code: MetamapsError.Code,
        detail: String,
    ): MetamapsError {
        val recoverable = code !in setOf(
            MetamapsError.Code.BRIDGE_MAP_MISMATCH,
            MetamapsError.Code.BRIDGE_UNSUPPORTED,
            MetamapsError.Code.INTERNAL_INVARIANT_VIOLATION,
            MetamapsError.Code.BLE_UNSUPPORTED,
        )
        val action = if (code == MetamapsError.Code.BLE_UNSUPPORTED) {
            MetamapsUserAction.SELECT_LOCATION_MANUALLY
        } else if (recoverable) {
            MetamapsUserAction.RETRY
        } else {
            MetamapsUserAction.CHECK_CONFIGURATION
        }
        return MetamapsError(
            code,
            "The embedded Metamaps bridge could not complete the requested operation.",
            recoverable,
            action,
            detail,
        )
    }

    private fun invariant(detail: String) =
        bridgeError(MetamapsError.Code.INTERNAL_INVARIANT_VIOLATION, detail)

    private fun ensureNotDisposed(action: String) {
        if (disposed) throw invariant("$action called after dispose")
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "MetamapsMapView must be used from the Android main thread"
        }
    }

    @SuppressLint("AddJavascriptInterface")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.addJavascriptInterface(nativeBridge, JS_INTERFACE)
        webView.webViewClient = MetamapsWebViewClient()
        webView.webChromeClient = MetamapsChromeClient()
        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            runCatching { URI.create(url) }.getOrNull()?.let(::handleDownload)
        })
    }

    internal inner class NativeBridge {
        @JavascriptInterface
        fun postMessage(message: String, token: String) {
            post { receive(message, token) }
        }
    }

    private inner class MetamapsWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            currentPageFailed = false
            activeLoadAttempt
                ?.takeIf { it.generation == activeLoadSession?.generation }
                ?.started = true
            resetPageBridge()
            if (loadState != MapViewLoadState.LOADING) {
                emit(MetamapsMapViewEvent.LoadState(MapViewLoadState.LOADING))
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            activeLoadAttempt
                ?.takeIf { it.generation == activeLoadSession?.generation }
                ?.responseObserved = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val uri = runCatching { url?.let(URI::create) }.getOrNull()
            if (!isAllowed(uri)) {
                handleLoadFailure(
                    nonRetryableWebLoadFailure("WebView completed a disallowed origin"),
                )
                return
            }
            if (currentPageFailed) {
                injectBridge()
                return
            }
            completeLoadAttempt()
            injectBridge()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = runCatching { URI.create(request.url.toString()) }.getOrNull()
                ?: return request.isForMainFrame
            if (isAllowed(uri)) return false
            if (request.isForMainFrame) handleExternalLink(uri)
            return request.isForMainFrame
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            handleLoadFailure(
                retryableWebLoadFailure("${error.errorCode}: ${error.description}"),
            )
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                handleLoadFailure(classifyHttpLoadFailure(errorResponse.statusCode))
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            handler.cancel()
            handleLoadFailure(
                sslLoadFailure("TLS validation failed: ${error.primaryError}"),
            )
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            handleLoadFailure(
                nonRetryableWebLoadFailure("Android WebView renderer terminated"),
            )
            return true
        }
    }

    private inner class MetamapsChromeClient : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(view.context)
            var handled = false
            val destroyUnusedPopup = Runnable {
                if (!handled) {
                    handled = true
                    popup.destroy()
                }
            }
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    popupView: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (!handled) {
                        handled = true
                        popupView.removeCallbacks(destroyUnusedPopup)
                        runCatching { URI.create(request.url.toString()) }
                            .getOrNull()
                            ?.let(::handleExternalLink)
                        popupView.post { popupView.destroy() }
                    }
                    return true
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            popup.postDelayed(destroyUnusedPopup, POPUP_DESTROY_FALLBACK_MS)
            return true
        }
    }

    private companion object {
        const val BEACON_SIGNAL_PRESENTATION_INTERVAL_MS = 200L
        const val CENTER_RETICLE_REQUEST_TIMEOUT_MS = 5_000L
        const val INITIAL_DOCUMENT_TIMEOUT_MS = 30_000L
        const val POPUP_DESTROY_FALLBACK_MS = 5_000L
        const val JS_INTERFACE = "MetamapNative"
    }

    private data class LoadSession(
        val generation: Long,
        val job: Job,
    )

    private data class LoadAttempt(
        val generation: Long,
        val completion: CompletableDeferred<LoadAttemptResult>,
        var settled: Boolean = false,
        var started: Boolean = false,
        var responseObserved: Boolean = false,
    )

    private sealed interface LoadAttemptResult {
        data object Success : LoadAttemptResult
        data class Failure(val failure: MapLoadFailure) : LoadAttemptResult
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun Map<String, Any?>?.uuid(key: String): UUID? =
    (this?.get(key) as? String)?.takeIf(String::isNotBlank)?.let(UUID::fromString)

private fun MetamapsError.toPayload(): Map<String, Any?> = mapOf(
    "code" to code.wireValue,
    "message" to message,
    "recoverable" to recoverable,
    "userAction" to userAction.wireValue,
    "debugDetail" to debugDetail,
)

private fun CapabilityReport.toPayload(): Map<String, Any?> = mapOf(
    "platform" to platform,
    "osVersion" to osVersion,
    "sdkVersion" to sdkVersion,
    "ble" to mapOf(
        "supported" to ble.supported,
        "enabled" to ble.enabled,
        "rangingAvailable" to ble.rangingAvailable,
        "foregroundScan" to ble.foregroundScan,
        "backgroundScan" to ble.backgroundScan,
        "directionFinding" to ble.directionFinding,
        "channelSounding" to ble.channelSounding,
    ),
    "authorization" to mapOf(
        "location" to authorization.location.wireValue,
        "preciseLocation" to authorization.preciseLocation,
        "bluetoothScan" to authorization.bluetoothScan,
        "motion" to authorization.motion.wireValue,
    ),
    "sensors" to mapOf(
        "stepDetector" to sensors.stepDetector,
        "rotationVector" to sensors.rotationVector,
        "gyroscope" to sensors.gyroscope,
        "magnetometer" to sensors.magnetometer,
        "barometer" to sensors.barometer,
    ),
    "selectedProfile" to selectedProfile,
)

internal fun MotionHeadingReading.toPayload(): Map<String, Any?> = mapOf(
    "localHeadingDeg" to localHeadingDeg,
    "magneticFieldAccuracy" to magneticFieldAccuracy,
)

private fun BeaconSignalReading.toPayload(): Map<String, Any?> = mapOf(
    "beaconId" to beaconId.toString().lowercase(),
    "uuid" to uuid,
    "major" to major,
    "minor" to minor,
    "floorId" to floorId.toString().lowercase(),
    "rssiDbm" to rssiDbm,
    "smoothedRssiDbm" to smoothedRssiDbm,
    "radioDistanceM" to radioDistanceM,
    "configuredPosition" to mapOf(
        "x" to configuredPosition.x,
        "y" to configuredPosition.y,
        "z" to configuredPosition.z,
    ),
    "configuredWgs84" to mapOf(
        "longitude" to configuredWgs84.longitude,
        "latitude" to configuredWgs84.latitude,
        "elevationM" to configuredWgs84.elevationM,
    ),
    "devicePosition" to devicePosition?.let {
        mapOf("x" to it.x, "y" to it.y, "z" to it.z)
    },
    "deviceWgs84" to deviceWgs84?.let {
        mapOf(
            "longitude" to it.longitude,
            "latitude" to it.latitude,
            "elevationM" to it.elevationM,
        )
    },
    "configuredDistanceM" to configuredDistanceM,
    "distanceDeltaM" to distanceDeltaM,
    "monotonicTimestampMs" to monotonicTimestampMs,
)

internal fun List<BeaconSignalReading>.toBridgePayload(): List<Map<String, Any?>> =
    map { it.toPayload() }

private fun PositioningUpdate.toPayload(): Map<String, Any?> = mapOf(
    "mapId" to mapId.toString().lowercase(),
    "groupId" to groupId.toString().lowercase(),
    "estimate" to estimate.toPayload(),
    "wgs84" to wgs84?.let {
        mapOf(
            "longitude" to it.longitude,
            "latitude" to it.latitude,
            "elevationM" to it.elevationM,
        )
    },
)

private fun PositionEstimate.toPayload(): Map<String, Any?> = mapOf(
    "sequence" to sequence,
    "monotonicTimestampMs" to monotonicTimestampMs,
    "status" to status,
    "mode" to mode,
    "floorId" to floorId,
    "floorProbability" to floorProbability,
    "local" to local?.let { mapOf("x" to it.x, "y" to it.y, "z" to it.z) },
    "accuracyRadiusM" to accuracyRadiusM,
    "headingDeg" to headingDeg,
    "headingAccuracyDeg" to headingAccuracyDeg,
    "speedMps" to speedMps,
    "freshBeaconCount" to freshBeaconCount,
    "lastBleAgeMs" to lastBleAgeMs,
    "manifestRevision" to manifestRevision,
    "algorithmVersion" to algorithmVersion,
    "stale" to stale,
    "diagnosticFlags" to diagnosticFlags,
)
