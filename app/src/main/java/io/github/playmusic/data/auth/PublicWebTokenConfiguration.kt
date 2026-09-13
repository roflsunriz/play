package io.github.playmusic.data.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Reads the published client configuration without evaluating JavaScript or retaining its source. */
class PublicWebTokenConfiguration private constructor(private val key: ByteArray, val version: Int) : AutoCloseable {
    private var closed = false

    enum class Reason(val wireValue: String) { INITIAL("init"), TRANSPORT("transport") }

    @Synchronized
    fun query(reason: Reason, pageKind: PlaybackAuthorizationClient.PageKind, nowMs: Long, serverTimeSeconds: Long?):
        PlaybackAuthorizationClient.TokenQuery {
        if (closed || nowMs < 0 || serverTimeSeconds != null && serverTimeSeconds !in 1..Long.MAX_VALUE / 1000) fail()
        val product = when (pageKind) {
            PlaybackAuthorizationClient.PageKind.DESKTOP -> "web-player"
            PlaybackAuthorizationClient.PageKind.MOBILE -> "mobile-web-player"
            PlaybackAuthorizationClient.PageKind.UNKNOWN -> fail()
        }
        return PlaybackAuthorizationClient.TokenQuery(reason.wireValue, product, totp(key, nowMs),
            serverTimeSeconds?.let { totp(key, it * 1000) } ?: "unavailable", version.toString())
    }

    @Synchronized
    override fun close() { key.fill(0); closed = true }

    override fun toString(): String = "PublicWebTokenConfiguration(version=$version)"

    class ConfigurationException : Exception("Unsupported public token configuration")

    companion object {
        private const val IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*"
        private val ARRAY_START = Regex("(?:let|const|var)\\s+($IDENTIFIER)\\s*=\\s*(?=\\[\\s*\\{\\s*secret\\s*:)")
        private val PATH = "$IDENTIFIER(?:\\.$IDENTIFIER)*"
        // Require the inspected transformation, including variable relationships and the first-row selection.
        private val TRANSFORM = Regex(
            "(?<item>$IDENTIFIER)=>\\{var (?<seed>$IDENTIFIER);let (?<values>$IDENTIFIER),(?<hex>$IDENTIFIER);" +
                "return\\{secret:\\(\\k<seed>=\\k<item>\\.secret,\\k<values>=\\[\\]," +
                "\\k<values>=[\"']string[\"']==typeof \\k<seed>\\?\\k<seed>\\.split\\([\"'][\"']\\)\\.map\\(" +
                "\\((?<character>$IDENTIFIER),(?<index>$IDENTIFIER)\\)=>\\k<character>\\.charCodeAt\\(0\\)\\^\\k<index>%33\\+9\\):" +
                "\\k<seed>\\.map\\(\\((?<number>$IDENTIFIER),(?<position>$IDENTIFIER)\\)=>\\k<number>\\^\\k<position>%33\\+9\\)," +
                "\\k<hex>=$PATH\\.from\\(\\k<values>\\.join\\([\"'][\"']\\),[\"']utf8[\"']\\)\\.toString\\([\"']hex[\"']\\)," +
                "$PATH\\.fromHex\\(\\k<hex>\\)\\),version:\\k<item>\\.version\\}\\}")

        fun parse(script: String): PublicWebTokenConfiguration {
            if (script.length !in 1..8_388_608) fail()
            val starts = ARRAY_START.findAll(script).take(2).toList()
            if (starts.size != 1) fail()
            val start = starts.single()
            val name = start.groupValues[1]
            val cursor = Cursor(script, start.range.last + 1)
            val entries = cursor.entries()
            cursor.expect('.')
            if (cursor.identifier() != "map") fail()
            val transform = normalize(cursor.parenthesized())
            if (!TRANSFORM.matches(transform)) fail()
            cursor.expect('[')
            if (cursor.number() != 0) fail()
            cursor.expect(']')
            val tail = script.substring(cursor.position, minOf(script.length, cursor.position + 10_000))
            val safeName = Regex.escape(name)
            val options = Regex("\\(\\{\\s*period\\s*:\\s*30\\s*,\\s*algorithm\\s*:\\s*[\"']SHA1[\"']\\s*," +
                "\\s*digits\\s*:\\s*6\\s*,\\s*secret\\s*:\\s*$safeName\\.secret\\s*\\}\\)")
            if (options.findAll(tail).count() != 1 ||
                !Regex("totpVer\\s*:\\s*String\\($safeName\\.version\\)").containsMatchIn(tail) ||
                !tail.contains("\"/api/token\"") && !tail.contains("'/api/token'")) fail()
            val selected = entries.first()
            val transformed = selected.seed.mapIndexed { index, value -> value xor (index % 33 + 9) }.joinToString("")
            return PublicWebTokenConfiguration(transformed.toByteArray(Charsets.UTF_8), selected.version)
        }

        /** RFC 6238 SHA-1 with the six digits and thirty-second period used by the inspected client. */
        internal fun totp(key: ByteArray, timestampMs: Long): String {
            if (key.isEmpty() || timestampMs < 0) fail()
            var counter = timestampMs / 30_000
            val message = ByteArray(8)
            for (index in 7 downTo 0) { message[index] = counter.toByte(); counter = counter ushr 8 }
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val digest = mac.doFinal(message)
            val offset = digest.last().toInt() and 15
            val number = ((digest[offset].toInt() and 127) shl 24) or
                ((digest[offset + 1].toInt() and 255) shl 16) or
                ((digest[offset + 2].toInt() and 255) shl 8) or (digest[offset + 3].toInt() and 255)
            return (number % 1_000_000).toString().padStart(6, '0')
        }

        private fun normalize(source: String): String {
            val result = StringBuilder()
            var position = 0
            while (position < source.length) {
                val value = source[position]
                if (value == '\'' || value == '"') {
                    val cursor = Cursor(source, position)
                    cursor.string()
                    result.append(source.substring(position, cursor.position))
                    position = cursor.position
                } else if (value.isWhitespace()) {
                    while (position < source.length && source[position].isWhitespace()) position++
                    if (result.lastOrNull()?.let { it.isLetterOrDigit() || it in "_$" } == true &&
                        position < source.length && (source[position].isLetterOrDigit() || source[position] in "_$")) result.append(' ')
                } else { result.append(value); position++ }
            }
            return result.toString()
        }

        private fun fail(): Nothing = throw ConfigurationException()
    }

