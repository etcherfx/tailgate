package io.github.etcherfx.tailgate.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException

/** Checks a Funnel host end to end: TLS connect, then a Minecraft status request and ping. */
object StatusPing {
    class Result(val versionName: String, val onlinePlayers: Int, val maxPlayers: Int, val latencyMs: Long) {
        override fun toString() = "$versionName, $onlinePlayers/$maxPlayers players, $latencyMs ms"
    }

    private const val MAX_RESPONSE = 4 * 1024 * 1024

    @Throws(IOException::class)
    fun ping(address: FunnelAddress, connector: TlsConnector): Result {
        connector.connect(address).use { socket ->
            socket.soTimeout = TlsConnector.TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())

            out.write(McProtocol.Handshake(-1, address.host, address.port, McProtocol.STATE_STATUS).encode())
            out.write(McProtocol.packet(0x00) {})
            out.flush()

            val response = McProtocol.readPacket(input, MAX_RESPONSE).inputStream()
            if (McProtocol.readVarInt(response) != 0x00) throw McProtocol.ProtocolException("Unexpected status reply")
            val json = try {
                Json.parse(McProtocol.readString(response, MAX_RESPONSE))
            } catch (e: Json.ParseException) {
                throw McProtocol.ProtocolException("Unreadable status reply")
            } as? Map<*, *> ?: throw McProtocol.ProtocolException("Unreadable status reply")

            val sent = System.nanoTime()
            out.write(McProtocol.packet(0x01) { o -> for (shift in 56 downTo 0 step 8) o.write((sent ushr shift).toInt() and 0xFF) })
            out.flush()
            val pong = McProtocol.readPacket(input, 64).inputStream()
            if (McProtocol.readVarInt(pong) != 0x01) throw McProtocol.ProtocolException("Unexpected ping reply")
            DataInputStream(pong).readLong()
            val latency = (System.nanoTime() - sent) / 1_000_000

            val version = json["version"] as? Map<*, *>
            val players = json["players"] as? Map<*, *>
            return Result(
                versionName = version?.get("name") as? String ?: "unknown version",
                onlinePlayers = (players?.get("online") as? Number)?.toInt() ?: 0,
                maxPlayers = (players?.get("max") as? Number)?.toInt() ?: 0,
                latencyMs = latency,
            )
        }
    }
}
