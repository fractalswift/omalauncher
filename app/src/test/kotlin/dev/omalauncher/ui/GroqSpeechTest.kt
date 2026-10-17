package dev.omalauncher.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

class GroqSpeechTest {
    private class Connection(private val code: Int = 200, private val response: String = """{"text":"hello"}""") : HttpURLConnection(URL(GroqSpeechRequest.ENDPOINT)) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getResponseCode() = code
    }
    private fun recording(block: (File) -> Unit) {
        val file = File.createTempFile("groq-fixture-", ".m4a")
        try { file.writeBytes(byteArrayOf(1, 2, 3, 4)); block(file) } finally { file.delete() }
    }
    @Test fun sendsAuthenticatedMultipartRecordingAndKeepsTerminalInputOnOneLine() = recording { file ->
        val http = Connection(response = """{"text":"  explain\nthis function\r\nplease  "}""")
        val transcript = GroqSpeechRequest("test-key", "whisper-large-v3-turbo", "en") { http }.transcribe(file)
        assertEquals("explain this function please", transcript)
        assertEquals("POST", http.requestMethod)
        assertEquals("Bearer test-key", http.getRequestProperty("Authorization"))
        assertFalse(http.instanceFollowRedirects)
        val body = http.sent.toString(Charsets.UTF_8.name())
        assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-large-v3-turbo"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nen"))
        assertTrue(body.contains("filename=\"dictation.m4a\""))
        assertFalse(body.contains("test-key"))
        assertTrue(http.disconnected)
    }
    @Test fun automaticLanguageOmitsTheLanguageField() = recording { file ->
        val http = Connection()
        GroqSpeechRequest("test-key", "whisper-large-v3", "") { http }.transcribe(file)
        assertFalse(http.sent.toString(Charsets.UTF_8.name()).contains("name=\"language\""))
    }
    @Test fun providerFailuresDoNotEchoCredentialsOrProviderBody() = recording { file ->
        val http = Connection(401, "sensitive provider detail and token")
        val failure = runCatching { GroqSpeechRequest("test-key", "whisper-large-v3", "") { http }.transcribe(file) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("API key"))
        assertFalse(failure.message!!.contains("sensitive"))
        assertFalse(failure.message!!.contains("test-key"))
        assertTrue(file.exists()) // Retry owns cleanup, not the HTTP transport.
        assertTrue(http.disconnected)
    }
    @Test fun cancellationBeforeUploadDoesNotOpenAConnection() = recording { file ->
        var opened = false
        val request = GroqSpeechRequest("test-key", "whisper-large-v3", "") { opened = true; Connection() }
        request.cancel()
        assertTrue(runCatching { request.transcribe(file) }.exceptionOrNull() is CancellationException)
        assertFalse(opened)
    }
    @Test fun rejectsHeaderInjectionAndMalformedResponses() = recording { file ->
        assertTrue(runCatching { GroqSpeechRequest("key\r\nBad: injected", "whisper-large-v3", "").transcribe(file) }.exceptionOrNull() is IllegalArgumentException)
        val http = Connection(response = "not json")
        assertTrue(runCatching { GroqSpeechRequest("key", "whisper-large-v3", "") { http }.transcribe(file) }.exceptionOrNull() is IOException)
    }
    @Test fun offlineFallbackIsOptionalAndNeverPretendsToInstallAModel() {
        assertEquals(DictationRoute.Groq, dictationRoute(DictationProvider.Groq, online = true, onDevice = false, fallback = true))
        assertEquals(DictationRoute.OnDevice, dictationRoute(DictationProvider.Groq, online = false, onDevice = true, fallback = true))
        assertEquals(DictationRoute.Unavailable, dictationRoute(DictationProvider.Groq, online = false, onDevice = false, fallback = true))
        assertEquals(DictationRoute.Unavailable, dictationRoute(DictationProvider.Groq, online = false, onDevice = true, fallback = false))
        assertEquals(DictationRoute.Unavailable, dictationRoute(DictationProvider.Android, online = false, onDevice = false, fallback = true))
    }
}
