package io.github.playmusic.data.auth

import io.github.playmusic.data.auth.crypto.Shannon
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Authenticated AP packets. Each direction has its own cipher and nonce sequence. */
internal class AccessPointPackets(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    sendKey: ByteArray,
    receiveKey: ByteArray,
) {
    private val encoder = Shannon().apply { key(sendKey) }
    private val decoder = Shannon().apply { key(receiveKey) }
    private var sendNonce = 0
    private var receiveNonce = 0

    fun send(command: Int, payload: ByteArray) {
        require(command in 0..255 && payload.size <= 65_535)
        val packet = byteArrayOf(command.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        encoder.nonce(intBytes(sendNonce++))
        encoder.encrypt(packet)
        output.write(packet)
        output.write(ByteArray(4).also(encoder::finish))
        output.flush()
    }

    fun receive(): Pair<Int, ByteArray> {
        val header = ByteArray(3).also(input::readFully)
        decoder.nonce(intBytes(receiveNonce++))
        decoder.decrypt(header)
        val length = ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
        val payload = ByteArray(length).also(input::readFully)
        decoder.decrypt(payload)
        val expectedMac = ByteArray(4).also(input::readFully)
        val actualMac = ByteArray(4).also(decoder::finish)
        require(MessageDigest.isEqual(expectedMac, actualMac)) { "AP response authentication failed" }
        return (header[0].toInt() and 0xff) to payload
    }

    private fun intBytes(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
}
