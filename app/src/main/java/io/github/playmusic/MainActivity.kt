package io.github.playmusic

import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        PlayViewModel.Factory((application as PlayApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setOnExitAnimationListener { splash ->
            if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
                splash.remove()
            } else {
                splash.iconView.animate().scaleX(1.12f).scaleY(1.12f).alpha(0f)
                    .setDuration(260L).setInterpolator(DecelerateInterpolator()).start()
                splash.view.animate().alpha(0f).setDuration(260L)
                    .withEndAction { splash.remove() }.start()
            }
        }
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
