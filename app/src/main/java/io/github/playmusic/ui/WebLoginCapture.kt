package io.github.playmusic.ui

import io.github.playmusic.data.auth.PlaybackAuthorizationClient

/**
 * Host allowlist and cookie parsing for the in-app browser import.
 *
 * The app never injects script into the page and never reads keystrokes: only the sp_dc
 * cookie value is taken from the cookie store after the user signs in normally.
 */
object WebLoginCapture {
    /** The accounts login page is lighter than the player shell and does not depend on endpoint resolution. */
    const val START_URL = "https://accounts.spotify.com/login"

    fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.lowercase()
        return normalized == "open.spotify.com" || normalized == "accounts.spotify.com" ||
            normalized.endsWith(".spotify.com") || normalized.endsWith(".spotifycdn.com") ||
            // Bot check on the login page.
            normalized == "www.google.com" || normalized == "www.gstatic.com"
    }

    /** Returns the validated sp_dc value from a cookie header, or null. Never logs its input. */
    fun extractSpDc(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank() || cookieHeader.length > 16_384) return null
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            if (!trimmed.startsWith("sp_dc=")) continue
            val value = trimmed.removePrefix("sp_dc=")
            if (PlaybackAuthorizationClient.isValidWebCookie(value)) return value
            return null
        }
        return null
    }
}
