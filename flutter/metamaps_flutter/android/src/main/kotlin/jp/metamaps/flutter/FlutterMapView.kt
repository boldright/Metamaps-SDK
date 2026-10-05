package jp.metamaps.flutter

import android.content.Context
import android.view.View
import jp.metamaps.mapview.MapViewPositioningPolicy
import jp.metamaps.mapview.MapViewPositioningStartTrigger
import jp.metamaps.mapview.MetamapsMapView
import jp.metamaps.mapview.MetamapsMapViewConfiguration
import jp.metamaps.mapview.MetamapsMapViewRetryPolicy
import jp.metamaps.mapview.MetamapsExternalLinkDecision
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.common.StandardMessageCodec
import java.net.URI
import java.util.UUID

internal class FlutterMapViewFactory(
    private val owner: MetamapsPlugin,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val parameters = args as? Map<*, *> ?: emptyMap<String, Any?>()
        val nativeView = MetamapsMapView(context)
        val id = viewId.toLong()
        nativeView.eventListener = { event ->
            owner.emit(event.toFlutter(id))
        }
        nativeView.externalLinkHandler = { uri ->
            owner.decideMapViewExternalLink(id, uri, nativeView)
            MetamapsExternalLinkDecision.HANDLED
        }
        nativeView.configure(parameters.toConfiguration())
        owner.registerMapView(id, nativeView)
        return FlutterMapView(id, nativeView, owner)
    }
}

private class FlutterMapView(
    private val id: Long,
    private val mapView: MetamapsMapView,
    private val owner: MetamapsPlugin,
) : PlatformView {
    override fun getView(): View = mapView

    override fun dispose() {
        owner.unregisterMapView(id, mapView)
    }
}

private fun Map<*, *>.toConfiguration(): MetamapsMapViewConfiguration {
    val baseUrl = requiredString("baseUrl")
    val mapSlug = requiredString("mapSlug")
    return MetamapsMapViewConfiguration(
        baseUrl = URI.create(baseUrl),
        mapSlug = mapSlug,
        groupId = optionalUuid("groupId"),
        language = optionalString("language"),
        initialFloorId = optionalUuid("initialFloorId"),
        previewToken = optionalString("previewToken"),
        positioningPolicy = when (requiredString("positioningPolicy")) {
            "userInitiated" -> MapViewPositioningPolicy.USER_INITIATED
            "hostControlled" -> MapViewPositioningPolicy.HOST_CONTROLLED
            "disabled" -> MapViewPositioningPolicy.DISABLED
            else -> throw IllegalArgumentException("Unsupported positioningPolicy")
        },
        // creationParams from older Dart code have no fields. Fall back to the default automatic start.
        positioningStartTrigger = when (
            optionalString("positioningStartTrigger") ?: "automaticWhenAuthorized"
        ) {
            "userAction" -> MapViewPositioningStartTrigger.USER_ACTION
            "automatic" -> MapViewPositioningStartTrigger.AUTOMATIC
            "automaticWhenAuthorized" -> MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED
            else -> throw IllegalArgumentException("Unsupported positioningStartTrigger")
        },
        showsBeaconDiagnostics = this["showsBeaconDiagnostics"] == true,
        additionalQuery = stringMap("additionalQuery"),
        retryPolicy = when (optionalString("retryPolicy") ?: "automatic") {
            "automatic" -> MetamapsMapViewRetryPolicy.AUTOMATIC
            "disabled" -> MetamapsMapViewRetryPolicy.DISABLED
            else -> throw IllegalArgumentException("Unsupported retryPolicy")
        },
        userAgentAppendix = optionalString("userAgentAppendix"),
        isWebViewInspectable = this["isWebViewInspectable"] == true,
    )
}

private fun Map<*, *>.requiredString(key: String): String =
    (this[key] as? String)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing or invalid $key")

private fun Map<*, *>.optionalString(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun Map<*, *>.optionalUuid(key: String): UUID? =
    optionalString(key)?.let(UUID::fromString)

private fun Map<*, *>.stringMap(key: String): Map<String, String> =
    (this[key] as? Map<*, *>)
        ?.entries
        ?.associate { (entryKey, value) ->
            (entryKey as? String ?: throw IllegalArgumentException("$key keys must be strings")) to
                (value as? String ?: throw IllegalArgumentException("$key values must be strings"))
        }
        ?: emptyMap()
