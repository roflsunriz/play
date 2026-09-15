package io.github.playmusic

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.api.CatalogApiClient
import io.github.playmusic.data.model.SearchFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only diagnostic: logs schema keys/types and text lengths, never biography text or credentials. */
class ArtistBiographyProbeTest {
    @Test fun inspectLocalizedAndDefaultBiographyShapes(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveArtistBiographyProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as PlayApplication).container
        check(app.sessionStore.loadSession() != null)
        val catalog = CatalogApiClient(app.sessionManager)
        val fixtures = listOf("Queen" to "1dfeR4HaWDbWqFHLkxsg1d", "Nirvana" to "6olE6TJLqED3rqDCT0FyPh")
        for ((index, fixture) in fixtures.withIndex()) {
            val matches = catalog.search(fixture.first, SearchFilter.ARTISTS)
            val artist = matches.first { it.id == fixture.second }
            Log.i("PlayArtistBiography", "artistIndex=$index canonicalWasFirstExactName=" +
                (matches.firstOrNull { it.title.equals(fixture.first, ignoreCase = true) }?.id == fixture.second))
            for (locale in listOf("intl-ja", "")) {
                val raw = catalog.artistOverview(artist.uri, locale)
                val profile = raw.getJSONObject("profile")
                val biography = profile.opt("biography")
                val objectValue = biography as? JSONObject
                val text = objectValue?.opt("text")
                Log.i("PlayArtistBiography", "artistIndex=$index locale=${locale.ifEmpty { "default" }} " +
                    "profileKeys=${keys(profile)} biographyPresent=${profile.has("biography")} " +
                    "biographyType=${type(biography)} biographyKeys=${objectValue?.let(::keys).orEmpty()} " +
                    "textType=${type(text)} textChars=${(text as? String)?.length ?: -1}")
            }
        }
    }

    private fun keys(value: JSONObject) = value.keys().asSequence().filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,79}")) }
        .sorted().joinToString(",")
    private fun type(value: Any?): String = when (value) {
        null -> "missing"
        JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is String -> "string"
        is Number -> "number"
        is Boolean -> "boolean"
        else -> "other"
    }
}
