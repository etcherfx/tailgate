package io.github.etcherfx.tailgate.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listens on a loopback port and forwards each Minecraft connection to [target] over TLS.
 *
 * The first packet (the handshake) is rewritten to carry the real Funnel host and port; everything
 * after it is copied byte for byte. If the host can't be reached, the client gets a readable status
 * MOTD or login disconnect instead of a bare connection error.
 */
class Forwarder(
    val target: FunnelAddress,
    private val connector: TlsConnector,
    private val log: TailgateLog,
    private val label: String = target.toString(),
) : Closeable {
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private val connectionIds = AtomicInteger()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var listener: Thread? = null

    val localPort: Int get() = server?.localPort ?: -1

    val isRunning: Boolean get() = server?.isClosed == false

    /**
     * Binds `127.0.0.1:[preferredPort]` (0 picks any free port). If that port is taken and
     * [allowOtherPort] is set, binds any free port instead. Returns the bound port.
     */
    @Throws(IOException::class)
    fun start(preferredPort: Int, allowOtherPort: Boolean = true): Int {
        check(server == null) { "Already started" }
        val socket = try {
            bind(preferredPort)
        } catch (e: IOException) {
            if (!allowOtherPort || preferredPort == 0) throw e
            log.warn("Port $preferredPort for $label is taken; picking another")
            bind(0)
        }
        server = socket
        listener = daemon("Tailgate listener $label") { acceptLoop(socket) }
        log.info("Forwarding 127.0.0.1:${socket.localPort} to $target over TLS")
        return socket.localPort
    }

    /** Stops listening and drops open connections; the port is free again when this returns. */
    override fun close() {
        server?.let { closeQuietly(it) }
        // Linux releases the port only once the accept() blocked on it returns, which can be after
        // close() does; wait for the listener so the port can be bound again straight away.
        listener?.takeIf { it !== Thread.currentThread() }?.let {
            try {
                it.join(CLOSE_WAIT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(sockets) { sockets.toList() }.forEach { closeQuietly(it) }
    }

    private fun bind(port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
            return socket
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!listener.isClosed) {
            val client = try {
                listener.accept()
            } catch (e: IOException) {
                if (!listener.isClosed) log.warn("Listener for $label stopped", e)
                return
            }
            val id = connectionIds.incrementAndGet()
            daemon("Tailgate $label #$id") { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        track(client)
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = TlsConnector.TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            input.mark(1)
            val first = input.read()
            if (first < 0) return
            input.reset()

            val firstPacket: ByteArray
            val handshake: McProtocol.Handshake?
            if (first == McProtocol.LEGACY_PING) {
                firstPacket = ByteArray(0)
                handshake = null
            } else {
                val parsed = McProtocol.parseHandshake(McProtocol.readPacket(input))
                handshake = parsed
                firstPacket = parsed.withTarget(target.host, target.port).encode()
            }

            val tls = try {
                connector.connect(target)
            } catch (e: IOException) {
                val reason = Failures.describe(e)
                log.warn("Can't reach $target: $reason")
                if (handshake != null) refuse(client, input, handshake, "Tailgate: can't reach ${target.host} ($reason)")
                return
            }
            remote = track(tls)
            client.soTimeout = 0

            val upstream = tls.getOutputStream()
            upstream.write(firstPacket)
            upstream.flush()

            val server = remote
            daemon("Tailgate $label upstream") {
                pump(input, upstream)
                closeQuietly(client)
                closeQuietly(server)
            }
            pump(tls.getInputStream(), client.getOutputStream())
        } catch (e: SocketTimeoutException) {
            log.warn("Client on $label sent no handshake in time")
        } catch (e: IOException) {
            if (!client.isClosed) log.warn("Connection on $label failed: ${Failures.describe(e)}")
        } finally {
            closeQuietly(client)
            remote?.let { closeQuietly(it) }
        }
    }

    /** Answers a client the host couldn't serve: a status MOTD or a login disconnect with [message]. */
    private fun refuse(client: Socket, input: InputStream, handshake: McProtocol.Handshake, message: String) {
        val out = client.getOutputStream()
        if (handshake.nextState == McProtocol.STATE_STATUS) {
            while (true) {
                val body = McProtocol.readPacket(input, 64).inputStream()
                when (McProtocol.readVarInt(body)) {
                    0x00 -> out.write(McProtocol.statusResponse(handshake.protocol, message))
                    0x01 -> {
                        out.write(McProtocol.pong(DataInputStream(body).readLong()))
                        out.flush()
                        return
                    }
                    else -> return
                }
                out.flush()
            }
        }
        out.write(McProtocol.loginDisconnect(message))
        out.flush()
        // Let the client read the reason before the socket goes away; closing with unread
        // input can reset the connection and hide the message.
        client.shutdownOutput()
        client.soTimeout = 2_000
        val sink = ByteArray(512)
        try {
            do {
                val n = input.read(sink)
            } while (n >= 0)
        } catch (ignored: IOException) {
        }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) return
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (ignored: IOException) {
            // One side closed; the caller tears down both.
        }
    }

    private fun <T : Socket> track(socket: T): T {
        sockets.add(socket)
        return socket
    }

    private fun closeQuietly(closeable: Closeable) {
        if (closeable is Socket) sockets.remove(closeable)
        try {
            closeable.close()
        } catch (ignored: IOException) {
        }
    }

    private fun daemon(name: String, body: () -> Unit): Thread {
        val thread = Thread(body, name)
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private companion object {
        const val CLOSE_WAIT_MS = 2_000L
    }
}
