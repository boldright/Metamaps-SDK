package jp.metamaps.samples.mapviewsample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import jp.metamaps.mapview.MetamapMapViewConfiguration

/**
 * A sample app for checking [MetamapMapScreen] with a mapSlug, base URL, extra query parameters, and development
 * toggles.
 */
class MapViewSampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MapViewSampleApp()
            }
        }
    }
}

@Composable
private fun MapViewSampleApp() {
    val context = LocalContext.current
    val settings = remember { SampleSettings(context) }
    var configuration by remember { mutableStateOf<MetamapMapViewConfiguration?>(null) }

    val current = configuration
    if (current == null) {
        SampleSettingsScreen(settings = settings, onShowMap = { configuration = it })
    } else {
        BackHandler { configuration = null }
        SampleMapScreen(configuration = current, onBack = { configuration = null })
    }
}
