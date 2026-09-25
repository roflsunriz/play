package io.github.playmusic

import io.github.playmusic.data.auth.BrowserAuthorizationClient
import io.github.playmusic.data.auth.DpopKeys
import io.github.playmusic.data.auth.DpopProofs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

class BrowserAuthorizationDpopTest {
    @Test fun authorizeBindsTheDeviceKeyAndCodeExchangeSendsProofs(): Unit = runBlocking {
        val key = DpopProofs.generate()
        val backend = Backend()
        val client = BrowserAuthorizationClient(
            openConnection = { uri -> backend.connection(uri) },
            dpopKeys = FakeKeys(key),
        )
        val pending = client.begin()
        val parameters = decode(URI(pending.authorizationUrl).rawQuery)
        assertEquals(DpopProofs.thumbprint(key.x, key.y), parameters["dpop_jkt"])
        backend.script.add(Backend.Reply(200,
            """{"access_token":"bound-access","token_type":"DPoP","expires_in":3600,"refresh_token":"bound-refresh"}"""))
        val browser = async(Dispatchers.IO) {
            Socket("127.0.0.1", URI(pending.redirectUri).port).use { socket ->
                val callback = "/login?code=synthetic-code&state=${parameters.getValue("state")}"
                socket.getOutputStream().write("GET $callback HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                socket.getInputStream().bufferedReader().readText()
            }
        }
        val tokens = withTimeout(5_000) { client.awaitAuthorization(pending, "Done") }
        browser.await()
        assertEquals("bound-access", tokens.accessToken)
        assertEquals("bound-refresh", tokens.refreshToken)
        assertEquals("DPoP", tokens.tokenType)
        val exchange = backend.requests.single()
        val proof = checkNotNull(exchange.properties["DPoP"]) { "code exchange must send a DPoP proof" }
        val claims = proofClaims(proof)
        assertEquals("POST", claims.getString("htm"))
        assertTrue(claims.getString("htu").endsWith("/api/token"))
        assertTrue(claims.has("ath"))
        assertEquals(DpopProofs.thumbprint(key.x, key.y),
            DpopProofs.thumbprint(
                proofHeader(proof).getJSONObject("jwk").getString("x"),
                proofHeader(proof).getJSONObject("jwk").getString("y"),
            ))
    }

    @Test fun refreshRetriesOnceWithTheServerNonce(): Unit = runBlocking {
        val key = DpopProofs.generate()
        val backend = Backend()
        backend.script.add(Backend.Reply(400, """{"error":"use_dpop_nonce"}""",
            mapOf("DPoP-Nonce" to "synthetic-nonce")))
        backend.script.add(Backend.Reply(200,
            """{"access_token":"rotated-access","token_type":"DPoP","expires_in":1800,"refresh_token":"rotated-refresh"}"""))
        val client = BrowserAuthorizationClient(
            openConnection = { uri -> backend.connection(uri) },
            dpopKeys = FakeKeys(key),
        )
        val tokens = client.refresh("synthetic-refresh")
        assertEquals("rotated-access", tokens.accessToken)
        assertEquals("DPoP", tokens.tokenType)
        assertEquals(2, backend.requests.size)
        assertNull(backend.requests[0].properties["DPoP"]?.let { proofClaims(it).optString("nonce").ifEmpty { null } })
        assertEquals("synthetic-nonce", proofClaims(backend.requests[1].properties.getValue("DPoP")).getString("nonce"))
    }

    @Test fun loginWithoutKeysKeepsThePreviousBehaviour(): Unit = runBlocking {
        val backend = Backend()
        backend.script.add(Backend.Reply(200,
            """{"access_token":"plain-access","token_type":"Bearer","expires_in":3600}"""))
        val client = BrowserAuthorizationClient(openConnection = { uri -> backend.connection(uri) })
        val pending = client.begin()
        assertFalse(decode(URI(pending.authorizationUrl).rawQuery).containsKey("dpop_jkt"))
        pending.close()
        val tokens = client.refresh("synthetic-refresh")
        assertEquals("Bearer", tokens.tokenType)
        assertNull(tokens.refreshToken)
        assertTrue(backend.requests.none { it.properties.containsKey("DPoP") })
    }

    private class FakeKeys(private val key: DpopProofs.Key) : DpopKeys {
        override fun getOrCreate(): DpopProofs.Key = key
        override fun current(): DpopProofs.Key = key
    }

    private class Backend {
        data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
        data class Seen(val properties: Map<String, String>, val form: Map<String, String>)
        val script = ArrayDeque<Reply>()
        val requests = mutableListOf<Seen>()

        fun connection(uri: URI): HttpURLConnection {
            val reply = script.removeFirst()
            return object : HttpURLConnection(uri.toURL()) {
                private val output = ByteArrayOutputStream()
                private val stored = mutableMapOf<String, String>()
                override fun getOutputStream() = output
                override fun setRequestProperty(key: String, value: String) { stored[key] = value }
                override fun getRequestProperty(key: String): String? = stored[key]
                override fun getResponseCode() = reply.status
                override fun getHeaderField(name: String): String? = reply.headers[name]
                override fun getInputStream(): InputStream {
                    check(reply.status in 200..299)
                    return ByteArrayInputStream(reply.body.toByteArray())
                }
                @Suppress("DEPRECATION")
                override fun getErrorStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray())
                override fun connect() = Unit
                override fun disconnect() {
                    requests += Seen(stored.toMap(), decode(output.toString("UTF-8")))
                }
                override fun usingProxy() = false
            }
        }
    }

    private fun proofHeader(proof: String): JSONObject {
        val header = proof.split('.')
        require(header.size == 3)
        return JSONObject(String(DpopProofs.B64Url.decode(header[0]), Charsets.US_ASCII))
    }

    private fun proofClaims(proof: String): JSONObject {
        val header = proof.split('.')
        require(header.size == 3)
        return JSONObject(String(DpopProofs.B64Url.decode(header[1]), Charsets.US_ASCII))
    }

    private companion object {
        private fun decode(value: String): Map<String, String> = value.split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
    }
}
