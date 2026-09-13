package io.github.playmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/** Hosts synthetic UI tests with the same window setup as MainActivity, without opening an account. */
class PlaylistUiTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}
