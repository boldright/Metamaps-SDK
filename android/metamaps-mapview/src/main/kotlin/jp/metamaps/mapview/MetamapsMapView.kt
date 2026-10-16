@file:OptIn(MetamapsInternalApi::class)

package jp.metamaps.mapview

import android.Manifest
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
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.speech.SpeechRecognizer
import android.util.AttributeSet
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
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
import jp.metamaps.positioning.android.ActivityPermissionRequester
import jp.metamaps.positioning.android.BeaconSignalReading
import jp.metamaps.positioning.android.CapabilityReport
import jp.metamaps.positioning.android.LocationAuthorizationState
import jp.metamaps.positioning.android.MetamapsInternalApi
import jp.metamaps.positioning.android.MetamapsPositioningClient
import jp.metamaps.positioning.android.MetamapsPositioningEvent
import jp.metamaps.positioning.android.MotionHeadingReading
import jp.metamaps.positioning.android.PermissionRequirements
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * - Call every method on the main thread.
 * - Host commands such as [selectFloor], [showSpot], [setDestination], and [setLanguage] throw a [MetamapsError]
 *   with `BRIDGE_HANDSHAKE_FAILED` until [MetamapsMapViewEvent.Ready]. They throw again from the moment the WebView
 *   starts loading another page, such as after [setLanguage] or [reload], until the next `Ready`.
 * - GPS location in the map needs `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, and voice input needs
 *   `RECORD_AUDIO`, declared in your app's manifest. When the map uses one of them before it is granted, the view
 *   asks your app through [permissionRequestHandler]; the SDK never shows a permission dialog for them on its own.
 * - The page's safe area (`env(safe-area-inset-*)`) gets only the system bars and display cutout that overlap this
 *   view, on Android 11 and later. Pad the parent, consume the insets, or draw the map edge to edge; the map does not
 *   add the space twice.
 */
@SuppressLint("SetJavaScriptEnabled")
class MetamapsMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    /**
     * The WebView that shows the map. Do not control it directly; use the public commands.
     *
     * Read this property when you need it instead of keeping the instance: a new WebView replaces it after the
     * WebView renderer process ends, and anything set on the old one is lost. Do not replace its `WebViewClient`
     * or `WebChromeClient`; the SDK uses them for links, permissions, and recovery.
     */
    var webView: WebView = WebView(context)
        private set
    private val defaultUserAgent = webView.settings.userAgentString.orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Runs work for views that are never attached to a window, whose own post() queue never runs. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableEvents = MutableSharedFlow<MetamapsMapViewEvent>(extraBufferCapacity = 64)
    private val mutableCanGoBack = MutableStateFlow(false)
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
    /** The main frame document URL last requested, used to tell main frame TLS errors from subresource ones. */
    private var mainFrameUrl: URI? = null
    private var pendingBeaconSignals: List<BeaconSignalReading>? = null
    /** Waits for beacon signals and retries automatic positioning. Never advances in a `USER_ACTION` map view. */
    private val automaticLoop = AutomaticPositioningLoop()
    private var automaticTimerJob: Job? = null
    /** `capabilities` re-evaluated on returning to the foreground. Sent again only when they differ from the last value. */
    private var lastSentCapabilities: CapabilityReport? = null
    /** The automatic positioning configuration last announced in `bridge.hello`, used to decide whether a correction is needed. */
    private var lastSentAutomaticPositioning: Boolean? = null
    /** Whether voice input was last announced in `bridge.hello` as available. */
    private var lastSentSpeechRecognition: Boolean? = null
    private var windowVisible = false
    /** Map permission requests waiting for the host. The first one has been passed to the host once `presented`. */
    private val pendingPermissions = ArrayDeque<PendingMapPermission>()
    private val declaredPermissions by lazy { PermissionRequirements.declaredPermissions(context) }
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

    /**
     * A SharedFlow of map, positioning, and error events. It has no replay, so start collecting before [load];
     * events sent while nothing collects are lost. [eventListener] receives the same events.
     */
    val events: SharedFlow<MetamapsMapViewEvent> = mutableEvents.asSharedFlow()

    /**
     * Whether the web map has a step that going back closes, such as an open spot. Enable the host's back handling,
     * such as an `OnBackPressedCallback`, while this is `true`, and call [goBack] from it. `false` until the map is
     * ready, and again whenever the WebView loads a new page.
     */
    val canGoBack: StateFlow<Boolean> = mutableCanGoBack.asStateFlow()

    /** The current map load state. */
    @Volatile
    var loadState: MapViewLoadState = MapViewLoadState.IDLE
        private set

    /** A listener for receiving events without the SharedFlow. Always called on the main thread. */
    var eventListener: ((MetamapsMapViewEvent) -> Unit)? = null

    /**
     * Decides how a link that leaves the map opens. It is also called for downloads and for links that open a new
     * window, including ones on the map's own origin. Return `HANDLED` to open the link yourself, for example after
     * a confirmation dialog, through [openExternalLink]. [MetamapsMapViewEvent.ExternalLinkRequested] is emitted
     * either way, so do not open the link again from that event.
     */
    var externalLinkHandler: ((URI) -> MetamapsExternalLinkDecision)? = null

    /**
     * Receives the map's requests for a permission that your app declares but has not been granted, such as when the
     * user taps the location button or the microphone. Answer each request once with
     * [MetamapsMapPermissionRequest.request], [MetamapsMapPermissionRequest.finish], or
     * [MetamapsMapPermissionRequest.decline]; the map waits until then, and the view passes you one request at a time. When this is `null`, the view denies such requests at once. Either
     * way, a denied request emits [MetamapsMapViewEvent.Error] with the `userAction` your app's guidance should follow.
     */
    var permissionRequestHandler: ((MetamapsMapPermissionRequest) -> Unit)? = null

    init {
        configureWebView()
    }

    /**
     * Validates and applies the map and positioning configuration. Call it before [load]. After changing the
     * configuration of a loaded view, call [reload]; until then host commands throw, because the loaded page belongs
     * to the previous configuration.
     */
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
        // The loaded page belongs to the previous configuration until the host reloads it.
        resetPageBridge()
        configuration = value
        applyUserAgent(value)
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

    /**
     * Loads the web map and waits for a successful HTTP response and the end of loading. Does not wait for `Ready`.
     *
     * A newer [load] or [reload], [dispose], or cancelling the calling coroutine ends this call with a
     * `CancellationException`. When the map later fails, for example because the WebView renderer process ended,
     * the view emits `LoadState(FAILED)` and an error whose `userAction` is `RETRY`; call [reload] to recover.
     */
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

    /**
     * Requests the Android permissions that indoor positioning needs. It may show OS permission dialogs, so call it
     * from a user action. It never requests `RECORD_AUDIO`, and it requests location only for maps with indoor
     * positioning. Voice input and GPS location in the map ask your app through [permissionRequestHandler] instead.
     */
    suspend fun requestPositioningAuthorization(activity: Activity? = context.findActivity()) {
        ensureNotDisposed("requestPositioningAuthorization")
        val client = positioningClient ?: throw invariant("MapView is not configured")
        if (client.status == PositioningLifecycleStatus.UNCONFIGURED) client.configure()
        validateManifestIdentityIfReady()
        // A null Activity, such as from a plugin that is not attached to one, falls back to the view's own Activity.
        val host = activity ?: context.findActivity() ?: throw MetamapsError(
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

    /**
     * Closes the web map's last step, as the browser's back button does on the web, such as an open spot. Returns
     * `false` and does nothing when [canGoBack] is `false`, so that the host can handle back itself.
     */
    fun goBack(): Boolean {
        checkMainThread()
        if (!mutableCanGoBack.value || !bridgeValidator.handshakeComplete) return false
        send("map.goBack", emptyMap<String, Any?>())
        return true
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
     * Fails with a [MetamapsError] (`BRIDGE_HANDSHAKE_FAILED`) when the page changes, the view is disposed, or the
     * map does not answer within 5 seconds.
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
        // Unsupported external schemes are intentionally ignored.
        startOutsideActivity(Intent(Intent.ACTION_VIEW, androidUri))
    }

    /**
     * Ends positioning, the WebView, and subscriptions, and releases held resources. Call it even in apps that only
     * show maps: every configured view registers application lifecycle callbacks that only this releases. Calling it
     * again does nothing.
     */
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
        failPendingCenterReticleRequests(
            bridgeError(MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED, "MapView disposed during center reticle request"),
        )
        clearBeaconSignalPresentation()
        denyPendingPermissions()
        webView.stopLoading()
        webView.removeJavascriptInterface(JS_INTERFACE)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        removeView(webView)
        webView.destroy()
        updateCanGoBack(false)
        emit(MetamapsMapViewEvent.LoadState(MapViewLoadState.DISPOSED))
        eventListener = null
        externalLinkHandler = null
        permissionRequestHandler = null
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
        val url = mapUrl(config)
        mainFrameUrl = url
        webView.loadUrl(url.toString())
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
                // Bridge state belongs to the main thread; only the manifest download runs in the background.
                withContext(Dispatchers.Main.immediate) {
                    validateManifestIdentityIfReady()
                    refreshHelloAnnouncement()
                }
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
                // A position from the manifest of another map or revision is not painted. The handshake stopped
                // positioning in that case, and preparePositioningIfNeeded configures the manifest of this page.
                if (bridgeValidator.handshakeComplete && manifestMatchesPage()) {
                    send("positioning.position", event.update.toPayload())
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
                refreshHelloAnnouncement()
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
        if (disposed || token != pageToken ||
            !isAllowed(webView.url?.let { runCatching { URI.create(it) }.getOrNull() })
        ) {
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
                // A manifest from an older revision must not block the map: preparePositioningIfNeeded configures
                // the manifest of this page after the handshake, and positioning commands validate it (15 §3).
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
            "map.canGoBackChanged" -> envelope.canGoBack?.let(::updateCanGoBack)
            "map.share" -> envelope.shareRequest?.let(::openShareSheet)
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

    /** Whether the verified manifest belongs to the loaded page. `true` while there is no page or no manifest yet. */
    private fun manifestMatchesPage(): Boolean = runCatching { validateManifestIdentityIfReady() }.isSuccess

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
            if (manifestMatchesPage()) {
                positioningClient?.latestUpdate?.let { send("positioning.position", it.toPayload()) }
            } else {
                // 15 §3: stop positioning that runs on the manifest of another map or revision. The map stays usable,
                // and preparePositioningIfNeeded configures the manifest of this page.
                stopPositioning()
            }
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
        val speechRecognition = isSpeechRecognitionAvailable()
        lastSentCapabilities = capabilities
        lastSentAutomaticPositioning = automaticPositioning
        lastSentSpeechRecognition = speechRecognition
        send(
            "bridge.hello",
            mapOf(
                "sdkVersion" to MetamapsSdk.VERSION,
                "platform" to "android",
                "capabilities" to capabilities.toPayload(),
                // Backward-compatible addition in bridge 1.4. True only for map views configured for automatic positioning.
                "automaticPositioning" to automaticPositioning,
                // Backward-compatible addition in bridge 1.5. The web map hides its microphone button when false, that
                // is, only when the app does not declare RECORD_AUDIO or the device has no speech recognition service.
                "speechRecognition" to speechRecognition,
                // Backward-compatible addition in bridge 1.6. Android WebView has no Web Share API, so the web map
                // sends `map.share` to the OS share sheet instead of copying the link.
                "share" to true,
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
            hasVerifiedManifest = client.manifestIdentity != null && manifestMatchesPage(),
        )
    }

    /** Whether the web map's voice input can work (`bridge.hello.payload.speechRecognition`). */
    private fun isSpeechRecognitionAvailable(): Boolean {
        val baseUrl = configuration?.baseUrl ?: return false
        return isWebSpeechRecognitionAvailable(
            baseUrl,
            Manifest.permission.RECORD_AUDIO in declaredPermissions,
            SpeechRecognizer.isRecognitionAvailable(context),
        )
    }

    /**
     * If the manifest download or permission decision finishes after the handshake, send the same `bridge.hello`
     * again to correct the automatic positioning configuration in the runtime. If the first announcement said
     * false and is never corrected, automatic positioning never starts for that page's lifetime. The same correction
     * shows or hides the voice input button when the device's speech recognition service changes.
     */
    private fun refreshHelloAnnouncement() {
        if (disposed || !bridgeValidator.handshakeComplete) return
        if (isAutomaticPositioningEnabled() != lastSentAutomaticPositioning ||
            isSpeechRecognitionAvailable() != lastSentSpeechRecognition
        ) {
            sendHello()
        }
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
        if (disposed || positioningClient == null) return
        refreshPermissionDependentState()
        runAutomaticEffects(automaticLoop.returnToForeground())
    }

    /**
     * Re-evaluates what the app's permissions decide: `capabilities`, then the hello announcement. Capabilities go
     * first because `sendHello` records them as sent, which would otherwise hide a change from the host.
     */
    private fun refreshPermissionDependentState() {
        val client = positioningClient ?: return
        val capabilities = client.capabilities()
        if (capabilities != lastSentCapabilities && bridgeValidator.handshakeComplete) {
            lastSentCapabilities = capabilities
            emit(MetamapsMapViewEvent.Capabilities(capabilities))
            send("positioning.capabilities", capabilities.toPayload())
        }
        refreshHelloAnnouncement()
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

    /**
     * The app's own permission dialog closes over a visible map without changing window visibility, but the window
     * regains focus. Re-evaluate so that newly granted positioning permissions reach the map without a reload.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && !disposed) refreshPermissionDependentState()
    }

    // ── Map permissions: GPS location and voice input ─────────────────
    /**
     * Grants a map permission request that the app already holds, denies one the app cannot ask for, and passes the
     * rest to [permissionRequestHandler] one at a time.
     */
    private fun decideMapPermission(
        permission: MetamapsMapPermission,
        key: Any?,
        grant: () -> Unit,
        deny: () -> Unit,
    ) {
        val status = currentPermissionStatus(permission)
        when {
            status == MapPermissionStatus.GRANTED -> grant()
            status == MapPermissionStatus.NOT_DECLARED || permissionRequestHandler == null -> {
                deny()
                val canAskAgain = canAskAgain(permission, activity = null, requestedNow = false)
                emit(MetamapsMapViewEvent.Error(mapPermissionDeniedError(permission, status, canAskAgain)))
            }
            else -> {
                pendingPermissions.addLast(PendingMapPermission(permission, key, grant, deny))
                presentNextPermission()
            }
        }
    }

    private fun presentNextPermission() {
        val next = pendingPermissions.firstOrNull() ?: return
        if (next.presented) return
        val handler = permissionRequestHandler
        if (handler == null) {
            // The host removed its handler while the request waited.
            settlePermission(next) { canAskAgain(next.permission, activity = null, requestedNow = false) }
            return
        }
        next.presented = true
        val request = MetamapsMapPermissionRequest(
            next.permission,
            onRequest = { activity -> onMain { requestPermissionFromActivity(next, activity) } },
            onFinish = {
                onMain {
                    // The host asked the OS itself. Record it, as the SDK's own requests do, so that a later denial
                    // without a rationale reads as one the OS no longer shows a dialog for.
                    PermissionRequirements.markRequested(context, next.permission.androidPermissions)
                    settlePermission(next) { canAskAgain(next.permission, activity = null, requestedNow = true) }
                }
            },
            onDecline = {
                onMain { settlePermission(next) { canAskAgain(next.permission, activity = null, requestedNow = false) } }
            },
        )
        try {
            handler(request)
        } catch (error: Throwable) {
            // Do not let a failing handler hold the map's request and every request behind it.
            request.decline()
            throw error
        }
    }

    private fun requestPermissionFromActivity(entry: PendingMapPermission, activity: Activity) {
        if (disposed) return
        scope.launch(Dispatchers.Main.immediate) {
            if (!entry.settled) {
                try {
                    ActivityPermissionRequester.request(activity, entry.permission.androidPermissions)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // The OS state read below decides the answer.
                }
            }
            settlePermission(entry) { canAskAgain(entry.permission, activity, requestedNow = true) }
        }
    }

    /**
     * Answers [entry] from the OS permission state after the host answered it, and every other waiting request for
     * the same permission with it, so that one denial does not show the dialog again. Then passes on the next request.
     */
    private fun settlePermission(entry: PendingMapPermission, canAskAgain: () -> Boolean) {
        if (disposed) return
        if (!entry.settled) {
            val status = currentPermissionStatus(entry.permission)
            val granted = status == MapPermissionStatus.GRANTED
            pendingPermissions
                .filter { it.permission == entry.permission }
                .plus(entry)
                .forEach { it.settle(granted) }
            if (!granted) {
                emit(MetamapsMapViewEvent.Error(mapPermissionDeniedError(entry.permission, status, canAskAgain())))
            }
        }
        pendingPermissions.remove(entry)
        pendingPermissions.removeAll { it.settled && !it.presented }
        presentNextPermission()
    }

    /**
     * The WebView withdrew a request, for example because the page stopped asking. A request the host already has stays
     * first in line until the host answers it, so that two permission dialogs never overlap.
     */
    private fun cancelPendingPermissions(permission: MetamapsMapPermission, key: Any?) {
        pendingPermissions
            .filter { it.permission == permission && (key == null || it.key === key) }
            .forEach { it.settled = true }
        pendingPermissions.removeAll { it.settled && !it.presented }
    }

    private fun denyPendingPermissions() {
        val pending = pendingPermissions.toList()
        pendingPermissions.clear()
        pending.forEach { it.settle(false) }
    }

    private fun currentPermissionStatus(permission: MetamapsMapPermission): MapPermissionStatus = mapPermissionStatus(
        permission,
        isDeclared = declaredPermissions::contains,
        isGranted = { PermissionRequirements.isGranted(context, it) },
    )

    /** Whether a new request can still show the OS dialog. See [canAskForMapPermissionAgain]. */
    private fun canAskAgain(permission: MetamapsMapPermission, activity: Activity?, requestedNow: Boolean): Boolean {
        val host = activity ?: context.findActivity() ?: return true
        return canAskForMapPermissionAgain(
            missing = permission.androidPermissions.filterNot { PermissionRequirements.isGranted(context, it) },
            requestedNow = requestedNow,
            showsRationale = host::shouldShowRequestPermissionRationale,
            wasRequestedBefore = { PermissionRequirements.wasRequested(context, it) },
        )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
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

    /** Opens the OS share sheet with the web map's Web Share data. */
    private fun openShareSheet(request: MapShareRequest) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, request.body)
            request.text?.let { putExtra(Intent.EXTRA_TITLE, it) }
        }
        // When no app receives text, nothing happens, as when the user closes the share sheet.
        startOutsideActivity(Intent.createChooser(send, null))
    }

    /** Starts an activity outside the map, ignoring an intent that no app handles. */
    private fun startOutsideActivity(intent: Intent) {
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing to open.
        }
    }

    private fun updateCanGoBack(value: Boolean) {
        if (mutableCanGoBack.value == value) return
        mutableCanGoBack.value = value
        emit(MetamapsMapViewEvent.CanGoBackChanged(value))
    }

    private fun resetPageBridge() {
        // The history the web map can go back through belongs to the previous page. Going back across pages would
        // reload the previous page, such as the one before a language change.
        updateCanGoBack(false)
        // A CancellationException here would silently cancel the caller's coroutine instead of failing the request.
        failPendingCenterReticleRequests(
            bridgeError(MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED, "Map document changed during center reticle request"),
        )
        // Permission requests belong to the document that made them. A later answer from the host does nothing.
        denyPendingPermissions()
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
        return isSameOrigin(uri, config.baseUrl)
    }

    private fun handleExternalLink(uri: URI) {
        if (!isAllowed(uri)) handleOutsideWebView(uri)
    }

    /** Downloads and new windows never open in the WebView, even for the map origin. */
    private fun handleOutsideWebView(uri: URI) {
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

    private fun applyUserAgent(value: MetamapsMapViewConfiguration) {
        webView.settings.userAgentString = buildMetamapsUserAgent(
            defaultUserAgent,
            MetamapsSdk.VERSION,
            value.userAgentAppendix,
        )
    }

    /** Android cannot reuse a WebView after its renderer ends, so the next [reload] loads into a new one. */
    private fun replaceWebViewAfterRendererGone() {
        val gone = webView
        removeView(gone)
        gone.destroy()
        webView = WebView(context)
        configureWebView()
        resetPageBridge()
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
            // Without an explicit value, Android WebView scales every text by the system font scale, which breaks the
            // map's fixed-size UI. iOS WKWebView does not follow Dynamic Type either.
            textZoom = 100
            // The web map's GPS uses the Geolocation API. MetamapsChromeClient decides each request.
            setGeolocationEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.addJavascriptInterface(nativeBridge, JS_INTERFACE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WebViewSafeAreaInsets(webView).install()
        webView.webViewClient = MetamapsWebViewClient()
        webView.webChromeClient = MetamapsChromeClient()
        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            runCatching { URI.create(url) }.getOrNull()?.let(::handleOutsideWebView)
        })
        configuration?.let(::applyUserAgent)
        addView(
            webView,
            0,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    internal inner class NativeBridge {
        @JavascriptInterface
        fun postMessage(message: String, token: String) {
            post { receive(message, token) }
        }
    }

    private inner class MetamapsWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            url?.let { runCatching { URI.create(it) }.getOrNull() }?.let { mainFrameUrl = it }
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
            if (isAllowed(uri)) {
                if (request.isForMainFrame) mainFrameUrl = uri
                return false
            }
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
            if (!isMainFrameDocumentUrl(error.url, mainFrameUrl)) return
            handleLoadFailure(
                sslLoadFailure("TLS validation failed: ${error.primaryError}"),
            )
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (disposed || view !== webView) return true
            replaceWebViewAfterRendererGone()
            handleLoadFailure(
                nonRetryableWebLoadFailure("Android WebView renderer terminated"),
            )
            return true
        }
    }

    private inner class MetamapsChromeClient : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?,
        ) {
            // Do not retain the decision, so a revoked permission or a changed base URL applies to the next request.
            val grant: () -> Unit = { callback?.invoke(origin, true, false) }
            val deny: () -> Unit = { callback?.invoke(origin, false, false) }
            val baseUrl = configuration?.baseUrl
            if (disposed || baseUrl == null || !isMapGeolocationRequest(origin, baseUrl)) {
                deny()
                return
            }
            decideMapPermission(MetamapsMapPermission.LOCATION, callback, grant, deny)
        }

        override fun onGeolocationPermissionsHidePrompt() {
            cancelPendingPermissions(MetamapsMapPermission.LOCATION, key = null)
        }

        /** The web map's voice input asks for the microphone through `webkitSpeechRecognition`. */
        override fun onPermissionRequest(request: PermissionRequest) {
            val baseUrl = configuration?.baseUrl
            if (disposed || baseUrl == null ||
                !isMapMicrophoneRequest(request.origin?.toString(), request.resources.toList(), baseUrl)
            ) {
                request.deny()
                return
            }
            decideMapPermission(
                MetamapsMapPermission.MICROPHONE,
                request,
                { request.grant(request.resources) },
                { request.deny() },
            )
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            cancelPendingPermissions(MetamapsMapPermission.MICROPHONE, key = request)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            // The popup only captures the URL of the new window and is never attached to a window, so its own
            // post() queue never runs. Schedule its destruction on the main looper instead.
            val popup = WebView(view.context)
            var handled = false
            var destroyed = false
            val destroyPopup = Runnable {
                if (!destroyed) {
                    destroyed = true
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
                        mainHandler.removeCallbacks(destroyPopup)
                        runCatching { URI.create(request.url.toString()) }
                            .getOrNull()
                            ?.let(::handleOutsideWebView)
                        mainHandler.post(destroyPopup)
                    }
                    return true
                }

                // The popup shares the renderer with the map. Returning false would let a renderer crash kill the
                // app even though the map view itself recovers.
                override fun onRenderProcessGone(popupView: WebView, detail: RenderProcessGoneDetail): Boolean {
                    mainHandler.removeCallbacks(destroyPopup)
                    destroyPopup.run()
                    return true
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            mainHandler.postDelayed(destroyPopup, POPUP_DESTROY_FALLBACK_MS)
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

    /** A web permission request that waits for the host. [key] identifies the WebView request it answers. */
    private class PendingMapPermission(
        val permission: MetamapsMapPermission,
        val key: Any?,
        private val grant: () -> Unit,
        private val deny: () -> Unit,
    ) {
        var presented = false
        var settled = false

        fun settle(granted: Boolean) {
            if (settled) return
            settled = true
            // The WebView may have dropped the request with its document; answering a stale request does nothing.
            runCatching { if (granted) grant() else deny() }
        }
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
