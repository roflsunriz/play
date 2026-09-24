package io.github.playmusic.data.cache

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.platform.app.InstrumentationRegistry
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class PlaylistDiskCacheTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(base.cacheDir, "playlist-disk-test-${UUID.randomUUID()}")
    private val isolated = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    }
    private val opened = mutableListOf<PlaylistDiskCache>()

    @After fun cleanup() {
        opened.forEach(PlaylistDiskCache::close)
        check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        check(root.deleteRecursively() || !root.exists())
    }

    @Test fun aCompleteSnapshotSurvivesNewInstancesWithItsOrderAndMetadata(): Unit = runBlocking {
        val cache = cache()
        val initial = cache.read(ACCOUNT)
        assertNull(initial.snapshot)
        val expected = snapshot(entry('a'), entry('b'))
        assertTrue(cache.reconcile(ACCOUNT, initial.generation, expected))
        val saved = cache.read(ACCOUNT)
        assertNotEquals(initial.generation, saved.generation)
        assertEquals(expected, saved.snapshot)
        cache.close()
        assertEquals(saved, cache().read(ACCOUNT))
        assertTrue(databaseFile(ACCOUNT).isFile)
        assertTrue(databaseFile(ACCOUNT).canonicalPath.startsWith(isolated.noBackupFilesDir.canonicalPath + File.separator))
    }

    @Test fun reconciliationWritesOnlyChangedRowsAndChangesOrderSeparately(): Unit = runBlocking {
        val cache = cache()
        save(cache, snapshot(entry('a'), entry('b'), entry('c')))
        database(ACCOUNT) { db ->
            db.execSQL("CREATE TABLE test_writes (operation TEXT, uri TEXT)")
            for ((operation, ref) in listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD")) {
                db.execSQL("CREATE TRIGGER record_$operation AFTER $operation ON playlist_items " +
                    "BEGIN INSERT INTO test_writes VALUES('$operation', $ref.uri); END")
            }
        }
        val changed = entry('b').copy(content = entry('b').content.copy(title = "Changed title"), fingerprint = "new-revision")
        val expected = PlaylistCacheSnapshot(listOf(entry('c'), changed, entry('d')), 200)
        val old = cache.read(ACCOUNT)
        assertTrue(cache.reconcile(ACCOUNT, old.generation, expected))
        assertEquals(expected, cache.read(ACCOUNT).snapshot)
        assertEquals(setOf("DELETE:${uri('a')}", "UPDATE:${uri('b')}", "INSERT:${uri('d')}"), writes())
        database(ACCOUNT) { it.delete("test_writes", null, null) }
        val same = cache.read(ACCOUNT)
        assertTrue(cache.reconcile(ACCOUNT, same.generation, expected.copy(syncedAtMs = 300)))
        assertTrue(writes().isEmpty())
        assertEquals(300L, cache.read(ACCOUNT).snapshot?.syncedAtMs)
    }

    @Test fun failureMidTransactionPreservesItemsOrderTimestampAndGeneration(): Unit = runBlocking {
        val cache = cache()
        save(cache, snapshot(entry('a'), entry('b')))
        val before = cache.read(ACCOUNT)
        database(ACCOUNT) { db ->
            db.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON playlist_items WHEN NEW.uri = '${uri('d')}' " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic cache write failure'); END")
        }
        val changed = entry('a').copy(content = entry('a').content.copy(description = "A change that must roll back"))
        val error = runCatching { cache.reconcile(ACCOUNT, before.generation, snapshot(changed, entry('d'))) }.exceptionOrNull()
        assertTrue(error is SQLiteException)
        assertEquals(before, cache.read(ACCOUNT))
        cache.close()
        assertEquals(before, cache().read(ACCOUNT))
    }

    @Test fun nullAndEmptySnapshotsRemainDistinctAndClearRejectsOlderSyncs(): Unit = runBlocking {
        val cache = cache()
        val initial = cache.read(ACCOUNT)
        val empty = PlaylistCacheSnapshot(emptyList(), 123)
        assertTrue(cache.reconcile(ACCOUNT, initial.generation, empty))
        val loaded = cache.read(ACCOUNT)
        assertEquals(empty, loaded.snapshot)
        cache.clear(ACCOUNT)
        val cleared = cache.read(ACCOUNT)
        assertNull(cleared.snapshot)
        assertNotEquals(loaded.generation, cleared.generation)
        assertFalse(cache.reconcile(ACCOUNT, loaded.generation, snapshot(entry('a'))))
        cache.close()
        assertEquals(cleared, cache().read(ACCOUNT))
    }

    @Test fun mutationsBeforeTheFirstCompleteReadDoNotCreateAnIncompleteLibrary(): Unit = runBlocking {
        val cache = cache()
        val before = cache.read(ACCOUNT)
        cache.updateItem(ACCOUNT, entry('a').content)
        cache.invalidateItem(ACCOUNT, uri('a'))
        cache.removeItem(ACCOUNT, uri('a'))
        val after = cache.read(ACCOUNT)
        assertNull(after.snapshot)
        assertNotEquals(before.generation, after.generation)
        assertFalse(cache.reconcile(ACCOUNT, before.generation, snapshot(entry('b'))))
        assertTrue(cache.reconcile(ACCOUNT, after.generation, snapshot(entry('b'))))
        assertEquals(listOf(uri('b')), cache.read(ACCOUNT).snapshot?.entries?.map { it.content.uri })
    }

    @Test fun localEditsInvalidateFingerprintsAndNewItemsAppearAtTheFront(): Unit = runBlocking {
        val cache = cache()
        save(cache, snapshot(entry('a'), entry('b')))
        val edited = entry('b').content.copy(title = "Saved title")
        cache.updateItem(ACCOUNT, edited)
        cache.updateItem(ACCOUNT, entry('c').content)
        cache.invalidateItem(ACCOUNT, uri('a'))
        val loaded = checkNotNull(cache.read(ACCOUNT).snapshot)
        assertEquals(listOf(uri('c'), uri('a'), uri('b')), loaded.entries.map { it.content.uri })
        assertTrue(loaded.entries.all { it.fingerprint == null })
        assertEquals(entry('a').content, loaded.entries[1].content)
        assertEquals(entry('a').ownerCheckedAtMs, loaded.entries[1].ownerCheckedAtMs)
        assertEquals(edited, loaded.entries.last().content)
        assertEquals(0L, loaded.entries.last().ownerCheckedAtMs)
        assertEquals(100L, loaded.syncedAtMs)
        cache.removeItem(ACCOUNT, uri('b'))
        assertEquals(listOf(uri('c'), uri('a')), cache.read(ACCOUNT).snapshot?.entries?.map { it.content.uri })
    }

    @Test fun accountsUseSeparateOpaquePathsAndClearingOnePreservesTheOther(): Unit = runBlocking {
        val cache = cache()
        val other = "synthetic/../second-account"
        save(cache, snapshot(entry('a')), ACCOUNT)
        save(cache, snapshot(entry('b')), other)
        assertNotEquals(databaseFile(ACCOUNT), databaseFile(other))
        assertTrue(listOf(databaseFile(ACCOUNT), databaseFile(other)).all { it.name.matches(Regex("[0-9a-f]{64}\\.sqlite")) })
        cache.clear(ACCOUNT)
        assertNull(cache.read(ACCOUNT).snapshot)
        assertEquals(snapshot(entry('b')), cache.read(other).snapshot)
    }

    @Test fun corruptDatabaseRecoveryOnlyRecreatesTheAffectedCacheWithANewGeneration(): Unit = runBlocking {
        val cache = cache()
        save(cache, snapshot(entry('a')))
        save(cache, snapshot(entry('b')), "synthetic-other")
        val old = cache.read(ACCOUNT)
        val sentinel = File(isolated.noBackupFilesDir, "unrelated-sentinel").apply { writeText("keep") }
        cache.close()
        databaseFile(ACCOUNT).writeBytes(ByteArray(4_096) { 0x3d })
        val restarted = cache()
        val recovered = restarted.read(ACCOUNT)
        assertNull(recovered.snapshot)
        assertNotEquals(old.generation, recovered.generation)
        assertFalse(restarted.reconcile(ACCOUNT, old.generation, snapshot(entry('a'))))
        assertEquals(snapshot(entry('b')), restarted.read("synthetic-other").snapshot)
        assertEquals("keep", sentinel.readText())
        assertTrue(restarted.reconcile(ACCOUNT, recovered.generation, snapshot(entry('c'))))
    }

    @Test fun unsupportedSchemaAndMalformedRowsAreRegeneratedWithoutReturningPartialData(): Unit = runBlocking {
        val cache = cache()
        for (corrupt in listOf<(SQLiteDatabase) -> Unit>(
            { it.version = 99 },
            { it.execSQL("ALTER TABLE playlist_items ADD COLUMN unexpected TEXT") },
            { it.execSQL("UPDATE playlist_items SET metadata = ?", arrayOf("{\"id\":123}")) },
            { it.execSQL("UPDATE cache_header SET ordered_uris = ?", arrayOf("[\"${uri('c')}\"]")) },
            { it.execSQL("UPDATE playlist_items SET fingerprint = ?", arrayOf("x".repeat(2_100_000))) },
            { it.execSQL("UPDATE cache_header SET ordered_uris = ?", arrayOf("x".repeat(2_100_000))) },
        )) {
            save(cache, snapshot(entry('a'), entry('b')))
            val old = cache.read(ACCOUNT)
            database(ACCOUNT, corrupt)
            val recovered = cache.read(ACCOUNT)
            assertNull(recovered.snapshot)
            assertNotEquals(old.generation, recovered.generation)
            assertFalse(cache.reconcile(ACCOUNT, old.generation, snapshot(entry('a'))))
        }
    }

    @Test fun invalidInputIsRejectedWithoutChangingAnExistingSnapshot(): Unit = runBlocking {
        val cache = cache()
        save(cache, snapshot(entry('a')))
        val before = cache.read(ACCOUNT)
        val invalid = listOf(
            snapshot(entry('a'), entry('a')),
            snapshot(entry('b').copy(content = entry('b').content.copy(kind = ContentKind.TRACK))),
            snapshot(entry('b').copy(content = entry('b').content.copy(uri = uri('c')))),
            snapshot(entry('b').copy(content = entry('b').content.copy(title = "x".repeat(2_049)))),
            snapshot(entry('b').copy(content = entry('b').content.copy(imageUrl = "http://invalid.example/art"))),
            snapshot(entry('b').copy(ownerCheckedAtMs = -1)),
            snapshot(entry('b')).copy(syncedAtMs = -1),
        )
        for (value in invalid) {
            assertTrue(runCatching { cache.reconcile(ACCOUNT, before.generation, value) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, cache.read(ACCOUNT))
        }
    }

    @Test fun twoWritersWithTheSameGenerationCannotOverwriteEachOther(): Unit = runBlocking {
        withTimeout(10_000) {
            val first = cache()
            val second = cache()
            val generation = first.read(ACCOUNT).generation
            val results = listOf(
                async(Dispatchers.IO) { first.reconcile(ACCOUNT, generation, snapshot(entry('a'))) },
                async(Dispatchers.IO) { second.reconcile(ACCOUNT, generation, snapshot(entry('b'))) },
            ).awaitAll()
            assertEquals(1, results.count { it })
            val winner = if (results.first()) entry('a') else entry('b')
            assertEquals(snapshot(winner), first.read(ACCOUNT).snapshot)
        }
    }

    @Test fun closeIsIdempotentAndPreventsFurtherWork(): Unit = runBlocking {
        val cache = cache()
        assertNotNull(cache.read(ACCOUNT))
        cache.close()
        cache.close()
        assertTrue(runCatching { cache.read(ACCOUNT) }.exceptionOrNull() is IllegalStateException)
    }

    private fun cache() = PlaylistDiskCache(isolated).also(opened::add)
    private suspend fun save(cache: PlaylistDiskCache, value: PlaylistCacheSnapshot, account: String = ACCOUNT) {
        assertTrue(cache.reconcile(account, cache.read(account).generation, value))
    }
    private fun databaseFile(account: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(isolated.noBackupFilesDir, "playlist-library/$hash.sqlite")
    }
    private fun database(account: String, block: (SQLiteDatabase) -> Unit) {
        val database = SQLiteDatabase.openDatabase(databaseFile(account).absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try { block(database) } finally { database.close() }
    }
    private fun writes(): Set<String> {
        val result = mutableSetOf<String>()
        database(ACCOUNT) { db ->
            db.rawQuery("SELECT operation, uri FROM test_writes", null).use { cursor ->
                while (cursor.moveToNext()) result += "${cursor.getString(0)}:${cursor.getString(1)}"
            }
        }
        return result
    }
    private fun uri(letter: Char) = "spotify:playlist:${letter.toString().repeat(22)}"
    private fun entry(letter: Char) = PlaylistCacheEntry(
        MusicContent(id = letter.toString().repeat(22), uri = uri(letter), title = "Playlist $letter", subtitle = "Display name",
            imageUrl = "https://images.example/$letter.jpg", kind = ContentKind.PLAYLIST,
            ownerName = "Display name", ownerUsername = "synthetic-owner", description = "Description $letter", trackCount = 42,
            releaseDate = null), "revision-$letter", 90)
    private fun snapshot(vararg entries: PlaylistCacheEntry) = PlaylistCacheSnapshot(entries.toList(), 100)
    private companion object { const val ACCOUNT = "synthetic-cache-account" }
}
