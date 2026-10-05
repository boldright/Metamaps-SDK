package jp.metamaps.samples.view

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import jp.metamaps.MetamapsError
import jp.metamaps.mapview.MetamapsExternalLinkDecision
import jp.metamaps.mapview.MetamapsMapView
import jp.metamaps.mapview.MetamapsMapViewConfiguration
import jp.metamaps.mapview.MetamapsMapViewEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A minimal map-only integration with the Android View API. It declares no location or Bluetooth permissions. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mapView: MetamapsMapView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = getString(R.string.map_loading)
            setPadding(24, 16, 24, 16)
        }
        mapView = MetamapsMapView(this).apply {
            configure(MetamapsMapViewConfiguration(mapSlug = "replace-with-your-map-slug"))
            eventListener = { event ->
                status.text = when (event) {
                    is MetamapsMapViewEvent.Error ->
                        "${event.error.code.wireValue}: ${event.error.message}"
                    else -> event::class.simpleName.orEmpty()
                }
            }
            externalLinkHandler = {
                // Return HANDLED after presenting a host confirmation UI instead.
                MetamapsExternalLinkDecision.OPEN_DEFAULT
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(
                mapView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        setContentView(layout)
        scope.launch {
            try {
                mapView.load()
            } catch (error: MetamapsError) {
                status.text = error.message
            }
        }
    }

    override fun onDestroy() {
        if (::mapView.isInitialized) mapView.dispose()
        scope.cancel()
        super.onDestroy()
    }
}
