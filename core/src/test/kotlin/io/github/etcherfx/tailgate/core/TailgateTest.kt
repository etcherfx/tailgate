package io.github.etcherfx.tailgate.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Timeout(30)
class TailgateTest {
    @TempDir
    lateinit var dir: File

    private val pki = TestPki()
    private val started = mutableListOf<Tailgate>()

    @AfterEach
    fun stop() = started.forEach { it.stop() }

    private fun tailgate(): Tailgate =
        Tailgate(ServerStore(File(dir, "servers.json")), TailgateLog.Stderr, pki.connector()).also { started += it }

    @Test
    fun `added servers persist and restart on their port`() {
        val first = tailgate()
        first.start()
        val added = first.add("Alex", FunnelAddress.parse("alex-pc.tail1234.ts.net"))
        assertTrue(added.localPort > 0)
        first.stop()

        val second = tailgate()
        second.start()
        val restored = second.servers().single()
        assertEquals("Alex", restored.name)
        assertEquals(added.localPort, restored.localPort)
        Socket("127.0.0.1", restored.localPort).close()
        assertEquals(emptyMap<String, String>(), second.reconcile(listOf(restored.localAddress)))
    }

    @Test
    fun `a taken port moves and the server-list entry is rewritten`() {
        val taken = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        taken.use {
            ServerStore(File(dir, "servers.json")).save(
                listOf(SavedServer("id", "Alex", FunnelAddress.parse("alex-pc.tail1234.ts.net"), taken.localPort)),
            )
            val tailgate = tailgate()
            tailgate.start()
            val moved = tailgate.servers().single()
            assertNotEquals(taken.localPort, moved.localPort)

            val rewrites = tailgate.reconcile(listOf("127.0.0.1:${taken.localPort}", "mc.hypixel.net"))
            assertEquals(mapOf("127.0.0.1:${taken.localPort}" to moved.localAddress), rewrites)
            assertEquals(moved.localPort, ServerStore(File(dir, "servers.json")).load().single().localPort)
            assertEquals(1, tailgate.servers().size)
        }
    }

    @Test
    fun `servers whose entries were deleted are removed`() {
        val tailgate = tailgate()
        tailgate.start()
        val keep = tailgate.add("Keep", FunnelAddress.parse("keep.ts.net"))
        val drop = tailgate.add("Drop", FunnelAddress.parse("drop.ts.net"))

        tailgate.reconcile(listOf(keep.localAddress, "play.example.com"))

        assertEquals(listOf("Keep"), tailgate.servers().map { it.name })
        assertEquals(listOf("Keep"), ServerStore(File(dir, "servers.json")).load().map { it.name })
        val refused = runCatching { Socket("127.0.0.1", drop.localPort).close() }
        assertTrue(refused.isFailure, "the dropped forwarder should stop listening")
    }

    @Test
    fun `form tests and adds`() {
        FakeFunnel(pki.serverContext("localhost")).use { funnel ->
            val tailgate = tailgate()
            tailgate.start()
            val form = AddServerForm(tailgate)

            form.test("127.0.0.1")
            assertEquals(AddServerForm.Tone.ERROR, form.tone)

            val done = CompletableFuture<Unit>()
            form.test("localhost:${funnel.port}")
            assertTrue(form.testing)
            thread("poll") {
                while (form.testing) Thread.sleep(20)
                done.complete(Unit)
            }
            done.get(20, TimeUnit.SECONDS)
            assertEquals(AddServerForm.Tone.OK, form.tone, form.status)
            assertTrue(form.status.startsWith("Reachable: Fake 26.3"), form.status)

            val added = form.add("  ", "localhost:${funnel.port}")
            assertNotNull(added)
            assertEquals("localhost", added!!.name)
            assertTrue(added.localAddress.startsWith("127.0.0.1:"))
        }
    }
}
