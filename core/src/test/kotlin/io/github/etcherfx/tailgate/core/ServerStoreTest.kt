package io.github.etcherfx.tailgate.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class ServerStoreTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `servers round-trip`() {
        val store = ServerStore(File(dir, "config/tailgate/servers.json"))
        assertEquals(emptyList<SavedServer>(), store.load())

        val servers = listOf(
            SavedServer("a", "Alex's \"world\"", FunnelAddress("alex-pc.tail1234.ts.net", 10000), 54321),
            SavedServer("b", "Ünïcødé ✓", FunnelAddress("mc.example.com", 443), 1025),
        )
        store.save(servers)
        val loaded = store.load()
        assertEquals(2, loaded.size)
        for ((expected, actual) in servers.zip(loaded)) {
            assertEquals(expected.id, actual.id)
            assertEquals(expected.name, actual.name)
            assertEquals(expected.address, actual.address)
            assertEquals(expected.localPort, actual.localPort)
        }
        assertTrue(store.file.readText().contains("\"localPort\": 54321"))
    }

    @Test
    fun `corrupt files are reported`() {
        val file = File(dir, "servers.json")
        file.writeText("[{\"id\": 1}")
        assertThrows(IOException::class.java) { ServerStore(file).load() }
        file.writeText("[{\"id\": \"a\", \"name\": \"n\", \"host\": \"10.0.0.1\", \"port\": 1, \"localPort\": 2}]")
        assertThrows(IOException::class.java) { ServerStore(file).load() }
    }

    @Test
    fun `json codec handles escapes and nesting`() {
        val value = linkedMapOf(
            "s" to "line\nbreak \u0001 \\ \"q\" \u2028",
            "n" to listOf(1L, -2L, 3.5, true, false, null),
            "o" to linkedMapOf<String, Any?>(),
        )
        assertEquals(value, Json.parse(Json.write(value)))
        assertEquals(value, Json.parse(Json.writePretty(value)))
        assertEquals("é", Json.parse("\"\\u00e9\""))
        assertThrows(Json.ParseException::class.java) { Json.parse("{\"a\":1,}") }
        assertThrows(Json.ParseException::class.java) { Json.parse("[1] 2") }
    }
}
