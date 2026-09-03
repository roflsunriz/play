package io.github.playmusic.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import io.github.playmusic.data.model.AuthSession
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
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

    fun loadDeviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val deviceId = "0${SecureRandom().generateDeviceId()}"
        preferences.edit { putString(KEY_DEVICE_ID, deviceId) }
        return deviceId
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
                username = json.getString("username"),
                accessToken = json.getString("accessToken"),
                storedCredential = json.optString("storedCredential").takeIf(String::isNotBlank)
                    ?.let { Base64.decode(it, Base64.NO_WRAP) },
                expiresAtEpochMs = json.getLong("expiresAtEpochMs"),
            )
        }.getOrElse {
            clearSession()
            null
        }
    }

    fun saveSession(session: AuthSession) {
        val json = JSONObject()
            .put("schemaVersion", SESSION_SCHEMA_VERSION)
            .put("username", session.username)
            .put("accessToken", session.accessToken)
            .put("storedCredential", session.storedCredential?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty())
            .put("expiresAtEpochMs", session.expiresAtEpochMs)
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

    private fun SecureRandom.generateDeviceId(): String {
        val bytes = ByteArray(DEVICE_ID_HEX_BYTES)
        nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFERENCES_NAME = "play_secure_preferences"
        const val KEY_CLIENT_ID = "spotify_client_id"
        const val KEY_DEVICE_ID = "spotify_device_id"
        const val KEY_SESSION = "spotify_session"
        const val KEY_ALIAS = "play_spotify_session_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SESSION_SCHEMA_VERSION = 2
        const val DEVICE_ID_HEX_BYTES = 16
    }
}