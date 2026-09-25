package io.github.playmusic.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.web_session_title)) },
            navigationIcon = {
                IconButton(onClick = onClose, modifier = Modifier.testTag("web-login-close")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
        )
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("web-login-view"),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!WebLoginCapture.isAllowedHost(request.url.host)) return true
                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            harvest(view)
                        }
                    }
                    loadUrl(WebLoginCapture.START_URL)
                }
            },
        )
    }
}
