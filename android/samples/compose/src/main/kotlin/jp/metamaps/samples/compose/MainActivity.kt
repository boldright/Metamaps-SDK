package jp.metamaps.samples.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import jp.metamaps.mapview.MetamapMapView
import jp.metamaps.mapview.MetamapMapViewConfiguration
import jp.metamaps.mapview.MetamapMapViewEvent
import jp.metamaps.mapview.MetamapExternalLinkDecision
import jp.metamaps.positioning.android.MetamapPositioningError
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val activity = this
                val scope = rememberCoroutineScope()
                val mapLoading = stringResource(R.string.map_loading)
                val positioningFailed = stringResource(R.string.positioning_failed)
                var status by remember { mutableStateOf(mapLoading) }
                var mapView by remember { mutableStateOf<MetamapMapView?>(null) }

                DisposableEffect(Unit) {
                    onDispose { mapView?.dispose() }
                }
                Column(Modifier.fillMaxSize()) {
                    Text(status, Modifier.fillMaxWidth())
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                runCatching {
                                    mapView?.startPositioning(activity, requestAuthorization = true)
                                }.onFailure { status = it.message ?: positioningFailed }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.show_current_location))
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            MetamapMapView(context).also { view ->
                                view.configure(
                                    MetamapMapViewConfiguration(
                                        mapSlug = "replace-with-your-map-slug",
                                    ),
                                )
                                view.eventListener = { event ->
                                    status = when (event) {
                                        is MetamapMapViewEvent.Error ->
                                            "${event.error.code.wireValue}: ${event.error.message}"
                                        is MetamapMapViewEvent.PositioningStatus -> event.status.wireValue
                                        else -> event::class.simpleName.orEmpty()
                                    }
                                }
                                view.externalLinkHandler = {
                                    // Return HANDLED when the host opens a confirmation UI.
                                    MetamapExternalLinkDecision.OPEN_DEFAULT
                                }
                                mapView = view
                                scope.launch {
                                    try {
                                        view.load()
                                    } catch (error: MetamapPositioningError) {
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

    override fun onStop() {
        super.onStop()
        // The v1 client pauses automatically and starts a fresh estimator after foreground recovery.
    }
}
