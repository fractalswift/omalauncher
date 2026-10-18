package dev.omalauncher.ui

import android.content.Context
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shared by both mic buttons. Android owns capture, settings and credentials; Linux receives only text. */
class Speech(private val context: Context) {
    private val android = AndroidSpeech(context)
    private val settings = DictationSettings(context)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cloud by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    var processing by mutableStateOf(false)
        private set
    private var cloudText by mutableStateOf("")
    private var cloudLevel by mutableFloatStateOf(0f)
    private var cloudError by mutableStateOf<String?>(null)
    private var recorder: MediaRecorder? = null
    private var clip: File? = null
    private var request: GroqSpeechRequest? = null
    private var job: Job? = null
    private var done: ((String) -> Unit)? = null
    private var generation = 0

    val listening get() = if (cloud) recording else android.listening
    val text get() = if (cloud) cloudText else android.text
    val level get() = if (cloud) cloudLevel else android.level
    var error: String?
        get() = cloudError ?: android.error
        set(value) { cloudError = value; android.error = null }
    val available get() = settings.provider == DictationProvider.Groq || android.available

    fun start(hold: Boolean, onDone: (String) -> Unit) {
        val existing = clip
        if (existing?.isFile == true && !recording && !processing && settings.provider == DictationProvider.Groq) {
            error = null; done = onDone
            if (!online(context)) { error = "No internet; the recording is kept until you retry or leave this window"; return }
            upload(existing)
            return
        }
        cancel()
        error = null
        cloudText = ""
        val offline = onDeviceAvailable(context)
        when (dictationRoute(settings.provider, online(context), offline, settings.fallback)) {
            DictationRoute.Android -> { cloud = false; android.start(hold, language = settings.language, onDone = onDone) }
            DictationRoute.OnDevice -> { cloud = false; android.start(hold, offlineOnly = true, language = settings.language, onDone = onDone) }
            DictationRoute.Unavailable -> { error = "No internet and no existing offline recognition available; no model will be installed" }
            DictationRoute.Groq -> {
                cloud = true
                val key = try { settings.key() } catch (e: IllegalStateException) { error = e.message; return }
                if (key.isBlank()) { error = "No Groq API key saved. Add one in Settings → Microphone"; return }
                done = onDone
                try {
                    val file = File.createTempFile("dictation-", ".m4a", context.cacheDir)
                    clip = file
                    @Suppress("DEPRECATION")
                    val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                    recorder = r
                    r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    r.setAudioChannels(1); r.setAudioSamplingRate(16000); r.setAudioEncodingBitRate(64000)
                    r.setMaxDuration(120_000); r.setMaxFileSize(10 * 1024 * 1024L)
                    r.setOutputFile(file.absolutePath)
                    r.setOnInfoListener { _, what, _ -> if (recorder === r && (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) stop() }
                    r.setOnErrorListener { _, _, _ -> if (recorder === r) { cancel(); error = "Recording failed; please try again" } }
                    r.prepare(); r.start(); recording = true
                    handler.post(meter)
                } catch (_: Exception) { cancel(); error = "Could not record audio; check microphone permission and try again" }
            }
        }
    }

    private val meter = object : Runnable {
        override fun run() {
            if (!recording) return
            cloudLevel = ((runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)) / 10000f).coerceIn(0f, 1f)
            handler.postDelayed(this, 100)
        }
    }

    fun stop() {
        if (!cloud) return android.stop()
        if (!recording) return
        recording = false; cloudLevel = 0f; handler.removeCallbacks(meter)
        try { recorder?.stop() } catch (_: RuntimeException) {
            cancel(); error = "Recording was too short; please try again"; return
        } finally { recorder?.release(); recorder = null }
        clip?.let(::upload)
    }
    fun letGo() { if (!cloud) android.letGo() }

    private fun upload(file: File) {
        val key = try { settings.key() } catch (e: IllegalStateException) { error = e.message; return }
        if (key.isBlank()) { error = "No Groq API key saved. Add one in Settings → Microphone"; return }
        processing = true
        val token = generation
        val next = GroqSpeechRequest(key, settings.model, settings.language.substringBefore('-').lowercase())
        request = next
        job = scope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) { next.transcribe(file) }
                if (token != generation) return@launch
                if (runCatching { settings.key() == key }.getOrDefault(false)) settings.lastKeyResult = "accepted"
                processing = false; cloudText = transcript
                clip = null; file.delete(); request = null
                val callback = done; done = null
                callback?.invoke(transcript)
            } catch (_: CancellationException) {
                // cancel() already cleared the destination and recording.
            } catch (e: Exception) {
                if (token == generation) {
                    if (e is GroqAuthenticationException && runCatching { settings.key() == key }.getOrDefault(false)) settings.lastKeyResult = "rejected"
                    processing = false; request = null
                    error = if (e is IllegalArgumentException) e.message else "${e.message?.takeIf { it.startsWith("Groq") } ?: "Could not reach Groq"}. Tap the microphone to retry; leaving this window discards the recording."
                }
            }
        }
    }

    fun cancel() {
        generation++
        done = null; android.cancel(); handler.removeCallbacks(meter)
        request?.cancel(); request = null; job?.cancel(); job = null
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        recording = false; processing = false; cloudLevel = 0f
        clip?.delete(); clip = null
    }

    companion object {
        fun onDeviceAvailable(context: Context) = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        fun online(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
