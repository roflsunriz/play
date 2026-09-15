package io.github.playmusic.data.api

import io.github.playmusic.data.model.SpotifyContent

data class PlaylistMembership(val playlist: SpotifyContent, val containsAll: Boolean)
