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

    private fun grants(
        origin: String?,
        resources: List<String> = listOf(microphone),
        hasRecordAudioPermission: Boolean = true,
    ) = shouldGrantWebAudioCapture(origin, resources, baseUrl, hasRecordAudioPermission)

    @Test
    fun grantsOnlyMicrophoneCaptureFromTheConfiguredHttpsMapOriginWhileTheAppHoldsRecordAudio() {
        assertTrue(grants("https://metamaps.jp/"))
        assertTrue(grants("https://METAMAPS.jp:443/"))

        assertFalse(grants("https://metamaps.jp/", hasRecordAudioPermission = false))
        assertFalse(grants("https://example.com/"))
        assertFalse(grants("http://metamaps.jp/"))
        assertFalse(grants(null))

        assertFalse(grants("https://metamaps.jp/", listOf(camera)))
        assertFalse(grants("https://metamaps.jp/", listOf(microphone, camera)))
        assertFalse(grants("https://metamaps.jp/", emptyList()))
    }

    @Test
    fun announcesVoiceInputOnlyWhenTheMicrophoneWouldBeGrantedAndARecognizerIsAvailable() {
        assertTrue(isWebSpeechRecognitionAvailable(baseUrl, hasRecordAudioPermission = true, hasRecognitionService = true))

        assertFalse(isWebSpeechRecognitionAvailable(baseUrl, hasRecordAudioPermission = false, hasRecognitionService = true))
        assertFalse(isWebSpeechRecognitionAvailable(baseUrl, hasRecordAudioPermission = true, hasRecognitionService = false))
        val localBaseUrl = URI.create("http://localhost:8080")
        assertFalse(isWebSpeechRecognitionAvailable(localBaseUrl, hasRecordAudioPermission = true, hasRecognitionService = true))
    }
}
