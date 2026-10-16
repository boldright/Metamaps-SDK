package jp.metamaps.mapview

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keeps the web map's GPS working in the WebView without granting location to any other origin. */
class WebGeolocationPolicyTests {
    private val baseUrl = URI.create("https://metamaps.jp")

    @Test
    fun handlesOnlyRequestsFromTheConfiguredHttpsMapOrigin() {
        assertTrue(isMapGeolocationRequest("https://metamaps.jp/", baseUrl))
        assertTrue(isMapGeolocationRequest("https://METAMAPS.jp:443/", baseUrl))

        assertFalse(isMapGeolocationRequest("https://example.com/", baseUrl))
        assertFalse(isMapGeolocationRequest("https://metamaps.jp:8443/", baseUrl))
        assertFalse(isMapGeolocationRequest("http://metamaps.jp/", baseUrl))
        assertFalse(isMapGeolocationRequest(null, baseUrl))
        assertFalse(isMapGeolocationRequest("http://localhost:8080/", URI.create("http://localhost:8080")))
    }
}
