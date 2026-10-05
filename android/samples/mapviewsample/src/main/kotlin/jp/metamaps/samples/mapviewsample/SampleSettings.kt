package jp.metamaps.samples.mapviewsample

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import jp.metamaps.mapview.MapViewPositioningPolicy
import jp.metamaps.mapview.MapViewPositioningStartTrigger
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** The input values of one extra query parameter row. */
data class SampleQueryParameter(
    val id: String = UUID.randomUUID().toString(),
    val isEnabled: Boolean = true,
    val key: String = "",
    val value: String = "",
)

/**
 * Saves the sample app's input values to SharedPreferences.
 *
 * mapSlug and baseUrl are saved only by [save]; extra query parameters and development toggles are saved on every
 * change.
 */
class SampleSettings(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var mapSlug by mutableStateOf(preferences.getString(KEY_MAP_SLUG, null).orEmpty())
        private set

    var baseUrl by mutableStateOf(preferences.getString(KEY_BASE_URL, null) ?: PRODUCTION_BASE_URL)
        private set

    val queryParameters: SnapshotStateList<SampleQueryParameter> =
        decodeQueryParameters().toMutableStateList()

    private var inspectableState by mutableStateOf(preferences.getBoolean(KEY_WEB_VIEW_INSPECTABLE, false))
    private var beaconDiagnosticsState by
        mutableStateOf(preferences.getBoolean(KEY_BEACON_DIAGNOSTICS, false))
    // Without saved values, start from the same combination as the SDK defaults.
    private var startTriggerState by mutableStateOf(
        preferences.getString(KEY_START_TRIGGER, null)
            ?.let { name -> MapViewPositioningStartTrigger.entries.firstOrNull { it.name == name } }
            ?: MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED,
    )
    private var positioningPolicyState by mutableStateOf(
        preferences.getString(KEY_POSITIONING_POLICY, null)
            ?.let { name -> MapViewPositioningPolicy.entries.firstOrNull { it.name == name } }
            ?: MapViewPositioningPolicy.USER_INITIATED,
    )

    var isWebViewInspectable: Boolean
        get() = inspectableState
        set(value) {
            inspectableState = value
            preferences.edit().putBoolean(KEY_WEB_VIEW_INSPECTABLE, value).apply()
        }

    /** Receives registered beacons as `BeaconSignals`, used to measure the delay until the first reception. */
    var showsBeaconDiagnostics: Boolean
        get() = beaconDiagnosticsState
        set(value) {
            beaconDiagnosticsState = value
            preferences.edit().putBoolean(KEY_BEACON_DIAGNOSTICS, value).apply()
        }

    /** The automatic positioning start trigger, kept so the three values can be compared on a device. */
    var startTrigger: MapViewPositioningStartTrigger
        get() = startTriggerState
        set(value) {
            startTriggerState = value
            preferences.edit().putString(KEY_START_TRIGGER, value.name).apply()
        }

    /** Who handles start requests. With `HOST_CONTROLLED`, the SDK only reports an event and does not start. */
    var positioningPolicy: MapViewPositioningPolicy
        get() = positioningPolicyState
        set(value) {
            positioningPolicyState = value
            preferences.edit().putString(KEY_POSITIONING_POLICY, value.name).apply()
        }

    /**
     * Passes only rows that are on and have a non-empty key to the SDK. For duplicate keys, the later row wins.
     * The SDK URL-encodes the values, so pass the raw strings.
     */
    val additionalQuery: Map<String, String>
        get() = queryParameters.fold(mutableMapOf()) { result, parameter ->
            val key = parameter.key.trim()
            if (parameter.isEnabled && key.isNotEmpty()) {
                result[key] = parameter.value.trim()
            }
            result
        }

    fun addQueryParameter() {
        queryParameters.add(SampleQueryParameter())
        persistQueryParameters()
    }

    fun updateQueryParameter(id: String, transform: (SampleQueryParameter) -> SampleQueryParameter) {
        val index = queryParameters.indexOfFirst { it.id == id }
        if (index < 0) return
        queryParameters[index] = transform(queryParameters[index])
        persistQueryParameters()
    }

    fun removeQueryParameter(id: String) {
        if (queryParameters.removeAll { it.id == id }) persistQueryParameters()
    }

    fun save(mapSlug: String, baseUrl: String) {
        this.mapSlug = mapSlug
        this.baseUrl = baseUrl
        preferences.edit()
            .putString(KEY_MAP_SLUG, mapSlug)
            .putString(KEY_BASE_URL, baseUrl)
            .apply()
    }

    private fun persistQueryParameters() {
        val array = JSONArray()
        queryParameters.forEach { parameter ->
            array.put(
                JSONObject()
                    .put("id", parameter.id)
                    .put("isEnabled", parameter.isEnabled)
                    .put("key", parameter.key)
                    .put("value", parameter.value),
            )
        }
        preferences.edit().putString(KEY_QUERY_PARAMETERS, array.toString()).apply()
    }

    private fun decodeQueryParameters(): List<SampleQueryParameter> {
        val stored = preferences.getString(KEY_QUERY_PARAMETERS, null) ?: return emptyList()
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            SampleQueryParameter(
                id = item.optString("id").ifEmpty { UUID.randomUUID().toString() },
                isEnabled = item.optBoolean("isEnabled", true),
                key = item.optString("key"),
                value = item.optString("value"),
            )
        }
    }

    companion object {
        const val PRODUCTION_BASE_URL = "https://metamaps.jp"

        private const val PREFERENCES_NAME = "metamap.map-view-sample"
        private const val KEY_MAP_SLUG = "metamap.map-view-sample.map-slug"
        private const val KEY_BASE_URL = "metamap.map-view-sample.base-url"
        private const val KEY_QUERY_PARAMETERS = "metamap.map-view-sample.query-parameters"
        private const val KEY_WEB_VIEW_INSPECTABLE = "metamap.map-view-sample.web-view-inspectable"
        private const val KEY_BEACON_DIAGNOSTICS = "metamap.map-view-sample.beacon-diagnostics"
        private const val KEY_START_TRIGGER = "metamap.map-view-sample.positioning-start-trigger"
        private const val KEY_POSITIONING_POLICY = "metamap.map-view-sample.positioning-policy"
    }
}
