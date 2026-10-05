package jp.metamaps.samples.mapviewsample

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.metamaps.mapview.MetamapMapViewConfiguration
import jp.metamaps.mapview.MetamapMapViewEvent
import jp.metamaps.positioning.android.PositioningLifecycleStatus
import jp.metamaps.positioning.android.PositioningUserAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

private fun formatSeconds(milliseconds: Long): String =
    String.format(Locale.US, "%.2fs", milliseconds / 1000.0)

/** A floor's display name. Before loading or for an unknown ID, falls back to the first 8 characters (a full UUID is unreadable on screen). */
private fun floorLabel(labels: Map<UUID, String>, floorId: UUID?): String {
    if (floorId == null) return "all"
    return labels[floorId] ?: floorId.toString().lowercase().take(8)
}

/**
 * Maps floor IDs to labels from the public config. The SDK has no labels, so read them from the same public
 * API the map uses. On failure, keep showing shortened IDs as before loading.
 */
private suspend fun loadFloorLabels(baseUrl: URI, mapSlug: String): Map<UUID, String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val boot = JSONObject(baseUrl.resolve("/api/public/maps/$mapSlug/boot").toURL().readText())
            val config = JSONObject(baseUrl.resolve(boot.getString("configUrl")).toURL().readText())
            val floors = config.getJSONArray("floors")
            (0 until floors.length()).associate { index ->
                val floor = floors.getJSONObject(index)
                UUID.fromString(floor.getString("id")) to floor.getString("label")
            }
        }.getOrElse { emptyMap() }
    }

/** The tag for filtering exported logs in Logcat. Read them with `adb logcat -d -s MetamapSampleLog:I`. */
private const val LOG_TAG = "MetamapSampleLog"

