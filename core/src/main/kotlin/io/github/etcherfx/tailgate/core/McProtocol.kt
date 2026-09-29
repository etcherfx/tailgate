package io.github.etcherfx.tailgate.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The parts of the Netty-era (1.7+) Minecraft protocol the forwarder needs before it goes transparent. */
object McProtocol {
    const val STATE_STATUS = 1
    const val STATE_LOGIN = 2
    const val STATE_TRANSFER = 3

    /** First byte of the pre-Netty server list ping, which some clients still send as a fallback. */
    const val LEGACY_PING = 0xFE

    /** Handshakes are small; anything bigger isn't Minecraft. */
    private const val MAX_HANDSHAKE_LENGTH = 32 * 1024

    class ProtocolException(message: String) : IOException(message)

    class Handshake(
        val protocol: Int,
        /** The address the client typed, including any `\0`-separated loader markers (FML, FML2, FML3…). */
        val host: String,
        val port: Int,
        val nextState: Int,
    ) {
        /** Everything from the first `\0` on, which Forge uses to tag modded clients. */
        val hostSuffix: String get() = host.indexOf('\u0000').let { if (it < 0) "" else host.substring(it) }

        fun withTarget(targetHost: String, targetPort: Int) = Handshake(protocol, targetHost + hostSuffix, targetPort, nextState)

        fun encode(): ByteArray = packet(0x00) {
            writeVarInt(it, protocol)
            writeString(it, host)
            it.write(port ushr 8 and 0xFF)
            it.write(port and 0xFF)
            writeVarInt(it, nextState)
        }
    }

    fun readVarInt(input: InputStream): Int {
        var value = 0
        for (i in 0 until 5) {
            val b = input.read()
            if (b < 0) throw EOFException()
            value = value or ((b and 0x7F) shl (7 * i))
            if (b and 0x80 == 0) return value
        }
        throw ProtocolException("VarInt too long")
    }

    fun writeVarInt(out: OutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.write(v)
                return
            }
            out.write(v and 0x7F or 0x80)
            v = v ushr 7
        }
    }

    fun readString(input: InputStream, maxBytes: Int): String {
        val length = readVarInt(input)
        if (length < 0 || length > maxBytes) throw ProtocolException("String length $length out of range")
        val bytes = ByteArray(length)
        DataInputStream(input).readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun writeString(out: OutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    /** Reads one length-prefixed, uncompressed packet and returns its body (packet id + payload). */
    fun readPacket(input: InputStream, maxLength: Int = MAX_HANDSHAKE_LENGTH): ByteArray {
        val length = readVarInt(input)
        if (length <= 0 || length > maxLength) throw ProtocolException("Packet length $length out of range")
        val body = ByteArray(length)
        DataInputStream(input).readFully(body)
        return body
    }

    /** Builds a length-prefixed packet from an id and a payload writer. */
    inline fun packet(id: Int, writePayload: (OutputStream) -> Unit): ByteArray {
        val body = ByteArrayOutputStream()
        writeVarInt(body, id)
        writePayload(body)
        val out = ByteArrayOutputStream(body.size() + 5)
        writeVarInt(out, body.size())
        body.writeTo(out)
        return out.toByteArray()
    }

    /** Parses a handshake packet body as returned by [readPacket]. */
    fun parseHandshake(body: ByteArray): Handshake {
        val input = body.inputStream()
        val id = readVarInt(input)
        if (id != 0x00) throw ProtocolException("Expected a handshake, got packet $id")
        val protocol = readVarInt(input)
        // Vanilla caps the address at 255 characters; Forge markers can push it a little past that.
        val host = readString(input, 4096)
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) throw EOFException()
        val nextState = readVarInt(input)
        return Handshake(protocol, host, hi shl 8 or lo, nextState)
    }

    /** A status response for when Tailgate can't reach the host; shows [message] as the MOTD. */
    fun statusResponse(protocol: Int, message: String): ByteArray {
        val json = Json.write(
            linkedMapOf(
                "version" to linkedMapOf("name" to "Tailgate", "protocol" to protocol),
                "players" to linkedMapOf("max" to 0, "online" to 0),
                "description" to linkedMapOf("text" to message),
            ),
        )
        return packet(0x00) { writeString(it, json) }
    }

    /** A status-state pong echoing the client's ping payload. */
    fun pong(payload: Long): ByteArray = packet(0x01) { out ->
        for (shift in 56 downTo 0 step 8) out.write((payload ushr shift).toInt() and 0xFF)
    }

    /** A login-state Disconnect packet; its reason is a JSON text component in every version. */
    fun loginDisconnect(message: String): ByteArray =
        packet(0x00) { writeString(it, Json.write(linkedMapOf("text" to message))) }
}
