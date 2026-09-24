package io.github.playmusic.data.cache

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import io.github.playmusic.data.model.ContentKind
import io.github.playmusic.data.model.MusicContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

data class PlaylistCacheEntry(val content: MusicContent, val fingerprint: String?, val ownerCheckedAtMs: Long = 0)
data class PlaylistCacheSnapshot(val entries: List<PlaylistCacheEntry>, val syncedAtMs: Long)
data class PlaylistCacheRead(val generation: Long, val snapshot: PlaylistCacheSnapshot?)

/** Private metadata only. Complete snapshots survive process death; mutations invalidate older synchronizations. */
class PlaylistDiskCache(private val context: Context) {
    private val mutex = Mutex()
    private val lifecycle = Any()
    private var closed = false

    suspend fun read(account: String): PlaylistCacheRead = access(account) { db -> readState(db).read }

    suspend fun reconcile(account: String, expectedGeneration: Long, snapshot: PlaylistCacheSnapshot): Boolean {
        require(expectedGeneration >= 0) { "Invalid playlist cache generation" }
        val incoming = withContext(Dispatchers.IO) { prepare(snapshot) }
        return access(account) { db ->
            val before = readState(db)
            if (before.read.generation != expectedGeneration) return@access false
            before.rows.keys.filterNot(incoming::containsKey).forEach { uri ->
                db.delete(ITEMS, "uri = ?", arrayOf(uri))
            }
            incoming.forEach { (uri, row) -> writeRow(db, before.rows[uri], row) }
            writeHeader(db, nextGeneration(before.read.generation), true, snapshot.syncedAtMs, incoming.keys.toList())
            true
        }
    }

    suspend fun updateItem(account: String, content: MusicContent) {
        val row = withContext(Dispatchers.IO) { prepareEntry(PlaylistCacheEntry(content, null)) }
        access(account) { db ->
            val before = readState(db)
            val snapshot = before.read.snapshot
            if (snapshot != null) {
                val updated = LinkedHashMap<String, Row>().apply {
                    if (content.uri !in before.rows) put(content.uri, row)
                    putAll(before.rows)
                    put(content.uri, row)
                }
                validateSize(updated.values)
                writeRow(db, before.rows[content.uri], row)
                writeHeader(db, nextGeneration(before.read.generation), true, snapshot.syncedAtMs, updated.keys.toList())
            } else writeHeader(db, nextGeneration(before.read.generation), false, 0, emptyList())
        }
    }

    suspend fun invalidateItem(account: String, uri: String) {
        validateUri(uri)
        access(account) { db ->
            val before = readState(db)
            before.rows[uri]?.let { old ->
                writeRow(db, old, old.copy(entry = old.entry.copy(fingerprint = null)))
            }
            val snapshot = before.read.snapshot
            writeHeader(db, nextGeneration(before.read.generation), snapshot != null,
                snapshot?.syncedAtMs ?: 0, before.rows.keys.toList())
        }
    }

    suspend fun removeItem(account: String, uri: String) {
        validateUri(uri)
        access(account) { db ->
            val before = readState(db)
            db.delete(ITEMS, "uri = ?", arrayOf(uri))
            val snapshot = before.read.snapshot
            writeHeader(db, nextGeneration(before.read.generation), snapshot != null,
                snapshot?.syncedAtMs ?: 0, before.rows.keys.filterNot { it == uri })
        }
    }

    suspend fun clear(account: String) {
        access(account) { db ->
            val before = readState(db)
            db.delete(ITEMS, null, null)
            writeHeader(db, nextGeneration(before.read.generation), false, 0, emptyList())
        }
    }

    /** Operations own and close their database handles, so closing also waits for an active transaction. */
    fun close() = synchronized(lifecycle) { closed = true }

