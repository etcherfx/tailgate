package io.github.etcherfx.tailgate.core

import java.io.File
import java.security.KeyStore

/**
 * The CI TLS fixture for the in-game self-test: a [FakeFunnel] for `localhost` whose CA is written
 * to a JKS trust store, which the game trusts through `-Djavax.net.ssl.trustStore`.
 *
 * Usage: `SelfTestFixture <output dir>`; writes `truststore.jks` (password `changeit`) and
 * `address.txt`, then serves until killed.
 */
object SelfTestFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrElse(0) { "build/selftest-fixture" }).absoluteFile
        dir.mkdirs()
        val pki = TestPki("Tailgate Self-Test CA")
        val store = KeyStore.getInstance("JKS")
        store.load(null, null)
        store.setCertificateEntry("tailgate-selftest", pki.ca)
        File(dir, "truststore.jks").outputStream().use { store.store(it, "changeit".toCharArray()) }
        val funnel = FakeFunnel(pki.serverContext("localhost"))
        File(dir, "address.txt").writeText("localhost:${funnel.port}")
        println("Self-test fixture serving localhost:${funnel.port}; trust store in $dir")
        Thread.currentThread().join()
    }
}
