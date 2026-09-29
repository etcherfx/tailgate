package io.github.etcherfx.tailgate.core

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Owns the saved Funnel servers and their forwarders. One instance per game, created by the
 * loader dispatcher through [boot]; the version-specific UI talks to it through [get].
 */
class Tailgate(
    val store: ServerStore,
    val log: TailgateLog,
    private val connector: TlsConnector = TlsConnector(),
    val gameDir: File = store.file.absoluteFile.parentFile,
) {
    private val lock = Any()
    private val servers = LinkedHashMap<String, SavedServer>()
    private val forwarders = HashMap<String, Forwarder>()

    /** Server-list addresses that moved because their port was taken at startup: old → new. */
    private val moved = LinkedHashMap<String, String>()

    /** Loads `servers.json` and starts a forwarder per server. */
    fun start() {
        val saved = try {
            store.load()
        } catch (e: IOException) {
            log.error("Couldn't read ${store.file}; starting with no Tailgate servers", e)
            emptyList()
        }
        var changed = false
        synchronized(lock) {
            for (server in saved) {
                val forwarder = Forwarder(server.address, connector, log, server.name)
                val port = try {
                    forwarder.start(server.localPort, allowOtherPort = true)
                } catch (e: IOException) {
                    log.error("Couldn't start the forwarder for ${server.name}", e)
                    continue
                }
                forwarders[server.id] = forwarder
                servers[server.id] = if (port == server.localPort) {
                    server
                } else {
                    moved[server.localAddress] = "127.0.0.1:$port"
                    changed = true
                    server.withLocalPort(port)
                }
            }
        }
        if (changed) persist()
    }

    fun servers(): List<SavedServer> = synchronized(lock) { servers.values.toList() }

    /** Saves a server and starts its forwarder; the caller adds the server-list entry. */
    @Throws(IOException::class)
    fun add(name: String, address: FunnelAddress): SavedServer {
        val forwarder = Forwarder(address, connector, log, name)
        val port = forwarder.start(0)
        val server = SavedServer(UUID.randomUUID().toString(), name, address, port)
        synchronized(lock) {
            servers[server.id] = server
            forwarders[server.id] = forwarder
        }
        try {
            persist(rethrow = true)
        } catch (e: IOException) {
            remove(server.id)
            throw e
        }
        return server
    }

    /**
     * Brings saved servers in line with the vanilla server list, whose entries' addresses are
     * [entryAddresses]. Returns the entries to rewrite (old → new address) because their port
     * changed at startup; after applying those, servers without an entry are removed.
     */
    fun reconcile(entryAddresses: Collection<String>): Map<String, String> {
        val rewrites: Map<String, String>
        val orphans: List<SavedServer>
        synchronized(lock) {
            rewrites = moved.filterKeys { it in entryAddresses }
            moved.clear()
            val present = entryAddresses.map { rewrites[it] ?: it }.toSet()
            orphans = servers.values.filter { it.localAddress !in present }
        }
        for (orphan in orphans) {
            log.info("Removing ${orphan.name} (${orphan.address}); its server-list entry was deleted")
            remove(orphan.id)
        }
        if (orphans.isNotEmpty()) persist()
        return rewrites
    }

    /** Runs [StatusPing] on a background thread and reports on it; callers hop to their UI thread. */
    fun test(address: FunnelAddress, done: (TestResult) -> Unit) {
        val thread = Thread({
            val result = try {
                TestResult(true, "Reachable: ${StatusPing.ping(address, connector)}")
            } catch (e: Exception) {
                TestResult(false, "Can't reach ${address.host}: ${Failures.describe(e)}")
            }
            done(result)
        }, "Tailgate test $address")
        thread.isDaemon = true
        thread.start()
    }

    class TestResult(val ok: Boolean, val message: String)

    fun stop() {
        synchronized(lock) { forwarders.values.toList() }.forEach { it.close() }
    }

    private fun remove(id: String) {
        val forwarder = synchronized(lock) {
            servers.remove(id)
            forwarders.remove(id)
        }
        forwarder?.close()
    }

    private fun persist(rethrow: Boolean = false) {
        try {
            store.save(servers())
        } catch (e: IOException) {
            if (rethrow) throw e
            log.error("Couldn't save ${store.file}", e)
        }
    }

    companion object {
        @Volatile
        private var instance: Tailgate? = null

        /** Starts Tailgate for the game in [gameDir]; later calls return the running instance. */
        @JvmStatic
        fun boot(gameDir: File, log: TailgateLog): Tailgate = synchronized(this) {
            instance ?: Tailgate(ServerStore(File(gameDir, "config/tailgate/servers.json")), log, gameDir = gameDir).also {
                it.start()
                instance = it
            }
        }

        @JvmStatic
        fun get(): Tailgate = instance ?: error("Tailgate hasn't booted")

        @JvmStatic
        fun getOrNull(): Tailgate? = instance
    }
}
