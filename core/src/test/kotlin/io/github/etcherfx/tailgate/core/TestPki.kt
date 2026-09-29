package io.github.etcherfx.tailgate.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** A throwaway certificate authority for TLS tests. */
class TestPki(name: String = "Tailgate Test CA") {
    private val caKeys = keyPair()
    val ca: X509Certificate = sign(X500Name("CN=$name"), caKeys, caKeys, X500Name("CN=$name"), isCa = true, dnsName = null)

    /** A server context presenting a leaf for [dnsName] signed by this CA. */
    fun serverContext(dnsName: String): SSLContext {
        val keys = keyPair()
        val leaf = sign(X500Name("CN=$dnsName"), keys, caKeys, X500Name(ca.subjectX500Principal.name), isCa = false, dnsName = dnsName)
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("leaf", keys.private, PASSWORD, arrayOf(leaf, ca))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, PASSWORD)
        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, null, null)
        return context
    }

    /** A connector that trusts only this CA. */
    fun connector() = TlsConnector(TlsConnector.createContext(listOf(ca)).socketFactory)

    private fun sign(
        subject: X500Name,
        subjectKeys: KeyPair,
        issuerKeys: KeyPair,
        issuer: X500Name,
        isCa: Boolean,
        dnsName: String?,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(serials.incrementAndGet()),
            Date(now - 60_000),
            Date(now + 86_400_000),
            subject,
            subjectKeys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
        if (isCa) {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        } else {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        }
        if (dnsName != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, dnsName)))
        }
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKeys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun keyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    companion object {
        private val PASSWORD = "test".toCharArray()
        private val serials = AtomicLong(System.currentTimeMillis())
    }
}
