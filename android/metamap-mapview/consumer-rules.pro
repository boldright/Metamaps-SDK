# The JavaScript interface is referenced by Android WebView through @JavascriptInterface.
-keepclassmembers class jp.metamaps.mapview.MetamapMapView$NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
