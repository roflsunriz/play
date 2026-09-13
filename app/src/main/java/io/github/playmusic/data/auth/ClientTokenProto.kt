package io.github.playmusic.data.auth

import io.github.playmusic.data.auth.ProtoWire.Reader
import java.net.URI
import java.util.Locale

internal fun ByteArray.toUppercaseHex(): String = joinToString("") { "%02X".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

data class ClientTokenRequest(
    val clientId: String,
    val clientVersion: String,
    val deviceId: String,
    val platformData: ClientTokenPlatformData = NativeAndroidData.current(),
) {
    init {
        require(clientId.isNotBlank() && clientId.length <= 512)
        require(clientVersion.isNotBlank() && clientVersion.length <= 256)
        require(deviceId.isNotBlank() && deviceId.length <= 1024)
    }

    fun encode(): ByteArray {
        val connectivity = java.io.ByteArrayOutputStream().apply {
            val platform = java.io.ByteArrayOutputStream().apply {
                write(ProtoWire.fieldMessage(platformData.fieldNumber, platformData.encode()))
            }.toByteArray()
            write(ProtoWire.fieldMessage(1, platform))
            write(ProtoWire.fieldString(2, deviceId))
        }.toByteArray()
        val clientData = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldString(1, clientVersion))
            write(ProtoWire.fieldString(2, clientId))
            write(ProtoWire.fieldMessage(3, connectivity))
        }.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(ProtoWire.fieldVarint(1, ClientTokenRequestType.CLIENT_DATA_REQUEST.code))
        out.write(ProtoWire.fieldMessage(2, clientData))
        return out.toByteArray()
    }

    fun encodeChallengeAnswers(state: String, answer: HashCashAnswer): ByteArray {
        val challengeAnswer = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldVarint(1, ChallengeType.HASH_CASH.code))
            write(ProtoWire.fieldMessage(4, answer.encode()))
        }.toByteArray()
        val challengeAnswers = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldString(1, state))
            write(ProtoWire.fieldMessage(2, challengeAnswer))
        }.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(ProtoWire.fieldVarint(1, ClientTokenRequestType.CHALLENGE_ANSWERS_REQUEST.code))
        out.write(ProtoWire.fieldMessage(3, challengeAnswers))
        return out.toByteArray()
    }
}

data class HashCashAnswer(
    val suffix: ByteArray,
) {
    fun encode(): ByteArray = java.io.ByteArrayOutputStream().apply {
        // The server expects an uppercase ASCII hex string of the raw suffix.
        write(ProtoWire.fieldString(1, suffix.toUppercaseHex()))
    }.toByteArray()
}

data class ClientTokenChallengesResponse(
    val state: String,
    val challenges: List<ClientTokenChallenge>,
) {
    companion object {
        fun parse(data: ByteArray): ClientTokenChallengesResponse {
            val reader = Reader(data)
            var state = ""
            val challenges = mutableListOf<ClientTokenChallenge>()
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> state = reader.stringField(tag)
                    2 -> challenges.add(parseChallenge(reader.bytesField(tag)))
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return ClientTokenChallengesResponse(state, challenges)
        }

        private fun parseChallenge(data: ByteArray): ClientTokenChallenge {
            val reader = Reader(data)
            var typeCode = 0
            var hashCash: HashCashChallengeParameters? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> typeCode = reader.nonNegativeIntField(tag)
                    4 -> hashCash = parseHashCash(reader.bytesField(tag))
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            if (hashCash != null && typeCode != ChallengeType.HASH_CASH.code) {
                throw ProtoParseException("Client token challenge type does not match its payload")
            }
            return ClientTokenChallenge(
                type = ChallengeType.entries.firstOrNull { it.code == typeCode } ?: ChallengeType.CHALLENGE_UNKNOWN,
                hashCash = hashCash,
            )
        }

        private fun parseHashCash(data: ByteArray): HashCashChallengeParameters? {
            val reader = Reader(data)
            var length = 0
            var prefixHex: String? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> length = reader.nonNegativeIntField(tag)
                    2 -> prefixHex = reader.stringField(tag)
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return prefixHex?.let { HashCashChallengeParameters(length, it.hexToBytes()) }
        }
    }
}

data class ClientTokenChallenge(
    val type: ChallengeType,
    val hashCash: HashCashChallengeParameters? = null,
)

data class HashCashChallengeParameters(
    val length: Int,
    val prefix: ByteArray,
)