    private suspend fun <T> access(account: String, block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        require(account.isNotBlank() && account.length <= 512 && '\u0000' !in account) { "Invalid playlist cache account" }
        val requestContext = currentCoroutineContext()
        mutex.withLock {
            synchronized(lifecycle) {
                check(!closed) { "Playlist cache is closed" }
                requestContext.ensureActive()
                val directory = File(context.noBackupFilesDir, DIRECTORY).canonicalFile
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create playlist cache directory")
                val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val file = File(directory, "$hash.sqlite")
                withRecovery(file) { db ->
                    db.beginTransaction()
                    try {
                        val result = block(db)
                        requestContext.ensureActive()
                        db.setTransactionSuccessful()
                        result
                    } finally { db.endTransaction() }
                }
            }
        }
    }

    private fun <T> withRecovery(file: File, block: (SQLiteDatabase) -> T): T {
        repeat(2) { attempt ->
            val helper = Helper(context, file)
            try {
                try { return block(helper.writableDatabase) } finally { helper.close() }
            } catch (error: Exception) {
                if (attempt != 0 || (error !is InvalidCache && error !is SQLiteDatabaseCorruptException)) throw error
                // The exact account cache and its SQLite sidecars are the only recovery targets.
                check(file.canonicalFile.parentFile == File(context.noBackupFilesDir, DIRECTORY).canonicalFile)
                if (!SQLiteDatabase.deleteDatabase(file) && file.exists()) throw IOException("Could not reset playlist cache")
            }
        }
        error("Playlist cache recovery did not finish")
    }

