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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Stands in for Tailscale Funnel with `--tls-terminated-tcp`: terminates TLS on a loopback port
 * and pipes plaintext to a fake Minecraft backend.
 */
class FakeFunnel(context: SSLContext) : Closeable {
    val backend = FakeMinecraftServer()
    private val tls = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val port: Int get() = tls.localPort

    init {
        thread("fake funnel accept") {
            while (!tls.isClosed) {
                val client = try {
                    tls.accept()
                } catch (e: IOException) {
                    return@thread
                }
                thread("fake funnel conn") {
                    val upstream = Socket()
                    try {
                        upstream.connect(InetSocketAddress("127.0.0.1", backend.port))
                        thread("fake funnel up") { pipe(client.getInputStream(), upstream.getOutputStream()); upstream.close() }
                        pipe(upstream.getInputStream(), client.getOutputStream())
                    } catch (ignored: IOException) {
                    } finally {
                        client.close()
                        upstream.close()
                    }
                }
            }
        }
    }

    override fun close() {
        tls.close()
        backend.close()
    }
}

/**
 * Answers the Minecraft status flow, and in the login state echoes every byte after the
 * handshake so tests can check the stream is copied verbatim.
 */
class FakeMinecraftServer : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val handshakes = LinkedBlockingQueue<McProtocol.Handshake>()

    init {
        thread("fake mc accept") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    return@thread
                }
                thread("fake mc conn") { socket.use { serve(it) } }
            }
        }
    }

    fun nextHandshake(): McProtocol.Handshake = handshakes.poll(10, TimeUnit.SECONDS) ?: error("no handshake received")

    private fun serve(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val handshake = McProtocol.parseHandshake(McProtocol.readPacket(input))
            handshakes.add(handshake)
            if (handshake.nextState == McProtocol.STATE_STATUS) {
                while (true) {
                    val body = McProtocol.readPacket(input).inputStream()
                    when (McProtocol.readVarInt(body)) {
                        0x00 -> out.write(McProtocol.packet(0x00) { McProtocol.writeString(it, STATUS_JSON) })
                        0x01 -> {
                            out.write(McProtocol.pong(DataInputStream(body).readLong()))
                            out.flush()
                            return
                        }
                    }
                    out.flush()
                }
            } else {
                pipe(input, out)
            }
        } catch (ignored: IOException) {
        }
    }

    override fun close() = server.close()

    companion object {
        const val STATUS_JSON =
            """{"version":{"name":"Fake 26.3","protocol":775},"players":{"max":20,"online":3},"description":{"text":"hi"}}"""
    }
}

fun pipe(from: InputStream, to: OutputStream) {
    val buffer = ByteArray(8192)
    try {
        while (true) {
            val n = from.read(buffer)
            if (n < 0) return
            to.write(buffer, 0, n)
            to.flush()
        }
    } catch (ignored: IOException) {
    }
}

fun thread(name: String, body: () -> Unit): Thread = Thread(body, name).apply {
    isDaemon = true
    start()
}
