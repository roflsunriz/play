package io.github.playmusic.data.auth

/** Public request profile verified against the desktop client and on the Android device. */
object DesktopClientProfile {
    const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    const val VERSION = "1.2.93.667"
    const val ALBUM_QUERY_VERSION = "896000000"
    val headers = mapOf(
        "App-Platform" to "Win32_x86_64",
        "Spotify-App-Version" to VERSION,
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.7680.179 Spotify/1.2.93.667 Safari/537.36",
    )
}