    private fun readState(db: SQLiteDatabase): State {
        // Reject oversized or wrongly typed stored values before a CursorWindow materializes them.
        val validHeader = "typeof(id) = 'integer' AND id = 1 AND typeof(generation) = 'integer' AND generation > 0 " +
            "AND typeof(complete) = 'integer' AND complete IN (0, 1) AND typeof(synced_at) = 'integer' AND synced_at >= 0 " +
            "AND typeof(ordered_uris) = 'text' AND length(ordered_uris) <= $MAX_ORDER_CHARACTERS"
        db.rawQuery("SELECT count(*), coalesce(sum(CASE WHEN $validHeader THEN 1 ELSE 0 END), 0) FROM $HEADER", null).use {
            if (!it.moveToFirst() || it.getLong(0) != 1L || it.getLong(1) != 1L) invalid()
        }
        val header = db.query(HEADER, arrayOf("id", "generation", "complete", "synced_at", "ordered_uris"),
            null, null, null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) invalid()
            if (cursor.integer(0) != 1L) invalid()
            val generation = cursor.integer(1).also { if (it <= 0) invalid() }
            val complete = cursor.integer(2).also { if (it !in 0L..1L) invalid() } == 1L
            val syncedAt = cursor.integer(3).also { if (it < 0) invalid() }
            val serializedOrder = cursor.string(4).also { if (it.length > MAX_ORDER_CHARACTERS) invalid() }
            if (cursor.moveToNext()) invalid()
            Header(generation, complete, syncedAt, parseOrder(serializedOrder))
        }
        val validItem = "typeof(uri) = 'text' AND length(uri) <= 64 AND typeof(metadata) = 'text' " +
            "AND (fingerprint IS NULL OR (typeof(fingerprint) = 'text' AND length(fingerprint) <= 512)) " +
            "AND typeof(owner_checked_at) = 'integer' AND owner_checked_at >= 0"
        db.rawQuery("SELECT count(*), coalesce(sum(length(CAST(metadata AS BLOB))), 0), " +
            "coalesce(max(length(CAST(metadata AS BLOB))), 0), " +
            "coalesce(sum(CASE WHEN $validItem THEN 0 ELSE 1 END), 0) FROM $ITEMS", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getLong(0) > MAX_ENTRIES ||
                cursor.getLong(1) > MAX_SNAPSHOT_BYTES || cursor.getLong(2) > MAX_ENTRY_BYTES || cursor.getLong(3) != 0L) invalid()
        }
        val rows = mutableMapOf<String, Row>()
        db.query(ITEMS, arrayOf("uri", "metadata", "fingerprint", "owner_checked_at"),
            null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = cursor.string(0)
                val serialized = cursor.string(1)
                val fingerprint = if (cursor.isNull(2)) null else cursor.string(2)
                val checkedAt = cursor.integer(3)
                val content = decode(serialized)
                if (content.uri != uri || checkedAt < 0) invalid()
                try { validateFingerprint(fingerprint) } catch (_: IllegalArgumentException) { invalid() }
                rows[uri] = Row(PlaylistCacheEntry(content, fingerprint, checkedAt), serialized)
            }
        }
        if (rows.keys != header.order.toSet()) invalid()
        if (!header.complete && (rows.isNotEmpty() || header.syncedAt != 0L)) invalid()
        val ordered = LinkedHashMap<String, Row>().apply { header.order.forEach { put(it, rows.getValue(it)) } }
        val snapshot = if (header.complete) PlaylistCacheSnapshot(ordered.values.map { it.entry }, header.syncedAt) else null
        return State(PlaylistCacheRead(header.generation, snapshot), ordered)
    }

    private fun writeRow(db: SQLiteDatabase, before: Row?, after: Row) {
        if (before?.entry == after.entry) return
        val values = ContentValues().apply {
            put("uri", after.entry.content.uri)
            put("metadata", after.serialized)
            put("fingerprint", after.entry.fingerprint)
            put("owner_checked_at", after.entry.ownerCheckedAtMs)
        }
        if (before == null) db.insertOrThrow(ITEMS, null, values)
        else check(db.update(ITEMS, values, "uri = ?", arrayOf(after.entry.content.uri)) == 1) { "Playlist cache item disappeared" }
    }

    private fun writeHeader(db: SQLiteDatabase, generation: Long, complete: Boolean, syncedAt: Long, order: List<String>) {
        val values = ContentValues().apply {
            put("generation", generation)
            put("complete", if (complete) 1 else 0)
            put("synced_at", syncedAt)
            put("ordered_uris", JSONArray(order).toString())
        }
        check(db.update(HEADER, values, "id = 1", null) == 1) { "Playlist cache header disappeared" }
    }

    private fun prepare(snapshot: PlaylistCacheSnapshot): LinkedHashMap<String, Row> {
        require(snapshot.syncedAtMs >= 0 && snapshot.entries.size <= MAX_ENTRIES) { "Invalid playlist cache snapshot" }
        val rows = LinkedHashMap<String, Row>()
        snapshot.entries.forEach { entry ->
            require(rows.put(entry.content.uri, prepareEntry(entry)) == null) { "Duplicate playlist cache entry" }
        }
        validateSize(rows.values)
        return rows
    }

    private fun prepareEntry(entry: PlaylistCacheEntry): Row {
        require(entry.ownerCheckedAtMs >= 0) { "Invalid playlist owner check time" }
        validateFingerprint(entry.fingerprint)
        validateContent(entry.content)
        val serialized = encode(entry.content)
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_BYTES) { "Playlist cache entry is too large" }
        return Row(entry, serialized)
    }

    private fun validateSize(rows: Collection<Row>) {
        require(rows.size <= MAX_ENTRIES && rows.sumOf { it.serialized.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_SNAPSHOT_BYTES) {
            "Playlist cache snapshot is too large"
        }
    }

    private fun validateContent(content: MusicContent) {
        require(content.kind == ContentKind.PLAYLIST && content.uri == "spotify:playlist:${content.id}") { "Invalid playlist cache content" }
        validateUri(content.uri)
        require(content.title.length <= 2_048 && content.subtitle.length <= 4_096 && content.durationMs >= 0 &&
            (content.trackCount == null || content.trackCount >= 0)) { "Invalid playlist metadata" }
        require(listOf(content.ownerName, content.ownerUsername, content.albumTitle).all { it == null || it.length <= 4_096 } &&
            (content.description == null || content.description.length <= 32_768) &&
            (content.releaseDate == null || content.releaseDate.length <= 64) &&
            (content.albumUri == null || content.albumUri.length <= 256)) { "Playlist metadata is too large" }
        content.imageUrl?.let { value ->
            require(value.length <= 8_192 && runCatching { java.net.URI(value).let {
                it.scheme == "https" && it.host != null && it.userInfo == null
            } }.getOrDefault(false)) { "Invalid playlist artwork URL" }
        }
    }

    private fun encode(content: MusicContent): String = JSONObject().apply {
        put("id", content.id); put("uri", content.uri); put("kind", content.kind.name)
        put("title", content.title); put("subtitle", content.subtitle)
        put("imageUrl", content.imageUrl ?: JSONObject.NULL); put("durationMs", content.durationMs)
        put("albumUri", content.albumUri ?: JSONObject.NULL); put("albumTitle", content.albumTitle ?: JSONObject.NULL)
        put("isPlayable", content.isPlayable ?: JSONObject.NULL)
        put("ownerName", content.ownerName ?: JSONObject.NULL); put("ownerUsername", content.ownerUsername ?: JSONObject.NULL)
        put("description", content.description ?: JSONObject.NULL); put("trackCount", content.trackCount ?: JSONObject.NULL)
        put("releaseDate", content.releaseDate ?: JSONObject.NULL)
    }.toString()

    private fun decode(serialized: String): MusicContent = try {
        val json = JSONObject(serialized)
        if (json.keys().asSequence().toSet() != CONTENT_FIELDS || json.requiredString("kind") != "PLAYLIST") invalid()
        MusicContent(id = json.requiredString("id"), uri = json.requiredString("uri"),
            title = json.requiredString("title"), subtitle = json.requiredString("subtitle"),
            imageUrl = json.nullableString("imageUrl"), kind = ContentKind.PLAYLIST,
            durationMs = json.integer("durationMs"), albumUri = json.nullableString("albumUri"),
            albumTitle = json.nullableString("albumTitle"),
            isPlayable = if (json.isNull("isPlayable")) null else (json.get("isPlayable") as? Boolean ?: invalid()),
            ownerName = json.nullableString("ownerName"), ownerUsername = json.nullableString("ownerUsername"),
            description = json.nullableString("description"),
            trackCount = if (json.isNull("trackCount")) null else json.integer("trackCount")
                .also { if (it !in 0..Int.MAX_VALUE.toLong()) invalid() }.toInt(),
            releaseDate = json.nullableString("releaseDate")).also(::validateContent)
    } catch (_: JSONException) { invalid() } catch (_: IllegalArgumentException) { invalid() }

    private fun parseOrder(serialized: String): List<String> = try {
        val json = JSONArray(serialized)
        if (json.length() > MAX_ENTRIES) invalid()
        (0 until json.length()).map { index -> (json.get(index) as? String ?: invalid()).also(::validateUri) }
            .also { if (it.size != it.toSet().size) invalid() }
    } catch (_: JSONException) { invalid() } catch (_: IllegalArgumentException) { invalid() }

    private class Helper(context: Context, file: File) : SQLiteOpenHelper(context, file.absolutePath, null, SCHEMA,
        DatabaseErrorHandler { /* The caller closes the handle and resets only this dedicated file. */ }) {
        override fun onCreate(db: SQLiteDatabase) {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'", null).use {
                if (it.moveToFirst()) invalid()
            }
            db.execSQL("CREATE TABLE $HEADER (id INTEGER PRIMARY KEY CHECK(id = 1), generation INTEGER NOT NULL, " +
                "complete INTEGER NOT NULL, synced_at INTEGER NOT NULL, ordered_uris TEXT NOT NULL)")
            db.execSQL("CREATE TABLE $ITEMS (uri TEXT PRIMARY KEY NOT NULL, metadata TEXT NOT NULL, " +
                "fingerprint TEXT, owner_checked_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO $HEADER VALUES(1, ?, 0, 0, '[]')", arrayOf(newGeneration()))
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = invalid()
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = invalid()
        override fun onOpen(db: SQLiteDatabase) {
            validateTable(db, HEADER, listOf(Column("id", "INTEGER", 0, 1), Column("generation", "INTEGER", 1, 0),
                Column("complete", "INTEGER", 1, 0), Column("synced_at", "INTEGER", 1, 0), Column("ordered_uris", "TEXT", 1, 0)))
            validateTable(db, ITEMS, listOf(Column("uri", "TEXT", 1, 1), Column("metadata", "TEXT", 1, 0),
                Column("fingerprint", "TEXT", 0, 0), Column("owner_checked_at", "INTEGER", 1, 0)))
        }
        private fun validateTable(db: SQLiteDatabase, table: String, expected: List<Column>) {
            db.rawQuery("SELECT type FROM sqlite_master WHERE name = ?", arrayOf(table)).use {
                if (!it.moveToFirst() || it.getString(0) != "table" || it.moveToNext()) invalid()
            }
            val actual = mutableListOf<Column>()
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                while (cursor.moveToNext()) actual += Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getInt(5))
            }
            if (actual != expected) invalid()
        }
    }

    private data class Column(val name: String, val type: String, val notNull: Int, val primaryKey: Int)
    private data class Header(val generation: Long, val complete: Boolean, val syncedAt: Long, val order: List<String>)
    private data class Row(val entry: PlaylistCacheEntry, val serialized: String)
    private data class State(val read: PlaylistCacheRead, val rows: LinkedHashMap<String, Row>)
    private class InvalidCache : IOException("Playlist cache data is invalid")

    private companion object {
        const val DIRECTORY = "playlist-library"
        const val HEADER = "cache_header"
        const val ITEMS = "playlist_items"
        const val SCHEMA = 1
        // Metadata only: bounds keep each row below CursorWindow limits and prevent unbounded snapshots.
        const val MAX_ENTRIES = 10_000
        const val MAX_ENTRY_BYTES = 65_536
        const val MAX_SNAPSHOT_BYTES = 16L * 1_024 * 1_024
        const val MAX_ORDER_CHARACTERS = MAX_ENTRIES * 64
        val PLAYLIST_URI = Regex("spotify:playlist:[A-Za-z0-9]{22}")
        val CONTENT_FIELDS = setOf("id", "uri", "kind", "title", "subtitle", "imageUrl", "durationMs", "albumUri", "albumTitle",
            "isPlayable", "ownerName", "ownerUsername", "description", "trackCount", "releaseDate")
        fun validateUri(uri: String) = require(PLAYLIST_URI.matches(uri)) { "Invalid playlist cache URI" }
        fun validateFingerprint(value: String?) = require(value == null || (value.isNotEmpty() && value.length <= 512)) {
            "Invalid playlist cache fingerprint"
        }
        // A new epoch prevents an outstanding synchronization from matching a recreated cache's old generation.
        fun newGeneration(): Long = (SecureRandom().nextLong().ushr(1) % (Long.MAX_VALUE / 2)).coerceAtLeast(1)
        fun nextGeneration(previous: Long): Long = if (previous == Long.MAX_VALUE) newGeneration() else previous + 1
        fun invalid(): Nothing = throw InvalidCache()
        fun Cursor.integer(index: Int): Long = if (getType(index) == Cursor.FIELD_TYPE_INTEGER) getLong(index) else invalid()
        fun Cursor.string(index: Int): String = if (getType(index) == Cursor.FIELD_TYPE_STRING) getString(index) else invalid()
        fun JSONObject.requiredString(key: String): String = get(key) as? String ?: invalid()
        fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else requiredString(key)
        fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> invalid()
        }
    }
}
