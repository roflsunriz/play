package io.github.playmusic.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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

    fun loadDeviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        preferences.getString(LEGACY_KEY_DEVICE_ID, null)?.let { legacy ->
            check(preferences.edit().putString(KEY_DEVICE_ID, legacy).remove(LEGACY_KEY_DEVICE_ID).commit()) {
                "Device identity could not be saved"
            }
            return legacy
        }
        val deviceId = "0${SecureRandom().generateDeviceId()}"
        check(preferences.edit().putString(KEY_DEVICE_ID, deviceId).commit()) { "Device identity could not be saved" }
        return deviceId
    }

    fun loadSession(): AuthSession? {
        val encoded = preferences.getString(KEY_SESSION, null)
            ?: preferences.getString(LEGACY_KEY_SESSION, null)
            ?: return null
        val (session, schema) = runCatching {
            val parts = encoded.split('.', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val json = JSONObject(String(decrypt(iv, ciphertext), Charsets.UTF_8))
            val schema = json.getInt("schemaVersion")
            require(schema in 2..SESSION_SCHEMA_VERSION)
            val session = AuthSession(
                username = json.getString("username"),
                accessToken = json.getString("accessToken"),
                storedCredential = json.optString("storedCredential").takeIf(String::isNotBlank)
                    ?.let { Base64.decode(it, Base64.NO_WRAP) },
                expiresAtEpochMs = json.getLong("expiresAtEpochMs"),
                refreshToken = json.optString("refreshToken").takeIf(String::isNotBlank),
            )
            require(session.username.isNotBlank() && session.accessToken.isNotBlank())
            session to schema
        }.getOrElse {
            clearSession()
            return null
        }
        // A failed migration write must not be mistaken for corrupted encrypted data.
        // A surviving legacy entry is also rewritten to the current key.
        if (schema < SESSION_SCHEMA_VERSION || preferences.contains(LEGACY_KEY_SESSION)) saveSession(session)
        return session
    }

    fun saveSession(session: AuthSession) {
        val json = JSONObject()
            .put("schemaVersion", SESSION_SCHEMA_VERSION)
            .put("username", session.username)
            .put("accessToken", session.accessToken)
            .put("storedCredential", session.storedCredential?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty())
            .put("expiresAtEpochMs", session.expiresAtEpochMs)
            .put("refreshToken", session.refreshToken.orEmpty())
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encoded = listOf(cipher.iv, cipher.doFinal(json))
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
        // Rotated refresh credentials must reach disk before the caller can finish or the process can exit.
        check(preferences.edit().putString(KEY_SESSION, encoded).remove(LEGACY_KEY_SESSION).commit()) {
            "Login information could not be saved"
        }
    }

    fun clearSession() {
        check(preferences.edit().remove(KEY_SESSION).remove(LEGACY_KEY_SESSION).commit()) {
            "Login information could not be cleared"
        }
    }

    /** Decrypts with the current key, falling back to the pre-rename key for migrated installs. */
    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        runCatching {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(KEY_ALIAS), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        }
        val legacyKey = keyFor(LEGACY_KEY_ALIAS) ?: throw IllegalArgumentException("Session cannot be decrypted")
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, legacyKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(alias: String = KEY_ALIAS): SecretKey {
        keyFor(alias)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val specification = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(specification)
        return generator.generateKey()
    }

    private fun keyFor(alias: String): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    private fun SecureRandom.generateDeviceId(): String {
        val bytes = ByteArray(DEVICE_ID_HEX_BYTES)
        nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFERENCES_NAME = "play_secure_preferences"
        const val KEY_DEVICE_ID = "play_device_id"
        const val KEY_SESSION = "play_session"
        const val KEY_ALIAS = "play_session_key"
        // Pre-rename values. Existing installs still hold data under these names, so they must
        // stay byte-identical here only to read and migrate that data. New writes use the keys above.
        const val LEGACY_KEY_DEVICE_ID = "spotify_device_id"
        const val LEGACY_KEY_SESSION = "spotify_session"
        const val LEGACY_KEY_ALIAS = "play_spotify_session_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SESSION_SCHEMA_VERSION = 3
        const val DEVICE_ID_HEX_BYTES = 16
    }
}
