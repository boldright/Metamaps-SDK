package jp.metamaps.samples.view

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import jp.metamaps.mapview.MetamapMapView
import jp.metamaps.mapview.MetamapMapViewConfiguration
import jp.metamaps.mapview.MetamapMapViewEvent
import jp.metamaps.mapview.MetamapExternalLinkDecision
import jp.metamaps.positioning.android.MetamapPositioningError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mapView: MetamapMapView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = getString(R.string.map_loading)
            setPadding(24, 16, 24, 16)
        }
        val positionButton = Button(this).apply {
            text = getString(R.string.show_current_location)
            setOnClickListener {
                scope.launch {
                    runCatching {
                        mapView.startPositioning(this@MainActivity, requestAuthorization = true)
                    }.onFailure { error ->
                        runOnUiThread {
                            status.text = error.message ?: getString(R.string.positioning_failed)
                        }
                    }
                }
            }
        }
        mapView = MetamapMapView(this).apply {
            configure(MetamapMapViewConfiguration(mapSlug = "replace-with-your-map-slug"))
            eventListener = { event ->
                status.text = when (event) {
                    is MetamapMapViewEvent.Error ->
                        "${event.error.code.wireValue}: ${event.error.message}"
                    is MetamapMapViewEvent.PositioningStatus -> event.status.wireValue
                    else -> event::class.simpleName.orEmpty()
                }
            }
            externalLinkHandler = {
                // Return HANDLED after presenting a host confirmation UI instead.
                MetamapExternalLinkDecision.OPEN_DEFAULT
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(positionButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
            } catch (error: MetamapPositioningError) {
                status.text = error.message
            }
        }
    }

    override fun onStop() {
        mapView.stopPositioning()
        super.onStop()
    }

    override fun onDestroy() {
        if (::mapView.isInitialized) mapView.dispose()
        scope.cancel()
        super.onDestroy()
    }
}
