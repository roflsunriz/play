package io.github.playmusic.data.auth

import io.github.playmusic.data.auth.ProtoWire.Reader

data class ClientTokenRequest(
    val clientId: String,
    val clientVersion: String,
    val deviceId: String,
) {
    fun encode(): ByteArray = encodeClientData(NativeAndroidData())

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
        val answers = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldMessage(1, challengeAnswer))
        }.toByteArray()
        val challengeAnswers = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldString(1, state))
            write(ProtoWire.fieldMessage(2, answers))
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
        write(ProtoWire.fieldBytes(1, suffix))
    }.toByteArray()
}

data class NativeAndroidData(
    val majorVersion: Int = 0,
    val minorVersion: Int = 0,
    val patchVersion: Int = 0,
    val apiVersion: Int = 0,
    val deviceName: String = "",
    val deviceManufacturer: String = "",
    val deviceBrand: String = "",
    val deviceModel: String = "",
    val deviceArch: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val screenDensity: Int = 0,
) {
    fun encode(): ByteArray = java.io.ByteArrayOutputStream().apply {
        if (majorVersion != 0) write(ProtoWire.fieldVarint(1, majorVersion))
        if (minorVersion != 0) write(ProtoWire.fieldVarint(2, minorVersion))
        if (patchVersion != 0) write(ProtoWire.fieldVarint(3, patchVersion))
        if (apiVersion != 0) write(ProtoWire.fieldVarint(4, apiVersion))
        if (deviceName.isNotEmpty()) write(ProtoWire.fieldString(5, deviceName))
        if (deviceManufacturer.isNotEmpty()) write(ProtoWire.fieldString(6, deviceManufacturer))
        if (deviceBrand.isNotEmpty()) write(ProtoWire.fieldString(7, deviceBrand))
        if (deviceModel.isNotEmpty()) write(ProtoWire.fieldString(8, deviceModel))
        if (deviceArch.isNotEmpty()) write(ProtoWire.fieldString(9, deviceArch))
        if (screenWidth != 0 || screenHeight != 0 || screenDensity != 0) {
            val screen = java.io.ByteArrayOutputStream().apply {
                if (screenWidth != 0) write(ProtoWire.fieldVarint(1, screenWidth))
                if (screenHeight != 0) write(ProtoWire.fieldVarint(2, screenHeight))
                if (screenDensity != 0) write(ProtoWire.fieldVarint(3, screenDensity))
            }.toByteArray()
            write(ProtoWire.fieldMessage(10, screen))
        }
    }.toByteArray()
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

data class HashCashChallengeParameters(
    val length: Int,
    val prefix: ByteArray,
)

data class ClientTokenChallenge(
    val type: ChallengeType,
    val hashCash: HashCashChallengeParameters? = null,
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
            var prefix: ByteArray? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> length = reader.readVarint().toInt()
                    2 -> prefix = reader.readBytes()
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return prefix?.let { HashCashChallengeParameters(length, it) }
        }
    }
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