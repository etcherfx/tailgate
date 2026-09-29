package io.github.etcherfx.tailgate.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class McProtocolTest {
    @Test
    fun `varints round-trip and match the wire format`() {
        val cases = mapOf(
            0 to "00",
            1 to "01",
            127 to "7f",
            128 to "8001",
            255 to "ff01",
            25565 to "ddc701",
            2097151 to "ffff7f",
            Int.MAX_VALUE to "ffffffff07",
            -1 to "ffffffff0f",
        )
        for ((value, hex) in cases) {
            val out = ByteArrayOutputStream()
            McProtocol.writeVarInt(out, value)
            assertEquals(hex, out.toByteArray().joinToString("") { "%02x".format(it) }, "encoding $value")
            assertEquals(value, McProtocol.readVarInt(out.toByteArray().inputStream()), "decoding $hex")
        }
    }

    @Test
    fun `overlong varints are rejected`() {
        assertThrows(McProtocol.ProtocolException::class.java) {
            McProtocol.readVarInt(byteArrayOf(-1, -1, -1, -1, -1, 1).inputStream())
        }
    }

    @Test
    fun `handshake rewrite targets the Funnel host and keeps the FML marker`() {
        val original = McProtocol.Handshake(763, "127.0.0.1\u0000FML3\u0000", 54321, McProtocol.STATE_LOGIN)
        val packet = original.encode()
        val parsed = McProtocol.parseHandshake(McProtocol.readPacket(packet.inputStream()))
        assertEquals("127.0.0.1\u0000FML3\u0000", parsed.host)

        val rewritten = parsed.withTarget("alex-pc.tail1234.ts.net", 10000)
        val reparsed = McProtocol.parseHandshake(McProtocol.readPacket(rewritten.encode().inputStream()))
        assertEquals(763, reparsed.protocol)
        assertEquals("alex-pc.tail1234.ts.net\u0000FML3\u0000", reparsed.host)
        assertEquals(10000, reparsed.port)
        assertEquals(McProtocol.STATE_LOGIN, reparsed.nextState)
    }

    @Test
    fun `legacy FML marker is kept too`() {
        val handshake = McProtocol.Handshake(5, "localhost\u0000FML\u0000", 25565, McProtocol.STATE_LOGIN)
        assertEquals("host.ts.net\u0000FML\u0000", handshake.withTarget("host.ts.net", 443).host)
        assertEquals("host.ts.net", McProtocol.Handshake(5, "localhost", 1, 1).withTarget("host.ts.net", 443).host)
    }

    @Test
    fun `handshake encoding matches vanilla`() {
        val packet = McProtocol.Handshake(47, "a", 25565, 1).encode()
        // length, id 0, protocol 47, "a", port 0x63DD, next state 1
        assertArrayEquals(byteArrayOf(7, 0, 47, 1, 'a'.code.toByte(), 0x63, 0xDD.toByte(), 1), packet)
    }

    @Test
    fun `status response echoes protocol and carries the message`() {
        val body = McProtocol.readPacket(McProtocol.statusResponse(772, "Tailgate: can't reach x (timed out)").inputStream()).inputStream()
        assertEquals(0, McProtocol.readVarInt(body))
        val json = Json.parse(McProtocol.readString(body, 1 shl 20)) as Map<*, *>
        assertEquals(772L, (json["version"] as Map<*, *>)["protocol"])
        assertEquals("Tailgate: can't reach x (timed out)", (json["description"] as Map<*, *>)["text"])
    }

    @Test
    fun `pong echoes the payload`() {
        val body = McProtocol.readPacket(McProtocol.pong(0x0102030405060708L).inputStream()).inputStream()
        assertEquals(1, McProtocol.readVarInt(body))
        assertEquals(0x0102030405060708L, DataInputStream(body).readLong())
    }

    @Test
    fun `login disconnect is a JSON text component`() {
        val body = McProtocol.readPacket(McProtocol.loginDisconnect("nope \"quoted\"").inputStream()).inputStream()
        assertEquals(0, McProtocol.readVarInt(body))
        assertEquals("""{"text":"nope \"quoted\""}""", McProtocol.readString(body, 1 shl 20))
    }

    @Test
    fun `oversized packets are rejected`() {
        val out = ByteArrayOutputStream()
        McProtocol.writeVarInt(out, 1 shl 20)
        assertThrows(McProtocol.ProtocolException::class.java) { McProtocol.readPacket(out.toByteArray().inputStream()) }
    }
}
