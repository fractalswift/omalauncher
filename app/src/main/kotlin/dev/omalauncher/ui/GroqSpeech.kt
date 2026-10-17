package dev.omalauncher.ui

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** A single cancellable recording upload. Fixed HTTPS endpoint; credentials never enter Linux. */
class GroqSpeechRequest(
    private val key: String,
    private val model: String,
    private val language: String,
    private val connect: () -> HttpURLConnection = { URL(ENDPOINT).openConnection() as HttpURLConnection },
) {
    @Volatile private var connection: HttpURLConnection? = null
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true); connection?.disconnect() }
    private fun checkCancelled() { if (cancelled.get()) throw CancellationException() }

    fun transcribe(file: File): String {
        require(key.isNotBlank() && key.all { it.code in 33..126 }) { "Configure a valid Groq API key in Microphone settings" }
        require(model in DictationSettings.MODELS) { "Unsupported Groq transcription model" }
        require(language.isEmpty() || language.matches(Regex("[A-Za-z]{2,3}"))) { "Use a language code such as en, or leave language empty" }
        checkCancelled()
        val boundary = "oma-${UUID.randomUUID()}"
        val prefix = ByteArrayOutputStream()
        fun write(text: String) { prefix.write(text.toByteArray(Charsets.UTF_8)) }
        fun field(name: String, value: String) { write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n") }
        field("model", model); field("response_format", "json")
        if (language.isNotEmpty()) field("language", language)
        field("prompt", "Software development. OpenCode, Tailscale, tmux, Groq, GitHub, Linux, TypeScript.")
        write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dictation.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n")
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val http = connect()
        connection = http
        try {
            checkCancelled()
            http.requestMethod = "POST"
            http.instanceFollowRedirects = false
            http.connectTimeout = 15_000; http.readTimeout = 60_000
            http.doOutput = true
            http.setRequestProperty("Authorization", "Bearer $key")
            http.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            http.setFixedLengthStreamingMode(prefix.size().toLong() + file.length() + suffix.size)
            http.outputStream.use { output ->
                prefix.writeTo(output)
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                }
                output.write(suffix)
            }
            checkCancelled()
            val status = http.responseCode
            if (status !in 200..299) throw IOException(when (status) {
                401, 403 -> "Groq rejected the API key; check Microphone settings"
                429 -> "Groq rate limit reached; tap the microphone to retry"
                else -> "Groq transcription failed (HTTP $status); tap the microphone to retry"
            })
            val bytes = http.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1_048_576) {
                    checkCancelled()
                    val n = input.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            if (bytes.size > 1_048_576) throw IOException("Groq returned an oversized response")
            val body = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) { throw IOException("Groq returned an unreadable response") }
            if (!body.has("text") || body.isNull("text")) throw IOException("Groq returned no transcript")
            // Dictation inserts text; a cloud-produced line break must not submit a shell command.
            return body.getString("text").replace(Regex("[\\r\\n]+"), " ").trim()
        } finally { connection = null; http.disconnect() }
    }
    companion object { const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions" }
}
