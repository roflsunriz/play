package io.github.playmusic.data.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.AuthSession
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

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
}
