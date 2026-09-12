package io.github.playmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.playmusic.ui.PlayRoute
import io.github.playmusic.ui.PlayViewModel
import io.github.playmusic.ui.theme.PlayTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PlayViewModel by viewModels {
        PlayViewModel.Factory(AppContainer(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlayTheme {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = BuildConfig.DEBUG }) {
                    PlayRoute(viewModel)
                }
            }
        }
    }
}
