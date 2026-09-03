package io.github.playmusic.data.auth

import io.github.playmusic.data.auth.ProtoWire.Reader

data class ClientInfo(
    val clientId: String,
    val deviceId: String,
) {
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ProtoWire.fieldString(1, clientId))
        out.write(ProtoWire.fieldString(2, deviceId))
        return out.toByteArray()
    }
}

data class LoginPassword(
    val id: String,
    val password: String,
) {
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ProtoWire.fieldString(1, id))
        out.write(ProtoWire.fieldString(2, password))
        return out.toByteArray()
    }
}

data class LoginStoredCredential(
    val username: String,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ProtoWire.fieldString(1, username))
        out.write(ProtoWire.fieldBytes(2, data))
        return out.toByteArray()
    }
}

data class HashcashSolution(
    val suffix: ByteArray,
    val durationSeconds: Long,
    val durationNanos: Int,
) {
    fun encode(): ByteArray {
        val duration = java.io.ByteArrayOutputStream().apply {
            write(ProtoWire.fieldVarint(1, durationSeconds))
            write(ProtoWire.fieldVarint(2, durationNanos))
        }.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(ProtoWire.fieldBytes(1, suffix))
        out.write(ProtoWire.fieldMessage(2, duration))
        return out.toByteArray()
    }
}

data class ChallengeSolution(
    val hashcash: HashcashSolution? = null,
) {
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        hashcash?.let { out.write(ProtoWire.fieldMessage(1, it.encode())) }
        return out.toByteArray()
    }
}

data class LoginRequest(
    val clientInfo: ClientInfo? = null,
    val loginContext: ByteArray? = null,
    val challengeSolutions: List<ChallengeSolution> = emptyList(),
    val storedCredential: LoginStoredCredential? = null,
    val password: LoginPassword? = null,
) {
    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        clientInfo?.let { out.write(ProtoWire.fieldMessage(1, it.encode())) }
        loginContext?.let { out.write(ProtoWire.fieldBytes(2, it)) }
        if (challengeSolutions.isNotEmpty()) {
            val solutions = java.io.ByteArrayOutputStream().apply {
                challengeSolutions.forEach { write(ProtoWire.fieldMessage(1, it.encode())) }
            }.toByteArray()
            out.write(ProtoWire.fieldMessage(3, solutions))
        }
        storedCredential?.let { out.write(ProtoWire.fieldMessage(100, it.encode())) }
        password?.let { out.write(ProtoWire.fieldMessage(101, it.encode())) }
        return out.toByteArray()
    }
}

data class LoginOk(
    val username: String,
    val accessToken: String,
    val storedCredential: ByteArray?,
    val accessTokenExpiresIn: Int,
)

data class HashcashChallenge(
    val prefix: ByteArray,
    val length: Int,
)

data class Challenge(
    val hashcash: HashcashChallenge? = null,
)

data class LoginResponse(
    val ok: LoginOk? = null,
    val error: Int? = null,
    val challenges: List<Challenge> = emptyList(),
    val loginContext: ByteArray? = null,
) {
    companion object {
        fun parse(data: ByteArray): LoginResponse {
            val reader = Reader(data)
            var ok: LoginOk? = null
            var error: Int? = null
            val challenges = mutableListOf<Challenge>()
            var loginContext: ByteArray? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> ok = parseOk(reader.readBytes())
                    2 -> error = reader.readVarint().toInt()
                    3 -> challenges.addAll(parseChallenges(reader.readBytes()))
                    5 -> loginContext = reader.readBytes()
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return LoginResponse(ok, error, challenges, loginContext)
        }

        private fun parseOk(data: ByteArray): LoginOk {
            val reader = Reader(data)
            var username = ""
            var accessToken = ""
            var storedCredential: ByteArray? = null
            var expiresIn = 0
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> username = reader.readString()
                    2 -> accessToken = reader.readString()
                    3 -> storedCredential = reader.readBytes()
                    4 -> expiresIn = reader.readVarint().toInt()
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return LoginOk(username, accessToken, storedCredential, expiresIn)
        }

        private fun parseChallenges(data: ByteArray): List<Challenge> {
            val reader = Reader(data)
            val result = mutableListOf<Challenge>()
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> result.add(parseChallenge(reader.readBytes()))
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return result
        }

        private fun parseChallenge(data: ByteArray): Challenge {
            val reader = Reader(data)
            var hashcash: HashcashChallenge? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                when (reader.fieldNumber(tag)) {
                    1 -> {
                        val inner = Reader(reader.readBytes())
                        var prefix: ByteArray? = null
                        var length = 0
                        while (inner.hasNext()) {
                            val innerTag = inner.readTag()
                            when (inner.fieldNumber(innerTag)) {
                                1 -> prefix = inner.readBytes()
                                2 -> length = inner.readVarint().toInt()
                                else -> inner.skip(inner.wireType(innerTag))
                            }
                        }
                        if (prefix != null) hashcash = HashcashChallenge(prefix, length)
                    }
                    else -> reader.skip(reader.wireType(tag))
                }
            }
            return Challenge(hashcash)
        }
    }
}

enum class Login5Error(val code: Int) {
    UNKNOWN_ERROR(0),
    INVALID_CREDENTIALS(1),
    BAD_REQUEST(2),
    UNSUPPORTED_LOGIN_PROTOCOL(3),
    TIMEOUT(4),
    UNKNOWN_IDENTIFIER(5),
    TOO_MANY_ATTEMPTS(6),
    INVALID_PHONENUMBER(7),
    TRY_AGAIN_LATER(8);

    companion object {
        fun fromCode(code: Int): Login5Error = entries.firstOrNull { it.code == code } ?: UNKNOWN_ERROR
    }
}