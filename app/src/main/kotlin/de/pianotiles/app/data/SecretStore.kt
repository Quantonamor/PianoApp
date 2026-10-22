package de.pianotiles.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the AI API key encrypted with a key from the Android Keystore (AES-GCM). The key material never
 * leaves the device's secure hardware; the encrypted value lives in private SharedPreferences.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "pianotiles_ai_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    var apiKey: String?
        get() = runCatching {
            val data = prefs.getString("apiKey", null) ?: return null
            val iv = Base64.decode(prefs.getString("apiKeyIv", null) ?: return null, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
        set(value) {
            if (value.isNullOrBlank()) { prefs.edit().remove("apiKey").remove("apiKeyIv").apply(); return }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            prefs.edit()
                .putString("apiKey", Base64.encodeToString(c.doFinal(value.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                .putString("apiKeyIv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .apply()
        }

    /** Model id for "KI-nachbessern" (editable in the settings for when newer models come out). */
    var aiModel: String
        get() = prefs.getString("aiModel", null) ?: de.pianotiles.core.ai.ClaudeReviewer.DEFAULT_MODEL
        set(value) { prefs.edit().putString("aiModel", value.trim()).apply() }

    /** The user saw the cost/privacy note before the first upload. */
    var aiConsent: Boolean
        get() = prefs.getBoolean("aiConsent", false)
        set(value) { prefs.edit().putBoolean("aiConsent", value).apply() }
}
