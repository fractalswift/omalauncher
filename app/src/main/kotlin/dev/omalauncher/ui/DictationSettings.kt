package dev.omalauncher.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class DictationProvider(val label: String) { Android("Android speech"), Groq("Groq") }
enum class DictationRoute { Android, OnDevice, Groq, Unavailable }

/** Optional offline fallback means an existing Android recognizer, never downloading a model. */
fun dictationRoute(provider: DictationProvider, online: Boolean, onDevice: Boolean, fallback: Boolean): DictationRoute =
    when {
        provider == DictationProvider.Android && online -> DictationRoute.Android
        online -> DictationRoute.Groq
        onDevice && (provider == DictationProvider.Android || fallback) -> DictationRoute.OnDevice
        else -> DictationRoute.Unavailable
    }

class DictationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("dictation", Context.MODE_PRIVATE)
    var provider: DictationProvider
        get() = runCatching { DictationProvider.valueOf(prefs.getString("provider", "Android")!!) }.getOrDefault(DictationProvider.Android)
        set(value) { prefs.edit().putString("provider", value.name).apply() }
    var fallback: Boolean
        get() = prefs.getBoolean("existing_offline_fallback", true)
        set(value) { prefs.edit().putBoolean("existing_offline_fallback", value).apply() }
    var model: String
        get() = prefs.getString("groq_model", MODELS.first())!!.takeIf { it in MODELS } ?: MODELS.first()
        set(value) { require(value in MODELS); prefs.edit().putString("groq_model", value).apply() }
    var language: String
        get() = prefs.getString("language", "").orEmpty()
        set(value) { require(value.isEmpty() || value.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2})?"))); prefs.edit().putString("language", value).apply() }

    /** Only ciphertext is stored in preferences. Neither this store nor its UI logs the key. */
    val hasSavedKey get() = prefs.contains("groq_key")
    var lastKeyResult: String
        get() = prefs.getString("groq_key_result", "untested").orEmpty()
        set(value) { prefs.edit().putString("groq_key_result", value).apply() }

    fun keyStatus(): String = when {
        !hasSavedKey -> "No Groq API key saved"
        runCatching { key() }.isFailure -> "Key saved, but Android cannot decrypt it. Retry after unlocking your phone; if this persists, replace the key."
        lastKeyResult == "rejected" -> "Key saved · Groq last denied access (HTTP 401/403). Check the key and account permissions."
        lastKeyResult == "accepted" -> "Key saved · last transcription succeeded"
        else -> "Key saved · not yet verified by a successful transcription"
    }

    fun key(): String {
        val encrypted = prefs.getString("groq_key", null) ?: return ""
        return runCatching {
            val parts = encrypted.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(create = false), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrElse { throw IllegalStateException("Groq key is saved but Android cannot decrypt it. Unlock your phone and retry; if this persists, replace it in Microphone settings.") }
    }
    fun saveKey(value: String) {
        if (value.isBlank()) { check(prefs.edit().remove("groq_key").remove("groq_key_result").commit()) { "Could not remove Groq key" }; return }
        require(value.all { it.code in 33..126 }) { "API key must be a single line without spaces" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("groq_key", encoded).remove("groq_key_result").commit()) { "Could not save Groq key on this device" }
    }
    private fun secret(create: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Saved encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        private const val ALIAS = "omalauncher.dictation.groq"
        val MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3")
    }
}
