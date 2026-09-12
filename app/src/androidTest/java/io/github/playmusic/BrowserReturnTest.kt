package io.github.playmusic

import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.auth.BrowserAuthorizationClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in on an isolated AVD: a real browser returns a synthetic login response. */
class BrowserReturnTest {
    @Test fun theBrowserReturnsToPlayWithoutAnotherTap(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("externalBrowserProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as PlayApplication).container
        val client = BrowserAuthorizationClient(openConnection = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(
                    """{"access_token":"synthetic","token_type":"Bearer","expires_in":3600,"refresh_token":"synthetic"}""".toByteArray())
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        })
        val pending = client.begin()
        val state = URI(pending.authorizationUrl).rawQuery.split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair[1], "UTF-8")
        }.getValue("state")
        val paused = AtomicBoolean()
        val callback = AtomicBoolean()
        val resumed = AtomicBoolean()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val response = async(Dispatchers.IO) {
                    // Cover a delayed callback after the app has been in the background.
                    delay(12_000)
                    client.awaitAuthorization(pending, "Returning to Play") {
                        callback.set(true)
                        container.returnToApp()
                    }
                }
                scenario.onActivity { activity ->
                    container.startLoginWaiting()
                    activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onPause(owner: LifecycleOwner) { paused.set(true) }
                        override fun onResume(owner: LifecycleOwner) { if (callback.get()) resumed.set(true) }
                    })
                    CustomTabsIntent.Builder().build().launchUrl(activity,
                        (pending.redirectUri + "?code=synthetic-code&state=$state").toUri())
                }
                withTimeout(25_000) { response.await() }
                withTimeout(5_000) { while (!resumed.get()) delay(50) }
                assertTrue("The browser must first move Play to the background", paused.get())
                assertTrue("Play must resume automatically after the callback", resumed.get())
            }
        } finally { pending.close(); container.stopLoginWaiting() }
    }
}
