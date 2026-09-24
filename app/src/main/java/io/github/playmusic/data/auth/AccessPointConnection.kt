package io.github.playmusic.data.auth

import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Obtains the canonical account identity from a signed and authenticated AP connection. */
class AccessPointConnection(private val host: String, private val port: Int) {
    fun username(accessToken: String, deviceId: String): String = Socket().use { socket ->
        require(accessToken.isNotBlank() && deviceId.isNotBlank())
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.soTimeout = 10_000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val random = SecureRandom()
        val privateKey = BigInteger(760, random)
        val publicKey = TWO.modPow(privateKey, DH_PRIME).unsignedBytes()
        val helloBody = ProtoWire.fieldMessage(10,
            ProtoWire.fieldVarint(10, 0) + ProtoWire.fieldVarint(20, 0) +
                // The Linux ARM AP profile is used by the reference library on Android.
                ProtoWire.fieldVarint(30, 17) + ProtoWire.fieldVarint(40, 124200290L)) +
            ProtoWire.fieldVarint(30, 0) +
            ProtoWire.fieldMessage(50, ProtoWire.fieldMessage(10,
                ProtoWire.fieldBytes(10, publicKey) + ProtoWire.fieldVarint(20, 1))) +
            ProtoWire.fieldBytes(60, ByteArray(16).also(random::nextBytes)) +
            ProtoWire.fieldBytes(70, byteArrayOf(0x1e))
        val hello = byteArrayOf(0, 4) + intBytes(helloBody.size + 6) + helloBody
        output.write(hello)
        output.flush()
        val responseSize = input.readInt()
        require(responseSize in 4..MAX_PACKET_BYTES) { "Invalid AP handshake size" }
        val response = ByteArray(responseSize - 4).also(input::readFully)
        val challenge = ApIdentityProto.bytes(response, 10, 10, 10)
        val remoteKey = ApIdentityProto.bytes(challenge, 10)
        val signature = ApIdentityProto.bytes(challenge, 30)
        verifyServerKey(remoteKey, signature)
        val remoteNumber = BigInteger(1, remoteKey)
        require(remoteNumber > BigInteger.ONE && remoteNumber < DH_PRIME.subtract(BigInteger.ONE)) { "Invalid AP key" }
        val sharedSecret = remoteNumber.modPow(privateKey, DH_PRIME).unsignedBytes()
        val transcript = hello + intBytes(responseSize) + response
        val keys = (1..5).map { hmac(sharedSecret, transcript + it.toByte()) }.reduce(ByteArray::plus)
        val answer = hmac(keys.copyOfRange(0, 20), transcript)
        val reply = ProtoWire.fieldMessage(10, ProtoWire.fieldMessage(10, ProtoWire.fieldBytes(10, answer))) +
            ProtoWire.fieldMessage(20, ByteArray(0)) + ProtoWire.fieldMessage(30, ByteArray(0))
        output.writeInt(reply.size + 4)
        output.write(reply)
        output.flush()
        val packets = AccessPointPackets(input, output, keys.copyOfRange(20, 52), keys.copyOfRange(52, 84))
        sharedSecret.fill(0)
        keys.fill(0)
        val login = ProtoWire.fieldMessage(10, ProtoWire.fieldVarint(20, 3) + ProtoWire.fieldString(30, accessToken)) +
            ProtoWire.fieldMessage(50, ProtoWire.fieldVarint(10, 5) + ProtoWire.fieldVarint(60, 7) +
                ProtoWire.fieldString(90, "Play") + ProtoWire.fieldString(100, deviceId)) +
            ProtoWire.fieldString(70, "Play 0.1.0")
        packets.send(0xab, login)
        login.fill(0)
        val (command, payload) = packets.receive()
        try {
            ApIdentityProto.username(command, payload)
        } finally {
            payload.fill(0)
        }
    }

    private fun verifyServerKey(key: ByteArray, signature: ByteArray) {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(SERVER_KEY, BigInteger.valueOf(65537)))
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(publicKey)
        verifier.update(key)
        require(verifier.verify(signature)) { "AP server identity could not be verified" }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(key, "HmacSHA1"))
        doFinal(data)
    }

    private fun BigInteger.unsignedBytes(): ByteArray = toByteArray().let { if (it[0] == 0.toByte()) it.drop(1).toByteArray() else it }

    companion object {
        private const val MAX_PACKET_BYTES = 65_536
        private val TWO = BigInteger.valueOf(2)
        private fun intBytes(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
        private val DH_PRIME = BigInteger(
            "ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22514a08798e3404dd" +
                "ef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a63a3620ffffffffffffffff", 16)
        // Published AP verification key from the reference protocol implementation, not a credential.
        private val SERVER_KEY = BigInteger(
            "ace0460bffc230aff46bfec3bfbf863da191c6cc336c93a14fb3b01612acac6af180e7f614d9429dbe2e346643e362d2" +
                "327a1a0d923baedd1402b18155056104d52c96a44c1ecc024ad4b20c001f17edc22fc43521c8f0cbaed2add72b0f9db3" +
                "c5321a2afe59f35a0dac68f1fa621efb2c8d0cb7392d9247e3d7351a6dbd24c2ae255b88ffab73298a0bcccd0c586731" +
                "89e8bd3480784a5fc96b899d956bfc86d74f33a6781796c9c32d0d32a5abcd0527e2f710a39613c42f99c027bfed049c" +
                "3c275804b6b219f9c12f02e94863eca1b642a09d4825f8b39dd0e86af9484da1c2ba863042ea9db3086c190e48b39d66" +
                "eb0006a25aeea11b13873cd719e655bd", 16)
    }
}

internal object ApIdentityProto {
    fun bytes(payload: ByteArray, vararg path: Int): ByteArray {
        var current = payload
        for (field in path) {
            val reader = ProtoWire.Reader(current)
            var found: ByteArray? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                if (reader.fieldNumber(tag) == field && reader.wireType(tag) == 2) { found = reader.readBytes(); break }
                reader.skip(reader.wireType(tag))
            }
            current = found ?: throw AuthException("AP response is missing field $field")
        }
        return current
    }

    fun username(command: Int, payload: ByteArray): String {
        if (command == 0xad) {
            val reader = ProtoWire.Reader(payload)
            var error: Long? = null
            while (reader.hasNext()) {
                val tag = reader.readTag()
                if (reader.fieldNumber(tag) == 10 && reader.wireType(tag) == 0) error = reader.readVarint()
                else reader.skip(reader.wireType(tag))
            }
            throw AuthException("Account identity was rejected by AP (code $error)")
        }
        require(command == 0xac) { "Unexpected AP identity response" }
        return bytes(payload, 10).toString(Charsets.UTF_8).also { require(it.isNotBlank()) { "Account identity is missing" } }
    }
}
