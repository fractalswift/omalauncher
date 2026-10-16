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
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }

    fun save(tailnet: String, token: String) {
        val editor = prefs.edit().putString("tailnet", tailnet.trim().ifBlank { "-" })
        if (token.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            editor.putString("token", Base64.encodeToString(cipher.iv + cipher.doFinal(token.trim().toByteArray()), Base64.NO_WRAP))
        }
        editor.apply()
    }

    fun clear() { prefs.edit().clear().apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object { private const val ALIAS = "omalauncher-tailscale-discovery" }
}
