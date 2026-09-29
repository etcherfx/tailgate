package io.github.etcherfx.tailgate.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FunnelAddressTest {
    @Test
    fun `port defaults to 10000`() {
        val address = FunnelAddress.parse("alex-pc.tail1234.ts.net")
        assertEquals("alex-pc.tail1234.ts.net", address.host)
        assertEquals(10000, address.port)
        assertEquals("alex-pc.tail1234.ts.net", address.toString())
    }

    @Test
    fun `explicit port`() {
        val address = FunnelAddress.parse("  Alex-PC.Tail1234.ts.net.:443 ")
        assertEquals("alex-pc.tail1234.ts.net", address.host)
        assertEquals(443, address.port)
        assertEquals("alex-pc.tail1234.ts.net:443", address.toString())
    }

    @Test
    fun `any TLS hostname is accepted`() {
        assertEquals("mc.example.com", FunnelAddress.parse("mc.example.com:8443").host)
        assertEquals("localhost", FunnelAddress.parse("localhost").host)
    }

    @ParameterizedTest
    @ValueSource(strings = ["127.0.0.1", "100.101.102.103:10000", "[::1]:10000", "::1", "fe80::1", "127.1"])
    fun `IP literals are rejected`(input: String) {
        val e = assertThrows(FunnelAddress.InvalidException::class.java) { FunnelAddress.parse(input) }
        assertEquals(true, e.message!!.contains("not an IP address"), e.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", ":10000", "host:", "host:0", "host:65536", "host:abc", "https://host.ts.net", "bad_host.ts.net", "-a.ts.net", "a..b", "a b.ts.net", "user@host"])
    fun `malformed addresses are rejected`(input: String) {
        assertThrows(FunnelAddress.InvalidException::class.java) { FunnelAddress.parse(input) }
    }
}
