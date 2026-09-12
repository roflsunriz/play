package io.github.playmusic.data.auth

object AppConstants {
    /** Official Spotify Android client ID (used for client token and Login5). */
    const val SPOTIFY_CLIENT_ID = "9a8d2f0ce77a4e248bb71fefcb557637"

    /** Version reported by the official app (updates to the current APK version). */
    const val CLIENT_VERSION = "9.1.82.1596"

    /**
     * User agent format used by the official Spotify Android app.
     * The model is static in the captured app; other devices should use Build.MODEL.
     */
    const val SPOTIFY_USER_AGENT = "Spotify/9.1.82.1596 Android/36 (SH-R80P)"
}
