package io.github.playmusic.data.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.AuthSession
import io.github.playmusic.data.api.SessionManager
import io.github.playmusic.data.auth.BrowserAuthorizationClient
import io.github.playmusic.data.auth.SpotifyClientTokenClient
import io.github.playmusic.data.auth.SpotifyLogin5Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStoreTest {
    private val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "session_test_${java.util.UUID.randomUUID()}"
    private val context = object : ContextWrapper(baseContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    }

    @After
    fun cleanup() {
        baseContext.deleteSharedPreferences(preferenceName)
    }

    @Test
    fun encryptedSessionAndDeviceIdSurviveNewStoreInstances() {
        val first = SecureSessionStore(context)
        val deviceId = first.loadDeviceId()
        first.saveSession(AuthSession("synthetic-user", "synthetic-access-token", byteArrayOf(1, 2, 3), Long.MAX_VALUE))
        val second = SecureSessionStore(context)
        val loaded = checkNotNull(second.loadSession())
        assertEquals("synthetic-user", loaded.username)
        assertEquals("synthetic-access-token", loaded.accessToken)
        assertArrayEquals(byteArrayOf(1, 2, 3), loaded.storedCredential)
        assertEquals(deviceId, second.loadDeviceId())
        val serialized = context.getSharedPreferences("unused", 0).all.values.joinToString()
        assertFalse(serialized.contains("synthetic-user"))
        assertFalse(serialized.contains("synthetic-access-token"))
    }

    @Test
    fun corruptedSessionIsClearedWithoutLosingDeviceIdentity() {
        val store = SecureSessionStore(context)
        val deviceId = store.loadDeviceId()
        context.getSharedPreferences("unused", 0).edit().putString("spotify_session", "invalid.ciphertext").commit()
        assertNull(store.loadSession())
        assertFalse(context.getSharedPreferences("unused", 0).contains("spotify_session"))
        assertEquals(deviceId, store.loadDeviceId())
    }

    @Test fun browserRefreshTokenIsEncryptedAndSurvivesRestart() {
        val store = SecureSessionStore(context)
        store.saveSession(AuthSession("browser-user", "browser-access", null, Long.MAX_VALUE, "browser-refresh-secret"))
        val loaded = checkNotNull(SecureSessionStore(context).loadSession())
        assertEquals("browser-refresh-secret", loaded.refreshToken)
        assertNull(loaded.storedCredential)
        assertFalse(context.getSharedPreferences("unused", 0).all.values.joinToString().contains("browser-refresh-secret"))
    }

    @Test fun persistenceFailureIsReportedInsteadOfClaimingTheLoginWasSaved() {
        val backing = context.getSharedPreferences("unused", 0)
        val preferences = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by backing.edit() {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                override fun commit() = false
                override fun apply() { throw AssertionError("Session writes must finish before returning") }
            }
        }
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        assertThrows(IllegalStateException::class.java) {
            SecureSessionStore(failing).saveSession(AuthSession("synthetic", "synthetic", null, 0, "synthetic"))
        }
    }

    @Test fun schemaTwoMigratesInPlaceWithoutLosingCredentialsOrDeviceIdentity() {
        val store = SecureSessionStore(context)
        val device = store.loadDeviceId()
        store.saveSession(AuthSession("legacy-user", "legacy-access", byteArrayOf(1, 2, 3), Long.MAX_VALUE))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("play_spotify_session_key", null) as SecretKey
        val legacy = """{"schemaVersion":2,"username":"legacy-user","accessToken":"legacy-access",
            "storedCredential":"AQID","expiresAtEpochMs":9223372036854775807}"""
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encoded = listOf(encrypt.iv, encrypt.doFinal(legacy.toByteArray()))
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
        val prefs = context.getSharedPreferences("unused", 0)
        prefs.edit().putString("spotify_session", encoded).commit()
        val loaded = checkNotNull(store.loadSession())
        assertEquals("legacy-user", loaded.username)
        assertNull(loaded.refreshToken)
        assertArrayEquals(byteArrayOf(1, 2, 3), loaded.storedCredential)
        assertEquals(device, store.loadDeviceId())
        val migrated = checkNotNull(prefs.getString("spotify_session", null)).split('.')
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(migrated[0], Base64.NO_WRAP)))
        }
        val json = JSONObject(String(decrypt.doFinal(Base64.decode(migrated[1], Base64.NO_WRAP))))
        assertEquals(3, json.getInt("schemaVersion"))
    }

    @Test fun anOldRefreshCannotRestoreALoggedOutOrReplacedAccount(): Unit = runBlocking {
        for (replace in listOf(false, true)) {
            val store = SecureSessionStore(context)
            val started = CountDownLatch(1)
            val continueResponse = CountDownLatch(1)
            val clientTokens = SpotifyClientTokenClient(openConnection = { error("Unexpected legacy authentication") })
            val oauth = BrowserAuthorizationClient(openConnection = { uri ->
                object : HttpURLConnection(uri.toURL()) {
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getResponseCode(): Int {
                        started.countDown()
                        check(continueResponse.await(5, TimeUnit.SECONDS))
                        return 200
                    }
                    override fun getInputStream() = ByteArrayInputStream(
                        """{"access_token":"old-account-refreshed","token_type":"Bearer","expires_in":3600}""".toByteArray())
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                }
            })
            val manager = SessionManager(store, SpotifyLogin5Client("synthetic-client", clientTokens), clientTokens, oauth)
            manager.replaceSession(AuthSession("old-user", "expired", null, 0, "synthetic-refresh"))
            val refresh = async(Dispatchers.IO) { runCatching { manager.accessToken() } }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                if (replace) manager.replaceSession(AuthSession("new-user", "new-access", null, Long.MAX_VALUE, "new-refresh"))
                else manager.clearSession()
            } finally { continueResponse.countDown() }
            assertTrue(refresh.await().exceptionOrNull() is IllegalStateException)
            val session = store.loadSession()
            if (replace) {
                assertEquals("new-user", session?.username)
                assertEquals("new-access", session?.accessToken)
            } else assertNull(session)
        }
    }

    @Test fun cancellingSearchDuringRefreshStillPersistsRotatedCredentials(): Unit = runBlocking {
        val store = SecureSessionStore(context)
        val started = CountDownLatch(1)
        val continueResponse = CountDownLatch(1)
        val refreshInputs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val clientTokens = SpotifyClientTokenClient(openConnection = { error("Unexpected legacy authentication") })
        val oauth = BrowserAuthorizationClient(openConnection = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                val body = ByteArrayOutputStream()
                override fun getOutputStream() = body
                override fun getResponseCode(): Int {
                    refreshInputs += body.toString("UTF-8")
                    if (refreshInputs.size == 1) {
                        started.countDown()
                        check(continueResponse.await(5, TimeUnit.SECONDS))
                    }
                    return 200
                }
                override fun getInputStream() = ByteArrayInputStream(
                    """{"access_token":"renewed-access","refresh_token":"rotated-${refreshInputs.size}","token_type":"Bearer","expires_in":3600}""".toByteArray())
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        })
        fun manager() = SessionManager(SecureSessionStore(context),
            SpotifyLogin5Client("synthetic-client", clientTokens), clientTokens, oauth)
        val first = manager()
        first.replaceSession(AuthSession("synthetic-user", "expired", null, 0, "original-refresh"))
        val search = async(Dispatchers.Default) { first.accessToken() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            search.cancel()
        } finally { continueResponse.countDown() }
        search.join()
        assertTrue(search.isCancelled)
        assertEquals("rotated-1", SecureSessionStore(context).loadSession()?.refreshToken)
        // A new store/manager must use the rotated value, including after a caller is gone.
        manager().accessToken(forceRefresh = true)
        assertTrue(refreshInputs[1].contains("refresh_token=rotated-1"))
        assertEquals("rotated-2", store.loadSession()?.refreshToken)
    }
}
