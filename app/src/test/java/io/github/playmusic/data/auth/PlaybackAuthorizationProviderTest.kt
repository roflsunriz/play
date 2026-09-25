package io.github.playmusic.data.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class PlaybackAuthorizationProviderTest {
    @Test fun reusesValidAuthorizationAndRefreshesAtExpiryOrOnDemand() = runTest {
        var now = 1000L
        val forced = mutableListOf<Boolean>()
        val provider = PlaybackAuthorizationProvider({ sourceSnapshot("own-source") }, { _, force ->
            forced += force
            credentials("web-${forced.size}", now + 10_000)
        }, { now })
        assertEquals("Bearer web-1", provider.headers(LICENSE)["Authorization"])
        assertEquals("Bearer web-1", provider.headers(LICENSE)["Authorization"])
        now += 10_000
        assertEquals("Bearer web-2", provider.headers(LICENSE)["Authorization"])
        assertEquals("Bearer web-3", provider.headers(LICENSE, true)["Authorization"])
        assertEquals(listOf(false, false, true), forced)
    }

    @Test fun preparedAuthorizationIsReusedByLicenseRequests() = runTest {
        var calls = 0
        val provider = PlaybackAuthorizationProvider({ sourceSnapshot("own-source") }, { _, _ ->
            calls++
            credentials("web-$calls", 20_000)
        }, { 1000 })
        provider.prepare(LICENSE)
        assertEquals("Bearer web-1", provider.headers(LICENSE)["Authorization"])
        assertEquals(1, calls)
    }

    @Test fun invalidatesForAnotherSignInAndAfterLogout() = runTest {
        var source: String? = "first"
        var calls = 0
        val provider = PlaybackAuthorizationProvider({ sourceSnapshot(checkNotNull(source)) }, { token, _ ->
            calls++; credentials("web-$token", 20_000)
        }, { 1000 })
        provider.headers(LICENSE)
        source = "second"
        assertEquals("Bearer web-second", provider.headers(LICENSE)["Authorization"])
        source = null
        assertFails { provider.headers(LICENSE) }
        source = "second"
        provider.headers(LICENSE)
        assertEquals(3, calls)
    }

    @Test fun discardsAnAuthorizationCompletedForAnEarlierSignIn() = runTest {
        var source = "first"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val provider = PlaybackAuthorizationProvider({ sourceSnapshot(source) }, { token, _ ->
            calls++
            if (calls == 1) { entered.complete(Unit); release.await() }
            credentials("web-$token", 20_000)
        }, { 1000 })
        val first = async { runCatching { provider.headers(LICENSE) } }
        entered.await()
        source = "second"
        release.complete(Unit)
        assertTrue(first.await().isFailure)
        assertEquals("Bearer web-second", provider.headers(LICENSE)["Authorization"])
        assertEquals(2, calls)
    }

    @Test fun enforcesGrantedHostsAndDoesNotExposeCredentialsInDescriptions() = runTest {
        val credentials = credentials("private-web-access", 20_000)
        val provider = PlaybackAuthorizationProvider({ sourceSnapshot("own-source") }, { _, _ -> credentials }, { 1000 })
        for (uri in listOf("http://service.example/license", "https://service.example.attacker.invalid/license",
            "https://user@service.example/license", "https://service.example:444/license")) {
            assertFails { provider.headers(URI(uri)) }
        }
        assertEquals("private-client-token", provider.headers(LICENSE)["client-token"])
        assertFalse(credentials.toString().contains("private-web-access"))
        assertFalse(credentials.toString().contains("private-client-token"))
    }

    @Test fun refreshesTheSourceOnceWhenTransferRejectsAnOtherwiseCurrentToken() = runTest {
        var token = "stale"
        var refreshes = 0
        var acquisitions = 0
        val provider = PlaybackAuthorizationProvider({ force ->
            if (force) { refreshes++; token = "fresh" }
            PlaybackAuthorizationProvider.Source("same-owner", token)
        }, { value, force ->
            acquisitions++
            if (value == "stale") throw rejectedTransfer()
            assertTrue(force)
            credentials("web-fresh", 20_000)
        }, { 1000 })
        assertEquals("Bearer web-fresh", provider.headers(LICENSE)["Authorization"])
        assertEquals(1, refreshes)
        assertEquals(2, acquisitions)
    }

    @Test fun doesNotAuthorizeAnotherAccountWhenTransferFailsDuringASwitch() = runTest {
        var owner = "first"
        var refreshes = 0
        var acquisitions = 0
        val provider = PlaybackAuthorizationProvider({ force ->
            if (force) refreshes++
            PlaybackAuthorizationProvider.Source(owner, "source-$owner")
        }, { _, _ -> acquisitions++; owner = "second"; throw rejectedTransfer() }, { 1000 })
        assertFails { provider.headers(LICENSE) }
        assertEquals(0, refreshes)
        assertEquals(1, acquisitions)
    }

    @Test fun fallsBackToTheImportedWebSessionWhenTransferRejectsTheBearer() = runTest {
        var acquisitions = 0
        var cookieUses = 0
        val provider = PlaybackAuthorizationProvider(
            { PlaybackAuthorizationProvider.Source("owner", "bearer", "imported-cookie") },
            { _, _ -> acquisitions++; throw rejectedTransferForbidden() },
            { 1000 },
            { cookie, _ -> cookieUses++; assertEquals("imported-cookie", cookie); credentials("web-cookie", 20_000) },
        )
        assertEquals("Bearer web-cookie", provider.headers(LICENSE)["Authorization"])
        assertEquals(1, acquisitions)
        assertEquals(1, cookieUses)
        // The fallback result is cached like a normal acquisition.
        assertEquals("Bearer web-cookie", provider.headers(LICENSE)["Authorization"])
        assertEquals(1, cookieUses)
    }

    @Test fun transferRejectionFailsWithoutAnImportedCookie() = runTest {
        val provider = PlaybackAuthorizationProvider({ sourceSnapshot("own-source") },
            { _, _ -> throw rejectedTransferForbidden() }, { 1000 })
        assertFails { provider.headers(LICENSE) }
        assertTrue(provider.transferRefusedWithoutCookie)
    }

    @Test fun successfulAuthorizationClearsTheTransferRefusalSignal() = runTest {
        var calls = 0
        val provider = PlaybackAuthorizationProvider({ sourceSnapshot("own-source") }, { _, _ ->
            calls++
            if (calls == 1) throw rejectedTransferForbidden()
            credentials("web-recovered", 20_000)
        }, { 1000 })
        assertFails { provider.headers(LICENSE) }
        assertTrue(provider.transferRefusedWithoutCookie)
        assertEquals("Bearer web-recovered", provider.headers(LICENSE)["Authorization"])
        assertTrue(!provider.transferRefusedWithoutCookie)
    }

    @Test fun cookieMismatchInvalidatesTheCachedAuthorization() = runTest {
        var cookie: String? = "first-cookie"
        var cookieUses = 0
        val provider = PlaybackAuthorizationProvider(
            { PlaybackAuthorizationProvider.Source("owner", "bearer", cookie) },
            { _, _ -> throw rejectedTransferForbidden() },
            { 1000 },
            { _, _ -> cookieUses++; credentials("web-$cookieUses", 20_000) },
        )
        assertEquals("Bearer web-1", provider.headers(LICENSE)["Authorization"])
        cookie = "second-cookie"
        assertEquals("Bearer web-2", provider.headers(LICENSE)["Authorization"])
        assertEquals(2, cookieUses)
    }

    private fun sourceSnapshot(token: String) = PlaybackAuthorizationProvider.Source(token, token)
    private fun rejectedTransfer() = PlaybackAuthorizationClient.PlaybackAuthorizationException(
        PlaybackAuthorizationClient.Stage.TRANSFER, PlaybackAuthorizationClient.Failure.HTTP, 401,
    )

    private fun rejectedTransferForbidden() = PlaybackAuthorizationClient.PlaybackAuthorizationException(
        PlaybackAuthorizationClient.Stage.TRANSFER, PlaybackAuthorizationClient.Failure.HTTP, 403,
    )

    private suspend fun assertFails(block: suspend () -> Unit) {
        val result = runCatching { block() }
        assertTrue("The rejected authorization must fail", result.isFailure)
    }

    private fun credentials(value: String, expires: Long) = PlaybackAuthorizationProvider.Credentials(
        value, GrantedClientToken("private-client-token", 60, 45, listOf("service.example")), expires,
    )

    private companion object { val LICENSE = URI("https://service.example/license") }
}
