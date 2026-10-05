package jp.metamaps.metamap_positioning

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import jp.metamaps.mapview.MetamapMapView
import jp.metamaps.positioning.android.MetamapPositioningClient
import jp.metamaps.positioning.android.MotionPolicy
import jp.metamaps.positioning.android.PositioningConfiguration
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MetamapPositioningPlugin :
    FlutterPlugin,
    ActivityAware,
    MetamapHostApi {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextClientId = AtomicLong(1)
    private val clients = ConcurrentHashMap<Long, ClientEntry>()
    private val mapViews = ConcurrentHashMap<Long, MetamapMapView>()
    private lateinit var applicationContext: Context
    private lateinit var flutterApi: MetamapFlutterApi
    private lateinit var events: FlutterEvents
    private var activity: Activity? = null
    private var scope = newScope()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        scope = newScope()
        MetamapHostApi.setUp(binding.binaryMessenger, this)
        flutterApi = MetamapFlutterApi(binding.binaryMessenger)
        events = FlutterEvents(mainHandler)
        EventsStreamHandler.register(binding.binaryMessenger, events)
        binding.platformViewRegistry.registerViewFactory(
            MAP_VIEW_TYPE,
            FlutterMapViewFactory(this),
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        MetamapHostApi.setUp(binding.binaryMessenger, null)
        clients.values.forEach { entry ->
            entry.eventsJob.cancel()
            entry.client.dispose()
        }
        clients.clear()
        mapViews.values.toList().forEach { view ->
            runOnMain { view.dispose() }
        }
        mapViews.clear()
        events.close()
        scope.cancel()
        activity = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun createClient(
        configuration: NativePositioningConfiguration,
        callback: (Result<NativeClientCreateResult>) -> Unit,
    ) {
        scope.launch {
            val result = runCatching {
                val clientId = nextClientId.getAndIncrement()
                val config = configuration.toNative()
                config.validate()
                val client = MetamapPositioningClient(applicationContext, config)
                val eventsJob = scope.launch {
                    client.events.collect { event ->
                        emit(event.toFlutter(clientId))
                    }
                }
                clients[clientId] = ClientEntry(client, eventsJob)
                NativeClientCreateResult(clientId = clientId)
            }.getOrElse { error ->
                NativeClientCreateResult(error = error.toFlutterError())
            }
            complete(callback, result)
        }
    }

    override fun configureClient(
        clientId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = clientOperation(clientId, callback) { configure() }

    override fun clientCapabilities(
        clientId: Long,
        callback: (Result<NativeCapabilityResult>) -> Unit,
    ) {
        scope.launch {
            val entry = clients[clientId]
            val result = if (entry == null) {
                NativeCapabilityResult(error = missingOwner("client", clientId))
            } else {
                runCatching {
                    NativeCapabilityResult(report = entry.client.capabilities().toFlutter())
                }.getOrElse { error ->
                    NativeCapabilityResult(error = error.toFlutterError())
                }
            }
            complete(callback, result)
        }
    }

    override fun requestClientAuthorization(
        clientId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = clientOperation(clientId, callback) {
        val host = activity ?: throw IllegalStateException(
            "An attached Activity is required to request permissions.",
        )
        requestAuthorization(host)
    }

    override fun startClient(
        clientId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = clientOperation(clientId, callback) { start() }

    override fun stopClient(
        clientId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = clientOperation(clientId, callback) { stop() }

    override fun resetClient(
        clientId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = clientOperation(clientId, callback) { reset() }

    override fun disposeClient(
        clientId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) {
        scope.launch {
            val entry = clients.remove(clientId)
            val result = if (entry == null) {
                NativeOperationResult(error = missingOwner("client", clientId))
            } else {
                runCatching {
                    entry.eventsJob.cancel()
                    entry.client.dispose()
                    NativeOperationResult()
                }.getOrElse { error ->
                    NativeOperationResult(error = error.toFlutterError())
                }
            }
            complete(callback, result)
        }
    }

    override fun resetClientPedestrianRoute(
        clientId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) {
        clientOperation(clientId, callback) { resetPedestrianRoute() }
    }

    override fun loadMapView(
        viewId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewLoadOperation(viewId, callback) { load() }

    override fun reloadMapView(
        viewId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewLoadOperation(viewId, callback) { reload() }

    override fun requestMapViewAuthorization(
        viewId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewSuspendOperation(viewId, callback) {
        requestPositioningAuthorization(activity)
    }

    override fun startMapViewPositioning(
        viewId: Long,
        requestAuthorization: Boolean,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewSuspendOperation(viewId, callback) {
        startPositioning(activity, requestAuthorization)
    }

    override fun stopMapViewPositioning(
        viewId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewOperation(viewId, callback) { stopPositioning() }

    override fun selectMapViewFloor(
        viewId: Long,
        floorId: String?,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewOperation(viewId, callback) {
        selectFloor(floorId?.let(UUID::fromString))
    }

    override fun resetMapViewPedestrianRoute(
        viewId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) {
        mapViewSuspendOperation(viewId, callback) { resetPedestrianRoute() }
    }

    override fun showMapViewSpot(
        viewId: Long,
        spotId: String,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewOperation(viewId, callback) {
        showSpot(spotId)
    }

    override fun setMapViewDestination(
        viewId: Long,
        spotId: String?,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewOperation(viewId, callback) {
        setDestination(spotId)
    }

    override fun setMapViewLanguage(
        viewId: Long,
        language: String,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewOperation(viewId, callback) { setLanguage(language) }

    override fun setMapViewCenterReticleEnabled(
        viewId: Long,
        enabled: Boolean,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewOperation(viewId, callback) { setCenterReticleEnabled(enabled) }

    override fun requestMapViewCenterReticleCandidate(
        viewId: Long,
        callback: (Result<NativeMapCenterReticleCandidateResult>) -> Unit,
    ) {
        scope.launch {
            val view = mapViews[viewId]
            val result = if (view == null) {
                NativeMapCenterReticleCandidateResult(
                    error = missingOwner("mapView", viewId),
                )
            } else {
                runCatching {
                    val candidate = withContext(Dispatchers.Main.immediate) {
                        view.requestCenterReticleCandidate()
                    }
                    NativeMapCenterReticleCandidateResult(
                        candidate = candidate?.let {
                            NativeMapCenterReticleCandidate(
                                floorId = it.floorId.toString().lowercase(),
                                longitude = it.longitude,
                                latitude = it.latitude,
                                local = it.local?.let { local ->
                                    NativeLocalPosition(local.x, local.y, local.z)
                                },
                            )
                        },
                    )
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    NativeMapCenterReticleCandidateResult(error = error.toFlutterError())
                }
            }
            complete(callback, result)
        }
    }

    override fun openMapViewExternalLink(
        viewId: Long,
        url: String,
        callback: (Result<NativeOperationResult>) -> Unit,
    ) = mapViewOperation(viewId, callback) { openExternalLink(URI.create(url)) }

    internal fun emit(event: NativeEvent) {
        events.emit(event)
    }

    internal fun registerMapView(viewId: Long, mapView: MetamapMapView) {
        mapViews.put(viewId, mapView)?.let { previous ->
            runOnMain { previous.dispose() }
        }
    }

    internal fun unregisterMapView(viewId: Long, mapView: MetamapMapView) {
        if (mapViews.remove(viewId, mapView)) {
            runOnMain { mapView.dispose() }
        }
    }

    internal fun decideMapViewExternalLink(
        viewId: Long,
        uri: URI,
        mapView: MetamapMapView,
    ) {
        flutterApi.decideMapViewExternalLinkOpensDefault(viewId, uri.toString()) { result ->
            if (result.getOrNull() != true) return@decideMapViewExternalLinkOpensDefault
            runOnMain {
                if (mapViews[viewId] === mapView) {
                    mapView.openExternalLink(uri)
                }
            }
        }
    }

    private fun NativePositioningConfiguration.toNative(): PositioningConfiguration = PositioningConfiguration(
        baseUrl = URI.create(baseUrl),
        mapSlug = mapSlug,
        groupId = groupId?.let(UUID::fromString),
        beaconDiagnosticsEnabled = beaconDiagnosticsEnabled,
        maxOfflineAgeMs = maxOfflineAgeMs,
        motionPolicy = when (motionPolicy) {
            "preferred" -> MotionPolicy.PREFERRED
            "disabled" -> MotionPolicy.DISABLED
            else -> throw IllegalArgumentException("Unsupported motionPolicy")
        },
    )

    private fun clientOperation(
        clientId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
        block: suspend MetamapPositioningClient.() -> Unit,
    ) {
        scope.launch {
            val entry = clients[clientId]
            val result = if (entry == null) {
                NativeOperationResult(error = missingOwner("client", clientId))
            } else {
                runCatching {
                    entry.client.block()
                    NativeOperationResult()
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    NativeOperationResult(error = error.toFlutterError())
                }
            }
            complete(callback, result)
        }
    }

    private fun mapViewOperation(
        viewId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
        block: MetamapMapView.() -> Unit,
    ) {
        runOnMain {
            val view = mapViews[viewId]
            val result = if (view == null) {
                NativeOperationResult(error = missingOwner("mapView", viewId))
            } else {
                runCatching {
                    view.block()
                    NativeOperationResult()
                }.getOrElse { error ->
                    NativeOperationResult(error = error.toFlutterError())
                }
            }
            callback(Result.success(result))
        }
    }

    private fun mapViewSuspendOperation(
        viewId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
        block: suspend MetamapMapView.() -> Unit,
    ) {
        scope.launch {
            val view = mapViews[viewId]
            val result = if (view == null) {
                NativeOperationResult(error = missingOwner("mapView", viewId))
            } else {
                runCatching {
                    view.block()
                    NativeOperationResult()
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    NativeOperationResult(error = error.toFlutterError())
                }
            }
            complete(callback, result)
        }
    }

    private fun mapViewLoadOperation(
        viewId: Long,
        callback: (Result<NativeOperationResult>) -> Unit,
        block: suspend MetamapMapView.() -> Unit,
    ) {
        scope.launch {
            val view = mapViews[viewId]
            val result = if (view == null) {
                NativeOperationResult(error = missingOwner("mapView", viewId))
            } else {
                try {
                    withContext(Dispatchers.Main.immediate) {
                        view.block()
                    }
                    NativeOperationResult()
                } catch (_: CancellationException) {
                    NativeOperationResult(error = supersededLoad())
                } catch (error: Throwable) {
                    NativeOperationResult(error = error.toFlutterError())
                }
            }
            complete(callback, result)
        }
    }

    private fun <T> complete(callback: (Result<T>) -> Unit, value: T) {
        runOnMain { callback(Result.success(value)) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun missingOwner(type: String, id: Long) = NativeErrorMessage(
        code = "internalInvariantViolation",
        message = "The native $type is unavailable.",
        recoverable = false,
        userAction = "retry",
        debugDetail = "$type $id was not registered.",
    )

    private fun supersededLoad() = NativeErrorMessage(
        code = "webContentLoadFailed",
        message = "The MapView load was superseded.",
        recoverable = true,
        userAction = "retry",
        debugDetail = "superseded",
    )

    private data class ClientEntry(
        val client: MetamapPositioningClient,
        val eventsJob: Job,
    )

    private class FlutterEvents(
        private val mainHandler: Handler,
    ) : EventsStreamHandler() {
        private var sink: PigeonEventSink<NativeEvent>? = null
        private val pending = ArrayDeque<NativeEvent>()

        override fun onListen(p0: Any?, sink: PigeonEventSink<NativeEvent>) {
            this.sink = sink
            while (pending.isNotEmpty()) {
                sink.success(pending.removeFirst())
            }
        }

        override fun onCancel(p0: Any?) {
            sink = null
        }

        fun emit(event: NativeEvent) {
            mainHandler.post {
                val current = sink
                if (current == null) {
                    if (pending.size == MAX_PENDING_EVENTS) pending.removeFirst()
                    pending.addLast(event)
                } else {
                    current.success(event)
                }
            }
        }

        fun close() {
            mainHandler.post {
                sink?.endOfStream()
                sink = null
                pending.clear()
            }
        }
    }

    private companion object {
        const val MAP_VIEW_TYPE = "jp.metamaps/metamap_map_view"
        const val MAX_PENDING_EVENTS = 64

        fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
