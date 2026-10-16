package jp.metamaps.mapview

import kotlin.test.Test
import kotlin.test.assertEquals

/** Keeps the page's safe area from adding the system bars again when the host already placed the map clear of them. */
class WebViewInsetsTests {
    // A 1080 x 2400 window with a 135 px status bar and a 63 px navigation bar.
    private val window = EdgeInsets(left = 0, top = 135, right = 0, bottom = 63)

    private fun placement(top: Int, bottom: Int) = WindowPlacement(0, top, 1080, bottom, 1080, 2400)

    @Test
    fun aMapBetweenTheSystemBarsGetsNoSafeArea() {
        assertEquals(EdgeInsets(0, 0, 0, 0), keepOverlappingEdges(window, placement(top = 135, bottom = 2337)))
    }

    @Test
    fun aMapDrawnEdgeToEdgeKeepsTheFullSafeArea() {
        assertEquals(window, keepOverlappingEdges(window, placement(top = 0, bottom = 2400)))
        assertEquals(
            EdgeInsets(0, 135, 0, 0),
            keepOverlappingEdges(window, placement(top = 0, bottom = 2337)),
        )
    }

    @Test
    fun aPartlyCoveredEdgeKeepsItsWindowValueAndAnUnplacedViewPassesTheInsetsThrough() {
        assertEquals(EdgeInsets(0, 135, 0, 0), keepOverlappingEdges(window, placement(top = 40, bottom = 2337)))
        assertEquals(window, keepOverlappingEdges(window, WindowPlacement(0, 0, 0, 0, 0, 0)))
    }
}
