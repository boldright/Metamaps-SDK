package jp.metamaps.samples.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import jp.metamaps.MetamapsError
import jp.metamaps.mapview.MetamapsExternalLinkDecision
import jp.metamaps.mapview.MetamapsMapView
import jp.metamaps.mapview.MetamapsMapViewConfiguration
import jp.metamaps.mapview.MetamapsMapViewEvent
import kotlinx.coroutines.launch

/** A minimal map-only integration in Jetpack Compose. It declares no location or Bluetooth permissions. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val mapLoading = stringResource(R.string.map_loading)
                var status by remember { mutableStateOf(mapLoading) }
                var mapView by remember { mutableStateOf<MetamapsMapView?>(null) }

                DisposableEffect(Unit) {
                    onDispose { mapView?.dispose() }
                }
                Column(Modifier.fillMaxSize()) {
                    Text(status, Modifier.fillMaxWidth())
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            MetamapsMapView(context).also { view ->
                                view.configure(
                                    MetamapsMapViewConfiguration(
                                        mapSlug = "replace-with-your-map-slug",
                                    ),
                                )
                                view.eventListener = { event ->
                                    status = when (event) {
                                        is MetamapsMapViewEvent.Error ->
                                            "${event.error.code.wireValue}: ${event.error.message}"
                                        else -> event::class.simpleName.orEmpty()
                                    }
                                }
                                view.externalLinkHandler = {
                                    // Return HANDLED when the host opens a confirmation UI.
                                    MetamapsExternalLinkDecision.OPEN_DEFAULT
                                }
                                mapView = view
                                scope.launch {
                                    try {
                                        view.load()
                                    } catch (error: MetamapsError) {
                                        status = error.message
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
