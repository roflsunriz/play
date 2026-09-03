package io.github.playmusic.data.auth

import io.github.playmusic.data.auth.ProtoWire.Reader

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
    val androidData: NativeAndroidData = NativeAndroidData(),
) {
    fun encode(): ByteArray = encodeClientData(androidData)

    private fun encodeClientData(androidData: NativeAndroidData): ByteArray {
        val connectivity = java.io.ByteArrayOutputStream().apply {
            val platform = java.io.ByteArrayOutputStream().apply {
                write(ProtoWire.fieldMessage(1, androidData.encode()))
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

/**
 * NativeAndroidData as captured from the official Spotify Android app.
 * Field layout matches the real device traffic byte-for-byte.
 */
data class NativeAndroidData(
    val sdkVersion: AndroidSdkVersion = AndroidSdkVersion(),
    val field2: Int = 22,
    val field3: Int = 36,
    val deviceModel: String = "SH-R80P",
    val deviceName: String = "SH-R80P",
    val manufacturer: String = "SHARP",
    val brand: String = "SHARP",
    val field8: Int = 32,
    val appSignature: String = "",
    val installer: String = "",
) {
    fun encode(): ByteArray = java.io.ByteArrayOutputStream().apply {
        write(ProtoWire.fieldMessage(1, sdkVersion.encode()))
        if (field2 != 0) write(ProtoWire.fieldVarint(2, field2))
        if (field3 != 0) write(ProtoWire.fieldVarint(3, field3))
        if (deviceModel.isNotEmpty()) write(ProtoWire.fieldString(4, deviceModel))
        if (deviceName.isNotEmpty()) write(ProtoWire.fieldString(5, deviceName))
        if (manufacturer.isNotEmpty()) write(ProtoWire.fieldString(6, manufacturer))
        if (brand.isNotEmpty()) write(ProtoWire.fieldString(7, brand))
        if (field8 != 0) write(ProtoWire.fieldVarint(8, field8))
        if (appSignature.isNotEmpty()) write(ProtoWire.fieldString(9, appSignature))
        if (installer.isNotEmpty()) write(ProtoWire.fieldString(10, installer))
    }.toByteArray()
}

data class AndroidSdkVersion(
    val major: Int = 16,
    val minor: Int = 0,
    val patch: Int = 0,
    val apiLevel: Int = 36,
    val extra1: Int = 36,
) {
    fun encode(): ByteArray = java.io.ByteArrayOutputStream().apply {
        write(ProtoWire.fieldVarint(1, major))
        write(ProtoWire.fieldVarint(2, minor))
        write(ProtoWire.fieldVarint(3, patch))
        write(ProtoWire.fieldVarint(4, apiLevel))
        write(ProtoWire.fieldVarint(5, extra1))
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
                    1 -> state = reader.readString()
                    2 -> challenges.add(parseChallenge(reader.readBytes()))
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
                    1 -> typeCode = reader.readVarint().toInt()
                    4 -> hashCash = parseHashCash(reader.readBytes())
                    else -> reader.skip(reader.wireType(tag))
                }
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
                    1 -> length = reader.readVarint().toInt()
                    2 -> prefixHex = reader.readString()
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
) {
    companion object {
        fun parse(data: ByteArray): GrantedClientToken {
            val reader = Reader(data)
            var token = ""
            var expires = 0
            var refresh = 0
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> token = reader.readString()
                    2 -> expires = reader.readVarint().toInt()
                    3 -> refresh = reader.readVarint().toInt()
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return GrantedClientToken(token, expires, refresh)
        }
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
                    1 -> responseType = reader.readVarint().toInt()
                    2 -> grantedToken = GrantedClientToken.parse(reader.readBytes())
                    3 -> challenges = ClientTokenChallengesResponse.parse(reader.readBytes())
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return ClientTokenResponse(responseType, grantedToken, challenges)
        }
    }
}
