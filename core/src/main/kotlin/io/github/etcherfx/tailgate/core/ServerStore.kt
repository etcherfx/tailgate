package io.github.etcherfx.tailgate.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A Funnel server the player added, and the loopback port its forwarder listens on. */
class SavedServer(
    val id: String,
    val name: String,
    val address: FunnelAddress,
    val localPort: Int,
) {
    /** What the vanilla server list stores for this server. */
    val localAddress: String get() = "127.0.0.1:$localPort"

    fun withLocalPort(port: Int) = SavedServer(id, name, address, port)
}

/** Reads and writes `servers.json`: `[{id, name, host, port, localPort}]`. */
class ServerStore(val file: File) {
    @Throws(IOException::class)
    fun load(): List<SavedServer> {
        if (!file.exists()) return emptyList()
        val text = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        val root = try {
            Json.parse(text)
        } catch (e: Json.ParseException) {
            throw IOException("${file.name} is corrupt: ${e.message}", e)
        }
        val entries = root as? List<*> ?: throw IOException("${file.name} should contain a JSON array")
        return entries.map { entry ->
            val map = entry as? Map<*, *> ?: throw IOException("${file.name} has a non-object entry")
            fun string(key: String) = map[key] as? String ?: throw IOException("${file.name}: entry is missing \"$key\"")
            fun int(key: String) = (map[key] as? Long)?.toInt() ?: throw IOException("${file.name}: entry is missing \"$key\"")
            val host = string("host")
            val port = int("port")
            val address = try {
                FunnelAddress.parse("$host:$port")
            } catch (e: FunnelAddress.InvalidException) {
                throw IOException("${file.name}: bad address $host:$port (${e.message})")
            }
            SavedServer(string("id"), string("name"), address, int("localPort"))
        }
    }

    @Throws(IOException::class)
    fun save(servers: List<SavedServer>) {
        val json = Json.writePretty(
            servers.map {
                linkedMapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "host" to it.address.host,
                    "port" to it.address.port,
                    "localPort" to it.localPort,
                )
            },
        )
        val dir = file.absoluteFile.parentFile
        Files.createDirectories(dir.toPath())
        val temp = File(dir, file.name + ".tmp")
        Files.write(temp.toPath(), json.toByteArray(Charsets.UTF_8))
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
