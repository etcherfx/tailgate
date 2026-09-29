package io.github.etcherfx.tailgate.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

@Timeout(30)
class ForwarderTest {
    private val pki = TestPki()
    private val resources = mutableListOf<Closeable>()

    private fun <T : Closeable> T.closeLater(): T = also { resources += it }

    @AfterEach
    fun cleanup() = resources.asReversed().forEach { it.close() }

    private fun forwarder(target: FunnelAddress, connector: TlsConnector = pki.connector()): Forwarder =
        Forwarder(target, connector, TailgateLog.Stderr).closeLater()

    @Test
    fun `status and ping flow through TLS to the backend`() {
        val funnel = FakeFunnel(pki.serverContext("localhost")).closeLater()
        val forwarder = forwarder(FunnelAddress("localhost", funnel.port))
        val port = forwarder.start(0)

        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            out.write(McProtocol.Handshake(775, "127.0.0.1", port, McProtocol.STATE_STATUS).encode())
            out.write(McProtocol.packet(0x00) {})
            out.flush()
            val status = McProtocol.readPacket(input, 1 shl 20).inputStream()
            assertEquals(0, McProtocol.readVarInt(status))
            assertEquals(FakeMinecraftServer.STATUS_JSON, McProtocol.readString(status, 1 shl 20))
            out.write(McProtocol.pong(42L)) // a ping has the same shape as a pong
            out.flush()
            val pong = McProtocol.readPacket(input).inputStream()
            assertEquals(1, McProtocol.readVarInt(pong))
            assertEquals(42L, DataInputStream(pong).readLong())
        }

        val seen = funnel.backend.nextHandshake()
        assertEquals("localhost", seen.host)
        assertEquals(funnel.port, seen.port)
        assertEquals(775, seen.protocol)
    }

    @Test
    fun `login traffic after the handshake is copied byte for byte`() {
        val funnel = FakeFunnel(pki.serverContext("localhost")).closeLater()
        val port = forwarder(FunnelAddress("localhost", funnel.port)).start(0)
        val payload = ByteArray(200_000) { (it * 31 + 7).toByte() }

        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            out.write(McProtocol.Handshake(47, "127.0.0.1\u0000FML\u0000", port, McProtocol.STATE_LOGIN).encode())
            thread("writer") {
                out.write(payload)
                out.flush()
            }
            val echoed = ByteArray(payload.size)
            DataInputStream(socket.getInputStream()).readFully(echoed)
            assertArrayEquals(payload, echoed)
        }
        assertEquals("localhost\u0000FML\u0000", funnel.backend.nextHandshake().host)
    }

    @Test
    fun `unreachable host gets a readable MOTD`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val port = forwarder(FunnelAddress("localhost", closedPort)).start(0)

        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            out.write(McProtocol.Handshake(767, "127.0.0.1", port, McProtocol.STATE_STATUS).encode())
            out.write(McProtocol.packet(0x00) {})
            out.flush()
            val status = McProtocol.readPacket(input, 1 shl 20).inputStream()
            assertEquals(0, McProtocol.readVarInt(status))
            val json = Json.parse(McProtocol.readString(status, 1 shl 20)) as Map<*, *>
            assertEquals(767L, (json["version"] as Map<*, *>)["protocol"])
            assertEquals("Tailgate: can't reach localhost (connection refused)", (json["description"] as Map<*, *>)["text"])
        }
    }

    @Test
    fun `unreachable host gets a readable login disconnect`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val port = forwarder(FunnelAddress("localhost", closedPort)).start(0)

        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            out.write(McProtocol.Handshake(767, "127.0.0.1", port, McProtocol.STATE_LOGIN).encode())
            out.write(McProtocol.packet(0x00) { McProtocol.writeString(it, "Steve") })
            out.flush()
            val body = McProtocol.readPacket(BufferedInputStream(socket.getInputStream()), 1 shl 20).inputStream()
            assertEquals(0, McProtocol.readVarInt(body))
            assertEquals(
                """{"text":"Tailgate: can't reach localhost (connection refused)"}""",
                McProtocol.readString(body, 1 shl 20),
            )
        }
    }

    @Test
    fun `wrong hostname is rejected`() {
        val funnel = FakeFunnel(pki.serverContext("someone-else.example")).closeLater()
        val e = assertThrows(IOException::class.java) { pki.connector().connect(FunnelAddress("localhost", funnel.port)) }
        assertEquals("the host's certificate doesn't match its name", Failures.describe(e))
    }

    @Test
    fun `untrusted certificate is rejected`() {
        val funnel = FakeFunnel(TestPki("Untrusted CA").serverContext("localhost")).closeLater()
        val e = assertThrows(IOException::class.java) { pki.connector().connect(FunnelAddress("localhost", funnel.port)) }
        assertEquals("the host's certificate isn't trusted", Failures.describe(e))
    }

    @Test
    fun `status ping reports the server`() {
        val funnel = FakeFunnel(pki.serverContext("localhost")).closeLater()
        val result = StatusPing.ping(FunnelAddress("localhost", funnel.port), pki.connector())
        assertEquals("Fake 26.3", result.versionName)
        assertEquals(3, result.onlinePlayers)
        assertEquals(20, result.maxPlayers)
    }

    @Test
    fun `a taken port is replaced unless forbidden`() {
        val taken = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).closeLater()
        val target = FunnelAddress("localhost", 1)
        val moved = forwarder(target).start(taken.localPort, allowOtherPort = true)
        assertNotEquals(taken.localPort, moved)
        assertTrue(moved > 0)
        assertThrows(IOException::class.java) { forwarder(target).start(taken.localPort, allowOtherPort = false) }
    }
}
