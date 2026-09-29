package io.github.etcherfx.tailgate.core

/** Logging seam; the loader dispatcher backs it with the game's log4j2. */
interface TailgateLog {
    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    fun error(message: String, error: Throwable? = null)

    /** Writes to stderr; used by tests and before a dispatcher installs the game's logger. */
    object Stderr : TailgateLog {
        override fun info(message: String) = System.err.println("[Tailgate] $message")

        override fun warn(message: String, error: Throwable?) {
            System.err.println("[Tailgate] WARN $message")
            error?.printStackTrace()
        }

        override fun error(message: String, error: Throwable?) {
            System.err.println("[Tailgate] ERROR $message")
            error?.printStackTrace()
        }
    }
}
