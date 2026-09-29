package io.github.etcherfx.tailgate.core

import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Opens verified TLS connections to a Funnel host.
 *
 * Trust is the JVM's default trust store plus the bundled ISRG roots, because the Java 8u51 that
 * Mojang bundled with older launchers predates both. There's deliberately no way to skip verification.
 */
class TlsConnector(private val factory: SSLSocketFactory = defaultContext.socketFactory) {
    @Throws(java.io.IOException::class)
    fun connect(address: FunnelAddress): SSLSocket {
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(address.host, address.port), TIMEOUT_MS)
            val socket = factory.createSocket(raw, address.host, address.port, true) as SSLSocket
            val params = socket.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            params.serverNames = listOf(SNIHostName(address.host))
            params.protocols = socket.supportedProtocols.filter { it in PROTOCOLS }.toTypedArray()
            socket.sslParameters = params
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()
            socket.soTimeout = 0
            return socket
        } catch (e: Throwable) {
            try {
                raw.close()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000

        private val PROTOCOLS = setOf("TLSv1.3", "TLSv1.2")

        private val ROOTS = listOf("isrg-root-x1.pem", "isrg-root-x2.pem")

        /** ISRG Root X1 and X2, as bundled in the jar. */
        val bundledRoots: List<X509Certificate> by lazy {
            val certificates = CertificateFactory.getInstance("X.509")
            ROOTS.map { name ->
                val stream = TlsConnector::class.java.getResourceAsStream(name)
                    ?: error("Missing bundled certificate $name")
                stream.use { certificates.generateCertificate(it) as X509Certificate }
            }
        }

        /** JVM default trust (including `javax.net.ssl.trustStore` overrides) plus [bundledRoots]. */
        val defaultContext: SSLContext by lazy { createContext(defaultTrustedCertificates() + bundledRoots) }

        fun createContext(trusted: Collection<X509Certificate>): SSLContext {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            trusted.forEachIndexed { i, cert -> store.setCertificateEntry("tailgate-$i", cert) }
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagers.init(store)
            val context = SSLContext.getInstance("TLS")
            context.init(null, trustManagers.trustManagers, null)
            return context
        }

        private fun defaultTrustedCertificates(): List<X509Certificate> {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.asList() }
        }
    }
}
