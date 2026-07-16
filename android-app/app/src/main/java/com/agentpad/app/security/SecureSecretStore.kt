package com.agentpad.app.security

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
 * Profile-scoped secret storage (API keys). Ciphertexts live in SharedPreferences;
 * AES key lives in Android Keystore.
 */
class SecureSecretStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun has(profileId: String): Boolean {
        val id = profileId.trim()
        return preferences.contains(cipherKey(id)) && preferences.contains(ivKey(id))
    }

    fun save(profileId: String, secret: String) {
        val id = profileId.trim()
        require(id.isNotEmpty()) { "profileId 不能为空" }
        require(secret.isNotBlank()) { "API Key 不能为空" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(secret.trim().toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(cipherKey(id), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(ivKey(id), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun read(profileId: String): String? {
        val id = profileId.trim()
        val encodedCiphertext = preferences.getString(cipherKey(id), null) ?: return null
        val encodedIv = preferences.getString(ivKey(id), null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyStore.getKey(KEY_ALIAS, null) as SecretKey,
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)),
                Charsets.UTF_8
            )
        }.getOrNull()
    }

    fun delete(profileId: String) {
        val id = profileId.trim()
        preferences.edit()
            .remove(cipherKey(id))
            .remove(ivKey(id))
            .apply()
    }

    /**
     * Copy legacy single-key store into [profileId] if profile has no key yet.
     */
    fun migrateFromLegacyIfNeeded(profileId: String, legacy: SecureApiKeyStore) {
        if (has(profileId)) return
        val legacyKey = legacy.read() ?: return
        save(profileId, legacyKey)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun cipherKey(profileId: String) = "cipher_$profileId"
    private fun ivKey(profileId: String) = "iv_$profileId"

    private companion object {
        const val PREFS = "agentpad_secrets_v2"
        const val KEY_ALIAS = "agentpad_secrets_aes_v2"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
