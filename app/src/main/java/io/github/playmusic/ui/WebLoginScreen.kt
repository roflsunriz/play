package io.github.playmusic.ui

import android.net.http.SslError
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.playmusic.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets the user sign in to the web player normally inside the app. No script is injected and
 * keystrokes are never read: when the sp_dc cookie appears, only its value is imported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebLoginScreen(onCaptured: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val delivered = remember { AtomicBoolean(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    fun harvest(view: WebView) {
        if (!delivered.compareAndSet(false, true)) return
        val spDc = WebLoginCapture.extractSpDc(CookieManager.getInstance().getCookie("https://open.spotify.com/"))
        if (spDc != null) {
            CookieManager.getInstance().flush()
            onCaptured(spDc)
        } else {
            delivered.set(false)
        }
    }
    // Host names only: page URLs can carry tokens after a successful sign-in.
    fun trace(stage: String, host: String?) {
        Log.i("PlayWebLogin", "$stage host=${host?.take(253)}")
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.web_session_title)) },
            navigationIcon = {
                IconButton(onClick = onClose, modifier = Modifier.testTag("web-login-close")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
        )
        failure?.let {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.request_failed) + " ($it)",
                    modifier = Modifier.testTag("web-login-error"))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { failure = null; webView?.reload() },
                    modifier = Modifier.testTag("web-login-retry")) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("web-login-view"),
            factory = { context ->
                WebView(context).apply {
                    // Behave like the stock browser: the login flow spans accounts and open hosts.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    // Software rendering: hardware compositing stays black on some devices.
                    setBackgroundColor(android.graphics.Color.WHITE)
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!WebLoginCapture.isAllowedHost(request.url.host)) {
                                trace("blocked", request.url.host)
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull()
                            trace("started ${parsed?.encodedPath?.take(80)}", parsed?.host)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            trace("finished", runCatching { android.net.Uri.parse(url).host }.getOrNull())
                            trace("title=" + view.title?.take(80), null)
                            failure = null
                            harvest(view)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (!request.isForMainFrame) return
                            trace("error/${error.errorCode}", request.url.host)
                            failure = "${error.errorCode}"
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            trace("ssl-error", error.url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() })
                            handler.cancel()
                            failure = "ssl"
                        }
                    }
                    webView = this
                    loadUrl(WebLoginCapture.START_URL)
                }
            },
        )
    }
}
