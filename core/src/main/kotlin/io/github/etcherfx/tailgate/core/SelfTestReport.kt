package io.github.etcherfx.tailgate.core

import java.io.File

/**
 * CI self-test support. When `-Dtailgate.selftest=<address>` is set, the UI build drives its own
 * screens against that address and records each check here; CI asserts the written file.
 */
class SelfTestReport(private val tailgate: Tailgate) {
    private val checks = LinkedHashMap<String, String>()

    fun pass(check: String) {
        checks[check] = "ok"
        tailgate.log.info("Self-test: $check ok")
    }

    fun fail(check: String, reason: String) {
        checks[check] = "FAIL $reason"
        tailgate.log.error("Self-test: $check failed: $reason")
    }

    /** Writes `tailgate-selftest.txt` next to the server store's config directory. */
    fun write(gameDir: File) {
        val passed = checks.isNotEmpty() && checks.values.all { it == "ok" }
        val text = buildString {
            for ((check, result) in checks) append(check).append('=').append(result).append('\n')
            append("result=").append(if (passed) "pass" else "fail").append('\n')
        }
        File(gameDir, FILE_NAME).writeText(text)
        tailgate.log.info("Self-test ${if (passed) "passed" else "failed"}; wrote $FILE_NAME")
    }

    companion object {
        const val FILE_NAME = "tailgate-selftest.txt"
        const val PROPERTY = "tailgate.selftest"

        /** The address to test against, or null when the self-test is off. */
        @JvmStatic
        val address: String? get() = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }
    }
}
