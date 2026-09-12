package io.github.playmusic.data.api

import io.github.playmusic.data.auth.AppConstants
import io.github.playmusic.data.auth.ProtoWire

data class AccountContext(val country: String, val catalogue: String) {
    init {
        require(country.matches(Regex("[A-Z]{2}"))) { "Account country is unavailable" }
        require(catalogue.isNotBlank() && catalogue.length <= 64) { "Account catalogue is unavailable" }
    }
}

/** Wire contract confirmed in the reference APK's UcsRequest/UcsResponseWrapper. */
object AccountContextProto {
    fun request(): ByteArray = ProtoWire.fieldBytes(1,
        ProtoWire.fieldString(1, "com.spotify.music") + ProtoWire.fieldString(2, AppConstants.CLIENT_VERSION)) +
        ProtoWire.fieldBytes(3, ByteArray(0))

    fun parse(bytes: ByteArray): AccountContext {
        check(message(bytes, 2) == null) { "Account attributes request failed" }
        val success = checkNotNull(message(bytes, 1)) { "Account attributes response is missing" }
        check(message(success, 4) == null) { "Account attributes request failed" }
        val attributes = checkNotNull(message(success, 3)) { "Account attributes response is missing" }
        var country: String? = null
        var catalogue: String? = null
        val reader = ProtoWire.Reader(attributes)
        while (reader.hasNext()) {
            val tag = reader.readTag()
            if (reader.fieldNumber(tag) != 1) {
                reader.skip(reader.wireType(tag))
                continue
            }
            val entry = reader.readBytes()
            val key = message(entry, 1)?.toString(Charsets.UTF_8)
            if (key != "country_code" && key != "catalogue") continue
            val value = message(entry, 2)?.let { message(it, 4) }?.toString(Charsets.UTF_8)
            if (key == "country_code") country = value else catalogue = value
        }
        return AccountContext(country.orEmpty(), catalogue.orEmpty())
    }

    private fun message(bytes: ByteArray, number: Int): ByteArray? {
        val reader = ProtoWire.Reader(bytes)
        while (reader.hasNext()) {
            val tag = reader.readTag()
            if (reader.fieldNumber(tag) == number && reader.wireType(tag) == 2) return reader.readBytes()
            reader.skip(reader.wireType(tag))
        }
        return null
    }
}
