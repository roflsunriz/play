package io.github.playmusic.data.api

import io.github.playmusic.data.model.MusicContent

data class PlaylistMembership(val playlist: MusicContent, val containsAll: Boolean)
