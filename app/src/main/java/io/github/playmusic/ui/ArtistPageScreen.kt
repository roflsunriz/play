package io.github.playmusic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.playmusic.R
import io.github.playmusic.data.model.*
import java.text.NumberFormat

private enum class ArtistDiscographyFilter { ALL, ALBUMS, SINGLES }

@Composable
internal fun ArtistPageScreen(selected: MusicContent, detail: ContentDetail?, loading: Boolean,
    followBusy: Boolean, radioBusy: Boolean, onPlay: (MusicContent) -> Unit,
    onPlayTrack: (Int) -> Unit, onOpen: (MusicContent) -> Unit, onFollow: () -> Unit,
    onRadio: (MusicContent) -> Unit, onActions: (MusicContent) -> Unit,
    savedUris: Set<String>, onRetry: () -> Unit) {
    val content = detail?.content ?: selected
    val page = detail?.artistPage
    var filter by rememberSaveable(selected.uri) { mutableStateOf(ArtistDiscographyFilter.ALL) }
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    LazyColumn(Modifier.fillMaxSize().testTag("artist-page"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content.imageUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(156.dp).clip(RoundedCornerShape(12.dp)).testTag("artist-artwork")) }
                Text(content.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("artist-name"))
                page?.monthlyListeners?.let { Text(stringResource(R.string.artist_monthly_listeners, numbers.format(it)),
                    modifier = Modifier.testTag("artist-listeners")) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPlay(content) }, enabled = detail?.tracks?.any { it.isPlayable != false } == true,
                        modifier = Modifier.testTag("artist-play")) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.play))
                    }
                    val followDescription = stringResource(if (page?.isFollowed == true) R.string.artist_unfollow else R.string.artist_follow)
                    OutlinedButton(onClick = onFollow, enabled = page != null && !followBusy,
                        modifier = Modifier.testTag("artist-follow").semantics { contentDescription = followDescription }) {
                        if (followBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else if (page?.isFollowed == true) Icon(Icons.Default.Check, null)
                        Text(stringResource(if (page?.isFollowed == true) R.string.artist_following else R.string.artist_follow))
                    }
                }
            }
        }
        if (loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (page == null && !loading) item("retry") {
            Text(stringResource(R.string.detail_not_loaded))
            TextButton(onClick = onRetry, modifier = Modifier.testTag("artist-retry")) { Text(stringResource(R.string.refresh)) }
        }
        if (page != null) {
            if (page.unavailableRelatedItems > 0) item("related-error") {
                Text(stringResource(R.string.artist_partial_content, page.unavailableRelatedItems),
                    modifier = Modifier.testTag("artist-related-error"), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.testTag("artist-related-retry")) {
                    Text(stringResource(R.string.refresh))
                }
            }
            item("popular-title") { ArtistSectionTitle(R.string.artist_popular_tracks) }
            itemsIndexed(detail.tracks, key = { index, track -> "popular:$index:${track.uri}" }) { index, track ->
                Card(onClick = { onOpen(track) }, modifier = Modifier.fillMaxWidth().testTag("artist-popular-$index")) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((index + 1).toString(), Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, fontWeight = FontWeight.Medium)
                            track.albumTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        ContentAddButton(track, track.uri in savedUris, onActions)
                        IconButton(onClick = { onPlayTrack(index) }, enabled = track.isPlayable != false,
                            modifier = Modifier.testTag("artist-popular-play-$index")) { Icon(Icons.Default.PlayArrow, stringResource(R.string.play)) }
                    }
                }
            }
            item("discography-title") {
                ArtistSectionTitle(R.string.artist_discography)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArtistDiscographyFilter.entries.forEach { option -> FilterChip(selected = filter == option,
                        onClick = { filter = option }, modifier = Modifier.testTag("artist-discography-${option.name.lowercase()}"),
                        label = { Text(stringResource(when (option) {
                            ArtistDiscographyFilter.ALL -> R.string.search_filter_all
                            ArtistDiscographyFilter.ALBUMS -> R.string.albums
                            ArtistDiscographyFilter.SINGLES -> R.string.artist_singles_eps
                        })) }) }
                }
            }
            val releases = page.discography.filter { when (filter) {
                ArtistDiscographyFilter.ALL -> true
                ArtistDiscographyFilter.ALBUMS -> it.type == ArtistReleaseType.ALBUM || it.type == ArtistReleaseType.COMPILATION
                ArtistDiscographyFilter.SINGLES -> it.type == ArtistReleaseType.SINGLE || it.type == ArtistReleaseType.EP
            } }
            if (releases.isEmpty()) item("no-releases") { Text(stringResource(R.string.artist_no_releases)) }
            items(releases, key = { "release:${it.content.uri}" }) {
                ArtistWorkCard(it.content, "artist-release-${it.content.id}", onOpen, onPlay, onActions, savedUris)
            }
            item("radio-title") { ArtistSectionTitle(R.string.artist_song_radios) }
            items(page.songRadioSeeds.distinctBy { it.uri }, key = { "radio:${it.uri}" }) { track ->
                OutlinedButton(onClick = { onRadio(track) }, enabled = !radioBusy,
                    modifier = Modifier.fillMaxWidth().testTag("artist-radio-${track.id}")) {
                    Icon(Icons.Default.Radio, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.artist_radio_for_song, track.title), Modifier.weight(1f))
                }
            }
            if (radioBusy) item("radio-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item("about") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("artist-about")) {
                    ArtistSectionTitle(R.string.artist_about)
                    page.monthlyListeners?.let { Text(stringResource(R.string.artist_monthly_listeners, numbers.format(it))) }
                    page.followers?.let { Text(stringResource(R.string.artist_followers, numbers.format(it))) }
                    page.worldRank?.let { Text(stringResource(R.string.artist_world_rank, numbers.format(it))) }
                    Text(page.biography?.takeIf { it.isNotBlank() }?.let(::readableDescription)
                        ?: stringResource(R.string.artist_no_biography), modifier = Modifier.testTag("artist-biography"))
                }
            }
            item("appears-on-title") { ArtistSectionTitle(R.string.artist_appears_on) }
            items(page.appearsOn.distinctBy { it.uri }, key = { "appears:${it.uri}" }) {
                ArtistWorkCard(it, "artist-appears-${it.id}", onOpen, onPlay, onActions, savedUris)
            }
            if (page.featuringPlaylists.isNotEmpty()) item("featuring-title") { ArtistSectionTitle(R.string.artist_featuring_playlists) }
            items(page.featuringPlaylists.distinctBy { it.uri }, key = { "featuring:${it.uri}" }) {
                ArtistWorkCard(it, "artist-featuring-${it.id}", onOpen, onPlay, onActions, savedUris)
            }
            if (page.discoveredOnPlaylists.isNotEmpty()) item("discovered-title") { ArtistSectionTitle(R.string.artist_discovered_on) }
            items(page.discoveredOnPlaylists.distinctBy { it.uri }, key = { "discovered:${it.uri}" }) {
                ArtistWorkCard(it, "artist-discovered-${it.id}", onOpen, onPlay, onActions, savedUris)
            }
            item("suggested-title") { ArtistSectionTitle(R.string.artist_suggested) }
            items(page.suggestedArtists.distinctBy { it.uri }, key = { "suggested:${it.uri}" }) {
                ArtistWorkCard(it, "artist-suggested-${it.id}", onOpen, onPlay, onActions, savedUris)
            }
        }
    }
}

@Composable
private fun ArtistSectionTitle(label: Int) { Text(stringResource(label), style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }

@Composable
private fun ArtistWorkCard(content: MusicContent, tag: String, onOpen: (MusicContent) -> Unit,
    onPlay: (MusicContent) -> Unit, onActions: (MusicContent) -> Unit, savedUris: Set<String>) {
    Card(onClick = { onOpen(content) }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content.imageUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) }
            Column(Modifier.weight(1f)) {
                Text(content.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (content.subtitle.isNotBlank()) Text(content.subtitle, style = MaterialTheme.typography.bodySmall)
                content.releaseDate?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            ContentAddButton(content, content.uri in savedUris, onActions)
            if (content.kind != ContentKind.ARTIST) IconButton(onClick = { onPlay(content) },
                enabled = content.isPlayable != false, modifier = Modifier.testTag("$tag-play")) {
                Icon(Icons.Default.PlayArrow, stringResource(R.string.play))
            }
        }
    }
}
