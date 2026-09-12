package io.github.playmusic

import android.util.Base64
import io.github.playmusic.data.auth.BrowserAuthorizationClient
import io.github.playmusic.data.auth.SpotifyAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

class BrowserAuthorizationClientTest {
    @Test fun usesPkceAndReceivesTheCallbackWithoutCodeEntry(): Unit = runBlocking {
        var request: Connection? = null
        val client = BrowserAuthorizationClient(openConnection = { uri -> Connection(uri).also { request = it } })
        val pending = client.begin()
        val parameters = decode(URI(pending.authorizationUrl).rawQuery)
        assertEquals("/authorize", URI(pending.authorizationUrl).path)
        assertEquals("code", parameters["response_type"])
        assertEquals("S256", parameters["code_challenge_method"])
        assertFalse(parameters.containsKey("device_code"))
        assertEquals("127.0.0.1", URI(pending.redirectUri).host)
        val browser = async(Dispatchers.IO) {
            Socket("127.0.0.1", URI(pending.redirectUri).port).use { socket ->
                val callback = "/login?code=synthetic-code&state=${parameters.getValue("state")}"
                socket.getOutputStream().write("GET $callback HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                socket.getInputStream().bufferedReader().readText()
            }
        }
        var returned = false
        val tokens = withTimeout(5_000) { client.awaitAuthorization(pending, "Returning to <Play>") { returned = true } }
        assertTrue(returned)
        assertEquals("synthetic-access", tokens.accessToken)
        assertEquals("synthetic-refresh", tokens.refreshToken)
        assertTrue(pending.server.isClosed)
        assertTrue(browser.await().contains("&lt;Play&gt;"))
        val form = checkNotNull(request).form()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("synthetic-code", form["code"])
        assertEquals(pending.redirectUri, form["redirect_uri"])
        val hash = MessageDigest.getInstance("SHA-256").digest(form.getValue("code_verifier").toByteArray())
        assertEquals(parameters["code_challenge"], Base64.encodeToString(hash, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE))
        assertTrue(checkNotNull(request).disconnected)
    }

    @Test fun cancelledOrExpiredLoginClosesItsListener(): Unit = runBlocking {
        var time = 0L
        var requested = false
        val client = BrowserAuthorizationClient(openConnection = { requested = true; error("Unexpected token request") }, now = { time })
        val expired = client.begin()
        time = 700_000
        assertThrows(SpotifyAuthException::class.java) { runBlocking { client.awaitAuthorization(expired, "Done") } }
        assertTrue(expired.server.isClosed)
        val cancelled = client.begin()
        cancelled.close()
        assertTrue(cancelled.server.isClosed)
        assertFalse(requested)
    }

    @Test fun renewingAccessDoesNotOpenANewLoginFlow(): Unit = runBlocking {
        var request: Connection? = null
        val client = BrowserAuthorizationClient(openConnection = { uri ->
            Connection(uri, includeRefresh = false).also { request = it }
        })
        val tokens = client.refresh("synthetic-refresh")
        assertNull(tokens.refreshToken)
        assertEquals("refresh_token", request?.form()?.get("grant_type"))
        assertEquals("synthetic-refresh", request?.form()?.get("refresh_token"))
    }

    @Test fun nullRefreshKeepsTheExistingCredentialAndObjectsAreRejected(): Unit = runBlocking {
        val empty = BrowserAuthorizationClient(openConnection = { uri -> Connection(uri, refreshJson = "null") })
        assertNull(empty.refresh("synthetic-refresh").refreshToken)
        val invalid = BrowserAuthorizationClient(openConnection = { uri -> Connection(uri, refreshJson = "{}") })
        assertThrows(SpotifyAuthException::class.java) { runBlocking { invalid.refresh("synthetic-refresh") } }
    }

    private class Connection(uri: URI, private val includeRefresh: Boolean = true,
        private val refreshJson: String = "\"synthetic-refresh\"") : HttpURLConnection(uri.toURL()) {
        private val output = ByteArrayOutputStream()
        var disconnected = false
        fun form() = decode(output.toString("UTF-8"))
        override fun getOutputStream() = output
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(
            ("""{"access_token":"synthetic-access","token_type":"Bearer","expires_in":3600""" +
                (if (includeRefresh) ""","refresh_token":$refreshJson}""" else "}")).toByteArray())
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }

    companion object {
        private fun decode(value: String): Map<String, String> = value.split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
    }
}
