package jp.metamaps.metamap_positioning

import android.content.Context
import android.view.View
import jp.metamaps.mapview.MapViewPositioningPolicy
import jp.metamaps.mapview.MapViewPositioningStartTrigger
import jp.metamaps.mapview.MetamapMapView
import jp.metamaps.mapview.MetamapMapViewConfiguration
import jp.metamaps.mapview.MetamapMapViewRetryPolicy
import jp.metamaps.mapview.MetamapExternalLinkDecision
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.common.StandardMessageCodec
import java.net.URI
import java.util.UUID

internal class FlutterMapViewFactory(
    private val owner: MetamapPositioningPlugin,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val parameters = args as? Map<*, *> ?: emptyMap<String, Any?>()
        val nativeView = MetamapMapView(context)
        val id = viewId.toLong()
        nativeView.eventListener = { event ->
            owner.emit(event.toFlutter(id))
        }
        nativeView.externalLinkHandler = { uri ->
            owner.decideMapViewExternalLink(id, uri, nativeView)
            MetamapExternalLinkDecision.HANDLED
        }
        nativeView.configure(parameters.toConfiguration())
        owner.registerMapView(id, nativeView)
        return FlutterMapView(id, nativeView, owner)
    }
}

private class FlutterMapView(
    private val id: Long,
    private val mapView: MetamapMapView,
    private val owner: MetamapPositioningPlugin,
) : PlatformView {
    override fun getView(): View = mapView

    override fun dispose() {
        owner.unregisterMapView(id, mapView)
    }
}

private fun Map<*, *>.toConfiguration(): MetamapMapViewConfiguration {
    val baseUrl = requiredString("baseUrl")
    val mapSlug = requiredString("mapSlug")
    return MetamapMapViewConfiguration(
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
            "automatic" -> MetamapMapViewRetryPolicy.AUTOMATIC
            "disabled" -> MetamapMapViewRetryPolicy.DISABLED
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
