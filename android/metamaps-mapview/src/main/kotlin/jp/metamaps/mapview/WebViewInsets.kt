package jp.metamaps.mapview

import android.graphics.Insets
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.annotation.RequiresApi

/** Edge distances in pixels, in window coordinates. */
internal data class EdgeInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** A view's bounds in its window and the window's size, in pixels. */
internal data class WindowPlacement(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val windowWidth: Int,
    val windowHeight: Int,
) {
    val isLaidOut: Boolean get() = right > left && bottom > top && windowWidth > 0 && windowHeight > 0
}

/**
 * Keeps each edge of the window [insets] only where the view overlaps that edge's system UI, and sets the other edges
 * to 0. A kept edge keeps its window value: WebView versions that check the overlap themselves read the insets in
 * window coordinates, and a view that reaches the window edge needs the full value anyway. Before layout, the
 * placement is unknown and the insets pass through unchanged.
 */
internal fun keepOverlappingEdges(insets: EdgeInsets, placement: WindowPlacement): EdgeInsets {
    if (!placement.isLaidOut) return insets
    return EdgeInsets(
        left = if (placement.left < insets.left) insets.left else 0,
        top = if (placement.top < insets.top) insets.top else 0,
        right = if (placement.right > placement.windowWidth - insets.right) insets.right else 0,
        bottom = if (placement.bottom > placement.windowHeight - insets.bottom) insets.bottom else 0,
    )
}

/**
 * Passes the WebView only the system bar and display cutout insets that overlap it, so that the page's
 * `env(safe-area-inset-*)` does not add space again when the host already placed the map clear of the system bars.
 * Other inset types, such as the keyboard, pass through unchanged. Android 11 and later only.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class WebViewSafeAreaInsets(private val view: View) {
    private var appliedPlacement: WindowPlacement? = null

    /**
     * A host that pads a parent moves the WebView without sending new insets, and an ancestor's layout can move a
     * fixed-size map without laying out the WebView itself. After every layout pass of the window, send the insets
     * again when the WebView's place in the window changed, so the kept edges follow it.
     */
    private val placementWatcher = ViewTreeObserver.OnGlobalLayoutListener {
        if (placement() != appliedPlacement) view.requestApplyInsets()
    }

    fun install() {
        view.setOnApplyWindowInsetsListener { target, insets -> target.onApplyWindowInsets(limit(insets)) }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                attached.viewTreeObserver.addOnGlobalLayoutListener(placementWatcher)
            }

            override fun onViewDetachedFromWindow(detached: View) {
                detached.viewTreeObserver.removeOnGlobalLayoutListener(placementWatcher)
            }
        })
        if (view.isAttachedToWindow) view.viewTreeObserver.addOnGlobalLayoutListener(placementWatcher)
    }

    private fun limit(insets: WindowInsets): WindowInsets {
        val placement = placement()
        appliedPlacement = placement
        val builder = WindowInsets.Builder(insets)
        for (type in LIMITED_TYPES) {
            val window = insets.getInsets(type)
            val kept = keepOverlappingEdges(EdgeInsets(window.left, window.top, window.right, window.bottom), placement)
            builder.setInsets(type, Insets.of(kept.left, kept.top, kept.right, kept.bottom))
        }
        return builder.build()
    }

    private fun placement(): WindowPlacement {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val root = view.rootView
        return WindowPlacement(
            left = location[0],
            top = location[1],
            right = location[0] + view.width,
            bottom = location[1] + view.height,
            windowWidth = root.width,
            windowHeight = root.height,
        )
    }

    private companion object {
        val LIMITED_TYPES = intArrayOf(
            WindowInsets.Type.statusBars(),
            WindowInsets.Type.navigationBars(),
            WindowInsets.Type.captionBar(),
            WindowInsets.Type.displayCutout(),
        )
    }
}
