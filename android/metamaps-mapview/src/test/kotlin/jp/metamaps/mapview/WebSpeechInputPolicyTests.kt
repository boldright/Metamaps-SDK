package jp.metamaps.mapview

import android.webkit.PermissionRequest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lets the web map's voice input use the microphone without granting capture to any other origin or resource. */
class WebSpeechInputPolicyTests {
    private val baseUrl = URI.create("https://metamaps.jp")
    private val microphone = PermissionRequest.RESOURCE_AUDIO_CAPTURE
    private val camera = PermissionRequest.RESOURCE_VIDEO_CAPTURE

    private fun isMapRequest(origin: String?, resources: List<String> = listOf(microphone)) =
        isMapMicrophoneRequest(origin, resources, baseUrl)

    @Test
    fun handlesOnlyMicrophoneCaptureFromTheConfiguredHttpsMapOrigin() {
        assertTrue(isMapRequest("https://metamaps.jp/"))
        assertTrue(isMapRequest("https://METAMAPS.jp:443/"))

        assertFalse(isMapRequest("https://example.com/"))
        assertFalse(isMapRequest("http://metamaps.jp/"))
        assertFalse(isMapRequest(null))

        assertFalse(isMapRequest("https://metamaps.jp/", listOf(camera)))
        assertFalse(isMapRequest("https://metamaps.jp/", listOf(microphone, camera)))
        assertFalse(isMapRequest("https://metamaps.jp/", emptyList()))
    }

    /** 07 §4: the microphone hides only when the app cannot ask for it or the device cannot recognize speech. */
    @Test
    fun announcesVoiceInputWhenTheAppDeclaresTheMicrophoneWhetherOrNotItIsGranted() {
        assertTrue(isWebSpeechRecognitionAvailable(baseUrl, declaresRecordAudio = true, hasRecognitionService = true))

        assertFalse(isWebSpeechRecognitionAvailable(baseUrl, declaresRecordAudio = false, hasRecognitionService = true))
        assertFalse(isWebSpeechRecognitionAvailable(baseUrl, declaresRecordAudio = true, hasRecognitionService = false))
        val localBaseUrl = URI.create("http://localhost:8080")
        assertFalse(isWebSpeechRecognitionAvailable(localBaseUrl, declaresRecordAudio = true, hasRecognitionService = true))
    }
}
