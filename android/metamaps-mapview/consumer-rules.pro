# The JavaScript interface is referenced by Android WebView through @JavascriptInterface.
-keepclassmembers class jp.metamaps.mapview.MetamapsMapView$NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
