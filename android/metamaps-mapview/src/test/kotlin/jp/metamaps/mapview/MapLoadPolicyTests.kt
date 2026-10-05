package jp.metamaps.mapview

import java.net.URI
import java.util.UUID
import jp.metamaps.MetamapsError
import jp.metamaps.MetamapsUserAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Prevents HTTP failures and retry timing from drifting from the cross-platform MapView load contract. */
class MapLoadPolicyTests {
    @Test
    fun classifiesHttpFailuresAndRetryEligibility() {
        val notFound = classifyHttpLoadFailure(404)
        assertEquals(MetamapsError.Code.MAP_NOT_FOUND, notFound.error.code)
        assertFalse(notFound.error.recoverable)
        assertEquals(MetamapsUserAction.CHECK_CONFIGURATION, notFound.error.userAction)
        assertFalse(notFound.retryable)

        listOf(401, 403).forEach { status ->
            val denied = classifyHttpLoadFailure(status)
            assertEquals(MetamapsError.Code.MAP_ACCESS_DENIED, denied.error.code)
            assertTrue(denied.error.recoverable)
            assertFalse(denied.retryable)
        }

        listOf(408, 429, 500, 503, 599).forEach { status ->
            val transient = classifyHttpLoadFailure(status)
            assertEquals(MetamapsError.Code.WEB_CONTENT_LOAD_FAILED, transient.error.code)
            assertTrue(transient.retryable)
        }

        listOf(400, 409, 451).forEach { status ->
            assertFalse(classifyHttpLoadFailure(status).retryable)
        }
    }

    @Test
    fun retryDelaysBackOffThenRemainAtFourSeconds() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 4_000L, 4_000L),
            (0..4).map(::mapLoadRetryDelayMillis),
        )
    }

    @Test
    fun initialDocumentWatchdogOnlyFiresBeforeResponseIsObserved() {
        assertTrue(shouldInitialDocumentRequestTimeout(responseObserved = false))
        assertFalse(shouldInitialDocumentRequestTimeout(responseObserved = true))
    }
}

/** Prevents URL overrides, external schemes, and diagnostic UA markers from regressing silently. */
class MapViewConfigurationPolicyTests {
    @Test
    fun acceptsNewAndLegacySpotStableKeysWithoutRewriting() {
        assertTrue(isValidMetamapsSpotStableKey("6Y6SSBY5"))
        assertTrue(isValidMetamapsSpotStableKey("spot_6Y6SSBY5"))
        assertFalse(isValidMetamapsSpotStableKey(""))
        assertFalse(isValidMetamapsSpotStableKey("6Y6S/SBY5"))
    }

    @Test
    fun additionalQueryOverridesFixedKeysWithoutDuplicatesAndUsesStableOrder() {
        val groupId = UUID.fromString("13addae1-88c2-4a4f-9764-948687bf567d")
        val url = buildMetamapsMapUrl(
            MetamapsMapViewConfiguration(
                mapSlug = "demo map",
                groupId = groupId,
                language = "ja",
                additionalQuery = mapOf(
                    "lang" to "en-US",
                    "group" to "override",
                    "zeta" to "last value",
                    "alpha" to "first",
                ),
            ),
        )

        assertEquals(
            "group=override&lang=en-US&alpha=first&zeta=last%20value",
            url.rawQuery,
        )
    }

    @Test
    fun externalLinkAllowlistIncludesEverySupportedScheme() {
        listOf("http", "https", "tel", "mailto", "sms", "geo").forEach { scheme ->
            assertTrue(isAllowedExternalLinkScheme(URI.create("$scheme:test")))
        }
        listOf("javascript", "file", "intent", "data").forEach { scheme ->
            assertFalse(isAllowedExternalLinkScheme(URI.create("$scheme:test")))
        }
    }

    @Test
    fun userAgentIncludesSdkMarkerAndHostAppendix() {
        assertEquals(
            "AndroidWebView/1 Metamaps/2.3.4 HostApp/9",
            buildMetamapsUserAgent("AndroidWebView/1", "2.3.4", "HostApp/9"),
        )
    }
}