data class GrantedClientToken(
    val token: String,
    val expiresAfterSeconds: Int,
    val refreshAfterSeconds: Int,
    val domains: List<String> = emptyList(),
) {
    /** A client token may only be sent to an HTTPS host covered by its granted domains. */
    fun allows(uri: URI): Boolean {
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return false
        return domains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    override fun toString(): String =
        "GrantedClientToken(expiresAfterSeconds=$expiresAfterSeconds, refreshAfterSeconds=$refreshAfterSeconds, domains=$domains)"

    companion object {
        fun parse(data: ByteArray): GrantedClientToken {
            val reader = Reader(data)
            var token = ""
            var expires = 0
            var refresh = 0
            val domains = linkedSetOf<String>()
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> token = reader.stringField(tag)
                    2 -> expires = reader.nonNegativeIntField(tag)
                    3 -> refresh = reader.nonNegativeIntField(tag)
                    4 -> domains.add(parseDomain(reader.bytesField(tag)))
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            if (token.isBlank() || expires <= 0) throw ProtoParseException("Client token is empty or has no valid lifetime")
            return GrantedClientToken(token, expires, refresh, domains.toList())
        }

        private fun parseDomain(data: ByteArray): String {
            val reader = Reader(data)
            var domain = ""
            while (reader.hasNext()) {
                val tag = reader.readTag()
                if (reader.fieldNumber(tag) == 1) domain = reader.stringField(tag)
                else reader.skip(reader.wireType(tag))
            }
            return normalizeDomain(domain)
        }

        internal fun normalizeDomain(domain: String): String {
            val normalized = domain.lowercase(Locale.ROOT).removePrefix(".").removeSuffix(".")
            if (normalized.length !in 1..253 || normalized.split('.').any { !it.matches(DOMAIN_LABEL) }) {
                throw ProtoParseException("Client token contains an invalid domain")
            }
            return normalized
        }

        private val DOMAIN_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    }
}

enum class ClientTokenRequestType(val code: Int) {
    REQUEST_UNKNOWN(0),
    CLIENT_DATA_REQUEST(1),
    CHALLENGE_ANSWERS_REQUEST(2),
}

enum class ClientTokenResponseType(val code: Int) {
    RESPONSE_UNKNOWN(0),
    GRANTED_TOKEN_RESPONSE(1),
    CHALLENGES_RESPONSE(2),
}

enum class ChallengeType(val code: Int) {
    CHALLENGE_UNKNOWN(0),
    CLIENT_SECRET_HMAC(1),
    EVALUATE_JS(2),
    HASH_CASH(3),
}

data class ClientTokenResponse(
    val responseType: Int? = null,
    val grantedToken: GrantedClientToken? = null,
    val challenges: ClientTokenChallengesResponse? = null,
) {
    companion object {
        fun parse(data: ByteArray): ClientTokenResponse {
            val reader = Reader(data)
            var responseType: Int? = null
            var grantedToken: GrantedClientToken? = null
            var challenges: ClientTokenChallengesResponse? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> responseType = reader.nonNegativeIntField(tag)
                    2 -> grantedToken = GrantedClientToken.parse(reader.bytesField(tag))
                    3 -> challenges = ClientTokenChallengesResponse.parse(reader.bytesField(tag))
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            when (responseType) {
                ClientTokenResponseType.GRANTED_TOKEN_RESPONSE.code ->
                    if (grantedToken == null || challenges != null) throw ProtoParseException("Invalid client token grant response")
                ClientTokenResponseType.CHALLENGES_RESPONSE.code ->
                    if (challenges == null || grantedToken != null) throw ProtoParseException("Invalid client token challenge response")
                else -> throw ProtoParseException("Unsupported client token response type")
            }
            return ClientTokenResponse(responseType, grantedToken, challenges)
        }
    }
}

private fun Reader.bytesField(tag: Int): ByteArray {
    if (wireType(tag) != 2) throw ProtoParseException("Unexpected wire type in client token response")
    return readBytes()
}

private fun Reader.stringField(tag: Int): String = bytesField(tag).toString(Charsets.UTF_8)

private fun Reader.nonNegativeIntField(tag: Int): Int {
    if (wireType(tag) != 0) throw ProtoParseException("Unexpected wire type in client token response")
    val value = readVarint()
    if (value !in 0..Int.MAX_VALUE.toLong()) throw ProtoParseException("Client token integer is out of range")
    return value.toInt()
}
