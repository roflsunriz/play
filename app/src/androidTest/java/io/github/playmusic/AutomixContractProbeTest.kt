package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.CatalogApiClient
import io.github.playmusic.data.api.SpClientProto
import io.github.playmusic.data.api.SpotifyApiClient
import io.github.playmusic.data.auth.ProtoWire
import io.github.playmusic.data.model.SearchFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in read-only protocol probe. Stores metadata only; no audio, DRM material, or headers. */
class AutomixContractProbeTest {
    @Test fun inspectPlaylistAndCuepointContracts(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveAutomixProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        val api = SpotifyApiClient(app.sessionManager)
        val catalog = CatalogApiClient(app.sessionManager)
        val playlists = catalog.search(arguments.getString("automixQuery") ?: "Dance Party", SearchFilter.PLAYLISTS).take(4)
        check(playlists.isNotEmpty())
        val directory = context.filesDir.resolve("automix-probe").apply { mkdirs() }
        for ((index, playlist) in playlists.withIndex()) {
            val reply = api.getProto("/playlist/v2/playlist/${playlist.id}", mapOf("from" to "0", "length" to "12",
                "decorate" to "attributes,revision,length"))
            directory.resolve("playlist-$index.pb").writeBytes(reply.bodyBytes)
            val source = SpClientProto.parsePlaylist(reply.bodyBytes)
            Log.i("PlayAutomixProbe", "playlist=$index listKeys=${source.formatAttributes.keys} itemKeys=${source.items.flatMap { it.formatAttributes.keys }.distinct()}")
            val queries = listOf(playlist.uri to 27) + source.items.take(6).map { it.uri to 28 }
            val body = queries.fold(byteArrayOf()) { bytes, (uri, kind) ->
                bytes + ProtoWire.fieldBytes(2, ProtoWire.fieldString(1, uri) + ProtoWire.fieldBytes(2, ProtoWire.fieldVarint(1, kind)))
            }
            val extensions = api.postProto("/extended-metadata/v0/extended-metadata", body)
            directory.resolve("extensions-$index.pb").writeBytes(extensions.bodyBytes)
            Log.i("PlayAutomixProbe", "playlist=$index extensionsStatus=${extensions.status} bytes=${extensions.bodyBytes.size}")
        }
    }
}
