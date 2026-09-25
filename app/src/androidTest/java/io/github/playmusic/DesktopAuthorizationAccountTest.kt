package io.github.playmusic

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.auth.BrowserAuthorizationClient
import io.github.playmusic.data.auth.DpopKeys
import io.github.playmusic.data.auth.DpopProofs
import io.github.playmusic.data.auth.LoginCallbackService
import io.github.playmusic.data.auth.PlaybackAuthorizationClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** User-initiated account check: never replaces or clears the app's saved session. */
@RunWith(AndroidJUnit4::class)
class DesktopAuthorizationAccountTest {
    @Test fun browserAuthorizationGrantsPlaybackTransferScope(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDesktopAuth") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = DpopProofs.generate()
        val client = BrowserAuthorizationClient(dpopKeys = object : DpopKeys {
            override fun getOrCreate() = key
            override fun current() = key
        })
        val pending = client.begin()
        val waitingService = Intent(context, LoginCallbackService::class.java)
        try {
            ContextCompat.startForegroundService(context, waitingService)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.authorizationUrl))
                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val tokens = withTimeout(300_000) { client.awaitAuthorization(pending, "Sign-in check finished") }
            assertTrue("Browser token lacks playback transfer scope",
                "transfer-auth-session" in tokens.grantedScopes)
            PlaybackAuthorizationClient().connect(tokens.accessToken).use { session ->
                session.configuration()
            }
        } finally {
            pending.close()
            context.stopService(waitingService)
        }
    }
}
