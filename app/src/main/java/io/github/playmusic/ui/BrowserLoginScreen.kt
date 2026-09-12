package io.github.playmusic.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.playmusic.R

data class BrowserAuthorizationUi(val userCode: String, val verificationUri: String)

@Composable
internal fun BrowserLoginScreen(
    pending: BrowserAuthorizationUi?,
    isAuthorizing: Boolean,
    onBegin: () -> Unit,
    onCancel: () -> Unit,
    onFailure: (String?) -> Unit,
) {
    BackHandler(enabled = isAuthorizing, onBack = onCancel)
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(if (pending == null) R.string.browser_login_description else R.string.browser_login_waiting))
        Spacer(Modifier.height(24.dp))
        if (pending != null) {
            SelectionContainer {
                Text(pending.userCode, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("browser-login-code"))
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, pending.verificationUri.toUri())) }
                    catch (exception: android.content.ActivityNotFoundException) { onFailure(exception.message) }
                },
                modifier = Modifier.fillMaxWidth().testTag("open-browser-login-button"),
            ) { Text(stringResource(R.string.browser_login_open)) }
        } else {
            Button(onClick = onBegin, enabled = !isAuthorizing,
                modifier = Modifier.fillMaxWidth().testTag("browser-login-button")) {
                Text(stringResource(R.string.browser_login))
            }
        }
        if (isAuthorizing) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(Modifier.testTag("browser-login-progress"))
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("browser-login-cancel-button")) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
