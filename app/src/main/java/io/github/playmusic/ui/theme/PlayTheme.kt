package io.github.playmusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1ED760)
private val DarkColors = darkColorScheme(
    primary = Green,
    secondary = Color(0xFF8EE6AD),
    background = Color(0xFF101010),
    surface = Color(0xFF181818),
    surfaceVariant = Color(0xFF282828),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF087E36),
    secondary = Color(0xFF216D3E),
    background = Color(0xFFF7F7F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFE5E5E5),
)

@Composable
fun PlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
