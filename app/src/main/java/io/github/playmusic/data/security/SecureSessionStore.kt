package io.github.playmusic.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import io.github.playmusic.data.model.AuthSession
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadClientId(): String = preferences.getString(KEY_CLIENT_ID, "").orEmpty()

    fun saveClientId(clientId: String) {
        preferences.edit { putString(KEY_CLIENT_ID, clientId.trim()) }
    }

    fun loadSession(): AuthSession? {
        val encoded = preferences.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            require(json.getInt("schemaVersion") == SESSION_SCHEMA_VERSION)
            AuthSession(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                expiresAtEpochMs = json.getLong("expiresAtEpochMs"),
                scope = json.optString("scope"),
            )
        }.getOrElse {
            clearSession()
            null
        }
    }

    fun saveSession(session: AuthSession) {
        val json = JSONObject()
            .put("schemaVersion", SESSION_SCHEMA_VERSION)
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("expiresAtEpochMs", session.expiresAtEpochMs)
            .put("scope", session.scope)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encoded = listOf(cipher.iv, cipher.doFinal(json))
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
        preferences.edit { putString(KEY_SESSION, encoded) }
    }

    fun clearSession() {
        preferences.edit { remove(KEY_SESSION) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val specification = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "play_secure_preferences"
        const val KEY_CLIENT_ID = "spotify_client_id"
        const val KEY_SESSION = "spotify_session"
        const val KEY_ALIAS = "play_spotify_session_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SESSION_SCHEMA_VERSION = 1
    }
}
