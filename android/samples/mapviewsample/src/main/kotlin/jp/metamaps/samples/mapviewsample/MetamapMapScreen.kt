package jp.metamaps.samples.mapviewsample

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import jp.metamaps.mapview.MetamapExternalLinkDecision
import jp.metamaps.mapview.MetamapMapView
import jp.metamaps.mapview.MetamapMapViewConfiguration
import jp.metamaps.mapview.MetamapMapViewEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "MetamapMapScreen"

/**
 * A minimal wrapper that hosts [MetamapMapView] in Compose (the counterpart of the iOS `MetamapMapScreen.swift`).
 *
 * It handles configuration, the event listener, external link decisions, the [MetamapMapView.load] call, and
 * cleanup. A reload recreates this whole composable with `key(...)`.
 */
@Composable
fun MetamapMapScreen(
    configuration: MetamapMapViewConfiguration,
    modifier: Modifier = Modifier,
    onEvent: (MetamapMapViewEvent) -> Unit = {},
    onLoadError: (Throwable) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnLoadError by rememberUpdatedState(onLoadError)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MetamapMapView(context).also { view ->
                view.eventListener = { event ->
                    if (event is MetamapMapViewEvent.Error) {
                        Log.w(
                            TAG,
                            "Metamaps SDK error: ${event.error.code.wireValue} " +
                                "detail=${event.error.debugDetail ?: "-"}",
                        )
                    }
                    // Minor SDK updates may add events. Never assume an exhaustive set here.
                    currentOnEvent(event)
                }
                view.externalLinkHandler = {
                    // Return HANDLED after presenting host-owned confirmation/UI.
                    MetamapExternalLinkDecision.OPEN_DEFAULT
                }
                scope.launch {
                    // configure throws for an invalid baseURL or mapSlug, so report it through the same path as load.
                    try {
                        view.configure(configuration)
                        view.load()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        // Production apps should route this to their own persistent error UI.
                        Log.w(TAG, "Metamap load failed", error)
                        currentOnLoadError(error)
                    }
                }
            }
        },
        onRelease = { view -> view.dispose() },
    )
}