    private class Entry(val seed: List<Int>, val version: Int)

    /** Limited literal reader: no expression evaluation, comments, calls, or identifiers as seed values. */
    private class Cursor(private val source: String, var position: Int) {
        private val start = position
        private fun whitespace() {
            if (position - start > 16_384) fail()
            while (position < source.length && source[position].isWhitespace()) position++
        }
        private fun peek(): Char { whitespace(); return source.getOrNull(position) ?: fail() }
        fun expect(value: Char) { if (peek() != value) fail(); position++ }
        private fun consume(value: Char): Boolean = if (peek() == value) { position++; true } else false
        fun identifier(): String {
            whitespace()
            val match = Regex(IDENTIFIER).find(source, position)?.takeIf { it.range.first == position } ?: fail()
            position = match.range.last + 1
            return match.value
        }
        fun number(): Int {
            whitespace()
            val first = position
            while (source.getOrNull(position)?.let { it in '0'..'9' } == true) position++
            val text = source.substring(first, position)
            if (text.isEmpty() || text.length > 10 || text.length > 1 && text.startsWith('0')) fail()
            return text.toIntOrNull() ?: fail()
        }
        fun string(): String {
            val quote = peek()
            if (quote != '\'' && quote != '"') fail()
            position++
            val result = StringBuilder()
            while (position < source.length && result.length <= 1024) {
                val next = source[position++]
                if (next == quote) return result.toString()
                if (next == '\n' || next == '\r') fail()
                if (next != '\\') result.append(next) else {
                    val escaped = source.getOrNull(position++) ?: fail()
                    result.append(when (escaped) {
                        '\\', '\'', '"', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'v' -> '\u000B'
                        'x', 'u' -> {
                            val count = if (escaped == 'x') 2 else 4
                            if (position + count > source.length) fail()
                            val hex = source.substring(position, position + count)
                            if (hex.any { it !in "0123456789abcdefABCDEF" }) fail()
                            position += count
                            hex.toInt(16).toChar()
                        }
                        else -> fail()
                    })
                }
            }
            fail()
        }
        private fun seed(): List<Int> {
            val values = if (peek() == '[') {
                expect('[')
                val result = mutableListOf<Int>()
                while (peek() != ']') {
                    result.add(number())
                    if (result.size > 256) fail()
                    if (!consume(',')) break
                }
                expect(']')
                result
            } else string().map(Char::code)
            if (values.isEmpty() || values.size > 256 || values.any { it !in 0..255 }) fail()
            return values
        }
        fun entries(): List<Entry> {
            expect('[')
            val result = mutableListOf<Entry>()
            while (peek() != ']') {
                expect('{')
                if (identifier() != "secret") fail()
                expect(':')
                val seed = seed()
                expect(',')
                if (identifier() != "version") fail()
                expect(':')
                val version = number()
                if (version <= 0 || result.any { it.version == version }) fail()
                expect('}')
                result.add(Entry(seed, version))
                if (result.size > 16) fail()
                if (!consume(',')) break
            }
            expect(']')
            if (result.isEmpty()) fail()
            return result
        }
        fun parenthesized(): String {
            expect('(')
            val first = position
            var depth = 1
            while (position < source.length && position - first <= 4096) {
                when (source[position]) {
                    '\'', '"' -> string()
                    '(' -> { depth++; position++ }
                    ')' -> { depth--; if (depth == 0) return source.substring(first, position++) else position++ }
                    else -> position++
                }
            }
            fail()
        }
    }
}
