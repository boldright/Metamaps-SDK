package jp.metamaps.mapview

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keeps the web map's GPS working in the WebView without granting location to any other origin. */
class WebGeolocationPolicyTests {
    private val baseUrl = URI.create("https://metamaps.jp")

    @Test
    fun grantsOnlyTheConfiguredHttpsMapOriginWhileTheAppHoldsLocationPermission() {
        assertTrue(shouldGrantWebGeolocation("https://metamaps.jp/", baseUrl, hasPreciseLocationPermission = true))
        assertTrue(shouldGrantWebGeolocation("https://METAMAPS.jp:443/", baseUrl, hasPreciseLocationPermission = true))

        assertFalse(shouldGrantWebGeolocation("https://metamaps.jp/", baseUrl, hasPreciseLocationPermission = false))
        assertFalse(shouldGrantWebGeolocation("https://example.com/", baseUrl, hasPreciseLocationPermission = true))
        assertFalse(shouldGrantWebGeolocation("https://metamaps.jp:8443/", baseUrl, hasPreciseLocationPermission = true))
        assertFalse(shouldGrantWebGeolocation("http://metamaps.jp/", baseUrl, hasPreciseLocationPermission = true))
        assertFalse(shouldGrantWebGeolocation(null, baseUrl, hasPreciseLocationPermission = true))

        val localBaseUrl = URI.create("http://localhost:8080")
        assertFalse(
            shouldGrantWebGeolocation("http://localhost:8080/", localBaseUrl, hasPreciseLocationPermission = true),
        )
    }
}
