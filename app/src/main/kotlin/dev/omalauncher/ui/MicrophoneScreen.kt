package dev.omalauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun MicrophoneScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { DictationSettings(context.applicationContext) }
    val speech = remember { Speech(context.applicationContext) }
    val view = LocalView.current
    DisposableEffect(speech, view) {
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) speech.cancel() }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            speech.cancel()
        }
    }
    var provider by remember { mutableStateOf(settings.provider) }
    var model by remember { mutableStateOf(settings.model) }
    var language by remember { mutableStateOf(settings.language) }
    var fallback by remember { mutableStateOf(settings.fallback) }
    var key by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf(settings.hasSavedKey) }
    var message by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    val onDevice = remember { Speech.onDeviceAvailable(context) }

    fun save(): Boolean = try {
        val tag = language.trim()
        require(tag.isEmpty() || tag.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2})?"))) { "Language must be a code such as en, or empty for automatic" }
        require(provider != DictationProvider.Groq || key.isNotBlank() || savedKey) { "Enter a Groq API key first" }
        if (key.isNotBlank()) { settings.saveKey(key.trim()); key = ""; savedKey = true }
        settings.language = tag; settings.model = model; settings.fallback = fallback; settings.provider = provider
        message = "Microphone settings saved"
        true
    } catch (e: Exception) {
        message = if (e is IllegalArgumentException) e.message.orEmpty() else "Could not save microphone credentials"
        false
    }
    fun test() { if (save()) speech.start(hold = true) { transcript = it } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) test() else message = "Microphone permission is required"
    }

    OverlayPanel("microphone", onDismiss, contentWidth = ReadingWidth) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("Used by both the prompt-bar and terminal microphone. Only the transcript is inserted into Linux or SSH.", style = Mono.copy(color = Palette.muted))
            BasicText("provider", style = Mono.copy(color = Palette.accent))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DictationProvider.entries.forEach { choice ->
                    BracketAction((if (provider == choice) "✓ " else "") + choice.label, Palette.accent) { speech.cancel(); provider = choice }
                }
            }
            if (provider == DictationProvider.Groq) {
                BasicText(settings.keyStatus(), style = Mono)
                DictationField(key, "Enter key; leave empty to keep the saved key", password = true) { key = it }
                BasicText("The box stays empty for privacy. Save or test microphone to store a replacement. Saved keys survive app updates and restarts on this device.", style = Mono.copy(color = Palette.muted))
                if (savedKey) BracketAction("remove saved key", Palette.red) {
                    try { speech.cancel(); settings.saveKey(""); savedKey = false; key = ""; message = "Saved Groq key removed" }
                    catch (_: Exception) { message = "Could not remove the saved key" }
                }
                BasicText("model", style = Mono.copy(color = Palette.accent))
                DictationSettings.MODELS.forEach { choice ->
                    BracketAction((if (model == choice) "✓ " else "") + choice, Palette.accent) { model = choice }
                }
                BracketAction("${if (fallback) "✓" else "○"} use existing on-device speech when offline", Palette.accent) { fallback = !fallback }
                BasicText("Groq receives recorded audio over HTTPS. Keys are encrypted using Android Keystore; recordings are temporary and discarded on success or cancellation.", style = Mono.copy(color = Palette.muted))
            }
            BasicText("language", style = Mono.copy(color = Palette.accent))
            DictationField(language, "Automatic (empty), or en, de, …") { language = it }
            BasicText(
                if (onDevice) "An on-device recognizer exists. Offline use checks for an already-installed language model; support is not a promise that a model is installed."
                else "No on-device recognizer is available. Offline dictation is unavailable on this phone.",
                style = Mono.copy(color = Palette.muted),
            )
            BasicText("No speech models are installed or downloaded by Omalauncher. If offline recognition is unavailable, dictation reports it instead of downloading anything.", style = Mono.copy(color = Palette.muted))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BracketAction("save", Palette.accent) { speech.cancel(); save() }
                BracketAction(when { speech.processing -> "cancel transcription"; speech.listening -> "stop test"; else -> "test microphone" }, Palette.accent) {
                    when {
                        speech.processing -> speech.cancel()
                        speech.listening -> speech.stop()
                        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> test()
                        else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            if (message.isNotEmpty()) BasicText(message, style = Mono.copy(color = Palette.muted))
            if (speech.processing) BasicText("Transcribing with Groq…", style = Mono.copy(color = Palette.accent))
            speech.error?.let { BasicText(it, style = Mono.copy(color = Palette.red)) }
            if (transcript.isNotEmpty()) BasicText(transcript, style = Mono)
        }
    }
}

@Composable
private fun DictationField(value: String, placeholder: String, password: Boolean = false, onChange: (String) -> Unit) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = Mono.copy(color = Palette.foreground), cursorBrush = SolidColor(Palette.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().background(Palette.surface).padding(12.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) BasicText(placeholder, style = Mono.copy(color = Palette.muted))
            inner()
        },
    )
}