/** One line for on-device checks: time since the map opened or the log was cleared, and the interval since the previous event of the same kind. */
private data class SampleEventLogEntry(
    val elapsedMs: Long,
    /** Only positioning status lines carry the interval since the previous status, used to check the 10-second and 30-second timings. */
    val sincePreviousStatusMs: Long?,
    val text: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleMapScreen(
    configuration: MetamapMapViewConfiguration,
    onBack: () -> Unit,
) {
    var reloadId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var status by remember { mutableStateOf("loadState: idle") }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }
    var lastErrorCode by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<SampleEventLogEntry>() }
    var openedAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastStatusElapsed by remember { mutableStateOf<Long?>(null) }
    // Log only the first high-frequency event of each positioning session, to read the delay until the first reception.
    var loggedFirstSignals by remember { mutableStateOf(false) }
    var loggedFirstPosition by remember { mutableStateOf(false) }
    // The one-line result of an export, kept until the next clear or export.
    var exportNotice by remember { mutableStateOf<String?>(null) }
    // Floor ID to label map, so `floorChanged` is logged with a readable name instead of a UUID.
    var floorLabels by remember { mutableStateOf<Map<UUID, String>>(emptyMap()) }
    val context = LocalContext.current

    fun resetLog() {
        openedAt = System.currentTimeMillis()
        log.clear()
        lastStatusElapsed = null
        loggedFirstSignals = false
        loggedFirstPosition = false
        exportNotice = null
    }

    /** The exported text. The settings come first so it is clear later which configuration produced the log. */
    fun logText(): String = buildString {
        appendLine("slug=${configuration.mapSlug}")
        appendLine("baseUrl=${configuration.baseUrl}")
        appendLine("trigger=${configuration.positioningStartTrigger.name}")
        appendLine("policy=${configuration.positioningPolicy.name}")
        appendLine("beaconDiagnostics=${configuration.showsBeaconDiagnostics}")
        appendLine(
            "query=" + configuration.additionalQuery.entries
                .sortedBy { it.key }
                .joinToString("&") { "${it.key}=${it.value}" },
        )
        appendLine("device=${Build.MODEL} Android ${Build.VERSION.RELEASE}")
        log.forEach { entry ->
            append(formatSeconds(entry.elapsedMs)).append('\t').append(entry.text)
            entry.sincePreviousStatusMs?.let { append('\t').append("+${formatSeconds(it)}") }
            appendLine()
        }
    }

    /**
     * Hands over the full log through three channels at once. Which one works depends on the recipient's setup, so
     * one action does all three.
     *
     * 1. Logcat, readable with `adb logcat -d -s MetamapSampleLog:I` from a computer connected to the device
     * 2. The clipboard
     * 3. The share sheet, for sending the log to someone
     */
    fun exportLog() {
        val text = logText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("metamap sample log", text))
        // Write line by line. Logcat limits the length of each entry, so one entry with the full text would be cut off.
        Log.i(LOG_TAG, "--- METAMAP SAMPLE LOG BEGIN ---")
        text.lineSequence().forEach { Log.i(LOG_TAG, it) }
        Log.i(LOG_TAG, "--- METAMAP SAMPLE LOG END ---")
        exportNotice = "Sent ${log.size} lines to Logcat, the clipboard, and the share sheet"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "metamap eventlog ${configuration.mapSlug}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share log"))
    }

    fun append(text: String, isStatus: Boolean = false) {
        val elapsed = System.currentTimeMillis() - openedAt
        val sincePrevious = if (isStatus) lastStatusElapsed?.let { elapsed - it } else null
        if (isStatus) lastStatusElapsed = elapsed
        // No line limit. Checks read back over many 10-second waits and 30-second retries, and dropping old
        // lines would lose the evidence partway through.
        log.add(SampleEventLogEntry(elapsed, sincePrevious, text))
        // Also write to Logcat immediately. For rare events, a record would otherwise exist only if someone
        // watching the screen pressed Export, so always keep a path that records without it.
        Log.d(LOG_TAG, formatSeconds(elapsed) + "\t" + text + (sincePrevious?.let { "\t+${formatSeconds(it)}" } ?: ""))
    }

    LaunchedEffect(configuration.baseUrl, configuration.mapSlug) {
        floorLabels = loadFloorLabels(configuration.baseUrl, configuration.mapSlug)
    }

    fun handle(event: MetamapMapViewEvent) {
        when (event) {
            // The first line shows the state the SDK holds (loadState). ready and error appear here as load outcomes too.
            is MetamapMapViewEvent.LoadState -> {
                status = "loadState: ${event.state.name.lowercase()}"
                append("loadState: ${event.state.name.lowercase()}")
            }
            is MetamapMapViewEvent.Ready -> {
                status = "ready"
                append("ready")
            }
            is MetamapMapViewEvent.Error -> {
                // Several places throw the same code. Without the message, the failure point cannot be identified.
                val body = buildString {
                    append("${event.error.code.wireValue} / ${event.error.message}")
                    event.error.debugDetail?.let { append(" / $it") }
                }
                // Status notices that are not failures (they recover on their own and need no user action) are logged
                // as `info:`, so real failures do not get buried among `error:` lines. Currently this is only
                // `pausedBackground`.
                if (event.error.recoverable && event.error.userAction == PositioningUserAction.NONE) {
                    append("info: $body")
                } else {
                    lastErrorCode = event.error.code.wireValue
                    status = "error: ${event.error.code.wireValue}"
                    append("error: $body")
                }
            }
            is MetamapMapViewEvent.FloorChanged ->
                append("floorChanged: ${floorLabel(floorLabels, event.event.floorId)}")
            is MetamapMapViewEvent.SpotSelected ->
                append("spotSelected: ${event.event.spotId ?: "cleared"}")
            is MetamapMapViewEvent.RouteChanged ->
                append("routeChanged: ${event.event.destinationSpotId ?: "-"} active=${event.event.active}")
            is MetamapMapViewEvent.ExternalLinkRequested ->
                append("externalLinkRequested: ${event.uri}")
            is MetamapMapViewEvent.PositioningStatus -> {
                // The interval from `acquiring` to `stopped` is the signal wait; from `stopped` to `acquiring` is the retry period.
                if (event.status == PositioningLifecycleStatus.ACQUIRING ||
                    event.status == PositioningLifecycleStatus.RECOVERING
                ) {
                    loggedFirstSignals = false
                    loggedFirstPosition = false
                }
                append("status: ${event.status.wireValue}", isStatus = true)
            }
            is MetamapMapViewEvent.Capabilities -> {
                // Appears only when permissions changed on returning to the foreground (identical content is not resent).
                val authorization = event.report.authorization
                append(
                    "capabilities: ble=${if (event.report.ble.enabled) "on" else "off"}" +
                        " ranging=${if (event.report.ble.rangingAvailable) "ok" else "ng"}" +
                        " loc=${authorization.location.wireValue}" +
                        " scan=${authorization.bluetoothScan}" +
                        " motion=${authorization.motion.wireValue}",
                )
            }
            is MetamapMapViewEvent.BeaconSignals -> {
                // The gap from the preceding `status: acquiring` is the delay from scan start to the first reception
                // (`BeaconSignals` is batched for up to 200 ms, so the gap can be that much longer).
                if (!loggedFirstSignals) {
                    loggedFirstSignals = true
                    append("beaconSignals: ${event.readings.size} (first)")
                }
            }
            is MetamapMapViewEvent.Position -> {
                if (!loggedFirstPosition) {
                    loggedFirstPosition = true
                    val estimate = event.update.estimate
                    val accuracy = String.format(Locale.US, "%.1f", estimate.accuracyRadiusM)
                    append("position: ${estimate.status} ±${accuracy}m (first)")
                }
            }
            is MetamapMapViewEvent.PositioningAuthorizationRequested ->
                append("positioningAuthorizationRequested (handed to the host)")
            is MetamapMapViewEvent.PositioningStartRequested ->
                append("positioningStartRequested (handed to the host)")
            // motionHeading is too frequent to log.
            // Minor SDK updates may add events. Always keep a default branch.
            else -> Unit
        }
    }

    fun handleLoadError(error: Throwable) {
        status = lastErrorCode?.let { "error: $it" } ?: "error"
        loadErrorMessage = error.message ?: error.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        configuration.mapSlug,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            key(reloadId) {
                MetamapMapScreen(
                    configuration = configuration,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onEvent = ::handle,
                    onLoadError = ::handleLoadError,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        configuration.positioningStartTrigger.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { resetLog() },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { exportLog() },
                        modifier = Modifier.height(28.dp),
                        enabled = log.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Export", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // The event log with elapsed times. Read the 10-second stop, 30-second retry, and foreground recovery of automatic positioning here.
                val listState = rememberLazyListState()
                LaunchedEffect(log.size) {
                    if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(128.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(log) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                formatSeconds(entry.elapsedMs),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(52.dp),
                                textAlign = TextAlign.End,
                            )
                            Text(
                                entry.text,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            entry.sincePreviousStatusMs?.let {
                                Text(
                                    "+${formatSeconds(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }

                exportNotice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                loadErrorMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = {
                            loadErrorMessage = null
                            lastErrorCode = null
                            status = "loadState: idle"
                            resetLog()
                            reloadId = UUID.randomUUID().toString()
                        },
                    ) {
                        Text("Reload")
                    }
                }
            }
        }
    }
}
