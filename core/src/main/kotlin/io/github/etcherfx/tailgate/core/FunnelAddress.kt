package io.github.etcherfx.tailgate.core

import java.net.IDN

/** Where a Funnel publishes a server: a TLS hostname and the Funnel port. */
class FunnelAddress(val host: String, val port: Int) {
    override fun toString(): String = if (port == DEFAULT_PORT) host else "$host:$port"

    override fun equals(other: Any?): Boolean = other is FunnelAddress && other.host == host && other.port == port

    override fun hashCode(): Int = host.hashCode() * 31 + port

    class InvalidException(message: String) : Exception(message)

    companion object {
        const val DEFAULT_PORT = 10000

        /** Parses `host[:port]`; the port defaults to [DEFAULT_PORT]. IP literals are rejected. */
        @Throws(InvalidException::class)
        fun parse(input: String): FunnelAddress {
            val text = input.trim()
            if (text.isEmpty()) throw InvalidException("Enter the address your host sent you, like my-pc.tail1234.ts.net:10000")
            if ("://" in text) throw InvalidException("Enter just the address, without http:// or tcp://")
            if (text.startsWith("[") || text.count { it == ':' } > 1) throw InvalidException(IP_LITERAL)
            if (text.any { it.isWhitespace() || it == '/' || it == '@' }) throw InvalidException("That doesn't look like an address")

            val colon = text.lastIndexOf(':')
            val rawHost = if (colon < 0) text else text.substring(0, colon)
            val port = if (colon < 0) {
                DEFAULT_PORT
            } else {
                val digits = text.substring(colon + 1)
                val value = if (digits.isNotEmpty() && digits.length <= 5 && digits.all { it in '0'..'9' }) digits.toInt() else -1
                if (value !in 1..65535) throw InvalidException("The port must be a number from 1 to 65535")
                value
            }
            return FunnelAddress(parseHost(rawHost), port)
        }

        private const val IP_LITERAL = "Use the host's name (like my-pc.tail1234.ts.net), not an IP address"

        private fun parseHost(raw: String): String {
            if (raw.isEmpty()) throw InvalidException("The address is missing a host name")
            val ascii = try {
                IDN.toASCII(raw.removeSuffix("."), IDN.USE_STD3_ASCII_RULES)
            } catch (e: IllegalArgumentException) {
                throw InvalidException("\"$raw\" isn't a valid host name")
            }.lowercase()
            if (ascii.isEmpty() || ascii.length > 253) throw InvalidException("\"$raw\" isn't a valid host name")

            val labels = ascii.split('.')
            for (label in labels) {
                val valid = label.length in 1..63 &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                    !label.startsWith("-") && !label.endsWith("-")
                if (!valid) throw InvalidException("\"$raw\" isn't a valid host name")
            }
            // A numeric last label means an IPv4 literal (or a shorthand form like 127.1).
            if (labels.last().all { it in '0'..'9' }) throw InvalidException(IP_LITERAL)
            return ascii
        }
    }
}
