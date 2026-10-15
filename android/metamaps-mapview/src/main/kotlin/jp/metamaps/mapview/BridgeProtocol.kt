package jp.metamaps.mapview

import java.net.URI
import java.util.UUID
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsUserAction
import org.json.JSONArray
import org.json.JSONObject

internal data class BridgeEnvelope(
    val schema: String,
    val bridgeVersion: String,
    val type: String,
    val mapId: UUID?,
    val groupId: UUID?,
    val sequence: Long,
    val payload: Map<String, Any?>?,
) {
    val hasSupportedVersion: Boolean
        get() = VERSION.matches(bridgeVersion)

    /** The `code` of a `map.error` event, such as `spot_not_found`, or `unknown` when the payload has no usable code. */
    val mapErrorCode: String
        get() = (payload?.get("code") as? String)?.takeIf { MAP_ERROR_CODE.matches(it) } ?: "unknown"

    /**
     * The host error for a `map.error` event. The web map stays usable, so the error is recoverable and asks the user
     * for nothing.
     */
    val mapOperationError: MetamapsError
        get() = MetamapsError(
            MetamapsError.Code.MAP_OPERATION_FAILED,
            "The web map could not complete the requested operation.",
            true,
            MetamapsUserAction.NONE,
            "map.error: $mapErrorCode",
        )

    /** The value of a `map.canGoBackChanged` event, or `null` when the payload has no boolean value. */
    val canGoBack: Boolean?
        get() = payload?.get("canGoBack") as? Boolean

    /**
     * The Web Share data of a `map.share` request, or `null` when it cannot be shared. The URL must be an absolute HTTP
     * or HTTPS URL of up to 2,048 characters, and the text up to 1,000 characters.
     */
    val shareRequest: MapShareRequest?
        get() {
            val rawUrl = payload?.get("url") as? String ?: return null
            if (rawUrl.length > MAX_SHARE_URL_LENGTH) return null
            val url = runCatching { URI.create(rawUrl) }.getOrNull() ?: return null
            val text = payload["text"]
            if (url.scheme?.lowercase() !in SHARE_URL_SCHEMES ||
                url.host.isNullOrEmpty() ||
                (text != null && (text !is String || text.length > MAX_SHARE_TEXT_LENGTH))
            ) {
                return null
            }
            return MapShareRequest(url, text as String?)
        }

    companion object {
        const val SCHEMA = "metamap.native-bridge.v1"
        private val VERSION = Regex("1\\.\\d+")
        private val MAP_ERROR_CODE = Regex("[a-z0-9_]{1,64}")
        private const val MAX_SHARE_URL_LENGTH = 2048
        private const val MAX_SHARE_TEXT_LENGTH = 1000
        private val SHARE_URL_SCHEMES = setOf("http", "https")

        fun parse(json: String): BridgeEnvelope {
            val value = JSONObject(json)
            val schema = value.getString("schema")
            val bridgeVersion = value.getString("bridgeVersion")
            val type = value.getString("type")
            val sequenceValue = value.get("sequence")
            require(sequenceValue is Number) { "sequence must be a number" }
            val sequence = sequenceValue.toLong()
            require(sequence >= 0 && sequenceValue.toDouble() == sequence.toDouble()) {
                "sequence must be a non-negative integer"
            }
            return BridgeEnvelope(
                schema,
                bridgeVersion,
                type,
                value.optionalUuid("mapId"),
                value.optionalUuid("groupId"),
                sequence,
                value.optJSONObject("payload")?.toMap(),
            )
        }
    }
}

/** The Web Share data of a `map.share` request, which the map view passes to the OS share sheet. */
internal data class MapShareRequest(
    val url: URI,
    val text: String?,
) {
    /** The shared text: the Web Share title and the URL on separate lines, or the URL alone. */
    val body: String
        get() = if (text.isNullOrEmpty()) url.toString() else "$text\n$url"
}

internal data class BridgeReadyContext(
    val mapId: UUID,
    val groupId: UUID,
    val configRevision: String?,
    val manifestRevision: Int?,
)

