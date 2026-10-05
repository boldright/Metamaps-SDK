package jp.metamaps.samples.mapviewsample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.metamaps.mapview.MapViewPositioningPolicy
import jp.metamaps.mapview.MapViewPositioningStartTrigger
import jp.metamaps.mapview.MetamapMapViewConfiguration
import java.net.URI

private val TEXT_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)

private val URL_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleSettingsScreen(
    settings: SampleSettings,
    onShowMap: (MetamapMapViewConfiguration) -> Unit,
) {
    var mapSlug by rememberSaveable { mutableStateOf(settings.mapSlug) }
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }

    fun showMap() {
        val normalizedSlug = mapSlug.trim()
        val normalizedBaseUrl = baseUrl.trim()

        if (normalizedSlug.isEmpty()) {
            validationMessage = "Enter a mapSlug."
            return
        }
        val url = runCatching { URI(normalizedBaseUrl) }.getOrNull()
        val scheme = url?.scheme?.lowercase()
        if (url == null || scheme !in setOf("http", "https") || url.host.isNullOrEmpty()) {
            validationMessage = "Enter a valid HTTP(S) baseURL."
            return
        }

        validationMessage = null
        settings.save(mapSlug = normalizedSlug, baseUrl = normalizedBaseUrl)
        val additionalQuery = settings.additionalQuery
        onShowMap(
            MetamapMapViewConfiguration(
                mapSlug = normalizedSlug,
                baseUrl = url,
                positioningPolicy = settings.positioningPolicy,
                positioningStartTrigger = settings.startTrigger,
                showsBeaconDiagnostics = settings.showsBeaconDiagnostics,
                additionalQuery = additionalQuery,
                isWebViewInspectable = settings.isWebViewInspectable,
            ),
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Metamaps MapView") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("Map")
            OutlinedTextField(
                value = mapSlug,
                onValueChange = { mapSlug = it },
                label = { Text("mapSlug") },
                singleLine = true,
                keyboardOptions = TEXT_KEYBOARD,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("baseURL") },
                singleLine = true,
                keyboardOptions = URL_KEYBOARD,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Extra query parameters")
            settings.queryParameters.forEach { parameter ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = parameter.isEnabled,
                                onCheckedChange = { enabled ->
                                    settings.updateQueryParameter(parameter.id) {
                                        it.copy(isEnabled = enabled)
                                    }
                                },
                            )
                            TextButton(onClick = { settings.removeQueryParameter(parameter.id) }) {
                                Text("Delete")
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = parameter.key,
                                onValueChange = { key ->
                                    settings.updateQueryParameter(parameter.id) { it.copy(key = key) }
                                },
                                label = { Text("key") },
                                singleLine = true,
                                keyboardOptions = TEXT_KEYBOARD,
                                modifier = Modifier.weight(1f),
                            )
                            Text("=", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                value = parameter.value,
                                onValueChange = { value ->
                                    settings.updateQueryParameter(parameter.id) { it.copy(value = value) }
                                },
                                label = { Text("value") },
                                singleLine = true,
                                keyboardOptions = TEXT_KEYBOARD,
                                modifier = Modifier.weight(1.4f),
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { settings.addQueryParameter() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add parameter")
            }

            SectionHeader("Positioning")
            SettingChoice(
                label = "Start trigger",
                options = MapViewPositioningStartTrigger.entries,
                selected = settings.startTrigger,
                onSelect = { settings.startTrigger = it },
                name = { it.name },
            )
            Text(
                settings.startTrigger.sampleDescription(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingChoice(
                label = "Start request handler",
                options = MapViewPositioningPolicy.entries,
                selected = settings.positioningPolicy,
                onSelect = { settings.positioningPolicy = it },
                name = { it.name },
            )

            SectionHeader("Development")
            SettingToggle(
                label = "Receive beacon diagnostics",
                checked = settings.showsBeaconDiagnostics,
                onCheckedChange = { settings.showsBeaconDiagnostics = it },
            )
            SettingToggle(
                label = "Enable Web Inspector",
                checked = settings.isWebViewInspectable,
                onCheckedChange = { settings.isWebViewInspectable = it },
            )

            validationMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { showMap() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Show map")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SettingToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** A single-choice selector for switching values on a device. There are few options, so no drop-down is used. */
@Composable
private fun <T> SettingChoice(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    name: (T) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(name(option), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

/** A one-line description for the person switching values on a device. */
private fun MapViewPositioningStartTrigger.sampleDescription(): String = when (this) {
    MapViewPositioningStartTrigger.AUTOMATIC_WHEN_AUTHORIZED ->
        "Default. Starts positioning once the map is ready, only on devices that already have the permissions."
    MapViewPositioningStartTrigger.AUTOMATIC ->
        "Starts once the map is ready and requests undecided permissions (a dialog appears without any user action)."
    MapViewPositioningStartTrigger.USER_ACTION ->
        "Starts only when the user taps the location button in the map."
}
