package io.github.etcherfx.tailgate.core

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Turns connection failures into short reasons a player can act on. */
object Failures {
    fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        fun has(type: Class<out Throwable>) = chain.any { type.isInstance(it) }
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()

        return when {
            has(UnknownHostException::class.java) -> "unknown host; check the address, and that the host's Funnel is on"
            has(SocketTimeoutException::class.java) -> "timed out"
            has(ConnectException::class.java) -> "connection refused"
            has(NoRouteToHostException::class.java) -> "no route to host"
            "no subject alternative" in text || "no name matching" in text || "doesn't match" in text ->
                "the host's certificate doesn't match its name"
            has(CertPathValidatorException::class.java) || "unable to find valid certification path" in text ||
                has(CertificateException::class.java) -> "the host's certificate isn't trusted"
            has(SSLPeerUnverifiedException::class.java) -> "the host's certificate couldn't be verified"
            has(SSLHandshakeException::class.java) || has(SSLException::class.java) ->
                "TLS handshake failed; is the host using tailscale funnel --tls-terminated-tcp?"
            has(EOFException::class.java) -> "the host closed the connection"
            has(SocketException::class.java) -> error.message ?: "connection failed"
            else -> error.message ?: error.javaClass.simpleName
        }
    }
}
