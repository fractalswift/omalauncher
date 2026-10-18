package dev.omalauncher.sessions

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API token stays Android-private and encrypted, outside the Linux guest and widget sources. */
internal class DiscoveryCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("tailscale-discovery", Context.MODE_PRIVATE)
    val tailnet get() = prefs.getString("tailnet", "-").orEmpty()
    val configured get() = prefs.contains("token")

    fun token(): String {
        val encoded = prefs.getString("token", null) ?: return ""
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalStateException("Tailscale token is saved but Android cannot decrypt it. Unlock your phone and retry; if this persists, replace the token in discovery settings.")
        }
    }

    fun status(): String = when {
        !configured -> "No API token saved"
        runCatching { token() }.isFailure -> "Token saved, but Android cannot decrypt it. Unlock your phone and retry; if this persists, replace the token."
        else -> "API token saved on this device"
    }

    fun save(tailnet: String, token: String) {
        val editor = prefs.edit().putString("tailnet", tailnet.trim().ifBlank { "-" })
        if (token.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            editor.putString("token", Base64.encodeToString(cipher.iv + cipher.doFinal(token.trim().toByteArray()), Base64.NO_WRAP))
        }
        check(editor.commit()) { "Tailscale token could not be saved on this device" }
    }

    fun clear() { check(prefs.edit().clear().commit()) { "Tailscale token could not be removed" } }

    private fun key(create: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Saved encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object { private const val ALIAS = "omalauncher-tailscale-discovery" }
}