internal class BridgeValidator(
    private val requestedGroupId: UUID?,
) {
    var ready: BridgeReadyContext? = null
        private set
    var handshakeComplete: Boolean = false
        private set
    var lastInboundSequence: Long = -1
        private set

    fun validate(envelope: BridgeEnvelope): BridgeReadyContext? {
        if (envelope.schema != BridgeEnvelope.SCHEMA) {
            throw bridgeError(MetamapsError.Code.BRIDGE_UNSUPPORTED, "Unsupported bridge schema")
        }
        if (!envelope.hasSupportedVersion) {
            throw bridgeError(MetamapsError.Code.BRIDGE_UNSUPPORTED, "Unsupported bridge version")
        }
        if (envelope.sequence <= lastInboundSequence) {
            throw bridgeError(MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED, "Stale bridge sequence")
        }
        if (envelope.type == "map.ready") {
            val mapId = envelope.mapId
                ?: throw bridgeError(MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED, "map.ready requires mapId")
            val groupId = envelope.groupId
                ?: throw bridgeError(MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED, "map.ready requires groupId")
            ready?.let {
                if (it.mapId != mapId || it.groupId != groupId) {
                    throw bridgeError(
                        MetamapsError.Code.BRIDGE_MAP_MISMATCH,
                        "map.ready attempted to change the pinned map or group",
                    )
                }
            }
            if (requestedGroupId != null && requestedGroupId != groupId) {
                throw bridgeError(MetamapsError.Code.BRIDGE_MAP_MISMATCH, "map.ready returned another group")
            }
            val manifestValue = envelope.payload?.get("manifestRevision")
            val manifestRevision = (manifestValue as? Number)?.let {
                val integer = it.toInt()
                if (integer >= 0 && it.toDouble() == integer.toDouble()) integer else null
            }
            val context = BridgeReadyContext(
                mapId,
                groupId,
                envelope.payload?.get("configRevision") as? String,
                manifestRevision,
            )
            if (ready != context) handshakeComplete = false
            ready = context
            lastInboundSequence = envelope.sequence
            return context
        }
        val pinned = ready
            ?: throw bridgeError(
                MetamapsError.Code.BRIDGE_HANDSHAKE_FAILED,
                "Bridge command arrived before map.ready",
            )
        if (envelope.mapId != pinned.mapId || envelope.groupId != pinned.groupId) {
            throw bridgeError(MetamapsError.Code.BRIDGE_MAP_MISMATCH, "Bridge message map/group mismatch")
        }
        lastInboundSequence = envelope.sequence
        return null
    }

    fun completeHandshake(context: BridgeReadyContext): Boolean {
        if (ready != context) return false
        handshakeComplete = true
        return true
    }

    fun reset() {
        ready = null
        handshakeComplete = false
        lastInboundSequence = -1
    }

    private fun bridgeError(
        code: MetamapsError.Code,
        detail: String,
    ) = MetamapsError(
        code,
        "The native map bridge rejected a message.",
        code != MetamapsError.Code.BRIDGE_MAP_MISMATCH,
        MetamapsUserAction.RETRY,
        detail,
    )
}

internal object BridgeJson {
    fun envelope(
        type: String,
        sequence: Long,
        mapId: UUID?,
        groupId: UUID?,
        payload: Any?,
    ): String = JSONObject().apply {
        put("schema", BridgeEnvelope.SCHEMA)
        // The generation that adds back navigation (`map.canGoBackChanged`, `map.goBack`) and the share sheet
        // (`bridge.hello.payload.share`, `map.share`).
        put("bridgeVersion", "1.6")
        put("type", type)
        if (mapId != null) put("mapId", mapId.toString().lowercase())
        if (groupId != null) put("groupId", groupId.toString().lowercase())
        put("sequence", sequence)
        if (payload != null) put("payload", wrap(payload))
    }.toString()
}

private fun JSONObject.optionalUuid(name: String): UUID? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)?.let(UUID::fromString)

private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    unwrap(get(key))
}

private fun unwrap(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
    else -> value
}

private fun Map<String, Any?>.toJsonObject(): JSONObject = JSONObject().also { output ->
    forEach { (key, value) -> output.put(key, wrap(value)) }
}

private fun wrap(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is UUID -> value.toString().lowercase()
    is Map<*, *> -> JSONObject().also { output ->
        value.forEach { (key, item) -> if (key is String) output.put(key, wrap(item)) }
    }
    is Iterable<*> -> JSONArray().also { output -> value.forEach { output.put(wrap(it)) } }
    else -> value
}
