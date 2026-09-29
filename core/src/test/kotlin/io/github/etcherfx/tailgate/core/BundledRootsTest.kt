package io.github.etcherfx.tailgate.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.cert.CertPathValidator
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The chains were captured from Let's Encrypt's valid-isrgrootx1/x2 test sites. Validation uses the
 * bundled roots alone (no JVM trust store), as on Java 8u51, at a time inside the leaf's validity.
 */
class BundledRootsTest {
    @ParameterizedTest
    @ValueSource(strings = ["letsencrypt-chain-x1.pem", "letsencrypt-chain-x2.pem"])
    fun `bundled ISRG roots alone validate a real Let's Encrypt chain`(resource: String) {
        val chain = load(resource)
        val params = PKIXParameters(TlsConnector.bundledRoots.map { TrustAnchor(it, null) }.toSet())
        params.isRevocationEnabled = false
        params.date = Date(chain.first().notBefore.time + 86_400_000)
        val factory = CertificateFactory.getInstance("X.509")
        val result = CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain), params)
        assertEquals(true, TlsConnector.bundledRoots.contains((result as java.security.cert.PKIXCertPathValidatorResult).trustAnchor.trustedCert))
    }

    @ParameterizedTest
    @ValueSource(strings = ["letsencrypt-chain-x1.pem"])
    fun `a chain doesn't validate against unrelated roots`(resource: String) {
        val chain = load(resource)
        val params = PKIXParameters(setOf(TrustAnchor(TestPki().ca, null)))
        params.isRevocationEnabled = false
        params.date = Date(chain.first().notBefore.time + 86_400_000)
        val factory = CertificateFactory.getInstance("X.509")
        assertThrows(CertPathValidatorException::class.java) {
            CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain), params)
        }
    }

    private fun load(resource: String): List<X509Certificate> {
        val stream = javaClass.getResourceAsStream(resource) ?: error("missing $resource")
        return stream.use { CertificateFactory.getInstance("X.509").generateCertificates(it).map { c -> c as X509Certificate } }
    }
}
